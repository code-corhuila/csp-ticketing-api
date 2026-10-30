package com.cinesync.ticketing.app.rest;

import com.cinesync.ticketing.app.config.HealthStatusConverter;
import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.port.in.TicketQueryUseCase;
import com.cinesync.ticketing.core.port.in.NotificationQueryUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ticketing")
@RequiredArgsConstructor
public class TicketingController {

    private final TicketQueryUseCase ticketQueryUseCase;
    private final NotificationQueryUseCase notificationQueryUseCase;
    private final HealthEndpoint healthEndpoint;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        // Check overall health
        var healthComponent = healthEndpoint.health();
        Health health = HealthStatusConverter.convert(healthComponent);

        // Build readiness response
        Map<String, Object> details = Map.of(
                "status", health.getStatus().getCode(),
                "database", getComponentStatus(health, "db"),
                "broker", getComponentStatus(health, "rabbit"),
                "emailProvider", getComponentStatus(health, "mail"),
                "consumers", getComponentStatus(health, "consumers"),
                "objectStorage", getComponentStatus(health, "objectStorage")
        );

        boolean ready = Status.UP.equals(health.getStatus());

        return ready
                ? ResponseEntity.ok(details)
                : ResponseEntity.status(503).body(details);
    }

    private String getComponentStatus(Health health, String component) {
        var detail = health.getDetails().get(component);
        if (detail instanceof Map) {
            Object status = ((Map<?, ?>) detail).get("status");
            return status != null ? status.toString() : "UNKNOWN";
        }
        return "UNKNOWN";
    }

    @GetMapping("/me/tickets")
    public ResponseEntity<TicketListResponse> getMyTickets(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<Ticket> tickets = ticketQueryUseCase.getTicketsByUser(userId, page, limit);
        return ResponseEntity.ok(new TicketListResponse(tickets, page, limit, tickets.size()));
    }

    @GetMapping("/me/notifications")
    public ResponseEntity<NotificationListResponse> getMyNotifications(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<TicketingFulfillment> fulfillments = notificationQueryUseCase.getFulfillmentsByUser(userId, page, limit);
        return ResponseEntity.ok(new NotificationListResponse(fulfillments, page, limit, fulfillments.size()));
    }

    public record TicketListResponse(List<Ticket> data, int page, int limit, long total) {}
    public record NotificationListResponse(List<TicketingFulfillment> data, int page, int limit, long total) {}
}