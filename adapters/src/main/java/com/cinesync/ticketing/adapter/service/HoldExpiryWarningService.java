package com.cinesync.ticketing.adapter.service;

import com.cinesync.ticketing.core.model.HeldReservation;
import com.cinesync.ticketing.core.model.Notification;
import com.cinesync.ticketing.core.port.in.HoldExpiryWarningUseCase;
import com.cinesync.ticketing.core.port.out.HeldReservationRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates the HOLD_EXPIRY_WARNING notifications that are due (HU-TICKETING-005, ADR-068). One batch is one transaction. Each
 * reservation is locked, like the events of Booking do, and its hold is read again under the lock, so a confirmation or an
 * expiry processed meanwhile, or another run of the operation, is seen and the reservation is skipped.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HoldExpiryWarningService implements HoldExpiryWarningUseCase {

    private final HeldReservationRepository heldReservationRepository;
    private final TicketRepository ticketRepository;
    private final NotificationRecorder notificationRecorder;
    private final Clock clock;

    /** How long before the hold expires the reminder is created. */
    @Value("${ticketing.hold-expiry-warning.lead-seconds:120}")
    private long leadSeconds;

    @Override
    @Transactional
    public WarningResult warnDue(int limit) {
        Instant now = clock.instant();
        Instant until = now.plusSeconds(leadSeconds);
        int warned = 0;
        for (UUID reservationId : heldReservationRepository.findReservationsToWarn(now, until, limit)) {
            ticketRepository.lockReservation(reservationId);
            Optional<HeldReservation> hold = heldReservationRepository.findToWarn(reservationId, now, until);
            if (hold.isEmpty()) {
                log.info("Hold of reservationId {} is no longer due, skipping", reservationId);
                continue;
            }
            if (notificationRecorder.record(Notification.holdExpiryWarning(hold.get(), now))) {
                warned++;
            }
            hold.get().markWarned(now);
            heldReservationRepository.save(hold.get());
        }
        return new WarningResult(warned);
    }
}
