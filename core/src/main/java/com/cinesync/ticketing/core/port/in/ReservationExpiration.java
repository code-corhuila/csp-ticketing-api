package com.cinesync.ticketing.core.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the notification needs from a ReservationExpired event: whose hold expired, which seats it freed and when. */
public record ReservationExpiration(UUID reservationId, UUID userId, UUID showtimeId, List<String> seatNumbers,
                                    Instant expiredAt) {
}
