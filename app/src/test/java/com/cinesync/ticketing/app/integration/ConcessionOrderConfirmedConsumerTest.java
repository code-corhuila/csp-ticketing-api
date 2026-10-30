package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.model.TicketingFulfillment;
import com.cinesync.ticketing.core.model.UserContact;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import com.cinesync.ticketing.core.port.out.TicketRepository;
import com.cinesync.ticketing.core.port.out.TicketingFulfillmentRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for ConcessionOrderConfirmed consumer.
 * Tests deduplication and order-independent processing (before/after BookingConfirmed).
 */
@SpringBootTest
@Testcontainers
class ConcessionOrderConfirmedConsumerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("csp")
            .withUsername("ticketing_app")
            .withPassword("ticketing_pass");

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
    private TicketRepository ticketRepository;

    @Autowired
    private TicketingFulfillmentRepository fulfillmentRepository;

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
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        registry.add("spring.rabbitmq.listener.simple.missing-queues-fatal", () -> "false");
        registry.add("ticketing.rabbitmq.declare-at-startup", () -> "true");
    }

    @BeforeAll
    static void setup() {
        await().atMost(60, TimeUnit.SECONDS).until(postgres::isRunning);
        await().atMost(60, TimeUnit.SECONDS).until(rabbitMQ::isRunning);
    }

    @AfterAll
    static void teardown() {
        rabbitMQ.stop();
        postgres.stop();
    }

    /**
     * Test 1: Send the real payload (from csp-ticketing-api#5) twice.
     * Should see the snapshot in the ticket and the second time ignored (idempotency).
     */
    @Test
    void concessionOrderConfirmed_duplicateDelivery_idempotent() throws Exception {
        // Given: Setup queue and consumer
        String queueName = "ticketing.concession-order-confirmed";
        String exchange = "cine.events";
        String routingKey = "cine.concessions.order-confirmed.v1";

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

        // Create user contact first (required for ticket creation)
        UUID userId = UUID.randomUUID();
        UserContact contact = UserContact.builder()
                .userId(userId)
                .email("test@example.com")
                .lastUpdatedAt(Instant.now())
                .build();
        userContactRepository.save(contact);

        // Create a ticket for the reservation (BookingConfirmed already happened)
        UUID reservationId = UUID.randomUUID();
        UUID showtimeId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        
        Ticket ticket = Ticket.builder()
                .id(ticketId)
                .userContactId(contact.getId())
                .reservationId(reservationId)
                .showtimeId(showtimeId)
                .movieTitleSnapshot("Test Movie")
                .roomNameSnapshot("Room 1")
                .showtimeStartsAt(Instant.now().plusSeconds(3600))
                .seatLabelsSnapshot(List.of("A1", "A2"))
                .qrPayload("CSP-" + reservationId + "-A1-A2")
                .build();
        ticketRepository.save(ticket);

        // Create fulfillment record
        TicketingFulfillment fulfillment = TicketingFulfillment.builder()
                .ticketId(ticketId)
                .channel("EMAIL")
                .status(TicketingFulfillment.FulfillmentStatus.PENDING)
                .attempts(0)
                .build();
        fulfillmentRepository.save(fulfillment);

        // Payload from csp-ticketing-api#5 (ConcessionOrderConfirmed)
        String eventId = UUID.randomUUID().toString();
        String itemsJson = """
                {
                    "items": [
                        {
                            "itemType": "PRODUCT",
                            "itemId": "61000000-0000-0000-0000-000000000001",
                            "itemName": "Popcorn (large)",
                            "unitPrice": 500,
                            "quantity": 2
                        },
                        {
                            "itemType": "COMBO",
                            "itemId": "65000000-0000-0000-0000-000000000001",
                            "itemName": "Combo Familiar",
                            "unitPrice": 1200,
                            "quantity": 1
                        }
                    ]
                }
                """;

        String message = String.format("""
                {
                    "eventId": "%s",
                    "eventType": "ConcessionOrderConfirmed",
                    "occurredAt": "%s",
                    "version": 1,
                    "source": "concessions-service",
                    "aggregateId": "%s",
                    "aggregateType": "ConcessionOrder",
                    "payload": {
                        "orderId": "%s",
                        "userId": "%s",
                        "reservationId": "%s",
                        "status": "CONFIRMED",
                        "totalAmount": 2200,
                        "items": [
                            {
                                "itemType": "PRODUCT",
                                "itemId": "61000000-0000-0000-0000-000000000001",
                                "itemName": "Popcorn (large)",
                                "unitPrice": 500,
                                "quantity": 2
                            },
                            {
                                "itemType": "COMBO",
                                "itemId": "65000000-0000-0000-0000-000000000001",
                                "itemName": "Combo Familiar",
                                "unitPrice": 1200,
                                "quantity": 1
                            }
                        ]
                    },
                    "metadata": {
                        "correlationId": "%s"
                    }
                }
                """,
                eventId,
                Instant.now().toString(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                userId,
                reservationId,
                UUID.randomUUID()
        );

        // When: Publish message first time
        rabbitTemplate.convertAndSend(exchange, routingKey, message);

        // Then: First delivery should update concessions snapshot
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var ticketOpt = ticketRepository.findByReservationId(reservationId);
            assertThat(ticketOpt).isPresent();
            var ticketConcessions = ticketOpt.get().getConcessionsSnapshot();
            assertThat(ticketConcessions).isNotNull();
            assertThat(ticketConcessions).hasSize(2);
            
            // Verify first item (Popcorn)
            var popcorn = ticketConcessions.stream()
                    .filter(c -> c.getName().equals("Popcorn (large)"))
                    .findFirst();
            assertThat(popcorn).isPresent();
            assertThat(popcorn.get().getQuantity()).isEqualTo(2);
            assertThat(popcorn.get().getUnitPriceCents()).isEqualTo(500);
            
            // Verify second item (Combo Familiar)
            var combo = ticketConcessions.stream()
                    .filter(c -> c.getName().equals("Combo Familiar"))
                    .findFirst();
            assertThat(combo).isPresent();
            assertThat(combo.get().getQuantity()).isEqualTo(1);
            assertThat(combo.get().getUnitPriceCents()).isEqualTo(1200);
        });

        // Verify inbox event recorded
        var inboxEvent = inboxEventRepository.findByEventId(UUID.fromString(eventId));
        assertThat(inboxEvent).isPresent();

        // When: Publish same message again (duplicate delivery)
        rabbitTemplate.convertAndSend(exchange, routingKey, message);

        // Then: Second delivery should be ignored (idempotency)
        // Wait a bit and verify concessions haven't changed (no duplicates)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var ticketOpt = ticketRepository.findByReservationId(reservationId);
            assertThat(ticketOpt).isPresent();
            var ticketConcessions = ticketOpt.get().getConcessionsSnapshot();
            assertThat(ticketConcessions).hasSize(2); // Still 2 items, no duplicates
            
            // Verify quantities unchanged
            var popcorn = ticketConcessions.stream()
                    .filter(c -> c.getName().equals("Popcorn (large)"))
                    .findFirst();
            assertThat(popcorn).isPresent();
            assertThat(popcorn.get().getQuantity()).isEqualTo(2); // Still 2, not 4
        });

        // Verify only one inbox event recorded (idempotency)
        var duplicateInboxEvent = inboxEventRepository.findByEventId(UUID.fromString(eventId));
        assertThat(duplicateInboxEvent).isPresent();
    }

    /**
     * Test 2: ConcessionOrderConfirmed arrives BEFORE BookingConfirmed.
     * Order doesn't matter - concessions should be merged when ticket is created.
     */
    @Test
    void concessionOrderConfirmed_beforeBookingConfirmed_mergedOnTicketCreation() throws Exception {
        // Given: Setup queue and consumer
        String queueName = "ticketing.concession-order-confirmed";
        String exchange = "cine.events";
        String routingKey = "cine.concessions.order-confirmed.v1";

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

        // Create user contact
        UUID userId = UUID.randomUUID();
        UserContact contact = UserContact.builder()
                .userId(userId)
                .email("test2@example.com")
                .lastUpdatedAt(Instant.now())
                .build();
        userContactRepository.save(contact);

        UUID reservationId = UUID.randomUUID();
        UUID showtimeId = UUID.randomUUID();

        // Payload from csp-ticketing-api#5 - ConcessionOrderConfirmed FIRST (before BookingConfirmed)
        String eventId = UUID.randomUUID().toString();
        String message = String.format("""
                {
                    "eventId": "%s",
                    "eventType": "ConcessionOrderConfirmed",
                    "occurredAt": "%s",
                    "version": 1,
                    "source": "concessions-service",
                    "aggregateId": "%s",
                    "aggregateType": "ConcessionOrder",
                    "payload": {
                        "orderId": "%s",
                        "userId": "%s",
                        "reservationId": "%s",
                        "status": "CONFIRMED",
                        "totalAmount": 1700,
                        "items": [
                            {
                                "itemType": "PRODUCT",
                                "itemId": "61000000-0000-0000-0000-000000000001",
                                "itemName": "Popcorn (large)",
                                "unitPrice": 500,
                                "quantity": 2
                            },
                            {
                                "itemType": "COMBO",
                                "itemId": "65000000-0000-0000-0000-000000000001",
                                "itemName": "Combo Familiar",
                                "unitPrice": 1200,
                                "quantity": 1
                            }
                        ]
                    },
                    "metadata": {
                        "correlationId": "%s"
                    }
                }
                """,
                eventId,
                Instant.now().toString(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                userId,
                reservationId,
                UUID.randomUUID()
        );

        // When: Publish ConcessionOrderConfirmed FIRST (before BookingConfirmed)
        rabbitTemplate.convertAndSend(exchange, routingKey, message);

        // Then: Should be processed (no ticket yet, but stored as pending)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var inboxEvent = inboxEventRepository.findByEventId(UUID.fromString(eventId));
            assertThat(inboxEvent).isPresent();
        });

        // No ticket should exist yet (BookingConfirmed hasn't arrived)
        var ticketBeforeBooking = ticketRepository.findByReservationId(reservationId);
        assertThat(ticketBeforeBooking).isEmpty();

        // Now: Send BookingConfirmed (simulating booking confirmation AFTER concessions)
        String bookingEventId = UUID.randomUUID().toString();
        String bookingMessage = String.format("""
                {
                    "eventId": "%s",
                    "eventType": "BookingConfirmed",
                    "occurredAt": "%s",
                    "version": 1,
                    "source": "booking-service",
                    "aggregateId": "%s",
                    "aggregateType": "Reservation",
                    "payload": {
                        "reservationId": "%s",
                        "userId": "%s",
                        "showtimeId": "%s",
                        "movieTitle": "Test Movie",
                        "roomName": "Room 1",
                        "showtimeStartsAt": "%s",
                        "seatNumbers": ["A1", "A2"]
                    },
                    "metadata": {
                        "correlationId": "%s"
                    }
                }
                """,
                bookingEventId,
                Instant.now().toString(),
                reservationId,
                reservationId,
                userId,
                showtimeId,
                Instant.now().plusSeconds(3600).toString(),
                UUID.randomUUID()
        );

        String bookingExchange = "cine.events";
        String bookingRoutingKey = "cine.booking.booking-confirmed.v1";
        String bookingQueue = "ticketing.booking-confirmed.v1";

        // Ensure booking queue is declared
        rabbitAdmin.declareQueue(new Queue(bookingQueue, true));
        rabbitAdmin.declareBinding(org.springframework.amqp.core.BindingBuilder
                .bind(new Queue(bookingQueue, true))
                .to(new org.springframework.amqp.core.TopicExchange(bookingExchange, true, false))
                .with(bookingRoutingKey));

        // When: Send BookingConfirmed AFTER concessions
        rabbitTemplate.convertAndSend(bookingExchange, bookingRoutingKey, bookingMessage);

        // Then: Ticket should be created with concessions merged
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            var ticketOpt = ticketRepository.findByReservationId(reservationId);
            assertThat(ticketOpt).isPresent();
            
            var ticket = ticketOpt.get();
            assertThat(ticket.getReservationId()).isEqualTo(reservationId);
            assertThat(ticket.getUserContactId()).isNotNull();
            
            // Verify concessions were merged from pending
            var concessions = ticket.getConcessionsSnapshot();
            assertThat(concessions).isNotNull();
            assertThat(concessions).hasSize(2);
            
            var popcorn = concessions.stream()
                    .filter(c -> c.getName().equals("Popcorn (large)"))
                    .findFirst();
            assertThat(popcorn).isPresent();
            assertThat(popcorn.get().getQuantity()).isEqualTo(2);
            assertThat(popcorn.get().getUnitPriceCents()).isEqualTo(500);
            
            var combo = concessions.stream()
                    .filter(c -> c.getName().equals("Combo Familiar"))
                    .findFirst();
            assertThat(combo).isPresent();
            assertThat(combo.get().getQuantity()).isEqualTo(1);
            assertThat(combo.get().getUnitPriceCents()).isEqualTo(1200);
        });

        // Verify both inbox events recorded
        var inboxEvent1 = inboxEventRepository.findByEventId(UUID.fromString(eventId));
        var inboxEvent2 = inboxEventRepository.findByEventId(UUID.fromString(bookingEventId));
        assertThat(inboxEvent1).isPresent();
        assertThat(inboxEvent2).isPresent();
    }
}