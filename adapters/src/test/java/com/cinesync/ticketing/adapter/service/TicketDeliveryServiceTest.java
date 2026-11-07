package com.cinesync.ticketing.adapter.service;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.model.TicketingFulfillment.FulfillmentStatus;
import com.cinesync.ticketing.core.model.UserContact;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase.DispatchResult;
import com.cinesync.ticketing.core.port.out.EmailSenderPort;
import com.cinesync.ticketing.core.port.out.OutboxRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import com.cinesync.ticketing.core.port.out.TicketingFulfillmentRepository;
import com.cinesync.ticketing.core.port.out.UserContactRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HU-TICKETING-004: one delivery that cannot be processed does not stop the others of the same run. */
class TicketDeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TicketingFulfillmentRepository fulfillments = mock(TicketingFulfillmentRepository.class);
    private final UserContactRepository contacts = mock(UserContactRepository.class);
    private final EmailSenderPort emailSender = mock(EmailSenderPort.class);
    private final TicketDeliveryService service = new TicketDeliveryService(tickets, fulfillments, contacts,
            mock(OutboxRepository.class), emailSender, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
            mock(PlatformTransactionManager.class), mock(NotificationRecorder.class));

    TicketDeliveryServiceTest() {
        ReflectionTestUtils.setField(service, "portalUrl", "http://localhost:4200");
    }

    private TicketingFulfillment due(UUID ticketId) {
        TicketingFulfillment fulfillment = TicketingFulfillment.builder().id(UUID.randomUUID()).ticketId(ticketId)
                .channel("EMAIL").status(FulfillmentStatus.PENDING).attempts(0).build();
        when(fulfillments.claimDeliverable(fulfillment.getId(), NOW)).thenReturn(Optional.of(fulfillment));
        return fulfillment;
    }

    @Test
    void aDeliveryWhoseTicketIsGone_isSkippedAndTheRestOfTheRunIsProcessed() {
        UUID goneTicketId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Ticket ticket = Ticket.builder().id(UUID.randomUUID()).userId(userId).movieTitleSnapshot("Example Movie")
                .roomNameSnapshot("Room 1").seatLabelsSnapshot(List.of("A1")).userContactId(UUID.randomUUID()).build();
        TicketingFulfillment gone = due(goneTicketId);
        TicketingFulfillment healthy = due(ticket.getId());
        when(fulfillments.findDeliverable(NOW, 10)).thenReturn(List.of(gone, healthy));
        when(tickets.findById(goneTicketId)).thenReturn(Optional.empty());
        when(tickets.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(contacts.findByUserId(userId)).thenReturn(Optional.of(UserContact.of(userId, "client@example.com", NOW)));

        DispatchResult result = service.dispatchDue(10);

        assertThat(result).isEqualTo(new DispatchResult(1, 0, 0));
        verify(emailSender).sendTicketEmail(eq("client@example.com"), any(), any());
        verify(fulfillments).save(healthy);
        verify(fulfillments, never()).save(gone);
        assertThat(healthy.getStatus()).isEqualTo(FulfillmentStatus.SENT);
    }
}
