package com.cinesync.ticketing.app.rest;

import java.util.List;
import java.util.UUID;

/** The single error envelope of the platform: {@code error}, {@code message}, {@code details} and {@code traceId}. */
public record ErrorResponse(String error, String message, List<String> details, String traceId) {

    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, List.of(), UUID.randomUUID().toString());
    }
}
