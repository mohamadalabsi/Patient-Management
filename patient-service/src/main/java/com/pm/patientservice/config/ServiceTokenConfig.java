package com.pm.patientservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * PHASE 3 (step 3.3): NEW - how patient-service gets (and keeps) its own token.
 *
 * The pieces, and how they connect:
 *
 *   application.yml "client.registration.billing"   WHO we are (client id/secret) and WHERE
 *            │                                       to ask (token-uri)
 *            ▼
 *   ClientRegistrationRepository  (Spring Boot builds it from the yml)
 *            │
 *            ▼
 *   OAuth2AuthorizedClientManager (this bean)        "give me a valid token for 'billing'"
 *     - has a token that is still valid?  -> return it (cached in memory)
 *     - none / about to expire?          -> POST to token-uri:
 *          grant_type=client_credentials, client_id, client_secret, audience
 *        and remember the answer until it expires
 *            │
 *            ▼
 *   ServiceTokenProvider.token()  ->  BillingServiceGrpcClient puts it on every gRPC call
 *
 * "AuthorizedClientService..." variant: works OUTSIDE a web request too (scheduled jobs,
 * Kafka listeners - later phases), because a service token belongs to the SERVICE, not to
 * the HTTP request or user currently being handled.
 */
@Configuration
public class ServiceTokenConfig {

    @Bean
    OAuth2AuthorizedClientManager serviceTokenManager(ClientRegistrationRepository registrations,
                                                      OAuth2AuthorizedClientService authorizedClients,
                                                      @Value("${AUTH0_AUDIENCE}") String audience) {
        // Auth0 needs to know FOR WHICH API the token is ("audience"). The OAuth2 standard
        // has no such parameter for client credentials, so Spring doesn't send it by
        // default - we add it to the form body. Same value our services check as "aud".
        RestClientClientCredentialsTokenResponseClient tokenClient = new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setParametersCustomizer(parameters -> parameters.add("audience", audience));

        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(clientCredentials -> clientCredentials.accessTokenResponseClient(tokenClient))
                .build());
        return manager;
    }
}
