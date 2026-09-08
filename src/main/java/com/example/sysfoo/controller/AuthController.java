package com.example.sysfoo.controller;

import com.example.sysfoo.model.User;
import com.example.sysfoo.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Session-based authentication endpoints for the Sysfoo dashboard.
 *
 * We authenticate manually (rather than relying on Spring Security's
 * form-login filter) so the frontend can POST plain JSON and receive a JSON
 * response, which is what a single-page dashboard needs.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private SecurityContextRepository securityContextRepository;

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body,
                                                          HttpServletRequest request,
                                                          HttpServletResponse response) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        String email = body.getOrDefault("email", "").trim();
        String displayName = body.getOrDefault("displayName", "").trim();

        if (username.length() < 3 || username.length() > 40) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Username must be 3-40 characters"));
        }
        if (!username.matches("^[a-zA-Z0-9_.-]+$")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Username may only contain letters, numbers, dots, dashes and underscores"));
        }
        if (password.length() < 6) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Password must be at least 6 characters"));
        }
        if (userRepository.existsByUsername(username)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "That username is already taken"));
        }
        if (!email.isBlank() && userRepository.existsByEmail(email)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "That email is already registered"));
        }

        User user = new User(
                username,
                passwordEncoder.encode(password),
                email.isBlank() ? null : email,
                displayName.isBlank() ? username : displayName
        );
        userRepository.save(user);

        // Auto sign-in right after registration for a smoother first-run experience.
        authenticateAndPersist(username, password, request, response);

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "username", user.getUsername(),
                "displayName", user.getDisplayName()
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                       HttpServletRequest request,
                                                       HttpServletResponse response) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");

        try {
            authenticateAndPersist(username, password, request, response);
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(401)
                    .body(Map.of("status", "error", "message", "Invalid username or password"));
        }

        User user = userRepository.findByUsername(username).orElse(null);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "username", username,
                "displayName", (user != null && user.getDisplayName() != null) ? user.getDisplayName() : username
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Logged out"));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Not signed in"));
        }
        String username = authentication.getName();
        User user = userRepository.findByUsername(username).orElse(null);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "username", username,
                "displayName", (user != null && user.getDisplayName() != null) ? user.getDisplayName() : username
        ));
    }

    /**
     * Authenticates the given credentials and, on success, persists the resulting
     * SecurityContext into the HTTP session so subsequent requests (carrying the
     * session cookie) are recognised as authenticated.
     */
    private void authenticateAndPersist(String username, String password,
                                         HttpServletRequest request, HttpServletResponse response) {
        Authentication authRequest = new UsernamePasswordAuthenticationToken(username, password);
        Authentication authResult = authenticationManager.authenticate(authRequest);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authResult);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
