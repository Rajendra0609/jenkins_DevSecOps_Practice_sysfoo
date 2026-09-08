package com.example.sysfoo.controller;

import com.example.sysfoo.model.Attachment;
import com.example.sysfoo.model.Comment;
import com.example.sysfoo.model.Todo;
import com.example.sysfoo.model.User;
import com.example.sysfoo.repository.AttachmentRepository;
import com.example.sysfoo.repository.CommentRepository;
import com.example.sysfoo.repository.UserRepository;
import com.example.sysfoo.service.FileStorageService;
import com.example.sysfoo.service.TodoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/todos")
public class TodoController {

    private static final Set<String> VALID_PRIORITIES = Set.of("high", "medium", "low");

    @Autowired
    private TodoService todoService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private FileStorageService fileStorageService;

    @PostMapping
    public ResponseEntity<?> addTodo(@RequestBody Todo todo, Authentication authentication) {
        if (todo.getText() == null || todo.getText().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Task text is required"));
        }
        if (todo.getText().length() > 200) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Task text must be under 200 characters"));
        }

        String priority = todo.getPriority() == null ? "medium" : todo.getPriority().trim().toLowerCase();
        if (!VALID_PRIORITIES.contains(priority)) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Priority must be high, medium or low"));
        }
        todo.setPriority(priority);
        todo.setDone(false);

        // ENHANCEMENT: the creator ("assigner") is now recorded server-side —
        // required for the creator-or-assignee-only visibility rule below.
        todo.setCreatedByUsername(authentication.getName());

        // ENHANCEMENT: assignee is now a real username (validated against the
        // user directory), not free text — see Todo.java. Resolving the
        // display name here, rather than trusting whatever the client sends,
        // is what makes it "automatic".
        ResponseEntity<?> assigneeError = applyAssignee(todo, todo.getAssigneeUsername());
        if (assigneeError != null) {
            return assigneeError;
        }

        Todo savedTodo = todoService.save(todo);
        return ResponseEntity.ok(savedTodo);
    }

    /**
     * ENHANCEMENT ("who assigned the task and assignee can only see the
     * task, rest can't view the task"): this used to return every task to
     * everyone (GET /todos was public — see SecurityConfig's old comment).
     * Now requires authentication and returns only tasks the caller created
     * or is assigned to.
     */
    @GetMapping
    public ResponseEntity<List<Todo>> getAllTodos(Authentication authentication) {
        String me = authentication.getName();
        List<Todo> visible = todoService.findAllNewestFirst().stream()
                .filter(t -> canAccess(t, me))
                .collect(Collectors.toList());
        return ResponseEntity.ok(visible);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> updateTodo(@PathVariable Long id, @RequestBody Map<String, Object> updates,
                                         Authentication authentication) {
        Optional<Todo> existing = todoService.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        Todo todo = existing.get();
        if (!canAccess(todo, authentication.getName())) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }

        if (updates.containsKey("text")) {
            Object rawText = updates.get("text");
            String text = rawText == null ? "" : String.valueOf(rawText).trim();
            if (text.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Task text is required"));
            }
            if (text.length() > 200) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Task text must be under 200 characters"));
            }
            todo.setText(text);
        }

        if (updates.containsKey("priority")) {
            Object rawPriority = updates.get("priority");
            String priority = rawPriority == null ? "" : String.valueOf(rawPriority).trim().toLowerCase();
            if (!VALID_PRIORITIES.contains(priority)) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Priority must be high, medium or low"));
            }
            todo.setPriority(priority);
        }

        if (updates.containsKey("done")) {
            Object rawDone = updates.get("done");
            todo.setDone(Boolean.parseBoolean(String.valueOf(rawDone)));
        }

        if (updates.containsKey("assigneeUsername")) {
            Object rawAssignee = updates.get("assigneeUsername");
            String assigneeUsername = rawAssignee == null ? "" : String.valueOf(rawAssignee).trim();
            ResponseEntity<?> assigneeError = applyAssignee(todo, assigneeUsername.isEmpty() ? null : assigneeUsername);
            if (assigneeError != null) {
                return assigneeError;
            }
        }

        Todo saved = todoService.save(todo);
        return ResponseEntity.ok(saved);
    }

    /**
     * ENHANCEMENT: delete is restricted to the task's creator. An assignee
     * can see, comment on, and complete their tasks, but removing a task
     * the *creator* made — including erasing it from the creator's own view
     * — is scoped to the creator only. (This is the one place creator and
     * assignee permissions genuinely diverge; worth reconsidering if that's
     * not the split you want.)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTodo(@PathVariable Long id, Authentication authentication) {
        Optional<Todo> existing = todoService.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        Todo todo = existing.get();
        if (!authentication.getName().equals(todo.getCreatedByUsername())) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        attachmentRepository.findByTodoIdOrderByCreatedAtAsc(id).forEach(fileStorageService::delete);
        commentRepository.findByTodoIdOrderByCreatedAtAsc(id).forEach(commentRepository::delete);
        boolean deleted = todoService.delete(id);
        if (!deleted) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Task deleted"));
    }

    // ── Comments ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}/comments")
    public ResponseEntity<?> getComments(@PathVariable Long id, Authentication authentication) {
        Optional<Todo> todoOpt = todoService.findById(id);
        if (todoOpt.isEmpty() || !canAccess(todoOpt.get(), authentication.getName())) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        return ResponseEntity.ok(commentRepository.findByTodoIdOrderByCreatedAtAsc(id));
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<?> addComment(@PathVariable Long id, @RequestBody Map<String, String> body,
                                         Authentication authentication) {
        Optional<Todo> todoOpt = todoService.findById(id);
        if (todoOpt.isEmpty() || !canAccess(todoOpt.get(), authentication.getName())) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        String text = body.getOrDefault("text", "").trim();
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Comment text is required"));
        }
        if (text.length() > 2000) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Comment must be under 2000 characters"));
        }
        String username = authentication.getName();
        String displayName = userRepository.findByUsername(username)
                .map(u -> (u.getDisplayName() != null && !u.getDisplayName().isBlank()) ? u.getDisplayName() : username)
                .orElse(username);
        Comment saved = commentRepository.save(new Comment(id, username, displayName, text));
        return ResponseEntity.ok(saved);
    }

    // ── Attachments ──────────────────────────────────────────────────────────

    @GetMapping("/{id}/attachments")
    public ResponseEntity<?> getAttachments(@PathVariable Long id, Authentication authentication) {
        Optional<Todo> todoOpt = todoService.findById(id);
        if (todoOpt.isEmpty() || !canAccess(todoOpt.get(), authentication.getName())) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        return ResponseEntity.ok(attachmentRepository.findByTodoIdOrderByCreatedAtAsc(id));
    }

    /** ENHANCEMENT: file attachments on a task — .txt/.zip only, <=20MB (see FileStorageService). */
    @PostMapping("/{id}/attachments")
    public ResponseEntity<?> uploadAttachment(@PathVariable Long id, @RequestParam("file") MultipartFile file,
                                               Authentication authentication) {
        Optional<Todo> todoOpt = todoService.findById(id);
        if (todoOpt.isEmpty() || !canAccess(todoOpt.get(), authentication.getName())) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        try {
            fileStorageService.validateTaskAttachment(file);
            Attachment saved = fileStorageService.store(file, id, null, authentication.getName());
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", "Could not save the uploaded file"));
        }
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    private boolean canAccess(Todo todo, String username) {
        return username.equals(todo.getCreatedByUsername()) || username.equals(todo.getAssigneeUsername());
    }

    /** Validates assigneeUsername against the user directory and fills in Todo.name + assigneeUsername. Returns an error ResponseEntity, or null on success. */
    private ResponseEntity<?> applyAssignee(Todo todo, String assigneeUsername) {
        if (assigneeUsername == null || assigneeUsername.isBlank()) {
            todo.setAssigneeUsername(null);
            todo.setName(null);
            return null;
        }
        Optional<User> assignee = userRepository.findByUsername(assigneeUsername.trim());
        if (assignee.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Unknown assignee"));
        }
        User user = assignee.get();
        todo.setAssigneeUsername(user.getUsername());
        todo.setName((user.getDisplayName() != null && !user.getDisplayName().isBlank()) ? user.getDisplayName() : user.getUsername());
        return null;
    }
}
