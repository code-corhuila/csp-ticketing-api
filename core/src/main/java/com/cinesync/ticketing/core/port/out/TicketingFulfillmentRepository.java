package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.TicketingFulfillment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketingFulfillmentRepository {
    TicketingFulfillment save(TicketingFulfillment fulfillment);
    Optional<TicketingFulfillment> findById(UUID id);
    Optional<TicketingFulfillment> findByTicketId(UUID ticketId);
    /** Pending deliveries that are due and whose ticket already has its PDF, oldest first. */
    List<TicketingFulfillment> findDeliverable(Instant now, int limit);
}