package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.OutboxEvent;

public interface OutboxRepository {
    OutboxEvent save(OutboxEvent event);
}
