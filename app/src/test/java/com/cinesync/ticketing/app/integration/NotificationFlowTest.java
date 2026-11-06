package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.core.model.NotificationType;
import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.port.in.NotificationQueryUseCase;
import com.cinesync.ticketing.core.port.in.NotificationQueryUseCase.NotificationView;
import com.cinesync.ticketing.core.port.in.PageResult;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import com.cinesync.ticketing.core.port.out.NotificationRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** HU-TICKETING-005 on the real database and broker: the notifications the events create, once, and their list. */
class NotificationFlowTest extends ProjectionTestSupport {

    private static final String BOOKING_KEY = "cine.booking.booking-confirmed.v1";
    private static final String PDF_URL = "s3://tickets/tickets/r/t.pdf";

    @Autowired
    private TicketRepository tickets;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private NotificationQueryUseCase query;
    @Autowired
    private TicketDeliveryUseCase delivery;
    @Autowired
    private InboxEventRepository inbox;
    @Autowired
    private JdbcTemplate jdbc;

    private static String bookingConfirmed(UUID eventId, UUID reservationId, UUID userId) {
        return """
                {"eventId":"%s","eventType":"BookingConfirmed","occurredAt":"2026-10-30T15:00:00Z","version":1,
                 "source":"booking-service","aggregateId":"%s","aggregateType":"Reservation",
                 "payload":{"reservationId":"%s","userId":"%s","showtimeId":"%s","movieTitle":"Example Movie",
                            "roomName":"Sala 1","seatNumbers":["A1","A2"],"showtimeStartsAt":null},
                 "metadata":{"correlationId":"%s"}}
                """.formatted(eventId, reservationId, reservationId, userId, UUID.randomUUID(), UUID.randomUUID());
    }

    private Ticket confirm(UUID reservationId, UUID userId) {
        publish(BOOKING_KEY, bookingConfirmed(UUID.randomUUID(), reservationId, userId));
        await().atMost(15, TimeUnit.SECONDS).until(() -> tickets.findByReservationId(reservationId).isPresent());
        return tickets.findByReservationId(reservationId).orElseThrow();
    }

    private int count(UUID reservationId, NotificationType type) {
        return jdbc.queryForObject("SELECT count(*) FROM ticketing_fulfillment.notification WHERE reservation_id = ? AND type = ?",
                Integer.class, reservationId, type.name());
    }

    @Test
    void bookingConfirmed_createsOneNotificationForTheUser() {
        UUID user = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();

        confirm(reservation, user);

        assertThat(count(reservation, NotificationType.BOOKING_CONFIRMED)).isEqualTo(1);
        PageResult<NotificationView> page = query.listMine(user, 1, 20);
        assertThat(page.data()).singleElement().satisfies(n -> {
            assertThat(n.type()).isEqualTo(NotificationType.BOOKING_CONFIRMED);
            assertThat(n.message()).isEqualTo("Tu reserva fue confirmada: Example Movie, Sala 1, asientos A1, A2.");
            assertThat(n.metadata()).containsEntry("reservationId", reservation.toString());
            assertThat(n.timestamp()).isNotNull();
        });
    }

    @Test
    void theSameBookingDeliveredAgain_createsNoSecondNotification() {
        UUID user = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String message = bookingConfirmed(eventId, reservation, user);
        publish(BOOKING_KEY, message);
        await().atMost(15, TimeUnit.SECONDS).until(() -> tickets.findByReservationId(reservation).isPresent());

        publish(BOOKING_KEY, message);
        publish(BOOKING_KEY, bookingConfirmed(UUID.randomUUID(), reservation, user));
        UUID barrier = UUID.randomUUID();
        publish(BOOKING_KEY, bookingConfirmed(barrier, UUID.randomUUID(), UUID.randomUUID()));
        await().atMost(15, TimeUnit.SECONDS).until(() -> inbox.findByEventId(barrier).isPresent());

        assertThat(count(reservation, NotificationType.BOOKING_CONFIRMED)).isEqualTo(1);
        assertThat(query.listMine(user, 1, 20).total()).isEqualTo(1);
    }

    @Test
    void theRegistrationOfThePdf_createsOneTicketIssuedNotificationEvenIfItIsReportedTwice() {
        UUID user = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        Ticket ticket = confirm(reservation, user);

        delivery.registerPdf(ticket.getId(), PDF_URL, "corr-1");
        delivery.registerPdf(ticket.getId(), PDF_URL, "corr-2");

        assertThat(count(reservation, NotificationType.TICKET_ISSUED)).isEqualTo(1);
    }

    @Test
    void theList_isNewestFirstPaginatedAndOnlyOfTheUser() {
        UUID user = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        Ticket ticket = confirm(reservation, user);
        delivery.registerPdf(ticket.getId(), PDF_URL, "corr-1");
        confirm(UUID.randomUUID(), UUID.randomUUID());

        PageResult<NotificationView> first = query.listMine(user, 1, 1);
        PageResult<NotificationView> second = query.listMine(user, 2, 1);

        assertThat(first.total()).isEqualTo(2);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.data()).singleElement().extracting(NotificationView::type).isEqualTo(NotificationType.TICKET_ISSUED);
        assertThat(second.data()).singleElement().extracting(NotificationView::type).isEqualTo(NotificationType.BOOKING_CONFIRMED);
        assertThat(query.listMine(user, 3, 1).data()).isEmpty();
        assertThat(notifications.countByUserId(user)).isEqualTo(2);
    }
}
