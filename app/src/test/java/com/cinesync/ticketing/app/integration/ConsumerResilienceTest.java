package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import com.cinesync.ticketing.core.port.out.UserContactRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * HU-TICKETING-001, resubscription: the broker deletes the queue of a running consumer (it sends
 * basic.cancel), and the service has to declare it again and keep consuming without losing or duplicating.
 */
class ConsumerResilienceTest extends ProjectionTestSupport {

    private static final String RESUBSCRIPTIONS = "ticketing.consumer.resubscriptions";

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private UserContactRepository contacts;

    @Autowired
    private InboxEventRepository inbox;

    @Autowired
    @Qualifier("consumers")
    private HealthIndicator consumers;

    @Autowired
    private MeterRegistry meterRegistry;

    private void awaitSubscribed() {
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
            Properties queue = rabbitAdmin.getQueueProperties(REGISTERED_QUEUE);
            assertThat(queue).as("queue declared again by the service").isNotNull();
            assertThat(queue.get(RabbitAdmin.QUEUE_CONSUMER_COUNT)).isEqualTo(1);
            assertThat(consumers.health().getStatus()).isEqualTo(Status.UP);
        });
    }

    private void awaitEmail(UUID userId, String email) {
        await().atMost(30, TimeUnit.SECONDS)
                .until(() -> contacts.findByUserId(userId).map(c -> c.getEmail().equals(email)).orElse(false));
    }

    @Test
    void readiness_isUpWhileEveryConsumerIsSubscribed() {
        awaitSubscribed();
    }

    @Test
    void queueDeletedByTheBroker_isDeclaredAgainAndTheNextMessageIsConsumedOnce() {
        awaitSubscribed();
        double resubscriptionsBefore = meterRegistry.counter(RESUBSCRIPTIONS).count();
        UUID userId = UUID.randomUUID();
        publish(REGISTERED_KEY, event(UUID.randomUUID(), "UserRegistered", userId, "before@example.com", T0));
        awaitEmail(userId, "before@example.com");

        rabbitAdmin.deleteQueue(REGISTERED_QUEUE);
        awaitSubscribed();

        UUID afterEventId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        publish(REGISTERED_KEY, event(afterEventId, "UserRegistered", otherUser, "after@example.com", T0));
        awaitEmail(otherUser, "after@example.com");
        assertThat(inbox.findByEventId(afterEventId)).isPresent();
        assertThat(meterRegistry.counter(RESUBSCRIPTIONS).count()).isGreaterThan(resubscriptionsBefore);
    }

    @Test
    void queueDeletedWhileAMessageIsBeingProcessed_losesAndDuplicatesNothing() throws Exception {
        awaitSubscribed();
        CountDownLatch inProcessing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            inProcessing.countDown();
            release.await(30, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(commandService).handleUserRegistered(any(), any(), any());
        UUID userId = UUID.randomUUID();
        UUID heldEventId = UUID.randomUUID();
        String held = event(heldEventId, "UserRegistered", userId, "held@example.com", T0);

        publish(REGISTERED_KEY, held);
        assertThat(inProcessing.await(15, TimeUnit.SECONDS)).isTrue();
        rabbitAdmin.deleteQueue(REGISTERED_QUEUE);
        release.countDown();

        awaitEmail(userId, "held@example.com");
        awaitSubscribed();
        publish(REGISTERED_KEY, held);
        UUID barrier = UUID.randomUUID();
        publish(REGISTERED_KEY, event(barrier, "UserRegistered", UUID.randomUUID(), "barrier@example.com", T0));
        await().atMost(30, TimeUnit.SECONDS).until(() -> inbox.findByEventId(barrier).isPresent());

        assertThat(inbox.findByEventId(heldEventId)).isPresent();
        assertThat(contacts.findByUserId(userId).orElseThrow().getEmail()).isEqualTo("held@example.com");
        assertThat(contacts.findByUserId(userId).orElseThrow().getLastUpdatedAt()).isEqualTo(T0);
    }
}
