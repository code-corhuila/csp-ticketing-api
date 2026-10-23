package com.cinesync.ticketing.app.config;

import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ConsumerReadinessIndicator implements HealthIndicator {

    private final ApplicationContext applicationContext;

    public ConsumerReadinessIndicator(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public Health health() {
        Map<String, SimpleMessageListenerContainer> containers = applicationContext.getBeansOfType(SimpleMessageListenerContainer.class);

        if (containers.isEmpty()) {
            return Health.down()
                    .withDetail("consumers", "no listener containers found")
                    .build();
        }

        Map<String, Object> details = containers.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            SimpleMessageListenerContainer container = entry.getValue();
                            return Map.of(
                                    "running", container.isRunning(),
                                    "active", container.isActive(),
                                    "queueNames", container.getQueueNames()
                            );
                        }
                ));

        boolean allRunning = containers.values().stream()
                .allMatch(SimpleMessageListenerContainer::isRunning);

        if (allRunning) {
            return Health.up()
                    .withDetails(details)
                    .build();
        } else {
            return Health.down()
                    .withDetails(details)
                    .build();
        }
    }
}