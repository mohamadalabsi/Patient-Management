package com.pm.patientservice;

import com.google.protobuf.InvalidProtocolBufferException;
import com.pm.patientservice.grpc.FakeBillingServer;
import com.pm.patientservice.repository.PatientRepo;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import patient.events.PatientEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 1 (step 1.6): NEW - INTEGRATION test of the patient-creation flow (Phase 0, step 0.7).
 *
 * Everything is real except billing's business logic:
 *   HTTP (MockMvc) -> controller -> PatientService (@Transactional) -> PostgreSQL container
 *                                                   -> gRPC -> FakeBillingServer
 *                    after commit -> KafkaProducer -> Kafka container -> our test consumer
 *
 * The unit test (PatientServiceTest) checked the ORDER of calls with mocks. Only this
 * test can prove what Phase 0 promised:
 *   - billing fails/too slow -> the INSERT is really ROLLED BACK (0 rows) and the client gets 503
 *   - the Kafka event is sent only AFTER the commit, so on rollback there is NO event
 *   - the event is keyed by patient id (ordering per patient)
 *
 * Naming: "...IntegrationTest". Maven Surefire (the plugin behind "mvn test") only runs
 * classes ending in Test / Tests / TestCase. A first version was called
 * "PatientCreationFlowIT" - the usual suffix for the separate Failsafe plugin - and was
 * silently NOT run by "mvn test". The name keeps one simple "mvn test" for everything;
 * "Integration" tells the reader "slower, needs Docker".
 *
 * PHASE 3 (step 3.2): the endpoints now need permissions. @WithMockUser puts a caller with
 * these authorities into the security context of every test, so this class keeps testing
 * the creation FLOW. Tokens and permission rules are tested in PatientSecurityTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@WithMockUser(authorities = {"read:patients", "write:patients", "delete:patients"})
class PatientCreationFlowIntegrationTest {

    /**
     * Deadline used in the tests. PHASE 1 (step 1.12): 0.5 s -> 2 s. The FIRST gRPC call in a
     * fresh JVM also opens the connection and loads classes; with 0.5 s it sometimes ran
     * into the deadline although billing answered at once (flaky - found while
     * mutation-testing the suite). 2 s is still far below the fake's 10 s "slow" answer.
     */
    // PHASE 1 (old): private static final Duration BILLING_DEADLINE = Duration.ofMillis(500);
    private static final Duration BILLING_DEADLINE = Duration.ofSeconds(2);

    /**
     * Started ONCE, before Spring: its random port must be known when the context is created.
     * static = shared by all tests of this class (like the containers).
     */
    private static final Duration SLOW_BILLING = Duration.ofSeconds(10);
    private static final FakeBillingServer BILLING = new FakeBillingServer(SLOW_BILLING.toMillis()).start();

