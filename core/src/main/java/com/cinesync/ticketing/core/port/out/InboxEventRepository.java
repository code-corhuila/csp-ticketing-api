package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.InboxEvent;

import java.util.Optional;
import java.util.UUID;

public interface InboxEventRepository {
    boolean tryInsert(UUID eventId, String eventType);
    Optional<InboxEvent> findByEventId(UUID eventId);
    void markFailed(UUID eventId, String error);
}