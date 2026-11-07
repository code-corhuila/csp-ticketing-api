package com.cinesync.ticketing.adapter.in.rabbitmq;

import com.cinesync.ticketing.adapter.in.rabbitmq.UserContactEvent.InvalidEventException;
import com.cinesync.ticketing.core.port.in.BookingConfirmation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The part of a Booking envelope that the ticket needs (events.md of Booking). Unknown fields are ignored.
 * {@code showtimeStartsAt} may be null until Catalog exists; everything else is required, and a message
 * without it can never succeed, so it goes to the dead-letter queue without retries.
 */
record BookingConfirmedEvent(UUID eventId, String correlationId, BookingConfirmation booking) {

    private static final int MOVIE_TITLE_MAX_LENGTH = 255;
    private static final int ROOM_NAME_MAX_LENGTH = 160;

    static BookingConfirmedEvent parse(String json, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new InvalidEventException("Message is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidEventException("Message is not a JSON object");
        }
        if (!"BookingConfirmed".equals(root.path("eventType").asText())) {
            throw new InvalidEventException("Unexpected eventType, expected BookingConfirmed");
        }
        JsonNode payload = root.path("payload");
        if (!payload.isObject()) {
            throw new InvalidEventException("payload is missing");
        }
        BookingConfirmation booking = new BookingConfirmation(
                uuid(payload, "reservationId"),
                uuid(payload, "userId"),
                uuid(payload, "showtimeId"),
                text(payload, "movieTitle", MOVIE_TITLE_MAX_LENGTH),
                text(payload, "roomName", ROOM_NAME_MAX_LENGTH),
                optionalInstant(payload, "showtimeStartsAt"),
                seats(payload.path("seatNumbers")));
        String correlationId = root.path("metadata").path("correlationId").asText(null);
        return new BookingConfirmedEvent(uuid(root, "eventId"), correlationId, booking);
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(node.path(field).asText(""));
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException(field + " is missing or not a UUID");
        }
    }

    private static String text(JsonNode node, String field, int maxLength) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty() || value.length() > maxLength) {
            throw new InvalidEventException(field + " is missing or longer than " + maxLength);
        }
        return value;
    }

    private static Instant optionalInstant(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return Instant.parse(value.asText(""));
        } catch (DateTimeParseException e) {
            throw new InvalidEventException(field + " is not an RFC 3339 timestamp");
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
