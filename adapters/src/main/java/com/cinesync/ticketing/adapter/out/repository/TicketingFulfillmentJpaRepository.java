package com.cinesync.ticketing.adapter.out.repository;

import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.port.out.TicketingFulfillmentRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TicketingFulfillmentJpaRepository extends JpaRepository<TicketingFulfillment, UUID>, TicketingFulfillmentRepository {
    @Override
    List<TicketingFulfillment> findPendingForRetry(Instant now, int limit);

    @Override
    List<TicketingFulfillment> findByUserContactId(UUID userContactId, int page, int limit);
}