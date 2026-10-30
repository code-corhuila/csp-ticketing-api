package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.Ticket;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository {
    Optional<Ticket> findByReservationId(UUID reservationId);
    Optional<Ticket> findById(UUID id);
    Ticket save(Ticket ticket);
    /** Tickets created before the given instant that have no PDF yet, oldest first. */
    List<Ticket> findAwaitingPdf(Instant createdBefore, int limit);
    int updateConcessionsSnapshotByReservationId(UUID reservationId, List<Ticket.ConcessionItem> items);
}