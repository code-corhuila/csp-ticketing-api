package com.cinesync.ticketing.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HeldReservationTest {

    private static final Instant NOW = Instant.parse("2026-11-06T20:00:00Z");

    private final UUID user = UUID.randomUUID();
    private final UUID reservation = UUID.randomUUID();
    private final UUID showtime = UUID.randomUUID();

    private HeldReservation expiringIn(long seconds) {
        return HeldReservation.of(reservation, user, showtime, List.of("A1", "A2"), NOW.plusSeconds(seconds));
    }

    @Test
    void aNewHoldIsNeitherResolvedNorWarned() {
        HeldReservation hold = expiringIn(100);

        assertThat(hold.getReservationId()).isEqualTo(reservation);
        assertThat(hold.getSeatLabels()).containsExactly("A1", "A2");
        assertThat(hold.getResolvedAt()).isNull();
        assertThat(hold.getWarnedAt()).isNull();
    }

    @Test
    void resolveKeepsTheFirstInstant() {
        HeldReservation hold = expiringIn(100);

        hold.resolve(NOW);
        hold.resolve(NOW.plusSeconds(5));

        assertThat(hold.getResolvedAt()).isEqualTo(NOW);
    }

    @Test
    void markWarnedKeepsTheFirstInstant() {
        HeldReservation hold = expiringIn(100);

        hold.markWarned(NOW);
        hold.markWarned(NOW.plusSeconds(5));

        assertThat(hold.getWarnedAt()).isEqualTo(NOW);
    }

    @Test
    void minutesLeftAreRoundedUpAndNeverBelowOne() {
        assertThat(expiringIn(1).minutesLeft(NOW)).isEqualTo(1);
        assertThat(expiringIn(60).minutesLeft(NOW)).isEqualTo(1);
        assertThat(expiringIn(61).minutesLeft(NOW)).isEqualTo(2);
        assertThat(expiringIn(120).minutesLeft(NOW)).isEqualTo(2);
        assertThat(expiringIn(0).minutesLeft(NOW)).isEqualTo(1);
    }

    @Test
    void theWarningTellsTheMinutesLeftWithoutAClockTimeAndKeepsTheReferences() {
        Notification n = Notification.holdExpiryWarning(expiringIn(90), NOW);

        assertThat(n.getType()).isEqualTo(NotificationType.HOLD_EXPIRY_WARNING);
        assertThat(n.getUserId()).isEqualTo(user);
        assertThat(n.getReservationId()).isEqualTo(reservation);
        assertThat(n.getMessage()).isEqualTo("Tu reserva vence en menos de 2 minutos: confírmala para no perder tus asientos.");
        assertThat(n.getMetadata()).containsEntry("reservationId", reservation.toString())
                .containsEntry("showtimeId", showtime.toString()).containsEntry("expiresAt", "2026-11-06T20:01:30Z");
    }

    @Test
    void theWarningOfTheLastMinuteIsInTheSingular() {
        Notification n = Notification.holdExpiryWarning(expiringIn(45), NOW);

        assertThat(n.getMessage()).isEqualTo("Tu reserva vence en menos de 1 minuto: confírmala para no perder tus asientos.");
    }
}
