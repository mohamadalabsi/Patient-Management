import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

public class PatientIntegrationTest {

    @BeforeAll
    static void setup() {
        RestAssured.baseURI = "http://localhost:4004";
    }

    @Test
    public void shouldReturnPatientDetailsWithValidToken() {
        String loginPayload = """
                {
                    "email": "testuser@test.com",
                    "password": "password123"
                }
                """;

        String token = given()
                .contentType("application/json")
                .body(loginPayload)
                .when()
                .post("/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .get("token");

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                // PHASE 0 (step 0.6): explicit page request (response is now paginated).
                // PHASE 0 (old): .get("/api/patients")
                .get("/api/patients?page=0&size=5")
                .then()
                .statusCode(200)
                // PHASE 0 (step 0.6): fix B9 - the old assertion could never fail.
                // The response used to be a JSON ARRAY. body("patients", ...) on an
                // array means "collect field 'patients' of every element" -> a list
                // like [null, null] or [] - which is NOT null, so the test always
                // passed, even if the endpoint returned garbage.
                // Now we assert the real structure of the paginated response.
                // PHASE 0 (old): .body("patients", notNullValue());
                .body("content", notNullValue())
                .body("page.size", equalTo(5))
                .body("page.number", equalTo(0))
                .body("page.totalElements", greaterThanOrEqualTo(0));
    }
}