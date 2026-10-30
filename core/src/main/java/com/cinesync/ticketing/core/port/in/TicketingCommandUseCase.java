package com.cinesync.ticketing.core.port.in;

import com.cinesync.ticketing.core.model.Ticket;

import java.util.List;
import java.util.UUID;

public interface TicketingCommandUseCase {
    void handleUserRegistered(UUID userId, String email);
    void handleUserEmailChanged(UUID userId, String email);
    void handleBookingConfirmed(UUID reservationId, UUID userId, UUID showtimeId,
                                 String movieTitle, String roomName, String showtimeStartsAt,
                                 String seatNumbersJson);
    void handleConcessionOrderConfirmed(UUID reservationId, String itemsJson);
    Ticket getOrCreateTicketForReservation(UUID reservationId);
}