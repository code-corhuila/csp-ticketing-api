package com.cinesync.ticketing.app.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The topology the service declares at start-up, built from the real {@code application.yml}, against what the broker lets the
 * login {@code ticketing_fulfillment} do (ADR-036): it may configure only the queues {@code cine.ticketing_fulfillment.*}, and
 * each queue dead-letters to its own {@code .dlq} through {@code cine.dlx} (ADR-007).
 */
class TicketingQueuesTest {

    private static final String PERMITTED = "^cine[.]ticketing_fulfillment[.].*";

    private Properties yaml;
    private Declarables declared;

    @BeforeEach
    void declareTheTopologyOfTheApplicationYaml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        yaml = factory.getObject();
        declared = new TicketingConfiguration().ticketingQueues(
                property("exchange"),
                property("queue.user-registered"),
                property("queue.user-email-changed"),
                property("queue.booking-confirmed"),
                property("queue.concession-order-confirmed"),
                property("queue.ticket-issued"),
                property("routing-key.booking-confirmed"),
                property("routing-key.user-registered"),
                property("routing-key.user-email-changed"));
    }

    private String property(String name) {
        return yaml.getProperty("ticketing.rabbitmq." + name);
    }

    private List<Queue> queues() {
        return declared.getDeclarablesByType(Queue.class);
    }

    private List<Binding> bindings() {
        return declared.getDeclarablesByType(Binding.class);
    }

    @Test
    void everyDeclaredQueueIsOneTheLoginOfTheServiceMayConfigure() {
        assertThat(queues()).isNotEmpty().allSatisfy(queue -> assertThat(queue.getName()).matches(PERMITTED));
    }

    @Test
    void everyQueueDeadLettersToItsOwnDlqThroughTheDeadLetterExchange() {
        List<Queue> mainQueues = queues().stream().filter(queue -> !queue.getName().endsWith(".dlq")).toList();

        assertThat(mainQueues).hasSize(5).allSatisfy(queue -> {
            String dlq = queue.getName() + ".dlq";
            assertThat(queue.getArguments()).containsEntry("x-dead-letter-exchange", "cine.dlx")
                    .containsEntry("x-dead-letter-routing-key", dlq);
            assertThat(queues()).extracting(Queue::getName).contains(dlq);
            assertThat(bindings()).anySatisfy(binding -> {
                assertThat(binding.getDestination()).isEqualTo(dlq);
                assertThat(binding.getExchange()).isEqualTo("cine.dlx");
                assertThat(binding.getRoutingKey()).isEqualTo(dlq);
            });
        });
    }

    @Test
    void theTicketIssuedQueueIsBoundToTheRoutingKeyTheRelayPublishes() {
        String queue = property("queue.ticket-issued");

        assertThat(queue).isEqualTo("cine.ticketing_fulfillment.ticket-issued");
        assertThat(bindings()).anySatisfy(binding -> {
            assertThat(binding.getDestination()).isEqualTo(queue);
            assertThat(binding.getExchange()).isEqualTo("cine.events");
            assertThat(binding.getRoutingKey()).isEqualTo("cine.ticketing_fulfillment.ticket-issued.v1");
        });
    }

    @Test
    void noExchangeIsDeclaredBecauseTheyBelongToTheBrokerRepositoryAndTheLoginCannotConfigureThem() {
        assertThat(declared.getDeclarablesByType(Exchange.class)).isEmpty();
        assertThat(bindings()).extracting(Binding::getExchange).containsOnly("cine.events", "cine.dlx");
    }

    @Test
    void noSharedDeadLetterQueueOutsideThePatternIsDeclared() {
        assertThat(queues()).extracting(Queue::getName).doesNotContain("ticketing.dlq", "ticketing.ticket-issued");
        assertThat(yaml.stringPropertyNames()).doesNotContain("ticketing.rabbitmq.queue.dlq");
    }
}
