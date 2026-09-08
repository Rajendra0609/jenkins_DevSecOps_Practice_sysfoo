package com.example.sysfoo.controller;

import com.example.sysfoo.model.User;
import com.example.sysfoo.repository.TodoRepository;
import com.example.sysfoo.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ENHANCEMENT: two new endpoints, both requiring authentication (see
 * SecurityConfig):
 *
 *  - GET /api/users        — directory of every registered user (username,
 *    display name, email). Used to populate the assignee picker in the Task
 *    Manager, so a task's assignee is always a real account instead of
 *    free-typed text, and so the notification email can be looked up
 *    automatically instead of being typed by hand.
 *
 *    NOTE ON PRIVACY: this hands every signed-in user's email address to
 *    every OTHER signed-in user. That's an accepted trade-off for a small
 *    team practice app (same spirit as the CSRF/GET-visibility trade-offs
 *    already documented in SecurityConfig) — a larger deployment would want
 *    to gate this more carefully, e.g. only exposing email to people who
 *    share a task.
 *
 *  - GET /api/users/me/profile — the signed-in user's own full profile
 *    (username, display name, email, member-since date, and a couple of
 *    task counters) for the "click your name to see your info" panel.
 *    Deliberately separate from GET /api/auth/me, which stays a small,
 *    frequently-polled "am I logged in" check.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TodoRepository todoRepository;

    @GetMapping
    public ResponseEntity<List<Map<String, String>>> listUsers() {
        List<Map<String, String>> users = userRepository.findAll().stream()
                .map(this::toDirectoryEntry)
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    @GetMapping("/me/profile")
    public ResponseEntity<?> myProfile(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "User not found"));
        }
        long createdCount = todoRepository.findAll().stream()
                .filter(t -> user.getUsername().equals(t.getCreatedByUsername()))
                .count();
        long assignedCount = todoRepository.findAll().stream()
                .filter(t -> user.getUsername().equals(t.getAssigneeUsername()))
                .count();

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("username", user.getUsername());
        profile.put("displayName", (user.getDisplayName() != null && !user.getDisplayName().isBlank()) ? user.getDisplayName() : user.getUsername());
        profile.put("email", user.getEmail());
        profile.put("createdAt", user.getCreatedAt());
        profile.put("tasksCreated", createdCount);
        profile.put("tasksAssigned", assignedCount);
        return ResponseEntity.ok(profile);
    }

    private Map<String, String> toDirectoryEntry(User u) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("username", u.getUsername());
        m.put("displayName", (u.getDisplayName() != null && !u.getDisplayName().isBlank()) ? u.getDisplayName() : u.getUsername());
        m.put("email", u.getEmail());
        return m;
    }
}
