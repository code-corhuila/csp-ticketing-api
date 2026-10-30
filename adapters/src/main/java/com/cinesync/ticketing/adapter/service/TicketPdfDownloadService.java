package com.cinesync.ticketing.adapter.service;

import com.cinesync.ticketing.core.model.PdfStorageException;
import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketNotFoundException;
import com.cinesync.ticketing.core.port.in.TicketPdfDownloadUseCase;
import com.cinesync.ticketing.core.port.out.TicketPdfStorage;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketPdfDownloadService implements TicketPdfDownloadUseCase {

    private final TicketRepository ticketRepository;
    private final TicketPdfStorage storage;

    @Override
    @Transactional(readOnly = true)
    public byte[] download(UUID userId, UUID ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .filter(found -> found.getUserId().equals(userId) && found.getPdfUrl() != null)
                .orElseThrow(() -> new TicketNotFoundException(ticketId));
        // The key is immutable and follows from the ids (ADR-058); pdf_url only says that the file exists.
        String key = "tickets/" + ticket.getReservationId() + "/" + ticket.getId() + ".pdf";
        return storage.read(key).orElseThrow(() -> new PdfStorageException("The recorded PDF is missing from the storage"));
    }
}
