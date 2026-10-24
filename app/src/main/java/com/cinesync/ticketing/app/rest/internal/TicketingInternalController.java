package com.cinesync.ticketing.app.rest.internal;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.port.in.TicketingCommandUseCase;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import com.cinesync.ticketing.core.port.out.TicketingFulfillmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/internal/tickets")
@RequiredArgsConstructor
public class TicketingInternalController {

    private final TicketRepository ticketRepository;
    private final TicketingFulfillmentRepository fulfillmentRepository;

    @PostMapping("/{ticketId}/pdf")
    public ResponseEntity<Void> receivePdfUrl(
            @PathVariable UUID ticketId,
            @RequestBody PdfUrlRequest request) {

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));

        ticket.setPdfUrl(request.pdfUrl());
        ticketRepository.save(ticket);

        fulfillmentRepository.findByTicketId(ticketId).ifPresent(fulfillment -> {
            fulfillment.setStatus(TicketingFulfillment.FulfillmentStatus.SENT);
            fulfillment.setLastError(null);
            // fulfillmentRepository.save(fulfillment); // handled by transaction
        });

        return ResponseEntity.ok().build();
    }

    public record PdfUrlRequest(String pdfUrl) {}
}