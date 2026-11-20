package com.pm.authservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// PHASE 1 (step 1.2): fixed audit B7.
// The old test needed a running Postgres on localhost:5432 AND the JWT_SECRET env
// variable, so it could not run anywhere. Now: Postgres from Testcontainers, and a
// throwaway test secret passed as a property.
//
// PHASE 1 (old):
// @SpringBootTest
// class AuthServiceApplicationTests {
//
//     @Test
//     void contextLoads() {
//     }
//
// }

@SpringBootTest(properties = "jwt.secret=" + TestcontainersConfiguration.TEST_JWT_SECRET)
@Import(TestcontainersConfiguration.class)
class AuthServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
