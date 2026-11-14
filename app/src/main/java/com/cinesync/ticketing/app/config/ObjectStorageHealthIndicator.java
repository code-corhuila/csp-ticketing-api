package com.cinesync.ticketing.app.config;

import com.cinesync.ticketing.core.model.PdfStorageException;
import com.cinesync.ticketing.core.port.out.TicketPdfStorage;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** The bucket of the ticket PDFs is part of the readiness of the service (Ticketing DEC-002, ADR-058). */
@Component("objectStorage")
public class ObjectStorageHealthIndicator implements HealthIndicator {

    private final TicketPdfStorage storage;

    public ObjectStorageHealthIndicator(TicketPdfStorage storage) {
        this.storage = storage;
    }

    @Override
    public Health health() {
        try {
            storage.check();
            return Health.up().build();
        } catch (PdfStorageException e) {
            // The reason is logged by the adapter; the endpoint is public and must not describe the storage.
            return Health.down().withDetail("objectStorage", "bucket not reachable").build();
        }
    }
}
