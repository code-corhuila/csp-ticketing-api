package com.cinesync.ticketing.app.security;

import com.cinesync.ticketing.app.rest.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Who may call what (Norma 5.3.7, ADR-042, ADR-056). This service validates every token by itself with the public key of
 * the identity service: RS256 only, {@code exp} and {@code sub} required, 30 s of clock skew.
 *
 * <ul>
 *   <li>The health routes need no token.</li>
 *   <li>The routes of a client need a user token (its {@code sub} is the id of the user) with the permission
 *       {@code ticket:read:self}; without it the answer is {@code 403}.</li>
 *   <li>The internal operations of {@code csp-worker} need a service credential: issuer {@code csp-auth}, audience
 *       {@code csp-internal} and a {@code sub} in {@code SERVICE_SUBJECTS}. Any other token is {@code 401} (a user token is
 *       never a service credential); a valid credential of another subject is {@code 403}.</li>
 * </ul>
 * A rejection answers the single error envelope and never reaches a controller.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    static final String CLIENT_PERMISSION = "ticket:read:self";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final String SERVICE_ISSUER = "csp-auth";
    private static final String SERVICE_AUDIENCE = "csp-internal";

    @Bean
    @Order(1)
    SecurityFilterChain healthRoutes(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/ticketing/health", "/api/v1/ticketing/health/ready")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain internalRoutes(HttpSecurity http, RSAPublicKey key, ObjectMapper json,
            @Value("${ticketing.security.service-subjects:csp-worker}") Set<String> serviceSubjects) throws Exception {
        Set<String> allowed = Set.copyOf(serviceSubjects);
        http.securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest()
                        .access((authentication, context) -> new AuthorizationDecision(isAllowedService(authentication.get(), allowed))))
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.decoder(decoder(key, serviceCredential())))
                        .authenticationEntryPoint(unauthorized(json))
                        .accessDeniedHandler(forbidden(json)))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized(json)).accessDeniedHandler(forbidden(json)));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain clientRoutes(HttpSecurity http, RSAPublicKey key, ObjectMapper json) throws Exception {
        JwtGrantedAuthoritiesConverter permissions = new JwtGrantedAuthoritiesConverter();
        permissions.setAuthoritiesClaimName("permissions");
        permissions.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(permissions);
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/v1/ticketing/me/**").hasAuthority(CLIENT_PERMISSION)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.decoder(decoder(key, userId())).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(unauthorized(json))
                        .accessDeniedHandler(forbidden(json)))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized(json)).accessDeniedHandler(forbidden(json)));
        return http.build();
    }

    private static boolean isAllowedService(Authentication authentication, Set<String> allowed) {
        return authentication instanceof JwtAuthenticationToken token && allowed.contains(token.getToken().getSubject());
    }

    /** RS256 only, so {@code none}, HS256 and any other algorithm are rejected; {@code exp} and {@code sub} are required. */
    @SafeVarargs
    private static JwtDecoder decoder(RSAPublicKey key, OAuth2TokenValidator<Jwt>... kindOfToken) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt>[] validators = new OAuth2TokenValidator[kindOfToken.length + 2];
        validators[0] = new JwtTimestampValidator(CLOCK_SKEW);
        validators[1] = jwt -> jwt.getExpiresAt() == null || jwt.getSubject() == null || jwt.getSubject().isBlank()
                ? invalid("the token needs exp and sub") : OAuth2TokenValidatorResult.success();
        System.arraycopy(kindOfToken, 0, validators, 2, kindOfToken.length);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /** The {@code sub} of a user token is the id of the user in every table of the platform. */
    private static OAuth2TokenValidator<Jwt> userId() {
        return jwt -> {
            try {
                // All the validators run, so a token without sub reaches this one too.
                UUID.fromString(String.valueOf(jwt.getSubject()));
                return OAuth2TokenValidatorResult.success();
            } catch (IllegalArgumentException e) {
                return invalid("the sub of a user token is a user id");
            }
        };
    }

    /** ADR-056: a service credential carries issuer {@code csp-auth} and an audience that contains {@code csp-internal}. */
    private static OAuth2TokenValidator<Jwt> serviceCredential() {
        return jwt -> {
            Collection<String> audience = jwt.getAudience();
            boolean issuer = SERVICE_ISSUER.equals(String.valueOf(jwt.getClaims().get("iss")));
            return issuer && audience != null && audience.contains(SERVICE_AUDIENCE)
                    ? OAuth2TokenValidatorResult.success() : invalid("not a service credential");
        };
    }

    private static OAuth2TokenValidatorResult invalid(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }

    private static AuthenticationEntryPoint unauthorized(ObjectMapper json) {
        return (request, response, exception) -> write(json, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                "A valid access token is required");
    }

    private static AccessDeniedHandler forbidden(ObjectMapper json) {
        return (request, response, exception) -> write(json, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                "The token is not allowed to call this operation");
    }

    private static void write(ObjectMapper json, HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getWriter(), ErrorResponse.of(error, message));
    }
}
