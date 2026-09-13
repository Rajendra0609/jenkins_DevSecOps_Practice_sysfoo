package com.example.sysfoo.service;

import com.example.sysfoo.model.User;
import com.example.sysfoo.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Persisted, per-account brute-force protection.
 *
 * This is deliberately separate from {@link com.example.sysfoo.security.RateLimiter}
 * (which throttles by IP and is in-memory/best-effort): the two protect
 * against different things —
 *   - RateLimiter: "this IP is hammering /api/auth/login" (survives across
 *     different target usernames, resets on restart).
 *   - AccountSecurityService: "this specific account has had N bad password
 *     attempts in a row" (survives restarts, applies no matter which IP the
 *     attempts came from).
 * A real deployment wants both: an attacker spraying one password across
 * many accounts from one IP is caught by the rate limiter; an attacker
 * grinding one account's password from many IPs (or via a botnet) is caught
 * by this lockout.
 */
@Service
public class AccountSecurityService {

    /** Attempts allowed before the account is temporarily locked. */
    public static final int MAX_ATTEMPTS = 5;

    /** How long a lockout lasts once triggered. */
    public static final long LOCKOUT_MINUTES = 15;

    @Autowired
    private UserRepository userRepository;

    /** Call after a failed password check. Locks the account once MAX_ATTEMPTS is reached. */
    public void recordFailedAttempt(User user) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= MAX_ATTEMPTS) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES));
        }
        userRepository.save(user);
    }

    /** Call after a successful login — clears the counter and any lock. */
    public void recordSuccessfulLogin(User user) {
        if (user.getFailedLoginAttempts() != 0 || user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }
    }
}
