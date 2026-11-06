package com.cinesync.ticketing.core.port.in;

import java.util.List;

/** One page of a list: the records of the page and the size of the whole list. */
public record PageResult<T>(List<T> data, int page, int limit, long total) {

    public int totalPages() {
        return (int) ((total + limit - 1) / limit);
    }
}
