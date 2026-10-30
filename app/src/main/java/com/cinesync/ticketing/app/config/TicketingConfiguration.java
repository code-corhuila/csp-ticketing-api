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
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
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

    @Bean
    public Declarables ticketingQueues(
            @Value("${ticketing.rabbitmq.exchange}") String exchange,
            @Value("${ticketing.rabbitmq.queue.user-registered}") String userRegisteredQueue,
            @Value("${ticketing.rabbitmq.queue.user-email-changed}") String userEmailChangedQueue,
            @Value("${ticketing.rabbitmq.queue.booking-confirmed}") String bookingConfirmedQueue,
            @Value("${ticketing.rabbitmq.queue.concession-order-confirmed}") String concessionOrderConfirmedQueue,
            @Value("${ticketing.rabbitmq.queue.ticket-issued}") String ticketIssuedQueue,
            @Value("${ticketing.rabbitmq.routing-key.booking-confirmed}") String bookingConfirmedRoutingKey
    ) {
        TopicExchange mainExchange = new TopicExchange(exchange, true, false);
        TopicExchange dlxExchange = new TopicExchange("cine.dlx", true, false);

        Queue dlq = new Queue("ticketing.dlq", true);

        Queue userRegistered = QueueBuilder.durable(userRegisteredQueue)
                .withArgument("x-dead-letter-exchange", "cine.dlx")
                .withArgument("x-dead-letter-routing-key", "ticketing.dlq")
                .build();

        Queue userEmailChanged = QueueBuilder.durable(userEmailChangedQueue)
                .withArgument("x-dead-letter-exchange", "cine.dlx")
                .withArgument("x-dead-letter-routing-key", "ticketing.dlq")
                .build();

        Queue bookingConfirmed = QueueBuilder.durable(bookingConfirmedQueue)
                .withArgument("x-dead-letter-exchange", "cine.dlx")
                .withArgument("x-dead-letter-routing-key", "ticketing.dlq")
                .build();

        Queue concessionOrderConfirmed = QueueBuilder.durable(concessionOrderConfirmedQueue)
                .withArgument("x-dead-letter-exchange", "cine.dlx")
                .withArgument("x-dead-letter-routing-key", "ticketing.dlq")
                .build();

        Queue ticketIssued = QueueBuilder.durable(ticketIssuedQueue)
                .withArgument("x-dead-letter-exchange", "cine.dlx")
                .withArgument("x-dead-letter-routing-key", "ticketing.dlq")
                .build();

        Binding b1 = BindingBuilder.bind(userRegistered).to(mainExchange).with("user.registered");
        Binding b2 = BindingBuilder.bind(userEmailChanged).to(mainExchange).with("user.email.changed");
        Binding b3 = BindingBuilder.bind(concessionOrderConfirmed).to(mainExchange).with("concession.order.confirmed");
        Binding b4 = BindingBuilder.bind(ticketIssued).to(mainExchange).with("cine.ticketing_fulfillment.ticket-issued.v1");
        Binding b5 = BindingBuilder.bind(bookingConfirmed).to(mainExchange).with(bookingConfirmedRoutingKey);

        return new Declarables(
                userRegistered, userEmailChanged, concessionOrderConfirmed, ticketIssued,
                new Queue("ticketing.dlq", true),
                mainExchange, dlxExchange
        );
    }

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

        factory.setRecoveryInterval(1000L);

        RetryTemplate retryTemplate = new RetryTemplate();
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(initialIntervalMs);
        backOffPolicy.setMaxInterval(maxIntervalMs);
        backOffPolicy.setMultiplier(multiplier);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(Math.toIntExact(maxAttempts));
        retryTemplate.setRetryPolicy(retryPolicy);

        factory.setRetryTemplate(retryTemplate);

        factory.setMissingQueuesFatal(false);
        factory.setAutoStartup(true);
        factory.setDefaultRequeueRejected(false);

        return factory;
    }

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }

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
}