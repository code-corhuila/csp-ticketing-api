package com.cinesync.ticketing.core.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the ticket needs from a BookingConfirmed event. The start time is null while Catalog does not exist.
 */
public record BookingConfirmation(UUID reservationId, UUID userId, UUID showtimeId, String movieTitle,
                                  String roomName, Instant showtimeStartsAt, List<String> seatNumbers) {
}
