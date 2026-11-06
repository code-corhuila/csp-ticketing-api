package com.cinesync.ticketing.app.rest;

/** {@code page} or {@code limit} of a list is out of range (page from 1, limit from 1 to {@value #MAX_LIMIT}). */
public class InvalidPaginationException extends RuntimeException {

    public static final int MAX_LIMIT = 100;

    public InvalidPaginationException() {
        super("page must be 1 or more and limit between 1 and " + MAX_LIMIT);
    }

    /** Fails when {@code page} or {@code limit} is out of range. */
    public static void check(int page, int limit) {
        if (page < 1 || limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidPaginationException();
        }
    }
}
