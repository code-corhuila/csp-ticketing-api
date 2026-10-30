package com.cinesync.ticketing.app.config;

import com.cinesync.ticketing.core.port.out.EmailSenderPort;
import com.cinesync.ticketing.core.port.out.PdfGeneratorPort;
import com.cinesync.ticketing.adapter.out.smtp.SmtpEmailSender;
import com.cinesync.ticketing.adapter.out.pdf.ITextPdfGenerator;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
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
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.util.backoff.ExponentialBackOff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.util.Map;
import java.util.Properties;

@Configuration
public class TicketingConfiguration {

    private static final String DLQ_SUFFIX = ".dlq";

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
    public Clock clock() {
        return Clock.systemUTC();
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
            @Value("${ticketing.rabbitmq.routing-key.booking-confirmed}") String bookingConfirmedRoutingKey,
            @Value("${ticketing.rabbitmq.routing-key.user-registered}") String userRegisteredRoutingKey,
            @Value("${ticketing.rabbitmq.routing-key.user-email-changed}") String userEmailChangedRoutingKey
    ) {
        TopicExchange mainExchange = new TopicExchange(exchange, true, false);
        TopicExchange dlxExchange = new TopicExchange("cine.dlx", true, false);

        // Auth events: one dead-letter queue per queue (ADR-007), reached through cine.dlx.
        Queue userRegistered = durableWithDeadLetter(userRegisteredQueue);
        Queue userRegisteredDlq = new Queue(userRegisteredQueue + DLQ_SUFFIX, true);
        Queue userEmailChanged = durableWithDeadLetter(userEmailChangedQueue);
        Queue userEmailChangedDlq = new Queue(userEmailChangedQueue + DLQ_SUFFIX, true);

        // Booking and Concessions events: the same shape, one dead-letter queue per queue (ADR-007, ADR-036).
        Queue bookingConfirmed = durableWithDeadLetter(bookingConfirmedQueue);
        Queue bookingConfirmedDlq = new Queue(bookingConfirmedQueue + DLQ_SUFFIX, true);
        Queue concessionOrderConfirmed = durableWithDeadLetter(concessionOrderConfirmedQueue);
        Queue concessionOrderConfirmedDlq = new Queue(concessionOrderConfirmedQueue + DLQ_SUFFIX, true);

        // The event of this service has a queue so that the relay can route it (an unroutable event is a permanent failure).
        Queue ticketIssued = durableWithDeadLetter(ticketIssuedQueue);
        Queue ticketIssuedDlq = new Queue(ticketIssuedQueue + DLQ_SUFFIX, true);

        Binding b1 = BindingBuilder.bind(userRegistered).to(mainExchange).with(userRegisteredRoutingKey);
        Binding b2 = BindingBuilder.bind(userEmailChanged).to(mainExchange).with(userEmailChangedRoutingKey);
        Binding d1 = BindingBuilder.bind(userRegisteredDlq).to(dlxExchange).with(userRegisteredQueue + DLQ_SUFFIX);
        Binding d2 = BindingBuilder.bind(userEmailChangedDlq).to(dlxExchange).with(userEmailChangedQueue + DLQ_SUFFIX);
        Binding b3 = BindingBuilder.bind(concessionOrderConfirmed).to(mainExchange).with("cine.concessions.order-confirmed.v1");
        Binding b4 = BindingBuilder.bind(ticketIssued).to(mainExchange).with("cine.ticketing_fulfillment.ticket-issued.v1");
        Binding b5 = BindingBuilder.bind(bookingConfirmed).to(mainExchange).with(bookingConfirmedRoutingKey);
        Binding d3 = BindingBuilder.bind(bookingConfirmedDlq).to(dlxExchange).with(bookingConfirmedQueue + DLQ_SUFFIX);
        Binding d4 = BindingBuilder.bind(concessionOrderConfirmedDlq).to(dlxExchange)
                .with(concessionOrderConfirmedQueue + DLQ_SUFFIX);
        Binding d5 = BindingBuilder.bind(ticketIssuedDlq).to(dlxExchange).with(ticketIssuedQueue + DLQ_SUFFIX);

        return new Declarables(
                userRegistered, userEmailChanged, bookingConfirmed, concessionOrderConfirmed, ticketIssued,
                userRegisteredDlq, userEmailChangedDlq, bookingConfirmedDlq, concessionOrderConfirmedDlq, ticketIssuedDlq,
                mainExchange, dlxExchange,
                b1, b2, b3, b4, b5, d1, d2, d3, d4, d5
        );
    }

    private static Queue durableWithDeadLetter(String name) {
        return QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", "cine.dlx")
                .withArgument("x-dead-letter-routing-key", name + DLQ_SUFFIX)
                .build();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);

        // A cancelled consumer or a deleted queue is recovered forever: 1 s, doubling up to 30 s, no attempt limit.
        ExponentialBackOff recoveryBackOff = new ExponentialBackOff(1000L, 2.0);
        recoveryBackOff.setMaxInterval(30_000L);
        factory.setRecoveryBackOff(recoveryBackOff);

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

    /**
     * The listener retries come from spring.rabbitmq.listener.simple.retry (ADR-007). When they run out the
     * message is rejected without requeue and the broker dead-letters it through cine.dlx. A message that
     * can never succeed (AmqpRejectAndDontRequeueException) skips the retries.
     */
    @Bean
    public RabbitRetryTemplateCustomizer rejectedMessagesAreNotRetried(
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}") int maxAttempts) {
        return (target, template) -> {
            if (target == RabbitRetryTemplateCustomizer.Target.LISTENER) {
                template.setRetryPolicy(new SimpleRetryPolicy(maxAttempts,
                        Map.of(AmqpRejectAndDontRequeueException.class, false), true, true));
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        // Dates go out as RFC 3339 text (norm 5.3), not as numbers, and java.time types are understood.
        return JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    }
}