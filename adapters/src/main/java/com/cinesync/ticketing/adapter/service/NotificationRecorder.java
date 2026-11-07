package com.cinesync.ticketing.adapter.service;

import com.cinesync.ticketing.core.model.Notification;
import com.cinesync.ticketing.core.port.out.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Stores a notification unless the reservation already has one of that type. The callers hold the lock of the reservation
 * or of its ticket, so two of them cannot both see it missing; the unique key of the table is the last guard.
 * It tells whether it stored one.
 */
@Component
@RequiredArgsConstructor
public class NotificationRecorder {

    private final NotificationRepository notificationRepository;

    public boolean record(Notification notification) {
        if (notificationRepository.existsByReservationIdAndType(notification.getReservationId(), notification.getType())) {
            return false;
        }
        notificationRepository.save(notification);
        return true;
    }
}
