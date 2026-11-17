package com.pm.patientservice.exception;


import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

// =============================================================================
// PHASE 0 (step 0.6): error handling rewritten (audit findings B4, B5, B11).
//
// 1) Correct HTTP status codes. The status code is part of the API contract -
//    clients (and later monitoring/alerts) decide what to do based on it:
//      400 Bad Request  -> the CLIENT sent something invalid; retrying the same
//                          request will fail again.
//      404 Not Found    -> the resource does not exist (was 400 before).
//      409 Conflict     -> request is valid, but clashes with the current state,
//                          e.g. email already taken (was 400 before).
//      500              -> a bug on OUR side. Must never be caused by bad input
//                          (invalid date / email used to give 500).
//
// 2) One standard error format: RFC 9457 "Problem Details for HTTP APIs".
//    Spring has a built-in class for it: ProblemDetail. Response looks like:
//      { "type": "about:blank", "title": "Conflict", "status": 409,
//        "detail": "A patient with this email already exists",
//        "instance": "/patients" }
//    (Content-Type: application/problem+json). Every error of every service can
//    use the same shape, so clients need only one parser.
//
// 3) "extends ResponseEntityExceptionHandler": Spring's base class that already
//    turns ~15 framework exceptions into ProblemDetails with the right status
//    (malformed JSON -> 400, wrong HTTP method -> 405, wrong media type -> 415,
//    "abc" as UUID in the path -> 400 ...). We only add our own exceptions and
//    customise the validation response.
// =============================================================================

// PHASE 0 (old):
//@Slf4j
//@ControllerAdvice
//public class GlobalExceptionHandler {
//
//    @ExceptionHandler(MethodArgumentNotValidException.class)
//    public ResponseEntity<Map<String, String>> handleValidationException(MethodArgumentNotValidException ex) {
//        Map<String, String> errors = new HashMap<>();
//        ex.getBindingResult().getFieldErrors().forEach(error -> {
//            errors.put(error.getField(), error.getDefaultMessage());
//        });
//        return ResponseEntity.badRequest().body(errors);
//    }
//
//    @ExceptionHandler(EmailAlreadyExistException.class)
//    public ResponseEntity<Map<String, String>> handleEmailAlreadyExistException(EmailAlreadyExistException ex) {
//        log.warn("Email address already exists: {}", ex.getMessage());
//        Map<String, String> error = new HashMap<>();
//        error.put("Message", "Email already exists");
//        return ResponseEntity.badRequest().body(error);
//    }
//
//    @ExceptionHandler(PatientNotFoundException.class)
//    public ResponseEntity<Map<String, String>> handlePatientNotFoundException(PatientNotFoundException ex) {
//        log.warn("Patient not found: {}", ex.getMessage());
//        Map<String, String> error = new HashMap<>();
//        error.put("Message", "Patient not found");
//        return ResponseEntity.badRequest().body(error);
//    }
//}

@Slf4j
@RestControllerAdvice // = @ControllerAdvice + @ResponseBody: return values are written as JSON
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * Bean Validation failed (@NotBlank, @Email, @Past ... on PatientRequestDTO).
     * We override the base class method to add WHICH fields failed, because a
     * plain "Invalid request content" is useless for a form in a frontend.
     * Response: 400 + { ..., "errors": { "email": "Email should be valid" } }
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>(); // keeps field order stable
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        // setProperty adds extra, non-standard members to the JSON. RFC 9457 allows that.
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /** 404 - was 400 before (B4). */
    @ExceptionHandler(PatientNotFoundException.class)
    public ProblemDetail handlePatientNotFoundException(PatientNotFoundException ex) {
        // PHASE 0 (step 0.9 will keep it this way): log only the message, which
        // contains the patient ID - no personal data.
        log.warn("Patient not found: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Patient not found");
    }

    /** 409 - was 400 before (B4). Detected by our existsByEmail(...) check. */
    @ExceptionHandler(EmailAlreadyExistException.class)
    public ProblemDetail handleEmailAlreadyExistException(EmailAlreadyExistException ex) {
        // PHASE 0 (old): log.warn("Email address already exists: {}", ex.getMessage());
        // The old message contained the e-mail address = personal data in the logs.
        log.warn("Rejected patient request: email already exists");
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A patient with this email already exists");
    }

    /**
     * 409 for the RACE CONDITION (B11): two requests with the same email arrive
     * at the same time; both pass existsByEmail(...) because neither has saved
     * yet. The second INSERT then hits the UNIQUE constraint in PostgreSQL
     * (uk_patient_email) and Spring translates the SQL error into
     * DataIntegrityViolationException. Before this handler it became a 500.
     * Lesson: an "exists" check in Java is only a friendly early answer - the
     * database constraint is what really guarantees uniqueness.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Database constraint violated: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The request conflicts with existing data (e.g. email already exists)");
    }

    /**
     * PHASE 0 (step 0.7): 503 Service Unavailable when billing-service cannot be
     * reached or is too slow. 503 tells the client "temporary problem on the server
     * side, retrying later may work" - unlike 400 (your fault) or 500 (our bug).
     * The patient was NOT saved (transaction rolled back), so a retry is safe.
     */
    @ExceptionHandler(BillingServiceUnavailableException.class)
    public ProblemDetail handleBillingUnavailable(BillingServiceUnavailableException ex) {
        log.error("Patient creation aborted: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Billing service is temporarily unavailable. The patient was not created; please retry later.");
        return problem;
    }

    /**
     * 400 for an unknown sort field, e.g. GET /patients?sort=password.
     * Spring Data cannot map it to an entity property and throws
     * PropertyReferenceException - client error, not a server bug.
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ProblemDetail handleUnknownSortProperty(PropertyReferenceException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Unknown sort property: " + ex.getPropertyName());
    }
}
