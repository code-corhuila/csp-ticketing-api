package com.cinesync.ticketing.core.port.in;

import com.cinesync.ticketing.core.model.TicketingFulfillment.FulfillmentStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TicketQueryUseCase {

    /**
     * The tickets of the user, by showtime start and then by creation, newest first; {@code page} starts at 1. A ticket
     * with no start time (a hold written before Booking kept it, csp-booking-api#84) comes after the ones that have it.
     */
    PageResult<TicketView> listMine(UUID userId, int page, int limit);

    /**
     * What the client sees of a ticket. {@code hasPdf} says whether the PDF can be downloaded; {@code deliveryStatus} is
     * the state of the delivery of the ticket and is null when it has none.
     */
    record TicketView(UUID id, UUID reservationId, UUID showtimeId, String movieTitle, String roomName, Instant showtimeStartsAt,
                      List<String> seatLabels, List<Concession> concessions, String qrPayload, boolean hasPdf,
                      FulfillmentStatus deliveryStatus, Instant createdAt) {
    }

    record Concession(String name, int quantity) {
    }
}
