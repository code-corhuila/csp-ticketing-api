package com.cinesync.ticketing.core.port.in;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketingFulfillment;

import java.util.List;
import java.util.UUID;

public interface TicketQueryUseCase {
    List<Ticket> getTicketsByUser(UUID userId, int page, int limit);
    Ticket getTicketByReservationId(UUID reservationId);
}