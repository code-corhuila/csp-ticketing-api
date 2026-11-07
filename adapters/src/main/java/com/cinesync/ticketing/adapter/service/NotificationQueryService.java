package com.cinesync.ticketing.adapter.service;

import com.cinesync.ticketing.core.port.in.NotificationQueryUseCase;
import com.cinesync.ticketing.core.port.in.PageResult;
import com.cinesync.ticketing.core.port.out.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationQueryService implements NotificationQueryUseCase {

    private final NotificationRepository notificationRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResult<NotificationView> listMine(UUID userId, int page, int limit) {
        var notifications = notificationRepository.findPageByUserId(userId, limit, (page - 1) * limit).stream()
                .map(n -> new NotificationView(n.getId(), n.getType(), n.getMessage(), n.getCreatedAt(), n.getMetadata()))
                .toList();
        return new PageResult<>(notifications, page, limit, notificationRepository.countByUserId(userId));
    }
}
