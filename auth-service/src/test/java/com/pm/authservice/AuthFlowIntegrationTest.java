package com.pm.authservice;

import com.pm.authservice.model.User;
import com.pm.authservice.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 1 (step 1.9): NEW - integration test: real app + real PostgreSQL (Flyway schema).
 *
 * Login -> token -> validate, all real (BCrypt, JPA, JWT). Also proves the Phase 0 rule
 * (step 0.5) that the test admin from db/dev-data is NOT created unless docker-compose
 * enables that location: production starts with an empty users table.
 */
@SpringBootTest(properties = "jwt.secret=" + TestcontainersConfiguration.TEST_JWT_SECRET)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void devTestUserIsNotSeededOutsideDevCompose() {
        assertThat(userRepository.findByEmail("testuser@test.com")).isEmpty();
    }

    @Test
    void registeredUserCanLogInAndTheTokenValidates() throws Exception {
        String email = "it-" + UUID.randomUUID() + "@example.com";
        userRepository.save(new User(null, email, passwordEncoder.encode("password123"), "ADMIN"));

        String body = mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/validate").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void wrongPasswordReturns401() throws Exception {
        String email = "it-" + UUID.randomUUID() + "@example.com";
        userRepository.save(new User(null, email, passwordEncoder.encode("password123"), "USER"));

        mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordIsStoredAsBcryptHashNotPlainText() {
        String email = "it-" + UUID.randomUUID() + "@example.com";
        userRepository.save(new User(null, email, passwordEncoder.encode("password123"), "USER"));

        String stored = userRepository.findByEmail(email).orElseThrow().getPassword();
        assertThat(stored).isNotEqualTo("password123").startsWith("$2a$");
    }
}
