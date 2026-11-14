package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.core.model.NotificationType;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * HU-TICKETING-005, scenario 2 (ADR-068), first part, on the real database and broker: the holds kept from ReservationHeld
 * and their resolution by BookingConfirmed and ReservationExpired in either order.
 */
class HeldReservationFlowTest extends ProjectionTestSupport {

    private static final String HELD_KEY = "cine.booking.reservation-held.v1";
    private static final String HELD_QUEUE = "cine.ticketing_fulfillment.reservation-held";
    private static final String BOOKING_KEY = "cine.booking.booking-confirmed.v1";
    private static final String EXPIRED_KEY = "cine.booking.reservation-expired.v1";
    private static final String HOLD_ROW = "SELECT %s FROM ticketing_fulfillment.held_reservation WHERE reservation_id = ?";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private InboxEventRepository inbox;
    @Autowired
    private TicketRepository tickets;

    private static String held(UUID eventId, UUID reservationId, UUID userId, String expiresAtMember) {
        return """
                {"eventId":"%s","eventType":"ReservationHeld","occurredAt":"2026-11-06T19:50:00Z","version":1,
                 "source":"booking-service","aggregateId":"%s","aggregateType":"Reservation",
                 "payload":{"reservationId":"%s","userId":"%s","showtimeId":"%s","seatNumbers":["B3","B4"]%s},
                 "metadata":{"correlationId":"%s"}}
                """.formatted(eventId, reservationId, reservationId, userId, UUID.randomUUID(), expiresAtMember,
                UUID.randomUUID());
    }

    private static String expiresAt(Instant at) {
        return ",\"expiresAt\":\"" + at + "\"";
    }

    private static String bookingConfirmed(UUID reservationId, UUID userId) {
        return """
                {"eventId":"%s","eventType":"BookingConfirmed","occurredAt":"2026-11-06T19:55:00Z","version":1,
                 "source":"booking-service","aggregateId":"%s","aggregateType":"Reservation",
                 "payload":{"reservationId":"%s","userId":"%s","showtimeId":"%s","movieTitle":"Example Movie",
                            "roomName":"Sala 1","seatNumbers":["B3","B4"],"showtimeStartsAt":null},
                 "metadata":{"correlationId":"%s"}}
                """.formatted(UUID.randomUUID(), reservationId, reservationId, userId, UUID.randomUUID(), UUID.randomUUID());
    }

    private static String reservationExpired(UUID reservationId, UUID userId) {
        return """
                {"eventId":"%s","eventType":"ReservationExpired","occurredAt":"2026-11-06T20:05:00Z","version":1,
                 "source":"booking-service","aggregateId":"%s","aggregateType":"Reservation",
                 "payload":{"reservationId":"%s","userId":"%s","showtimeId":"%s","seatNumbers":["B3","B4"],
                            "expiredAt":"2026-11-06T20:00:00Z"},
                 "metadata":{"correlationId":"%s"}}
                """.formatted(UUID.randomUUID(), reservationId, reservationId, userId, UUID.randomUUID(), UUID.randomUUID());
    }

    /** Publishes a hold event and waits until the service has processed it. */
    private void publishHeld(UUID eventId, UUID reservationId, UUID userId, String expiresAtMember) {
        publish(HELD_KEY, held(eventId, reservationId, userId, expiresAtMember));
        await().atMost(15, TimeUnit.SECONDS).until(() -> inbox.findByEventId(eventId).isPresent());
    }

    private void awaitTicket(UUID reservationId) {
        await().atMost(15, TimeUnit.SECONDS).until(() -> tickets.findByReservationId(reservationId).isPresent());
    }

