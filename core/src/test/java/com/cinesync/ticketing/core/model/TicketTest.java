package com.cinesync.ticketing.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TicketTest {

    @Test
    void attachPdf_recordsTheUrlOnlyOnce() {
        Ticket ticket = Ticket.builder().build();

        assertThat(ticket.attachPdf("https://files.example.com/a.pdf")).isTrue();
        assertThat(ticket.attachPdf("https://files.example.com/b.pdf")).isFalse();
        assertThat(ticket.getPdfUrl()).isEqualTo("https://files.example.com/a.pdf");
    }
}
