package com.cinesync.ticketing.app.integration;

import com.cinesync.ticketing.adapter.out.storage.S3TicketPdfStorage;
import com.cinesync.ticketing.core.model.PdfStorageException;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The reader of the ticket PDFs against a real S3-compatible storage with the bucket and the key of ADR-058. */
class S3TicketPdfStorageTest {

    private static final String BUCKET = "tickets";
    private static final String KEY = "tickets/22222222-2222-4222-8222-222222222222/33333333-3333-4333-8333-333333333333.pdf";

    private static final GenericContainer<?> STORAGE = new GenericContainer<>("rustfs/rustfs:1.0.4")
            .withEnv("RUSTFS_ACCESS_KEY", "test-root")
            .withEnv("RUSTFS_SECRET_KEY", "test-root-secret")
            .withCommand("/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/health").forPort(9000));

    private static String url;

    @BeforeAll
    static void startTheStorageWithTheBucket() throws Exception {
        STORAGE.start();
        url = "http://" + STORAGE.getHost() + ":" + STORAGE.getMappedPort(9000);
        MinioClient admin = MinioClient.builder().endpoint(url).credentials("test-root", "test-root-secret").build();
        if (!admin.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
        byte[] pdf = "%PDF-test".getBytes(StandardCharsets.UTF_8);
        admin.putObject(PutObjectArgs.builder().bucket(BUCKET).object(KEY)
                .stream(new ByteArrayInputStream(pdf), pdf.length, -1).contentType("application/pdf").build());
    }

    @AfterAll
    static void stopTheStorage() {
        STORAGE.stop();
    }

    private static S3TicketPdfStorage storage() {
        return new S3TicketPdfStorage(url, "test-root", "test-root-secret", BUCKET, "us-east-1");
    }

    @Test
    void anExistingObjectIsReadWithItsBytes() {
        assertThat(storage().read(KEY)).hasValueSatisfying(
                bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("%PDF-test"));
    }

    @Test
    void aMissingObjectIsEmptyAndNotAnError() {
        assertThat(storage().read("tickets/none/none.pdf")).isEmpty();
    }

    @Test
    void theBucketIsCheckedWithTheAccountOfTheApi() {
        assertThatCode(() -> storage().check()).doesNotThrowAnyException();
        assertThatThrownBy(() -> new S3TicketPdfStorage(url, "test-root", "test-root-secret", "other", "us-east-1").check())
                .isInstanceOf(PdfStorageException.class);
    }

    @Test
    void aStorageThatDoesNotAnswerIsAStorageFailureThatDoesNotLeakTheCredentials() {
        S3TicketPdfStorage down = new S3TicketPdfStorage("http://127.0.0.1:1", "test-root", "test-root-secret", BUCKET, "us-east-1");

        assertThatThrownBy(() -> down.read(KEY)).isInstanceOf(PdfStorageException.class)
                .hasMessageNotContaining("test-root-secret");
        assertThatThrownBy(down::check).isInstanceOf(PdfStorageException.class);
    }
}
