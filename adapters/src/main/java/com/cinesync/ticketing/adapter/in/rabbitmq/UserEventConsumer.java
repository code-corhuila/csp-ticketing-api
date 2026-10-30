package com.cinesync.ticketing.adapter.in.rabbitmq;

import com.cinesync.ticketing.core.model.InboxEvent;
import com.cinesync.ticketing.core.model.UserContact;
import com.cinesync.ticketing.core.port.in.TicketingCommandUseCase;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserEventConsumer {

    private final InboxEventRepository inboxEventRepository;
    private final TicketingCommandUseCase commandUseCase;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "${ticketing.rabbitmq.queue.user-registered}")
    @Transactional
    public void onUserRegistered(String message) {
        processEvent(message, "UserRegistered", (payload, root) -> {
            UUID userId = UUID.fromString(payload.get("userId").asText());
            String email = payload.get("email").asText();
            Instant occurredAt = Instant.parse(root.path("occurredAt").asText());
            commandUseCase.handleUserRegistered(userId, email, occurredAt);
        });
    }

    @RabbitListener(queues = "${ticketing.rabbitmq.queue.user-email-changed}")
    @Transactional
    public void onUserEmailChanged(String message) {
        processEvent(message, "UserEmailChanged", (payload, root) -> {
            UUID userId = UUID.fromString(payload.get("userId").asText());
            String email = payload.get("email").asText();
            Instant occurredAt = Instant.parse(root.path("occurredAt").asText());
            commandUseCase.handleUserEmailChanged(userId, email, occurredAt);
        });
    }

    @RabbitListener(queues = "${ticketing.rabbitmq.queue.booking-confirmed}")
    @Transactional
    public void onBookingConfirmed(String message) {
        processEventSimple(message, "BookingConfirmed", payload -> {
            UUID reservationId = UUID.fromString(payload.get("reservationId").asText());
            UUID userId = UUID.fromString(payload.get("userId").asText());
            UUID showtimeId = UUID.fromString(payload.get("showtimeId").asText());
            String movieTitle = payload.get("movieTitle").asText();
            String roomName = payload.get("roomName").asText();
            String showtimeStartsAt = payload.get("showtimeStartsAt").asText();
            String seatNumbers;
            try {
                seatNumbers = objectMapper.writeValueAsString(payload.get("seatNumbers"));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new RuntimeException("Invalid seatNumbers JSON", e);
            }

            commandUseCase.handleBookingConfirmed(
                    reservationId, userId, showtimeId, movieTitle, roomName,
                    showtimeStartsAt, seatNumbers
            );
        });
    }

    @RabbitListener(queues = "${ticketing.rabbitmq.queue.concession-order-confirmed}")
    @Transactional
    public void onConcessionOrderConfirmed(String message) {
        processEventSimple(message, "ConcessionOrderConfirmed", payload -> {
            UUID reservationId = UUID.fromString(payload.get("reservationId").asText());
            String itemsJson;
            try {
                itemsJson = objectMapper.writeValueAsString(payload.get("items"));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new RuntimeException("Invalid items JSON", e);
            }
            commandUseCase.handleConcessionOrderConfirmed(reservationId, itemsJson);
        });
    }

    private void processEvent(String message, String eventType, BiConsumer<JsonNode, JsonNode> handler) {
        try {
            JsonNode root = objectMapper.readTree(message);
            UUID eventId = UUID.fromString(root.get("eventId").asText());
            JsonNode payload = root.get("payload");

            if (!inboxEventRepository.tryInsert(eventId, eventType)) {
                log.info("Duplicate eventId {} for {}, skipping", eventId, eventType);
                return;
            }

            handler.accept(payload, root);

        } catch (Exception e) {
            log.error("Failed to process {} event: {}", eventType, e.getMessage(), e);
            throw new RuntimeException("Event processing failed", e);
        }
    }

    private void processEventSimple(String message, String eventType, java.util.function.Consumer<JsonNode> handler) {
        try {
            JsonNode root = objectMapper.readTree(message);
            UUID eventId = UUID.fromString(root.get("eventId").asText());
            JsonNode payload = root.get("payload");

            if (!inboxEventRepository.tryInsert(eventId, eventType)) {
                log.info("Duplicate eventId {} for {}, skipping", eventId, eventType);
                return;
            }

            handler.accept(payload);

        } catch (Exception e) {
            log.error("Failed to process {} event: {}", eventType, e.getMessage(), e);
            throw new RuntimeException("Event processing failed", e);
        }
    }

    @FunctionalInterface
    private interface EventHandler {
        void handle(JsonNode payload, JsonNode root) throws Exception;
    }
}