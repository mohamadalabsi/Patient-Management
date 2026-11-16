package com.pm.patientservice.mapper;

import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.model.Patient;

// PHASE 0 (step 0.6): java.time.LocalDate import no longer needed here - the DTO
// already contains LocalDate values, so nothing has to be parsed.
// PHASE 0 (old): import java.time.LocalDate;

public class PatientMapper {

    public static PatientResponseDTO toDto(Patient patient) {
        PatientResponseDTO patientdto = new PatientResponseDTO();
        patientdto.setId(patient.getId().toString());
        patientdto.setName(patient.getName());
        patientdto.setEmail(patient.getEmail());
        patientdto.setAddress(patient.getAddress());
        patientdto.setBirthDate(patient.getBirthDate().toString());
        return patientdto;
    }

    public static Patient toEntity(PatientRequestDTO patientRequestDTO) {
        Patient patient = new Patient();
        patient.setName(patientRequestDTO.getName());
        patient.setEmail(patientRequestDTO.getEmail());
        patient.setAddress(patientRequestDTO.getAddress());
        // PHASE 0 (step 0.6): the DTO fields are LocalDate now (validated by Jackson
        // and Bean Validation before we get here), so we just copy them.
        // A parse error can no longer happen in the mapper -> no more 500.
        // PHASE 0 (old):
        // patient.setBirthDate(LocalDate.parse(patientRequestDTO.getBirthDate()));
        // patient.setRegistrationDate(LocalDate.parse(patientRequestDTO.getRegistrationDate()));
        patient.setBirthDate(patientRequestDTO.getBirthDate());
        patient.setRegistrationDate(patientRequestDTO.getRegistrationDate());
        return patient;
    }
}
