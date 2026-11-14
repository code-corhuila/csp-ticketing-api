package com.cinesync.ticketing.adapter.service;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.port.in.PageResult;
import com.cinesync.ticketing.core.port.in.TicketQueryUseCase;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import com.cinesync.ticketing.core.port.out.TicketingFulfillmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketQueryService implements TicketQueryUseCase {

    private final TicketRepository ticketRepository;
    private final TicketingFulfillmentRepository fulfillmentRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResult<TicketView> listMine(UUID userId, int page, int limit) {
        List<Ticket> tickets = ticketRepository.findPageByUserId(userId, limit, (page - 1) * limit);
        Map<UUID, TicketingFulfillment> deliveries = fulfillmentRepository
                .findByTicketIdIn(tickets.stream().map(Ticket::getId).toList()).stream()
                .collect(Collectors.toMap(TicketingFulfillment::getTicketId, Function.identity()));
        List<TicketView> views = tickets.stream().map(ticket -> view(ticket, deliveries.get(ticket.getId()))).toList();
        return new PageResult<>(views, page, limit, ticketRepository.countByUserId(userId));
    }

    private static TicketView view(Ticket ticket, TicketingFulfillment delivery) {
        List<Concession> concessions = ticket.getConcessionsSnapshot() == null ? List.of()
                : ticket.getConcessionsSnapshot().stream().map(item -> new Concession(item.getName(), item.getQuantity())).toList();
        return new TicketView(ticket.getId(), ticket.getReservationId(), ticket.getShowtimeId(), ticket.getMovieTitleSnapshot(),
                ticket.getRoomNameSnapshot(), ticket.getShowtimeStartsAt(), ticket.getSeatLabelsSnapshot(), concessions,
                ticket.getQrPayload(), ticket.getPdfUrl() != null, delivery == null ? null : delivery.getStatus(),
                ticket.getCreatedAt());
    }
}
