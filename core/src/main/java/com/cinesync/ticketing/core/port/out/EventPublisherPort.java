package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.Ticket;

import java.util.List;
import java.util.UUID;

public interface EventPublisherPort {
    void publishTicketIssued(UUID ticketId, UUID reservationId, String pdfUrl, List<Ticket.ConcessionItem> concessions);
}