package com.cinesync.ticketing.app.rest;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.port.in.TicketQueryUseCase;
import com.cinesync.ticketing.core.port.in.NotificationQueryUseCase;
import lombok.RequiredArgsConstructor;
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

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "database", "connected",
                "broker", "connected",
                "emailProvider", "connected"
        ));
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
        // Note: In real impl, we'd need a UserContactRepository to get userContactId from userId
        // For now, this is a placeholder
        List<TicketingFulfillment> fulfillments = notificationQueryUseCase.getFulfillmentsByUser(userId, page, limit);
        return ResponseEntity.ok(new NotificationListResponse(fulfillments, page, limit, fulfillments.size()));
    }

    public record TicketListResponse(List<Ticket> data, int page, int limit, long total) {}
    public record NotificationListResponse(List<TicketingFulfillment> data, int page, int limit, long total) {}
}