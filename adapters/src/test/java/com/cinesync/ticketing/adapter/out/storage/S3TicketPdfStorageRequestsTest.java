package com.cinesync.ticketing.adapter.out.storage;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the reader sends to the storage. The read-only account of ADR-058 may only get {@code tickets/*}: a request for anything
 * else, such as the location of the bucket that a client asks for when it does not know its region, is refused with
 * {@code AccessDenied} and the whole read fails. The stand-in answers like an empty bucket and records the requests.
 */
class S3TicketPdfStorageRequestsTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startTheStorage() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            exchange.getRequestBody().readAllBytes();
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.sendResponseHeaders(404, -1);
            } else if (exchange.getRequestURI().getQuery() == null) {
                byte[] body = "<Error><Code>NoSuchKey</Code><Message>missing</Message></Error>".getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/xml");
                exchange.sendResponseHeaders(404, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(403, -1);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopTheStorage() {
        server.stop(0);
    }

    private S3TicketPdfStorage storage() {
        return new S3TicketPdfStorage("http://127.0.0.1:" + server.getAddress().getPort(), "ticketing_pdf_reader", "secret",
                "tickets", "us-east-1");
    }

    @Test
    void aReadAsksOnlyForTheKeyItReadsNeverForTheLocationOfTheBucket() {
        assertThat(storage().read("tickets/r/t.pdf")).isEmpty();

        assertThat(requests).containsExactly("GET /tickets/tickets/r/t.pdf");
    }

    @Test
    void theBucketCheckAsksOnlyForItsProbeKey() {
        storage().check();

        assertThat(requests).containsExactly("GET /tickets/tickets/.readiness");
    }
}
