package com.cinesync.ticketing.adapter.out.repository;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TicketJpaRepository extends JpaRepository<Ticket, UUID>, TicketRepository {
    @Override
    Optional<Ticket> findByReservationId(UUID reservationId);

    @Modifying
    @Query("UPDATE Ticket t SET t.concessionsSnapshot = :items, t.updatedAt = CURRENT_TIMESTAMP WHERE t.reservationId = :reservationId AND t.concessionsSnapshot IS NULL")
    int updateConcessionsSnapshotByReservationId(@Param("reservationId") UUID reservationId, @Param("items") List<Ticket.ConcessionItem> items);
}