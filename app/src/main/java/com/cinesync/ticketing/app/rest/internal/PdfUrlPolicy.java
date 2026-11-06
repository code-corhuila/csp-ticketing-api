package com.cinesync.ticketing.app.rest.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Where the reference of a stored ticket PDF may point (threat T of csp-docs#292): only to the object storage of the
 * platform. The worker reports {@code s3://<bucket>/<key>}; an {@code http(s)} URL is accepted only on the host and port
 * of {@code ticketing.object-storage.url}. A reference with credentials in it, with a relative key or with another host is
 * refused, so a caller of the internal route cannot make a ticket point to a phishing address.
 */
@Component
public class PdfUrlPolicy {

    private final String bucket;
    private final String host;
    private final int port;

    public PdfUrlPolicy(@Value("${ticketing.object-storage.url}") String storageUrl,
            @Value("${ticketing.object-storage.bucket}") String bucket) {
        URI storage = parse(storageUrl);
        if (storage == null || storage.getHost() == null || bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("ticketing.object-storage.url and ticketing.object-storage.bucket are required");
        }
        this.bucket = bucket;
        this.host = storage.getHost().toLowerCase(Locale.ROOT);
        this.port = effectivePort(storage);
    }

    public boolean accepts(String reference) {
        URI uri = parse(reference);
        if (uri == null || uri.getScheme() == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawFragment() != null || !hasKey(uri.getPath())) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if ("s3".equals(scheme)) {
            return uri.getPort() == -1 && bucket.equals(uri.getHost());
        }
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return host.equals(uri.getHost().toLowerCase(Locale.ROOT)) && port == effectivePort(uri);
        }
        return false;
    }

    private static boolean hasKey(String path) {
        return path != null && path.length() > 1 && !path.contains("..");
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static URI parse(String value) {
        try {
            return value == null ? null : new URI(value.trim());
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
