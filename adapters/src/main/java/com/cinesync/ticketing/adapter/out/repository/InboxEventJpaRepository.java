package com.cinesync.ticketing.adapter.out.repository;

import com.cinesync.ticketing.core.model.InboxEvent;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InboxEventJpaRepository extends JpaRepository<InboxEvent, UUID>, InboxEventRepository {
    /** One statement decides: the unique key makes the second of two concurrent copies insert nothing. */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO ticketing_fulfillment.inbox_event (event_id, event_type) VALUES (:eventId, :eventType) "
            + "ON CONFLICT (event_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId, @Param("eventType") String eventType);

    @Override
    default boolean tryInsert(UUID eventId, String eventType) {
        return insertIfAbsent(eventId, eventType) == 1;
    }
}