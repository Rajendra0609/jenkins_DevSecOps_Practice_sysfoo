package com.example.sysfoo.controller;

import com.example.sysfoo.model.User;
import com.example.sysfoo.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ENHANCEMENT ("roles & permissions — right now every signed-in user has
 * identical power; no one can manage other accounts, moderate posts, or
 * reassign orphaned tasks"): the account-management half of that — see
 * PostController/TodoController for the "an admin can moderate anyone's
 * post/task" half, applied as a small extra clause on their existing
 * author/creator checks rather than a parallel set of admin-only endpoints.
 *
 * ── Why authorization is checked in the method body, not the URL rules ──
 * This app doesn't use Spring Security's hasRole()/@PreAuthorize anywhere
 * — CustomUserDetailsService only ever grants a single ROLE_USER, and
 * every other permission decision in this codebase (creator-only delete,
 * assignee-only comment access, etc.) is a plain "load the record, check a
 * field against authentication.getName()" in the controller. Admin checks
 * follow that same, already-established pattern rather than introducing a
 * second, inconsistent authorization mechanism alongside it.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private UserRepository userRepository;

    private ResponseEntity<Map<String, String>> requireAdmin(Authentication authentication) {
        User caller = userRepository.findByUsername(authentication.getName()).orElse(null);
        if (caller == null || !caller.isAdmin()) {
            // 404, not 403 — same "don't confirm this exists" instinct used
            // elsewhere in this app (e.g. TodoController's delete check):
            // a non-admin gets no signal that admin endpoints even exist.
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Not found"));
        }
        return null;
    }

    @GetMapping("/users")
    public ResponseEntity<?> listUsers(Authentication authentication) {
        ResponseEntity<?> denied = requireAdmin(authentication);
        if (denied != null) return denied;

        List<Map<String, Object>> users = userRepository.findAll().stream()
                .map(this::toAdminEntry)
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    @PatchMapping("/users/{username}/role")
    public ResponseEntity<?> updateRole(@PathVariable String username, @RequestBody Map<String, String> body,
                                         Authentication authentication) {
        ResponseEntity<?> denied = requireAdmin(authentication);
        if (denied != null) return denied;

        String newRole = body.getOrDefault("role", "").trim().toUpperCase();
        if (!User.ROLE_ADMIN.equals(newRole) && !User.ROLE_MEMBER.equals(newRole)) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Role must be ADMIN or MEMBER"));
        }
        if (username.equals(authentication.getName()) && User.ROLE_MEMBER.equals(newRole)) {
            // Defense against self-lockout: if this were the only admin,
            // demoting yourself would leave the app with zero admins and no
            // way back in short of editing the database directly.
            long adminCount = userRepository.findAll().stream().filter(User::isAdmin).count();
            if (adminCount <= 1) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "You're the only admin — promote someone else first"));
            }
        }

        User target = userRepository.findByUsername(username).orElse(null);
        if (target == null) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "User not found"));
        }
        target.setRole(newRole);
        userRepository.save(target);
        return ResponseEntity.ok(Map.of("status", "ok", "username", username, "role", newRole));
    }

    /** Clears a persisted lockout (see AccountSecurityService) before it would otherwise expire on its own. */
    @PostMapping("/users/{username}/unlock")
    public ResponseEntity<?> unlockUser(@PathVariable String username, Authentication authentication) {
        ResponseEntity<?> denied = requireAdmin(authentication);
        if (denied != null) return denied;

        User target = userRepository.findByUsername(username).orElse(null);
        if (target == null) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "User not found"));
        }
        target.setFailedLoginAttempts(0);
        target.setLockedUntil(null);
        userRepository.save(target);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Account unlocked"));
    }

    private Map<String, Object> toAdminEntry(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.getUsername());
        m.put("displayName", u.getDisplayName());
        m.put("email", u.getEmail());
        m.put("role", u.getRole());
        m.put("emailVerified", u.isEmailVerified());
        m.put("locked", u.isAccountLocked());
        m.put("createdAt", u.getCreatedAt());
        return m;
    }
}
