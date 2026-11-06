package com.cinesync.ticketing.app.rest.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfUrlPolicyTest {

    private final PdfUrlPolicy policy = new PdfUrlPolicy("http://rustfs:9000", "tickets");

    @Test
    void theReferenceTheWorkerReportsIsAccepted() {
        assertThat(policy.accepts("s3://tickets/tickets/r/t.pdf")).isTrue();
    }

    @Test
    void anUrlOnTheHostAndPortOfTheObjectStorageIsAccepted() {
        assertThat(policy.accepts("http://rustfs:9000/tickets/tickets/r/t.pdf")).isTrue();
        assertThat(policy.accepts("http://RUSTFS:9000/tickets/t.pdf")).isTrue();
        assertThat(policy.accepts("https://rustfs:9000/tickets/t.pdf")).isTrue();
    }

    @Test
    void anotherBucketHostOrPortIsRefused() {
        assertThat(policy.accepts("s3://other/tickets/t.pdf")).isFalse();
        assertThat(policy.accepts("http://evil.example.com/tickets/t.pdf")).isFalse();
        assertThat(policy.accepts("http://rustfs.evil.example:9000/tickets/t.pdf")).isFalse();
        assertThat(policy.accepts("http://rustfs:9001/tickets/t.pdf")).isFalse();
        assertThat(policy.accepts("https://rustfs/tickets/t.pdf")).isFalse();
    }

    @Test
    void credentialsInTheReferenceMakeTheHostAnotherOne() {
        assertThat(policy.accepts("http://rustfs:9000@evil.example.com/tickets/t.pdf")).isFalse();
        assertThat(policy.accepts("http://user:pass@rustfs:9000/tickets/t.pdf")).isFalse();
        assertThat(policy.accepts("s3://user@tickets/t.pdf")).isFalse();
    }

    @Test
    void otherSchemesEmptyKeysAndTraversalAreRefused() {
        assertThat(policy.accepts("ftp://rustfs:9000/tickets/t.pdf")).isFalse();
        assertThat(policy.accepts("javascript:alert(1)")).isFalse();
        assertThat(policy.accepts("//rustfs:9000/tickets/t.pdf")).isFalse();
        assertThat(policy.accepts("s3://tickets")).isFalse();
        assertThat(policy.accepts("s3://tickets/")).isFalse();
        assertThat(policy.accepts("s3://tickets/../other/t.pdf")).isFalse();
        assertThat(policy.accepts("http://rustfs:9000/tickets/t.pdf#evil")).isFalse();
        assertThat(policy.accepts("not a url")).isFalse();
        assertThat(policy.accepts("")).isFalse();
        assertThat(policy.accepts(null)).isFalse();
    }

    @Test
    void theServiceDoesNotStartWithoutTheStorageAndTheBucket() {
        assertThatThrownBy(() -> new PdfUrlPolicy("", "tickets")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PdfUrlPolicy("http://rustfs:9000", " ")).isInstanceOf(IllegalStateException.class);
    }
}
