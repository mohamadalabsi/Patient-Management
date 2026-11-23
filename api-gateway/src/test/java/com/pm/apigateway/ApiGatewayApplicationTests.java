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
 *
 * PHASE 3 (step 3.1): the security settings have no defaults (a gateway without a known
 * token issuer must not start), so the test passes placeholder values. Nothing is
 * downloaded from the issuer at startup - only when the first token has to be checked.
 */
// PHASE 3 (old): @SpringBootTest
@SpringBootTest(properties = {
        "AUTH0_ISSUER_URI=https://issuer.test/",
        "AUTH0_AUDIENCE=https://api.test"
})
class ApiGatewayApplicationTests {

    @Test
    void contextLoads() {
    }

}
