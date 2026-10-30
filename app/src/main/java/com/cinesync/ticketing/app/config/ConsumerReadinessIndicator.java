package com.cinesync.ticketing.app.config;

import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports DOWN while any listener container has no consumer subscribed to its queues, for example after
 * the broker cancelled the consumer or deleted its queue and the container has not resubscribed yet.
 */
@Component("consumers")
public class ConsumerReadinessIndicator implements HealthIndicator {

    private final RabbitListenerEndpointRegistry registry;

    public ConsumerReadinessIndicator(RabbitListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        if (containers.isEmpty()) {
            return Health.down().withDetail("consumers", "no listener containers found").build();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        boolean allSubscribed = true;
        for (MessageListenerContainer container : containers) {
            boolean subscribed = isSubscribed(container);
            allSubscribed &= subscribed;
            details.put(queueNames(container), subscribed ? "subscribed" : "not subscribed");
        }
        return (allSubscribed ? Health.up() : Health.down()).withDetails(details).build();
    }

    private static boolean isSubscribed(MessageListenerContainer container) {
        return container.isRunning()
                && container instanceof SimpleMessageListenerContainer simple
                && simple.getActiveConsumerCount() > 0;
    }

    private static String queueNames(MessageListenerContainer container) {
        return container instanceof AbstractMessageListenerContainer abstractContainer
                ? String.join(",", Arrays.asList(abstractContainer.getQueueNames()))
                : container.toString();
    }
}
