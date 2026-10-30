package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.core.model.UserContact;
import com.cinesync.ticketing.core.port.out.InboxEventRepository;
import com.cinesync.ticketing.core.port.out.UserContactRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** HU-TICKETING-001, scenarios 1 and 2, against a real RabbitMQ and PostgreSQL. */
class UserContactProjectionTest extends ProjectionTestSupport {

    @Autowired
    private UserContactRepository contacts;

    @Autowired
    private InboxEventRepository inbox;

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
