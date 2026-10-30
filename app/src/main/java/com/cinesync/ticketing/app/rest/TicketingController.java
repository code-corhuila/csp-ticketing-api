package com.cinesync.ticketing.app.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ticketing")
@RequiredArgsConstructor
public class TicketingController {

    private final HealthEndpoint healthEndpoint;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        HealthComponent health = healthEndpoint.health();
        Map<String, Object> details = Map.of(
                "status", health.getStatus().getCode(),
                "database", componentStatus(health, "db"),
                "broker", componentStatus(health, "rabbit"),
                "emailProvider", componentStatus(health, "mail"),
                "consumers", componentStatus(health, "consumers"),
                "objectStorage", componentStatus(health, "objectStorage")
        );

        return Status.UP.equals(health.getStatus())
                ? ResponseEntity.ok(details)
                : ResponseEntity.status(503).body(details);
    }

    private static String componentStatus(HealthComponent health, String component) {
        if (health instanceof CompositeHealth composite && composite.getComponents().get(component) != null) {
            return composite.getComponents().get(component).getStatus().getCode();
        }
        return "UNKNOWN";
    }
}
