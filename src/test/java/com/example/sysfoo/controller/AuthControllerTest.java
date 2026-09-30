package com.example.sysfoo.controller;

import com.example.sysfoo.repository.UserRepository;
import com.example.sysfoo.security.RateLimiter;
import com.example.sysfoo.service.AccountSecurityService;
import com.example.sysfoo.service.PasswordPolicyService;
import com.example.sysfoo.service.RecaptchaService;
import com.example.sysfoo.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Security filters are disabled here — this slice test only exercises
// AuthController's own request validation, not the security filter chain.
//
// ENHANCEMENT: AuthController now also depends on SessionAuthenticationStrategy/
// SessionRegistry (session management — see SecurityConfig), RateLimiter
// (login/register throttling), AccountSecurityService (persisted lockout),
// PasswordPolicyService (breach/complexity checks) and RecaptchaService —
// all mocked here so the @WebMvcTest slice context has something to wire
// in. setUp() stubs the two that default to a value which would otherwise
// block every request (rate limiter and CAPTCHA both default their boolean
// return to false when unstubbed, which reads as "blocked").
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
public class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private PasswordEncoder passwordEncoder;

    @MockBean
    private AuthenticationManager authenticationManager;

    @MockBean
    private SecurityContextRepository securityContextRepository;

    @MockBean
    private SessionAuthenticationStrategy sessionAuthenticationStrategy;

    @MockBean
    private SessionRegistry sessionRegistry;

    @MockBean
    private RateLimiter rateLimiter;

    @MockBean
    private AccountSecurityService accountSecurityService;

    @MockBean
    private PasswordPolicyService passwordPolicyService;

    @MockBean
    private RecaptchaService recaptchaService;

    @MockBean
    private TokenService tokenService;

    @BeforeEach
    public void setUp() {
        // Let requests reach AuthController's own logic by default — see
        // class javadoc on why these two specifically need a default stub.
        when(rateLimiter.allow(any(), anyInt(), anyLong())).thenReturn(true);
        when(recaptchaService.verify(any(), any())).thenReturn(true);
    }

    @Test
    public void registerRejectsWeakPassword() throws Exception {
        // PasswordPolicyService is mocked (not the real implementation) —
        // this test is verifying AuthController correctly rejects
        // registration when the policy says no, not re-testing the policy's
        // own rules (see PasswordPolicyServiceTest for that).
        when(passwordPolicyService.validate(eq("123"), eq("simba")))
                .thenReturn(Optional.of("Password must be at least 10 characters"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"simba\",\"password\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    public void registerRejectsDuplicateUsername() throws Exception {
        when(userRepository.existsByUsername("simba")).thenReturn(true);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"simba\",\"password\":\"longenough1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That username is already taken"));
    }

    @Test
    public void registerRejectsCaptchaFailure() throws Exception {
        when(recaptchaService.verify(any(), any())).thenReturn(false);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"simba\",\"password\":\"longenough1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("CAPTCHA verification failed — please try again"));
    }

    @Test
    public void loginRejectsWhenRateLimited() throws Exception {
        when(rateLimiter.allow(eq("login:127.0.0.1"), anyInt(), anyLong())).thenReturn(false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"simba\",\"password\":\"whatever\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
