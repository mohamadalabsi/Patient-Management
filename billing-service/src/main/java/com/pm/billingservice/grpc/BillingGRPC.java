package com.pm.billingservice.grpc;


import billing.BillingRequest;
import billing.BillingResponse;
import billing.BillingServiceGrpc.BillingServiceImplBase;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@GrpcService
public class BillingGRPC extends BillingServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(BillingGRPC.class);

    @Override
    public void createBillingAccount(BillingRequest billingRequest,
                                     StreamObserver<BillingResponse> responseObserver) {
        // PHASE 0 (step 0.9): no personal data (PII) in logs (audit S7).
        // Logs are copied to many places (log files, log servers like Loki in Phase 5,
        // support tickets, screenshots) and are kept for a long time. For health data
        // (GDPR "special category") name + email of a patient must not end up there.
        // We log only technical IDs; the full record stays in the database, where
        // access is controlled.
        // PHASE 0 (old): log.info("createBillingAccount request received {}", billingRequest.toString());
        log.info("createBillingAccount request received for patient {}", billingRequest.getPatientId());


        BillingResponse response = BillingResponse.newBuilder()
                .setAccountId("12345")
                .setStatus("ACTIVE")
                .build();


        responseObserver.onNext(response);
        responseObserver.onCompleted();


        // PHASE 0 (step 0.9): explicit fields instead of toString() (see comment above).
        // PHASE 0 (old): log.info("createBillingAccount response sent {}", response.toString());
        log.info("createBillingAccount response sent for patient {}: account {} ({})",
                billingRequest.getPatientId(), response.getAccountId(), response.getStatus());
    }
}
