package com.cinesync.ticketing.core.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ticketing_fulfillments", schema = "ticketing_fulfillment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketingFulfillment {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "channel", nullable = false, length = 50)
    private String channel = "EMAIL";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private FulfillmentStatus status = FulfillmentStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** After this many failed attempts the delivery is given up (ADR-057). */
    public static final int MAX_ATTEMPTS = 3;

    /** Wait before the next attempt, indexed by the attempts made so far minus one. */
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5));

    private static final int MAX_ERROR_LENGTH = 1000;

    public void markSent() {
        status = FulfillmentStatus.SENT;
        lastError = null;
        nextRetryAt = null;
    }

    /** Counts a failed attempt: it retries later with a growing delay, and fails for good at the last one. */
    public void recordFailedAttempt(String error, Instant now) {
        attempts++;
        lastError = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        if (attempts >= MAX_ATTEMPTS) {
            status = FulfillmentStatus.FAILED;
            nextRetryAt = null;
        } else {
            nextRetryAt = now.plus(RETRY_DELAYS.get(attempts - 1));
        }
    }

    public enum FulfillmentStatus {
        PENDING, SENT, FAILED
    }
}