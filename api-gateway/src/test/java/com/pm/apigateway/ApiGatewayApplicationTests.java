package com.pm.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * PHASE 1 (step 1.2): NEW - the gateway had no test at all (no src/test folder).
 *
 * Proves the application context starts: the routes in application.yml are valid, the
 * custom JwtValidation filter factory can be created and every route filter name resolves.
 * The gateway needs no database or broker. Routes point to auth/patient-service URLs, but
 * nothing is called while the context starts, so no other service must be running.
 */
@SpringBootTest
class ApiGatewayApplicationTests {

    @Test
    void contextLoads() {
    }

}
