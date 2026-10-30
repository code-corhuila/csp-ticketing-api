package com.cinesync.ticketing.core.port.in;

import java.util.UUID;

/** The internal operations the csp-worker calls to finish a ticket (ADR-057). */
public interface TicketDeliveryUseCase {

    /** Records the URL of the stored PDF and writes TicketIssued to the outbox, once per ticket. */
    void registerPdf(UUID ticketId, String pdfUrl, String correlationId);
}
