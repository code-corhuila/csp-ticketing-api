package com.cinesync.ticketing.adapter.out.repository;

import com.cinesync.ticketing.core.model.InboxEvent;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InboxEventJpaRepository extends JpaRepository<InboxEvent, UUID>, InboxEventRepository {
    @Override
    default boolean tryInsert(UUID eventId, String eventType) {
        if (existsById(eventId)) {
            return false;
        }
        InboxEvent event = InboxEvent.of(eventId, eventType);
        save(event);
        return true;
    }

    @Override
    default void markFailed(UUID eventId, String error) {
        findById(eventId).ifPresent(e -> {
            e.markFailed(error);
            save(e);
        });
    }
}