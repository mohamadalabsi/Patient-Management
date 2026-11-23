import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * PHASE 1 (step 1.11): NEW - end-to-end user journeys through the gateway.
 *
 * These are the only tests that run against ALL real services at once
 * (gateway -> auth-service -> patient-service -> billing gRPC -> Kafka -> analytics).
 * PHASE 3 (step 3.1): token issuer -> gateway -> patient-service -> ... (auth-service retired).
 * They are slow and need the compose stack, so they check only the main journeys and the
 * status codes a client sees. The details are covered by the much faster tests inside
 * each service (step 1.2 - 1.10).
 */
class PatientJourneyIntegrationTest {

    private static String token;

    @BeforeAll
    static void setUp() {
        E2E.configure();
        // PHASE 3 (step 3.1): token from the issuer instead of auth-service
        // PHASE 3 (old): token = E2E.login();
        token = E2E.token();
    }

    @Test
    void createListUpdateDelete() {
        String email = E2E.uniqueEmail();

        // create -> 201 (also creates the billing account via gRPC and sends a Kafka event)
        String id = given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(E2E.patientJson(email))
                .when().post("/api/patients")
                .then().statusCode(201)
                .body("email", equalTo(email))
                .body("id", notNullValue())
                .extract().jsonPath().getString("id");

        // list -> 200, paginated, contains at least the patient we just created
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/patients?page=0&size=10")
                .then().statusCode(200)
                .body("page.size", equalTo(10))
                .body("page.totalElements", greaterThanOrEqualTo(1));

        // update -> 200 (registrationDate not needed on update)
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("""
                        {"name": "E2E Renamed", "email": "%s", "address": "New Street 2", "birthDate": "1990-01-15"}
                        """.formatted(email))
                .when().put("/api/patients/" + id)
                .then().statusCode(200)
                .body("name", equalTo("E2E Renamed"));

        // delete -> 204, then updating it again -> 404
        given().header("Authorization", "Bearer " + token)
                .when().delete("/api/patients/" + id)
                .then().statusCode(204);
        given().header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(E2E.patientJson(email))
                .when().put("/api/patients/" + id)
                .then().statusCode(404);
    }

    @Test
    void duplicateEmailIs409() {
        String email = E2E.uniqueEmail();
        given().header("Authorization", "Bearer " + token).contentType("application/json")
                .body(E2E.patientJson(email))
                .when().post("/api/patients").then().statusCode(201);

        given().header("Authorization", "Bearer " + token).contentType("application/json")
                .body(E2E.patientJson(email))
                .when().post("/api/patients")
                .then().statusCode(409)
                .contentType(startsWith("application/problem+json"));
    }

    @Test
    void invalidInputIs400WithFieldErrors() {
        given().header("Authorization", "Bearer " + token).contentType("application/json")
                .body("""
                        {"name": "", "email": "not-an-email", "address": "X",
                         "birthDate": "2999-01-01", "registrationDate": "2025-01-01"}
                        """)
                .when().post("/api/patients")
                .then().statusCode(400)
                .body("errors.email", notNullValue())
                .body("errors.birthDate", notNullValue());
    }

    @Test
    void unknownPatientIs404() {
        given().header("Authorization", "Bearer " + token).contentType("application/json")
                .body(E2E.patientJson(E2E.uniqueEmail()))
                .when().put("/api/patients/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    @Test
    void noTokenIs401() {
        given().when().get("/api/patients")
                .then().statusCode(401)
                .contentType(startsWith("application/problem+json"));
    }

    @Test
    void invalidTokenIs401() {
        given().header("Authorization", "Bearer not.a.valid.token")
                .when().get("/api/patients")
                .then().statusCode(401);
    }

    /**
     * PHASE 3 (step 3.2): NEW - the token is valid (the gateway lets it through), but it
     * only has read:patients. patient-service checks the permission itself -> reading
     * works, creating is 403. Proves the second check behind the gateway.
     */
    @Test
    void readOnlyTokenCanListButNotCreate() {
        String readOnly = E2E.token("e2e-reader");

        given().header("Authorization", "Bearer " + readOnly)
                .when().get("/api/patients?page=0&size=1")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + readOnly).contentType("application/json")
                .body(E2E.patientJson(E2E.uniqueEmail()))
                .when().post("/api/patients")
                .then().statusCode(403)
                .contentType(startsWith("application/problem+json"));
    }

    /**
     * PHASE 3 (step 3.1): NEW - a REAL token from the same issuer, correctly signed, but
     * issued for another API ("aud"). The gateway's audience check must reject it.
     */
    @Test
    void tokenForAnotherApiIs401() {
        given().header("Authorization", "Bearer " + E2E.token("e2e-other-api"))
                .when().get("/api/patients")
                .then().statusCode(401)
                .contentType(startsWith("application/problem+json"));
    }
}