    /** Points BillingServiceGrpcClient at the fake server (properties computed at runtime). */
    @DynamicPropertySource
    static void billingProperties(DynamicPropertyRegistry registry) {
        registry.add("billing.service.address", () -> "localhost");
        registry.add("billing.service.port", BILLING::port);
        registry.add("billing.service.deadline", BILLING_DEADLINE::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PatientRepo patientRepo;

    /** The container is a Spring bean (TestcontainersConfiguration) - we read its address. */
    @Autowired
    private KafkaContainer kafka;

    private KafkaConsumer<String, byte[]> consumer;

    @BeforeEach
    void setUp() {
        BILLING.behave(FakeBillingServer.Mode.OK);
        // A fresh consumer group per test reads the topic from the beginning; each test
        // looks only for ITS OWN unique email, so tests never see each other's events.
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(List.of("patient"));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void happyPathSavesPatientCallsBillingAndPublishesOneEventKeyedById() throws Exception {
        String email = uniqueEmail();

        String body = mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(json(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // 1. row committed
        assertThat(patientRepo.existsByEmail(email)).isTrue();
        // 2. billing was called with the new id (proto request built correctly)
        assertThat(BILLING.requests()).anySatisfy(r -> assertThat(r.getPatientId()).isEqualTo(id));
        // 3. exactly one event for this patient, key = patient id, payload = protobuf PatientEvent
        List<ConsumerRecord<String, byte[]>> events = awaitEventsFor(email, 1);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().key()).isEqualTo(id);
        PatientEvent event = PatientEvent.parseFrom(events.getFirst().value());
        assertThat(event.getPatientId()).isEqualTo(id);
        assertThat(event.getEventType()).isEqualTo("PATIENT_CREATED");
    }

    @Test
    void billingUnavailableRollsBackAndPublishesNothing() throws Exception {
        BILLING.behave(FakeBillingServer.Mode.UNAVAILABLE);
        String email = uniqueEmail();

        mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(json(email)))
                .andExpect(status().isServiceUnavailable());

        // The row WAS inserted (saveAndFlush) before billing failed - the rollback removed it.
        assertThat(patientRepo.existsByEmail(email)).isFalse();
        // AFTER_COMMIT listener never ran, because there was no commit.
        assertNoEventFor(email);
    }

    @Test
    void billingSlowerThanDeadlineFailsFastAndRollsBack() throws Exception {
        BILLING.behave(FakeBillingServer.Mode.SLOW); // answers after 10 s, deadline is 2 s
        String email = uniqueEmail();

        long start = System.nanoTime();
        mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(json(email)))
                .andExpect(status().isServiceUnavailable());
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        // Without a deadline the request would hang as long as billing hangs (10 s).
        // PHASE 1 (step 1.6): the limit is deliberately generous (5 s, not "0.5 s + a bit"):
        // the first request in a fresh JVM also pays one-off warm-up costs (Hibernate,
        // gRPC connection, JIT) - a tight limit made this test fail when it ran first.
        // PHASE 1 (old): assertThat(took).isLessThan(Duration.ofMillis(1_500));
        // PHASE 1 (old, with 0.5 s deadline): assertThat(took).isLessThan(Duration.ofSeconds(5));
        assertThat(took).isLessThan(Duration.ofSeconds(6));
        assertThat(patientRepo.existsByEmail(email)).isFalse();
        assertNoEventFor(email);
    }

    @Test
    void retryAfterBillingRecoveredSucceeds() throws Exception {
        String email = uniqueEmail();
        BILLING.behave(FakeBillingServer.Mode.UNAVAILABLE);
        mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(json(email)))
                .andExpect(status().isServiceUnavailable());

        BILLING.behave(FakeBillingServer.Mode.OK);

        // Before Phase 0 the half-saved row blocked this retry with "email already exists".
        mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(json(email)))
                .andExpect(status().isCreated());
        assertThat(awaitEventsFor(email, 1)).hasSize(1);
    }

    @Test
    void secondCreateWithSameEmailReturns409() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(json(email)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(json(email)))
                .andExpect(status().isConflict());
    }

    @Test
    void sortingByUnknownPropertyReturns400() throws Exception {
        mockMvc.perform(get("/patients").param("sort", "password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown sort property: password"));
    }

    // ---- helpers ---------------------------------------------------------------------

    private List<ConsumerRecord<String, byte[]>> awaitEventsFor(String email, int expected) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        // Awaitility: poll until the condition is true or fail after 15 s.
        // No Thread.sleep: the test continues as soon as the event is there.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            consumer.poll(Duration.ofMillis(250)).forEach(r -> {
                if (email.equals(emailOf(r))) {
                    found.add(r);
                }
            });
            assertThat(found).hasSizeGreaterThanOrEqualTo(expected);
        });
        return found;
    }

    /**
     * Proving that something did NOT happen needs a time window: we read the topic for 3 s.
     * An after-commit send would arrive within milliseconds, so 3 s is a safe margin.
     */
    private void assertNoEventFor(String email) {
        long end = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < end) {
            consumer.poll(Duration.ofMillis(250))
                    .forEach(r -> assertThat(emailOf(r)).as("event for rolled-back patient").isNotEqualTo(email));
        }
    }

    private static String emailOf(ConsumerRecord<String, byte[]> record) {
        try {
            return PatientEvent.parseFrom(record.value()).getEmail();
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
    }

    /** Unique per test -> tests are independent of each other and of execution order. */
    private static String uniqueEmail() {
        return "it-" + UUID.randomUUID() + "@example.com";
    }

    private static String json(String email) {
        return """
                {"name": "Flow Test", "email": "%s", "address": "Main Street 1",
                 "birthDate": "1990-01-15", "registrationDate": "2025-01-01"}
                """.formatted(email);
    }
}
