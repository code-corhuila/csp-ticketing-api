package com.cinesync.ticketing.core.port.in;

import java.util.List;
import java.util.UUID;

/** The internal operations the csp-worker calls to finish a ticket (ADR-057). */
public interface TicketDeliveryUseCase {

    /**
     * The tickets whose document is still to be rendered, oldest first, at most {@code limit}. A ticket is offered
     * only after a grace period, so the concession order that follows the booking has time to reach it.
     */
    List<TicketToRender> awaitingPdf(int limit);

    /** Records the URL of the stored PDF and writes TicketIssued to the outbox, once per ticket. */
    void registerPdf(UUID ticketId, String pdfUrl, String correlationId);

    /** Sends the emails of the due pending deliveries, at most {@code limit}, and counts what happened. */
    DispatchResult dispatchDue(int limit);

    record DispatchResult(int sent, int retried, int failed) {
    }
}
