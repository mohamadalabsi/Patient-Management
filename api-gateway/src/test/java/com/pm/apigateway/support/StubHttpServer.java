package com.pm.apigateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * PHASE 1 (step 1.10): NEW - a tiny fake HTTP service for the gateway tests.
 *
 * Built on the JDK's own HTTP server (com.sun.net.httpserver, part of every JDK), so no
 * extra library is needed. It listens on a random free port (port 0) and answers EVERY
 * request with the status / delay / body the test configured. It records each request
 * so tests can check what the gateway forwarded.
 *
 * Used twice in GatewayJwtFilterTest: once as auth-service (/validate), once as
 * patient-service (the route target behind the filter).
 */
public class StubHttpServer {

    /** What the stub received - path and Authorization header are what the tests check. */
    public record Received(String path, String authorization) { }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile long delayMillis = 0;
    private volatile String body = "";

    public StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool()); // a slow answer must not block others
        server.createContext("/", this::handle);
    }

    public StubHttpServer start() {
        server.start();
        return this;
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Configure the next answers. */
    public void respond(int status, long delayMillis, String body) {
        this.status = status;
        this.delayMillis = delayMillis;
        this.body = body;
    }

    public List<Received> received() {
        return received;
    }

    public void reset() {
        received.clear();
        respond(200, 0, "");
    }

    private void handle(HttpExchange exchange) throws IOException {
        received.add(new Received(exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization")));
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
