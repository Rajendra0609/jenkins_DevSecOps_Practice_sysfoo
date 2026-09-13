package com.example.sysfoo.controller;

import com.example.sysfoo.model.Attachment;
import com.example.sysfoo.model.Comment;
import com.example.sysfoo.model.Subtask;
import com.example.sysfoo.model.Todo;
import com.example.sysfoo.model.User;
import com.example.sysfoo.repository.AttachmentRepository;
import com.example.sysfoo.repository.CommentRepository;
import com.example.sysfoo.repository.SubtaskRepository;
import com.example.sysfoo.repository.UserRepository;
import com.example.sysfoo.service.FileStorageService;
import com.example.sysfoo.service.TaskAuditService;
import com.example.sysfoo.service.TodoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/todos")
public class TodoController {

    private static final Set<String> VALID_PRIORITIES = Set.of("high", "medium", "low");
    private static final Set<String> VALID_STATUSES = Set.of("TO_DO", "IN_PROGRESS", "IN_REVIEW", "DONE");

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

    @Autowired
    private TaskAuditService taskAuditService;

    @Autowired
    private SubtaskRepository subtaskRepository;

    @Autowired
    private com.example.sysfoo.service.EventBroadcastService eventBroadcastService;

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_SUBTASKS_PER_TASK = 30;

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

        String requestedStatus = todo.getStatus() == null ? "TO_DO" : todo.getStatus().trim().toUpperCase();
        if (!VALID_STATUSES.contains(requestedStatus)) {
            requestedStatus = "TO_DO";
        }
        todo.setStatus(requestedStatus);
        todo.setDone("DONE".equals(requestedStatus));

