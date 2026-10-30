package com.cinesync.ticketing.adapter.out.smtp;

import com.cinesync.ticketing.core.port.out.EmailSenderPort;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SmtpEmailSender implements EmailSenderPort {

    private final JavaMailSender mailSender;

    @Override
    public void sendTicketEmail(String toEmail, String subject, String htmlBody,
                                byte[] pdfAttachment, String pdfFilename) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            helper.addAttachment(pdfFilename, () -> new java.io.ByteArrayInputStream(pdfAttachment));
            mailSender.send(message);
            log.info("Ticket email sent to {}", toEmail);
        } catch (MessagingException e) {
            log.error("Failed to send ticket email to {}: {}", toEmail, e.getMessage(), e);
            throw new RuntimeException("Email send failed", e);
        }
    }
}