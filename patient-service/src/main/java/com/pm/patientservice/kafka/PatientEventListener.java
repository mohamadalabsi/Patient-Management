package com.pm.patientservice.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * PHASE 0 (step 0.7): NEW - sends the Kafka event only AFTER the DB commit.
 *
 * THE DUAL-WRITE PROBLEM (audit B1)
 * ---------------------------------
 * Creating a patient writes to TWO systems: PostgreSQL and Kafka. There is no
 * transaction that spans both. Before this change the order was:
 *     save patient -> call billing -> send Kafka event
 * with NO transaction around it. If billing failed, the patient row was already
 * saved (orphan without billing account, retry blocked by "email exists"). And
 * if we had wrapped it in a transaction but sent to Kafka inside it, a later
 * rollback would leave a Kafka message announcing a patient that does not exist.
 *
 * WHAT THIS CLASS CHANGES
 * -----------------------
 * PatientService publishes PatientCreatedEvent inside its transaction.
 * @TransactionalEventListener(phase = AFTER_COMMIT) holds the event back and calls
 * this method only if the transaction COMMITTED. On rollback the event is simply
 * dropped. So consumers never hear about a patient that is not in the database.
 *
 * THE GAP THAT REMAINS (honestly)
 * -------------------------------
 * If the application crashes (or Kafka is down) right after the commit but before
 * the send succeeds, the patient exists but the event is lost. The commit and the
 * send are still two separate steps. The real fix is the "Transactional Outbox"
 * pattern (Phase 4): write the event into an outbox TABLE in the SAME DB
 * transaction, and a separate process forwards it to Kafka with retries.
 */
@Slf4j
@Component
public class PatientEventListener {

    private final KafkaProducer kafkaProducer;

    public PatientEventListener(KafkaProducer kafkaProducer) {
        this.kafkaProducer = kafkaProducer;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPatientCreated(PatientCreatedEvent event) {
        kafkaProducer.sendEvent(event.patient());
    }
}
