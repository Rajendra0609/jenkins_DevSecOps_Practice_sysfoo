package com.example.sysfoo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * A single-use, expiring token emailed to a user for a sensitive
 * self-service action. Shared entity for both purposes this app needs
 * (password reset, email verification) rather than two near-identical
 * tables — the {@link #purpose} column keeps them from ever being
 * cross-redeemable.
 *
 * ── Security notes ───────────────────────────────────────────────────────
 *   - The token value itself is a high-entropy random string (see
 *     TokenService) — never the user's id or username — so it can't be
 *     guessed or enumerated.
 *   - Single-use: {@link #used} is flipped the moment a token is redeemed,
 *     even if the underlying action (e.g. setting a new password) somehow
 *     fails afterwards, so a captured token can't be replayed.
 *   - Expiring: {@link #expiresAt} is checked on every redemption attempt.
 *   - Not FK-linked to User with cascade delete semantics on purpose: kept
 *     as a plain userId long so deleting a user doesn't require special
 *     handling here, and old tokens are simply orphaned/ignored.
 */
@Entity
@Table(
    name = "verification_tokens",
    uniqueConstraints = @UniqueConstraint(name = "uk_verification_tokens_token", columnNames = "token")
)
public class VerificationToken {

    public static final String PURPOSE_RESET_PASSWORD = "RESET_PASSWORD";
    public static final String PURPOSE_VERIFY_EMAIL = "VERIFY_EMAIL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String token;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String purpose;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean used = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public VerificationToken() {
    }

    public VerificationToken(String token, Long userId, String purpose, LocalDateTime expiresAt) {
        this.token = token;
        this.userId = userId;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return expiresAt == null || expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean isValid() {
        return !used && !isExpired();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isUsed() {
        return used;
    }

    public void setUsed(boolean used) {
        this.used = used;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
