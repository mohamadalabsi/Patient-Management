package com.pm.patientservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PHASE 1 (step 1.5): NEW - only the PostgreSQL container, split out of
 * TestcontainersConfiguration so that the repository tests (@DataJpaTest) do not also
 * start a Kafka broker they never use (~5 s saved per run).
 * TestcontainersConfiguration imports this class and adds Kafka on top.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16"));
    }
}
