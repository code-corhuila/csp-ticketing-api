package com.cinesync.ticketing.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HeldReservationTest {

    private static final Instant NOW = Instant.parse("2026-11-06T20:00:00Z");

    private final UUID reservation = UUID.randomUUID();

    private HeldReservation hold() {
        return HeldReservation.of(reservation, UUID.randomUUID(), UUID.randomUUID(), List.of("A1", "A2"), NOW.plusSeconds(100));
    }

    @Test
    void aNewHoldKeepsWhatBookingAnnouncedAndIsNotResolvedNorWarned() {
        HeldReservation hold = hold();

        assertThat(hold.getReservationId()).isEqualTo(reservation);
        assertThat(hold.getSeatLabels()).containsExactly("A1", "A2");
        assertThat(hold.getExpiresAt()).isEqualTo(NOW.plusSeconds(100));
        assertThat(hold.getResolvedAt()).isNull();
        assertThat(hold.getWarnedAt()).isNull();
    }

    @Test
    void resolveKeepsTheFirstInstant() {
        HeldReservation hold = hold();

        hold.resolve(NOW);
        hold.resolve(NOW.plusSeconds(5));

        assertThat(hold.getResolvedAt()).isEqualTo(NOW);
    }
}
