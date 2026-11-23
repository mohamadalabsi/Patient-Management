package com.pm.patientservice.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * PHASE 3 (step 3.2): NEW - security of patient-service.
 *
 * Two layers, and how they connect (one request, in order):
 *
 *   1. URL level (this filter chain, runs BEFORE the controller):
 *      oauth2ResourceServer().jwt() verifies the bearer token (issuer, audience, expiry,
 *      signature - settings in application.yml). Its "permissions" claim becomes the
 *      caller's authorities (authorities-claim-name in application.yml).
 *        no token / bad token        -> 401, the controller is never called
 *        /patients/**                -> needs a valid token (WHICH permission: layer 2)
 *        health, API docs            -> public
 *        anything else               -> denied
 *   2. Method level (@EnableMethodSecurity + @PreAuthorize on PatientController):
 *      right before a controller method runs, Spring checks its rule, e.g.
 *      hasAuthority('delete:patients').
 *        permission missing          -> 403, the method body never runs
 *
 * Why the permission rules live on the controller and not here: the rule sits right next
 * to the endpoint it protects, so a new endpoint without a rule stands out in review.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless API: each request carries its own token. No session, no
                // cookie -> CSRF (an attack that abuses browser cookies) does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(paths -> paths
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // OpenAPI docs: the gateway serves them at /api-docs/patients
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/patients", "/patients/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden()));
        return http.build();
    }

    /** 401: no token, or one we can't trust. Same answer and format as the gateway. */
    private AuthenticationEntryPoint unauthorized() {
        return (request, response, ex) -> {
            boolean badToken = ex instanceof OAuth2AuthenticationException;
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, badToken ? "Bearer error=\"invalid_token\"" : "Bearer");
            writeProblem(request, response, HttpStatus.UNAUTHORIZED,
                    badToken ? "Invalid or expired token" : "Missing bearer token");
        };
    }

    /**
     * 403: the token is fine, but it lacks the permission this endpoint needs. Thrown by
     * @PreAuthorize as AccessDeniedException; GlobalExceptionHandler has no handler for
     * it, so it reaches Spring Security, which calls this.
     */
    private AccessDeniedHandler forbidden() {
        return (request, response, ex) -> writeProblem(request, response, HttpStatus.FORBIDDEN,
                "Missing permission for this operation");
    }

    /** RFC 9457 Problem Details - the same JSON shape as GlobalExceptionHandler's errors. */
    private static void writeProblem(HttpServletRequest request, HttpServletResponse response,
                                     HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s","instance":"%s"}"""
                .formatted(status.getReasonPhrase(), status.value(), detail, request.getRequestURI()));
    }
}
