package com.cinesync.ticketing.adapter.out.repository;

import com.cinesync.ticketing.core.model.HeldReservation;
import com.cinesync.ticketing.core.port.out.HeldReservationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HeldReservationJpaRepository extends JpaRepository<HeldReservation, UUID>, HeldReservationRepository {

    /** The reminder rule (ADR-068): open, not warned, not expired yet, inside the lead time and with nothing else said about the reservation. */
    String DUE = "h.resolvedAt IS NULL AND h.warnedAt IS NULL AND h.expiresAt > :now AND h.expiresAt <= :until "
            + "AND NOT EXISTS (SELECT 1 FROM Ticket t WHERE t.reservationId = h.reservationId) "
            + "AND NOT EXISTS (SELECT 1 FROM Notification n WHERE n.reservationId = h.reservationId "
            + "AND n.type = com.cinesync.ticketing.core.model.NotificationType.RESERVATION_EXPIRED)";

    @Override
    Optional<HeldReservation> findByReservationId(UUID reservationId);

    @Query("SELECT h.reservationId FROM HeldReservation h WHERE " + DUE + " ORDER BY h.expiresAt, h.id")
    List<UUID> dueReservations(@Param("now") Instant now, @Param("until") Instant until, Pageable pageable);

    @Override
    default List<UUID> findReservationsToWarn(Instant now, Instant until, int limit) {
        return dueReservations(now, until, PageRequest.of(0, limit));
    }

    @Override
    @Query("SELECT h FROM HeldReservation h WHERE h.reservationId = :reservationId AND " + DUE)
    Optional<HeldReservation> findToWarn(@Param("reservationId") UUID reservationId, @Param("now") Instant now,
            @Param("until") Instant until);
}
