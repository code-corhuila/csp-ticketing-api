package com.cinesync.ticketing.app.rest;

import com.cinesync.ticketing.core.model.NotificationType;
import com.cinesync.ticketing.core.port.in.NotificationQueryUseCase;
import com.cinesync.ticketing.core.port.in.NotificationQueryUseCase.NotificationView;
import com.cinesync.ticketing.core.port.in.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The notifications of the authenticated user (HU-TICKETING-005): newest first, paginated; the user is the {@code sub}. */
@RestController
@RequestMapping("/api/v1/ticketing")
@RequiredArgsConstructor
public class NotificationsController {

    private final NotificationQueryUseCase queryUseCase;

    @GetMapping("/me/notifications")
    public NotificationListResponse list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        InvalidPaginationException.check(page, limit);
        PageResult<NotificationView> result = queryUseCase.listMine(UUID.fromString(jwt.getSubject()), page, limit);
        return new NotificationListResponse(
                result.data().stream().map(v -> new NotificationItem(v.id(), v.type(), v.message(), v.timestamp(), v.metadata())).toList(),
                new PageMeta(result.page(), result.limit(), result.total(), result.totalPages()));
    }

    public record NotificationItem(UUID id, NotificationType type, String message, Instant timestamp, Map<String, String> metadata) {
    }

    public record NotificationListResponse(List<NotificationItem> data, PageMeta meta) {
    }
}
