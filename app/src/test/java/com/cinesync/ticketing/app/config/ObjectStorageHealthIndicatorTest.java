package com.cinesync.ticketing.app.config;

import com.cinesync.ticketing.core.model.PdfStorageException;
import com.cinesync.ticketing.core.port.out.TicketPdfStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ObjectStorageHealthIndicatorTest {

    private final TicketPdfStorage storage = mock(TicketPdfStorage.class);
    private final ObjectStorageHealthIndicator indicator = new ObjectStorageHealthIndicator(storage);

    @Test
    void aReachableBucketIsUp() {
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void anUnreachableBucketIsDownWithoutTheErrorOfTheStorage() {
        doThrow(new PdfStorageException("connect to rustfs:9000 refused")).when(storage).check();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails().toString()).doesNotContain("rustfs:9000");
    }
}
