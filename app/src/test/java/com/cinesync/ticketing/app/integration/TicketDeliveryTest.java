package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketNotFoundException;
import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.model.TicketingFulfillment.FulfillmentStatus;
import com.cinesync.ticketing.core.model.UserContact;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase.DispatchResult;
import com.cinesync.ticketing.core.port.in.TicketToRender;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
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

    @Test
    void overlappingDispatchRuns_sendEachEmailOnce() throws Exception {
        int tickets = 10;
        for (int i = 0; i < tickets; i++) {
            UUID userId = UUID.randomUUID();
            contactFor(userId);
            pendingFor(ticket(userId, PDF_URL));
        }
        // A slow provider keeps the first run inside its send long enough for the second to read the same rows.
        doAnswer(invocation -> {
            Thread.sleep(50);
            return null;
        }).when(emailSender).sendTicketEmail(any(), any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<DispatchResult> run = () -> {
                start.await();
                return delivery.dispatchDue(tickets);
            };
            List<Future<DispatchResult>> runs = new ArrayList<>();
            runs.add(pool.submit(run));
            runs.add(pool.submit(run));
            start.countDown();
            for (Future<DispatchResult> result : runs) {
                result.get();
            }
        } finally {
            pool.shutdownNow();
        }

        verify(emailSender, times(tickets)).sendTicketEmail(any(), any(), any());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ticketing_fulfillment.ticketing_fulfillments WHERE status = 'SENT'",
                Integer.class)).isEqualTo(tickets);
    }

    private void age(Ticket ticket, int seconds) {
        jdbc.update("UPDATE ticketing_fulfillment.tickets SET created_at = now() - make_interval(secs => ?) WHERE id = ?",
                seconds, ticket.getId());
    }

    @Test
    void awaitingPdf_listsTheOldEnoughTicketsWithoutPdfOldestFirstWithWhatTheDocumentNeeds() {
        Ticket older = ticket(UUID.randomUUID(), null);
        Ticket old = tickets.save(Ticket.builder().userId(UUID.randomUUID()).reservationId(UUID.randomUUID())
                .showtimeId(UUID.randomUUID()).movieTitleSnapshot("Another Movie").roomNameSnapshot("Room 2")
                .showtimeStartsAt(Instant.parse("2026-11-01T20:00:00Z")).seatLabelsSnapshot(List.of("B1", "B2"))
                .concessionsSnapshot(List.of(Ticket.ConcessionItem.builder().name("Popcorn").quantity(2)
                        .unitPriceCents(500).build()))
                .qrPayload("CSP-old").build());
        Ticket fresh = ticket(UUID.randomUUID(), null);
        Ticket withPdf = ticket(UUID.randomUUID(), PDF_URL);
        age(older, 300);
        age(old, 120);
        age(withPdf, 300);

        List<TicketToRender> awaiting = delivery.awaitingPdf(10);

        assertThat(awaiting).extracting(TicketToRender::ticketId).containsExactly(older.getId(), old.getId());
        TicketToRender data = awaiting.get(1);
        assertThat(data.reservationId()).isEqualTo(old.getReservationId());
        assertThat(data.movieTitle()).isEqualTo("Another Movie");
        assertThat(data.roomName()).isEqualTo("Room 2");
        assertThat(data.showtimeStartsAt()).isEqualTo(Instant.parse("2026-11-01T20:00:00Z"));
        assertThat(data.seatNumbers()).containsExactly("B1", "B2");
        assertThat(data.qrPayload()).isEqualTo("CSP-old");
        assertThat(data.concessions()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("Popcorn");
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.unitPriceCents()).isEqualTo(500);
        });
        assertThat(awaiting.get(0).concessions()).isEmpty();
        assertThat(awaiting).extracting(TicketToRender::ticketId).doesNotContain(fresh.getId(), withPdf.getId());
    }

    @Test
    void awaitingPdf_returnsAtMostTheLimit() {
        age(ticket(UUID.randomUUID(), null), 300);
        age(ticket(UUID.randomUUID(), null), 200);
        age(ticket(UUID.randomUUID(), null), 100);

        assertThat(delivery.awaitingPdf(2)).hasSize(2);
    }

    private void makeDue(TicketingFulfillment fulfillment) {
        jdbc.update("UPDATE ticketing_fulfillment.ticketing_fulfillments SET next_retry_at = now() - interval '1 second' "
                + "WHERE id = ?", fulfillment.getId());
    }
}
