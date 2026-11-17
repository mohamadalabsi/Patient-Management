package com.pm.patientservice.mapper;

import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.model.Patient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 1 (step 1.3): NEW - unit tests for PatientMapper.
 *
 * A mapper looks too simple to test, but "forgot to copy one field" is one of the most
 * common bugs, and the compiler cannot catch it. These tests compare every field.
 * No mocks needed: the mapper is a pure function (input -> output, no side effects).
 */
class PatientMapperTest {

    @Test
    void toEntityCopiesAllRequestFields() {
        PatientRequestDTO dto = new PatientRequestDTO();
        dto.setName("Anna Test");
        dto.setEmail("anna@example.com");
        dto.setAddress("Main Street 1");
        dto.setBirthDate(LocalDate.of(1990, 1, 15));
        dto.setRegistrationDate(LocalDate.of(2025, 1, 1));

        Patient patient = PatientMapper.toEntity(dto);

        // id stays null: the DATABASE generates it on insert, never the client
        assertThat(patient.getId()).isNull();
        assertThat(patient.getName()).isEqualTo("Anna Test");
        assertThat(patient.getEmail()).isEqualTo("anna@example.com");
        assertThat(patient.getAddress()).isEqualTo("Main Street 1");
        assertThat(patient.getBirthDate()).isEqualTo(LocalDate.of(1990, 1, 15));
        assertThat(patient.getRegistrationDate()).isEqualTo(LocalDate.of(2025, 1, 1));
    }

    @Test
    void toDtoCopiesAllFieldsAndFormatsIdAndDateAsIsoStrings() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Patient patient = new Patient(id, "Anna Test", "anna@example.com", "Main Street 1",
                LocalDate.of(1990, 1, 15), LocalDate.of(2025, 1, 1));

        PatientResponseDTO dto = PatientMapper.toDto(patient);

        assertThat(dto.getId()).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        assertThat(dto.getName()).isEqualTo("Anna Test");
        assertThat(dto.getEmail()).isEqualTo("anna@example.com");
        assertThat(dto.getAddress()).isEqualTo("Main Street 1");
        assertThat(dto.getBirthDate()).isEqualTo("1990-01-15"); // ISO-8601, what clients parse
    }
}
