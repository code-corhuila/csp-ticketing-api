package com.cinesync.ticketing.adapter.in.rabbitmq;

import com.cinesync.ticketing.adapter.in.rabbitmq.UserContactEvent.InvalidEventException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads the fields of a Booking event envelope. Whatever is wrong with a message can never be fixed by a retry, so it is an
 * {@link InvalidEventException} and the message goes to the dead-letter queue without retries.
 */
final class EnvelopeFields {

    private EnvelopeFields() {
    }

    static JsonNode read(String json, ObjectMapper mapper) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new InvalidEventException("Message is not valid JSON");
        }
    }

    /** The payload of an envelope of the given type; fails if the message is not a JSON object of that type with a payload. */
    static JsonNode payloadOf(JsonNode root, String eventType) {
        if (root == null || !root.isObject()) {
            throw new InvalidEventException("Message is not a JSON object");
        }
        if (!eventType.equals(root.path("eventType").asText())) {
            throw new InvalidEventException("Unexpected eventType, expected " + eventType);
        }
        JsonNode payload = root.path("payload");
        if (!payload.isObject()) {
            throw new InvalidEventException("payload is missing");
        }
        return payload;
    }

    static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(node.path(field).asText(""));
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException(field + " is missing or not a UUID");
        }
    }

    static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(node.path(field).asText(""));
        } catch (DateTimeParseException e) {
            throw new InvalidEventException(field + " is missing or not an RFC 3339 timestamp");
        }
    }

    static List<String> seats(JsonNode node) {
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
