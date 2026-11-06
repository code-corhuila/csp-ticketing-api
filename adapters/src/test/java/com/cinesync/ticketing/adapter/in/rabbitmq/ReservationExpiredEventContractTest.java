package com.cinesync.ticketing.adapter.in.rabbitmq;

import com.cinesync.ticketing.adapter.in.rabbitmq.UserContactEvent.InvalidEventException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract of ReservationExpired as csp-booking-api writes it to its outbox (JdbcHoldRepository.expiredEnvelopeOf):
 * reservation, user, showtime, seats and the instant the hold expired.
 */
class ReservationExpiredEventContractTest {

    private static final String EVENT_ID = "0b6f5c1a-2d34-4e56-8a78-9b0c1d2e3f40";
    private static final String RESERVATION_ID = "5d1c9b7e-6a43-4f21-8c90-1e2f3a4b5c6d";
    private final ObjectMapper mapper = new ObjectMapper();

    private static String envelope(String eventType, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-11-06T20:05:00Z","version":1,
                 "source":"booking-service","aggregateId":"%s","aggregateType":"Reservation","payload":%s,
                 "metadata":{"correlationId":"c1c1c1c1-0000-4000-8000-000000000002"}}
                """.formatted(EVENT_ID, eventType, RESERVATION_ID, payload);
    }

    private static String payload(String seats, String expiredAt) {
        return """
                {"reservationId":"%s","userId":"7f9c24e8-3b12-4a5d-9e6f-0a1b2c3d4e5f",
                 "showtimeId":"9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d","seatNumbers":%s,"expiredAt":%s}
                """.formatted(RESERVATION_ID, seats, expiredAt);
    }

    private static String valid() {
        return envelope("ReservationExpired", payload("[\"B3\",\"B4\"]", "\"2026-11-06T20:00:00Z\""));
    }

    @Test
    void theEnvelopeBookingWritesIsParsed() {
        ReservationExpiredEvent event = ReservationExpiredEvent.parse(valid(), mapper);

        assertThat(event.eventId()).isEqualTo(UUID.fromString(EVENT_ID));
        assertThat(event.correlationId()).isEqualTo("c1c1c1c1-0000-4000-8000-000000000002");
        assertThat(event.expiration().reservationId()).isEqualTo(UUID.fromString(RESERVATION_ID));
        assertThat(event.expiration().userId()).isEqualTo(UUID.fromString("7f9c24e8-3b12-4a5d-9e6f-0a1b2c3d4e5f"));
        assertThat(event.expiration().seatNumbers()).containsExactly("B3", "B4");
        assertThat(event.expiration().expiredAt()).isEqualTo(Instant.parse("2026-11-06T20:00:00Z"));
    }

    @Test
    void unknownFieldsAreIgnored() {
        String json = envelope("ReservationExpired", payload("[\"B3\"]", "\"2026-11-06T20:00:00Z\"").replace("{", "{\"extra\":1,"));

        assertThat(ReservationExpiredEvent.parse(json, mapper).expiration().seatNumbers()).containsExactly("B3");
    }

    @Test
    void aMessageThatCanNeverSucceedIsRejectedWithoutRetries() {
        assertThatThrownBy(() -> ReservationExpiredEvent.parse("not json", mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationExpiredEvent.parse("[]", mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationExpiredEvent.parse(envelope("BookingConfirmed",
                payload("[\"B3\"]", "\"2026-11-06T20:00:00Z\"")), mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationExpiredEvent.parse(envelope("ReservationExpired", "null"), mapper))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationExpiredEvent.parse(envelope("ReservationExpired",
                payload("[]", "\"2026-11-06T20:00:00Z\"")), mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationExpiredEvent.parse(envelope("ReservationExpired",
                payload("[\"\"]", "\"2026-11-06T20:00:00Z\"")), mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationExpiredEvent.parse(envelope("ReservationExpired",
                payload("[\"B3\"]", "\"yesterday\"")), mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationExpiredEvent.parse(envelope("ReservationExpired",
                payload("[\"B3\"]", "null")), mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationExpiredEvent.parse(valid().replace(EVENT_ID, "x"), mapper))
                .isInstanceOf(InvalidEventException.class);
    }
}
