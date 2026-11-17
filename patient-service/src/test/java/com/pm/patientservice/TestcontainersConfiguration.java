package com.pm.patientservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PHASE 1 (step 1.2): NEW - the real infrastructure for tests that start the whole app.
 *
 * WHY
 * ---
 * The app needs PostgreSQL (Flyway + JPA) and Kafka to start. Before, the
 * contextLoads test tried to connect to localhost:5432 with no password and failed
 * (audit B7) - that is why every Dockerfile builds with -DskipTests.
 *
 * HOW
 * ---
 * - Each @Bean returns a Testcontainers container. Spring starts it before the app
 *   and stops it at the end of the test run.
 * - @ServiceConnection: Boot reads host / random port / user / password from the
 *   container and configures spring.datasource.* and spring.kafka.bootstrap-servers
 *   itself. No hard-coded ports -> tests never clash with the compose stack.
 * - Same images as docker-compose.yml (postgres:16, apache/kafka:4.3.2), so the tests
 *   run against the same versions as the real stack.
 * - Every test class that uses @Import(TestcontainersConfiguration.class) with the same
 *   setup shares ONE Spring context and therefore ONE pair of containers
 *   (Spring's test-context cache). That keeps the suite fast.
 *
 * Flyway runs the real migrations against the container and Hibernate runs
 * ddl-auto=validate - so starting the app also proves entities and migrations match.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16"));
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.2"));
    }
}
