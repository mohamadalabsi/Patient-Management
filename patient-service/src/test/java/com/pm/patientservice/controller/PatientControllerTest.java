package com.pm.patientservice.controller;

import com.pm.patientservice.config.SecurityConfig;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.exception.BillingServiceUnavailableException;
import com.pm.patientservice.exception.EmailAlreadyExistException;
import com.pm.patientservice.exception.PatientNotFoundException;
import com.pm.patientservice.service.PatientService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 1 (step 1.4): NEW - the HTTP contract of the patient API (from Phase 0, step 0.6).
 *
 * @WebMvcTest is a Spring "TEST SLICE": it starts only the web layer - controllers,
 * @RestControllerAdvice (GlobalExceptionHandler), JSON conversion, validation - but no
 * database, no Kafka, no gRPC. PatientService is replaced by a mock (@MockitoBean puts a
 * Mockito mock into the Spring context). Starts in ~1-2 s, no Docker needed.
 *
 * MockMvc sends fake HTTP requests through the real Spring MVC machinery (no real server,
 * no port) and lets us assert status, headers and JSON body (jsonPath).
 *
 * What these tests pin down: status codes, the Problem Details error format, the
 * "errors" map for validation, and pagination limits. Clients depend on exactly these.
 *
 * PHASE 3 (step 3.2): two additions, so these tests keep testing the HTTP contract:
 *   - @Import(SecurityConfig.class): a @WebMvcTest slice does not pick up our own
 *     @Configuration classes; without it the test would run with Spring's DEFAULT
 *     security (form login, CSRF) instead of ours.
 *   - @WithMockUser(authorities = ...): every request runs as a caller holding all
 *     patient permissions. Missing/wrong permissions are tested in PatientSecurityTest.
 */
