package com.pm.billingservice.grpc;

import billing.BillingRequest;
import billing.BillingResponse;
import billing.BillingServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE 1 (step 1.7): NEW - tests the billing gRPC API the way patient-service uses it.
 *
 * IN-PROCESS gRPC
 * ---------------
 * gRPC can run over an in-memory "transport" instead of TCP: client and server live in
 * the same JVM and exchange messages without a network or a port. Everything else is
 * real: the generated stub, protobuf serialization, the service registered by Spring
 * (@GrpcService on BillingGRPC, picked up by the net.devh server starter).
 *
 *   grpc.server.in-process-name=billing-test -> net.devh starts an in-process server
 *   grpc.server.port=-1                      -> and NO network server on 9001
 *   InProcessChannelBuilder.forName(...)     -> the test's client channel to it
 *
 * Same properties as BillingServiceApplicationTests -> both share one Spring context.
 *
 * PHASE 3 (step 3.3): every call now needs a token with create:billing-accounts
 * (BearerTokenInterceptor). The real interceptor runs; only the JwtDecoder is a test double
 * ({@link TestDecoder}) that looks tokens up in a table instead of checking a signature:
 *   "patient-service" -> [create:billing-accounts]   (what Auth0 gives patient-service)
 *   "patient-reader"  -> [read:patients]             (valid token, wrong permission)
 *   anything else     -> invalid
 * The existing tests send the "patient-service" token (the stub below), so they still test
 * billing's behaviour. The new tests at the end cover the door check itself.
 */
@SpringBootTest(properties = {
        "grpc.server.in-process-name=billing-test",
        "grpc.server.port=-1"
})
@Import(BillingGrpcServiceTest.TestDecoder.class)
class BillingGrpcServiceTest {

    private ManagedChannel channel;
    private BillingServiceGrpc.BillingServiceBlockingStub stub;

    @BeforeEach
    void connect() {
        channel = InProcessChannelBuilder.forName("billing-test").directExecutor().build();
        // PHASE 3 (old): stub = BillingServiceGrpc.newBlockingStub(channel);
        stub = withToken(BillingServiceGrpc.newBlockingStub(channel), "patient-service");
    }

    @AfterEach
    void disconnect() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void createBillingAccountReturnsActiveAccount() {
        BillingRequest request = BillingRequest.newBuilder()
                .setPatientId(UUID.randomUUID().toString())
                .setName("Anna Test")
                .setEmail("anna@example.com")
                .build();

        // same call (incl. a deadline) as BillingServiceGrpcClient in patient-service
        BillingResponse response = stub.withDeadlineAfter(3, TimeUnit.SECONDS).createBillingAccount(request);

        // NOTE: billing is still a stub service - it always returns account "12345".
        // Real account creation (and a test for unique ids) comes when billing gets a DB.
        assertThat(response.getAccountId()).isNotBlank();
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void emptyRequestIsStillAnswered() {
        // proto3 has no "required" fields: an empty message is valid on the wire.
        // Documents current behaviour - billing does no validation yet.
        BillingResponse response = stub.createBillingAccount(BillingRequest.getDefaultInstance());

        assertThat(response.getStatus()).isEqualTo("ACTIVE");
    }

    // ---- PHASE 3 (step 3.3): NEW - the token check in front of billing ----

    @Test
    void callWithoutTokenIsUnauthenticated() {
        var anonymous = BillingServiceGrpc.newBlockingStub(channel);

        assertThatThrownBy(() -> anonymous.createBillingAccount(BillingRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void invalidTokenIsUnauthenticated() {
        var forged = withToken(BillingServiceGrpc.newBlockingStub(channel), "forged");

        assertThatThrownBy(() -> forged.createBillingAccount(BillingRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void validTokenWithoutBillingPermissionIsDenied() {
        // e.g. a user's token for the patient API: real and valid, but not for billing
        var reader = withToken(BillingServiceGrpc.newBlockingStub(channel), "patient-reader");

        assertThatThrownBy(() -> reader.createBillingAccount(BillingRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    /** Adds "authorization: Bearer <token>" metadata to every call of this stub. */
    private static BillingServiceGrpc.BillingServiceBlockingStub withToken(
            BillingServiceGrpc.BillingServiceBlockingStub stub, String token) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    /** Replaces the real JwtDecoder (Spring Boot backs off when one exists). */
    @TestConfiguration
    static class TestDecoder {

        private static final Map<String, List<String>> PERMISSIONS = Map.of(
                "patient-service", List.of("create:billing-accounts"),
                "patient-reader", List.of("read:patients"));

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                List<String> permissions = PERMISSIONS.get(token);
                if (permissions == null) {
                    throw new BadJwtException("unknown test token");
                }
                return Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .subject(token + "@clients")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .claim("permissions", permissions)
                        .build();
            };
        }
    }
}
