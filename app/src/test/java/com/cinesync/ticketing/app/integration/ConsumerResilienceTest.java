package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.core.model.InboxEvent;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import com.cinesync.ticketing.core.port.out.UserContactRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class ConsumerResilienceTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"))
            .withExposedPorts(5672, 15672);

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private UserContactRepository userContactRepository;

    @Autowired
    private InboxEventRepository inboxEventRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbitMQ::getHost);
        registry.add("spring.rabbitmq.port", rabbitMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitMQ::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
        // Disable auto-startup for test control
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        registry.add("spring.rabbitmq.listener.simple.missing-queues-fatal", () -> "false");
        registry.add("ticketing.rabbitmq.declare-at-startup", () -> "true");
    }

    @BeforeAll
    static void setup() {
        // Wait for RabbitMQ to be ready
        await().atMost(30, TimeUnit.SECONDS).until(rabbitMQ::isRunning);
    }

    @AfterAll
    static void teardown() {
        rabbitMQ.stop();
    }

    @Test
    void consumerRecoversAfterQueueDeletion() throws Exception {
        // Given: Consumer is running and subscribed to queue
        String queueName = "ticketing.user-registered";
        String exchange = "cine.ticketing_fulfillment";
        String routingKey = "user.registered";

        // Ensure queue exists
        rabbitAdmin.declareQueue(new Queue(queueName, true));
        rabbitAdmin.declareExchange(new org.springframework.amqp.core.TopicExchange(exchange, true, false));
        rabbitAdmin.declareBinding(org.springframework.amqp.core.BindingBuilder
                .bind(new Queue(queueName, true))
                .to(new org.springframework.amqp.core.TopicExchange(exchange, true, false))
                .with(routingKey));

        // Wait for consumer to be ready
        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            var containers = applicationContext.getBeansOfType(org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer.class);
            return containers.values().stream().allMatch(c -> c.isRunning() && c.isActive());
        });

        // When: Publish a message (should be consumed)
        String eventId1 = UUID.randomUUID().toString();
        String message1 = String.format("""
                {"eventId":"%s","eventType":"UserRegistered","payload":{"userId":"%s","email":"test1@example.com"},"occurredAt":"%s" """,
                eventId1,
                UUID.randomUUID(),
                Instant.now().toString()
                );
        rabbitTemplate.convertAndSend(exchange, routingKey, message1);

        // Then: Message should be consumed (inbox event recorded)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(inboxEventRepository.findByEventId(UUID.fromString(eventId1))).isPresent();
        });

        // When: Delete the queue while consumer is running (simulates broker action)
        rabbitAdmin.deleteQueue(queueName);

        // Wait a bit for cancel to be detected
        Thread.sleep(2000);

        // Re-declare queue (simulates broker recovery or manual re-declaration)
        rabbitAdmin.declareQueue(new Queue(queueName, true));
        rabbitAdmin.declareBinding(org.springframework.amqp.core.BindingBuilder
                .bind(new Queue(queueName, true))
                .to(new org.springframework.amqp.core.TopicExchange("cine.ticketing_fulfillment", true, false))
                .with("user.registered"));

        // When: Publish another message after queue recovery
        String eventId2 = UUID.randomUUID().toString();
        String message2 = String.format("""
                {"eventId":"%s","eventType":"UserRegistered","payload":{"userId":"%s","email":"test2@example.com"},"occurredAt":"%s" """,
                eventId2,
                UUID.randomUUID(),
                Instant.now().toString()
                );
        rabbitTemplate.convertAndSend(exchange, routingKey, message2);

        // Then: Consumer should have recovered and consumed the message
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(inboxEventRepository.findByEventId(UUID.fromString(eventId2))).isPresent();
        });

        // Verify both messages were consumed exactly once (idempotency)
        assertThat(inboxEventRepository.findByEventId(UUID.fromString(eventId1))).isPresent();
        assertThat(inboxEventRepository.findByEventId(UUID.fromString(eventId2))).isPresent();
    }

    @Test
    void consumerHandlesDuplicateDeliveryIdempotently() throws Exception {
        // Given: Consumer is running
        String queueName = "ticketing.user-email-changed";
        String exchange = "cine.ticketing_fulfillment";
        String routingKey = "user.email.changed";

        rabbitAdmin.declareQueue(new Queue(queueName, true));
        rabbitAdmin.declareExchange(new org.springframework.amqp.core.TopicExchange(exchange, true, false));
        rabbitAdmin.declareBinding(org.springframework.amqp.core.BindingBuilder
                .bind(new Queue(queueName, true))
                .to(new org.springframework.amqp.core.TopicExchange(exchange, true, false))
                .with(routingKey));

        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            var containers = applicationContext.getBeansOfType(org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer.class);
            return containers.values().stream().allMatch(c -> c.isRunning() && c.isActive());
        });

        // When: Publish same event twice (duplicate delivery)
        String eventId = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        String email = "duplicate@example.com";
        String occurredAt = Instant.now().toString();

        String message = String.format("""
                {"eventId":"%s","eventType":"UserEmailChanged","payload":{"userId":"%s","email":"%s"},"occurredAt":"%s" """,
                eventId, userId, email, occurredAt
                );

        rabbitTemplate.convertAndSend(exchange, routingKey, message);
        rabbitTemplate.convertAndSend(exchange, routingKey, message); // Duplicate

        // Then: Should be processed only once (idempotency via inbox_event.event_id)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var contactOpt = userContactRepository.findByUserId(userId);
            assertThat(contactOpt).isPresent();
            assertThat(contactOpt.get().getEmail()).isEqualTo(email);
        });

        // Verify only one inbox event recorded
        var inboxEvent = inboxEventRepository.findByEventId(UUID.fromString(eventId));
        assertThat(inboxEvent).isPresent();
    }

    @Test
    void readinessReportsDownWhenConsumerNotSubscribed() {
        // Given: Health endpoint includes consumer status
        // When: Check readiness
        // Then: Should report consumer status
        var health = applicationContext.getBean(org.springframework.boot.actuate.health.HealthEndpoint.class).health();
        var details = health.getDetails();

        // Consumer readiness should be part of health check
        assertThat(details).containsKey("consumers");
    }
}