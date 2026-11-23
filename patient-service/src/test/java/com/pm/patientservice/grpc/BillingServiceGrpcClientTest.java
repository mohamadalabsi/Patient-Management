package com.pm.patientservice.grpc;

import com.pm.patientservice.exception.BillingServiceUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PHASE 3 (step 3.3): NEW - the billing client's token handling, without Spring.
 *
 * Real gRPC client -> real gRPC (fake) billing server; only the token source is a Mockito
 * mock, so each test decides: "a token is available" or "Auth0 refuses / is down".
 */
class BillingServiceGrpcClientTest {

    private final FakeBillingServer billing = new FakeBillingServer(10_000).start();
    private final ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
    private BillingServiceGrpcClient client;

    @BeforeEach
    void setUp() {
        client = new BillingServiceGrpcClient("localhost", billing.port(), Duration.ofSeconds(2), tokens);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        client.shutdown();
    }

    @Test
    void everyCallCarriesTheCurrentServiceToken() {
        when(tokens.token()).thenReturn("token-1");

        client.createBillingAccount("patient-1", "Anna", "anna@example.com");

        assertThat(billing.authorizations()).containsExactly("Bearer token-1");
    }

    @Test
    void noTokenAvailableMeansBillingIsNotCalledAndTheCallerGets503() {
        // e.g. wrong client secret in .env, or Auth0 unreachable
        when(tokens.token()).thenThrow(new IllegalStateException("token endpoint said 401"));

        assertThatThrownBy(() -> client.createBillingAccount("patient-1", "Anna", "anna@example.com"))
                // -> GlobalExceptionHandler: 503, and PatientService's transaction rolls back
                .isInstanceOf(BillingServiceUnavailableException.class)
                .hasMessageContaining("UNAUTHENTICATED");
        // the request never left patient-service: no token, no call
        assertThat(billing.requests()).isEmpty();
    }
}
