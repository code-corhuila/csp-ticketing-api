package com.cinesync.ticketing.app.rest.internal;

import com.cinesync.ticketing.core.model.TicketNotFoundException;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase.DispatchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Operations for csp-worker (ADR-057). Callers are not authenticated yet: the service-token check of
 * norm 5.3.7 is csp-ticketing-api#12.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class TicketingInternalController {

    private static final int MAX_PDF_URL_LENGTH = 500;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final TicketDeliveryUseCase deliveryUseCase;

    @PostMapping("/tickets/{ticketId}/pdf")
    public ResponseEntity<Void> receivePdfUrl(
            @PathVariable("ticketId") UUID ticketId,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody PdfUrlRequest request) {
        if (request.pdfUrl() == null || request.pdfUrl().isBlank() || request.pdfUrl().length() > MAX_PDF_URL_LENGTH) {
            throw new InvalidRequestException("pdfUrl is required and at most " + MAX_PDF_URL_LENGTH + " characters");
        }
        deliveryUseCase.registerPdf(ticketId, request.pdfUrl(),
                correlationId == null || correlationId.isBlank() ? UUID.randomUUID().toString() : correlationId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/fulfillments/dispatch")
    public DispatchResult dispatch(@RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidRequestException("limit must be between 1 and " + MAX_LIMIT);
        }
        return deliveryUseCase.dispatchDue(limit);
    }

    @ExceptionHandler(TicketNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(TicketNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidRequestException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, List.of(), UUID.randomUUID().toString()));
    }

    public record PdfUrlRequest(String pdfUrl) {}

    static class InvalidRequestException extends RuntimeException {
        InvalidRequestException(String message) {
            super(message);
        }
    }

    /** The single error envelope of the platform. */
    public record ErrorResponse(String error, String message, List<String> details, String traceId) {}
}
