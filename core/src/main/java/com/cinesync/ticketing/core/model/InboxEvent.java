package com.cinesync.ticketing.core.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inbox_event", schema = "ticketing_fulfillment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InboxEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    @Column(name = "error", length = 1000)
    private String error;

    public static InboxEvent of(UUID eventId, String eventType) {
        return InboxEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .build();
    }

    public void markFailed(String error) {
        this.error = error;
    }
}