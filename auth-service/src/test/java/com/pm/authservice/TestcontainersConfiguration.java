package com.pm.authservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PHASE 1 (step 1.2): NEW - a real PostgreSQL 16 for tests that start the whole app.
 *
 * @ServiceConnection lets Boot configure spring.datasource.* from the container
 * (random port, generated user/password). Flyway runs only db/migration here, NOT the
 * dev-data folder (that is enabled only by SPRING_FLYWAY_LOCATIONS in docker-compose),
 * so the test database starts with 0 users - exactly like production.
 * See patient-service's TestcontainersConfiguration for the full explanation.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * JWT_SECRET comes from .env in the real stack. Tests must not need .env, so they use
     * this throwaway key (Base64 of 32 bytes = 256 bits, the minimum for HS256).
     * It protects nothing - it only exists inside the test JVM.
     */
    public static final String TEST_JWT_SECRET = "dGVzdC1vbmx5LXNlY3JldC10ZXN0LW9ubHktc2VjcmV0IQ==";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16"));
    }
}
