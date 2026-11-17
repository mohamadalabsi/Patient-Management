package com.pm.analyticsservice.kafka;


import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import patient.events.PatientEvent;

@Service
public class KafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumer.class);


    @KafkaListener(topics = "patient")
    public void consumeEvent(byte[] event) {
        try {
            PatientEvent patientEvent = PatientEvent.parseFrom(event);
            // PHASE 0 (step 0.9): no personal data (PII) in logs (audit S7).
            // Logs are copied to many places (log files, log servers like Loki in Phase 5,
            // support tickets, screenshots) and are kept for a long time. For health data
            // (GDPR "special category") name + email of a patient must not end up there.
            // We log only technical IDs; the full record stays in the database, where
            // access is controlled.
            // PHASE 0 (old):
            // log.info("Received Patient Event : PatientId={} , PatientName={}, PatientEmail={}",
            //         patientEvent.getPatientId(), patientEvent.getName(), patientEvent.getEmail());
            log.info("Received patient event: type={}, patientId={}",
                    patientEvent.getEventType(), patientEvent.getPatientId());
        } catch (InvalidProtocolBufferException e) {
            log.error("Error deserializing event: {}", e.getMessage());
        }
    }
}
