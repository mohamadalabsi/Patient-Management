package com.pm.patientservice.grpc;

import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;

/**
 * PHASE 3 (step 3.3): NEW - "give me patient-service's current token for billing".
 *
 * A thin wrapper so BillingServiceGrpcClient does not need to know anything about OAuth2:
 * it only calls token(). Caching and renewing happen in the manager (ServiceTokenConfig):
 * the first call asks Auth0, later calls return the same token until shortly before it
 * expires (default: 60 s before "exp"), then a new one is fetched automatically.
 */
@Component
public class ServiceTokenProvider {

    /** The registration name in application.yml (spring.security.oauth2.client.registration.billing). */
    static final String REGISTRATION = "billing";

    private final OAuth2AuthorizedClientManager manager;

    public ServiceTokenProvider(OAuth2AuthorizedClientManager manager) {
        this.manager = manager;
    }

    /** @throws org.springframework.security.oauth2.client.ClientAuthorizationException if the issuer refuses (wrong secret) or is unreachable */
    public String token() {
        OAuth2AuthorizedClient client = manager.authorize(OAuth2AuthorizeRequest
                .withClientRegistrationId(REGISTRATION)
                // "principal" = who the token is for. With client credentials that is the
                // service itself, not a user - so a fixed name.
                .principal("patient-service")
                .build());
        if (client == null) {
            throw new IllegalStateException("No token for client registration '" + REGISTRATION + "'");
        }
        return client.getAccessToken().getTokenValue();
    }
}
