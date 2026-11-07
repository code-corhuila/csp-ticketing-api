package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.InboxEvent;

import java.util.Optional;
import java.util.UUID;

public interface InboxEventRepository {
    /** Records the event; false when its id was already recorded, even by a transaction running at the same time. */
    boolean tryInsert(UUID eventId, String eventType);
    Optional<InboxEvent> findByEventId(UUID eventId);
}