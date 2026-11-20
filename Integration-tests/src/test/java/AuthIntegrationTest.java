import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.notNullValue;

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
