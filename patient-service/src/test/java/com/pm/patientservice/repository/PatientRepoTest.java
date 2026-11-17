package com.pm.patientservice.repository;

import com.pm.patientservice.PostgresTestConfiguration;
import com.pm.patientservice.model.Patient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE 1 (step 1.5): NEW - repository tests against a REAL PostgreSQL 16.
 *
 * @DataJpaTest is the persistence "test slice": JPA, repositories, Flyway and the
 * DataSource - no web layer, no Kafka, no gRPC. Every test runs in a transaction that is
 * ROLLED BACK at the end, so tests do not see each other's data.
 *
 * WHY NOT H2 (in-memory database)?
 * By default @DataJpaTest swaps the DataSource for an embedded database.
 * @AutoConfigureTestDatabase(replace = NONE) switches that off, so the Postgres container
 * from PostgresTestConfiguration is used. H2 has a different SQL dialect, different
 * constraint behaviour and would not run our Flyway migrations unchanged - a test that
 * passes on H2 proves little about production. Here the schema comes from the real
 * V1__create_patient_table.sql.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
class PatientRepoTest {

    @Autowired
    private PatientRepo patientRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliedTheV1Migration() {
        // flyway_schema_history is Flyway's own bookkeeping table
        Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void existsByEmail() {
        patientRepo.saveAndFlush(patient("anna@example.com"));

        assertThat(patientRepo.existsByEmail("anna@example.com")).isTrue();
        assertThat(patientRepo.existsByEmail("nobody@example.com")).isFalse();
    }

    @Test
    void existsByEmailAndIdNotIgnoresThePatientItself() {
        Patient anna = patientRepo.saveAndFlush(patient("anna@example.com"));
        Patient ben = patientRepo.saveAndFlush(patient("ben@example.com"));

        // Anna keeps her own email -> not a conflict
        assertThat(patientRepo.existsByEmailAndIdNot("anna@example.com", anna.getId())).isFalse();
        // Ben wants Anna's email -> conflict
        assertThat(patientRepo.existsByEmailAndIdNot("anna@example.com", ben.getId())).isTrue();
    }

    @Test
    void databaseRejectsDuplicateEmailEvenWithoutOurCheck() {
        // This is the safety net behind the race condition (audit B11): if two requests
        // pass existsByEmail() at the same time, the UNIQUE constraint uk_patient_email
        // stops the second INSERT. Spring translates the SQL error into
        // DataIntegrityViolationException -> GlobalExceptionHandler -> 409.
        patientRepo.saveAndFlush(patient("same@example.com"));

        assertThatThrownBy(() -> patientRepo.saveAndFlush(patient("same@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sortingByUnknownPropertyFails() {
        // GET /patients?sort=password -> Spring Data cannot map "password" to a column.
        // GlobalExceptionHandler turns this exception into a 400.
        assertThatThrownBy(() -> patientRepo.findAll(PageRequest.of(0, 5, Sort.by("password"))))
                .isInstanceOf(PropertyReferenceException.class);
    }

    @Test
    void idIsGeneratedOnInsert() {
        Patient saved = patientRepo.saveAndFlush(patient("new@example.com"));

        assertThat(saved.getId()).isNotNull();
    }

    private static Patient patient(String email) {
        Patient p = new Patient();
        p.setName("Test Patient");
        p.setEmail(email);
        p.setAddress("Main Street 1");
        p.setBirthDate(LocalDate.of(1990, 1, 15));
        p.setRegistrationDate(LocalDate.of(2025, 1, 1));
        return p;
    }
}
