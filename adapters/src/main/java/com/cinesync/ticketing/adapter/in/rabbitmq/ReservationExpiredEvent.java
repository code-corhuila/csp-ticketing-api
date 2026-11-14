package com.cinesync.ticketing.adapter.in.rabbitmq;

import com.cinesync.ticketing.core.port.in.ReservationExpiration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

import static com.cinesync.ticketing.adapter.in.rabbitmq.EnvelopeFields.instant;
import static com.cinesync.ticketing.adapter.in.rabbitmq.EnvelopeFields.payloadOf;
import static com.cinesync.ticketing.adapter.in.rabbitmq.EnvelopeFields.read;
import static com.cinesync.ticketing.adapter.in.rabbitmq.EnvelopeFields.seats;
import static com.cinesync.ticketing.adapter.in.rabbitmq.EnvelopeFields.uuid;

/**
 * The part of a ReservationExpired envelope that the notification needs (events.md of Booking): the reservation, the
 * user, the showtime, the seats and the instant the hold expired. Unknown fields are ignored; everything else is required,
 * and a message without it can never succeed, so it goes to the dead-letter queue without retries.
 */
record ReservationExpiredEvent(UUID eventId, String correlationId, ReservationExpiration expiration) {

    static ReservationExpiredEvent parse(String json, ObjectMapper mapper) {
        JsonNode root = read(json, mapper);
        JsonNode payload = payloadOf(root, "ReservationExpired");
        ReservationExpiration expiration = new ReservationExpiration(
                uuid(payload, "reservationId"),
                uuid(payload, "userId"),
                uuid(payload, "showtimeId"),
                seats(payload.path("seatNumbers")),
                instant(payload, "expiredAt"));
        String correlationId = root.path("metadata").path("correlationId").asText(null);
        return new ReservationExpiredEvent(uuid(root, "eventId"), correlationId, expiration);
    }
}
