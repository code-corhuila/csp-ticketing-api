package com.cinesync.ticketing.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserContactTest {

    private static final Instant T0 = Instant.parse("2026-10-16T15:00:00Z");

    @Test
    void of_storesTrimmedLowerCaseEmailAndEventTimestamp() {
        UserContact contact = UserContact.of(UUID.randomUUID(), "  User@Example.COM ", T0);

        assertThat(contact.getEmail()).isEqualTo("user@example.com");
        assertThat(contact.getLastUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void applyChange_newerEvent_updatesEmailAndTimestamp() {
        UserContact contact = UserContact.of(UUID.randomUUID(), "old@example.com", T0);

        boolean changed = contact.applyChange("new@example.com", T0.plusSeconds(60));

        assertThat(changed).isTrue();
        assertThat(contact.getEmail()).isEqualTo("new@example.com");
        assertThat(contact.getLastUpdatedAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void applyChange_olderEvent_isIgnored() {
        UserContact contact = UserContact.of(UUID.randomUUID(), "current@example.com", T0);

        boolean changed = contact.applyChange("stale@example.com", T0.minusSeconds(60));

        assertThat(changed).isFalse();
        assertThat(contact.getEmail()).isEqualTo("current@example.com");
        assertThat(contact.getLastUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void applyChange_sameInstant_isIgnored() {
        UserContact contact = UserContact.of(UUID.randomUUID(), "current@example.com", T0);

        assertThat(contact.applyChange("other@example.com", T0)).isFalse();
        assertThat(contact.getEmail()).isEqualTo("current@example.com");
    }
}
