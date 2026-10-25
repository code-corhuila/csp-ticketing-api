package com.cinesync.ticketing.core.port.in;

import com.cinesync.ticketing.core.model.Ticket;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TicketingCommandUseCase {
    void handleUserRegistered(UUID userId, String email, Instant occurredAt);
    void handleUserEmailChanged(UUID userId, String email, Instant occurredAt);
    void handleBookingConfirmed(UUID reservationId, UUID userId, UUID showtimeId,
                                 String movieTitle, String roomName, String showtimeStartsAt,
                                 String seatNumbersJson, String correlationId);
    void handleConcessionOrderConfirmed(UUID reservationId, String itemsJson, String eventId, String correlationId);
    Ticket getOrCreateTicketForReservation(UUID reservationId);
}