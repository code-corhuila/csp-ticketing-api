package com.cinesync.ticketing.adapter.in.rabbitmq;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.model.UserContact;
import com.cinesync.ticketing.core.port.in.TicketingCommandUseCase;
import com.cinesync.ticketing.core.port.out.EmailSenderPort;
import com.cinesync.ticketing.core.port.out.EventPublisherPort;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import com.cinesync.ticketing.core.port.out.PdfGeneratorPort;
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
    private final EmailSenderPort emailSenderPort;
    private final PdfGeneratorPort pdfGeneratorPort;
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
                                       String seatNumbersJson) {
        log.info("Processing BookingConfirmed: reservationId={}", reservationId);

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

        byte[] pdfBytes = pdfGeneratorPort.generateTicketPdf(ticket);
        String pdfUrl = "s3://tickets/" + ticket.getId() + ".pdf";
        ticket.setPdfUrl(pdfUrl);
        ticketRepository.save(ticket);

        TicketingFulfillment fulfillment = TicketingFulfillment.builder()
                .ticketId(ticket.getId())
                .channel("EMAIL")
                .status(TicketingFulfillment.FulfillmentStatus.PENDING)
                .attempts(0)
                .build();
        fulfillmentRepository.save(fulfillment);

        try {
            emailSenderPort.sendTicketEmail(
                    contact.getEmail(),
                    "Your CineSync Ticket for " + movieTitle,
                    buildEmailHtml(ticket),
                    pdfBytes,
                    "ticket-" + ticket.getId() + ".pdf"
            );
            fulfillment.setStatus(TicketingFulfillment.FulfillmentStatus.SENT);
        } catch (Exception e) {
            log.error("Email send failed for ticket {}: {}", ticket.getId(), e.getMessage());
            fulfillment.setStatus(TicketingFulfillment.FulfillmentStatus.FAILED);
            fulfillment.setLastError(e.getMessage());
            fulfillment.setAttempts(1);
        }
        fulfillmentRepository.save(fulfillment);

        eventPublisherPort.publishTicketIssued(ticket.getId(), reservationId, pdfUrl, ticket.getConcessionsSnapshot());
    }

    @Override
    @Transactional
    public void handleConcessionOrderConfirmed(UUID reservationId, String itemsJson) {
        log.info("Processing ConcessionOrderConfirmed for reservationId: {}", reservationId);
        try {
            List<Ticket.ConcessionItem> items = objectMapper.readValue(itemsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Ticket.ConcessionItem.class));

            int updated = ticketRepository.updateConcessionsSnapshotByReservationId(reservationId, items);
            if (updated > 0) {
                log.info("Updated concessions snapshot for reservationId: {}", reservationId);
            } else {
                log.warn("No ticket found for reservationId: {}, storing for later merge", reservationId);
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

    private String buildEmailHtml(Ticket ticket) {
        return String.format("""
                <html>
                <body>
                    <h1>Your CineSync Ticket</h1>
                    <p><strong>Movie:</strong> %s</p>
                    <p><strong>Room:</strong> %s</p>
                    <p><strong>Showtime:</strong> %s</p>
                    <p><strong>Seats:</strong> %s</p>
                    <p><strong>QR Code:</strong> %s</p>
                </body>
                </html>
                """,
                ticket.getMovieTitleSnapshot(),
                ticket.getRoomNameSnapshot(),
                ticket.getShowtimeStartsAt(),
                String.join(", ", ticket.getSeatLabelsSnapshot()),
                ticket.getQrPayload()
        );
    }
}