package com.cinesync.ticketing.adapter.service;

import com.cinesync.ticketing.core.model.OutboxEvent;
import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketNotFoundException;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase;
import com.cinesync.ticketing.core.port.out.OutboxRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Finishes a ticket after the worker has rendered its PDF (ADR-057): the URL is recorded together with the
 * TicketIssued outbox row, in one transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TicketDeliveryService implements TicketDeliveryUseCase {

    private static final String SOURCE = "ticketing-fulfillment-service";

    private final TicketRepository ticketRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

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
