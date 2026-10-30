package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.adapter.in.rabbitmq.TicketingCommandService;
import com.cinesync.ticketing.adapter.in.rabbitmq.UserEventConsumer;
import com.cinesync.ticketing.adapter.out.repository.PendingConcessionOrderJpaRepository;
import com.cinesync.ticketing.adapter.out.repository.TicketJpaRepository;
import com.cinesync.ticketing.adapter.out.repository.TicketingFulfillmentJpaRepository;
import com.cinesync.ticketing.adapter.out.repository.UserContactJpaRepository;
import com.cinesync.ticketing.app.config.TicketingConfiguration;
import com.cinesync.ticketing.core.model.UserContact;
import com.cinesync.ticketing.core.port.out.PendingConcessionOrderRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import com.cinesync.ticketing.core.port.out.TicketingFulfillmentRepository;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import com.cinesync.ticketing.core.port.out.UserContactRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * HU-TICKETING-001 against a real RabbitMQ and PostgreSQL. Events are published the way the
 * csp-worker relay publishes them: exchange cine.events, Auth routing keys, application/json.
 */
@SpringBootTest(classes = UserContactProjectionTest.ProjectionApp.class)
@Testcontainers
class UserContactProjectionTest {

    /**
     * Only the HU-TICKETING-001 pipeline: the Auth listeners, the command service, the contact and inbox
     * repositories and the RabbitMQ topology. Ports of the ticket flow are mocked so this test does not
     * depend on it.
     */
    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = UserContact.class)
    @EnableJpaRepositories(basePackageClasses = UserContactJpaRepository.class,
            excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {TicketJpaRepository.class, TicketingFulfillmentJpaRepository.class,
                            PendingConcessionOrderJpaRepository.class}))
    @Import({TicketingConfiguration.class, UserEventConsumer.class, TicketingCommandService.class})
    static class ProjectionApp {
    }

    @MockBean
    private TicketRepository ticketRepository;

    @MockBean
    private TicketingFulfillmentRepository fulfillmentRepository;

    @MockBean
    private PendingConcessionOrderRepository pendingConcessionOrderRepository;

    private static final String EXCHANGE = "cine.events";
    private static final String REGISTERED_KEY = "cine.auth.user-registered.v1";
    private static final String EMAIL_CHANGED_KEY = "cine.auth.user-email-changed.v1";
    private static final String REGISTERED_QUEUE = "cine.ticketing_fulfillment.user-registered";
    private static final Instant T0 = Instant.parse("2026-10-16T15:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("ticketing-schema.sql");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void fastRetries(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "100ms");
        registry.add("spring.rabbitmq.listener.simple.retry.max-interval", () -> "200ms");
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private UserContactRepository contacts;

    @Autowired
    private InboxEventRepository inbox;

    private void publish(String routingKey, String json) {
        Message message = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        rabbitTemplate.send(EXCHANGE, routingKey, message);
    }

    private static String event(UUID eventId, String type, UUID userId, String email, Instant occurredAt) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","version":1,"source":"auth-service",
                 "aggregateId":"%s","payload":{"userId":"%s","email":"%s"}}
                """.formatted(eventId, type, occurredAt, userId, userId, email);
    }

    private UserContact awaitContact(UUID userId) {
        await().atMost(15, TimeUnit.SECONDS).until(() -> contacts.findByUserId(userId).isPresent());
        return contacts.findByUserId(userId).orElseThrow();
    }

    private void awaitEmail(UUID userId, String email) {
        await().atMost(15, TimeUnit.SECONDS)
                .until(() -> contacts.findByUserId(userId).map(c -> c.getEmail().equals(email)).orElse(false));
    }

    @Test
    void userRegistered_createsContactWithUserIdAndEmail() {
        UUID userId = UUID.randomUUID();

        publish(REGISTERED_KEY, event(UUID.randomUUID(), "UserRegistered", userId, "new.user@example.com", T0));

        UserContact contact = awaitContact(userId);
        assertThat(contact.getEmail()).isEqualTo("new.user@example.com");
        assertThat(contact.getLastUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void userEmailChanged_updatesEmailAndLastUpdatedTimestamp() {
        UUID userId = UUID.randomUUID();
        publish(REGISTERED_KEY, event(UUID.randomUUID(), "UserRegistered", userId, "old@example.com", T0));
        awaitContact(userId);

        publish(EMAIL_CHANGED_KEY,
                event(UUID.randomUUID(), "UserEmailChanged", userId, "changed@example.com", T0.plusSeconds(3600)));

        awaitEmail(userId, "changed@example.com");
        assertThat(contacts.findByUserId(userId).orElseThrow().getLastUpdatedAt()).isEqualTo(T0.plusSeconds(3600));
    }

    @Test
    void userEmailChanged_beforeUserRegistered_createsContactAndKeepsNewestEmail() {
        UUID userId = UUID.randomUUID();
        publish(EMAIL_CHANGED_KEY,
                event(UUID.randomUUID(), "UserEmailChanged", userId, "newest@example.com", T0.plusSeconds(600)));
        awaitContact(userId);

        publish(REGISTERED_KEY, event(UUID.randomUUID(), "UserRegistered", userId, "first@example.com", T0));
        UUID barrier = UUID.randomUUID();
        publish(REGISTERED_KEY, event(barrier, "UserRegistered", UUID.randomUUID(), "barrier@example.com", T0));
        await().atMost(15, TimeUnit.SECONDS).until(() -> inbox.findByEventId(barrier).isPresent());

        assertThat(contacts.findByUserId(userId).orElseThrow().getEmail()).isEqualTo("newest@example.com");
    }

    @Test
    void olderEventRedeliveredAfterNewerOne_doesNotOverwriteEmail() {
        UUID userId = UUID.randomUUID();
        UUID olderEventId = UUID.randomUUID();
        String older = event(olderEventId, "UserEmailChanged", userId, "older@example.com", T0);
        publish(EMAIL_CHANGED_KEY, event(UUID.randomUUID(), "UserEmailChanged", userId, "newer@example.com",
                T0.plusSeconds(60)));
        awaitContact(userId);

        publish(EMAIL_CHANGED_KEY, older);
        await().atMost(15, TimeUnit.SECONDS).until(() -> inbox.findByEventId(olderEventId).isPresent());

        assertThat(contacts.findByUserId(userId).orElseThrow().getEmail()).isEqualTo("newer@example.com");
    }

    @Test
    void duplicateDelivery_isAppliedOnce() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String registered = event(eventId, "UserRegistered", userId, "once@example.com", T0);
        publish(REGISTERED_KEY, registered);
        awaitContact(userId);
        publish(EMAIL_CHANGED_KEY, event(UUID.randomUUID(), "UserEmailChanged", userId, "later@example.com",
                T0.plusSeconds(60)));
        awaitEmail(userId, "later@example.com");

        publish(REGISTERED_KEY, registered);
        UUID barrier = UUID.randomUUID();
        publish(REGISTERED_KEY, event(barrier, "UserRegistered", UUID.randomUUID(), "barrier@example.com", T0));
        await().atMost(15, TimeUnit.SECONDS).until(() -> inbox.findByEventId(barrier).isPresent());

        assertThat(contacts.findByUserId(userId).orElseThrow().getEmail()).isEqualTo("later@example.com");
        assertThat(inbox.findByEventId(eventId)).isPresent();
    }

    @Test
    void malformedAndInvalidMessages_goToTheDeadLetterQueueAndCreateNothing() {
        UUID userId = UUID.randomUUID();
        String missingEmail = """
                {"eventId":"%s","eventType":"UserRegistered","occurredAt":"%s",
                 "payload":{"userId":"%s"}}
                """.formatted(UUID.randomUUID(), T0, userId);

        publish(REGISTERED_KEY, "{\"eventId\":");
        publish(REGISTERED_KEY, missingEmail);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(rabbitTemplate.receive(REGISTERED_QUEUE + ".dlq", 1000)).isNotNull();
            assertThat(rabbitTemplate.receive(REGISTERED_QUEUE + ".dlq", 1000)).isNotNull();
        });
        assertThat(contacts.findByUserId(userId)).isEmpty();
    }

    @Test
    void processedMessages_areAcknowledged() throws Exception {
        UUID userId = UUID.randomUUID();
        publish(REGISTERED_KEY, event(UUID.randomUUID(), "UserRegistered", userId, "acked@example.com", T0));
        awaitContact(userId);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            String queues = rabbitMQ.execInContainer(
                    "rabbitmqctl", "list_queues", "name", "messages_unacknowledged").getStdout();
            assertThat(queues).contains(REGISTERED_QUEUE + "\t0");
        });
    }
}
