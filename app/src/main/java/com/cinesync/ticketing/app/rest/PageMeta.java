package com.cinesync.ticketing.app.rest;

/** The pagination metadata of every list (PaginatedMeta of the shared contract). */
public record PageMeta(int page, int limit, long total, int totalPages) {
}
