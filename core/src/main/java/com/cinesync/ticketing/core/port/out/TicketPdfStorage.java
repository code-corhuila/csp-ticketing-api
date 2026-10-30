package com.cinesync.ticketing.core.port.out;

import java.util.Optional;

/** Where the ticket PDFs are stored (ADR-058). The API only reads; the csp-worker writes. */
public interface TicketPdfStorage {

    /**
     * @return the bytes of the object, or empty when there is none with that key
     * @throws com.cinesync.ticketing.core.model.PdfStorageException when the storage cannot answer
     */
    Optional<byte[]> read(String key);

    /** @throws com.cinesync.ticketing.core.model.PdfStorageException when the bucket cannot be reached with the account of the API */
    void check();
}
