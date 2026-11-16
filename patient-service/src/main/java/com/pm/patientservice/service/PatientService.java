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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.UUID;

// PHASE 0 (step 0.6): imports no longer needed
// PHASE 0 (old): import org.springframework.kafka.core.KafkaTemplate;
// PHASE 0 (old): import java.time.LocalDate;
// PHASE 0 (old): import java.util.List;

@Service
public class PatientService {

    private final PatientRepo patientRepo;
    private final BillingServiceGrpcClient billingServiceGrpcClient;
    // PHASE 0 (step 0.6): removed (audit A4). The KafkaTemplate was injected but
    // never used here - all sending goes through KafkaProducer. Unused
    // dependencies make a class look more complicated than it is.
    // PHASE 0 (old): private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final KafkaProducer kafkaProducer;

    // PHASE 0 (old):
    // public PatientService(PatientRepo patientRepo , BillingServiceGrpcClient billingServiceGrpcClient, KafkaTemplate<String, byte[]> kafkaTemplate, KafkaProducer kafkaProducer) {
    //     this.billingServiceGrpcClient = billingServiceGrpcClient;
    //     this.patientRepo = patientRepo;
    //     this.kafkaTemplate = kafkaTemplate;
    //     this.kafkaProducer = kafkaProducer;
    // }
    public PatientService(PatientRepo patientRepo,
                          BillingServiceGrpcClient billingServiceGrpcClient,
                          KafkaProducer kafkaProducer) {
        this.billingServiceGrpcClient = billingServiceGrpcClient;
        this.patientRepo = patientRepo;
        this.kafkaProducer = kafkaProducer;
    }


    public PatientResponseDTO createPatient(PatientRequestDTO patientRequestDTO) {

        // PHASE 0 (step 0.9): the e-mail is no longer put into the exception message
        // (it ended up in logs). GlobalExceptionHandler builds the client response.
        // PHASE 0 (old):
        // throw new EmailAlreadyExistException("A patient with this email already exist: " +
        //         patientRequestDTO.getEmail());
        if  (patientRepo.existsByEmail(patientRequestDTO.getEmail())) {
            throw new EmailAlreadyExistException("A patient with this email already exists");
        }
        Patient newPatient= patientRepo.save(PatientMapper.toEntity(patientRequestDTO));

        billingServiceGrpcClient.createBillingAccount(newPatient.getId().toString(),
                newPatient.getName(), newPatient.getEmail());

        kafkaProducer.sendEvent(newPatient);

        return PatientMapper.toDto(newPatient);
    }

    // PHASE 0 (step 0.6): pagination (audit A6).
    // Before: findAll() loaded EVERY patient into memory and sent them all in one
    // response. Fine with 10 rows, a production incident with 1,000,000 (memory,
    // response time, network). Now the client asks for one page at a time, e.g.
    // ?page=0&size=20&sort=name. Spring Data turns the Pageable into
    //   SELECT ... ORDER BY name LIMIT 20 OFFSET 0   plus   SELECT count(*) ...
    // so the database does the work and only one page travels over the network.
    // Page.map(...) converts each entity to a DTO and keeps the paging info.
    // PHASE 0 (old):
    // public List<PatientResponseDTO> getAllPatients() {
    //     List<Patient> patients = patientRepo.findAll();
    //     return patients.stream().map(PatientMapper::toDto).toList();
    // }
    public Page<PatientResponseDTO> getPatients(Pageable pageable) {
        return patientRepo.findAll(pageable).map(PatientMapper::toDto);
    }

    public PatientResponseDTO updatePatient(UUID id, PatientRequestDTO patientRequestDTO) {
        Patient existingPatient = patientRepo.findById(id)
                .orElseThrow(() -> new PatientNotFoundException("Patient not found with id: " + id));

        // PHASE 0 (old):
        // throw new EmailAlreadyExistException("A patient with this email already exist: " +
        //         patientRequestDTO.getEmail());
        if (patientRepo.existsByEmailAndIdNot(patientRequestDTO.getEmail(), id)) {
            throw new EmailAlreadyExistException("A patient with this email already exists");
        }

        existingPatient.setName(patientRequestDTO.getName());
        existingPatient.setEmail(patientRequestDTO.getEmail());
        existingPatient.setAddress(patientRequestDTO.getAddress());
        // PHASE 0 (step 0.6): DTO already holds a LocalDate - no parsing, no 500.
        // PHASE 0 (old): existingPatient.setBirthDate(LocalDate.parse(patientRequestDTO.getBirthDate()));
        existingPatient.setBirthDate(patientRequestDTO.getBirthDate());

        Patient updatedPatient = patientRepo.save(existingPatient);
        return PatientMapper.toDto(updatedPatient);
    }

    public void deletePatient(UUID id) {
        patientRepo.deleteById(id);
    }
}
