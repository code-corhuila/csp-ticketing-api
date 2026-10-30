package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.model.UserContact;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import com.cinesync.ticketing.core.port.out.TicketingFulfillmentRepository;
import com.cinesync.ticketing.core.port.out.UserContactRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** HU-TICKETING-002, the BookingConfirmed side: the ticket is stored whether or not the contact exists yet. */
class BookingConfirmedConsumerTest extends ProjectionTestSupport {

    private static final String BOOKING_KEY = "cine.booking.booking-confirmed.v1";
    private static final String CONCESSION_KEY = "cine.concessions.order-confirmed.v1";
    private static final String BOOKING_QUEUE = "cine.ticketing_fulfillment.booking-confirmed";
    private static final Instant STARTS_AT = Instant.parse("2026-11-01T20:00:00Z");

    @Autowired
    private UserContactRepository contacts;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private TicketingFulfillmentRepository fulfillments;

    @Autowired
    private InboxEventRepository inbox;

    private static String bookingConfirmed(UUID eventId, UUID reservationId, UUID userId, String startsAt) {
        return """
                {"eventId":"%s","eventType":"BookingConfirmed","occurredAt":"2026-10-30T15:00:00Z","version":1,
                 "source":"booking-service","aggregateId":"%s","aggregateType":"Reservation",
                 "payload":{"reservationId":"%s","userId":"%s","showtimeId":"%s","movieTitle":"Example Movie",
                            "roomName":"Room 1","seatNumbers":["A1","A2"],"showtimeStartsAt":%s},
                 "metadata":{"correlationId":"%s"}}
                """.formatted(eventId, reservationId, reservationId, userId, UUID.randomUUID(), startsAt,
                UUID.randomUUID());
    }

    private static String quoted(Instant instant) {
        return "\"" + instant + "\"";
    }

    private static String concessionOrderConfirmed(UUID reservationId) {
        return """
                {"eventId":"%s","eventType":"ConcessionOrderConfirmed","occurredAt":"2026-10-30T15:00:00Z",
                 "version":1,"source":"concessions-service","aggregateId":"%s",
                 "payload":{"reservationId":"%s","items":[{"itemName":"Popcorn","quantity":2,"unitPrice":500}]},
                 "metadata":{"correlationId":"%s"}}
                """.formatted(UUID.randomUUID(), reservationId, reservationId, UUID.randomUUID());
    }

    private Ticket awaitTicket(UUID reservationId) {
        await().atMost(15, TimeUnit.SECONDS).until(() -> tickets.findByReservationId(reservationId).isPresent());
        return tickets.findByReservationId(reservationId).orElseThrow();
    }

    private UserContact registeredContact(UUID userId) {
        publish(REGISTERED_KEY, event(UUID.randomUUID(), "UserRegistered", userId, "client@example.com", T0));
        await().atMost(15, TimeUnit.SECONDS).until(() -> contacts.findByUserId(userId).isPresent());
        return contacts.findByUserId(userId).orElseThrow();
    }

    @Test
    void contactResolvable_ticketKeepsContactAndDeliveryIsPending() {
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UserContact contact = registeredContact(userId);

        publish(BOOKING_KEY, bookingConfirmed(UUID.randomUUID(), reservationId, userId, quoted(STARTS_AT)));

        Ticket ticket = awaitTicket(reservationId);
        assertThat(ticket.getUserId()).isEqualTo(userId);
        assertThat(ticket.getUserContactId()).isEqualTo(contact.getId());
        assertThat(ticket.getMovieTitleSnapshot()).isEqualTo("Example Movie");
        assertThat(ticket.getRoomNameSnapshot()).isEqualTo("Room 1");
        assertThat(ticket.getShowtimeStartsAt()).isEqualTo(STARTS_AT);
        assertThat(ticket.getSeatLabelsSnapshot()).containsExactly("A1", "A2");
        assertThat(ticket.getConcessionsSnapshot()).isNull();
        assertThat(fulfillments.findByTicketId(ticket.getId())).hasValueSatisfying(f -> {
            assertThat(f.getStatus()).isEqualTo(TicketingFulfillment.FulfillmentStatus.PENDING);
            assertThat(f.getAttempts()).isZero();
        });
    }

