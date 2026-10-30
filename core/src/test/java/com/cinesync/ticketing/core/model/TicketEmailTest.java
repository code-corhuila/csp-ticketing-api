package com.cinesync.ticketing.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TicketEmailTest {

    private static Ticket.TicketBuilder ticket() {
        return Ticket.builder()
                .id(UUID.randomUUID())
                .movieTitleSnapshot("Example Movie")
                .roomNameSnapshot("Room 1")
                .seatLabelsSnapshot(List.of("A1", "A2"))
                .pdfUrl("https://files.example.com/tickets/1.pdf");
    }

    @Test
    void email_hasTheTicketDetailsAndTheLinkToThePdf() {
        TicketEmail email = TicketEmail.of(ticket().showtimeStartsAt(Instant.parse("2026-11-01T20:00:00Z")).build());

        assertThat(email.subject()).isEqualTo("Your CineSync ticket for Example Movie");
        assertThat(email.htmlBody()).contains("Example Movie", "Room 1", "2026-11-01T20:00:00Z", "A1, A2",
                "href=\"https://files.example.com/tickets/1.pdf\"");
    }

    @Test
    void showtimeWithoutAStart_isToBeConfirmed() {
        assertThat(TicketEmail.of(ticket().build()).htmlBody()).contains("To be confirmed");
    }

    @Test
    void concessions_appearOnlyWhenTheTicketHasThem() {
        Ticket.ConcessionItem popcorn = Ticket.ConcessionItem.builder().name("Popcorn").quantity(2).build();

        assertThat(TicketEmail.of(ticket().concessionsSnapshot(List.of(popcorn)).build()).htmlBody())
                .contains("Popcorn x2");
        assertThat(TicketEmail.of(ticket().build()).htmlBody()).doesNotContain("Concessions");
    }

    @Test
    void valuesFromOtherServices_areEscaped() {
        TicketEmail email = TicketEmail.of(ticket().movieTitleSnapshot("<script>x</script>\nSecond").build());

        assertThat(email.htmlBody()).doesNotContain("<script>").contains("&lt;script&gt;");
        assertThat(email.subject()).doesNotContain("\n");
    }

    @Test
    void attachPdf_recordsTheUrlOnlyOnce() {
        Ticket ticket = Ticket.builder().build();

        assertThat(ticket.attachPdf("https://files.example.com/a.pdf")).isTrue();
        assertThat(ticket.attachPdf("https://files.example.com/b.pdf")).isFalse();
        assertThat(ticket.getPdfUrl()).isEqualTo("https://files.example.com/a.pdf");
    }
}
