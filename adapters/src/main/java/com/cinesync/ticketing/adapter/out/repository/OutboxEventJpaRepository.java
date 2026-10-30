package com.cinesync.ticketing.adapter.out.repository;

import com.cinesync.ticketing.core.model.OutboxEvent;
import com.cinesync.ticketing.core.port.out.OutboxRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, UUID>, OutboxRepository {
}
