package com.cinesync.ticketing.adapter.service;

import com.cinesync.ticketing.core.model.OutboxEvent;
import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketEmail;
import com.cinesync.ticketing.core.model.TicketNotFoundException;
import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.model.UserContact;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase;
import com.cinesync.ticketing.core.port.in.TicketToRender;
import com.cinesync.ticketing.core.port.out.EmailSenderPort;
import com.cinesync.ticketing.core.port.out.OutboxRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import com.cinesync.ticketing.core.port.out.TicketingFulfillmentRepository;
import com.cinesync.ticketing.core.port.out.UserContactRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Finishes a ticket after the worker has rendered its PDF (ADR-057): the URL is recorded together with the
 * TicketIssued outbox row, and the email goes out only once the contact can be resolved by user id.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TicketDeliveryService implements TicketDeliveryUseCase {

    private static final String SOURCE = "ticketing-fulfillment-service";

    private final TicketRepository ticketRepository;
    private final TicketingFulfillmentRepository fulfillmentRepository;
    private final UserContactRepository userContactRepository;
    private final OutboxRepository outboxRepository;
    private final EmailSenderPort emailSender;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;

    /** How long a ticket waits before its document is rendered, for the concession order to reach it. */
    @Value("${ticketing.pdf.min-age-seconds:60}")
    private long minAgeSeconds;

    /** Base of the portal, where the user signs in and downloads the PDF (ADR-058). */
    @Value("${ticketing.portal.url:http://localhost:4200}")
    private String portalUrl;

    @Override
    @Transactional(readOnly = true)
    public List<TicketToRender> awaitingPdf(int limit) {
        return ticketRepository.findAwaitingPdf(clock.instant().minusSeconds(minAgeSeconds), limit).stream()
                .map(ticket -> new TicketToRender(ticket.getId(), ticket.getReservationId(),
                        ticket.getMovieTitleSnapshot(), ticket.getRoomNameSnapshot(), ticket.getShowtimeStartsAt(),
                        ticket.getSeatLabelsSnapshot(), concessionsOf(ticket), ticket.getQrPayload()))
                .toList();
    }

    private static List<TicketToRender.Concession> concessionsOf(Ticket ticket) {
        if (ticket.getConcessionsSnapshot() == null) {
            return List.of();
        }
        return ticket.getConcessionsSnapshot().stream()
                .map(item -> new TicketToRender.Concession(item.getName(), item.getQuantity(), item.getUnitPriceCents()))
                .toList();
    }

    @Override
    @Transactional
    public void registerPdf(UUID ticketId, String pdfUrl, String correlationId) {
        Ticket ticket = ticketRepository.findById(ticketId).orElseThrow(() -> new TicketNotFoundException(ticketId));
        if (!ticket.attachPdf(pdfUrl)) {
            log.info("PDF already registered for ticketId: {}", ticketId);
            return;
        }
        ticketRepository.save(ticket);
        outboxRepository.save(ticketIssued(ticket, correlationId));
    }

    @Override
    public DispatchResult dispatchDue(int limit) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        int sent = 0;
        int retried = 0;
        int failed = 0;
        for (UUID fulfillmentId : fulfillmentRepository.findDeliverable(clock.instant(), limit).stream()
                .map(TicketingFulfillment::getId).toList()) {
            TicketingFulfillment.FulfillmentStatus status = transaction.execute(tx -> dispatch(fulfillmentId));
            switch (status) {
                case SENT -> sent++;
                case FAILED -> failed++;
                default -> retried++;
            }
        }
        return new DispatchResult(sent, retried, failed);
    }

    private TicketingFulfillment.FulfillmentStatus dispatch(UUID fulfillmentId) {
        TicketingFulfillment fulfillment = fulfillmentRepository.findById(fulfillmentId).orElseThrow();
        Ticket ticket = ticketRepository.findById(fulfillment.getTicketId()).orElseThrow();
        Instant now = clock.instant();
        Optional<UserContact> contact = userContactRepository.findByUserId(ticket.getUserId());
        if (contact.isEmpty()) {
            fulfillment.recordFailedAttempt("The contact of the user is not known yet", now);
        } else {
            try {
                TicketEmail email = TicketEmail.of(ticket, portalUrl);
                emailSender.sendTicketEmail(contact.get().getEmail(), email.subject(), email.htmlBody());
                if (ticket.getUserContactId() == null) {
                    ticket.setUserContactId(contact.get().getId());
                    ticketRepository.save(ticket);
                }
                fulfillment.markSent();
            } catch (RuntimeException e) {
                log.warn("Ticket email failed: ticketId={}, error={}", ticket.getId(), e.getClass().getSimpleName());
                fulfillment.recordFailedAttempt("Email send failed: " + e.getClass().getSimpleName(), now);
            }
        }
        fulfillmentRepository.save(fulfillment);
        return fulfillment.getStatus();
    }

    private OutboxEvent ticketIssued(Ticket ticket, String correlationId) {
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        Map<String, Object> envelope = Map.of(
                "eventId", eventId.toString(),
                "eventType", "TicketIssued",
                "occurredAt", now.toString(),
                "version", 1,
                "source", SOURCE,
                "aggregateId", ticket.getId().toString(),
                "aggregateType", "Ticket",
                "payload", Map.of(
                        "ticketId", ticket.getId().toString(),
                        "reservationId", ticket.getReservationId().toString(),
                        "pdfUrl", ticket.getPdfUrl(),
                        "issuedAt", now.toString()),
                "metadata", Map.of("correlationId", correlationId));
        try {
            return OutboxEvent.builder()
                    .id(eventId)
                    .aggregateType("Ticket")
                    .aggregateId(ticket.getId())
                    .eventType("TicketIssued")
                    .envelope(objectMapper.writeValueAsString(envelope))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("TicketIssued envelope could not be serialized", e);
        }
    }
}
