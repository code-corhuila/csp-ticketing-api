package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.Ticket;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository {
    Optional<Ticket> findByReservationId(UUID reservationId);
    Optional<Ticket> findById(UUID id);
    List<Ticket> findByUserContactId(UUID userContactId, int page, int limit);
    Ticket save(Ticket ticket);
    int updateConcessionsSnapshotByReservationId(UUID reservationId, List<Ticket.ConcessionItem> items);
}