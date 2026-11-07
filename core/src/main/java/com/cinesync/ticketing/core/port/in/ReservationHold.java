package com.cinesync.ticketing.core.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the reminder needs from a ReservationHeld event: whose hold it is, which seats and when it expires.
 * {@code expiresAt} is null in the events published before Booking added the field (ADR-068).
 */
public record ReservationHold(UUID reservationId, UUID userId, UUID showtimeId, List<String> seatNumbers,
                              Instant expiresAt) {
}
