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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

        // 1. DEDUP: If already processed, skip (using tryInsert pattern from inbox_event)
        UUID eventIdUuid = UUID.fromString(eventId);
        if (!inboxEventRepository.tryInsert(eventIdUuid, "ConcessionOrderConfirmed")) {
            log.info("ConcessionOrderConfirmed already processed: eventId={}", eventId);
            return;
        }

        try {
            List<Ticket.ConcessionItem> items = objectMapper.readValue(itemsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Ticket.ConcessionItem.class));

            int updated = ticketRepository.updateConcessionsSnapshotByReservationId(reservationId, items);
            if (updated > 0) {
                log.info("Updated concessions snapshot for reservationId: {}", reservationId);
            } else {
                // 3. ORDER DOESN'T MATTER: If ticket doesn't exist yet (BookingConfirmed hasn't arrived),
                //    the snapshot will be merged when the ticket is created via BookingConfirmed
                log.info("No ticket found for reservationId: {} yet, concessions snapshot will be merged on ticket creation", reservationId);
            }
        } catch (Exception e) {
            log.error("Failed to parse concessions items: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Invalid concessions items JSON", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Ticket getOrCreateTicketForReservation(UUID reservationId) {
        return ticketRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new IllegalStateException("Ticket not found for reservation: " + reservationId));
    }
}