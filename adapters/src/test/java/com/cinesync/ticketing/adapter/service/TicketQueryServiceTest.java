package com.cinesync.ticketing.adapter.service;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.model.TicketingFulfillment.FulfillmentStatus;
import com.cinesync.ticketing.core.port.in.PageResult;
import com.cinesync.ticketing.core.port.in.TicketQueryUseCase.TicketView;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import com.cinesync.ticketing.core.port.out.TicketingFulfillmentRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketQueryServiceTest {

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TicketingFulfillmentRepository fulfillments = mock(TicketingFulfillmentRepository.class);
    private final TicketQueryService service = new TicketQueryService(tickets, fulfillments);
    private final UUID user = UUID.randomUUID();

    private Ticket ticket(String pdfUrl, List<Ticket.ConcessionItem> concessions, Instant startsAt) {
        return Ticket.builder().id(UUID.randomUUID()).userId(user).reservationId(UUID.randomUUID()).showtimeId(UUID.randomUUID())
                .movieTitleSnapshot("Example Movie").roomNameSnapshot("Sala 1").showtimeStartsAt(startsAt)
                .seatLabelsSnapshot(List.of("A1", "A2")).concessionsSnapshot(concessions).qrPayload("CSP-1").pdfUrl(pdfUrl)
                .createdAt(Instant.parse("2026-11-06T20:00:00Z")).build();
    }

    private TicketingFulfillment delivery(Ticket ticket, FulfillmentStatus status) {
        return TicketingFulfillment.builder().ticketId(ticket.getId()).channel("EMAIL").status(status).attempts(0).build();
    }

    @Test
    void theViewCarriesTheTicketItsConcessionsWithoutPricesAndTheStateOfItsDelivery() {
        Ticket withPdf = ticket("s3://tickets/tickets/r/t.pdf",
                List.of(new Ticket.ConcessionItem("Popcorn", 2, 500)), Instant.parse("2026-12-01T20:00:00Z"));
        when(tickets.findPageByUserId(user, 20, 0)).thenReturn(List.of(withPdf));
        when(tickets.countByUserId(user)).thenReturn(1L);
        when(fulfillments.findByTicketIdIn(anyCollection())).thenReturn(List.of(delivery(withPdf, FulfillmentStatus.SENT)));

        PageResult<TicketView> page = service.listMine(user, 1, 20);

        assertThat(page.data()).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo(withPdf.getId());
            assertThat(view.movieTitle()).isEqualTo("Example Movie");
            assertThat(view.roomName()).isEqualTo("Sala 1");
            assertThat(view.showtimeStartsAt()).isEqualTo(Instant.parse("2026-12-01T20:00:00Z"));
            assertThat(view.seatLabels()).containsExactly("A1", "A2");
            assertThat(view.concessions()).singleElement().satisfies(c -> {
                assertThat(c.name()).isEqualTo("Popcorn");
                assertThat(c.quantity()).isEqualTo(2);
            });
            assertThat(view.hasPdf()).isTrue();
            assertThat(view.deliveryStatus()).isEqualTo(FulfillmentStatus.SENT);
            assertThat(view.createdAt()).isEqualTo(Instant.parse("2026-11-06T20:00:00Z"));
        });
    }

    @Test
    void aTicketWithoutPdfConcessionsOrDeliveryHasNoneOfThem() {
        Ticket bare = ticket(null, null, null);
        when(tickets.findPageByUserId(user, 20, 0)).thenReturn(List.of(bare));
        when(tickets.countByUserId(user)).thenReturn(1L);
        when(fulfillments.findByTicketIdIn(anyCollection())).thenReturn(List.of());

        TicketView view = service.listMine(user, 1, 20).data().get(0);

        assertThat(view.hasPdf()).isFalse();
        assertThat(view.concessions()).isEmpty();
        assertThat(view.deliveryStatus()).isNull();
        assertThat(view.showtimeStartsAt()).isNull();
    }

    @Test
    void thePageStartsAtTheOffsetOfItsNumberAndCarriesTheTotal() {
        when(tickets.findPageByUserId(user, 10, 20)).thenReturn(List.of());
        when(tickets.countByUserId(user)).thenReturn(25L);
        when(fulfillments.findByTicketIdIn(anyCollection())).thenReturn(List.of());

        PageResult<TicketView> page = service.listMine(user, 3, 10);

        assertThat(page.data()).isEmpty();
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.total()).isEqualTo(25);
        assertThat(page.totalPages()).isEqualTo(3);
    }
}
