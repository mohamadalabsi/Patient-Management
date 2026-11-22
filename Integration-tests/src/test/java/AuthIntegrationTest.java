import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.notNullValue;

/**
 * PHASE 3 (step 3.1): DISABLED, kept for reference. These tests logged in at
 * auth-service /auth/login. auth-service is retired: Auth0 does the login, and in the
 * E2E stack tokens come from mock-oauth2-server (see E2E.token()).
 */
@Disabled("PHASE 3 (step 3.1): auth-service retired - login happens at Auth0")
public class AuthIntegrationTest {

    @BeforeAll
    static void setup() {
        // PHASE 1 (step 1.11): base URL from E2E (configurable with -Dgateway.url)
        // PHASE 1 (old): RestAssured.baseURI = "http://localhost:4004";
        E2E.configure();
    }

    @Test
    public void shouldReturnOkWithValidToken() {
        String loginPayload = """
                {
                    "email": "testuser@test.com",
                    "password": "password123"
                }
                """;

        Response response = RestAssured.given()
                .contentType("application/json")
                .body(loginPayload)
                .when()
                .post("/auth/login")
                .then()
                .statusCode(200)
                .body("token", notNullValue())
                .extract()
                .response();
        // PHASE 1 (step 1.11): do not print the token - a valid JWT in build logs (CI logs
        // are often readable by many people) is a credential leak.
        // PHASE 1 (old): System.out.println("Generated token : " + response.jsonPath().getString("token"));
    }

    @Test
    public void shouldReturnUnauthorizedOnInvalidLogin() {
        String loginPayload = """
                {
                    "email": "wrongtestuser@test.com",
                    "password": "wrongpassword123"
                }
                """;
        RestAssured.given()
                .contentType("application/json")
                .body(loginPayload)
                .when()
                .post("/auth/login")
                .then()
                .statusCode(401);
    }
}
