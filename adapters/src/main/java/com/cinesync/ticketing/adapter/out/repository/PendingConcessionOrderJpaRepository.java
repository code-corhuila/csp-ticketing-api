package com.cinesync.ticketing.adapter.out.repository;

import com.cinesync.ticketing.core.model.PendingConcessionOrder;
import com.cinesync.ticketing.core.port.out.PendingConcessionOrderRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PendingConcessionOrderJpaRepository extends JpaRepository<PendingConcessionOrder, UUID>, PendingConcessionOrderRepository {
    @Override
    Optional<PendingConcessionOrder> findByReservationId(UUID reservationId);

    @Override
    void deleteByReservationId(UUID reservationId);
}