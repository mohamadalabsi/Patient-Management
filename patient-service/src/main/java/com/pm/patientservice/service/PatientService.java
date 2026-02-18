package com.pm.patientservice.service;


import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.exception.EmailAlreadyExistException;
import com.pm.patientservice.exception.PatientNotFoundException;
import com.pm.patientservice.grpc.BillingServiceGrpcClient;
import com.pm.patientservice.kafka.KafkaProducer;
import com.pm.patientservice.mapper.PatientMapper;
import com.pm.patientservice.model.Patient;
import com.pm.patientservice.repository.PatientRepo;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class PatientService {

    private final PatientRepo patientRepo;
    private final BillingServiceGrpcClient billingServiceGrpcClient;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final KafkaProducer kafkaProducer;

    public PatientService(PatientRepo patientRepo , BillingServiceGrpcClient billingServiceGrpcClient, KafkaTemplate<String, byte[]> kafkaTemplate, KafkaProducer kafkaProducer) {
        this.billingServiceGrpcClient = billingServiceGrpcClient;
        this.patientRepo = patientRepo;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaProducer = kafkaProducer;
    }


    public PatientResponseDTO createPatient(PatientRequestDTO patientRequestDTO) {

        if  (patientRepo.existsByEmail(patientRequestDTO.getEmail())) {
            throw new EmailAlreadyExistException("A patient with this email already exist: " +
                    patientRequestDTO.getEmail());
        }
        Patient newPatient= patientRepo.save(PatientMapper.toEntity(patientRequestDTO));

        billingServiceGrpcClient.createBillingAccount(newPatient.getId().toString(),
                newPatient.getName(), newPatient.getEmail());

        kafkaProducer.sendEvent(newPatient);

        return PatientMapper.toDto(newPatient);
    }

    public List<PatientResponseDTO> getAllPatients() {
        List<Patient> patients = patientRepo.findAll();
        return patients.stream().map(PatientMapper::toDto).toList();
    }

    public PatientResponseDTO updatePatient(UUID id, PatientRequestDTO patientRequestDTO) {
        Patient existingPatient = patientRepo.findById(id)
                .orElseThrow(() -> new PatientNotFoundException("Patient not found with id: " + id));

        if (patientRepo.existsByEmailAndIdNot(patientRequestDTO.getEmail(), id)) {
            throw new EmailAlreadyExistException("A patient with this email already exist: " +
                    patientRequestDTO.getEmail());
        }

        existingPatient.setName(patientRequestDTO.getName());
        existingPatient.setEmail(patientRequestDTO.getEmail());
        existingPatient.setAddress(patientRequestDTO.getAddress());
        existingPatient.setBirthDate(LocalDate.parse(patientRequestDTO.getBirthDate()));

        Patient updatedPatient = patientRepo.save(existingPatient);
        return PatientMapper.toDto(updatedPatient);
    }

    public void deletePatient(UUID id) {
        patientRepo.deleteById(id);
    }
}
