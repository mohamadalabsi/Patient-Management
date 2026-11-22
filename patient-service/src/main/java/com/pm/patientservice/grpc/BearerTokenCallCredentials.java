package com.pm.patientservice.grpc;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * PHASE 3 (step 3.3): NEW - puts "authorization: Bearer <token>" on every gRPC call.
 *
 * gRPC's equivalent of an HTTP header is METADATA (key/value pairs sent before the
 * message). CallCredentials is gRPC's official hook for credentials: gRPC calls
 * applyRequestMetadata() right before each call is sent.
 *
 *   BillingServiceGrpcClient.createBillingAccount()
 *        └─ gRPC: "about to send - any credentials?" -> applyRequestMetadata()
 *              └─ tokenSupplier.get()  (ServiceTokenProvider: cached Auth0 token)
 *              └─ metadata "authorization: Bearer eyJ..."
 *        └─ billing-service's BearerTokenInterceptor reads exactly this key
 *
 * If no token can be obtained (Auth0 down, wrong secret), the call fails with status
 * UNAUTHENTICATED before anything is sent - BillingServiceGrpcClient turns that into a
 * 503 like any other billing failure, and the patient insert is rolled back.
 *
 * Plaintext note: our channel has no TLS (private Docker network), so the token travels
 * unencrypted inside that network. Encryption between services (TLS/mTLS) is part of the
 * Kubernetes phase.
 */
public class BearerTokenCallCredentials extends CallCredentials {

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final Supplier<String> tokenSupplier;

    public BearerTokenCallCredentials(Supplier<String> tokenSupplier) {
        this.tokenSupplier = tokenSupplier;
    }

    @Override
    public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
        // On gRPC's application executor: fetching a NEW token is an HTTP call to Auth0
        // and must not block gRPC's network threads.
        appExecutor.execute(() -> {
            try {
                Metadata headers = new Metadata();
                headers.put(AUTHORIZATION, "Bearer " + tokenSupplier.get());
                applier.apply(headers);
            } catch (RuntimeException e) {
                applier.fail(Status.UNAUTHENTICATED
                        .withDescription("Could not obtain a service token for billing")
                        .withCause(e));
            }
        });
    }
}
