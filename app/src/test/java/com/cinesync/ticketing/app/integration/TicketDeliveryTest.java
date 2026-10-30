package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketNotFoundException;
import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.model.TicketingFulfillment.FulfillmentStatus;
import com.cinesync.ticketing.core.model.UserContact;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase.DispatchResult;
import com.cinesync.ticketing.core.port.out.EmailSenderPort;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import com.cinesync.ticketing.core.port.out.TicketingFulfillmentRepository;
import com.cinesync.ticketing.core.port.out.UserContactRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/** HU-TICKETING-002: the PDF is recorded with its TicketIssued row, and the email waits for the contact. */
class TicketDeliveryTest extends ProjectionTestSupport {

    private static final String PDF_URL = "https://files.example.com/tickets/1.pdf";

    @Autowired
    private TicketDeliveryUseCase delivery;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private TicketingFulfillmentRepository fulfillments;

    @Autowired
    private UserContactRepository contacts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void cleanTicketsAndMail() {
        jdbc.update("DELETE FROM ticketing_fulfillment.ticketing_fulfillments");
        jdbc.update("DELETE FROM ticketing_fulfillment.tickets");
        jdbc.update("DELETE FROM ticketing_fulfillment.outbox_event");
        reset(emailSender);
    }

    private Ticket ticket(UUID userId, String pdfUrl) {
        return tickets.save(Ticket.builder()
                .userId(userId)
                .reservationId(UUID.randomUUID())
                .showtimeId(UUID.randomUUID())
                .movieTitleSnapshot("Example Movie")
                .roomNameSnapshot("Room 1")
                .seatLabelsSnapshot(List.of("A1"))
                .qrPayload("CSP-test")
                .pdfUrl(pdfUrl)
                .build());
    }

    private TicketingFulfillment pendingFor(Ticket ticket) {
        return fulfillments.save(TicketingFulfillment.builder()
                .ticketId(ticket.getId()).channel("EMAIL").status(FulfillmentStatus.PENDING).attempts(0).build());
    }

    private UserContact contactFor(UUID userId) {
        return contacts.save(UserContact.of(userId, "client@example.com", T0));
    }

    private TicketingFulfillment reload(TicketingFulfillment fulfillment) {
        return fulfillments.findByTicketId(fulfillment.getTicketId()).orElseThrow();
    }

    @Test
    void registerPdf_recordsTheUrlAndWritesOneTicketIssuedRow() throws Exception {
        Ticket ticket = ticket(UUID.randomUUID(), null);

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

    @Test
    void contactResolvable_emailIsSentAndTheDeliveryIsSent() {
        UUID userId = UUID.randomUUID();
        UserContact contact = contactFor(userId);
        Ticket ticket = ticket(userId, PDF_URL);
        TicketingFulfillment fulfillment = pendingFor(ticket);

        DispatchResult result = delivery.dispatchDue(10);

        assertThat(result).isEqualTo(new DispatchResult(1, 0, 0));
        verify(emailSender).sendTicketEmail(eq("client@example.com"), any(), any());
        assertThat(reload(fulfillment).getStatus()).isEqualTo(FulfillmentStatus.SENT);
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getUserContactId()).isEqualTo(contact.getId());
    }

    @Test
    void contactNotResolvableYet_staysPendingThenFailsAtTheThirdAttempt() {
        Ticket ticket = ticket(UUID.randomUUID(), PDF_URL);
        TicketingFulfillment fulfillment = pendingFor(ticket);

        assertThat(delivery.dispatchDue(10)).isEqualTo(new DispatchResult(0, 1, 0));
        TicketingFulfillment afterFirst = reload(fulfillment);
        assertThat(afterFirst.getStatus()).isEqualTo(FulfillmentStatus.PENDING);
        assertThat(afterFirst.getAttempts()).isEqualTo(1);
        assertThat(afterFirst.getNextRetryAt()).isAfter(Instant.now());

        assertThat(delivery.dispatchDue(10)).isEqualTo(new DispatchResult(0, 0, 0));

        makeDue(fulfillment);
        assertThat(delivery.dispatchDue(10)).isEqualTo(new DispatchResult(0, 1, 0));
        makeDue(fulfillment);
        assertThat(delivery.dispatchDue(10)).isEqualTo(new DispatchResult(0, 0, 1));
        assertThat(reload(fulfillment).getStatus()).isEqualTo(FulfillmentStatus.FAILED);
        verify(emailSender, never()).sendTicketEmail(any(), any(), any());
    }

    @Test
    void contactArrivesBetweenAttempts_emailIsSentOnTheNextOne() {
        UUID userId = UUID.randomUUID();
        TicketingFulfillment fulfillment = pendingFor(ticket(userId, PDF_URL));
        delivery.dispatchDue(10);

        contactFor(userId);
        makeDue(fulfillment);

        assertThat(delivery.dispatchDue(10)).isEqualTo(new DispatchResult(1, 0, 0));
        assertThat(reload(fulfillment).getStatus()).isEqualTo(FulfillmentStatus.SENT);
    }

    @Test
    void ticketWithoutPdf_isNotDispatchedNorCounted() {
        UUID userId = UUID.randomUUID();
        contactFor(userId);
        TicketingFulfillment fulfillment = pendingFor(ticket(userId, null));

        assertThat(delivery.dispatchDue(10)).isEqualTo(new DispatchResult(0, 0, 0));
        assertThat(reload(fulfillment).getAttempts()).isZero();
    }

    @Test
    void emailFailure_isCountedAsAnAttemptWithTheErrorKept() {
        UUID userId = UUID.randomUUID();
        contactFor(userId);
        TicketingFulfillment fulfillment = pendingFor(ticket(userId, PDF_URL));
        doThrow(new IllegalStateException("smtp down")).when(emailSender).sendTicketEmail(any(), any(), any());

        assertThat(delivery.dispatchDue(10)).isEqualTo(new DispatchResult(0, 1, 0));

        TicketingFulfillment reloaded = reload(fulfillment);
        assertThat(reloaded.getStatus()).isEqualTo(FulfillmentStatus.PENDING);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getLastError()).contains("IllegalStateException");
    }

    private void makeDue(TicketingFulfillment fulfillment) {
        jdbc.update("UPDATE ticketing_fulfillment.ticketing_fulfillments SET next_retry_at = now() - interval '1 second' "
                + "WHERE id = ?", fulfillment.getId());
    }
}
