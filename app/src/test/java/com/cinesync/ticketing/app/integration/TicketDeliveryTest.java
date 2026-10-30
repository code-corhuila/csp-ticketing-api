package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketNotFoundException;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HU-TICKETING-002: the PDF is recorded together with its TicketIssued row in the outbox. */
class TicketDeliveryTest extends ProjectionTestSupport {

    private static final String PDF_URL = "https://files.example.com/tickets/1.pdf";

    @Autowired
    private TicketDeliveryUseCase delivery;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void cleanTicketsAndOutbox() {
        jdbc.update("DELETE FROM ticketing_fulfillment.ticketing_fulfillments");
        jdbc.update("DELETE FROM ticketing_fulfillment.tickets");
        jdbc.update("DELETE FROM ticketing_fulfillment.outbox_event");
    }

    private Ticket ticket() {
        return tickets.save(Ticket.builder()
                .userId(UUID.randomUUID())
                .reservationId(UUID.randomUUID())
                .showtimeId(UUID.randomUUID())
                .movieTitleSnapshot("Example Movie")
                .roomNameSnapshot("Room 1")
                .seatLabelsSnapshot(List.of("A1"))
                .qrPayload("CSP-test")
                .build());
    }

    @Test
    void registerPdf_recordsTheUrlAndWritesOneTicketIssuedRow() throws Exception {
        Ticket ticket = ticket();

        delivery.registerPdf(ticket.getId(), PDF_URL, "corr-1");
        delivery.registerPdf(ticket.getId(), PDF_URL, "corr-2");

        assertThat(tickets.findById(ticket.getId()).orElseThrow().getPdfUrl()).isEqualTo(PDF_URL);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticketing_fulfillment.outbox_event", Integer.class))
                .isEqualTo(1);
        var row = jdbc.queryForMap("SELECT id, aggregate_id, event_type, payload::text AS payload "
                + "FROM ticketing_fulfillment.outbox_event");
        JsonNode envelope = mapper.readTree((String) row.get("payload"));
        assertThat(row.get("event_type")).isEqualTo("TicketIssued");
        assertThat(envelope.get("eventId").asText()).isEqualTo(row.get("id").toString());
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(ticket.getId().toString());
        assertThat(envelope.get("source").asText()).isEqualTo("ticketing-fulfillment-service");
        assertThat(envelope.get("metadata").get("correlationId").asText()).isEqualTo("corr-1");
        assertThat(envelope.get("payload").get("reservationId").asText()).isEqualTo(ticket.getReservationId().toString());
        assertThat(envelope.get("payload").get("pdfUrl").asText()).isEqualTo(PDF_URL);
        assertThat(Instant.parse(envelope.get("payload").get("issuedAt").asText())).isNotNull();
    }

    @Test
    void registerPdf_unknownTicket_isRejected() {
        assertThatThrownBy(() -> delivery.registerPdf(UUID.randomUUID(), PDF_URL, "corr"))
                .isInstanceOf(TicketNotFoundException.class);
    }
}
