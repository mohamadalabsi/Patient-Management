package com.pm.authservice.service;

import com.pm.authservice.dto.LoginRequestDTO;
import com.pm.authservice.model.User;
import com.pm.authservice.util.JwtUtil;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PHASE 1 (step 1.9): NEW - unit tests for the login rules.
 *
 * UserService and JwtUtil are mocks, but the PasswordEncoder is the REAL BCrypt one:
 * mocking "matches(...) -> true" would test nothing about password checking.
 * Rule of thumb: mock what is slow or external, keep cheap pure logic real.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Mock
    private UserService userService;
    @Mock
    private JwtUtil jwtUtil;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userService, passwordEncoder, jwtUtil);
    }

    @Test
    void correctPasswordReturnsToken() {
        when(userService.findByEmail("anna@example.com")).thenReturn(Optional.of(user("password123")));
        when(jwtUtil.generateToken("anna@example.com", "ADMIN")).thenReturn("a.jwt.token");

        assertThat(authService.authenticate(login("anna@example.com", "password123"))).contains("a.jwt.token");
    }

    @Test
    void wrongPasswordReturnsEmptyAndIssuesNoToken() {
        when(userService.findByEmail("anna@example.com")).thenReturn(Optional.of(user("password123")));

        assertThat(authService.authenticate(login("anna@example.com", "wrong-password"))).isEmpty();
        verify(jwtUtil, never()).generateToken(anyString(), anyString());
    }

    @Test
    void unknownUserReturnsEmpty() {
        // Same result as a wrong password: the client cannot find out which emails exist.
        when(userService.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThat(authService.authenticate(login("nobody@example.com", "password123"))).isEmpty();
    }

    @Test
    void validateTokenTrueForValidToken() {
        assertThat(authService.validateToken("good")).isTrue();
    }

    @Test
    void validateTokenFalseWhenJwtUtilRejects() {
        doThrow(new JwtException("invalid jwt")).when(jwtUtil).validateToken("bad");

        assertThat(authService.validateToken("bad")).isFalse();
    }

    private User user(String rawPassword) {
        // the DB stores only the BCrypt HASH, never the password itself
        return new User(UUID.randomUUID(), "anna@example.com", passwordEncoder.encode(rawPassword), "ADMIN");
    }

    private static LoginRequestDTO login(String email, String password) {
        LoginRequestDTO dto = new LoginRequestDTO();
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
    }
}