        // Jira-like enrichment fields for ticket classification and ownership.
        if (todo.getTicketType() == null || todo.getTicketType().isBlank()) {
            todo.setTicketType("TASK");
        }
        if (todo.getEffortHours() != null && todo.getEffortHours() < 0) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Effort hours must be a positive number"));
        }
        if (todo.getStoryPoints() != null && (todo.getStoryPoints() < 0 || todo.getStoryPoints() > 100)) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Story points must be between 0 and 100"));
        }

        // ENHANCEMENT ("tags/labels"): validated (not just accepted as-is)
        // so a caller can't sneak a value past the 200-char DB column via a
        // huge tag list — Todo.setTags() itself just joins whatever it's
        // given, so the length check has to happen here, before saving.
        ResponseEntity<?> tagsError = validateTags(todo.getTags());
        if (tagsError != null) {
            return tagsError;
        }

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
        taskAuditService.log(savedTodo.getId(), authentication.getName(), "CREATED",
                savedTodo.getAssigneeUsername() != null
                        ? "created and assigned to " + savedTodo.getName()
                        : "created");
        eventBroadcastService.broadcast("todos-changed");
        return ResponseEntity.ok(savedTodo);
    }

    /**
     * ENHANCEMENT ("who assigned the task and assignee can only see the
     * task, rest can't view the task"): this used to return every task to
     * everyone (GET /todos was public — see SecurityConfig's old comment).
     * Now requires authentication and returns only tasks the caller created
     * or is assigned to.
     *
     * CORRECTNESS FIX (N+1 / no pagination — see TodoService.findVisibleToUser):
     * this used to fetch the entire todos table on every single request and
     * filter it down in a Java stream. It's now one bounded, indexed-filter
     * query. page/size are accepted for a real "Load more" affordance;
     * size is capped so a caller can't request an unbounded page.
     */
    @GetMapping
    public ResponseEntity<Page<Todo>> getAllTodos(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            Authentication authentication) {
        String me = authentication.getName();
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Page<Todo> visible = todoService.findVisibleToUser(me, PageRequest.of(safePage, safeSize));
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
            if (!text.equals(todo.getText())) {
                taskAuditService.log(id, authentication.getName(), "UPDATED", "text updated");
            }
            todo.setText(text);
        }

        if (updates.containsKey("priority")) {
            Object rawPriority = updates.get("priority");
            String priority = rawPriority == null ? "" : String.valueOf(rawPriority).trim().toLowerCase();
            if (!VALID_PRIORITIES.contains(priority)) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Priority must be high, medium or low"));
            }
            taskAuditService.logFieldChange(id, authentication.getName(), "priority", todo.getPriority(), priority);
            todo.setPriority(priority);
        }

        if (updates.containsKey("ticketType")) {
            Object rawTicketType = updates.get("ticketType");
            String ticketType = rawTicketType == null ? "" : String.valueOf(rawTicketType).trim();
            todo.setTicketType(ticketType);
        }

        if (updates.containsKey("component")) {
            Object rawComponent = updates.get("component");
            String component = rawComponent == null ? "" : String.valueOf(rawComponent).trim();
            todo.setComponent(component);
        }

        if (updates.containsKey("team")) {
            Object rawTeam = updates.get("team");
            String team = rawTeam == null ? "" : String.valueOf(rawTeam).trim();
            todo.setTeam(team);
        }

        if (updates.containsKey("epic")) {
            Object rawEpic = updates.get("epic");
            String epic = rawEpic == null ? "" : String.valueOf(rawEpic).trim();
            todo.setEpic(epic);
        }

        if (updates.containsKey("storyPoints")) {
            Object rawPoints = updates.get("storyPoints");
            try {
                Integer points = rawPoints == null || String.valueOf(rawPoints).isBlank() ? null : Integer.valueOf(String.valueOf(rawPoints));
                if (points != null && (points < 0 || points > 100)) {
                    return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Story points must be between 0 and 100"));
                }
                todo.setStoryPoints(points);
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Story points must be an integer"));
            }
        }

        if (updates.containsKey("effortHours")) {
            Object rawEffort = updates.get("effortHours");
            try {
                Double effort = rawEffort == null || String.valueOf(rawEffort).isBlank() ? null : Double.valueOf(String.valueOf(rawEffort));
                if (effort != null && effort < 0) {
                    return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Effort hours must be a positive number"));
                }
                todo.setEffortHours(effort);
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Effort hours must be numeric"));
            }
        }

        if (updates.containsKey("folder")) {
            Object rawFolder = updates.get("folder");
            String folder = rawFolder == null ? "" : String.valueOf(rawFolder).trim();
            todo.setFolder(folder);
        }

        if (updates.containsKey("status")) {
            Object rawStatus = updates.get("status");
            String requestedStatus = rawStatus == null ? "" : String.valueOf(rawStatus).trim().toUpperCase();
            if (!VALID_STATUSES.contains(requestedStatus)) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Status must be TO_DO, IN_PROGRESS, IN_REVIEW or DONE"));
            }
            String previousStatus = todo.getStatus();
            if (!requestedStatus.equals(previousStatus)) {
                taskAuditService.log(id, authentication.getName(), "UPDATED", "status changed from " + previousStatus + " to " + requestedStatus);
            }
            todo.setStatus(requestedStatus);
            todo.setDone("DONE".equals(requestedStatus));
        }

        if (updates.containsKey("done")) {
            Object rawDone = updates.get("done");
            boolean done = Boolean.parseBoolean(String.valueOf(rawDone));
            if (done != todo.isDone()) {
                taskAuditService.log(id, authentication.getName(), done ? "COMPLETED" : "REOPENED",
                        done ? "marked complete" : "reopened");
            }
            todo.setStatus(done ? "DONE" : "TO_DO");
            todo.setDone(done);
        }

        if (updates.containsKey("assigneeUsername")) {
            Object rawAssignee = updates.get("assigneeUsername");
            String assigneeUsername = rawAssignee == null ? "" : String.valueOf(rawAssignee).trim();
            String previousAssignee = todo.getName();
            ResponseEntity<?> assigneeError = applyAssignee(todo, assigneeUsername.isEmpty() ? null : assigneeUsername);
            if (assigneeError != null) {
                return assigneeError;
            }
            taskAuditService.logFieldChange(id, authentication.getName(), "assignee", previousAssignee, todo.getName());
        }

        // ENHANCEMENT ("task due dates + reminders").
        if (updates.containsKey("dueDate")) {
            Object raw = updates.get("dueDate");
            java.time.LocalDate previousDueDate = todo.getDueDate();
            if (raw == null || String.valueOf(raw).isBlank()) {
                todo.setDueDate(null);
            } else {
                try {
                    todo.setDueDate(java.time.LocalDate.parse(String.valueOf(raw)));
                } catch (java.time.format.DateTimeParseException e) {
                    return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Due date must be in YYYY-MM-DD format"));
                }
            }
            taskAuditService.logFieldChange(id, authentication.getName(), "due date", previousDueDate, todo.getDueDate());
        }

        // ENHANCEMENT ("tags/labels").
        if (updates.containsKey("tags")) {
            Object raw = updates.get("tags");
            List<String> tags;
            if (raw instanceof List<?> rawList) {
                tags = rawList.stream().map(String::valueOf).toList();
            } else if (raw == null) {
                tags = List.of();
            } else {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Tags must be an array of strings"));
            }
            ResponseEntity<?> tagsError = validateTags(tags);
            if (tagsError != null) {
                return tagsError;
            }
            List<String> previousTags = todo.getTags();
            todo.setTags(tags);
            if (!previousTags.equals(todo.getTags())) {
                taskAuditService.log(id, authentication.getName(), "UPDATED", "tags updated");
            }
        }

        Todo saved = todoService.save(todo);
        eventBroadcastService.broadcast("todos-changed");
        return ResponseEntity.ok(saved);
    }

    private static final int MAX_TAGS = 6;
    private static final int MAX_TAG_LENGTH = 24;

    /** Shared by addTodo and updateTodo — see Todo.tagsCsv's javadoc for why this is a plain column instead of a join table. */
    private ResponseEntity<?> validateTags(List<String> tags) {
        if (tags.size() > MAX_TAGS) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "A task can have at most " + MAX_TAGS + " tags"));
        }
        for (String tag : tags) {
            if (tag != null && tag.trim().length() > MAX_TAG_LENGTH) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Each tag must be under " + MAX_TAG_LENGTH + " characters"));
            }
        }
        return null;
    }

    /**
     * ENHANCEMENT: delete is restricted to the task's creator. An assignee
     * can see, comment on, and complete their tasks, but removing a task
     * the *creator* made — including erasing it from the creator's own view
     * — is scoped to the creator only. (This is the one place creator and
     * assignee permissions genuinely diverge; worth reconsidering if that's
     * not the split you want.)
     *
     * CORRECTNESS FIX ("no soft-delete / audit trail... permanent and
     * untracked"): this used to hard-delete the row (and its comments and
     * attachment files) with zero trace. It now soft-deletes (see
     * TodoService.softDelete / Todo.deleted) and deliberately leaves
     * comments and attachments in place — a soft-deleted task is fully
     * recoverable, whereas the old behavior destroyed everything
     * irreversibly the moment "Remove" was clicked.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTodo(@PathVariable Long id, Authentication authentication) {
        Optional<Todo> existing = todoService.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        Todo todo = existing.get();
        // ENHANCEMENT ("roles & permissions... no one can manage other
        // accounts, moderate posts, or reassign orphaned tasks"): an admin
        // can delete any task, not just their own — everyone else still
        // needs to be the creator.
        if (!authentication.getName().equals(todo.getCreatedByUsername()) && !isAdminCaller(authentication)) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        if (todo.isDeleted()) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        todoService.softDelete(todo, authentication.getName());
        taskAuditService.log(id, authentication.getName(), "DELETED", "task deleted");
        eventBroadcastService.broadcast("todos-changed");
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Task deleted"));
    }

    /**
     * CORRECTNESS FIX ("no soft-delete / audit trail... no 'who changed
     * what when'"): the full change history for a task — see TaskAuditLog.
     * Same visibility rule as everything else on a task: creator or
     * assignee only.
     */
    @GetMapping("/{id}/audit")
    public ResponseEntity<?> getAudit(@PathVariable Long id, Authentication authentication) {
        Optional<Todo> todoOpt = todoService.findById(id);
        if (todoOpt.isEmpty() || !canAccess(todoOpt.get(), authentication.getName())) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        return ResponseEntity.ok(taskAuditService.history(id));
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

    // ── Subtasks / checklist ─────────────────────────────────────────────────
    // ENHANCEMENT ("subtasks/checklists... the task manager currently only
    // has priority + done/not-done"). Same creator-or-assignee visibility
    // rule as comments/attachments.

    @GetMapping("/{id}/subtasks")
    public ResponseEntity<?> getSubtasks(@PathVariable Long id, Authentication authentication) {
        Optional<Todo> todoOpt = todoService.findById(id);
        if (todoOpt.isEmpty() || !canAccess(todoOpt.get(), authentication.getName())) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        return ResponseEntity.ok(subtaskRepository.findByTodoIdOrderByCreatedAtAsc(id));
    }

    @PostMapping("/{id}/subtasks")
    public ResponseEntity<?> addSubtask(@PathVariable Long id, @RequestBody Map<String, String> body,
                                         Authentication authentication) {
        Optional<Todo> todoOpt = todoService.findById(id);
        if (todoOpt.isEmpty() || !canAccess(todoOpt.get(), authentication.getName())) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        String text = body.getOrDefault("text", "").trim();
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Checklist item text is required"));
        }
        if (text.length() > 200) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Checklist item must be under 200 characters"));
        }
        if (subtaskRepository.countByTodoId(id) >= MAX_SUBTASKS_PER_TASK) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "A task can have at most " + MAX_SUBTASKS_PER_TASK + " checklist items"));
        }
        Subtask saved = subtaskRepository.save(new Subtask(id, text));
        taskAuditService.log(id, authentication.getName(), "UPDATED", "checklist item added");
        return ResponseEntity.ok(saved);
    }

    @PatchMapping("/{id}/subtasks/{subtaskId}")
    public ResponseEntity<?> updateSubtask(@PathVariable Long id, @PathVariable Long subtaskId,
                                            @RequestBody Map<String, Object> updates, Authentication authentication) {
        Optional<Todo> todoOpt = todoService.findById(id);
        if (todoOpt.isEmpty() || !canAccess(todoOpt.get(), authentication.getName())) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        Optional<Subtask> subtaskOpt = subtaskRepository.findById(subtaskId);
        if (subtaskOpt.isEmpty() || !subtaskOpt.get().getTodoId().equals(id)) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Checklist item not found"));
        }
        Subtask subtask = subtaskOpt.get();
        if (updates.containsKey("done")) {
            subtask.setDone(Boolean.parseBoolean(String.valueOf(updates.get("done"))));
        }
        if (updates.containsKey("text")) {
            String text = String.valueOf(updates.get("text")).trim();
            if (text.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Checklist item text is required"));
            }
            if (text.length() > 200) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Checklist item must be under 200 characters"));
            }
            subtask.setText(text);
        }
        return ResponseEntity.ok(subtaskRepository.save(subtask));
    }

    @DeleteMapping("/{id}/subtasks/{subtaskId}")
    public ResponseEntity<?> deleteSubtask(@PathVariable Long id, @PathVariable Long subtaskId, Authentication authentication) {
        Optional<Todo> todoOpt = todoService.findById(id);
        if (todoOpt.isEmpty() || !canAccess(todoOpt.get(), authentication.getName())) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        Optional<Subtask> subtaskOpt = subtaskRepository.findById(subtaskId);
        if (subtaskOpt.isEmpty() || !subtaskOpt.get().getTodoId().equals(id)) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Checklist item not found"));
        }
        subtaskRepository.delete(subtaskOpt.get());
        return ResponseEntity.ok(Map.of("status", "ok"));
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
        if (todo.isDeleted()) {
            return false;
        }
        return username.equals(todo.getCreatedByUsername()) || username.equals(todo.getAssigneeUsername());
    }

    /** ENHANCEMENT ("roles & permissions"): see AdminController's javadoc on why this is a plain field check rather than Spring Security's hasRole(). */
    private boolean isAdminCaller(Authentication authentication) {
        return userRepository.findByUsername(authentication.getName())
                .map(com.example.sysfoo.model.User::isAdmin)
                .orElse(false);
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