@WebMvcTest(PatientController.class)
@Import(SecurityConfig.class)
@WithMockUser(authorities = {"read:patients", "write:patients", "delete:patients"})
class PatientControllerTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientService patientService;

    @Nested
    class CreatePatient {

        @Test
        void validRequestReturns201WithBody() throws Exception {
            when(patientService.createPatient(any())).thenReturn(response("anna@example.com"));

            postPatient(validJson())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.email").value("anna@example.com"))
                    .andExpect(jsonPath("$.birthDate").value("1990-01-15"));
        }

        @Test
        void invalidFieldsReturn400WithErrorPerField() throws Exception {
            String json = """
                    {"name": "", "email": "not-an-email", "address": "Main Street 1",
                     "birthDate": "2999-01-01", "registrationDate": "2025-01-01"}
                    """;

            postPatient(json)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errors.name").exists())
                    .andExpect(jsonPath("$.errors.email").value("Email should be valid"))
                    .andExpect(jsonPath("$.errors.birthDate").value("Birth date must be in the past"));

            // invalid input must never reach the business logic
            verifyNoInteractions(patientService);
        }

        @Test
        void missingRegistrationDateIsRejectedOnCreate() throws Exception {
            postPatient(validJson().replace(", \"registrationDate\": \"2025-01-01\"", ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.registrationDate").value("Registration date is mandatory"));
        }

        @Test
        void futureRegistrationDateIsRejected() throws Exception {
            postPatient(validJson().replace("2025-01-01", "2999-01-01"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.registrationDate").exists());
        }

        @Test
        void malformedJsonReturns400NotServerError() throws Exception {
            postPatient("{ this is not json")
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(PROBLEM_JSON));
        }

        @Test
        void impossibleDateReturns400() throws Exception {
            // before Phase 0 dates were Strings parsed by hand -> this was a 500
            postPatient(validJson().replace("1990-01-15", "1990-13-45"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void duplicateEmailReturns409() throws Exception {
            when(patientService.createPatient(any()))
                    .thenThrow(new EmailAlreadyExistException("A patient with this email already exists"));

            postPatient(validJson())
                    .andExpect(status().isConflict())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("A patient with this email already exists"));
        }

        @Test
        void uniqueConstraintRaceReturns409() throws Exception {
            // two simultaneous requests: the DB constraint, not our check, rejects the 2nd one
            when(patientService.createPatient(any()))
                    .thenThrow(new DataIntegrityViolationException("uk_patient_email"));

            postPatient(validJson()).andExpect(status().isConflict());
        }

        @Test
        void billingDownReturns503() throws Exception {
            when(patientService.createPatient(any()))
                    .thenThrow(new BillingServiceUnavailableException("Billing service call failed: UNAVAILABLE", null));

            postPatient(validJson())
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentType(PROBLEM_JSON));
        }
    }

    @Nested
    class UpdatePatient {

        @Test
        void registrationDateIsOptionalOnUpdate() throws Exception {
            UUID id = UUID.randomUUID();
            when(patientService.updatePatient(eq(id), any())).thenReturn(response("anna@example.com"));

            mockMvc.perform(put("/patients/{id}", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validJson().replace(", \"registrationDate\": \"2025-01-01\"", "")))
                    .andExpect(status().isOk());
        }

        @Test
        void unknownIdReturns404() throws Exception {
            when(patientService.updatePatient(any(), any()))
                    .thenThrow(new PatientNotFoundException("Patient not found with id: x"));

            mockMvc.perform(put("/patients/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validJson()))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("Patient not found"));
        }

        @Test
        void idThatIsNotAUuidReturns400() throws Exception {
            mockMvc.perform(put("/patients/{id}", "not-a-uuid")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validJson()))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(patientService);
        }
    }

    @Test
    void deleteReturns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/patients/{id}", id))
                .andExpect(status().isNoContent());
        verify(patientService).deletePatient(id);
    }

    @Nested
    class GetPatients {

        @Test
        void returnsPagedModelWithDefaults() throws Exception {
            when(patientService.getPatients(any()))
                    .thenReturn(page(List.of(response("anna@example.com")), PageRequest.of(0, 20)));

            mockMvc.perform(get("/patients"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].email").value("anna@example.com"))
                    .andExpect(jsonPath("$.page.size").value(20))
                    .andExpect(jsonPath("$.page.number").value(0))
                    .andExpect(jsonPath("$.page.totalElements").value(1));

            // default from @PageableDefault: 20 per page, sorted by name ascending
            Pageable pageable = capturePageable();
            assertThat(pageable.getPageSize()).isEqualTo(20);
            assertThat(pageable.getSort().getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        @Test
        void pageSizeIsCappedAt100() throws Exception {
            when(patientService.getPatients(any())).thenReturn(page(List.of(), PageRequest.of(0, 100)));

            // a client asking for 1000 rows per page must not be able to load the whole table
            mockMvc.perform(get("/patients").param("size", "1000"))
                    .andExpect(status().isOk());

            assertThat(capturePageable().getPageSize()).isEqualTo(100);
        }
    }

    // ---- helpers ---------------------------------------------------------------------

    private ResultActions postPatient(String json) throws Exception {
        return mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(patientService).getPatients(captor.capture());
        return captor.getValue();
    }

    private static String validJson() {
        return """
                {"name": "Anna Test", "email": "anna@example.com", "address": "Main Street 1", "birthDate": "1990-01-15", "registrationDate": "2025-01-01"}""";
    }

    private static PatientResponseDTO response(String email) {
        PatientResponseDTO dto = new PatientResponseDTO();
        dto.setId(UUID.randomUUID().toString());
        dto.setName("Anna Test");
        dto.setEmail(email);
        dto.setAddress("Main Street 1");
        dto.setBirthDate("1990-01-15");
        return dto;
    }

    private static Page<PatientResponseDTO> page(List<PatientResponseDTO> content, Pageable pageable) {
        return new PageImpl<>(content, pageable, content.size());
    }
}
