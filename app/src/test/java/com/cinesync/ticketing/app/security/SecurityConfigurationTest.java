package com.cinesync.ticketing.app.security;

import com.cinesync.ticketing.app.rest.TicketPdfController;
import com.cinesync.ticketing.app.rest.TicketingController;
import com.cinesync.ticketing.app.rest.internal.TicketingInternalController;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase;
import com.cinesync.ticketing.core.port.in.TicketPdfDownloadUseCase;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The token checks of the norm (5.3.7, 5.3.12) and of ADR-056 on the real routes: a user token on the client routes, a
 * service credential on the internal ones, and the health routes open.
 */
@WebMvcTest
@ContextConfiguration(classes = {SecurityConfiguration.class, JwtKeyConfiguration.class, TicketPdfController.class,
        TicketingInternalController.class, TicketingController.class})
class SecurityConfigurationTest {

    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER_KEYS = generate();
    private static final String PDF = "/api/v1/ticketing/me/tickets/%s/pdf";
    private static final String AWAITING = "/internal/tickets/awaiting-pdf";

    private final UUID user = UUID.randomUUID();
    private final UUID ticket = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;
    @MockBean
    private TicketPdfDownloadUseCase download;
    @MockBean
    private TicketDeliveryUseCase delivery;
    @MockBean
    private HealthEndpoint health;

    @DynamicPropertySource
    static void keyAndSubjects(DynamicPropertyRegistry registry) {
        registry.add("ticketing.jwt.public-key", () -> pem((RSAPublicKey) KEYS.getPublic()));
        registry.add("ticketing.security.service-subjects", () -> "csp-worker");
    }

    @BeforeEach
    void theUseCasesAnswer() {
        when(download.download(any(), any())).thenReturn(new byte[]{'%', 'P', 'D', 'F'});
        when(delivery.awaitingPdf(any(Integer.class))).thenReturn(List.of());
    }

    // ---- fixtures

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

    private JWTClaimsSet.Builder userClaims() {
        return new JWTClaimsSet.Builder().subject(user.toString()).issuer("csp-auth").audience("csp-api")
                .expirationTime(new Date(System.currentTimeMillis() + 900_000))
                .claim("permissions", List.of("ticket:read:self"));
    }

    private JWTClaimsSet.Builder serviceClaims(String subject) {
        return new JWTClaimsSet.Builder().subject(subject).issuer("csp-auth").audience("csp-internal")
                .expirationTime(new Date(System.currentTimeMillis() + 900_000));
    }

    private static String sign(JWTClaimsSet claims, KeyPair keys) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private String pdfUrl() {
        return PDF.formatted(ticket);
    }

    // ---- the health routes are open

    @Test
    void theHealthRoutesNeedNoToken() throws Exception {
        mvc.perform(get("/api/v1/ticketing/health")).andExpect(status().isOk());
    }

    // ---- 401: no token or a token that breaks a rule of 5.3.7

