package com.cinesync.ticketing.core.port.in;

import com.cinesync.ticketing.core.model.TicketingFulfillment;

import java.util.List;
import java.util.UUID;

public interface NotificationQueryUseCase {
    List<TicketingFulfillment> getFulfillmentsByUser(UUID userId, int page, int limit);
}