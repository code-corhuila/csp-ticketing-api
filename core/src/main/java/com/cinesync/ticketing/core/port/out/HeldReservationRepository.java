package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.HeldReservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HeldReservationRepository {
    HeldReservation save(HeldReservation hold);

    Optional<HeldReservation> findByReservationId(UUID reservationId);

    /**
     * The reservations of the holds that are not resolved, not warned and expire after {@code now} and up to {@code until},
     * soonest first, at most {@code limit}. A reservation that already has a ticket or a RESERVATION_EXPIRED notification is
     * left out: the events of a reservation use different queues, so one can be processed before the hold is stored (ADR-068).
     */
    List<UUID> findReservationsToWarn(Instant now, Instant until, int limit);

    /** The hold of the reservation if it still meets the conditions of {@link #findReservationsToWarn}. */
    Optional<HeldReservation> findToWarn(UUID reservationId, Instant now, Instant until);
}
