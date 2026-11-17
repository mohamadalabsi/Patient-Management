package com.pm.patientservice.kafka;

// =============================================================================
// PHASE 0 (step 0.7): DISABLED - kept only as a commented-out record (audit A4).
//
// This class built the ProducerFactory and KafkaTemplate by hand, while
// application.yml ALSO configured the producer serializers. Two places for the
// same settings: the Java class silently won, so changing application.yml had no
// effect - very confusing.
//
// It was needed because the project used plain spring-kafka, which in Boot 4 has
// no auto-configuration. Since step 0.2 we use spring-boot-starter-kafka, so
// Spring Boot creates the KafkaTemplate itself from spring.kafka.* in
// application.yml (bootstrap-servers, key/value serializers ...). One place.
// =============================================================================
// PHASE 0 (old):
//
//import org.apache.kafka.clients.producer.ProducerConfig;
//import org.apache.kafka.common.serialization.ByteArraySerializer;
//import org.apache.kafka.common.serialization.StringSerializer;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.kafka.core.DefaultKafkaProducerFactory;
//import org.springframework.kafka.core.KafkaTemplate;
//import org.springframework.kafka.core.ProducerFactory;
//
//import java.util.HashMap;
//import java.util.Map;
//
//@Configuration
//public class KafkaProducerConfig {
//
//    @Value("${spring.kafka.bootstrap-servers}")
//    private String bootstrapServers;
//
//    @Bean
//    public ProducerFactory<String, byte[]> producerFactory() {
//        Map<String, Object> configProps = new HashMap<>();
//        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
//        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
//        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
//        return new DefaultKafkaProducerFactory<>(configProps);
//    }
//
//    @Bean
//    public KafkaTemplate<String, byte[]> kafkaTemplate() {
//        return new KafkaTemplate<>(producerFactory());
//    }
//}
