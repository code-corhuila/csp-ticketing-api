package com.cinesync.ticketing.core.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A fact the client is told about. There is one per reservation and type: the same event, delivered again, creates
 * nothing new (HU-TICKETING-005). The message is the text the client reads, in Spanish.
 */
@Entity
@Table(name = "notification", schema = "ticketing_fulfillment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    private static final int MAX_MESSAGE_LENGTH = 500;

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private UUID reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private NotificationType type;

    @Column(name = "message", nullable = false, updatable = false, length = MAX_MESSAGE_LENGTH)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, String> metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Notification(UUID userId, UUID reservationId, NotificationType type, String message, Map<String, String> metadata) {
        this.userId = userId;
        this.reservationId = reservationId;
        this.type = type;
        this.message = abbreviate(message);
        this.metadata = Map.copyOf(metadata);
    }

    /** The booking of the reservation was confirmed and its ticket is stored. */
    public static Notification bookingConfirmed(Ticket ticket) {
        return new Notification(ticket.getUserId(), ticket.getReservationId(), NotificationType.BOOKING_CONFIRMED,
                "Tu reserva fue confirmada: " + ticket.getMovieTitleSnapshot() + ", " + ticket.getRoomNameSnapshot()
                        + ", asientos " + String.join(", ", ticket.getSeatLabelsSnapshot()) + ".",
                ticketMetadata(ticket));
    }

    /** The PDF of the ticket exists and can be downloaded. */
    public static Notification ticketIssued(Ticket ticket) {
        return new Notification(ticket.getUserId(), ticket.getReservationId(), NotificationType.TICKET_ISSUED,
                "Tu ticket para " + ticket.getMovieTitleSnapshot() + " está listo para descargar.", ticketMetadata(ticket));
    }

    /** The hold expired before it was confirmed (ReservationExpired of Booking). */
    public static Notification reservationExpired(UUID userId, UUID reservationId, UUID showtimeId, List<String> seats,
            Instant expiredAt) {
        return new Notification(userId, reservationId, NotificationType.RESERVATION_EXPIRED,
                "Tu reserva venció y los asientos " + String.join(", ", seats) + " quedaron libres.",
                Map.of("reservationId", reservationId.toString(), "showtimeId", showtimeId.toString(),
                        "expiredAt", expiredAt.toString()));
    }

    /** The hold is about to expire: confirm it to keep the seats (HU-TICKETING-005, ADR-068). The text carries no clock time. */
    public static Notification holdExpiryWarning(HeldReservation hold, Instant now) {
        long minutes = hold.minutesLeft(now);
        return new Notification(hold.getUserId(), hold.getReservationId(), NotificationType.HOLD_EXPIRY_WARNING,
                "Tu reserva vence en menos de " + minutes + (minutes == 1 ? " minuto" : " minutos")
                        + ": confírmala para no perder tus asientos.",
                Map.of("reservationId", hold.getReservationId().toString(), "showtimeId", hold.getShowtimeId().toString(),
                        "expiresAt", hold.getExpiresAt().toString()));
    }

    private static Map<String, String> ticketMetadata(Ticket ticket) {
        return Map.of("reservationId", ticket.getReservationId().toString(), "showtimeId", ticket.getShowtimeId().toString(),
                "ticketId", String.valueOf(ticket.getId()));
    }

    private static String abbreviate(String message) {
        return message.length() <= MAX_MESSAGE_LENGTH ? message : message.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
    }
}
