package com.cinesync.ticketing.adapter.in.rabbitmq;

import com.cinesync.ticketing.adapter.in.rabbitmq.UserContactEvent.InvalidEventException;
import com.cinesync.ticketing.core.port.in.ReservationExpiration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The part of a ReservationExpired envelope that the notification needs (events.md of Booking): the reservation, the
 * user, the showtime, the seats and the instant the hold expired. Unknown fields are ignored; everything else is required,
 * and a message without it can never succeed, so it goes to the dead-letter queue without retries.
 */
record ReservationExpiredEvent(UUID eventId, String correlationId, ReservationExpiration expiration) {

    static ReservationExpiredEvent parse(String json, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new InvalidEventException("Message is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidEventException("Message is not a JSON object");
        }
        if (!"ReservationExpired".equals(root.path("eventType").asText())) {
            throw new InvalidEventException("Unexpected eventType, expected ReservationExpired");
        }
        JsonNode payload = root.path("payload");
        if (!payload.isObject()) {
            throw new InvalidEventException("payload is missing");
        }
        ReservationExpiration expiration = new ReservationExpiration(
                uuid(payload, "reservationId"),
                uuid(payload, "userId"),
                uuid(payload, "showtimeId"),
                seats(payload.path("seatNumbers")),
                instant(payload, "expiredAt"));
        String correlationId = root.path("metadata").path("correlationId").asText(null);
        return new ReservationExpiredEvent(uuid(root, "eventId"), correlationId, expiration);
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(node.path(field).asText(""));
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException(field + " is missing or not a UUID");
        }
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(node.path(field).asText(""));
        } catch (DateTimeParseException e) {
            throw new InvalidEventException(field + " is missing or not an RFC 3339 timestamp");
        }
    }

    private static List<String> seats(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw new InvalidEventException("seatNumbers is missing or empty");
        }
        List<String> seats = new ArrayList<>();
        node.forEach(seat -> {
            String label = seat.asText("").trim();
            if (!seat.isTextual() || label.isEmpty()) {
                throw new InvalidEventException("seatNumbers has an empty or non-text seat");
            }
            seats.add(label);
        });
        return seats;
    }
}
