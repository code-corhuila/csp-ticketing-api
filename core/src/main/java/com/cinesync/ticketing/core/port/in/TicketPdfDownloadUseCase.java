package com.cinesync.ticketing.core.port.in;

import java.util.UUID;

public interface TicketPdfDownloadUseCase {

    /**
     * The PDF of a ticket of the given user (ADR-058).
     *
     * @throws com.cinesync.ticketing.core.model.TicketNotFoundException when the ticket does not exist, belongs to
     *     another user (the same answer, so ids cannot be probed) or has no PDF yet
     * @throws com.cinesync.ticketing.core.model.PdfStorageException when the storage cannot give the file
     */
    byte[] download(UUID userId, UUID ticketId);
}
