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
 *   PHASE 3 (step 3.1): replaced by token() - auth-service (and its test user) is retired.
 * - uniqueEmail(): every test creates its OWN data, so tests do not depend on each other,
 *   on their order, or on what earlier runs left in the database.
 */
final class E2E {

    static final String GATEWAY = System.getProperty("gateway.url", "http://localhost:4004");

    // PHASE 3 (step 3.1): NEW - where the tests get access tokens: the fake issuer
    // mock-oauth2-server from docker-compose.e2e.yml (host port 8090). Configurable like
    // gateway.url, e.g. http://host.docker.internal:8090/default/token inside a container.
    static final String TOKEN_URL = System.getProperty("token.url", "http://localhost:8090/default/token");

    // PHASE 3 (step 3.1): the mock server writes the host it was called with into the
    // token's "iss" claim. The gateway trusts exactly http://mock-oauth2-server:8080/default
    // (the address it uses inside the Docker network). By sending this Host header we get
    // that issuer no matter how WE reach the server (localhost, host.docker.internal ...).
    private static final String ISSUER_HOST = "mock-oauth2-server:8080";

    private E2E() {
    }

    static void configure() {
        RestAssured.baseURI = GATEWAY;
        // print request + response only when an assertion fails (keeps passing runs quiet)
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    /**
     * PHASE 3 (step 3.1): NEW - get an access token like a backend client does with Auth0:
     * OAuth2 "client credentials" grant = POST client_id + client_secret to the token URL.
     * What the token contains (audience, permissions) depends on the client id - see
     * Integration-tests/mock-issuer/mock-oauth2-server.json:
     *   "e2e-client"    -> our API's audience, all patient permissions
     *   "e2e-other-api" -> a DIFFERENT audience (the gateway must reject it)
     */
    static String token(String clientId) {
        return given()
                .header("Host", ISSUER_HOST)
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", clientId)
                .formParam("client_secret", "not-checked-by-the-mock")
                .when()
                .post(TOKEN_URL)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("access_token");
    }

    /** A token with all patient permissions for our API. */
    static String token() {
        return token("e2e-client");
    }

    // PHASE 3 (old): kept for reference - logged in at the retired auth-service.
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
