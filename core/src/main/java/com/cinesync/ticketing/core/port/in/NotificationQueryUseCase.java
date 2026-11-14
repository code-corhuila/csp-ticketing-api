package com.cinesync.ticketing.core.port.in;

import com.cinesync.ticketing.core.model.NotificationType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface NotificationQueryUseCase {

    /** The notifications of the user, newest first; {@code page} starts at 1. */
    PageResult<NotificationView> listMine(UUID userId, int page, int limit);

    record NotificationView(UUID id, NotificationType type, String message, Instant timestamp, Map<String, String> metadata) {
    }
}
