package com.pm.apigateway.Exception;

// =============================================================================
// PHASE 0 (step 0.8): DISABLED - kept only as a commented-out record.
//
// This global handler only covered ONE case: auth-service answering 401
// (WebClientResponseException.Unauthorized). Every other failure - auth-service
// down, slow, or answering 5xx - fell through to Spring's default handler and
// became an HTTP 500 (after a 30-second wait, measured before this step).
//
// Error handling now lives directly in JwtValidationGatewayFilterFactory, next to
// the call that can fail, and covers all cases (401 vs 503 + timeout). Keeping a
// second, partial handler here would only make it unclear which one answers.
// =============================================================================

// PHASE 0 (old):
//import org.springframework.http.HttpStatus;
//import org.springframework.web.bind.annotation.ExceptionHandler;
//import org.springframework.web.bind.annotation.RestControllerAdvice;
//import org.springframework.web.reactive.function.client.WebClientResponseException;
//import org.springframework.web.server.ServerWebExchange;
//import reactor.core.publisher.Mono;
//
//@RestControllerAdvice
//public class JwtValidationException {
//
//    @ExceptionHandler(WebClientResponseException.Unauthorized.class)
//    public Mono<Void> handleUnauthorizedException(ServerWebExchange exchange) {
//        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
//        return exchange.getResponse().setComplete();
//    }
//}
