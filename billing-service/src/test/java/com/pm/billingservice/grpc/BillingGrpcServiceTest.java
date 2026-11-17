package com.pm.billingservice.grpc;

import billing.BillingRequest;
import billing.BillingResponse;
import billing.BillingServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
 */
@SpringBootTest(properties = {
        "grpc.server.in-process-name=billing-test",
        "grpc.server.port=-1"
})
class BillingGrpcServiceTest {

    private ManagedChannel channel;
    private BillingServiceGrpc.BillingServiceBlockingStub stub;

    @BeforeEach
    void connect() {
        channel = InProcessChannelBuilder.forName("billing-test").directExecutor().build();
        stub = BillingServiceGrpc.newBlockingStub(channel);
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
}
