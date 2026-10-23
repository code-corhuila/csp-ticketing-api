package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.Ticket;

public interface PdfGeneratorPort {
    byte[] generateTicketPdf(Ticket ticket);
}