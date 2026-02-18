package com.pm.patientservice.mapper;

import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.model.Patient;

import java.time.LocalDate;

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
        patient.setBirthDate(LocalDate.parse(patientRequestDTO.getBirthDate()));
        patient.setRegistrationDate(LocalDate.parse(patientRequestDTO.getRegistrationDate()));
        return patient;
    }
}
