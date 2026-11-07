package com.cinesync.ticketing.adapter.service;

import com.cinesync.ticketing.core.model.PdfStorageException;
import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketNotFoundException;
import com.cinesync.ticketing.core.port.out.TicketPdfStorage;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class TicketPdfDownloadServiceTest {

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TicketPdfStorage storage = mock(TicketPdfStorage.class);
    private final TicketPdfDownloadService service = new TicketPdfDownloadService(tickets, storage);

    private final UUID owner = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();

    private Ticket ticket(String pdfUrl) {
        return Ticket.builder().id(ticketId).userId(owner).reservationId(reservationId).pdfUrl(pdfUrl).build();
    }

    private String key() {
        return "tickets/" + reservationId + "/" + ticketId + ".pdf";
    }

    @Test
    void theOwnerGetsTheBytesOfTheObjectOfTheImmutableKey() {
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket("http://storage/tickets/x.pdf")));
        when(storage.read(key())).thenReturn(Optional.of(new byte[]{1, 2, 3}));

        assertThat(service.download(owner, ticketId)).containsExactly(1, 2, 3);
    }

    @Test
    void anotherUserOrAnUnknownTicketOrATicketWithoutPdfIsNotFoundAndTheStorageIsNotTouched() {
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket("http://storage/tickets/x.pdf")));
        UUID unknown = UUID.randomUUID();
        when(tickets.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.download(UUID.randomUUID(), ticketId)).isInstanceOf(TicketNotFoundException.class);
        assertThatThrownBy(() -> service.download(owner, unknown)).isInstanceOf(TicketNotFoundException.class);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket(null)));
        assertThatThrownBy(() -> service.download(owner, ticketId)).isInstanceOf(TicketNotFoundException.class);

        verify(storage, never()).read(anyString());
    }

    @Test
    void aRecordedPdfThatIsMissingInTheStorageIsAStorageFailure() {
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket("http://storage/tickets/x.pdf")));
        when(storage.read(key())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.download(owner, ticketId)).isInstanceOf(PdfStorageException.class);
    }
}
