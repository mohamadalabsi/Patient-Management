package com.pm.patientservice.grpc;


import billing.BillingRequest;
import billing.BillingResponse;
import billing.BillingServiceGrpc;
import com.pm.patientservice.exception.BillingServiceUnavailableException;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class BillingServiceGrpcClient {

    // PHASE 0 (step 0.7): the channel is now a FIELD (it was a local variable in the
    // constructor), so we can close it when the application shuts down.
    private final ManagedChannel channel;
    private final BillingServiceGrpc.BillingServiceBlockingStub blockingStub;
    private final Duration deadline;

    // PHASE 0 (old):
    // public BillingServiceGrpcClient(@Value("${billing.service.address:localhost}") String serverAddress, @Value("${billing.service.port:9001}") int serverPort) {
    //     log.info("Connecting to Billing Service at {}:{}", serverAddress, serverPort);
    //     ManagedChannel channel =
    //             ManagedChannelBuilder.forAddress(serverAddress, serverPort).usePlaintext().build();
    //     blockingStub = BillingServiceGrpc.newBlockingStub(channel);
    // }
    public BillingServiceGrpcClient(@Value("${billing.service.address:localhost}") String serverAddress,
                                    @Value("${billing.service.port:9001}") int serverPort,
                                    // PHASE 0 (step 0.7): NEW - max time we wait for billing.
                                    // Spring converts "3s", "500ms" ... into a Duration.
                                    @Value("${billing.service.deadline:3s}") Duration deadline,
                                    // PHASE 3 (step 3.3): NEW - our own service token (Auth0)
                                    ServiceTokenProvider serviceTokens) {
        log.info("Connecting to Billing Service at {}:{} (deadline {})", serverAddress, serverPort, deadline);
        // A ManagedChannel is expensive (TCP/HTTP2 connection, thread pools) and is
        // meant to be created ONCE and reused for all calls - like a DB connection pool.
        // usePlaintext() = no TLS; acceptable only inside our private Docker network
        // (TLS / service-to-service auth comes in Phase 3).
        this.channel = ManagedChannelBuilder.forAddress(serverAddress, serverPort).usePlaintext().build();
        // PHASE 3 (step 3.3): every call made through this stub now carries
        // "authorization: Bearer <patient-service token>". billing-service checks it and
        // requires the permission create:billing-accounts.
        // serviceTokens::token is called per call, but returns the CACHED token until it
        // is about to expire - Auth0 is asked about once per token lifetime.
        // PHASE 3 (old): this.blockingStub = BillingServiceGrpc.newBlockingStub(channel);
        this.blockingStub = BillingServiceGrpc.newBlockingStub(channel)
                .withCallCredentials(new BearerTokenCallCredentials(serviceTokens::token));
        this.deadline = deadline;
    }

    public BillingResponse createBillingAccount(String patientId, String name, String email) {
        BillingRequest request =
                BillingRequest.newBuilder().setPatientId(patientId).setName(name).setEmail(email).build();

        // PHASE 0 (step 0.7): fix B3 - every remote call needs a DEADLINE.
        // Without one, a billing-service that hangs (GC pause, deadlock, network
        // problem) blocks this HTTP request thread FOREVER. Enough hanging requests
        // and patient-service runs out of threads -> it hangs too -> the gateway
        // hangs: one slow service takes down the whole chain ("cascading failure").
        // withDeadlineAfter(...) makes gRPC cancel the call after the deadline and
        // throw StatusRuntimeException with status DEADLINE_EXCEEDED.
        // The deadline is set PER CALL (a stub with a deadline is a new, cheap
        // object), because a deadline is an absolute point in time.
        //
        // We also translate gRPC errors into our own exception, so the HTTP layer
        // can answer 503 instead of a generic 500 (see GlobalExceptionHandler).
        // PHASE 0 (old):
        // BillingResponse response = blockingStub.createBillingAccount(request);
        // log.info("Received Response from billing service via GRPC: {}", response);
        // return response;
        try {
            BillingResponse response = blockingStub
                    .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .createBillingAccount(request);
            // PHASE 0 (step 0.9): log IDs and status only - the request contains
            // name + email (personal data), so it must not be logged.
            log.info("Billing account {} created for patient {} (status {})",
                    response.getAccountId(), patientId, response.getStatus());
            return response;
        } catch (StatusRuntimeException e) {
            // e.getStatus().getCode(): UNAVAILABLE (billing down / unreachable),
            // DEADLINE_EXCEEDED (too slow), INTERNAL (bug in billing) ...
            // PHASE 3 (step 3.3): also UNAUTHENTICATED (no/invalid service token) and
            // PERMISSION_DENIED (token lacks create:billing-accounts). For the HTTP client
            // it is the same situation: billing can't be used right now -> 503 + rollback.
            // The log line shows the exact code, so operators can tell "auth" from "down".
            log.error("Billing call failed for patient {}: {}", patientId, e.getStatus().getCode());
            throw new BillingServiceUnavailableException(
                    "Billing service call failed: " + e.getStatus().getCode(), e);
        }
    }

    /**
     * PHASE 0 (step 0.7): NEW - graceful shutdown.
     * @PreDestroy runs when Spring closes the application context (e.g. "docker stop").
     * shutdown() stops accepting new calls but lets running calls finish;
     * awaitTermination waits a little; shutdownNow() cancels whatever is left.
     * Before, the channel was never closed: open connections and threads leaked
     * until the JVM was killed.
     */
    @PreDestroy
    public void shutdown() throws InterruptedException {
        log.info("Shutting down billing gRPC channel");
        channel.shutdown();
        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            channel.shutdownNow();
        }
    }
}
