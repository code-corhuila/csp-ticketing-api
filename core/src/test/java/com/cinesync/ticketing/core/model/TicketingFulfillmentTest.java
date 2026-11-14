package com.cinesync.ticketing.core.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TicketingFulfillmentTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private static TicketingFulfillment pending() {
        return TicketingFulfillment.builder().status(TicketingFulfillment.FulfillmentStatus.PENDING).build();
    }

    @Test
    void firstAndSecondFailure_keepItPendingWithAGrowingDelay() {
        TicketingFulfillment fulfillment = pending();

        fulfillment.recordFailedAttempt("no contact", NOW);
        assertThat(fulfillment.getStatus()).isEqualTo(TicketingFulfillment.FulfillmentStatus.PENDING);
        assertThat(fulfillment.getAttempts()).isEqualTo(1);
        assertThat(fulfillment.getNextRetryAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));

        fulfillment.recordFailedAttempt("no contact", NOW);
        assertThat(fulfillment.getStatus()).isEqualTo(TicketingFulfillment.FulfillmentStatus.PENDING);
        assertThat(fulfillment.getNextRetryAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void thirdFailure_failsItForGood() {
        TicketingFulfillment fulfillment = pending();

        for (int attempt = 0; attempt < TicketingFulfillment.MAX_ATTEMPTS; attempt++) {
            fulfillment.recordFailedAttempt("smtp down", NOW);
        }

        assertThat(fulfillment.getStatus()).isEqualTo(TicketingFulfillment.FulfillmentStatus.FAILED);
        assertThat(fulfillment.getNextRetryAt()).isNull();
        assertThat(fulfillment.getLastError()).isEqualTo("smtp down");
    }

    @Test
    void longErrors_areTruncated() {
        TicketingFulfillment fulfillment = pending();

        fulfillment.recordFailedAttempt("x".repeat(5000), NOW);

        assertThat(fulfillment.getLastError()).hasSize(1000);
    }

    @Test
    void markSent_clearsTheErrorAndTheRetry() {
        TicketingFulfillment fulfillment = pending();
        fulfillment.recordFailedAttempt("smtp down", NOW);

        fulfillment.markSent();

        assertThat(fulfillment.getStatus()).isEqualTo(TicketingFulfillment.FulfillmentStatus.SENT);
        assertThat(fulfillment.getLastError()).isNull();
        assertThat(fulfillment.getNextRetryAt()).isNull();
    }
}
