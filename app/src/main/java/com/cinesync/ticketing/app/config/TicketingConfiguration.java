package com.cinesync.ticketing.app.config;

import com.cinesync.ticketing.core.port.out.EmailSenderPort;
import com.cinesync.ticketing.core.port.out.EventPublisherPort;
import com.cinesync.ticketing.core.port.out.PdfGeneratorPort;
import com.cinesync.ticketing.adapter.out.smtp.SmtpEmailSender;
import com.cinesync.ticketing.adapter.out.pdf.ITextPdfGenerator;
import com.cinesync.ticketing.adapter.out.rabbitmq.RabbitEventPublisher;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

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
        template.setMandatory(true);
        return template;
    }

    // RabbitMQ Listener Container Factory with consumer recovery
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Value("${ticketing.rabbitmq.consumer.recovery.initial-interval-ms:1000}") long initialIntervalMs,
            @Value("${ticketing.rabbitmq.consumer.recovery.max-interval-ms:30000}") long maxIntervalMs,
            @Value("${ticketing.rabbitmq.consumer.recovery.multiplier:2.0}") double multiplier,
            @Value("${ticketing.rabbitmq.consumer.recovery.max-attempts:0}") long maxAttempts,
            @Value("${ticketing.rabbitmq.consumer.cancel.log-warn:true}") boolean logCancelWarn
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);

        // Consumer recovery: exponential backoff, no attempt limit (maxAttempts=0 = unlimited)
        factory.setRecoveryInterval(1000L); // Base recovery check interval

        // Retry template for consumer recovery (re-subscribe after cancel)
        RetryTemplate retryTemplate = new RetryTemplate();
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(initialIntervalMs);
        backOffPolicy.setMaxInterval(maxIntervalMs);
        backOffPolicy.setMultiplier(multiplier);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(Math.toIntExact(maxAttempts)); // 0 = unlimited
        retryTemplate.setRetryPolicy(retryPolicy);

        factory.setRetryTemplate(retryTemplate);

        // Missing queues should not be fatal (allow re-declaration)
        factory.setMissingQueuesFatal(false);
        factory.setAutoStartup(true);
        factory.setDefaultRequeueRejected(false);

        return factory;
    }

    // RabbitAdmin for queue declaration at startup
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }

    // Queue declaration at startup (before relay enabled)
    @Bean
    public Declarables ticketingQueues(
            @Value("${ticketing.rabbitmq.exchange}") String exchange,
            @Value("${ticketing.rabbitmq.queue.user-registered}") String userRegisteredQueue,
            @Value("${ticketing.rabbitmq.queue.user-email-changed}") String userEmailChangedQueue,
            @Value("${ticketing.rabbitmq.queue.booking-confirmed}") String bookingConfirmedQueue,
            @Value("${ticketing.rabbitmq.queue.concession-order-confirmed}") String concessionOrderConfirmedQueue,
            @Value("${ticketing.rabbitmq.queue.ticket-issued}") String ticketIssuedQueue,
            @Value("${ticketing.rabbitmq.queue.dlq}") String dlqQueue,
            @Value("${ticketing.rabbitmq.routing-key.ticket-issued}") String ticketIssuedRoutingKey
    ) {
        Queue userRegistered = new Queue(userRegisteredQueue, true);
        Queue userEmailChanged = new Queue(userEmailChangedQueue, true);
        Queue bookingConfirmed = new Queue(bookingConfirmedQueue, true);
        Queue concessionOrderConfirmed = new Queue(concessionOrderConfirmedQueue, true);
        Queue ticketIssued = new Queue(ticketIssuedQueue, true);
        Queue dlq = new Queue(dlqQueue, true);

        Exchange topicExchange = new TopicExchange(exchange, true, false);

        Binding bindingUserRegistered = BindingBuilder.bind(userRegistered).to(topicExchange).with("user.registered").noargs();
        Binding bindingUserEmailChanged = BindingBuilder.bind(userEmailChanged).to(topicExchange).with("user.email.changed").noargs();
        Binding bindingBookingConfirmed = BindingBuilder.bind(bookingConfirmed).to(topicExchange).with("booking.confirmed").noargs();
        Binding bindingConcessionOrderConfirmed = BindingBuilder.bind(concessionOrderConfirmed).to(topicExchange).with("concession.order.confirmed").noargs();
        Binding bindingTicketIssued = BindingBuilder.bind(ticketIssued).to(topicExchange).with(ticketIssuedRoutingKey).noargs();

        return new Declarables(
                userRegistered, userEmailChanged, bookingConfirmed, concessionOrderConfirmed, ticketIssued, dlq,
                topicExchange,
                bindingUserRegistered, bindingUserEmailChanged, bindingBookingConfirmed,
                bindingConcessionOrderConfirmed, bindingTicketIssued
        );
    }

    // DLQ message recoverer (after 3 retries)
    @Bean
    public MessageRecoverer deadLetterRecoverer(RabbitTemplate rabbitTemplate,
            @Value("${ticketing.rabbitmq.queue.dlq}") String dlqQueue) {
        return new RepublishMessageRecoverer(rabbitTemplate, dlqQueue, null);
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }
}