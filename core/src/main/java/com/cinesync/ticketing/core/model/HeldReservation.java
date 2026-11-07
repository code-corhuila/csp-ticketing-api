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
import java.util.UUID;

/**
 * A hold announced by Booking with the instant it expires (ReservationHeld, ADR-068). It is kept once per reservation so
 * the reminder can find the holds about to expire. It is resolved when the booking is confirmed or the hold expires.
 */
@Entity
@Table(name = "held_reservation", schema = "ticketing_fulfillment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HeldReservation {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private UUID reservationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "showtime_id", nullable = false, updatable = false)
    private UUID showtimeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "seat_labels", nullable = false, updatable = false, columnDefinition = "jsonb")
    private List<String> seatLabels;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "warned_at")
    private Instant warnedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private HeldReservation(UUID reservationId, UUID userId, UUID showtimeId, List<String> seatLabels, Instant expiresAt) {
        this.reservationId = reservationId;
        this.userId = userId;
        this.showtimeId = showtimeId;
        this.seatLabels = List.copyOf(seatLabels);
        this.expiresAt = expiresAt;
    }

    public static HeldReservation of(UUID reservationId, UUID userId, UUID showtimeId, List<String> seatLabels,
            Instant expiresAt) {
        return new HeldReservation(reservationId, userId, showtimeId, seatLabels, expiresAt);
    }

    /** The reservation was confirmed or its hold expired: there is nothing left to warn about. The first instant stays. */
    public void resolve(Instant at) {
        if (resolvedAt == null) {
            resolvedAt = at;
        }
    }

}
