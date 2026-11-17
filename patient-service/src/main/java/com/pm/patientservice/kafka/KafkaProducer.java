package com.pm.patientservice.kafka;

import com.pm.patientservice.model.Patient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import patient.events.PatientEvent;

@Slf4j
@Service
public class KafkaProducer {

    // PHASE 0 (step 0.7): NEW constant - the topic name was a string literal inline.
    static final String PATIENT_TOPIC = "patient";

    // PHASE 0 (step 0.7): this KafkaTemplate is now created by Spring Boot's
    // auto-configuration from application.yml (spring.kafka.*), not by our own
    // KafkaProducerConfig class (now disabled) - one place for Kafka settings.
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    public KafkaProducer(KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    // PHASE 0 (old):
    // public void sendEvent(Patient patient) {
    //     PatientEvent event = PatientEvent.newBuilder()
    //             .setPatientId(patient.getId().toString())
    //             .setName(patient.getName())
    //             .setEmail(patient.getEmail())
    //             .setEventType("PATIENT_CREATED")
    //             .build();
    //
    //     try {
    //         kafkaTemplate.send("patient", event.toByteArray());
    //         log.info("Event sent to kafka topic: " + event);
    //     } catch (Exception e) {
    //         log.error("Error while sending PatientCreated event : {}", event);
    //     }
    // }
    public void sendEvent(Patient patient) {
        String patientId = patient.getId().toString();
        PatientEvent event = PatientEvent.newBuilder()
                .setPatientId(patientId)
                .setName(patient.getName())
                .setEmail(patient.getEmail())
                .setEventType("PATIENT_CREATED")
                .build();

        // PHASE 0 (step 0.7): two fixes (audit B2).
        //
        // 1) MESSAGE KEY = patient id. Kafka splits a topic into partitions and only
        //    guarantees ORDER within one partition. The key decides the partition
        //    (hash(key) % partitions). Same patient id -> same partition -> later
        //    events for one patient (CREATED, UPDATED, DELETED in Phase 4) are always
        //    consumed in the order they were sent. Without a key (old code) messages
        //    are spread across partitions and can overtake each other.
        //
        // 2) send() is ASYNCHRONOUS: it puts the record in a buffer and returns a
        //    CompletableFuture immediately; the network send happens later in a
        //    background thread. The old try/catch therefore only caught errors that
        //    happen while BUFFERING - a broker rejection or timeout came later and was
        //    silently lost. whenComplete(...) runs when Kafka has actually answered,
        //    so we finally see success (with partition/offset) or the real failure.
        //
        // PHASE 0 (step 0.9): log the patient id only, never name/email (PII).
        try {
            kafkaTemplate.send(PATIENT_TOPIC, patientId, event.toByteArray())
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.info("PATIENT_CREATED event for patient {} written to {}-{} at offset {}",
                                    patientId,
                                    result.getRecordMetadata().topic(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        } else {
                            log.error("Kafka rejected PATIENT_CREATED event for patient {}: {}",
                                    patientId, ex.getMessage());
                        }
                    });
        } catch (Exception e) {
            // Still possible synchronously, e.g. the producer cannot get topic metadata
            // within max.block.ms (broker down). We log instead of failing the HTTP
            // request: the patient is already committed at this point (see
            // PatientEventListener). Phase 4 (outbox) will retry instead of only logging.
            log.error("Could not send PATIENT_CREATED event for patient {}: {}", patientId, e.getMessage());
        }
    }
}
