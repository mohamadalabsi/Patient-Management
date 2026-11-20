package com.pm.analyticsservice.kafka;

import com.pm.analyticsservice.TestcontainersConfiguration;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;
import patient.events.PatientEvent;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * PHASE 1 (step 1.8): NEW - the analytics consumer against a REAL Kafka broker.
 *
 * The test plays patient-service: it writes a protobuf PatientEvent to topic "patient"
 * (key = patient id, like KafkaProducer in patient-service). The @KafkaListener in
 * analytics runs on its OWN thread, so the result arrives asynchronously.
 *
 * HOW WE OBSERVE THE CONSUMER
 * The consumer's only effect today is a log line (real analytics come later).
 * OutputCaptureExtension captures everything written to the console during the test,
 * so we can assert on that log line. Awaitility polls until it appears (max 20 s) -
 * never Thread.sleep, which is either too short (flaky) or too long (slow).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class KafkaConsumerIntegrationTest {

    @Autowired
    private KafkaContainer kafka;

    private KafkaProducer<String, byte[]> producer;

    @BeforeEach
    void createProducer() {
        producer = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()),
                new StringSerializer(), new ByteArraySerializer());
    }

    @AfterEach
    void closeProducer() {
        producer.close();
    }

    @Test
    void consumesAndParsesPatientCreatedEvent(CapturedOutput output) throws Exception {
        String patientId = UUID.randomUUID().toString();

        send(patientId, event(patientId, "secret.person@example.com").toByteArray());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(output.getOut()).contains("type=PATIENT_CREATED, patientId=" + patientId));
        // Phase 0 (step 0.9): analytics must log IDs only - never the patient's name/email
        assertThat(output.getOut()).doesNotContain("secret.person@example.com").doesNotContain("Secret Person");
    }

    @Test
    void malformedMessageDoesNotStopTheConsumer(CapturedOutput output) throws Exception {
        // 0x0A = "field 1, length-delimited", 0xFF = a length that never ends -> invalid protobuf
        send("broken", new byte[]{0x0A, (byte) 0xFF});
        String patientId = UUID.randomUUID().toString();
        send(patientId, event(patientId, "x@example.com").toByteArray());

        // The bad message is logged and skipped; the NEXT valid event is still processed.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(output.getOut()).contains("Error deserializing event");
            assertThat(output.getOut()).contains("patientId=" + patientId);
        });
        // NOTE: the bad message is only logged and then lost. Keeping it in a dead-letter
        // topic (DLT) for later inspection is Phase 4.
    }

    private void send(String key, byte[] value) throws Exception {
        producer.send(new ProducerRecord<>("patient", key, value)).get(); // wait for the broker's ack
    }

    private static PatientEvent event(String patientId, String email) {
        return PatientEvent.newBuilder()
                .setPatientId(patientId)
                .setName("Secret Person")
                .setEmail(email)
                .setEventType("PATIENT_CREATED")
                .build();
    }
}
