package com.cinesync.ticketing.app.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.Status;

import java.util.LinkedHashMap;
import java.util.Map;

public final class HealthStatusConverter {

    private HealthStatusConverter() {}

    public static Health convert(HealthComponent component) {
        Status status = component.getStatus();
        Map<String, Object> details = new LinkedHashMap<>();

        // HealthComponent doesn't have getDetails() in older versions
        // Use reflection or try-catch for compatibility
        try {
            var detailsMethod = component.getClass().getMethod("getDetails");
            @SuppressWarnings("unchecked")
            Map<String, Object> componentDetails = (Map<String, Object>) detailsMethod.invoke(component);
            if (componentDetails != null) {
                componentDetails.forEach((key, value) -> {
                    if (value instanceof HealthComponent) {
                        details.put(key, convert((HealthComponent) value));
                    } else {
                        details.put(key, value);
                    }
                });
            }
        } catch (Exception e) {
            // Fallback: no details available
        }

        return Health.status(status).withDetails(details).build();
    }
}