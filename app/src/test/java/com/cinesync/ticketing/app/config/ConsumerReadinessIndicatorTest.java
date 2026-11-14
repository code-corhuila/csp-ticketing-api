package com.cinesync.ticketing.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsumerReadinessIndicatorTest {

    private final RabbitListenerEndpointRegistry registry = mock(RabbitListenerEndpointRegistry.class);
    private final ConsumerReadinessIndicator indicator = new ConsumerReadinessIndicator(registry);

    private void containers(SimpleMessageListenerContainer... containers) {
        when(registry.getListenerContainers()).thenReturn(List.of(containers));
    }

    private static SimpleMessageListenerContainer container(String queue, boolean running, int activeConsumers) {
        SimpleMessageListenerContainer container = mock(SimpleMessageListenerContainer.class);
        when(container.isRunning()).thenReturn(running);
        when(container.getActiveConsumerCount()).thenReturn(activeConsumers);
        when(container.getQueueNames()).thenReturn(new String[] {queue});
        return container;
    }

    @Test
    void up_whenEveryContainerHasAConsumer() {
        containers(container("q1", true, 1), container("q2", true, 1));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void down_whenAContainerIsRunningWithoutConsumers_asAfterABrokerCancel() {
        containers(container("q1", true, 1), container("q2", true, 0));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("q1", "subscribed").containsEntry("q2", "not subscribed");
    }

    @Test
    void down_whenAContainerIsStopped() {
        containers(container("q1", false, 1));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void down_whenThereAreNoContainers() {
        containers();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
