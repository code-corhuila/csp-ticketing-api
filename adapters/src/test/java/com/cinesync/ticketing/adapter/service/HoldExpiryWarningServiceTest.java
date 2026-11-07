package com.cinesync.ticketing.adapter.service;

import com.cinesync.ticketing.core.model.HeldReservation;
import com.cinesync.ticketing.core.model.Notification;
import com.cinesync.ticketing.core.model.NotificationType;
import com.cinesync.ticketing.core.port.in.HoldExpiryWarningUseCase.WarningResult;
import com.cinesync.ticketing.core.port.out.HeldReservationRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HoldExpiryWarningServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T20:00:00Z");
    private static final Instant UNTIL = NOW.plusSeconds(120);

    private final HeldReservationRepository holds = mock(HeldReservationRepository.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final NotificationRecorder recorder = mock(NotificationRecorder.class);
    private final HoldExpiryWarningService service =
            new HoldExpiryWarningService(holds, tickets, recorder, Clock.fixed(NOW, ZoneOffset.UTC));

    HoldExpiryWarningServiceTest() {
        ReflectionTestUtils.setField(service, "leadSeconds", 120L);
    }

    private HeldReservation hold(UUID reservation, long secondsLeft) {
        return HeldReservation.of(reservation, UUID.randomUUID(), UUID.randomUUID(), List.of("A1"), NOW.plusSeconds(secondsLeft));
    }

    @Test
    void aDueHoldIsWarnedOnceUnderTheLockOfItsReservation() {
        UUID reservation = UUID.randomUUID();
        HeldReservation hold = hold(reservation, 90);
        when(holds.findReservationsToWarn(NOW, UNTIL, 50)).thenReturn(List.of(reservation));
        when(holds.findToWarn(reservation, NOW, UNTIL)).thenReturn(Optional.of(hold));
        when(recorder.record(any())).thenReturn(true);

        WarningResult result = service.warnDue(50);

        assertThat(result.warned()).isEqualTo(1);
        assertThat(hold.getWarnedAt()).isEqualTo(NOW);
        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
        InOrder order = inOrder(tickets, holds, recorder);
        order.verify(tickets).lockReservation(reservation);
        order.verify(holds).findToWarn(reservation, NOW, UNTIL);
        order.verify(recorder).record(notification.capture());
        order.verify(holds).save(hold);
        assertThat(notification.getValue().getType()).isEqualTo(NotificationType.HOLD_EXPIRY_WARNING);
        assertThat(notification.getValue().getReservationId()).isEqualTo(reservation);
    }

    @Test
    void aHoldThatStoppedBeingDueWhileWaitingForTheLockIsSkipped() {
        UUID reservation = UUID.randomUUID();
        when(holds.findReservationsToWarn(NOW, UNTIL, 50)).thenReturn(List.of(reservation));
        when(holds.findToWarn(reservation, NOW, UNTIL)).thenReturn(Optional.empty());

        assertThat(service.warnDue(50).warned()).isZero();

        verify(recorder, never()).record(any());
        verify(holds, never()).save(any());
    }

    @Test
    void aReservationThatAlreadyHasItsWarningIsMarkedButNotCounted() {
        UUID reservation = UUID.randomUUID();
        HeldReservation hold = hold(reservation, 90);
        when(holds.findReservationsToWarn(NOW, UNTIL, 50)).thenReturn(List.of(reservation));
        when(holds.findToWarn(reservation, NOW, UNTIL)).thenReturn(Optional.of(hold));
        when(recorder.record(any())).thenReturn(false);

        assertThat(service.warnDue(50).warned()).isZero();

        assertThat(hold.getWarnedAt()).isEqualTo(NOW);
        verify(holds).save(hold);
    }

    @Test
    void theLimitIsPassedToTheQuery() {
        when(holds.findReservationsToWarn(NOW, UNTIL, 7)).thenReturn(List.of());

        assertThat(service.warnDue(7).warned()).isZero();
    }
}
