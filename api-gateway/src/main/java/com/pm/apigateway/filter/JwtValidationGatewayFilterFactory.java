package com.pm.apigateway.filter;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
// PHASE 3 (old): import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

// =============================================================================
// Gateway filter "JwtValidation" (used on the patient route in application.yml).
// For every request it asks auth-service GET /validate whether the token is valid.
// (Phase 3 replaces this with local JWT verification against Auth0's public keys.)
//
// PHASE 0 (step 0.8): error handling fixed (audit finding B8).
// Measured BEFORE this change with auth-service stopped:
//     client waited 30 seconds, then got HTTP 500.
//   - 30s: no timeout on the /validate call -> the default TCP connect timeout.
//   - 500: the connection error was not handled -> "Internal Server Error".
// Now:
//   - token rejected by auth-service (4xx)    -> 401 Unauthorized
//   - auth-service down / too slow / 5xx      -> 503 Service Unavailable, within 3s
// Both answers use the RFC 9457 Problem Details JSON format, like patient-service.
//
// PHASE 3 (step 3.1): RETIRED. Tokens now come from Auth0 and the gateway checks them
// itself (config/SecurityConfig.java + the resource-server settings in application.yml),
// without calling another service. This class is kept for reference only: without
// @Component Spring no longer creates it, and no route uses "JwtValidation" any more.
// =============================================================================
// PHASE 3 (old): @Component
public class JwtValidationGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> {

    private final WebClient webClient;
    // PHASE 0 (step 0.8): NEW - max time to wait for auth-service.
    private final Duration validateTimeout;

    // PHASE 0 (old):
    // public JwtValidationGatewayFilterFactory(WebClient.Builder webClientBuilder, @Value("${auth.service.url}") String authServiceUrl) {
    //     this.webClient = webClientBuilder.baseUrl(authServiceUrl).build();
    // }
    public JwtValidationGatewayFilterFactory(WebClient.Builder webClientBuilder,
                                             @Value("${auth.service.url}") String authServiceUrl,
                                             @Value("${auth.service.timeout:3s}") Duration validateTimeout) {
        this.webClient = webClientBuilder.baseUrl(authServiceUrl).build();
        this.validateTimeout = validateTimeout;
    }

    // PHASE 0 (old):
    // @Override
    // public GatewayFilter apply(Object config) {
    //     return (exchange, chain) -> {
    //         String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    //
    //         if (token == null || !token.startsWith("Bearer ")) {
    //             exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
    //             return exchange.getResponse().setComplete();
    //         }
    //
    //         return webClient.get()
    //                 .uri("/validate")
    //                 .header(HttpHeaders.AUTHORIZATION, token)
    //                 .retrieve()
    //                 .toBodilessEntity()
    //                 .then(chain.filter(exchange));
    //     };
    // }
    @Override
    public GatewayFilter apply(Object config) {
        return (exchange, chain) -> {
            String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            if (token == null || !token.startsWith("Bearer ")) {
                return reject(exchange, HttpStatus.UNAUTHORIZED, "Missing or malformed Authorization header");
            }

            // Step 1: ask auth-service and turn EVERY possible outcome into a verdict
            // (an HttpStatus). Reactive code: nothing runs until the gateway subscribes;
            // each operator below describes what happens to the result or error.
            Mono<HttpStatus> verdict = webClient.get()
                    .uri("/validate")
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .retrieve()               // 4xx/5xx answers become WebClientResponseException
                    .toBodilessEntity()
                    // PHASE 0 (step 0.8): give up after validateTimeout (TimeoutException).
                    .timeout(validateTimeout)
                    .map(response -> HttpStatus.OK)
                    // auth-service ANSWERED with an error status:
                    //   4xx = "this token is not valid"     -> 401 for the client
                    //   5xx = "auth-service itself is broken" -> 503 for the client
                    .onErrorResume(WebClientResponseException.class, ex -> Mono.just(
                            ex.getStatusCode().is4xxClientError()
                                    ? HttpStatus.UNAUTHORIZED
                                    : HttpStatus.SERVICE_UNAVAILABLE))
                    // auth-service did NOT answer at all: connection refused, DNS
                    // failure, timeout ... -> we cannot decide -> 503 (temporary).
                    // Not 401: the token may be perfectly valid; telling the client
                    // "log in again" would be wrong.
                    .onErrorResume(ex -> Mono.just(HttpStatus.SERVICE_UNAVAILABLE));

            // Step 2: only a positive verdict forwards the request to patient-service.
            // The error handlers above are deliberately placed BEFORE this step, so an
            // error coming from patient-service itself is NOT mistaken for an
            // authentication problem.
            return verdict.flatMap(status -> switch (status) {
                case OK -> chain.filter(exchange);
                case UNAUTHORIZED -> reject(exchange, status, "Invalid or expired token");
                default -> reject(exchange, status, "Authentication service is temporarily unavailable");
            });
        };
    }

    /**
     * PHASE 0 (step 0.8): NEW - writes a small RFC 9457 Problem Details body.
     * Before, rejections were an empty body with only a status code.
     */
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String body = """
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s","instance":"%s"}"""
                .formatted(status.getReasonPhrase(), status.value(), detail,
                        exchange.getRequest().getPath().value());
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
