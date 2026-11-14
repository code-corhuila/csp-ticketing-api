package com.cinesync.ticketing.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTest {

    private final UUID user = UUID.randomUUID();
    private final UUID reservation = UUID.randomUUID();
    private final UUID showtime = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();

    private Ticket ticket(String movie) {
        return Ticket.builder().id(ticketId).userId(user).reservationId(reservation).showtimeId(showtime)
                .movieTitleSnapshot(movie).roomNameSnapshot("Sala 1").seatLabelsSnapshot(List.of("A1", "A2")).qrPayload("CSP").build();
    }

    @Test
    void bookingConfirmed_tellsTheMovieTheRoomAndTheSeatsAndKeepsTheReferences() {
        Notification n = Notification.bookingConfirmed(ticket("Example Movie"));

        assertThat(n.getType()).isEqualTo(NotificationType.BOOKING_CONFIRMED);
        assertThat(n.getUserId()).isEqualTo(user);
        assertThat(n.getReservationId()).isEqualTo(reservation);
        assertThat(n.getMessage()).isEqualTo("Tu reserva fue confirmada: Example Movie, Sala 1, asientos A1, A2.");
        assertThat(n.getMetadata()).containsEntry("reservationId", reservation.toString())
                .containsEntry("showtimeId", showtime.toString()).containsEntry("ticketId", ticketId.toString());
    }

    @Test
    void ticketIssued_tellsTheTicketCanBeDownloaded() {
        Notification n = Notification.ticketIssued(ticket("Example Movie"));

        assertThat(n.getType()).isEqualTo(NotificationType.TICKET_ISSUED);
        assertThat(n.getMessage()).isEqualTo("Tu ticket para Example Movie está listo para descargar.");
        assertThat(n.getMetadata()).containsEntry("ticketId", ticketId.toString());
    }

    @Test
    void reservationExpired_tellsWhichSeatsWereFreed() {
        Instant expiredAt = Instant.parse("2026-11-06T20:00:00Z");

        Notification n = Notification.reservationExpired(user, reservation, showtime, List.of("B3", "B4"), expiredAt);

        assertThat(n.getType()).isEqualTo(NotificationType.RESERVATION_EXPIRED);
        assertThat(n.getMessage()).isEqualTo("Tu reserva venció y los asientos B3, B4 quedaron libres.");
        assertThat(n.getMetadata()).containsEntry("expiredAt", "2026-11-06T20:00:00Z");
    }

    @Test
    void aMessageNeverExceedsWhatTheColumnAccepts() {
        Notification n = Notification.bookingConfirmed(ticket("M".repeat(600)));

        assertThat(n.getMessage()).hasSize(500).endsWith("…");
    }
}
