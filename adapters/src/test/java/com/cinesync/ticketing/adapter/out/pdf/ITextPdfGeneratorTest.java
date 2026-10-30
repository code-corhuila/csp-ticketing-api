package com.cinesync.ticketing.adapter.out.pdf;

import com.cinesync.ticketing.core.model.Ticket;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ITextPdfGeneratorTest {

    private final ITextPdfGenerator generator = new ITextPdfGenerator();

    private static Ticket.TicketBuilder ticket() {
        UUID reservationId = UUID.randomUUID();
        return Ticket.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .reservationId(reservationId)
                .showtimeId(UUID.randomUUID())
                .movieTitleSnapshot("Example Movie")
                .roomNameSnapshot("Room 1")
                .seatLabelsSnapshot(List.of("A1", "A2"))
                .qrPayload("CSP-" + reservationId + "-A1-A2");
    }

    private String text(byte[] pdf) throws Exception {
        try (PdfDocument document = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                text.append(PdfTextExtractor.getTextFromPage(document.getPage(page))).append(' ');
            }
            return text.toString();
        }
    }

    @Test
    void ticketWithAStart_printsMovieRoomShowtimeAndSeats() throws Exception {
        String page = text(generator.generateTicketPdf(
                ticket().showtimeStartsAt(Instant.parse("2026-11-01T20:00:00Z")).build()));

        assertThat(page).contains("Example Movie", "Room 1", "2026-11-01T20:00:00Z", "A1, A2");
    }

    @Test
    void ticketWithoutAStart_printsToBeConfirmed() throws Exception {
        assertThat(text(generator.generateTicketPdf(ticket().build()))).contains("To be confirmed");
    }

    @Test
    void concessions_areListedOnlyWhenTheTicketHasThem() throws Exception {
        Ticket.ConcessionItem popcorn = Ticket.ConcessionItem.builder().name("Popcorn").quantity(2).build();

        assertThat(text(generator.generateTicketPdf(ticket().concessionsSnapshot(List.of(popcorn)).build())))
                .contains("Popcorn x2");
        assertThat(text(generator.generateTicketPdf(ticket().build()))).doesNotContain("Concessions");
    }
}
