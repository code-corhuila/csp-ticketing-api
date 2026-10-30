package com.cinesync.ticketing.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The application as it is deployed: every bean of the real configuration, on a real PostgreSQL with the schema and a real
 * RabbitMQ. It is the test that fails when a controller needs a bean nobody provides, which stopped the service from starting
 * in {@code develop}. The security filters are left out here: the token checks have their own tests. Bean overriding is allowed
 * because the configuration of the integration tests sits in the scanned package and defines the same repositories.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc(addFilters = false)
class TicketingApplicationTests {

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("ticketing-schema.sql");

    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    static {
        postgres.start();
        rabbitMQ.start();
    }

    @DynamicPropertySource
    static void serviceSettings(DynamicPropertyRegistry registry) {
        // The reader account is required at start; nothing is stored in these tests, so any value does.
        registry.add("ticketing.object-storage.url", () -> "http://127.0.0.1:1");
        registry.add("ticketing.object-storage.access-key", () -> "test-reader");
        registry.add("ticketing.object-storage.secret-key", () -> "test-reader-secret");
        registry.add("ticketing.jwt.public-key", TicketingApplicationTests::aPublicKey);
    }

    /** The service needs a public key to start; no token is checked in this test, so any RSA key does. */
    private static String aPublicKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void contextLoads() {
    }

    @Test
    void readinessNamesTheStateOfEveryComponentItChecks() throws Exception {
        // No storage and no mail server run in this test, so the answer is 503 with those two down and none unknown.
        mvc.perform(get("/api/v1/ticketing/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.broker").value("UP"))
                .andExpect(jsonPath("$.objectStorage").value("DOWN"))
                .andExpect(jsonPath("$.emailProvider").value("DOWN"))
                .andExpect(jsonPath("$.consumers").exists());
    }
}
