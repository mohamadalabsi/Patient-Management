package com.pm.analyticsservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PHASE 1 (step 1.2): NEW - a real Kafka broker (same image as docker-compose) for tests.
 * @ServiceConnection sets spring.kafka.bootstrap-servers to the container's random port.
 * See patient-service's TestcontainersConfiguration for the full explanation.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.2"));
    }
}