    private void awaitExpiredNotification(UUID reservationId) {
        await().atMost(15, TimeUnit.SECONDS).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ticketing_fulfillment.notification WHERE reservation_id = ? AND type = ?",
                Integer.class, reservationId, NotificationType.RESERVATION_EXPIRED.name()) == 1);
    }

    private int holds(UUID reservationId) {
        return jdbc.queryForObject("SELECT count(*) FROM ticketing_fulfillment.held_reservation WHERE reservation_id = ?",
                Integer.class, reservationId);
    }

    private boolean isNull(UUID reservationId, String column) {
        return jdbc.queryForObject(HOLD_ROW.formatted(column + " IS NULL"), Boolean.class, reservationId);
    }

    // ---- the consumer of ReservationHeld

    @Test
    void reservationHeld_isStoredOncePerReservationEvenIfDeliveredAgain() {
        UUID user = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-11-06T20:00:00Z");
        String message = held(UUID.randomUUID(), reservation, user, expiresAt(expiresAt));

        publish(HELD_KEY, message);
        await().atMost(15, TimeUnit.SECONDS).until(() -> holds(reservation) == 1);
        publish(HELD_KEY, message);
        publish(HELD_KEY, held(UUID.randomUUID(), reservation, user, expiresAt(expiresAt.plusSeconds(60))));
        publishHeld(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), expiresAt(expiresAt));

        assertThat(holds(reservation)).isEqualTo(1);
        assertThat(jdbc.queryForObject(HOLD_ROW.formatted("user_id"), UUID.class, reservation)).isEqualTo(user);
        assertThat(jdbc.queryForObject(HOLD_ROW.formatted("seat_labels::text"), String.class, reservation))
                .contains("B3").contains("B4");
        assertThat(jdbc.queryForObject(HOLD_ROW.formatted("expires_at"), Timestamp.class, reservation).toInstant())
                .isEqualTo(expiresAt);
        assertThat(isNull(reservation, "resolved_at")).isTrue();
        assertThat(isNull(reservation, "warned_at")).isTrue();
    }

    @Test
    void reservationHeldWithoutExpiresAt_isAcceptedAndNotStoredAndDoesNotGoToTheDeadLetterQueue() {
        UUID reservation = UUID.randomUUID();

        publishHeld(UUID.randomUUID(), reservation, UUID.randomUUID(), "");

        assertThat(holds(reservation)).isZero();
        assertThat(rabbitTemplate.receive(HELD_QUEUE + ".dlq", 500)).isNull();
    }

    @Test
    void invalidReservationHeld_goesToTheDeadLetterQueueAndStoresNothing() {
        UUID reservation = UUID.randomUUID();

        publish(HELD_KEY, "{\"eventId\":");
        publish(HELD_KEY, held(UUID.randomUUID(), reservation, UUID.randomUUID(), ",\"expiresAt\":\"soon\""));

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(rabbitTemplate.receive(HELD_QUEUE + ".dlq", 1000)).isNotNull();
            assertThat(rabbitTemplate.receive(HELD_QUEUE + ".dlq", 1000)).isNotNull();
        });
        assertThat(holds(reservation)).isZero();
    }

    // ---- BookingConfirmed and ReservationExpired resolve the hold, in either order

    @Test
    void bookingConfirmedAfterTheHold_resolvesIt() {
        UUID user = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        publishHeld(UUID.randomUUID(), reservation, user, expiresAt(Instant.now().plusSeconds(600)));

        publish(BOOKING_KEY, bookingConfirmed(reservation, user));
        awaitTicket(reservation);

        assertThat(isNull(reservation, "resolved_at")).isFalse();
    }

    @Test
    void reservationExpiredAfterTheHold_resolvesIt() {
        UUID user = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        publishHeld(UUID.randomUUID(), reservation, user, expiresAt(Instant.now().plusSeconds(600)));

        publish(EXPIRED_KEY, reservationExpired(reservation, user));
        awaitExpiredNotification(reservation);

        assertThat(isNull(reservation, "resolved_at")).isFalse();
    }

    @Test
    void bookingConfirmedBeforeTheHold_leavesTheHoldStoredAndUnresolved() {
        UUID user = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        publish(BOOKING_KEY, bookingConfirmed(reservation, user));
        awaitTicket(reservation);

        publishHeld(UUID.randomUUID(), reservation, user, expiresAt(Instant.now().plusSeconds(60)));

        assertThat(holds(reservation)).isEqualTo(1);
        assertThat(isNull(reservation, "resolved_at")).isTrue();
    }

    @Test
    void reservationExpiredBeforeTheHold_leavesTheHoldStoredAndUnresolved() {
        UUID user = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        publish(EXPIRED_KEY, reservationExpired(reservation, user));
        awaitExpiredNotification(reservation);

        publishHeld(UUID.randomUUID(), reservation, user, expiresAt(Instant.now().plusSeconds(60)));

        assertThat(holds(reservation)).isEqualTo(1);
        assertThat(isNull(reservation, "resolved_at")).isTrue();
    }
}
