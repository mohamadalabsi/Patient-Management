package com.pm.billingservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// PHASE 1 (step 1.2): billing needs no database or broker, but the old test started the
// real gRPC server on the fixed port 9001 - that fails if the compose stack (or another
// test run) already uses the port.
// Now the gRPC server runs IN-PROCESS (in memory, name "billing-test") and the network
// server is switched off (port -1). The same properties are used by BillingGrpcServiceTest
// (step 1.7), so both test classes share one Spring context.
//
// PHASE 1 (old):
// @SpringBootTest
// class BillingServiceApplicationTests {
//
//     @Test
//     void contextLoads() {
//     }
//
// }

@SpringBootTest(properties = {
        "grpc.server.in-process-name=billing-test",
        "grpc.server.port=-1"
})
class BillingServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
