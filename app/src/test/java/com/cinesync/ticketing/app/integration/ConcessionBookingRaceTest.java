package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.port.in.BookingConfirmation;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two events of a reservation are consumed by two listeners at the same time, as happens when both queues have a
 * backlog after an outage. Each transaction has to see what the other one stored, in whichever order they run: the
 * ticket must end with the concession lines and no pending order may be left behind.
 */
class ConcessionBookingRaceTest extends ProjectionTestSupport {

    private static final int RESERVATIONS = 80;
    private static final String ITEMS = "[{\"itemName\":\"Popcorn\",\"quantity\":2,\"unitPrice\":500}]";

    @Autowired
    private TicketRepository tickets;

    @Test
    void bookingAndConcessionOrderProcessedAtTheSameTime_neverLeaveATicketWithoutItsConcessions() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2 * RESERVATIONS);
        try {
            List<UUID> reservations = new ArrayList<>();
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < RESERVATIONS; i++) {
                UUID reservationId = UUID.randomUUID();
                reservations.add(reservationId);
                CountDownLatch start = new CountDownLatch(1);
                running.add(pool.submit(() -> {
                    start.await();
                    commandService.handleBookingConfirmed(new BookingConfirmation(reservationId, UUID.randomUUID(),
                            UUID.randomUUID(), "Example Movie", "Room 1", Instant.parse("2026-11-01T20:00:00Z"),
                            List.of("A1")), "corr");
                    return null;
                }));
                running.add(pool.submit(() -> {
                    start.await();
                    commandService.handleConcessionOrderConfirmed(reservationId, ITEMS, UUID.randomUUID().toString(), "corr");
                    return null;
                }));
                start.countDown();
            }
            for (Future<?> future : running) {
                future.get(60, TimeUnit.SECONDS);
            }

            List<UUID> withoutConcessions = reservations.stream().filter(reservationId -> {
                Ticket ticket = tickets.findByReservationId(reservationId).orElseThrow();
                return ticket.getConcessionsSnapshot() == null || ticket.getConcessionsSnapshot().size() != 1;
            }).toList();
            assertThat(withoutConcessions).as("tickets without their concession lines").isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }
}
