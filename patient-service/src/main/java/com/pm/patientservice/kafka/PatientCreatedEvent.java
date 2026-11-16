package com.pm.patientservice.kafka;

import com.pm.patientservice.model.Patient;

/**
 * PHASE 0 (step 0.7): NEW - an in-memory Spring "application event".
 *
 * This is NOT the Kafka message. It is a small Java object that PatientService
 * publishes inside the JVM with ApplicationEventPublisher to say
 * "a patient was created in this transaction".
 *
 * PatientEventListener receives it, but - thanks to @TransactionalEventListener -
 * only AFTER the database transaction has committed successfully. Only then is
 * the real Kafka message sent. See PatientEventListener for the full reasoning.
 *
 * A Java "record" is a compact, immutable data class (constructor, getters,
 * equals/hashCode/toString are generated). Perfect for events: they describe
 * something that already happened and should never change.
 */
public record PatientCreatedEvent(Patient patient) {
}
