package com.cinesync.ticketing.adapter.out.repository;

import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.port.out.TicketingFulfillmentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TicketingFulfillmentJpaRepository extends JpaRepository<TicketingFulfillment, UUID>, TicketingFulfillmentRepository {
    @Query("SELECT f FROM TicketingFulfillment f WHERE f.status = :status "
            + "AND (f.nextRetryAt IS NULL OR f.nextRetryAt <= :now) "
            + "AND f.ticketId IN (SELECT t.id FROM Ticket t WHERE t.pdfUrl IS NOT NULL) ORDER BY f.createdAt")
    List<TicketingFulfillment> findDue(@Param("status") TicketingFulfillment.FulfillmentStatus status,
                                       @Param("now") Instant now, Pageable pageable);

    @Override
    default List<TicketingFulfillment> findDeliverable(Instant now, int limit) {
        return findDue(TicketingFulfillment.FulfillmentStatus.PENDING, now, PageRequest.of(0, limit));
    }
}