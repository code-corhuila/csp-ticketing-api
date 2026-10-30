package com.cinesync.ticketing.app.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The public key every token of this service is validated with (Norma 5.3.7). It comes from the environment:
 * {@code JWT_PUBLIC_KEY} carries the PEM and {@code JWT_PUBLIC_KEY_FILE} the path of the file the platform mounts. One of
 * the two is required and the service refuses to start without it; it never holds a private key nor a shared secret.
 */
@Configuration
public class JwtKeyConfiguration {

    private static final String BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String END = "-----END PUBLIC KEY-----";

    @Bean
    RSAPublicKey jwtPublicKey(
            @Value("${ticketing.jwt.public-key:}") String inlinePem,
            @Value("${ticketing.jwt.public-key-file:}") String file) {
        String pem = !inlinePem.isBlank() ? inlinePem.replace("\\n", "\n") : read(file);
        if (pem.isBlank()) {
            throw new IllegalStateException("JWT_PUBLIC_KEY or JWT_PUBLIC_KEY_FILE is required: the service validates every token by itself");
        }
        return parse(pem);
    }

    private static String read(String file) {
        if (file == null || file.isBlank()) {
            return "";
        }
        try {
            return Files.readString(Path.of(file));
        } catch (Exception e) {
            throw new IllegalStateException("JWT_PUBLIC_KEY_FILE cannot be read", e);
        }
    }

    private static RSAPublicKey parse(String pem) {
        try {
            byte[] encoded = Base64.getDecoder().decode(pem.replace(BEGIN, "").replace(END, "").replaceAll("\\s", ""));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new IllegalStateException("JWT_PUBLIC_KEY is not an RSA public key", e);
        }
    }
}
