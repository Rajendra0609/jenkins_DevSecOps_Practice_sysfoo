package com.example.sysfoo.controller;

import com.example.sysfoo.model.User;
import com.example.sysfoo.repository.TodoRepository;
import com.example.sysfoo.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 *    display name, and whether they have a notifiable email on file — NOT
 *    the address itself). Used to populate the assignee picker in the Task
 *    Manager, so a task's assignee is always a real account instead of
 *    free-typed text.
 *
 *    SECURITY FIX: this used to also return every user's raw email address
 *    to every OTHER signed-in user — a real privacy leak once this app has
 *    more than a handful of trusted people in it. Notification emails are
 *    now resolved server-side, by username, at send time (see
 *    NotificationController) — the client never needs, and is never given,
 *    anyone else's email address. The "notifiable" boolean is all the
 *    frontend needs to grey out "notify" for a user with no email on file.
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
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        List<Map<String, Object>> users = userRepository.findAll().stream()
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
        // CORRECTNESS FIX (N+1 / full-table scan): this used to call
        // todoRepository.findAll() — the ENTIRE todos table, every task
        // belonging to every user in the system — twice, just to run
        // .stream().filter().count() over it in Java. Two indexed COUNT(*)
        // queries do the same job without ever pulling a single Todo row
        // (or its text/comments/attachments) into memory.
        long createdCount = todoRepository.countCreatedBy(user.getUsername());
        long assignedCount = todoRepository.countAssignedTo(user.getUsername());

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("username", user.getUsername());
        profile.put("displayName", (user.getDisplayName() != null && !user.getDisplayName().isBlank()) ? user.getDisplayName() : user.getUsername());
        profile.put("email", user.getEmail());
        profile.put("emailVerified", user.isEmailVerified());
        profile.put("notifyOnAssignment", user.isNotifyOnAssignment());
        profile.put("role", user.getRole());
        profile.put("createdAt", user.getCreatedAt());
        profile.put("tasksCreated", createdCount);
        profile.put("tasksAssigned", assignedCount);
        return ResponseEntity.ok(profile);
    }

    /** ENHANCEMENT ("notification preferences"): lets a user turn assignment-notification emails off without removing their email address. */
    @PatchMapping("/me/preferences")
    public ResponseEntity<?> updatePreferences(@RequestBody Map<String, Object> updates, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "User not found"));
        }
        if (updates.containsKey("notifyOnAssignment")) {
            user.setNotifyOnAssignment(Boolean.parseBoolean(String.valueOf(updates.get("notifyOnAssignment"))));
        }
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("status", "ok", "notifyOnAssignment", user.isNotifyOnAssignment()));
    }

    private Map<String, Object> toDirectoryEntry(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.getUsername());
        m.put("displayName", (u.getDisplayName() != null && !u.getDisplayName().isBlank()) ? u.getDisplayName() : u.getUsername());
        // Deliberately NOT the address itself — see class javadoc. Also
        // gated by the user's own notifyOnAssignment preference (see
        // User.notifyOnAssignment) — someone who's opted out simply never
        // appears notifiable to a task creator.
        boolean hasEmail = u.getEmail() != null && !u.getEmail().isBlank();
        m.put("notifiable", hasEmail && u.isNotifyOnAssignment());
        return m;
    }
}
