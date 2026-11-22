package com.pm.apigateway.config;

import com.pm.apigateway.support.StubHttpServer;
import com.pm.apigateway.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 3 (step 3.1): NEW - the gateway checks tokens itself (replaces GatewayJwtFilterTest).
 *
 * Setup - three players, all inside this JVM:
 *   TOKENS  -> plays Auth0: signs tokens with a private test key
 *   JWKS    -> a stub HTTP server that serves TOKENS' public key, like Auth0's jwks.json
 *   PATIENT -> plays patient-service: tells us whether a request got through
 * The real gateway (real SecurityConfig, real routes) runs on a random port.
 *
 * jwk-set-uri: tells Spring to fetch the keys straight from JWKS instead of discovering
 * them via issuer-uri/.well-known (there is no real issuer here). issuer-uri and audiences
 * are still checked against every token - that is what most of these tests prove.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewaySecurityTest {

    private static final TestTokens TOKENS = new TestTokens();
    private static final StubHttpServer JWKS = new StubHttpServer().start();
    private static final StubHttpServer PATIENT = new StubHttpServer().start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("AUTH0_ISSUER_URI", () -> TestTokens.ISSUER);
        registry.add("AUTH0_AUDIENCE", () -> TestTokens.AUDIENCE);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWKS::url);
        registry.add("PATIENT_SERVICE_URL", PATIENT::url);
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://frontend.test");
    }

    @Autowired
    private WebTestClient client;

    @BeforeEach
    void reset() {
        JWKS.reset();
        JWKS.respond(200, 0, TOKENS.jwks());
        PATIENT.reset();
        PATIENT.respond(200, 0, "{\"content\":[]}");
    }

    @Test
    void validTokenIsForwardedWithItsHeaderAndPrefixStripped() {
        String token = TOKENS.valid(List.of("read:patients"));

        client.get().uri("/api/patients").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"content\":[]}");

        // patient-service gets the SAME token -> it can check permissions itself (step 3.2)
        assertThat(PATIENT.received()).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("/patients");
            assertThat(r.authorization()).isEqualTo("Bearer " + token);
        });
    }

    @Test
    void missingTokenIs401ProblemDetailsWithTheClientsPath() {
        client.get().uri("/api/patients")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.detail").isEqualTo("Missing bearer token")
                // Phase 1 finding fixed: the path the client called, not the stripped one
                .jsonPath("$.instance").isEqualTo("/api/patients");

        assertThat(PATIENT.received()).isEmpty();
    }

    @Test
    void tokenForAnotherApiIs401() {
        // valid signature and issuer, but "aud" is a different API of the same tenant
        assertRejected(TOKENS.token(TestTokens.ISSUER, "https://some-other-api",
                Instant.now().plusSeconds(300), List.of("read:patients")));
    }

    @Test
    void tokenFromAnotherIssuerIs401() {
        assertRejected(TOKENS.token("https://evil.example/", TestTokens.AUDIENCE,
                Instant.now().plusSeconds(300), List.of("read:patients")));
    }

    @Test
    void expiredTokenIs401() {
        // 5 minutes in the past: beyond Spring's default 60 s clock-skew tolerance
        assertRejected(TOKENS.token(TestTokens.ISSUER, TestTokens.AUDIENCE,
                Instant.now().minusSeconds(300), List.of("read:patients")));
    }

    @Test
    void tokenSignedByAnUnknownKeyIs401() {
        assertRejected(TOKENS.signedByStranger());
    }

    @Test
    void garbageTokenIs401() {
        assertRejected("not.a.jwt");
    }

    @Test
    void healthIsPublic() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void unplannedPathIsDeniedEvenWithAValidToken() {
        // anyExchange().denyAll(): a path nobody configured is never open by accident
        client.get().uri("/actuator/env")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.valid(List.of()))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void corsPreflightFromAllowedOriginNeedsNoToken() {
        client.options().uri("/api/patients")
                .header(HttpHeaders.ORIGIN, "http://frontend.test")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://frontend.test");
        assertThat(PATIENT.received()).isEmpty(); // answered by the gateway itself
    }

    @Test
    void corsPreflightFromUnknownOriginIsRejected() {
        client.options().uri("/api/patients")
                .header(HttpHeaders.ORIGIN, "http://evil.test")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden();
    }

    private void assertRejected(String token) {
        client.get().uri("/api/patients").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectBody().jsonPath("$.detail").isEqualTo("Invalid or expired token");
        assertThat(PATIENT.received()).isEmpty();
    }
}
