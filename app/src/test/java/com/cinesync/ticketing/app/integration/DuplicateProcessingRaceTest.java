package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.port.in.BookingConfirmation;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HU-TICKETING-003: at-least-once delivery means the same event, or the same reservation under another event id, can
 * be consumed by several listener threads at the same time. Whatever the order, one ticket, one inbox row and one
 * TicketIssued row must remain, and no transaction may fail because another one won.
 */
class DuplicateProcessingRaceTest extends ProjectionTestSupport {

    private static final int COPIES = 8;

    @Autowired
    private InboxEventRepository inbox;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private TicketDeliveryUseCase delivery;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sameEventIdInsertedAtTheSameTime_isAcceptedOnceAndNeverFails() throws Exception {
        for (int round = 0; round < 20; round++) {
            UUID eventId = UUID.randomUUID();
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);

            List<Boolean> accepted = runTogether(() -> transaction.execute(tx -> inbox.tryInsert(eventId, "BookingConfirmed")));

            assertThat(accepted).as("copies accepted in round %d", round).containsOnlyOnce(true);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticketing_fulfillment.inbox_event WHERE event_id = ?",
                    Integer.class, eventId)).isEqualTo(1);
        }
    }

    @Test
    void sameReservationUnderDifferentEventsAtTheSameTime_createsOneTicketAndOneFulfillment() throws Exception {
        UUID reservationId = UUID.randomUUID();
        BookingConfirmation booking = booking(reservationId);

        runTogether(() -> {
            commandService.handleBookingConfirmed(booking, "corr");
            return true;
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticketing_fulfillment.tickets WHERE reservation_id = ?",
                Integer.class, reservationId)).isEqualTo(1);
        Ticket ticket = tickets.findByReservationId(reservationId).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticketing_fulfillment.ticketing_fulfillments WHERE ticket_id = ?",
                Integer.class, ticket.getId())).isEqualTo(1);
    }

    @Test
    void pdfRegisteredAtTheSameTime_writesOneTicketIssuedRow() throws Exception {
        UUID reservationId = UUID.randomUUID();
        commandService.handleBookingConfirmed(booking(reservationId), "corr");
        UUID ticketId = tickets.findByReservationId(reservationId).orElseThrow().getId();

        runTogether(() -> {
            delivery.registerPdf(ticketId, "s3://tickets/" + reservationId + "/" + ticketId + ".pdf", "corr");
            return true;
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticketing_fulfillment.outbox_event WHERE aggregate_id = ?",
                Integer.class, ticketId)).isEqualTo(1);
    }

    private static BookingConfirmation booking(UUID reservationId) {
        return new BookingConfirmation(reservationId, UUID.randomUUID(), UUID.randomUUID(), "Example Movie", "Room 1",
                Instant.parse("2026-11-01T20:00:00Z"), List.of("A1"));
    }

    /** Runs the same action from several threads released at once; a failure of any of them fails the test. */
    private static <T> List<T> runTogether(Callable<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(COPIES);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> running = new ArrayList<>();
            for (int i = 0; i < COPIES; i++) {
                running.add(pool.submit(() -> {
                    start.await();
                    return action.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : running) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
