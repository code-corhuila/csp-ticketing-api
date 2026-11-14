package com.cinesync.ticketing.app.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.AsyncConsumerRestartedEvent;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** Makes consumer cancellations and resubscriptions visible. Messages and payloads are never logged. */
@Component
@Slf4j
public class ConsumerLifecycleListener {

    private final Counter resubscriptions;

    public ConsumerLifecycleListener(MeterRegistry meterRegistry) {
        this.resubscriptions = Counter.builder("ticketing.consumer.resubscriptions")
                .description("Times a listener container subscribed again after its consumer stopped")
                .register(meterRegistry);
    }

    @EventListener
    public void onConsumerFailed(ListenerContainerConsumerFailedEvent event) {
        log.warn("Consumer of queues {} stopped ({}); it will subscribe again with backoff",
                queueNames(event.getSource()), event.getReason());
    }

    @EventListener
    public void onConsumerRestarted(AsyncConsumerRestartedEvent event) {
        resubscriptions.increment();
        log.info("Consumer of queues {} subscribed again", queueNames(event.getSource()));
    }

    private static String queueNames(Object container) {
        return container instanceof AbstractMessageListenerContainer listenerContainer
                ? String.join(",", Arrays.asList(listenerContainer.getQueueNames()))
                : String.valueOf(container);
    }
}
