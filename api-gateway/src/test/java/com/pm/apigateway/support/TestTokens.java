package com.pm.apigateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * PHASE 3 (step 3.1): NEW - a tiny "token issuer" for tests (plays Auth0).
 *
 * It does exactly what Auth0 does, just in memory:
 *   - owns an RSA key pair (private key signs, public key verifies)
 *   - publishes the PUBLIC key as a JWKS document ({@link #jwks()}), which a stub HTTP
 *     server serves to the gateway like Auth0's /.well-known/jwks.json
 *   - signs tokens with whatever claims a test wants ({@link #token})
 * So the gateway runs its REAL verification (signature, iss, aud, exp) against tokens we
 * control - including deliberately broken ones. No internet, no real Auth0.
 * Uses Nimbus JOSE, the same library Spring Security uses internally to verify tokens.
 */
public final class TestTokens {

    public static final String ISSUER = "https://issuer.test/";
    public static final String AUDIENCE = "https://api.patient-management.local";

    private final RSAKey key;

    public TestTokens() {
        this.key = generateKey("test-key");
    }

    /** The public half of our key, as the JSON an issuer serves at its JWKS URL. */
    public String jwks() {
        return new JWKSet(key.toPublicJWK()).toString();
    }

    /** A token that passes every check: our key, our issuer, our audience, valid for 5 min. */
    public String valid(List<String> permissions) {
        return token(ISSUER, AUDIENCE, Instant.now().plusSeconds(300), permissions);
    }

    public String token(String issuer, String audience, Instant expiresAt, List<String> permissions) {
        return sign(key, issuer, audience, expiresAt, permissions);
    }

    /** Correct claims, but signed by a key the gateway has never seen -> forged. */
    public String signedByStranger() {
        return sign(generateKey("stranger"), ISSUER, AUDIENCE, Instant.now().plusSeconds(300), List.of());
    }

    private static String sign(RSAKey signingKey, String issuer, String audience, Instant expiresAt,
                               List<String> permissions) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject("test-client")
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(expiresAt))
                .claim("permissions", permissions)
                .build();
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
