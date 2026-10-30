package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.Ticket;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository {
    Optional<Ticket> findByReservationId(UUID reservationId);
    Optional<Ticket> findById(UUID id);
    /** The ticket, locked until the current transaction ends, so concurrent changes to it run one at a time. */
    Optional<Ticket> findByIdForUpdate(UUID id);
    Ticket save(Ticket ticket);
    /** Tickets created before the given instant that have no PDF yet, oldest first. */
    List<Ticket> findAwaitingPdf(Instant createdBefore, int limit);
    int updateConcessionsSnapshotByReservationId(UUID reservationId, List<Ticket.ConcessionItem> items);
    /**
     * Serializes the transactions that handle the same reservation, until the current one ends. The booking and the
     * concession order of a reservation are consumed at the same time and each one looks for what the other stores, so
     * without it both can miss it and the ticket is left without its concessions. Must run inside a transaction.
     */
    void lockReservation(UUID reservationId);
}