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
        log.info("createBillingAccount request received {}", billingRequest.toString());


        BillingResponse response = BillingResponse.newBuilder()
                .setAccountId("12345")
                .setStatus("ACTIVE")
                .build();


        responseObserver.onNext(response);
        responseObserver.onCompleted();


        log.info("createBillingAccount response sent {}", response.toString());
    }
}
