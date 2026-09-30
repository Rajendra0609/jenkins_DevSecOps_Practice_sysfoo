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
 * Registered application user.
 *
 * NOTE: the table is named "app_users" rather than "users" because "user" is a
 * reserved keyword in PostgreSQL (and several other SQL dialects) — using it
 * directly as a table name would break DDL generation on the prod database.
 */
@Entity
@Table(
    name = "app_users",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_app_users_username", columnNames = "username"),
        @UniqueConstraint(name = "uk_app_users_email", columnNames = "email")
    }
)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String username;

    /** BCrypt password hash — the raw password is never stored or returned to the client. */
    @Column(nullable = false)
    private String password;

    @Column(length = 120)
    private String email;

    @Column(length = 60)
    private String displayName;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── SECURITY: account lockout ────────────────────────────────────────────
    // Tracks consecutive failed login attempts. Reset to 0 on any successful
    // login. Once it crosses AccountSecurityService.MAX_ATTEMPTS, lockedUntil
    // is set and further logins are rejected (even with the right password)
    // until that instant passes. Persisted (not in-memory) so a lockout
    // survives an app restart and works the same for every login path.
    @Column(nullable = false)
    private int failedLoginAttempts = 0;

    @Column
    private LocalDateTime lockedUntil;

    // ── SECURITY: email verification ─────────────────────────────────────────
    // Only meaningful when email is non-null — a user who registered without
    // an email has nothing to verify and is treated as verified so the rest
    // of the app never has to special-case "no email" vs "verified email".
    @Column(nullable = false)
    private boolean emailVerified = false;

    /**
     * ENHANCEMENT ("notification preferences... right now email
     * notifications are all-or-nothing per assignment"): lets a user turn
     * off assignment-notification emails without losing/removing their
     * email address entirely (which would also disable password reset).
     * Read by UserController.toDirectoryEntry() — the "notifiable" flag the
     * assignee picker uses is hasEmail AND this, so an opted-out user
     * simply never shows as notifiable to a task creator.
     */
    @Column(nullable = false)
    private boolean notifyOnAssignment = true;

    /**
     * ENHANCEMENT ("roles & permissions... right now every signed-in user
     * has identical power; no one can manage other accounts, moderate
     * posts, or reassign orphaned tasks"): "ADMIN" or "MEMBER" (plain
     * String, not a JPA @Enumerated enum — this app has no other enum
     * columns, and a two-value String keeps this consistent with how
     * everything else here favors simple columns over extra machinery).
     *
     * Bootstrap: there's no seed data or setup wizard, so the very first
     * account ever registered is automatically made ADMIN (see
     * AuthController.register()) — every account after that defaults to
     * MEMBER. An existing ADMIN can promote/demote anyone afterwards (see
     * AdminController). A fresh database's first signup becoming the admin
     * is a common, well-understood bootstrap pattern for self-hosted apps
     * with no separate provisioning step.
     */
    @Column(nullable = false, length = 10)
    private String role = ROLE_MEMBER;

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_MEMBER = "MEMBER";

    public User() {
    }

    public User(String username, String password, String email, String displayName) {
        this.username = username;
        this.password = password;
        this.email = email;
        this.displayName = displayName;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public void setFailedLoginAttempts(int failedLoginAttempts) {
        this.failedLoginAttempts = failedLoginAttempts;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(LocalDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public boolean isAccountLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public boolean isNotifyOnAssignment() {
        return notifyOnAssignment;
    }

    public void setNotifyOnAssignment(boolean notifyOnAssignment) {
        this.notifyOnAssignment = notifyOnAssignment;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
