package com.cinesync.ticketing.core.model;

/** The object storage cannot give the PDF now: it is down, refuses the account, or the file is missing. */
public class PdfStorageException extends RuntimeException {

    public PdfStorageException(String message) {
        super(message);
    }

    public PdfStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
