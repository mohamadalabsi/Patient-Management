package com.pm.patientservice.controller;


import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.dto.validators.CreatePatientValidationGroup;
import com.pm.patientservice.service.PatientService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.groups.Default;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;


import java.util.UUID;

// PHASE 0 (old): import java.util.List;

// PHASE 3 (step 3.2): every endpoint now declares the permission it needs.
//   GET    /patients        -> read:patients
//   POST   /patients        -> write:patients
//   PUT    /patients/{id}   -> write:patients
//   DELETE /patients/{id}   -> delete:patients
// How it connects: the token's "permissions" claim (from Auth0) becomes the caller's
// authorities (application.yml), and @PreAuthorize compares them with the rule BEFORE
// the method runs. Missing -> 403 (config/SecurityConfig.java), the method never runs.
// Delete is its own permission on purpose: removing health data is the most dangerous
// operation, so it can be granted to fewer callers than "write".
@RestController
@Tag(name="Patient", description = "Patient management APIs")
public class PatientController {

    private PatientService patientService;

    @Autowired
    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    @RequestMapping("/")
    public String version() {
        return "Patient Service - Version 1.0";
    }

    // PHASE 0 (step 0.6): paginated list (audit A6).
    //
    // Pageable is filled by Spring from the query string:
    //   GET /patients?page=0&size=20&sort=name,asc
    // @PageableDefault applies when the client sends nothing: first page, 20 rows,
    // sorted by name (a stable order - without ORDER BY, SQL may return rows in any
    // order and pages could overlap). The maximum page size (100) is set in
    // application.yml, so nobody can ask for size=1000000.
    //
    // Return type PagedModel: Spring Data's STABLE JSON format for pages:
    //   { "content": [ ...patients... ],
    //     "page": { "size": 20, "number": 0, "totalElements": 57, "totalPages": 3 } }
    // (Serialising Page/PageImpl directly is discouraged - its JSON is an internal
    // detail and has changed between versions.)
    //
    // ⚠ Breaking change: the response used to be a bare JSON array [ ... ].
    // Our only clients (Integration-tests, api-requests/*.http) are updated too.
    // PHASE 0 (old):
    // @GetMapping("/patients")
    // @Operation(summary = "Get Patients Version 1.0")
    // public ResponseEntity<List<PatientResponseDTO>> getAllPatients() {
    //     return ResponseEntity.ok().body(patientService.getAllPatients());
    // }
    @PreAuthorize("hasAuthority('read:patients')") // PHASE 3 (step 3.2)
    @GetMapping("/patients")
    @Operation(summary = "Get a page of patients (?page=0&size=20&sort=name,asc)")
    public ResponseEntity<PagedModel<PatientResponseDTO>> getPatients(
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(new PagedModel<>(patientService.getPatients(pageable)));
    }

    @PreAuthorize("hasAuthority('write:patients')") // PHASE 3 (step 3.2)
    @PostMapping("/patients")
    @Operation(summary = "Create new Patient Version 1.0")
    public ResponseEntity<PatientResponseDTO> createPatient(@Validated({Default.class, CreatePatientValidationGroup.class}) @RequestBody PatientRequestDTO
                                                                    patientRequestDTO) {
        return new ResponseEntity<>(patientService.createPatient(patientRequestDTO), HttpStatus.CREATED);
    }

    @PreAuthorize("hasAuthority('write:patients')") // PHASE 3 (step 3.2)
    @PutMapping("/patients/{id}")
    @Operation(summary = "Update Patient Version 1.0")
    public ResponseEntity<PatientResponseDTO> updatePatient(@PathVariable UUID id,
                                                            @Validated({Default.class}) @RequestBody PatientRequestDTO patientRequestDTO) {
        return ResponseEntity.ok().body(patientService.updatePatient(id, patientRequestDTO));
    }

    @PreAuthorize("hasAuthority('delete:patients')") // PHASE 3 (step 3.2)
    @DeleteMapping("/patients/{id}")
    @Operation(summary = "Delete Patient Version 1.0")
    public ResponseEntity<Void> deletePatient(@PathVariable UUID id) {
        patientService.deletePatient(id);
        return ResponseEntity.noContent().build();
    }


}
