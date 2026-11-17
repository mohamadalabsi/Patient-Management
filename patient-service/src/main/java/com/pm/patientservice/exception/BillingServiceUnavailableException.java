package com.pm.patientservice.exception;

/**
 * PHASE 0 (step 0.7): NEW.
 *
 * Thrown when the billing gRPC call fails (billing down, too slow, internal error).
 * Mapped to HTTP 503 Service Unavailable in GlobalExceptionHandler.
 *
 * Why our own exception instead of letting io.grpc.StatusRuntimeException bubble up?
 *  - The web layer should not need to know that billing is called via gRPC
 *    (tomorrow it could be REST or Kafka). Each layer speaks its own "language".
 *  - It is a RuntimeException, so @Transactional rolls back the patient INSERT
 *    when it is thrown (Spring rolls back on unchecked exceptions by default).
 */
public class BillingServiceUnavailableException extends RuntimeException {
    public BillingServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
