package com.cinesync.ticketing.core.model;

import java.util.stream.Collectors;

/** The email of an issued ticket: ticket details in the body and a link to the stored PDF. */
public record TicketEmail(String subject, String htmlBody) {

    public static TicketEmail of(Ticket ticket) {
        String showtime = ticket.getShowtimeStartsAt() == null
                ? "To be confirmed" : ticket.getShowtimeStartsAt().toString();
        StringBuilder body = new StringBuilder("<h2>Your CineSync ticket</h2>")
                .append("<p><b>Movie:</b> ").append(escape(ticket.getMovieTitleSnapshot())).append("<br>")
                .append("<b>Room:</b> ").append(escape(ticket.getRoomNameSnapshot())).append("<br>")
                .append("<b>Showtime:</b> ").append(escape(showtime)).append("<br>")
                .append("<b>Seats:</b> ").append(escape(String.join(", ", ticket.getSeatLabelsSnapshot())));
        if (ticket.getConcessionsSnapshot() != null && !ticket.getConcessionsSnapshot().isEmpty()) {
            body.append("<br><b>Concessions:</b> ").append(escape(ticket.getConcessionsSnapshot().stream()
                    .map(item -> item.getName() + " x" + item.getQuantity())
                    .collect(Collectors.joining(", "))));
        }
        body.append("</p><p><a href=\"").append(escape(ticket.getPdfUrl()))
                .append("\">Download your ticket (PDF)</a></p>");
        String title = ticket.getMovieTitleSnapshot().replaceAll("[\\r\\n]", " ");
        return new TicketEmail("Your CineSync ticket for " + title, body.toString());
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
