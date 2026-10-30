package com.cinesync.ticketing.adapter.in.rabbitmq;

import com.cinesync.ticketing.core.model.PendingConcessionOrder;
import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.model.UserContact;
import com.cinesync.ticketing.core.port.in.BookingConfirmation;
import com.cinesync.ticketing.core.port.in.TicketingCommandUseCase;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import com.cinesync.ticketing.core.port.out.PendingConcessionOrderRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import com.cinesync.ticketing.core.port.out.TicketingFulfillmentRepository;
import com.cinesync.ticketing.core.port.out.UserContactRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketingCommandService implements TicketingCommandUseCase {

    private final UserContactRepository userContactRepository;
    private final TicketRepository ticketRepository;
    private final TicketingFulfillmentRepository fulfillmentRepository;
    private final InboxEventRepository inboxEventRepository;
    private final PendingConcessionOrderRepository pendingConcessionOrderRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void handleUserRegistered(UUID userId, String email, Instant occurredAt) {
        log.info("Processing UserRegistered: userId={}, occurredAt={}", userId, occurredAt);
        upsertContact(userId, email, occurredAt);
    }

    @Override
    @Transactional
    public void handleUserEmailChanged(UUID userId, String email, Instant occurredAt) {
        log.info("Processing UserEmailChanged: userId={}, occurredAt={}", userId, occurredAt);
        upsertContact(userId, email, occurredAt);
    }

    // Either event can be the first one seen for a user, so both are an upsert by userId.
    private void upsertContact(UUID userId, String email, Instant occurredAt) {
        userContactRepository.findByUserId(userId).ifPresentOrElse(
                contact -> {
                    if (contact.applyChange(email, occurredAt)) {
                        userContactRepository.save(contact);
                    } else {
                        log.info("Ignoring stale contact event: userId={}, occurredAt={}", userId, occurredAt);
                    }
                },
                () -> userContactRepository.save(UserContact.of(userId, email, occurredAt)));
    }

    @Override
    @Transactional
    public void handleBookingConfirmed(BookingConfirmation booking, String correlationId) {
        UUID reservationId = booking.reservationId();
        log.info("Processing BookingConfirmed: reservationId={}, correlationId={}", reservationId, correlationId);

        if (ticketRepository.findByReservationId(reservationId).isPresent()) {
            log.info("Ticket already exists for reservationId: {}", reservationId);
            return;
        }

        // The contact may arrive after the booking: the ticket is stored anyway and the delivery stays
        // PENDING until the contact can be resolved by user_id (ADR-057).
        UUID contactId = userContactRepository.findByUserId(booking.userId()).map(UserContact::getId).orElse(null);
        if (contactId == null) {
            log.info("No contact yet for the user of reservationId: {}, delivery stays pending", reservationId);
        }

        String qrPayload = "CSP-" + reservationId + "-" + String.join("-", booking.seatNumbers());

        Ticket ticket = Ticket.builder()
                .userId(booking.userId())
                .userContactId(contactId)
                .reservationId(reservationId)
                .showtimeId(booking.showtimeId())
                .movieTitleSnapshot(booking.movieTitle())
                .roomNameSnapshot(booking.roomName())
                .showtimeStartsAt(booking.showtimeStartsAt())
                .seatLabelsSnapshot(booking.seatNumbers())
                .qrPayload(qrPayload)
                .build();

        ticket = ticketRepository.save(ticket);

        // Merge any pending concessions that arrived before BookingConfirmed (order doesn't matter)
        mergePendingConcessions(reservationId, ticket);

        TicketingFulfillment fulfillment = TicketingFulfillment.builder()
                .ticketId(ticket.getId())
                .channel("EMAIL")
                .status(TicketingFulfillment.FulfillmentStatus.PENDING)
                .attempts(0)
                .build();
        fulfillmentRepository.save(fulfillment);

        // Do NOT generate PDF, send email, or publish TicketIssued here.
        // The worker will generate the PDF, upload it, and call the internal endpoint
        // to provide the pdfUrl, at which point we'll publish TicketIssued.
    }

    @Override
    @Transactional
    public void handleConcessionOrderConfirmed(UUID reservationId, String itemsJson, String eventId, String correlationId) {
        log.info("Processing ConcessionOrderConfirmed: reservationId={}, eventId={}, correlationId={}", reservationId, eventId, correlationId);

        try {
            List<Ticket.ConcessionItem> items = parseConcessionItems(itemsJson);

            if (items.isEmpty()) {
                log.warn("Parsed concession items are empty for reservationId: {}, eventId: {}", reservationId, eventId);
                // Throw exception to trigger retry/DLQ - do NOT store empty as pending
                throw new IllegalArgumentException("Concession items parsed to empty list");
            }

            int updated = ticketRepository.updateConcessionsSnapshotByReservationId(reservationId, items);
            if (updated > 0) {
                log.info("Updated concessions snapshot for reservationId: {}", reservationId);
                // If there was a pending order, clean it up since we successfully applied the concessions
                pendingConcessionOrderRepository.deleteByReservationId(reservationId);
            } else {
                // ORDER DOESN'T MATTER: If ticket doesn't exist yet (BookingConfirmed hasn't arrived),
                // store concessions as pending to be merged when ticket is created via BookingConfirmed
                log.info("No ticket found for reservationId: {} yet, storing pending concessions for later merge", reservationId);
                storePendingConcessions(reservationId, itemsJson, eventId, correlationId);
            }
        } catch (Exception e) {
            log.error("Failed to parse or process concessions items: {}", e.getMessage(), e);
            // Re-throw as runtime exception to let Spring RabbitMQ retry handling
            // Max 3 attempts with growing delay, then DLQ per ADR-007
            throw new RuntimeException("Invalid concessions items JSON", e);
        }
    }

    /** Parse concession items from JSON payload, mapping field names to Ticket.ConcessionItem */
    /** Payload can be: {"items": [...]} or raw array [...]
     *  Fields mapped: itemName -> name, unitPrice -> unitPriceCents, quantity -> quantity */
    private List<Ticket.ConcessionItem> parseConcessionItems(String itemsJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(itemsJson);

            // Handle both formats: {"items": [...]} or raw array [...]
            List<Map<String, JsonNode>> itemLists = new ArrayList<>();

            if (rootNode.isArray()) {
                // Raw array format: [{"itemName": "Popcorn", ...}, {"itemName": "Combo", ...}]
                for (JsonNode itemNode : rootNode) {
                    if (itemNode.isObject()) {
                        Map<String, JsonNode> itemMap = new HashMap<>();
                        Iterator<Map.Entry<String, JsonNode>> fields = itemNode.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> field = fields.next();
                            itemMap.put(field.getKey(), field.getValue());
                        }
                        itemLists.add(itemMap);
                    }
                }
            } else if (rootNode.has("items") && rootNode.get("items").isArray()) {
                // Object with "items" key format: {"items": [{"itemName": "Popcorn", ...}, ...]}
                JsonNode itemsNode = rootNode.get("items");
                for (JsonNode itemNode : itemsNode) {
                    if (itemNode.isObject()) {
                        Map<String, JsonNode> itemMap = new HashMap<>();
                        Iterator<Map.Entry<String, JsonNode>> fields = itemNode.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> field = fields.next();
                            itemMap.put(field.getKey(), field.getValue());
                        }
                        itemLists.add(itemMap);
                    }
                }
            } else {
                // Single item format: {"itemName": "Popcorn", ...}
                if (rootNode.isObject()) {
                    Map<String, JsonNode> itemMap = new HashMap<>();
                    Iterator<Map.Entry<String, JsonNode>> fields = rootNode.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        itemMap.put(field.getKey(), field.getValue());
                    }
                    itemLists.add(itemMap);
                }
            }

            return itemLists.stream().map(item -> {
                Ticket.ConcessionItem concessionItem = new Ticket.ConcessionItem();

                // Map itemName -> name (may be "itemName" or "name" in payload)
                String name = null;
                if (item.containsKey("itemName")) {
                    name = item.get("itemName").asText();
                } else if (item.containsKey("name")) {
                    name = item.get("name").asText();
                }
                concessionItem.setName(name);

                // Map quantity (default to 1 if not present)
                int quantity = 1;
                if (item.containsKey("quantity")) {
                    JsonNode qtyNode = item.get("quantity");
                    if (qtyNode.isNumber()) {
                        quantity = qtyNode.intValue();
                    } else if (qtyNode.isTextual()) {
                        try {
                            quantity = Integer.parseInt(qtyNode.asText());
                        } catch (NumberFormatException e) {
                            quantity = 1;
                        }
                    }
                }
                concessionItem.setQuantity(quantity);

                // Map unitPrice -> unitPriceCents (both in cents, just rename)
                int price = 0;
                if (item.containsKey("unitPrice")) {
                    price = item.get("unitPrice").asInt();
                } else if (item.containsKey("unitPriceCents")) {
                    price = item.get("unitPriceCents").asInt();
                }
                concessionItem.setUnitPriceCents(price);

                return concessionItem;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to parse concession items JSON: {}", e.getMessage());
            throw new IllegalArgumentException("Failed to parse concession items JSON", e);
        }
    }

    /** Store pending concessions in database (will be merged when BookingConfirmed arrives) */
    private void storePendingConcessions(UUID reservationId, String itemsJson, String eventId, String correlationId) {
        PendingConcessionOrder pending = PendingConcessionOrder.builder()
                .reservationId(reservationId)
                .itemsJson(itemsJson)
                .eventId(eventId)
                .correlationId(correlationId)
                .build();
        pendingConcessionOrderRepository.save(pending);
        log.info("Stored pending concessions in DB for reservationId: {}", reservationId);
    }

    /** Merge pending concessions from database into newly created ticket */
    private void mergePendingConcessions(UUID reservationId, Ticket ticket) {
        Optional<PendingConcessionOrder> pendingOpt = pendingConcessionOrderRepository.findByReservationId(reservationId);
        if (pendingOpt.isPresent()) {
            PendingConcessionOrder pending = pendingOpt.get();
            try {
                List<Ticket.ConcessionItem> items = parseConcessionItems(pending.getItemsJson());
                if (!items.isEmpty()) {
                    ticket.setConcessionsSnapshot(items);
                    ticketRepository.save(ticket);
                    log.info("Merged {} pending concession items into ticket for reservationId: {}", items.size(), reservationId);
                    // Only delete on successful merge
                    pendingConcessionOrderRepository.deleteByReservationId(reservationId);
                } else {
                    log.warn("Pending concessions JSON parsed to empty items for reservationId: {}, keeping pending for retry", reservationId);
                    // Do NOT delete - keep pending for potential retry
                }
            } catch (Exception e) {
                log.error("Failed to parse pending concessions JSON for reservationId: {}, keeping pending for retry", reservationId, e);
                // Do NOT delete on parse failure - keep pending for potential retry
            }
        } else {
            log.info("No pending concessions found in DB for reservationId: {}", reservationId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Ticket getOrCreateTicketForReservation(UUID reservationId) {
        return ticketRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new IllegalStateException("Ticket not found for reservation: " + reservationId));
    }
}