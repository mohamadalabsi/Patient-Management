package com.pm.apigateway.config;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * PHASE 3 (step 3.1): NEW - writes an RFC 9457 Problem Details body for 401/403.
 *
 * Same JSON shape as the old JwtValidation filter's reject() (Phase 0, step 0.8) and as
 * patient-service's errors, so clients see ONE error format no matter who rejects them.
 *
 * Fixes the Phase 1 finding: "instance" is now the path the CLIENT called
 * (/api/patients). Spring Security runs before the route's StripPrefix, so it still sees
 * the original path - the old route filter saw the rewritten one (/patients).
 */
final class ProblemDetailsWriter {

    private ProblemDetailsWriter() {
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String detail) {
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
