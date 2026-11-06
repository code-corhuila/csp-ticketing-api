package com.cinesync.ticketing.app.rest;

import com.cinesync.ticketing.core.model.NotificationType;
import com.cinesync.ticketing.core.port.in.NotificationQueryUseCase;
import com.cinesync.ticketing.core.port.in.NotificationQueryUseCase.NotificationView;
import com.cinesync.ticketing.core.port.in.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The notifications of the authenticated user (HU-TICKETING-005): newest first, paginated; the user is the {@code sub}. */
@RestController
@RequestMapping("/api/v1/ticketing")
@RequiredArgsConstructor
public class NotificationsController {

    private static final int MAX_LIMIT = 100;

    private final NotificationQueryUseCase queryUseCase;

    @GetMapping("/me/notifications")
    public NotificationListResponse list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        if (page < 1 || limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidPaginationException("page must be 1 or more and limit between 1 and " + MAX_LIMIT);
        }
        PageResult<NotificationView> result = queryUseCase.listMine(UUID.fromString(jwt.getSubject()), page, limit);
        return new NotificationListResponse(
                result.data().stream().map(v -> new NotificationItem(v.id(), v.type(), v.message(), v.timestamp(), v.metadata())).toList(),
                new PageMeta(result.page(), result.limit(), result.total(), result.totalPages()));
    }

    @ExceptionHandler({InvalidPaginationException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalidPagination(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_REQUEST", "page must be 1 or more and limit between 1 and " + MAX_LIMIT));
    }

    public record NotificationItem(UUID id, NotificationType type, String message, Instant timestamp, Map<String, String> metadata) {
    }

    public record NotificationListResponse(List<NotificationItem> data, PageMeta meta) {
    }

    static class InvalidPaginationException extends RuntimeException {
        InvalidPaginationException(String message) {
            super(message);
        }
    }
}
