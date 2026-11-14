package com.cinesync.ticketing.adapter.in.rabbitmq;

import com.cinesync.ticketing.adapter.in.rabbitmq.UserContactEvent.InvalidEventException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract of BookingConfirmed, taken from csp-docs (09-microservices/services/04-booking-service/events.md).
 */
class BookingConfirmedEventContractTest {

    private static final String EVENT_ID = "0b6f5c1a-2d34-4e56-8a78-9b0c1d2e3f40";
    private static final String RESERVATION_ID = "5d1c9b7e-6a43-4f21-8c90-1e2f3a4b5c6d";
    private final ObjectMapper mapper = new ObjectMapper();

    private static String envelope(String payload) {
        return """
                {"eventId":"%s","eventType":"BookingConfirmed","occurredAt":"2026-09-06T19:00:00Z","version":1,
                 "source":"booking-service","aggregateId":"%s","aggregateType":"Reservation","payload":%s,
                 "metadata":{"correlationId":"c1c1c1c1-0000-4000-8000-000000000001"}}
                """.formatted(EVENT_ID, RESERVATION_ID, payload);
    }

    private static String payload(String movieTitle, String seats, String startsAt) {
        return """
                {"reservationId":"%s","userId":"7f9c24e8-3b12-4a5d-9e6f-0a1b2c3d4e5f",
                 "showtimeId":"9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d","movieTitle":%s,"roomName":"Room 1",
                 "seatNumbers":%s,"showtimeStartsAt":%s}
                """.formatted(RESERVATION_ID, movieTitle, seats, startsAt);
    }

    private static String valid(String startsAt) {
        return envelope(payload("\"Example Movie\"", "[\"A1\",\"A2\"]", startsAt));
    }

    @Test
    void documentedExample_isParsed() {
        BookingConfirmedEvent event = BookingConfirmedEvent.parse(valid("\"2026-09-06T20:00:00Z\""), mapper);

        assertThat(event.eventId()).isEqualTo(UUID.fromString(EVENT_ID));
        assertThat(event.correlationId()).isEqualTo("c1c1c1c1-0000-4000-8000-000000000001");
        assertThat(event.booking().reservationId()).isEqualTo(UUID.fromString(RESERVATION_ID));
        assertThat(event.booking().movieTitle()).isEqualTo("Example Movie");
        assertThat(event.booking().roomName()).isEqualTo("Room 1");
        assertThat(event.booking().seatNumbers()).containsExactly("A1", "A2");
        assertThat(event.booking().showtimeStartsAt()).isEqualTo(Instant.parse("2026-09-06T20:00:00Z"));
    }

    @Test
    void nullShowtimeStart_isAccepted() {
        assertThat(BookingConfirmedEvent.parse(valid("null"), mapper).booking().showtimeStartsAt()).isNull();
    }

    @Test
    void unknownFields_areIgnored() {
        String json = valid("null").replace("\"version\":1,", "\"version\":1,\"extra\":true,");

        assertThat(BookingConfirmedEvent.parse(json, mapper).booking().seatNumbers()).hasSize(2);
    }

    @Test
    void messagesThatCanNeverSucceed_areRejected() {
        String noTitle = envelope(payload("\"\"", "[\"A1\"]", "null"));
        String noSeats = envelope(payload("\"Example Movie\"", "[]", "null"));
        String blankSeat = envelope(payload("\"Example Movie\"", "[\"A1\",\" \"]", "null"));
        String badStart = valid("\"tomorrow\"");
        String wrongType = valid("null").replace("BookingConfirmed", "ReservationHeld");
        String noReservation = valid("null").replace("\"reservationId\":\"" + RESERVATION_ID + "\",", "");

        for (String json : new String[]{"{\"eventId\":", "[]", noTitle, noSeats, blankSeat, badStart, wrongType,
                noReservation}) {
            assertThatThrownBy(() -> BookingConfirmedEvent.parse(json, mapper))
                    .isInstanceOf(InvalidEventException.class);
        }
    }
}
