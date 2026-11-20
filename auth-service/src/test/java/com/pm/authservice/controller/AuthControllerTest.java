package com.pm.authservice.controller;

import com.pm.authservice.config.SecurityConfig;
import com.pm.authservice.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 1 (step 1.9): NEW - HTTP contract of /login and /validate (@WebMvcTest slice).
 *
 * @Import(SecurityConfig.class): a web slice only scans controllers. Without our security
 * config, Spring Security's DEFAULT would apply (everything needs login + CSRF token on
 * POST) and every test would get 401/403 - testing a configuration production never uses.
 * The api-gateway depends on exactly these status codes (200 -> forward, 401 -> reject).
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    void loginWithValidCredentialsReturns200AndToken() throws Exception {
        when(authService.authenticate(any())).thenReturn(Optional.of("a.jwt.token"));

        mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"anna@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("a.jwt.token"));
    }

    @Test
    void loginWithWrongCredentialsReturns401() throws Exception {
        when(authService.authenticate(any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"anna@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithInvalidInputReturns400() throws Exception {
        // invalid email + password shorter than 8 -> rejected before any DB lookup
        mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(authService);
    }

    @Test
    void validateWithValidTokenReturns200() throws Exception {
        when(authService.validateToken("good.token")).thenReturn(true);

        mockMvc.perform(get("/validate").header("Authorization", "Bearer good.token"))
                .andExpect(status().isOk());
    }

    @Test
    void validateWithInvalidTokenReturns401() throws Exception {
        when(authService.validateToken("bad.token")).thenReturn(false);

        mockMvc.perform(get("/validate").header("Authorization", "Bearer bad.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validateWithoutBearerPrefixReturns401() throws Exception {
        mockMvc.perform(get("/validate").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(authService);
    }

    @Test
    void validateWithoutHeaderReturns400() throws Exception {
        // Documents CURRENT behaviour: @RequestHeader("Authorization") is required, so Spring
        // answers 400 (missing header) before our code runs. The gateway maps every 4xx
        // from auth to 401 for the client (Phase 0, step 0.8), so clients still see 401.
        mockMvc.perform(get("/validate"))
                .andExpect(status().isBadRequest());
    }
}
