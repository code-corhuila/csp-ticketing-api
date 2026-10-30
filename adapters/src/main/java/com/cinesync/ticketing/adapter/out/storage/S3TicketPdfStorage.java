package com.cinesync.ticketing.adapter.out.storage;

import com.cinesync.ticketing.core.model.PdfStorageException;
import com.cinesync.ticketing.core.port.out.TicketPdfStorage;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Optional;

/**
 * Reads the ticket PDFs from the private bucket of the object storage, through its S3 API, with the read-only account of the API (ADR-058). A file
 * over the size limit of the ADR is refused. Nothing that could hold a credential goes into an error message.
 */
@Component
public class S3TicketPdfStorage implements TicketPdfStorage {

    /** The limit of ADR-058: the worker never writes a PDF larger than this. */
    private static final int MAX_PDF_BYTES = 1024 * 1024;

    /** A key the account may read (the policy is {@code tickets/*}) that no ticket uses, to test the bucket. */
    private static final String PROBE_KEY = "tickets/.readiness";

    private final MinioClient client;
    private final String bucket;

    public S3TicketPdfStorage(
            @Value("${ticketing.object-storage.url}") String url,
            @Value("${ticketing.object-storage.access-key}") String accessKey,
            @Value("${ticketing.object-storage.secret-key}") String secretKey,
            @Value("${ticketing.object-storage.bucket:tickets}") String bucket,
            @Value("${ticketing.object-storage.region:us-east-1}") String region) {
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("OBJECT_STORAGE_ACCESS_KEY and OBJECT_STORAGE_SECRET_KEY are required");
        }
        // The region is given so that the client never asks for the location of the bucket, which the read-only account may not do.
        this.client = MinioClient.builder().endpoint(url).credentials(accessKey, secretKey).region(region).build();
        this.bucket = bucket;
    }

    @Override
    public Optional<byte[]> read(String key) {
        try (InputStream object = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] bytes = object.readNBytes(MAX_PDF_BYTES + 1);
            if (bytes.length > MAX_PDF_BYTES) {
                throw new PdfStorageException("The stored PDF is larger than the limit of " + MAX_PDF_BYTES + " bytes");
            }
            return Optional.of(bytes);
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return Optional.empty();
            }
            throw new PdfStorageException("Object storage refused the read: " + e.errorResponse().code(), e);
        } catch (PdfStorageException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfStorageException("Object storage cannot be read: " + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void check() {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(PROBE_KEY).build());
        } catch (ErrorResponseException e) {
            // A missing probe key is the good answer: the bucket exists and the account may read it.
            if (!"NoSuchKey".equals(e.errorResponse().code())) {
                throw new PdfStorageException("Object storage bucket check failed: " + e.errorResponse().code(), e);
            }
        } catch (Exception e) {
            throw new PdfStorageException("Object storage cannot be reached: " + e.getClass().getSimpleName(), e);
        }
    }
}
