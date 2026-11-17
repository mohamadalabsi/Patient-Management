package com.pm.patientservice.service;

import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.exception.BillingServiceUnavailableException;
import com.pm.patientservice.exception.EmailAlreadyExistException;
import com.pm.patientservice.exception.PatientNotFoundException;
import com.pm.patientservice.grpc.BillingServiceGrpcClient;
import com.pm.patientservice.kafka.PatientCreatedEvent;
import com.pm.patientservice.model.Patient;
import com.pm.patientservice.repository.PatientRepo;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PHASE 1 (step 1.3): NEW - UNIT tests for PatientService (the business rules).
 *
 * UNIT TEST = one class, no Spring, no database, no network. Runs in milliseconds.
 * Every collaborator (repository, gRPC client, event publisher) is replaced by a Mockito
 * MOCK: an object that records how it was called and returns what we tell it to.
 *   - @ExtendWith(MockitoExtension.class)  -> JUnit 5 + Mockito integration
 *   - @Mock                                -> creates a mock
 *   - @InjectMocks                         -> creates PatientService with the mocks
 *                                             passed to its constructor
 *   - when(...).thenReturn(...)            -> "stubbing": what the mock returns
 *   - verify(...)                          -> assert how the mock was called
 *
 * What a unit test can NOT prove: that @Transactional really rolls back, or that the
 * event really reaches Kafka only after commit - that needs Spring + a real DB/Kafka
 * (PatientCreationFlowIT, step 1.6). Here we check the ORDER and the DECISIONS.
 *
 * Test structure: Given (prepare mocks) / When (call one method) / Then (assert).
 * @Nested groups tests by method, so the report reads like a specification.
 */
