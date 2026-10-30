package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.adapter.in.rabbitmq.TicketingCommandService;
import com.cinesync.ticketing.adapter.in.rabbitmq.UserEventConsumer;
import com.cinesync.ticketing.adapter.out.repository.PendingConcessionOrderJpaRepository;
import com.cinesync.ticketing.adapter.out.repository.TicketJpaRepository;
import com.cinesync.ticketing.adapter.out.repository.TicketingFulfillmentJpaRepository;
import com.cinesync.ticketing.adapter.out.repository.UserContactJpaRepository;
import com.cinesync.ticketing.app.config.ConsumerLifecycleListener;
import com.cinesync.ticketing.app.config.ConsumerReadinessIndicator;
import com.cinesync.ticketing.app.config.TicketingConfiguration;
import com.cinesync.ticketing.core.model.UserContact;
import com.cinesync.ticketing.core.port.out.PendingConcessionOrderRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import com.cinesync.ticketing.core.port.out.TicketingFulfillmentRepository;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Real RabbitMQ and PostgreSQL plus only the HU-TICKETING-001 pipeline: the Auth listeners, the command
 * service, the contact and inbox repositories and the RabbitMQ topology. Ports of the ticket flow are mocked
 * so these tests do not depend on it. Events are published the way the csp-worker relay publishes them:
 * exchange cine.events, Auth routing keys, application/json.
 *
 * <p>The containers are started once and shared, and every subclass uses this same Spring context, so there
 * is never a second set of consumers competing for the same queues.
 */
@SpringBootTest(classes = ProjectionTestSupport.ProjectionApp.class)
abstract class ProjectionTestSupport {

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = UserContact.class)
    @EnableJpaRepositories(basePackageClasses = UserContactJpaRepository.class,
            excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {TicketJpaRepository.class, TicketingFulfillmentJpaRepository.class,
                            PendingConcessionOrderJpaRepository.class}))
    @Import({TicketingConfiguration.class, UserEventConsumer.class, TicketingCommandService.class,
            ConsumerReadinessIndicator.class, ConsumerLifecycleListener.class})
    static class ProjectionApp {
    }

    static final String EXCHANGE = "cine.events";
    static final String REGISTERED_KEY = "cine.auth.user-registered.v1";
    static final String EMAIL_CHANGED_KEY = "cine.auth.user-email-changed.v1";
    static final String REGISTERED_QUEUE = "cine.ticketing_fulfillment.user-registered";
    static final Instant T0 = Instant.parse("2026-10-16T15:00:00Z");

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("ticketing-schema.sql");

    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    static {
        postgres.start();
        rabbitMQ.start();
    }

    @DynamicPropertySource
    static void fastRetries(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "100ms");
        registry.add("spring.rabbitmq.listener.simple.retry.max-interval", () -> "200ms");
    }

    @MockBean
    private TicketRepository ticketRepository;

    @MockBean
    private TicketingFulfillmentRepository fulfillmentRepository;

    @MockBean
    private PendingConcessionOrderRepository pendingConcessionOrderRepository;

    /** Spied so a test can hold a message in processing; it calls the real method unless stubbed. */
    @SpyBean
    TicketingCommandService commandService;

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
