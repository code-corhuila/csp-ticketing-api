package com.cinesync.ticketing.app.rest;

import com.cinesync.ticketing.core.model.TicketingFulfillment.FulfillmentStatus;
import com.cinesync.ticketing.core.port.in.PageResult;
import com.cinesync.ticketing.core.port.in.TicketQueryUseCase;
import com.cinesync.ticketing.core.port.in.TicketQueryUseCase.TicketView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The tickets of the authenticated user (the {@code sub} of the token): the page {@code My Tickets} of the portal. */
@RestController
@RequestMapping("/api/v1/ticketing")
@RequiredArgsConstructor
public class TicketsController {

    private final TicketQueryUseCase queryUseCase;

    @GetMapping("/me/tickets")
    public TicketListResponse list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        InvalidPaginationException.check(page, limit);
        PageResult<TicketView> result = queryUseCase.listMine(UUID.fromString(jwt.getSubject()), page, limit);
        return new TicketListResponse(result.data().stream().map(TicketsController::item).toList(),
                new PageMeta(result.page(), result.limit(), result.total(), result.totalPages()));
    }

    /** {@code pdfUrl} is the path of the download of the PDF, never the internal reference of the storage (ADR-058). */
    private static TicketItem item(TicketView ticket) {
        String pdfUrl = ticket.hasPdf() ? "/api/v1/ticketing/me/tickets/" + ticket.id() + "/pdf" : null;
        return new TicketItem(ticket.id(), ticket.reservationId(), ticket.showtimeId(), ticket.movieTitle(), ticket.roomName(),
                ticket.showtimeStartsAt(), ticket.seatLabels(),
                ticket.concessions().stream().map(c -> new ConcessionItem(c.name(), c.quantity())).toList(),
                ticket.qrPayload(), pdfUrl, ticket.deliveryStatus(), ticket.createdAt());
    }

    public record ConcessionItem(String name, int quantity) {
    }

    public record TicketItem(UUID id, UUID reservationId, UUID showtimeId, String movieTitle, String roomName, Instant showtimeStartsAt,
                             List<String> seatLabels, List<ConcessionItem> concessions, String qrPayload, String pdfUrl,
                             FulfillmentStatus deliveryStatus, Instant createdAt) {
    }

    public record TicketListResponse(List<TicketItem> data, PageMeta meta) {
    }
}
