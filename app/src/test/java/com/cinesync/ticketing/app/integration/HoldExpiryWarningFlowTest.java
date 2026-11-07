package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.core.model.NotificationType;
import com.cinesync.ticketing.core.port.in.HoldExpiryWarningUseCase;
import com.cinesync.ticketing.core.port.in.NotificationQueryUseCase;
import com.cinesync.ticketing.core.port.in.NotificationQueryUseCase.NotificationView;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * HU-TICKETING-005, scenario 2 (ADR-068), second part, on the real database and broker: the internal operation that warns about
 * the holds about to expire, once per reservation, and the reservations it must leave alone.
 */
class HoldExpiryWarningFlowTest extends ProjectionTestSupport {

    private static final String HELD_KEY = "cine.booking.reservation-held.v1";
    private static final String BOOKING_KEY = "cine.booking.booking-confirmed.v1";
    private static final String EXPIRED_KEY = "cine.booking.reservation-expired.v1";
    private static final String HOLD_ROW = "SELECT %s FROM ticketing_fulfillment.held_reservation WHERE reservation_id = ?";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private InboxEventRepository inbox;
    @Autowired
    private TicketRepository tickets;
    @Autowired
    private HoldExpiryWarningUseCase warnings;
    @Autowired
    private NotificationQueryUseCase query;

    /** The holds of the other tests must not count in the totals of these ones. */
    @BeforeEach
    void forgetTheHoldsOfTheOtherTests() {
        jdbc.update("UPDATE ticketing_fulfillment.held_reservation SET resolved_at = now() WHERE resolved_at IS NULL");
    }

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

    private void awaitNotification(UUID reservationId, NotificationType type) {
        await().atMost(15, TimeUnit.SECONDS).until(() -> count(reservationId, type) == 1);
    }

    private int count(UUID reservationId, NotificationType type) {
        return jdbc.queryForObject("SELECT count(*) FROM ticketing_fulfillment.notification WHERE reservation_id = ? AND type = ?",
                Integer.class, reservationId, type.name());
    }

    private boolean isNull(UUID reservationId, String column) {
        return jdbc.queryForObject(HOLD_ROW.formatted(column + " IS NULL"), Boolean.class, reservationId);
    }

    /** A hold written straight to the table, as the consumer would have stored it. */
    private UUID insertHold(UUID userId, Instant expiresAt) {
        UUID reservationId = UUID.randomUUID();
        insertHold(reservationId, userId, expiresAt);
        return reservationId;
    }

    private void insertHold(UUID reservationId, UUID userId, Instant expiresAt) {
        jdbc.update("INSERT INTO ticketing_fulfillment.held_reservation (reservation_id, user_id, showtime_id, seat_labels, "
                + "expires_at) VALUES (?, ?, ?, '[\"B3\",\"B4\"]'::jsonb, ?)", reservationId, userId, UUID.randomUUID(),
                Timestamp.from(expiresAt));
    }

    // ---- a hold whose reservation was closed before the hold arrived is never warned (the events use different queues)

    @Test
    void bookingConfirmedBeforeTheHold_leavesTheHoldOpenButItIsNeverWarned() {
        UUID user = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        publish(BOOKING_KEY, bookingConfirmed(reservation, user));
        awaitTicket(reservation);

        publishHeld(UUID.randomUUID(), reservation, user, expiresAt(Instant.now().plusSeconds(60)));
        warnings.warnDue(100);

        assertThat(isNull(reservation, "resolved_at")).isTrue();
        assertThat(count(reservation, NotificationType.HOLD_EXPIRY_WARNING)).isZero();
    }

    @Test
    void reservationExpiredBeforeTheHold_leavesTheHoldOpenButItIsNeverWarned() {
        UUID user = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        publish(EXPIRED_KEY, reservationExpired(reservation, user));
        awaitNotification(reservation, NotificationType.RESERVATION_EXPIRED);

        publishHeld(UUID.randomUUID(), reservation, user, expiresAt(Instant.now().plusSeconds(60)));
        warnings.warnDue(100);

        assertThat(isNull(reservation, "resolved_at")).isTrue();
        assertThat(count(reservation, NotificationType.HOLD_EXPIRY_WARNING)).isZero();
    }

    // ---- the internal operation

