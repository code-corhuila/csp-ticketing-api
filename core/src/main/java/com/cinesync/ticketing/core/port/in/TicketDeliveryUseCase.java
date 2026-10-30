package com.cinesync.ticketing.core.port.in;

import java.util.UUID;

/** The two internal operations the csp-worker calls to finish a ticket (ADR-057). */
public interface TicketDeliveryUseCase {

    /** Records the URL of the stored PDF and writes TicketIssued to the outbox, once per ticket. */
    void registerPdf(UUID ticketId, String pdfUrl, String correlationId);

    /** Sends the emails of the due pending deliveries, at most {@code limit}, and counts what happened. */
    DispatchResult dispatchDue(int limit);

    record DispatchResult(int sent, int retried, int failed) {
    }
}
