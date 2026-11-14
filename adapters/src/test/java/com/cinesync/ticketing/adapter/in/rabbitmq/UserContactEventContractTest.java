package com.cinesync.ticketing.adapter.in.rabbitmq;

import com.cinesync.ticketing.adapter.in.rabbitmq.UserContactEvent.InvalidEventException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract of the two consumed Auth events, taken from csp-docs
 * (09-microservices/services/02-auth-service/events.md).
 */
class UserContactEventContractTest {

    private static final String USER_ID = "7f9c24e8-3b12-4a5d-9e6f-0a1b2c3d4e5f";
    private static final String EVENT_ID = "0b6f5c1a-2d34-4e56-8a78-9b0c1d2e3f40";
    private final ObjectMapper mapper = new ObjectMapper();

    private static String envelope(String eventType, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-09-06T19:00:00Z","version":1,
                 "source":"auth-service","aggregateId":"%s","payload":%s}
                """.formatted(EVENT_ID, eventType, USER_ID, payload);
    }

    @Test
    void userRegistered_documentedExample_isParsed() {
        String json = envelope("UserRegistered",
                "{\"userId\":\"" + USER_ID + "\",\"email\":\"user@example.com\",\"roles\":[\"CLIENT\"]}");

        UserContactEvent event = UserContactEvent.parse(json, mapper, "UserRegistered");

        assertThat(event.eventId()).isEqualTo(UUID.fromString(EVENT_ID));
        assertThat(event.userId()).isEqualTo(UUID.fromString(USER_ID));
        assertThat(event.email()).isEqualTo("user@example.com");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-09-06T19:00:00Z"));
    }

    @Test
    void userEmailChanged_documentedExample_isParsed() {
        String json = envelope("UserEmailChanged",
                "{\"userId\":\"" + USER_ID + "\",\"email\":\"new@example.com\"}");

        UserContactEvent event = UserContactEvent.parse(json, mapper, "UserEmailChanged");

        assertThat(event.email()).isEqualTo("new@example.com");
    }

    @Test
    void unknownFields_areIgnored() {
        String json = envelope("UserEmailChanged",
                "{\"userId\":\"" + USER_ID + "\",\"email\":\"new@example.com\",\"futureField\":true}");

        assertThat(UserContactEvent.parse(json, mapper, "UserEmailChanged").userId())
                .isEqualTo(UUID.fromString(USER_ID));
    }

    @Test
    void malformedJson_isRejectedWithoutRetry() {
        assertThatThrownBy(() -> UserContactEvent.parse("{\"eventId\":", mapper, "UserRegistered"))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void wrongEventType_isRejected() {
        String json = envelope("UserRoleChanged", "{\"userId\":\"" + USER_ID + "\",\"email\":\"a@b.co\"}");

        assertThatThrownBy(() -> UserContactEvent.parse(json, mapper, "UserRegistered"))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void missingOrInvalidFields_areRejected() {
        String[] payloads = {
                "{\"email\":\"a@b.co\"}",
                "{\"userId\":\"not-a-uuid\",\"email\":\"a@b.co\"}",
                "{\"userId\":\"" + USER_ID + "\"}",
                "{\"userId\":\"" + USER_ID + "\",\"email\":\"no-at-sign\"}",
                "null"
        };
        for (String payload : payloads) {
            String json = envelope("UserRegistered", payload);
            assertThatThrownBy(() -> UserContactEvent.parse(json, mapper, "UserRegistered"))
                    .as(payload)
                    .isInstanceOf(InvalidEventException.class);
        }
    }

    @Test
    void missingOccurredAt_isRejected() {
        String json = envelope("UserRegistered", "{\"userId\":\"" + USER_ID + "\",\"email\":\"a@b.co\"}")
                .replace("\"occurredAt\":\"2026-09-06T19:00:00Z\",", "");

        assertThatThrownBy(() -> UserContactEvent.parse(json, mapper, "UserRegistered"))
                .isInstanceOf(InvalidEventException.class);
    }
}
