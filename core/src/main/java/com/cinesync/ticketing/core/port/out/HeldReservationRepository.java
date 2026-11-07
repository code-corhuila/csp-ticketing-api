package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.HeldReservation;

import java.util.Optional;
import java.util.UUID;

public interface HeldReservationRepository {
    HeldReservation save(HeldReservation hold);

    Optional<HeldReservation> findByReservationId(UUID reservationId);
}