    @Test
    void contactNotResolvableYet_ticketIsStoredAndDeliveryStaysPending() {
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publish(BOOKING_KEY, bookingConfirmed(eventId, reservationId, userId, quoted(STARTS_AT)));

        Ticket ticket = awaitTicket(reservationId);
        assertThat(ticket.getUserId()).isEqualTo(userId);
        assertThat(ticket.getUserContactId()).isNull();
        assertThat(fulfillments.findByTicketId(ticket.getId())).hasValueSatisfying(
                f -> assertThat(f.getStatus()).isEqualTo(TicketingFulfillment.FulfillmentStatus.PENDING));
        assertThat(inbox.findByEventId(eventId)).isPresent();
        assertThat(rabbitTemplate.receive(BOOKING_QUEUE + ".dlq", 500)).isNull();
    }

    @Test
    void showtimeStartNull_ticketIsStoredWithoutStart() {
        UUID reservationId = UUID.randomUUID();

        publish(BOOKING_KEY, bookingConfirmed(UUID.randomUUID(), reservationId, UUID.randomUUID(), "null"));

        assertThat(awaitTicket(reservationId).getShowtimeStartsAt()).isNull();
    }

    @Test
    void concessionOrderConfirmedBeforeTheBooking_isAttachedToTheTicket() {
        UUID reservationId = UUID.randomUUID();
        publish(CONCESSION_KEY, concessionOrderConfirmed(reservationId));
        UUID barrier = UUID.randomUUID();
        publish(BOOKING_KEY, bookingConfirmed(barrier, UUID.randomUUID(), UUID.randomUUID(), "null"));
        await().atMost(15, TimeUnit.SECONDS).until(() -> inbox.findByEventId(barrier).isPresent());

        publish(BOOKING_KEY, bookingConfirmed(UUID.randomUUID(), reservationId, UUID.randomUUID(), "null"));

        Ticket ticket = awaitTicket(reservationId);
        assertThat(ticket.getConcessionsSnapshot()).singleElement().satisfies(item -> {
            assertThat(item.getName()).isEqualTo("Popcorn");
            assertThat(item.getQuantity()).isEqualTo(2);
        });
    }

    @Test
    void concessionOrderConfirmedAfterTheBooking_isAttachedToTheTicket() {
        UUID reservationId = UUID.randomUUID();
        publish(BOOKING_KEY, bookingConfirmed(UUID.randomUUID(), reservationId, UUID.randomUUID(), "null"));
        awaitTicket(reservationId);

        publish(CONCESSION_KEY, concessionOrderConfirmed(reservationId));

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> assertThat(
                tickets.findByReservationId(reservationId).orElseThrow().getConcessionsSnapshot()).hasSize(1));
    }

    @Test
    void duplicateDelivery_createsOneTicket() {
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String message = bookingConfirmed(eventId, reservationId, UUID.randomUUID(), "null");
        publish(BOOKING_KEY, message);
        Ticket first = awaitTicket(reservationId);

        publish(BOOKING_KEY, message);
        UUID barrier = UUID.randomUUID();
        publish(BOOKING_KEY, bookingConfirmed(barrier, UUID.randomUUID(), UUID.randomUUID(), "null"));
        await().atMost(15, TimeUnit.SECONDS).until(() -> inbox.findByEventId(barrier).isPresent());

        assertThat(tickets.findByReservationId(reservationId).orElseThrow().getId()).isEqualTo(first.getId());
        assertThat(rabbitTemplate.receive(BOOKING_QUEUE + ".dlq", 500)).isNull();
    }

    @Test
    void invalidMessages_goToTheDeadLetterQueueAndCreateNothing() {
        UUID reservationId = UUID.randomUUID();
        String noSeats = bookingConfirmed(UUID.randomUUID(), reservationId, UUID.randomUUID(), "null")
                .replace("[\"A1\",\"A2\"]", "[]");

        publish(BOOKING_KEY, "{\"eventId\":");
        publish(BOOKING_KEY, noSeats);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(rabbitTemplate.receive(BOOKING_QUEUE + ".dlq", 1000)).isNotNull();
            assertThat(rabbitTemplate.receive(BOOKING_QUEUE + ".dlq", 1000)).isNotNull();
        });
        assertThat(tickets.findByReservationId(reservationId)).isEmpty();
    }
}