    @Test
    void warnDue_warnsTheHoldsInsideTheLeadTimeOnceAndLeavesTheRest() {
        UUID user = UUID.randomUUID();
        Instant now = Instant.now();
        UUID soon = insertHold(user, now.plusSeconds(90));
        UUID far = insertHold(user, now.plus(10, ChronoUnit.MINUTES));
        UUID expired = insertHold(user, now.minusSeconds(5));

        int first = warnings.warnDue(50).warned();
        int second = warnings.warnDue(50).warned();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(count(soon, NotificationType.HOLD_EXPIRY_WARNING)).isEqualTo(1);
        assertThat(isNull(soon, "warned_at")).isFalse();
        assertThat(count(far, NotificationType.HOLD_EXPIRY_WARNING)).isZero();
        assertThat(isNull(far, "warned_at")).isTrue();
        assertThat(count(expired, NotificationType.HOLD_EXPIRY_WARNING)).isZero();
        assertThat(isNull(expired, "warned_at")).isTrue();
    }

    @Test
    void warnDue_skipsTheHoldsAlreadyResolved() {
        UUID reservation = insertHold(UUID.randomUUID(), Instant.now().plusSeconds(90));
        jdbc.update("UPDATE ticketing_fulfillment.held_reservation SET resolved_at = now() WHERE reservation_id = ?", reservation);

        assertThat(warnings.warnDue(50).warned()).isZero();
        assertThat(count(reservation, NotificationType.HOLD_EXPIRY_WARNING)).isZero();
    }

    @Test
    void warnDue_ofAHoldOfSixtySeconds_warnsItAtOnce() {
        UUID user = UUID.randomUUID();
        UUID reservation = insertHold(user, Instant.now().plusSeconds(60));

        assertThat(warnings.warnDue(50).warned()).isEqualTo(1);

        assertThat(count(reservation, NotificationType.HOLD_EXPIRY_WARNING)).isEqualTo(1);
    }

    @Test
    void warnDue_handlesAtMostTheLimitSoonestFirstAndTheNextCallTheRest() {
        UUID user = UUID.randomUUID();
        Instant now = Instant.now();
        UUID latest = insertHold(user, now.plusSeconds(110));
        UUID middle = insertHold(user, now.plusSeconds(80));
        UUID soonest = insertHold(user, now.plusSeconds(50));

        assertThat(warnings.warnDue(2).warned()).isEqualTo(2);
        assertThat(count(soonest, NotificationType.HOLD_EXPIRY_WARNING)).isEqualTo(1);
        assertThat(count(middle, NotificationType.HOLD_EXPIRY_WARNING)).isEqualTo(1);
        assertThat(count(latest, NotificationType.HOLD_EXPIRY_WARNING)).isZero();

        assertThat(warnings.warnDue(2).warned()).isEqualTo(1);
        assertThat(count(latest, NotificationType.HOLD_EXPIRY_WARNING)).isEqualTo(1);
    }

    @Test
    void warnDue_whenTheWarningAlreadyExists_createsNoSecondOneAndMarksTheHold() {
        UUID user = UUID.randomUUID();
        UUID reservation = insertHold(user, Instant.now().plusSeconds(90));
        jdbc.update("INSERT INTO ticketing_fulfillment.notification (user_id, reservation_id, type, message, metadata) "
                + "VALUES (?, ?, 'HOLD_EXPIRY_WARNING', 'earlier', '{}'::jsonb)", user, reservation);

        assertThat(warnings.warnDue(50).warned()).isZero();

        assertThat(count(reservation, NotificationType.HOLD_EXPIRY_WARNING)).isEqualTo(1);
        assertThat(isNull(reservation, "warned_at")).isFalse();
    }

    @Test
    void theWarning_isShownInTheNotificationsOfTheUserInSpanishWithoutAClockTime() {
        UUID user = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(90).truncatedTo(ChronoUnit.MICROS);
        publishHeld(UUID.randomUUID(), reservation, user, expiresAt(expiresAt));

        warnings.warnDue(50);

        List<NotificationView> mine = query.listMine(user, 1, 20).data();
        assertThat(mine).singleElement().satisfies(n -> {
            assertThat(n.type()).isEqualTo(NotificationType.HOLD_EXPIRY_WARNING);
            assertThat(n.message()).isEqualTo("Tu reserva vence en menos de 2 minutos: confírmala para no perder tus asientos.");
            assertThat(n.metadata()).containsEntry("reservationId", reservation.toString())
                    .containsEntry("expiresAt", expiresAt.toString()).containsKey("showtimeId");
        });
    }

    @Test
    void warnDue_afterTheBookingWasConfirmed_warnsNothing() {
        UUID user = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        publishHeld(UUID.randomUUID(), reservation, user, expiresAt(Instant.now().plusSeconds(90)));
        publish(BOOKING_KEY, bookingConfirmed(reservation, user));
        awaitTicket(reservation);

        assertThat(warnings.warnDue(50).warned()).isZero();
        assertThat(count(reservation, NotificationType.HOLD_EXPIRY_WARNING)).isZero();
    }
}
