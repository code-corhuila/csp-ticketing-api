package com.cinesync.ticketing.core.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "tickets", schema = "ticketing_fulfillment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Empty while the contact projection of the user does not exist yet (ADR-057). */
    @Column(name = "user_contact_id")
    private UUID userContactId;

    @Column(name = "reservation_id", nullable = false, unique = true)
    private UUID reservationId;

    @Column(name = "showtime_id", nullable = false)
    private UUID showtimeId;

    @Column(name = "movie_title_snapshot", nullable = false, length = 255)
    private String movieTitleSnapshot;

    @Column(name = "room_name_snapshot", nullable = false, length = 160)
    private String roomNameSnapshot;

    /** Null while Catalog does not exist: Booking sends no start time (ADR-020). */
    @Column(name = "showtime_starts_at")
    private Instant showtimeStartsAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "seat_labels_snapshot", nullable = false, columnDefinition = "jsonb")
    private List<String> seatLabelsSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "concessions_snapshot", columnDefinition = "jsonb")
    private List<ConcessionItem> concessionsSnapshot;

    @Column(name = "qr_payload", nullable = false, length = 255)
    private String qrPayload;

    @Column(name = "pdf_url", length = 500)
    private String pdfUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ConcessionItem {
        private String name;
        private int quantity;
        private long unitPriceCents;
    }
}