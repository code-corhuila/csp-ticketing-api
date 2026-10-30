package com.cinesync.ticketing.core.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the worker needs to render the document of a ticket (ADR-057): the snapshot the ticket keeps, never the entity.
 * The start time is null while Catalog does not exist.
 */
public record TicketToRender(UUID ticketId, UUID reservationId, String movieTitle, String roomName,
                             Instant showtimeStartsAt, List<String> seatNumbers, List<Concession> concessions,
                             String qrPayload) {

    public record Concession(String name, int quantity, long unitPriceCents) {
    }
}
