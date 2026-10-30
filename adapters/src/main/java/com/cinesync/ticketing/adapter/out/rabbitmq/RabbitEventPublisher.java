package com.cinesync.ticketing.adapter.out.rabbitmq;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.port.out.EventPublisherPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class RabbitEventPublisher implements EventPublisherPort {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ticketing.rabbitmq.exchange:cine.ticketing_fulfillment}")
    private String exchange;

    @Value("${ticketing.rabbitmq.routing-key.ticket-issued:ticketing_fulfillment.ticket-issued.v1}")
    private String ticketIssuedRoutingKey;

    @Override
    public void publishTicketIssued(UUID ticketId, UUID reservationId, String pdfUrl,
                                     List<Ticket.ConcessionItem> concessions) {
        try {
            Map<String, Object> event = Map.of(
                    "eventId", UUID.randomUUID(),
                    "eventType", "TicketIssued",
                    "aggregateId", ticketId.toString(),
                    "aggregateType", "Ticket",
                    "occurredAt", Instant.now().toString(),
                    "version", 1,
                    "payload", Map.of(
                            "ticketId", ticketId.toString(),
                            "reservationId", reservationId.toString(),
                            "pdfUrl", pdfUrl,
                            "issuedAt", Instant.now().toString(),
                            "concessions", concessions
                    ),
                    "metadata", Map.of(
                            "correlationId", UUID.randomUUID().toString(),
                            "causationId", UUID.randomUUID().toString()
                    )
            );

            rabbitTemplate.convertAndSend(exchange, ticketIssuedRoutingKey, event);
            log.info("Published TicketIssued event for ticketId: {}", ticketId);
        } catch (Exception e) {
            log.error("Failed to publish TicketIssued event: {}", e.getMessage(), e);
            throw new RuntimeException("Event publish failed", e);
        }
    }
}