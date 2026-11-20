package com.pm.authservice.util;

import com.pm.authservice.TestcontainersConfiguration;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE 1 (step 1.9): NEW - unit tests for JwtUtil (token creation + validation).
 *
 * A JWT is header.payload.signature (Base64URL). The signature is an HMAC-SHA256 over
 * header+payload with the secret key. Validation must reject every token whose signature
 * does not match (tampered, or signed with another key) and every expired token.
 * These are the security-critical cases - all of them are tested here.
 *
 * Plain unit test: JwtUtil is created with "new" and a throwaway test key, no Spring.
 * (Phase 3 replaces our own JWTs with Auth0 tokens; these tests then go with it.)
 */
class JwtUtilTest {

    private static final String SECRET = TestcontainersConfiguration.TEST_JWT_SECRET;

    private final JwtUtil jwtUtil = new JwtUtil(SECRET);

    @Test
    void generatedTokenIsValidAndContainsSubjectAndRole() {
        String token = jwtUtil.generateToken("anna@example.com", "ADMIN");

        assertThatCode(() -> jwtUtil.validateToken(token)).doesNotThrowAnyException();

        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET)))
                .build().parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo("anna@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(claims.getExpiration()).isAfter(new Date());
    }

    @Test
    void tamperedPayloadIsRejected() {
        String token = jwtUtil.generateToken("user@example.com", "USER");
        String[] parts = token.split("\\.");
        // An attacker changes the role in the payload but cannot recompute the signature.
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"user@example.com\",\"role\":\"ADMIN\"}".getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> jwtUtil.validateToken(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String otherSecret = Base64.getEncoder().encodeToString("another-secret-another-secret-!!".getBytes());
        String foreignToken = new JwtUtil(otherSecret).generateToken("anna@example.com", "ADMIN");

        assertThatThrownBy(() -> jwtUtil.validateToken(foreignToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        // JwtUtil always issues 10 h tokens, so we build an expired one with the same key.
        String expired = Jwts.builder()
                .subject("anna@example.com")
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000))
                .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET)))
                .compact();

        assertThatThrownBy(() -> jwtUtil.validateToken(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> jwtUtil.validateToken("not.a.jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void secretShorterThan256BitsIsRefusedAtStartup() {
        // HS256 needs >= 256-bit keys; jjwt refuses weaker ones -> the app fails fast
        // instead of running with a guessable key.
        String weak = Base64.getEncoder().encodeToString("too-short".getBytes());

        assertThatThrownBy(() -> new JwtUtil(weak)).isInstanceOf(io.jsonwebtoken.security.WeakKeyException.class);
    }
}
