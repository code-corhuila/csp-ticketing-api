package com.cinesync.ticketing.testsupport;

import com.cinesync.ticketing.adapter.in.rabbitmq.TicketingCommandService;
import com.cinesync.ticketing.adapter.in.rabbitmq.UserEventConsumer;
import com.cinesync.ticketing.adapter.out.repository.UserContactJpaRepository;
import com.cinesync.ticketing.adapter.service.TicketDeliveryService;
import com.cinesync.ticketing.app.config.ConsumerLifecycleListener;
import com.cinesync.ticketing.app.config.ConsumerReadinessIndicator;
import com.cinesync.ticketing.app.config.TicketingConfiguration;
import com.cinesync.ticketing.core.model.UserContact;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The beans the integration tests need: the consumers, the command and delivery services and the RabbitMQ topology,
 * without the REST layer. It lives outside {@code com.cinesync.ticketing.app} on purpose: {@code TicketingApplication}
 * scans that package, and a second {@code @EnableJpaRepositories} there would define every repository twice.
 */
@Configuration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = UserContact.class)
@EnableJpaRepositories(basePackageClasses = UserContactJpaRepository.class)
@Import({TicketingConfiguration.class, UserEventConsumer.class, TicketingCommandService.class,
        TicketDeliveryService.class, ConsumerReadinessIndicator.class, ConsumerLifecycleListener.class})
public class ProjectionApp {
}
