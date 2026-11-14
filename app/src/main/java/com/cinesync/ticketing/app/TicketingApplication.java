package com.cinesync.ticketing.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {
        "com.cinesync.ticketing.core",
        "com.cinesync.ticketing.adapter",
        "com.cinesync.ticketing.app"
})
@EntityScan(basePackages = "com.cinesync.ticketing.core.model")
@EnableJpaRepositories(basePackages = "com.cinesync.ticketing.adapter.out.repository")
public class TicketingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketingApplication.class, args);
    }
}