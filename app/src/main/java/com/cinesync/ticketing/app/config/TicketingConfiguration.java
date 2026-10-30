package com.cinesync.ticketing.app.config;

import com.cinesync.ticketing.core.port.out.EmailSenderPort;
import com.cinesync.ticketing.core.port.out.EventPublisherPort;
import com.cinesync.ticketing.core.port.out.PdfGeneratorPort;
import com.cinesync.ticketing.adapter.out.smtp.SmtpEmailSender;
import com.cinesync.ticketing.adapter.out.pdf.ITextPdfGenerator;
import com.cinesync.ticketing.adapter.out.rabbitmq.RabbitEventPublisher;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Properties;

@Configuration
public class TicketingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EmailSenderPort emailSenderPort(JavaMailSender mailSender) {
        return new SmtpEmailSender(mailSender);
    }

    @Bean
    @ConditionalOnMissingBean
    public JavaMailSender javaMailSender(
            @Value("${spring.mail.host}") String host,
            @Value("${spring.mail.port}") int port,
            @Value("${spring.mail.username}") String username,
            @Value("${spring.mail.password}") String password,
            @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean auth,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean starttls
    ) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", auth);
        props.put("mail.smtp.starttls.enable", starttls);
        return sender;
    }

    @Bean
    @ConditionalOnMissingBean
    public PdfGeneratorPort pdfGeneratorPort() {
        return new ITextPdfGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventPublisherPort eventPublisherPort(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        return new RabbitEventPublisher(rabbitTemplate, objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        return template;
    }

    // RabbitMQ Queues (declare if not exist)
    @Bean
    public Queue userRegisteredQueue(@Value("${ticketing.rabbitmq.queue.user-registered}") String name) {
        return new Queue(name, true);
    }

    @Bean
    public Queue userEmailChangedQueue(@Value("${ticketing.rabbitmq.queue.user-email-changed}") String name) {
        return new Queue(name, true);
    }

    @Bean
    public Queue bookingConfirmedQueue(@Value("${ticketing.rabbitmq.queue.booking-confirmed}") String name) {
        return new Queue(name, true);
    }

    @Bean
    public Queue concessionOrderConfirmedQueue(@Value("${ticketing.rabbitmq.queue.concession-order-confirmed}") String name) {
        return new Queue(name, true);
    }

    @Bean
    public Queue ticketIssuedQueue(@Value("${ticketing.rabbitmq.queue.ticket-issued}") String name) {
        return new Queue(name, true);
    }

    @Bean
    public Queue dlqQueue(@Value("${ticketing.rabbitmq.queue.dlq}") String name) {
        return new Queue(name, true);
    }
}