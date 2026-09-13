package com.example.sysfoo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Verifies a Google reCAPTCHA v2 ("I'm not a robot" checkbox) token against
 * Google's siteverify endpoint before allowing registration.
 *
 * ── Configured like mail: optional, and safe when unset ──────────────────
 * Same pattern as JavaMailSender/NotificationController elsewhere in this
 * app: if app.security.recaptcha.secret-key isn't set (local dev, CI,
 * an operator who hasn't set up a reCAPTCHA site yet), verification is
 * SKIPPED rather than the app failing to start or every registration being
 * rejected. Set both app.security.recaptcha.site-key (public, goes in the
 * frontend) and app.security.recaptcha.secret-key (private, server-only,
 * from an env var) to turn it on.
 */
@Service
public class RecaptchaService {

    @Value("${app.security.recaptcha.secret-key:}")
    private String secretKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    public boolean isEnabled() {
        return secretKey != null && !secretKey.isBlank();
    }

    /**
     * @param token the g-recaptcha-response token submitted by the browser
     * @param remoteIp the caller's IP, optional but recommended by Google
     * @return true if verification is disabled (nothing to check) OR Google confirms the token is valid
     */
    public boolean verify(String token, String remoteIp) {
        if (!isEnabled()) {
            return true;
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            String form = "secret=" + urlEncode(secretKey)
                    + "&response=" + urlEncode(token)
                    + (remoteIp != null ? "&remoteip=" + urlEncode(remoteIp) : "");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.google.com/recaptcha/api/siteverify"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(4))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // Minimal JSON parse without pulling in a JSON lib dependency here —
            // this is the one field we need: {"success": true/false, ...}
            return response.statusCode() == 200 && response.body().contains("\"success\": true")
                    || response.statusCode() == 200 && response.body().contains("\"success\":true");
        } catch (Exception e) {
            // If Google is unreachable, fail CLOSED here (unlike password
            // breach checks) — CAPTCHA exists specifically to stop automated
            // abuse, so silently letting every signup through during an
            // outage would defeat the point.
            return false;
        }
    }

    private String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
