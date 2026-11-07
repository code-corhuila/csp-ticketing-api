package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketingFulfillment.FulfillmentStatus;
import com.cinesync.ticketing.core.port.in.PageResult;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase;
import com.cinesync.ticketing.core.port.in.TicketQueryUseCase;
import com.cinesync.ticketing.core.port.in.TicketQueryUseCase.TicketView;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The list of the tickets of a user on the real database and broker: order, pages, owner and the state of each ticket. */
class TicketListFlowTest extends ProjectionTestSupport {

    private static final String BOOKING_KEY = "cine.booking.booking-confirmed.v1";

    @Autowired
    private TicketRepository tickets;
    @Autowired
    private TicketQueryUseCase query;
    @Autowired
    private TicketDeliveryUseCase delivery;

    private static String bookingConfirmed(UUID reservationId, UUID userId, String movie, String startsAt) {
        return """
                {"eventId":"%s","eventType":"BookingConfirmed","occurredAt":"2026-10-30T15:00:00Z","version":1,
                 "source":"booking-service","aggregateId":"%s","aggregateType":"Reservation",
                 "payload":{"reservationId":"%s","userId":"%s","showtimeId":"%s","movieTitle":"%s",
                            "roomName":"Sala 1","seatNumbers":["A1","A2"],"showtimeStartsAt":%s},
                 "metadata":{"correlationId":"%s"}}
                """.formatted(UUID.randomUUID(), reservationId, reservationId, userId, UUID.randomUUID(), movie, startsAt,
                UUID.randomUUID());
    }

    private Ticket confirm(UUID user, String movie, String startsAt) {
        UUID reservation = UUID.randomUUID();
        publish(BOOKING_KEY, bookingConfirmed(reservation, user, movie, startsAt));
        await().atMost(15, TimeUnit.SECONDS).until(() -> tickets.findByReservationId(reservation).isPresent());
        return tickets.findByReservationId(reservation).orElseThrow();
    }

    @Test
    void theTicketsComeByShowtimeStartNewestFirstAndOnesWithoutStartLast() {
        UUID user = UUID.randomUUID();
        confirm(user, "November", "\"2026-11-01T20:00:00Z\"");
        confirm(user, "Unknown", "null");
        confirm(user, "December", "\"2026-12-01T20:00:00Z\"");
        confirm(UUID.randomUUID(), "Someone else", "\"2027-01-01T20:00:00Z\"");

        PageResult<TicketView> page = query.listMine(user, 1, 20);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.data()).extracting(TicketView::movieTitle).containsExactly("December", "November", "Unknown");
    }

    @Test
    void theListIsPaginatedAndAPageBeyondTheEndIsEmpty() {
        UUID user = UUID.randomUUID();
        confirm(user, "One", "\"2026-11-01T20:00:00Z\"");
        confirm(user, "Two", "\"2026-11-02T20:00:00Z\"");
        confirm(user, "Three", "\"2026-11-03T20:00:00Z\"");

        PageResult<TicketView> first = query.listMine(user, 1, 2);
        PageResult<TicketView> second = query.listMine(user, 2, 2);

        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.data()).extracting(TicketView::movieTitle).containsExactly("Three", "Two");
        assertThat(second.data()).extracting(TicketView::movieTitle).containsExactly("One");
        assertThat(query.listMine(user, 3, 2).data()).isEmpty();
    }

    @Test
    void aNewTicketIsPendingWithoutPdfAndAfterTheRegistrationOfThePdfItCanBeDownloaded() {
        UUID user = UUID.randomUUID();
        Ticket ticket = confirm(user, "Example Movie", "\"2026-11-01T20:00:00Z\"");

        TicketView before = query.listMine(user, 1, 20).data().get(0);
        assertThat(before.id()).isEqualTo(ticket.getId());
        assertThat(before.hasPdf()).isFalse();
        assertThat(before.deliveryStatus()).isEqualTo(FulfillmentStatus.PENDING);
        assertThat(before.seatLabels()).containsExactly("A1", "A2");
        assertThat(before.concessions()).isEmpty();

        delivery.registerPdf(ticket.getId(), "s3://tickets/tickets/r/t.pdf", "corr-1");

        assertThat(query.listMine(user, 1, 20).data().get(0).hasPdf()).isTrue();
    }

    @Test
    void aUserWithoutTicketsGetsAnEmptyList() {
        PageResult<TicketView> page = query.listMine(UUID.randomUUID(), 1, 20);

        assertThat(page.data()).isEmpty();
        assertThat(page.total()).isZero();
    }
}
