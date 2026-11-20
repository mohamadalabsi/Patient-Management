import io.restassured.RestAssured;

import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * PHASE 1 (step 1.11): NEW - shared helpers for the end-to-end tests.
 *
 * - GATEWAY: the stack is reached ONLY through the api-gateway, like a real client.
 *   Configurable with -Dgateway.url=... (e.g. http://host.docker.internal:4004 when the
 *   tests run inside a maven container); default http://localhost:4004.
 * - login(): uses the dev-only test user seeded by Flyway db/dev-data in docker-compose.
 * - uniqueEmail(): every test creates its OWN data, so tests do not depend on each other,
 *   on their order, or on what earlier runs left in the database.
 */
final class E2E {

    static final String GATEWAY = System.getProperty("gateway.url", "http://localhost:4004");

    private E2E() {
    }

    static void configure() {
        RestAssured.baseURI = GATEWAY;
        // print request + response only when an assertion fails (keeps passing runs quiet)
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    static String login() {
        return given()
                .contentType("application/json")
                .body("{\"email\":\"testuser@test.com\",\"password\":\"password123\"}")
                .when()
                .post("/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("token");
    }

    static String uniqueEmail() {
        return "e2e-" + UUID.randomUUID() + "@example.com";
    }

    static String patientJson(String email) {
        return """
                {"name": "E2E Patient", "email": "%s", "address": "Main Street 1, Frankfurt",
                 "birthDate": "1990-01-15", "registrationDate": "2025-01-01"}
                """.formatted(email);
    }
}
