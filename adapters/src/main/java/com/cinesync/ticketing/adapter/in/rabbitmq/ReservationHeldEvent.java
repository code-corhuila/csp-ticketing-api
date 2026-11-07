package com.cinesync.ticketing.adapter.in.rabbitmq;

import com.cinesync.ticketing.core.port.in.ReservationHold;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

import static com.cinesync.ticketing.adapter.in.rabbitmq.EnvelopeFields.instant;
import static com.cinesync.ticketing.adapter.in.rabbitmq.EnvelopeFields.payloadOf;
import static com.cinesync.ticketing.adapter.in.rabbitmq.EnvelopeFields.read;
import static com.cinesync.ticketing.adapter.in.rabbitmq.EnvelopeFields.seats;
import static com.cinesync.ticketing.adapter.in.rabbitmq.EnvelopeFields.uuid;

/**
 * The part of a ReservationHeld envelope that the reminder needs (events.md of Booking): the reservation, the user, the
 * showtime, the seats and, when the event has it, the instant the hold expires. {@code expiresAt} is optional because the
 * events published before Booking added it (ADR-068) must be accepted; if it is present it must be a valid timestamp.
 * Unknown fields are ignored and anything else wrong goes to the dead-letter queue without retries.
 */
record ReservationHeldEvent(UUID eventId, String correlationId, ReservationHold hold) {

    static ReservationHeldEvent parse(String json, ObjectMapper mapper) {
        JsonNode root = read(json, mapper);
        JsonNode payload = payloadOf(root, "ReservationHeld");
        JsonNode expiresAt = payload.path("expiresAt");
        ReservationHold hold = new ReservationHold(
                uuid(payload, "reservationId"),
                uuid(payload, "userId"),
                uuid(payload, "showtimeId"),
                seats(payload.path("seatNumbers")),
                expiresAt.isMissingNode() || expiresAt.isNull() ? null : instant(payload, "expiresAt"));
        String correlationId = root.path("metadata").path("correlationId").asText(null);
        return new ReservationHeldEvent(uuid(root, "eventId"), correlationId, hold);
    }
}
