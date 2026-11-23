package com.pm.patientservice.grpc;

import billing.BillingRequest;
import billing.BillingResponse;
import billing.BillingServiceGrpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * PHASE 1 (step 1.6): NEW - a FAKE billing-service for tests.
 *
 * A fake is a small, real implementation of a dependency that we fully control (unlike a
 * Mockito mock, it speaks the real protocol). This one is a real gRPC server on a random
 * free port (port 0 = "OS, pick one"), built from the same billing_service.proto contract.
 * So BillingServiceGrpcClient is tested for real: channel, protobuf encoding, deadline,
 * error status translation - only billing's business logic is fake.
 *
 * Each test sets the behaviour it needs with {@link #behave(Mode)}:
 *   OK          -> answers like the real billing-service
 *   UNAVAILABLE -> answers with gRPC status UNAVAILABLE (service down / overloaded)
 *   SLOW        -> answers only after 'slowDelayMillis' (longer than the client deadline)
 */
public class FakeBillingServer {

    public enum Mode { OK, UNAVAILABLE, SLOW }

    private final Server server;
    private final List<BillingRequest> requests = new CopyOnWriteArrayList<>();
    // PHASE 3 (step 3.3): NEW - the "authorization" metadata of every call (null = none sent)
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private final long slowDelayMillis;
    private volatile Mode mode = Mode.OK;

    public FakeBillingServer(long slowDelayMillis) {
        this.slowDelayMillis = slowDelayMillis;
        // PHASE 3 (step 3.3): the service is wrapped with a tiny interceptor that only
        // RECORDS the authorization header (it checks nothing - the real check is billing's
        // job and is tested in billing-service). Tests assert that the token was sent.
        // PHASE 3 (old): this.server = ServerBuilder.forPort(0).addService(new BillingServiceGrpc.BillingServiceImplBase() {
        ServerInterceptor recordAuthorization = new ServerInterceptor() {
            @Override
            public <Q, A> ServerCall.Listener<Q> interceptCall(ServerCall<Q, A> call, Metadata headers,
                                                               ServerCallHandler<Q, A> next) {
                String authorization = headers.get(AUTHORIZATION);
                authorizations.add(authorization == null ? "<none>" : authorization);
                return next.startCall(call, headers);
            }
        };
        this.server = ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(new BillingServiceGrpc.BillingServiceImplBase() {
            @Override
            public void createBillingAccount(BillingRequest request, StreamObserver<BillingResponse> response) {
                requests.add(request);
                switch (mode) {
                    case UNAVAILABLE -> response.onError(
                            Status.UNAVAILABLE.withDescription("fake billing is down").asRuntimeException());
                    case SLOW -> {
                        sleep(FakeBillingServer.this.slowDelayMillis);
                        reply(response);
                    }
                    case OK -> reply(response);
                }
            }
        }, recordAuthorization)).build();
    }

    public FakeBillingServer start() {
        try {
            server.start();
            return this;
        } catch (IOException e) {
            throw new IllegalStateException("could not start fake billing server", e);
        }
    }

    public int port() {
        return server.getPort();
    }

    public void behave(Mode mode) {
        this.mode = mode;
    }

    public List<BillingRequest> requests() {
        return requests;
    }

    /** PHASE 3 (step 3.3): NEW - authorization header per received call ("<none>" if missing). */
    public List<String> authorizations() {
        return authorizations;
    }

    private static void reply(StreamObserver<BillingResponse> response) {
        response.onNext(BillingResponse.newBuilder().setAccountId("fake-account").setStatus("ACTIVE").build());
        response.onCompleted();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
