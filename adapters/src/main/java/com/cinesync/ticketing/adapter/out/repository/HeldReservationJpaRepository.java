package com.cinesync.ticketing.adapter.out.repository;

import com.cinesync.ticketing.core.model.HeldReservation;
import com.cinesync.ticketing.core.port.out.HeldReservationRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface HeldReservationJpaRepository extends JpaRepository<HeldReservation, UUID>, HeldReservationRepository {

    @Override
    Optional<HeldReservation> findByReservationId(UUID reservationId);
}