@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

    @Mock
    private PatientRepo patientRepo;
    @Mock
    private BillingServiceGrpcClient billingClient;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private PatientService patientService;

    @Nested
    class CreatePatient {

        @Test
        void savesThenCreatesBillingAccountThenPublishesEvent() {
            // Given
            PatientRequestDTO request = request("anna@example.com");
            when(patientRepo.existsByEmail("anna@example.com")).thenReturn(false);
            when(patientRepo.saveAndFlush(any(Patient.class))).thenAnswer(inv -> withId(inv.getArgument(0)));

            // When
            PatientResponseDTO response = patientService.createPatient(request);

            // Then - the response is built from the SAVED entity (it has the generated id)
            assertThat(response.getId()).isNotBlank();
            assertThat(response.getEmail()).isEqualTo("anna@example.com");

            // ... and the order is the one designed in Phase 0 (step 0.7):
            // 1. DB row (flushed, so constraint errors surface here)  2. billing  3. event
            InOrder order = inOrder(patientRepo, billingClient, eventPublisher);
            order.verify(patientRepo).saveAndFlush(any(Patient.class));
            order.verify(billingClient).createBillingAccount(response.getId(), "Anna Test", "anna@example.com");
            order.verify(eventPublisher).publishEvent(any(PatientCreatedEvent.class));
        }

        @Test
        void publishedEventCarriesTheSavedPatient() {
            when(patientRepo.existsByEmail(anyString())).thenReturn(false);
            when(patientRepo.saveAndFlush(any(Patient.class))).thenAnswer(inv -> withId(inv.getArgument(0)));

            PatientResponseDTO response = patientService.createPatient(request("ben@example.com"));

            // ArgumentCaptor = "catch" the object that was passed to the mock and inspect it
            ArgumentCaptor<PatientCreatedEvent> captor = ArgumentCaptor.forClass(PatientCreatedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().patient().getId().toString()).isEqualTo(response.getId());
        }

        @Test
        void duplicateEmailIsRejectedBeforeAnythingIsSaved() {
            when(patientRepo.existsByEmail("taken@example.com")).thenReturn(true);

            assertThatThrownBy(() -> patientService.createPatient(request("taken@example.com")))
                    .isInstanceOf(EmailAlreadyExistException.class)
                    // Phase 0 (step 0.9): no PII in messages - the email must not appear
                    .hasMessageNotContaining("taken@example.com");

            verify(patientRepo, never()).saveAndFlush(any());
            verifyNoInteractions(billingClient, eventPublisher);
        }

        @Test
        void billingFailurePropagatesAndNoEventIsPublished() {
            when(patientRepo.existsByEmail(anyString())).thenReturn(false);
            when(patientRepo.saveAndFlush(any(Patient.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
            when(billingClient.createBillingAccount(anyString(), anyString(), anyString()))
                    .thenThrow(new BillingServiceUnavailableException("billing down", null));

            // The exception must leave the method: that is what makes @Transactional roll
            // back the saved row (proven against a real DB in step 1.6).
            assertThatThrownBy(() -> patientService.createPatient(request("carl@example.com")))
                    .isInstanceOf(BillingServiceUnavailableException.class);

            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    class UpdatePatient {

        @Test
        void unknownIdThrowsNotFound() {
            UUID id = UUID.randomUUID();
            when(patientRepo.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> patientService.updatePatient(id, request("x@example.com")))
                    .isInstanceOf(PatientNotFoundException.class);
            verify(patientRepo, never()).save(any());
        }

        @Test
        void emailOfAnotherPatientIsRejected() {
            UUID id = UUID.randomUUID();
            when(patientRepo.findById(id)).thenReturn(Optional.of(existing(id, "old@example.com")));
            when(patientRepo.existsByEmailAndIdNot("other@example.com", id)).thenReturn(true);

            assertThatThrownBy(() -> patientService.updatePatient(id, request("other@example.com")))
                    .isInstanceOf(EmailAlreadyExistException.class);
            verify(patientRepo, never()).save(any());
        }

        @Test
        void keepingOwnEmailIsAllowedAndFieldsAreUpdated() {
            UUID id = UUID.randomUUID();
            when(patientRepo.findById(id)).thenReturn(Optional.of(existing(id, "same@example.com")));
            when(patientRepo.existsByEmailAndIdNot("same@example.com", id)).thenReturn(false);
            when(patientRepo.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));

            PatientRequestDTO update = request("same@example.com");
            update.setName("New Name");
            update.setAddress("New Street 1");

            PatientResponseDTO response = patientService.updatePatient(id, update);

            assertThat(response.getId()).isEqualTo(id.toString());
            assertThat(response.getName()).isEqualTo("New Name");
            assertThat(response.getAddress()).isEqualTo("New Street 1");
        }

        @Test
        void updateDoesNotCallBillingOrPublishEvents() {
            UUID id = UUID.randomUUID();
            when(patientRepo.findById(id)).thenReturn(Optional.of(existing(id, "a@example.com")));
            when(patientRepo.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));

            patientService.updatePatient(id, request("a@example.com"));

            verifyNoInteractions(billingClient, eventPublisher);
        }
    }

    @Test
    void deleteDelegatesToRepository() {
        UUID id = UUID.randomUUID();

        patientService.deletePatient(id);

        verify(patientRepo).deleteById(id);
    }

    // ---- test data helpers ---------------------------------------------------------

    private static PatientRequestDTO request(String email) {
        PatientRequestDTO dto = new PatientRequestDTO();
        dto.setName("Anna Test");
        dto.setEmail(email);
        dto.setAddress("Main Street 1, Frankfurt");
        dto.setBirthDate(LocalDate.of(1990, 1, 15));
        dto.setRegistrationDate(LocalDate.of(2025, 1, 1));
        return dto;
    }

    /** Simulates what the database does on INSERT: it generates the id. */
    private static Patient withId(Patient patient) {
        patient.setId(UUID.randomUUID());
        return patient;
    }

    private static Patient existing(UUID id, String email) {
        return new Patient(id, "Old Name", email, "Old Street 1",
                LocalDate.of(1980, 5, 5), LocalDate.of(2024, 1, 1));
    }
}
