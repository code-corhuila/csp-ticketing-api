package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.TicketingFulfillment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketingFulfillmentRepository {
    TicketingFulfillment save(TicketingFulfillment fulfillment);
    Optional<TicketingFulfillment> findByTicketId(UUID ticketId);
    List<TicketingFulfillment> findPendingForRetry(Instant now, int limit);
    List<TicketingFulfillment> findByUserContactId(UUID userContactId, int page, int limit);
}