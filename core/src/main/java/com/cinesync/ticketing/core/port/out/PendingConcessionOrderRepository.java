package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.PendingConcessionOrder;

import java.util.Optional;
import java.util.UUID;

public interface PendingConcessionOrderRepository {
    Optional<PendingConcessionOrder> findByReservationId(UUID reservationId);
    PendingConcessionOrder save(PendingConcessionOrder pendingConcessionOrder);
    void deleteByReservationId(UUID reservationId);
}