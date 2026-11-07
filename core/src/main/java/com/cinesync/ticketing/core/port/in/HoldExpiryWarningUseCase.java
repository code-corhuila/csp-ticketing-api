package com.cinesync.ticketing.core.port.in;

/** The internal operation the csp-worker calls on a schedule to warn about the holds about to expire (ADR-068). */
public interface HoldExpiryWarningUseCase {

    /**
     * Creates the HOLD_EXPIRY_WARNING notification of up to {@code limit} holds that are open, not warned and expire within
     * the lead time, once per reservation, and marks them warned.
     */
    WarningResult warnDue(int limit);

    record WarningResult(int warned) {
    }
}
