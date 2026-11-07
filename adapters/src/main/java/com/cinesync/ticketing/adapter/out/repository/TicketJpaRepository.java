package com.cinesync.ticketing.adapter.out.repository;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TicketJpaRepository extends JpaRepository<Ticket, UUID>, TicketRepository {
    @Override
    Optional<Ticket> findByReservationId(UUID reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Ticket t WHERE t.id = :id")
    Optional<Ticket> lockById(@Param("id") UUID id);

    @Query("SELECT t FROM Ticket t WHERE t.userId = :userId "
            + "ORDER BY t.showtimeStartsAt DESC NULLS LAST, t.createdAt DESC, t.id DESC")
    List<Ticket> findByUser(@Param("userId") UUID userId, Pageable pageable);

    /** The offset is always a multiple of the limit (it comes from a page number), so it maps to a page of the same size. */
    @Override
    default List<Ticket> findPageByUserId(UUID userId, int limit, int offset) {
        return findByUser(userId, PageRequest.of(offset / limit, limit));
    }

    @Override
    default Optional<Ticket> findByIdForUpdate(UUID id) {
        return lockById(id);
    }

    @Query("SELECT t FROM Ticket t WHERE t.pdfUrl IS NULL AND t.createdAt < :createdBefore ORDER BY t.createdAt")
    List<Ticket> findAwaiting(@Param("createdBefore") Instant createdBefore, Pageable pageable);

    @Override
    default List<Ticket> findAwaitingPdf(Instant createdBefore, int limit) {
        return findAwaiting(createdBefore, PageRequest.of(0, limit));
    }

    @Modifying
    @Query("UPDATE Ticket t SET t.concessionsSnapshot = :items, t.updatedAt = CURRENT_TIMESTAMP WHERE t.reservationId = :reservationId AND t.concessionsSnapshot IS NULL")
    int updateConcessionsSnapshotByReservationId(@Param("reservationId") UUID reservationId, @Param("items") List<Ticket.ConcessionItem> items);

    /** The result is cast because the function returns void, which the JDBC mapping of the query cannot read. */
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(hashtextextended(:reservationId, 0)) AS text)", nativeQuery = true)
    String acquireReservationLock(@Param("reservationId") String reservationId);

    @Override
    default void lockReservation(UUID reservationId) {
        acquireReservationLock(reservationId.toString());
    }
}