package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.Notification;
import com.cinesync.ticketing.core.model.NotificationType;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository {
    Notification save(Notification notification);

    boolean existsByReservationIdAndType(UUID reservationId, NotificationType type);

    /** The notifications of a user, newest first. */
    List<Notification> findPageByUserId(UUID userId, int limit, int offset);

    long countByUserId(UUID userId);
}
