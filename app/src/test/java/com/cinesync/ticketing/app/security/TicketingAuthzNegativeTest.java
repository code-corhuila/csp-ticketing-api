package com.cinesync.ticketing.app.security;

import com.cinesync.ticketing.app.TicketingSchema;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Threat S (spoofing) of the hop from the gateway to this service (csp-docs#291, norm 5.3.7): the service validates the
 * token itself, so a request that skipped the gateway is refused the same way. The application runs as deployed, with every
 * security filter on a real HTTP port, and the requests go straight to that port and not through the gateway.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TicketingAuthzNegativeTest {

    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER_KEYS = generate();
    private static final String TICKETS = "/api/v1/ticketing/me/tickets";

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUsername("postgres").withPassword("postgres");

    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    static {
        postgres.start();
        TicketingSchema.migrate(postgres);
        rabbitMQ.start();
    }

    @DynamicPropertySource
    static void serviceSettings(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("ticketing.object-storage.url", () -> "http://127.0.0.1:1");
        registry.add("ticketing.object-storage.access-key", () -> "test-reader");
        registry.add("ticketing.object-storage.secret-key", () -> "test-reader-secret");
        registry.add("ticketing.jwt.public-key", () -> pem((RSAPublicKey) KEYS.getPublic()));
    }

    @Autowired
    private TestRestTemplate http;

    @Test
    void aRequestWithoutAuthorizationIsUnauthorizedInTheErrorEnvelope() {
        ResponseEntity<Map> response = http.getForEntity(TICKETS, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsKeys("error", "message", "traceId");
        assertThat(response.getBody().get("error")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void anUnsignedTokenIsUnauthorized() {
        String none = new PlainJWT(userClaims().build()).serialize();

        assertThat(get(TICKETS, none).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anHs256TokenIsUnauthorizedEvenWhenItIsSignedWithTheBytesOfThePublicKey() throws Exception {
        SignedJWT hs256 = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), userClaims().build());
        hs256.sign(new MACSigner(Arrays.copyOf(KEYS.getPublic().getEncoded(), 64)));

        assertThat(get(TICKETS, hs256.serialize()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anRs256TokenSignedWithAnotherKeyIsUnauthorized() throws Exception {
        assertThat(get(TICKETS, sign(userClaims().build(), OTHER_KEYS)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anRs256TokenWithoutExpOrWithoutSubIsUnauthorized() throws Exception {
        String noExp = sign(new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString())
                .claim("permissions", List.of("ticket:read:self")).build(), KEYS);
        String noSub = sign(new JWTClaimsSet.Builder().expirationTime(new Date(System.currentTimeMillis() + 900_000))
                .claim("permissions", List.of("ticket:read:self")).build(), KEYS);

        assertThat(get(TICKETS, noExp).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(TICKETS, noSub).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theInternalOperationsRefuseAUserTokenAndAnUnsignedOne() throws Exception {
        String internal = "/internal/tickets/awaiting-pdf";

        assertThat(http.getForEntity(internal, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(internal, sign(userClaims().build(), KEYS)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(internal, new PlainJWT(userClaims().build()).serialize()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aValidRs256TokenIsNotRefusedForItsSignature() throws Exception {
        // The control of the tests above: the same request with a token signed by the key of the service gets past the
        // filters (the ticket does not exist), so the 401 of the others comes from the token and not from the route.
        String valid = sign(userClaims().build(), KEYS);

        ResponseEntity<Map> response = get(TICKETS + "/" + UUID.randomUUID() + "/pdf", valid);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private static JWTClaimsSet.Builder userClaims() {
        return new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).issuer("csp-auth").audience("csp-api")
                .expirationTime(new Date(System.currentTimeMillis() + 900_000))
                .claim("permissions", List.of("ticket:read:self"));
    }

    private static String sign(JWTClaimsSet claims, KeyPair keys) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(RSAPublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }
}