    @Test
    void aRouteWithoutTokenIsUnauthorizedInTheEnvelope() throws Exception {
        mvc.perform(get(pdfUrl())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED")).andExpect(jsonPath("$.traceId").isNotEmpty());
        mvc.perform(get(AWAITING)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        verifyNoInteractions(download, delivery);
    }

    @Test
    void anExpiredTokenIsUnauthorizedButOneWithinThirtySecondsOfSkewIsAccepted() throws Exception {
        String expired = sign(userClaims().expirationTime(new Date(System.currentTimeMillis() - 120_000)).build(), KEYS);
        String justExpired = sign(userClaims().expirationTime(new Date(System.currentTimeMillis() - 10_000)).build(), KEYS);

        mvc.perform(get(pdfUrl()).header("Authorization", bearer(expired))).andExpect(status().isUnauthorized());
        mvc.perform(get(pdfUrl()).header("Authorization", bearer(justExpired))).andExpect(status().isOk());
    }

    @Test
    void aTokenWithAnotherAlgorithmOrNoSignatureIsUnauthorized() throws Exception {
        SignedJWT hs256 = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), userClaims().build());
        hs256.sign(new MACSigner(new byte[32]));
        String none = new PlainJWT(userClaims().build()).serialize();

        mvc.perform(get(pdfUrl()).header("Authorization", bearer(hs256.serialize()))).andExpect(status().isUnauthorized());
        mvc.perform(get(pdfUrl()).header("Authorization", bearer(none))).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenSignedWithAnotherKeyOrTamperedWithIsUnauthorized() throws Exception {
        String foreign = sign(userClaims().build(), OTHER_KEYS);
        String valid = sign(userClaims().build(), KEYS);
        String[] parts = valid.split("\\.");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                userClaims().subject(UUID.randomUUID().toString()).build().toString().getBytes()) + "." + parts[2];

        mvc.perform(get(pdfUrl()).header("Authorization", bearer(foreign))).andExpect(status().isUnauthorized());
        mvc.perform(get(pdfUrl()).header("Authorization", bearer(tampered))).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithoutExpOrWithoutSubIsUnauthorized() throws Exception {
        String noExp = sign(new JWTClaimsSet.Builder().subject(user.toString()).claim("permissions", List.of("ticket:read:self"))
                .build(), KEYS);
        String noSub = sign(new JWTClaimsSet.Builder().expirationTime(new Date(System.currentTimeMillis() + 900_000))
                .claim("permissions", List.of("ticket:read:self")).build(), KEYS);

        mvc.perform(get(pdfUrl()).header("Authorization", bearer(noExp))).andExpect(status().isUnauthorized());
        mvc.perform(get(pdfUrl()).header("Authorization", bearer(noSub))).andExpect(status().isUnauthorized());
    }

    @Test
    void aClientRouteTakesOnlyATokenWhoseSubIsAUserId() throws Exception {
        String service = sign(serviceClaims("csp-worker").claim("permissions", List.of("ticket:read:self")).build(), KEYS);

        mvc.perform(get(pdfUrl()).header("Authorization", bearer(service))).andExpect(status().isUnauthorized());
    }

    // ---- client routes: permission and owner

    @Test
    void aClientTokenWithThePermissionReachesTheRouteAsItsOwner() throws Exception {
        mvc.perform(get(pdfUrl()).header("Authorization", bearer(sign(userClaims().build(), KEYS)))).andExpect(status().isOk());

        verify(download).download(eq(user), eq(ticket));
    }

    @Test
    void aClientTokenWithoutThePermissionIsForbiddenInTheEnvelope() throws Exception {
        String noPermission = sign(userClaims().claim("permissions", List.of("catalog:read")).build(), KEYS);
        String noClaim = sign(new JWTClaimsSet.Builder().subject(user.toString())
                .expirationTime(new Date(System.currentTimeMillis() + 900_000)).build(), KEYS);

        mvc.perform(get(pdfUrl()).header("Authorization", bearer(noPermission))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        mvc.perform(get(pdfUrl()).header("Authorization", bearer(noClaim))).andExpect(status().isForbidden());
        verifyNoInteractions(download);
    }

    // ---- internal routes: a service credential of the allowed subjects

    @Test
    void anAllowedServiceCredentialReachesTheInternalRoutes() throws Exception {
        String worker = sign(serviceClaims("csp-worker").build(), KEYS);

        mvc.perform(get(AWAITING).header("Authorization", bearer(worker))).andExpect(status().isOk());
        mvc.perform(post("/internal/fulfillments/dispatch").header("Authorization", bearer(worker))).andExpect(status().isOk());
    }

    @Test
    void aValidServiceCredentialOfAnotherSubjectIsForbidden() throws Exception {
        String other = sign(serviceClaims("booking-service").build(), KEYS);

        mvc.perform(get(AWAITING).header("Authorization", bearer(other))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void aTokenThatIsNotAServiceCredentialIsUnauthorizedOnTheInternalRoutes() throws Exception {
        String userToken = sign(userClaims().build(), KEYS);
        String wrongIssuer = sign(serviceClaims("csp-worker").issuer("someone-else").build(), KEYS);
        String wrongAudience = sign(serviceClaims("csp-worker").audience("csp-api").build(), KEYS);
        String noAudience = sign(new JWTClaimsSet.Builder().subject("csp-worker").issuer("csp-auth")
                .expirationTime(new Date(System.currentTimeMillis() + 900_000)).build(), KEYS);
        String listedAudience = sign(serviceClaims("csp-worker").audience(List.of("csp-api", "csp-internal")).build(), KEYS);

        mvc.perform(get(AWAITING).header("Authorization", bearer(userToken))).andExpect(status().isUnauthorized());
        mvc.perform(get(AWAITING).header("Authorization", bearer(wrongIssuer))).andExpect(status().isUnauthorized());
        mvc.perform(get(AWAITING).header("Authorization", bearer(wrongAudience))).andExpect(status().isUnauthorized());
        mvc.perform(get(AWAITING).header("Authorization", bearer(noAudience))).andExpect(status().isUnauthorized());
        mvc.perform(get(AWAITING).header("Authorization", bearer(listedAudience))).andExpect(status().isOk());
    }
}
