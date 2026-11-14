package com.cinesync.ticketing.adapter.in.rabbitmq;

import com.cinesync.ticketing.adapter.in.rabbitmq.UserContactEvent.InvalidEventException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract of ReservationHeld as csp-booking-api writes it to its outbox (JdbcHoldRepository.envelopeOf): reservation, user,
 * showtime, seats and, since ADR-068, the instant the hold expires. The events published before that field are accepted too.
 */
class ReservationHeldEventContractTest {

    private static final String EVENT_ID = "0b6f5c1a-2d34-4e56-8a78-9b0c1d2e3f40";
    private static final String RESERVATION_ID = "5d1c9b7e-6a43-4f21-8c90-1e2f3a4b5c6d";
    private static final String EXPIRY = ",\"expiresAt\":\"2026-11-06T20:00:00Z\"";
    private final ObjectMapper mapper = new ObjectMapper();

    private static String envelope(String eventType, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-11-06T19:50:00Z","version":1,
                 "source":"booking-service","aggregateId":"%s","aggregateType":"Reservation","payload":%s,
                 "metadata":{"correlationId":"c1c1c1c1-0000-4000-8000-000000000003"}}
                """.formatted(EVENT_ID, eventType, RESERVATION_ID, payload);
    }

    private static String payload(String seats, String expiresAtMember) {
        return """
                {"reservationId":"%s","userId":"7f9c24e8-3b12-4a5d-9e6f-0a1b2c3d4e5f",
                 "showtimeId":"9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d","seatNumbers":%s%s}
                """.formatted(RESERVATION_ID, seats, expiresAtMember);
    }

    private static String valid() {
        return envelope("ReservationHeld", payload("[\"B3\",\"B4\"]", EXPIRY));
    }

    @Test
    void theEnvelopeBookingWritesIsParsed() {
        ReservationHeldEvent event = ReservationHeldEvent.parse(valid(), mapper);

        assertThat(event.eventId()).isEqualTo(UUID.fromString(EVENT_ID));
        assertThat(event.correlationId()).isEqualTo("c1c1c1c1-0000-4000-8000-000000000003");
        assertThat(event.hold().reservationId()).isEqualTo(UUID.fromString(RESERVATION_ID));
        assertThat(event.hold().userId()).isEqualTo(UUID.fromString("7f9c24e8-3b12-4a5d-9e6f-0a1b2c3d4e5f"));
        assertThat(event.hold().showtimeId()).isEqualTo(UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"));
        assertThat(event.hold().seatNumbers()).containsExactly("B3", "B4");
        assertThat(event.hold().expiresAt()).isEqualTo(Instant.parse("2026-11-06T20:00:00Z"));
    }

    @Test
    void anEventPublishedBeforeTheFieldExistedIsAcceptedWithoutExpiresAt() {
        assertThat(ReservationHeldEvent.parse(envelope("ReservationHeld", payload("[\"B3\"]", "")), mapper).hold().expiresAt())
                .isNull();
        assertThat(ReservationHeldEvent.parse(envelope("ReservationHeld", payload("[\"B3\"]", ",\"expiresAt\":null")), mapper)
                .hold().expiresAt()).isNull();
    }

    @Test
    void unknownFieldsAreIgnored() {
        String json = envelope("ReservationHeld", payload("[\"B3\"]", EXPIRY).replace("{", "{\"extra\":1,"));

        assertThat(ReservationHeldEvent.parse(json, mapper).hold().seatNumbers()).containsExactly("B3");
    }

    @Test
    void aMessageThatCanNeverSucceedIsRejectedWithoutRetries() {
        assertThatThrownBy(() -> ReservationHeldEvent.parse("not json", mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationHeldEvent.parse("[]", mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationHeldEvent.parse(envelope("BookingConfirmed", payload("[\"B3\"]", EXPIRY)), mapper))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationHeldEvent.parse(envelope("ReservationHeld", "null"), mapper))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationHeldEvent.parse(envelope("ReservationHeld", payload("[]", EXPIRY)), mapper))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationHeldEvent.parse(envelope("ReservationHeld", payload("[\"\"]", EXPIRY)), mapper))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationHeldEvent.parse(envelope("ReservationHeld",
                payload("[\"B3\"]", ",\"expiresAt\":\"soon\"")), mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationHeldEvent.parse(envelope("ReservationHeld",
                payload("[\"B3\"]", EXPIRY).replace(RESERVATION_ID, "x")), mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> ReservationHeldEvent.parse(valid().replace("\"eventId\":\"" + EVENT_ID + "\"", "\"eventId\":\"x\""),
                mapper)).isInstanceOf(InvalidEventException.class);
    }
}
