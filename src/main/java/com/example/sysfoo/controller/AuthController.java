package com.example.sysfoo.controller;

import com.example.sysfoo.model.User;
import com.example.sysfoo.model.VerificationToken;
import com.example.sysfoo.repository.UserRepository;
import com.example.sysfoo.security.RateLimiter;
import com.example.sysfoo.service.AccountSecurityService;
import com.example.sysfoo.service.PasswordPolicyService;
import com.example.sysfoo.service.RecaptchaService;
import com.example.sysfoo.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Session-based authentication endpoints for the Sysfoo dashboard.
 *
 * We authenticate manually (rather than relying on Spring Security's
 * form-login filter) so the frontend can POST plain JSON and receive a JSON
 * response, which is what a single-page dashboard needs.
 *
 * ── SECURITY FIXES in this version ───────────────────────────────────────
 *   - Rate limiting on /login and /register, by IP (see RateLimiter).
 *   - Persisted per-account lockout after repeated bad passwords (see
 *     AccountSecurityService) — independent of the IP rate limit above.
 *   - Stronger password policy + breach-database check on registration and
 *     password reset (see PasswordPolicyService).
 *   - Optional CAPTCHA on registration (see RecaptchaService — a no-op
 *     until an operator configures a reCAPTCHA secret key).
 *   - Email verification links + forgot/reset-password flow (see
 *     TokenService), both using single-use, expiring tokens.
 *   - "Sign out everywhere" (logout-everywhere), backed by SessionRegistry.
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

    @Autowired
    private SessionAuthenticationStrategy sessionAuthenticationStrategy;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private AccountSecurityService accountSecurityService;

    @Autowired
    private PasswordPolicyService passwordPolicyService;

    @Autowired
    private RecaptchaService recaptchaService;

    @Autowired
    private TokenService tokenService;

    @org.springframework.beans.factory.annotation.Value("${app.security.recaptcha.site-key:}")
    private String recaptchaSiteKey;

    /**
     * Public, unauthenticated config the login page needs before anyone has
     * signed in — right now just whether CAPTCHA is turned on and, if so,
     * the PUBLIC site key (the secret key never leaves the server; see
     * RecaptchaService). login.html/index.html are plain static files with
     * no server-side templating, so this is how a value from
     * application.properties reaches client-side JS.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        return ResponseEntity.ok(Map.of(
                "recaptchaEnabled", recaptchaSiteKey != null && !recaptchaSiteKey.isBlank(),
                "recaptchaSiteKey", recaptchaSiteKey == null ? "" : recaptchaSiteKey
        ));
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body,
                                                          HttpServletRequest request,
                                                          HttpServletResponse response) {
        String ip = clientIp(request);
        if (!rateLimiter.allow("register:" + ip, 5, 3600)) {
            return ResponseEntity.status(429)
                    .body(Map.of("status", "error", "message", "Too many registration attempts from this address. Please try again later."));
        }

        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        String email = body.getOrDefault("email", "").trim();
        String displayName = body.getOrDefault("displayName", "").trim();
        String captchaToken = body.getOrDefault("captchaToken", "");

        if (!recaptchaService.verify(captchaToken, ip)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "CAPTCHA verification failed — please try again"));
        }
        if (username.length() < 3 || username.length() > 40) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Username must be 3-40 characters"));
        }
        if (!username.matches("^[a-zA-Z0-9_.-]+$")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Username may only contain letters, numbers, dots, dashes and underscores"));
        }
        var passwordError = passwordPolicyService.validate(password, username);
        if (passwordError.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", passwordError.get()));
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
        // Administrative promotion is not auto-granted on first registration.
        // Every account created through the normal register flow stays a
        // default MEMBER and can be promoted later by an existing ADMIN.
        user.setRole(User.ROLE_MEMBER);
        userRepository.save(user);

        // Auto sign-in right after registration for a smoother first-run experience.
        try {
            authenticateAndPersist(username, password, request, response);
        } catch (SessionAuthenticationException e) {
            // Extremely unlikely on a brand-new account with no other sessions,
            // but don't fail the whole registration over it if it happens.
        }

        boolean verificationSent = false;
        if (!email.isBlank() && tokenService.isMailConfigured()) {
            tokenService.issueEmailVerificationToken(user);
            verificationSent = true;
        }

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "username", user.getUsername(),
                "displayName", user.getDisplayName(),
                "verificationEmailSent", verificationSent,
                "role", user.getRole(),
                "isAdmin", user.isAdmin()
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                       HttpServletRequest request,
                                                       HttpServletResponse response) {
        String ip = clientIp(request);
        if (!rateLimiter.allow("login:" + ip, 10, 60)) {
            return ResponseEntity.status(429)
                    .body(Map.of("status", "error", "message", "Too many login attempts. Please wait a minute and try again."));
        }

        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");

        User existingUser = userRepository.findByUsername(username).orElse(null);
        if (existingUser != null && existingUser.isAccountLocked()) {
            long minutesLeft = Math.max(1, ChronoUnit.MINUTES.between(LocalDateTime.now(), existingUser.getLockedUntil()));
            return ResponseEntity.status(423)
                    .body(Map.of("status", "error", "message",
                            "This account is temporarily locked after too many failed attempts. Try again in " + minutesLeft + " minute(s)."));
        }

        if (existingUser != null && existingUser.isPasswordChangeRequired()) {
            boolean passwordMatchesDefault = passwordEncoder.matches(password, existingUser.getPassword());
            if (passwordMatchesDefault) {
                return ResponseEntity.status(403)
                        .body(Map.of(
                                "status", "error",
                                "message", "Password change required before continuing. Please update your password first.",
                                "passwordChangeRequired", true
                        ));
            }
        }

        try {
            authenticateAndPersist(username, password, request, response);
        } catch (LockedException e) {
            return ResponseEntity.status(423)
                    .body(Map.of("status", "error", "message", "This account is temporarily locked. Please try again later."));
        } catch (BadCredentialsException e) {
            if (existingUser != null) {
                accountSecurityService.recordFailedAttempt(existingUser);
            }
            return ResponseEntity.status(401)
                    .body(Map.of("status", "error", "message", "Invalid username or password"));
        } catch (SessionAuthenticationException e) {
            return ResponseEntity.status(429)
                    .body(Map.of("status", "error", "message", "Too many active sessions for this account. Please sign out elsewhere and try again."));
        }

        if (existingUser != null) {
            accountSecurityService.recordSuccessfulLogin(existingUser);
        }

        User user = userRepository.findByUsername(username).orElse(null);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "username", username,
                "displayName", (user != null && user.getDisplayName() != null) ? user.getDisplayName() : username,
                "role", (user != null) ? user.getRole() : User.ROLE_MEMBER,
                "isAdmin", user != null && user.isAdmin()
        ));
    }

    @PostMapping("/force-change-password")
    public ResponseEntity<Map<String, String>> forceChangePassword(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String oldPassword = body.getOrDefault("oldPassword", "");
        String newPassword = body.getOrDefault("newPassword", "");

        if (username.isBlank() || oldPassword.isBlank() || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Username, current password and new password are required."));
        }

        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Invalid username or password."));
        }
        if (!user.isPasswordChangeRequired()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "This account does not need a password reset."));
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Current password is incorrect."));
        }

        var passwordError = passwordPolicyService.validate(newPassword, user.getUsername());
        if (passwordError.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", passwordError.get()));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangeRequired(false);
        userRepository.save(user);
        accountSecurityService.recordSuccessfulLogin(user);

        return ResponseEntity.ok(Map.of("status", "ok", "message", "Password updated successfully. You can now sign in with your new password."));
    }
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Logged out"));
    }

    /**
     * SECURITY FEATURE: invalidates every OTHER active session for the
     * signed-in account (e.g. "I left myself logged in on a shared/public
     * computer"), leaving the caller's own current session intact.
     */
    @PostMapping("/logout-everywhere")
    public ResponseEntity<Map<String, Object>> logoutEverywhere(Authentication authentication, HttpServletRequest request) {
        String username = authentication.getName();
        HttpSession currentSession = request.getSession(false);
        String currentSessionId = currentSession != null ? currentSession.getId() : null;

        List<SessionInformation> sessions = sessionRegistry.getAllPrincipals().stream()
                .filter(p -> username.equals(p instanceof UserDetails ud ? ud.getUsername() : String.valueOf(p)))
                .flatMap(p -> sessionRegistry.getAllSessions(p, false).stream())
                .collect(Collectors.toList());

        int expiredCount = 0;
        for (SessionInformation info : sessions) {
            if (!info.getSessionId().equals(currentSessionId)) {
                info.expireNow();
                expiredCount++;
            }
        }

        return ResponseEntity.ok(Map.of("status", "ok", "sessionsSignedOut", expiredCount));
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
                "displayName", (user != null && user.getDisplayName() != null) ? user.getDisplayName() : username,
                "role", (user != null) ? user.getRole() : User.ROLE_MEMBER,
                "isAdmin", user != null && user.isAdmin()
        ));
    }

    // ── Forgot / reset password ─────────────────────────────────────────────

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body,
                                                                HttpServletRequest request) {
        String ip = clientIp(request);
        String email = body.getOrDefault("email", "").trim();
        // Generic response either way — never reveal whether the address is
        // registered (that's an account-enumeration vector on its own).
        Map<String, String> genericResponse = Map.of("status", "ok",
                "message", "If that email is registered, we've sent a password reset link to it.");

        if (email.isBlank() || !rateLimiter.allow("forgot:" + ip, 5, 3600)) {
            return ResponseEntity.ok(genericResponse);
        }

        userRepository.findAll().stream()
                .filter(u -> email.equalsIgnoreCase(u.getEmail()))
                .findFirst()
                .ifPresent(tokenService::issuePasswordResetToken);

        return ResponseEntity.ok(genericResponse);
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@RequestBody Map<String, String> body) {
        String token = body.getOrDefault("token", "");
        String newPassword = body.getOrDefault("newPassword", "");

        var tokenOpt = tokenService.find(token, VerificationToken.PURPOSE_RESET_PASSWORD);
        if (tokenOpt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "This reset link is invalid or has expired. Please request a new one."));
        }

        User user = userRepository.findById(tokenOpt.get().getUserId()).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "This reset link is invalid or has expired. Please request a new one."));
        }

        var passwordError = passwordPolicyService.validate(newPassword, user.getUsername());
        if (passwordError.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", passwordError.get()));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        tokenService.markUsed(tokenOpt.get());
        accountSecurityService.recordSuccessfulLogin(user); // also clears any existing lockout

        return ResponseEntity.ok(Map.of("status", "ok", "message", "Your password has been reset. You can now sign in."));
    }

    // ── Email verification ──────────────────────────────────────────────────

    /**
     * Clicked from an email, so this is a browser navigation, not a fetch()
     * call — it responds with a redirect back into the app rather than JSON.
     */
    @GetMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@RequestParam("token") String token) {
        var tokenOpt = tokenService.find(token, VerificationToken.PURPOSE_VERIFY_EMAIL);
        boolean success = false;
        if (tokenOpt.isPresent()) {
            User user = userRepository.findById(tokenOpt.get().getUserId()).orElse(null);
            if (user != null) {
                user.setEmailVerified(true);
                userRepository.save(user);
                tokenService.markUsed(tokenOpt.get());
                success = true;
            }
        }
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, "/login.html?verified=" + (success ? "1" : "0"))
                .build();
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName()).orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Your account has no email on file"));
        }
        if (user.isEmailVerified()) {
            return ResponseEntity.ok(Map.of("status", "ok", "message", "Your email is already verified"));
        }
        if (!rateLimiter.allow("resend-verify:" + user.getUsername(), 3, 3600)) {
            return ResponseEntity.status(429).body(Map.of("status", "error", "message", "Please wait before requesting another verification email"));
        }
        tokenService.issueEmailVerificationToken(user);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Verification email sent to " + user.getEmail()));
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Authenticates the given credentials and, on success, persists the resulting
     * SecurityContext into the HTTP session so subsequent requests (carrying the
     * session cookie) are recognised as authenticated.
     *
     * SECURITY FIX: also runs the SessionAuthenticationStrategy (session-fixation
     * protection + concurrent-session cap + SessionRegistry registration) —
     * see SecurityConfig's javadoc for why this has to be called explicitly
     * here rather than configured declaratively.
     */
    private void authenticateAndPersist(String username, String password,
                                         HttpServletRequest request, HttpServletResponse response) {
        Authentication authRequest = new UsernamePasswordAuthenticationToken(username, password);
        Authentication authResult = authenticationManager.authenticate(authRequest);

        sessionAuthenticationStrategy.onAuthentication(authResult, request, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authResult);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    /** Best-effort client IP resolution — trusts X-Forwarded-For if present (reverse-proxy deployments), else the raw socket address. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
