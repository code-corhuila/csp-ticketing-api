package com.cinesync.ticketing.app.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The service refuses to start without the public key of the identity service, and reads it from the PEM or from a file. */
class JwtKeyConfigurationTest {

    private final JwtKeyConfiguration configuration = new JwtKeyConfiguration();

    private static RSAPublicKey key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return (RSAPublicKey) generator.generateKeyPair().getPublic();
    }

    private static String pem(RSAPublicKey key, String lineBreak) {
        return "-----BEGIN PUBLIC KEY-----" + lineBreak + Base64.getEncoder().encodeToString(key.getEncoded()) + lineBreak
                + "-----END PUBLIC KEY-----";
    }

    @Test
    void withoutAKeyTheServiceDoesNotStart() {
        assertThatThrownBy(() -> configuration.jwtPublicKey("", "")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PUBLIC_KEY");
    }

    @Test
    void thePemOfTheEnvironmentIsReadAlsoWithEscapedLineBreaks() throws Exception {
        RSAPublicKey expected = key();

        assertThat(configuration.jwtPublicKey(pem(expected, "\n"), "")).isEqualTo(expected);
        assertThat(configuration.jwtPublicKey(pem(expected, "\n"), "")).isEqualTo(expected);
    }

    @Test
    void theKeyIsReadFromTheFileThePlatformMounts(@TempDir Path directory) throws Exception {
        RSAPublicKey expected = key();
        Path file = Files.writeString(directory.resolve("jwt.pem"), pem(expected, "\n"));

        assertThat(configuration.jwtPublicKey("", file.toString())).isEqualTo(expected);
    }

    @Test
    void aFileThatCannotBeReadOrAKeyThatIsNotRsaStopsTheStart() {
        assertThatThrownBy(() -> configuration.jwtPublicKey("", "/no/such/file.pem")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> configuration.jwtPublicKey("not a key", "")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an RSA public key");
    }
}
