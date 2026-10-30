package com.cinesync.ticketing.adapter.in.rabbitmq;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.model.UserContact;
import com.cinesync.ticketing.core.port.in.TicketingCommandUseCase;
import com.cinesync.ticketing.core.port.out.EventPublisherPort;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import com.cinesync.ticketing.core.port.out.TicketingFulfillmentRepository;
import com.cinesync.ticketing.core.port.out.UserContactRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketingCommandService implements TicketingCommandUseCase {

    private final UserContactRepository userContactRepository;
    private final TicketRepository ticketRepository;
    private final TicketingFulfillmentRepository fulfillmentRepository;
    private final InboxEventRepository inboxEventRepository;
    private final EventPublisherPort eventPublisherPort;
    private final ObjectMapper objectMapper;

    // Store pending concessions for reservations that don't have a ticket yet (order doesn't matter)
    private final ConcurrentHashMap<UUID, List<Ticket.ConcessionItem>> pendingConcessions = new ConcurrentHashMap<>();

    @Override
    @Transactional
    public void handleUserRegistered(UUID userId, String email, Instant occurredAt) {
        log.info("Processing UserRegistered: userId={}, email={}, occurredAt={}", userId, email, occurredAt);
        UserContact contact = userContactRepository.findByUserId(userId)
                .orElseGet(() -> UserContact.builder()
                        .userId(userId)
                        .email(email)
                        .lastUpdatedAt(occurredAt)
                        .build());
        contact.updateEmail(email, occurredAt);
        userContactRepository.save(contact);
    }

    @Override
    @Transactional
    public void handleUserEmailChanged(UUID userId, String email, Instant occurredAt) {
        log.info("Processing UserEmailChanged: userId={}, email={}, occurredAt={}", userId, email, occurredAt);
        UserContact contact = userContactRepository.findByUserId(userId)
                .orElseGet(() -> UserContact.builder()
                        .userId(userId)
                        .email(email)
                        .lastUpdatedAt(occurredAt)
                        .build());
        contact.updateEmail(email, occurredAt);
        userContactRepository.save(contact);
    }

    @Override
    @Transactional
    public void handleBookingConfirmed(UUID reservationId, UUID userId, UUID showtimeId,
                                       String movieTitle, String roomName, String showtimeStartsAt,
                                       String seatNumbersJson, String correlationId) {
        log.info("Processing BookingConfirmed: reservationId={}, correlationId={}", reservationId, correlationId);

        UserContact contact = userContactRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("User contact not found for userId: " + userId));

        Ticket existingTicket = ticketRepository.findByReservationId(reservationId).orElse(null);
        if (existingTicket != null) {
            log.info("Ticket already exists for reservationId: {}", reservationId);
            return;
        }

        List<String> seatLabels;
        try {
            seatLabels = objectMapper.readValue(seatNumbersJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid seatNumbers JSON", e);
        }

        String qrPayload = "CSP-" + reservationId + "-" + String.join("-", seatLabels);

        Ticket ticket = Ticket.builder()
                .userContactId(contact.getId())
                .reservationId(reservationId)
                .showtimeId(showtimeId)
                .movieTitleSnapshot(movieTitle)
                .roomNameSnapshot(roomName)
                .showtimeStartsAt(Instant.parse(showtimeStartsAt))
                .seatLabelsSnapshot(seatLabels)
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

            int updated = ticketRepository.updateConcessionsSnapshotByReservationId(reservationId, items);
            if (updated > 0) {
                log.info("Updated concessions snapshot for reservationId: {}", reservationId);
            } else {
                // ORDER DOESN'T MATTER: If ticket doesn't exist yet (BookingConfirmed hasn't arrived),
                // store concessions as pending to be merged when ticket is created via BookingConfirmed
                log.info("No ticket found for reservationId: {} yet, storing pending concessions for later merge", reservationId);
                pendingConcessions.put(reservationId, items);
            }
        } catch (Exception e) {
            log.error("Failed to parse concessions items: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Invalid concessions items JSON", e);
        }
    }

    /** Parse concession items from JSON payload, mapping field names to Ticket.ConcessionItem */
    /** Payload fields: itemType, itemId, itemName, unitPrice (cents), quantity */
    /** Ticket.ConcessionItem fields: name, quantity, unitPriceCents */
    private List<Ticket.ConcessionItem> parseConcessionItems(String itemsJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            // Read as generic map to allow field mapping
            Map<String, Object> itemMap = mapper.readValue(itemsJson, new TypeReference<Map<String, Object>>() {});

            // Get the items list - could be "items" key or direct array
            List<Map<String, Object>> itemLists;
            Object itemsObj = itemMap.get("items");
            if (itemsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
                itemLists = items;
            } else {
                // If no "items" key, treat the whole map as a single item map
                itemLists = Collections.singletonList(itemMap);
            }

            return itemLists.stream().map(item -> {
                Ticket.ConcessionItem concessionItem = new Ticket.ConcessionItem();
                // Map itemName -> name (may be "itemName" or "name" in payload)
                String name = (String) item.get("itemName");
                if (name == null) {
                    name = (String) item.get("name");
                }
                concessionItem.setName(name);

                // Map quantity
                Object quantityObj = item.get("quantity");
                int quantity = 1;
                if (quantityObj != null) {
                    if (quantityObj instanceof Number) {
                        quantity = ((Number) quantityObj).intValue();
                    } else {
                        try {
                            quantity = Integer.parseInt(quantityObj.toString());
                        } catch (NumberFormatException e) {
                            quantity = 1;
                        }
                    }
                }
                concessionItem.setQuantity(quantity);

                // Map unitPrice -> unitPriceCents (both in cents, just rename)
                Object priceObj = item.get("unitPrice");
                if (priceObj == null) {
                    priceObj = item.get("unitPriceCents");
                }
                int price = 0;
                if (priceObj != null) {
                    if (priceObj instanceof Number) {
                        price = ((Number) priceObj).intValue();
                    } else {
                        try {
                            price = Integer.parseInt(priceObj.toString());
                        } catch (NumberFormatException e) {
                            price = 0;
                        }
                    }
                }
                concessionItem.setUnitPriceCents(price);

                return concessionItem;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to parse concession items JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Merge pending concessions into newly created ticket */
    private void mergePendingConcessions(UUID reservationId, Ticket ticket) {
        List<Ticket.ConcessionItem> pending = pendingConcessions.remove(reservationId);
        if (pending != null && !pending.isEmpty()) {
            ticket.setConcessionsSnapshot(pending);
            ticketRepository.save(ticket);
            log.info("Merged {} pending concession items into ticket for reservationId: {}", pending.size(), reservationId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Ticket getOrCreateTicketForReservation(UUID reservationId) {
        return ticketRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new IllegalStateException("Ticket not found for reservation: " + reservationId));
    }
}