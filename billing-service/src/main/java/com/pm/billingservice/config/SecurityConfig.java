package com.pm.billingservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * PHASE 3 (step 3.3): NEW - security for billing's HTTP port (4002).
 *
 * Billing's real API is gRPC (port 9001), protected by grpc/BearerTokenInterceptor. The
 * HTTP port only serves Actuator. Adding Spring Security to the project (pom.xml) would,
 * by default, lock EVERY HTTP path behind a generated password - including the health
 * check Docker uses, so the container would never become "healthy". This chain says
 * explicitly: health/info public, everything else denied.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(paths -> paths
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
