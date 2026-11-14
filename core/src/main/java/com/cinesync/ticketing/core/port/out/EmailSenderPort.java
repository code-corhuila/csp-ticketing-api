package com.cinesync.ticketing.core.port.out;

public interface EmailSenderPort {
    void sendTicketEmail(String toEmail, String subject, String htmlBody);
}
