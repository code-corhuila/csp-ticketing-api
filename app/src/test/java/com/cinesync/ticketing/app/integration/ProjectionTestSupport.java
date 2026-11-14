package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.adapter.in.rabbitmq.TicketingCommandService;
import com.cinesync.ticketing.app.TicketingSchema;
import com.cinesync.ticketing.testsupport.ProjectionApp;
import com.cinesync.ticketing.core.port.out.EmailSenderPort;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Real RabbitMQ and PostgreSQL plus the consumers of the service: the listeners, the command service, the
 * repositories and the RabbitMQ topology. Only the email sender is mocked. Events are published the way the
 * csp-worker relay publishes them: exchange cine.events, application/json.
 *
 * <p>The containers are started once and shared, and every subclass uses this same Spring context, so there
 * is never a second set of consumers competing for the same queues.
 */
@SpringBootTest(classes = ProjectionApp.class)
abstract class ProjectionTestSupport {

    static final String EXCHANGE = "cine.events";
    static final String REGISTERED_KEY = "cine.auth.user-registered.v1";
    static final String EMAIL_CHANGED_KEY = "cine.auth.user-email-changed.v1";
    static final String REGISTERED_QUEUE = "cine.ticketing_fulfillment.user-registered";
    static final Instant T0 = Instant.parse("2026-10-16T15:00:00Z");

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUsername("postgres").withPassword("postgres");

    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    static {
        postgres.start();
        TicketingSchema.migrate(postgres);
        rabbitMQ.start();
        declareTheExchangesOfTheBrokerRepository();
    }

    /** The broker of the platform has both exchanges before any service starts (csp-infra-rabbitmq, ADR-036); the service never declares them. */
    private static void declareTheExchangesOfTheBrokerRepository() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitMQ.getHost());
        factory.setPort(rabbitMQ.getAmqpPort());
        try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
            channel.exchangeDeclare(EXCHANGE, "topic", true);
            channel.exchangeDeclare("cine.dlx", "topic", true);
        } catch (IOException | TimeoutException e) {
            throw new IllegalStateException("The test broker cannot declare the exchanges", e);
        }
    }

    @DynamicPropertySource
    static void fastRetries(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "100ms");
        registry.add("spring.rabbitmq.listener.simple.retry.max-interval", () -> "200ms");
    }

    /** Spied so a test can hold a message in processing; it calls the real method unless stubbed. */
    @SpyBean
    TicketingCommandService commandService;

    /** The only thing that is not real: no SMTP server runs in the tests. */
    @MockBean
    EmailSenderPort emailSender;

    @Autowired
    RabbitTemplate rabbitTemplate;

    void publish(String routingKey, String json) {
        Message message = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        rabbitTemplate.send(EXCHANGE, routingKey, message);
    }

    static String event(UUID eventId, String type, UUID userId, String email, Instant occurredAt) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","version":1,"source":"auth-service",
                 "aggregateId":"%s","payload":{"userId":"%s","email":"%s"}}
                """.formatted(eventId, type, occurredAt, userId, userId, email);
    }
}
