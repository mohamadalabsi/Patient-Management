package com.pm.analyticsservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// PHASE 1 (step 1.2): fixed audit B7 - the Kafka listener needs a broker; the old test
// had none (it tried localhost:9092). Now a Testcontainers Kafka is started first.
//
// PHASE 1 (old):
// @SpringBootTest
// class AnalyticsServiceApplicationTests {
//
//     @Test
//     void contextLoads() {
//     }
//
// }

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AnalyticsServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
