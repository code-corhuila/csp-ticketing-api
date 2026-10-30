package com.cinesync.ticketing.adapter.in.rabbitmq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * The part of an Auth event envelope that the contact projection needs. Unknown fields are ignored
 * so the producer can add data without breaking this consumer.
 */
record UserContactEvent(UUID eventId, Instant occurredAt, UUID userId, String email) {

    private static final int EMAIL_MIN_LENGTH = 3;
    private static final int EMAIL_MAX_LENGTH = 255;

    /** Raised for a message that can never succeed, so it goes to the dead-letter queue without retries. */
    static final class InvalidEventException extends AmqpRejectAndDontRequeueException {
        InvalidEventException(String reason) {
            super(reason);
        }
    }

    static UserContactEvent parse(String json, ObjectMapper mapper, String expectedEventType) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new InvalidEventException("Message is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidEventException("Message is not a JSON object");
        }
        if (!expectedEventType.equals(root.path("eventType").asText())) {
            throw new InvalidEventException("Unexpected eventType, expected " + expectedEventType);
        }
        JsonNode payload = root.path("payload");
        if (!payload.isObject()) {
            throw new InvalidEventException("payload is missing");
        }
        String email = payload.path("email").asText("").trim();
        if (email.length() < EMAIL_MIN_LENGTH || email.length() > EMAIL_MAX_LENGTH || !email.contains("@")) {
            throw new InvalidEventException("payload.email is missing or invalid");
        }
        return new UserContactEvent(
                uuid(root, "eventId"),
                instant(root, "occurredAt"),
                uuid(payload, "userId"),
                email);
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
}
