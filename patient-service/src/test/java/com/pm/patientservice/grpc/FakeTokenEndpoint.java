package com.pm.patientservice.grpc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * PHASE 3 (step 3.3): NEW - plays Auth0's token endpoint (POST /oauth/token) in tests.
 *
 * Answers every request with a fixed access token and records the form parameters, so a
 * test can check WHAT patient-service asked for (grant_type, client_id, audience) and HOW
 * OFTEN (token caching). Built on the JDK's own HTTP server - no extra library.
 */
public class FakeTokenEndpoint {

    public static final String TOKEN = "test-service-token";

    private final HttpServer server;
    private final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();

    public FakeTokenEndpoint() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
    }

    public FakeTokenEndpoint start() {
        server.start();
        return this;
    }

    public String tokenUri() {
        return "http://localhost:" + server.getAddress().getPort() + "/oauth/token";
    }

    /** Form parameters of every token request received so far. */
    public List<Map<String, String>> requests() {
        return requests;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        kv -> URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv -> kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "")));

        // the standard OAuth2 token response (RFC 6749, section 5.1)
        byte[] answer = """
                {"access_token":"%s","token_type":"Bearer","expires_in":3600}""".formatted(TOKEN)
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, answer.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(answer);
        }
    }
}
