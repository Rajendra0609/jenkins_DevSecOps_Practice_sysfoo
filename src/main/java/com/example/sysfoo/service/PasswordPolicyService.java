package com.example.sysfoo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Password strength policy applied at registration (and, when we add
 * self-service password changes, there too).
 *
 * ── Design choice: length + breach-check over forced composition rules ──
 *   NIST SP 800-63B explicitly recommends checking new passwords against a
 *   list of known-breached/commonly-used passwords, and de-emphasizes
 *   forced composition rules (mandatory uppercase/digit/symbol) — those
 *   rules push people toward predictable patterns ("Password1!") without
 *   meaningfully improving real-world strength. This service follows that
 *   guidance: a longer minimum length, a same-as-username check, and a
 *   breach check via the Have I Been Pwned range API, rather than a bag of
 *   character-class requirements.
 *
 * ── HIBP check: k-anonymity, no plaintext password ever leaves the server ─
 *   The password is SHA-1 hashed locally; only the first 5 hex characters
 *   of the hash are sent to the API, which returns every known-breached
 *   hash sharing that prefix. The full comparison happens locally. This is
 *   the same scheme HIBP documents and Have I Been Pwned itself recommends
 *   for exactly this reason.
 *
 * ── Fails open, not closed ────────────────────────────────────────────────
 *   If the HIBP API is unreachable or slow (offline dev box, air-gapped
 *   deployment, transient outage), registration must not be blocked by a
 *   third party being down — the check is skipped and only the local rules
 *   apply. Toggle entirely off via app.security.password.hibp-check-enabled.
 */
@Service
public class PasswordPolicyService {

    private static final int MIN_LENGTH = 10;

    @Value("${app.security.password.hibp-check-enabled:true}")
    private boolean hibpCheckEnabled;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** @return empty if the password is acceptable, or an error message to show the user. */
    public Optional<String> validate(String password, String username) {
        if (password == null || password.length() < MIN_LENGTH) {
            return Optional.of("Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.length() > 128) {
            return Optional.of("Password must be under 128 characters");
        }
        if (username != null && password.equalsIgnoreCase(username)) {
            return Optional.of("Password can't be the same as your username");
        }
        if (isCommonlyUsedFallback(password)) {
            return Optional.of("That password is far too common — please choose another");
        }
        if (hibpCheckEnabled && isPwned(password)) {
            return Optional.of("That password has appeared in a known data breach — please choose another");
        }
        return Optional.empty();
    }

    /**
     * Queries the Have I Been Pwned range API. Returns false (i.e. "not
     * known to be pwned") on ANY failure — see class javadoc on failing
     * open. Never throws.
     */
    private boolean isPwned(String password) {
        try {
            String sha1 = sha1Hex(password);
            String prefix = sha1.substring(0, 5);
            String suffix = sha1.substring(5);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.pwnedpasswords.com/range/" + prefix))
                    .header("Add-Padding", "true")
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            for (String line : response.body().split("\r?\n")) {
                String[] parts = line.split(":");
                if (parts.length >= 1 && parts[0].equalsIgnoreCase(suffix)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // Network unavailable, DNS blocked, timeout, etc. — fail open.
            return false;
        }
    }

    private String sha1Hex(String input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * A tiny, local backstop of the very worst offenders — checked even if
     * HIBP is disabled or unreachable, so at least the most obvious
     * passwords are always rejected without needing network access.
     */
    private boolean isCommonlyUsedFallback(String password) {
        String lower = password.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "password", "password1", "password123", "123456789", "1234567890",
                 "qwertyuiop", "letmein123", "welcome123", "admin1234", "iloveyou1" -> true;
            default -> false;
        };
    }
}
