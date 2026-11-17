package com.pm.patientservice.dto;

import com.pm.patientservice.dto.validators.CreatePatientValidationGroup;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;


@Data
public class PatientRequestDTO {

    @NotBlank(message = "Name is mandatory")
    @Size(max = 100, message = "Name must be less than 100 characters")
    private String name;

    // PHASE 0 (step 0.6): fix B5.
    // @Size without min/max checks nothing - its message "Email should be valid"
    // was never shown. @Email really validates the format. Before this fix an
    // invalid email passed the controller and only failed later at the @Email on
    // the JPA entity, during the database insert -> 500 instead of 400.
    // PHASE 0 (old):
    // @NotBlank(message = "Email is mandatory")
    // @Size(message = "Email should be valid")
    // private String email;
    @NotBlank(message = "Email is mandatory")
    @Email(message = "Email should be valid")
    @Size(max = 255, message = "Email must be at most 255 characters") // = VARCHAR(255) in V1 migration
    private String email;

    @NotBlank(message = "Address is mandatory")
    @Size(max = 255, message = "Address must be at most 255 characters")
    private String address;

    // PHASE 0 (step 0.6): String -> LocalDate (part of B4).
    // Before, the date was a String and PatientMapper called LocalDate.parse(...)
    // itself. "abc" therefore threw DateTimeParseException deep in the service
    // -> 500. Now Jackson converts "1998-04-23" while reading the JSON; an
    // invalid date makes the request body unreadable -> Spring answers 400
    // (HttpMessageNotReadableException, handled by ResponseEntityExceptionHandler).
    // The JSON format for clients is unchanged: still "yyyy-MM-dd".
    // @NotBlank only works on text, so for dates we use @NotNull.
    // PHASE 0 (old):
    // @NotBlank(message = "Birth date is mandatory")
    // private String birthDate;
    @NotNull(message = "Birth date is mandatory")
    @Past(message = "Birth date must be in the past")
    private LocalDate birthDate;

    // Only required when CREATING a patient: the controller validates POST with
    // {Default, CreatePatientValidationGroup} and PUT with {Default} only.
    // PHASE 0 (old):
    // @NotBlank(groups = CreatePatientValidationGroup.class, message = "Registration date is mandatory")
    // private String registrationDate;
    @NotNull(groups = CreatePatientValidationGroup.class, message = "Registration date is mandatory")
    @PastOrPresent(message = "Registration date cannot be in the future")
    private LocalDate registrationDate;
}
