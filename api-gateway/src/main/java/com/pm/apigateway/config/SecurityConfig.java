package com.pm.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * PHASE 3 (step 3.1): NEW - who may call which path of the gateway.
 *
 * How the pieces connect (one request, in order):
 *
 *   1. A request arrives, e.g. GET /api/patients with "Authorization: Bearer eyJ...".
 *   2. Spring Security runs as a WebFilter BEFORE the gateway routes (application.yml).
 *   3. oauth2ResourceServer().jwt() takes the token out of the header and verifies it with
 *      the issuer's public keys + issuer-uri/audiences from application.yml.
 *        - no token / invalid token  -> {@link #unauthorized()}   -> 401, request stops here
 *        - valid token               -> the request is "authenticated"
 *   4. authorizeExchange() decides if THIS path is allowed for this caller:
 *        - /api/**                   -> needs a valid token (any permission - the
 *                                       permissions are checked by patient-service, step 3.2)
 *        - health / api-docs         -> public
 *        - anything else             -> denied (a path we did not plan for is never open)
 *   5. Only then does the gateway route run (StripPrefix, forward to patient-service),
 *      and the Authorization header travels along unchanged.
 *
 * Replaces the per-request call to auth-service /validate (JwtValidationGatewayFilterFactory).
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                // Stateless: every request brings its own token. No session, no cookie,
                // so nothing to protect with CSRF tokens and no "login page" redirects.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                // CORS: uses the corsConfigurationSource() bean below. It must run inside
                // the security chain, because a browser's CORS "preflight" (OPTIONS) request
                // never carries a token and would otherwise be rejected with 401.
                .cors(Customizer.withDefaults())
                .authorizeExchange(paths -> paths
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/api-docs/**").permitAll()
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().denyAll())
                // Step 3 above: "this app accepts JWT bearer tokens". Everything else
                // (keys, issuer, audience, expiry) comes from application.yml.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden()))
                // Same answers for requests that bring no token at all.
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden()))
                .build();
    }

    /**
     * 401 = "we don't know who you are": no token, or a token we can't trust (bad signature,
     * wrong issuer/audience, expired). The WWW-Authenticate header tells the client which
     * kind of credentials we expect (RFC 6750); the body is Problem Details like everywhere
     * else. We never echo the exact reason (e.g. "signature invalid") to the client.
     */
    private ServerAuthenticationEntryPoint unauthorized() {
        return (exchange, ex) -> {
            boolean badToken = ex instanceof OAuth2AuthenticationException;
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                    badToken ? "Bearer error=\"invalid_token\"" : "Bearer");
            return ProblemDetailsWriter.write(exchange, HttpStatus.UNAUTHORIZED,
                    badToken ? "Invalid or expired token" : "Missing bearer token");
        };
    }

    /** 403 = "we know who you are, but you may not do this" (here: paths that are always denied). */
    private ServerAccessDeniedHandler forbidden() {
        return (exchange, ex) -> ProblemDetailsWriter.write(exchange, HttpStatus.FORBIDDEN, "Access denied");
    }

    /**
     * CORS: which OTHER websites (origins) may call this API from a browser.
     * Browsers block a page on http://localhost:3000 from reading answers of
     * http://localhost:4004 unless the API sends "Access-Control-Allow-Origin". Only the
     * gateway does this - the services behind it are never called from a browser directly.
     * Non-browser clients (curl, other services) ignore CORS completely.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        cors.setMaxAge(Duration.ofHours(1)); // browser may cache the preflight answer for 1h
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
