package com.cinesync.ticketing.app.rest;

import com.cinesync.ticketing.core.model.PdfStorageException;
import com.cinesync.ticketing.core.model.TicketNotFoundException;
import com.cinesync.ticketing.core.port.in.TicketPdfDownloadUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The download of the ticket PDF (ADR-058): the API reads the file from the private bucket and streams it, so the
 * client never talks to the storage. Only the owner of the ticket gets it.
 */
@RestController
@RequestMapping("/api/v1/ticketing")
@RequiredArgsConstructor
@Slf4j
public class TicketPdfController {

    private final TicketPdfDownloadUseCase downloadUseCase;

    @GetMapping("/me/tickets/{ticketId}/pdf")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable("ticketId") UUID ticketId) {
        byte[] pdf = downloadUseCase.download(UUID.fromString(jwt.getSubject()), ticketId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ticket-" + ticketId + ".pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(pdf);
    }

    @ExceptionHandler(TicketNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(TicketNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("TICKET_NOT_FOUND", e.getMessage()));
    }

    /** The reason stays in the log: the client does not need to know about the storage. */
    @ExceptionHandler(PdfStorageException.class)
    ResponseEntity<ErrorResponse> storageDown(PdfStorageException e) {
        log.error("Ticket PDF not available: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of("PDF_UNAVAILABLE", "The ticket PDF is not available right now"));
    }
}
