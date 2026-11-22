package com.pm.patientservice.controller;

import com.pm.patientservice.config.SecurityConfig;
import com.pm.patientservice.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 3 (step 3.2): NEW - who may call which patient endpoint.
 *
 * The REAL chain runs: bearer header -> JwtDecoder -> "permissions" claim mapped to
 * authorities (application.yml) -> SecurityConfig -> @PreAuthorize on PatientController.
 * Only the JwtDecoder is a test double ({@link TestDecoder}): instead of checking a
 * signature it looks the token up in a small table. So these tests prove the permission
 * MAPPING and RULES; signature/issuer/audience checks are the same Spring code the
 * gateway uses, tested with real signed tokens in GatewaySecurityTest.
 *
 *   token "reader" -> [read:patients]
 *   token "writer" -> [read:patients, write:patients]
 *   token "admin"  -> [read:patients, write:patients, delete:patients]
 *   token "nobody" -> []
 *   anything else  -> invalid token
 */
@WebMvcTest(PatientController.class)
@Import({SecurityConfig.class, PatientSecurityTest.TestDecoder.class})
class PatientSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientService patientService;

    @Test
    void noTokenIs401ProblemDetails() throws Exception {
        mockMvc.perform(get("/patients"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/patients"));
        verifyNoInteractions(patientService);
    }

    @Test
    void invalidTokenIs401() throws Exception {
        mockMvc.perform(get("/patients").header(HttpHeaders.AUTHORIZATION, "Bearer forged"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid or expired token"));
        verifyNoInteractions(patientService);
    }

    @Test
    void readPermissionMayList() throws Exception {
        when(patientService.getPatients(any())).thenReturn(Page.empty());

        mockMvc.perform(get("/patients").header(HttpHeaders.AUTHORIZATION, "Bearer reader"))
                .andExpect(status().isOk());
    }

    @Test
    void validTokenWithoutPermissionsIs403() throws Exception {
        // authenticated (valid token) but not authorized (no permission) -> 403, not 401
        mockMvc.perform(get("/patients").header(HttpHeaders.AUTHORIZATION, "Bearer nobody"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("Missing permission for this operation"));
        verifyNoInteractions(patientService);
    }

    @Test
    void readOnlyCallerCannotCreate() throws Exception {
        mockMvc.perform(post("/patients").header(HttpHeaders.AUTHORIZATION, "Bearer reader")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_PATIENT))
                .andExpect(status().isForbidden());
        verifyNoInteractions(patientService);
    }

    /**
     * FINDING (found by this test): the ORDER of checks. A caller WITH a valid token but
     * WITHOUT write:patients who sends an INVALID body gets 400 (validation errors), not 403.
     * Why: Spring must turn the JSON into the method's arguments (incl. @Validated) before
     * it can call the method - and @PreAuthorize runs only when the method is called.
     * Impact: small - only the field rules leak, no data, and only to authenticated callers
     * (no token is still 401 first, by the filter chain). Accepted and documented; the fix
     * would be URL rules in SecurityConfig (they run before the controller), duplicating
     * the per-method rules.
     */
    @Test
    void validationRunsBeforeThePermissionCheck() throws Exception {
        mockMvc.perform(post("/patients").header(HttpHeaders.AUTHORIZATION, "Bearer reader")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(patientService);
    }

    private static final String VALID_PATIENT = """
            {"name": "Anna Test", "email": "anna@example.com", "address": "Main Street 1",
             "birthDate": "1990-01-15", "registrationDate": "2025-01-01"}
            """;

    @Test
    void writePermissionIsNotEnoughToDelete() throws Exception {
        // the Auth0 test application has exactly read + write -> this is the 403 you see by hand
        mockMvc.perform(delete("/patients/{id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, "Bearer writer"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(patientService);
    }

    @Test
    void deletePermissionMayDelete() throws Exception {
        mockMvc.perform(delete("/patients/{id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, "Bearer admin"))
                .andExpect(status().isNoContent());
    }

    @Test
    void unplannedPathIsClosedEvenWithAllPermissions() throws Exception {
        mockMvc.perform(get("/").header(HttpHeaders.AUTHORIZATION, "Bearer admin"))
                .andExpect(status().isForbidden());
    }

    /**
     * Replaces the real JwtDecoder (Spring Boot backs off when a JwtDecoder bean exists).
     * Returns a Jwt with the claims a real Auth0 token would have.
     */
    @TestConfiguration
    static class TestDecoder {

        private static final Map<String, List<String>> PERMISSIONS = Map.of(
                "reader", List.of("read:patients"),
                "writer", List.of("read:patients", "write:patients"),
                "admin", List.of("read:patients", "write:patients", "delete:patients"),
                "nobody", List.of());

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                List<String> permissions = PERMISSIONS.get(token);
                if (permissions == null) {
                    throw new BadJwtException("unknown test token");
                }
                return Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .subject("test-" + token)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .claim("permissions", permissions)
                        .build();
            };
        }
    }
}
