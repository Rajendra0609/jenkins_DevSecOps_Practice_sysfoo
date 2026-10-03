package com.example.sysfoo.controller;

import com.example.sysfoo.model.Attachment;
import com.example.sysfoo.model.Comment;
import com.example.sysfoo.model.IssueLink;
import com.example.sysfoo.model.Project;
import com.example.sysfoo.model.Sprint;
import com.example.sysfoo.model.Subtask;
import com.example.sysfoo.model.Todo;
import com.example.sysfoo.model.User;
import com.example.sysfoo.repository.AttachmentRepository;
import com.example.sysfoo.repository.CommentRepository;
import com.example.sysfoo.repository.IssueLinkRepository;
import com.example.sysfoo.repository.ProjectRepository;
import com.example.sysfoo.repository.TodoRepository;
import com.example.sysfoo.repository.SprintRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/todos")
public class TodoController {

    private static final Set<String> VALID_PRIORITIES = Set.of("high", "medium", "low");
    private static final Set<String> VALID_STATUSES = Set.of(
            Todo.STATUS_TODO, Todo.STATUS_IN_PROGRESS, Todo.STATUS_IN_REVIEW, Todo.STATUS_DONE
    );

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
    private SprintRepository sprintRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private IssueLinkRepository issueLinkRepository;

    @Autowired
    private TodoRepository todoRepository;

    /** Guards the read-increment-write of Project.nextIssueNumber so two simultaneous creates never share a key. */
    private static final Object ISSUE_NUMBER_LOCK = new Object();

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
        // ENHANCEMENT ("Jira-style status workflow"): every new task starts
        // at To Do, same as a fresh Jira issue — setDone(false) also forces
        // status back to TODO (see Todo.setDone's javadoc), so any status
        // the client might have sent on creation is intentionally ignored.
        todo.setDone(false);

        // ENHANCEMENT ("tags/labels"): validated (not just accepted as-is)
        // so a caller can't sneak a value past the 200-char DB column via a
        // huge tag list — Todo.setTags() itself just joins whatever it's
        // given, so the length check has to happen here, before saving.
        ResponseEntity<?> tagsError = validateTags(todo.getTags());
        if (tagsError != null) {
            return tagsError;
        }

        // Jira-style fields (type, description, points, sprint, components, epic, time).
        ResponseEntity<?> jiraError = validateJiraFieldsOnCreate(todo, authentication.getName());
        if (jiraError != null) {
            return jiraError;
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

        assignIssueKey(todo);
        Todo savedTodo = todoService.save(todo);
        savedTodo.refreshIssueKey();
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

        if (updates.containsKey("done")) {
            Object rawDone = updates.get("done");
            boolean done = Boolean.parseBoolean(String.valueOf(rawDone));
            if (done != todo.isDone()) {
                taskAuditService.log(id, authentication.getName(), done ? "COMPLETED" : "REOPENED",
                        done ? "marked complete" : "reopened");
            }
            todo.setDone(done);
        }

        // ENHANCEMENT ("Jira-style status workflow — To Do / In Progress /
        // In Review / Done instead of just done/not-done"): the richer
        // workflow transition. If a request somehow sends both "done" and
        // "status", status wins (handled second, so it's the one that
        // sticks) — the frontend never sends both at once, but a direct
        // API caller might, and status is the more specific of the two.
        if (updates.containsKey("status")) {
            Object rawStatus = updates.get("status");
            String newStatus = rawStatus == null ? "" : String.valueOf(rawStatus).trim().toUpperCase();
            if (!VALID_STATUSES.contains(newStatus)) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message",
                        "Status must be one of TODO, IN_PROGRESS, IN_REVIEW, DONE"));
            }
            String previousStatus = todo.getStatus();
            if (!newStatus.equals(previousStatus)) {
                String action = Todo.STATUS_DONE.equals(newStatus) ? "COMPLETED"
                        : Todo.STATUS_DONE.equals(previousStatus) ? "REOPENED"
                        : "UPDATED";
                taskAuditService.log(id, authentication.getName(), action,
                        "status: " + statusLabel(previousStatus) + " \u2192 " + statusLabel(newStatus));
            }
            todo.setStatus(newStatus);
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

        ResponseEntity<?> jiraError = applyJiraUpdates(todo, updates, authentication.getName());
        if (jiraError != null) {
            return jiraError;
        }

        Todo saved = todoService.save(todo);
        saved.refreshIssueKey();
        eventBroadcastService.broadcast("todos-changed");
        return ResponseEntity.ok(saved);
    }

    // ── Jira-style fields ──────────────────────────────────────────────────
    private static final Set<String> VALID_TYPES = Set.of(Todo.TYPE_TASK, Todo.TYPE_BUG, Todo.TYPE_STORY, Todo.TYPE_EPIC);
    private static final int MAX_DESCRIPTION = 4000;
    private static final int MAX_STORY_POINTS = 100;
    private static final int MAX_HOURS = 9999;
    private static final int MAX_COMPONENTS = 6;
    private static final int MAX_COMPONENT_LENGTH = 24;

    private ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("status", "error", "message", message));
    }

    private ResponseEntity<?> validateJiraFieldsOnCreate(Todo todo, String actor) {
        if (todo.getProjectId() == null) {
            return bad("Project is required");
        }
        if (projectRepository.findById(todo.getProjectId()).isEmpty()) {
            return bad("Unknown project");
        }
        String type = todo.getIssueType() == null ? Todo.TYPE_TASK : todo.getIssueType().trim().toUpperCase();
        if (!VALID_TYPES.contains(type)) {
            return bad("Issue type must be TASK, BUG, STORY or EPIC");
        }
        todo.setIssueType(type);
        if (todo.getDescription() != null && todo.getDescription().length() > MAX_DESCRIPTION) {
            return bad("Description must be under " + MAX_DESCRIPTION + " characters");
        }
        ResponseEntity<?> err = checkRange(todo.getStoryPoints(), MAX_STORY_POINTS, "Story points");
        if (err == null) err = checkRange(todo.getEstimateHours(), MAX_HOURS, "Estimate");
        if (err == null) err = checkRange(todo.getLoggedHours(), MAX_HOURS, "Time logged");
        if (err == null) err = validateComponents(todo.getComponents());
        if (err == null) err = validateLength(todo.getFixVersion(), MAX_FIX_VERSION, "Fix version");
        if (err == null) err = validateLength(todo.getEnvironment(), MAX_ENVIRONMENT, "Environment");
        if (err == null) err = validateSprintRef(todo.getSprintId(), todo.getProjectId());
        if (err == null) {
            if (Todo.TYPE_EPIC.equals(type)) {
                todo.setEpicId(null);
            } else {
                err = validateEpicRef(todo.getEpicId(), null, actor, todo.getProjectId());
            }
        }
        return err;
    }

    private ResponseEntity<?> checkRange(Integer value, int max, String label) {
        if (value != null && (value < 0 || value > max)) {
            return bad(label + " must be between 0 and " + max);
        }
        return null;
    }

    private ResponseEntity<?> validateComponents(List<String> components) {
        if (components.size() > MAX_COMPONENTS) {
            return bad("A task can have at most " + MAX_COMPONENTS + " components");
        }
        for (String c : components) {
            if (c.length() > MAX_COMPONENT_LENGTH) {
                return bad("Each component must be under " + MAX_COMPONENT_LENGTH + " characters");
            }
        }
        return null;
    }

    private ResponseEntity<?> validateSprintRef(Long sprintId, Long projectId) {
        if (sprintId == null) return null;
        Optional<Sprint> sprint = sprintRepository.findById(sprintId);
        if (sprint.isEmpty()) return bad("Unknown sprint");
        if (Sprint.CLOSED.equals(sprint.get().getState())) return bad("That sprint is already completed");
        if (sprint.get().getProjectId() != null && !sprint.get().getProjectId().equals(projectId)) {
            return bad("That sprint belongs to a different project");
        }
        return null;
    }

    private ResponseEntity<?> validateEpicRef(Long epicId, Long selfId, String actor, Long projectId) {
        if (epicId == null) return null;
        if (epicId.equals(selfId)) return bad("An issue can't be its own epic");
        Optional<Todo> epic = todoService.findById(epicId);
        if (epic.isEmpty() || !canAccess(epic.get(), actor) || !Todo.TYPE_EPIC.equals(epic.get().getIssueType())) {
            return bad("Unknown epic");
        }
        if (epic.get().getProjectId() != null && !epic.get().getProjectId().equals(projectId)) {
            return bad("That epic belongs to a different project");
        }
        return null;
    }

    private static final int MAX_FIX_VERSION = 40;
    private static final int MAX_ENVIRONMENT = 300;

    private ResponseEntity<?> validateLength(String value, int max, String label) {
        if (value != null && value.length() > max) {
            return bad(label + " must be under " + max + " characters");
        }
        return null;
    }

    /** Looks up the real key ("SYS-7") of another issue, for audit-log lines. */
    private String issueKeyOf(Long todoId) {
        if (todoId == null) return null;
        return todoService.findById(todoId).map(Todo::getIssueKey).orElse("#" + todoId);
    }

    /** Stamps the new issue with its project key and the project's next issue number. */
    private void assignIssueKey(Todo todo) {
        synchronized (ISSUE_NUMBER_LOCK) {
            Project project = projectRepository.findById(todo.getProjectId()).orElseThrow();
            int number = project.getNextIssueNumber();
            project.setNextIssueNumber(number + 1);
            projectRepository.save(project);
            todo.setProjectKey(project.getProjectKey());
            todo.setIssueNumber(number);
        }
    }

    private String sprintName(Long sprintId) {
        if (sprintId == null) return null;
        return sprintRepository.findById(sprintId).map(Sprint::getName).orElse("#" + sprintId);
    }

    private static Integer toInteger(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        return new java.math.BigDecimal(s).intValueExact();
    }

    private static Long toLong(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        return new java.math.BigDecimal(s).longValueExact();
    }

    /** Handles the Jira-style keys of a PATCH body. Returns an error response, or null on success. */
    private ResponseEntity<?> applyJiraUpdates(Todo todo, Map<String, Object> updates, String actor) {
        Long id = todo.getId();
        try {
            if (updates.containsKey("projectId")) {
                Long newProjectId = toLong(updates.get("projectId"));
                if (!java.util.Objects.equals(newProjectId, todo.getProjectId())) {
                    if (newProjectId == null) return bad("Project is required");
                    Optional<Project> target = projectRepository.findById(newProjectId);
                    if (target.isEmpty()) return bad("Unknown project");
                    if (Todo.TYPE_EPIC.equals(todo.getIssueType()) && todoRepository.countByEpicIdAndDeletedFalse(id) > 0) {
                        return bad("This epic still has child issues — move or unlink them first");
                    }
                    String oldKey = todo.getIssueKey();
                    // A sprint and an epic belong to one project, so they don't travel with the issue.
                    todo.setSprintId(null);
                    todo.setEpicId(null);
                    todo.setProjectId(newProjectId);
                    assignIssueKey(todo);
                    todo.refreshIssueKey();
                    taskAuditService.log(id, actor, "UPDATED", "moved to project " + target.get().getName()
                            + ": " + oldKey + " \u2192 " + todo.getIssueKey() + " (sprint and epic cleared)");
                }
            }
            if (updates.containsKey("issueType")) {
                String type = String.valueOf(updates.get("issueType")).trim().toUpperCase();
                if (!VALID_TYPES.contains(type)) {
                    return bad("Issue type must be TASK, BUG, STORY or EPIC");
                }
                taskAuditService.logFieldChange(id, actor, "type", todo.getIssueType(), type);
                todo.setIssueType(type);
                if (Todo.TYPE_EPIC.equals(type)) {
                    todo.setEpicId(null);
                }
            }
            if (updates.containsKey("description")) {
                Object raw = updates.get("description");
                String description = raw == null ? null : String.valueOf(raw);
                if (description != null && description.length() > MAX_DESCRIPTION) {
                    return bad("Description must be under " + MAX_DESCRIPTION + " characters");
                }
                if (description != null && description.isBlank()) description = null;
                if (!java.util.Objects.equals(description, todo.getDescription())) {
                    taskAuditService.log(id, actor, "UPDATED", "description updated");
                }
                todo.setDescription(description);
            }
            if (updates.containsKey("storyPoints")) {
                Integer points = toInteger(updates.get("storyPoints"));
                ResponseEntity<?> err = checkRange(points, MAX_STORY_POINTS, "Story points");
                if (err != null) return err;
                taskAuditService.logFieldChange(id, actor, "story points", todo.getStoryPoints(), points);
                todo.setStoryPoints(points);
            }
            if (updates.containsKey("estimateHours")) {
                Integer hours = toInteger(updates.get("estimateHours"));
                ResponseEntity<?> err = checkRange(hours, MAX_HOURS, "Estimate");
                if (err != null) return err;
                taskAuditService.logFieldChange(id, actor, "original estimate (h)", todo.getEstimateHours(), hours);
                todo.setEstimateHours(hours);
            }
            if (updates.containsKey("loggedHours")) {
                Integer hours = toInteger(updates.get("loggedHours"));
                ResponseEntity<?> err = checkRange(hours, MAX_HOURS, "Time logged");
                if (err != null) return err;
                taskAuditService.logFieldChange(id, actor, "time logged (h)", todo.getLoggedHours(), hours);
                todo.setLoggedHours(hours);
            }
            if (updates.containsKey("fixVersion")) {
                Object raw = updates.get("fixVersion");
                String value = raw == null ? null : String.valueOf(raw).trim();
                if (value != null && value.isEmpty()) value = null;
                ResponseEntity<?> err = validateLength(value, MAX_FIX_VERSION, "Fix version");
                if (err != null) return err;
                taskAuditService.logFieldChange(id, actor, "fix version", todo.getFixVersion(), value);
                todo.setFixVersion(value);
            }
            if (updates.containsKey("environment")) {
                Object raw = updates.get("environment");
                String value = raw == null ? null : String.valueOf(raw).trim();
                if (value != null && value.isEmpty()) value = null;
                ResponseEntity<?> err = validateLength(value, MAX_ENVIRONMENT, "Environment");
                if (err != null) return err;
                if (!java.util.Objects.equals(value, todo.getEnvironment())) {
                    taskAuditService.log(id, actor, "UPDATED", "environment updated");
                }
                todo.setEnvironment(value);
            }
            if (updates.containsKey("components")) {
                Object raw = updates.get("components");
                List<String> components;
                if (raw instanceof List<?> rawList) {
                    components = rawList.stream().map(String::valueOf).map(String::trim).filter(x -> !x.isEmpty()).toList();
                } else if (raw == null) {
                    components = List.of();
                } else {
                    return bad("Components must be an array of strings");
                }
                ResponseEntity<?> err = validateComponents(components);
                if (err != null) return err;
                List<String> previous = todo.getComponents();
                todo.setComponents(components);
                if (!previous.equals(todo.getComponents())) {
                    taskAuditService.log(id, actor, "UPDATED", "components: " + (previous.isEmpty() ? "(none)" : String.join(", ", previous))
                            + " \u2192 " + (todo.getComponents().isEmpty() ? "(none)" : String.join(", ", todo.getComponents())));
                }
            }
            if (updates.containsKey("sprintId")) {
                Long sprintId = toLong(updates.get("sprintId"));
                if (!java.util.Objects.equals(sprintId, todo.getSprintId())) {
                    ResponseEntity<?> err = validateSprintRef(sprintId, todo.getProjectId());
                    if (err != null) return err;
                    taskAuditService.logFieldChange(id, actor, "sprint", sprintName(todo.getSprintId()), sprintName(sprintId));
                    todo.setSprintId(sprintId);
                }
            }
            if (updates.containsKey("epicId")) {
                Long epicId = toLong(updates.get("epicId"));
                if (!java.util.Objects.equals(epicId, todo.getEpicId())) {
                    if (epicId != null && Todo.TYPE_EPIC.equals(todo.getIssueType())) {
                        return bad("An epic can't belong to another epic");
                    }
                    ResponseEntity<?> err = validateEpicRef(epicId, id, actor, todo.getProjectId());
                    if (err != null) return err;
                    taskAuditService.logFieldChange(id, actor, "epic", issueKeyOf(todo.getEpicId()), issueKeyOf(epicId));
                    todo.setEpicId(epicId);
                }
            }
        } catch (NumberFormatException | ArithmeticException e) {
            return bad("A numeric field had an invalid value");
        }
        return null;
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
        // An epic's children must not keep pointing at a deleted epic, and links to a
        // deleted issue are removed so nothing is left dangling.
        if (Todo.TYPE_EPIC.equals(todo.getIssueType())) {
            for (Todo child : todoRepository.findByEpicIdAndDeletedFalse(id)) {
                child.setEpicId(null);
                todoRepository.save(child);
            }
        }
        issueLinkRepository.deleteAll(issueLinkRepository.findBySourceIdOrTargetId(id, id));
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
    // ── Issue links (blocks / is blocked by / relates to / duplicates) ─────────
    private static final Set<String> LINK_INPUTS = Set.of("BLOCKS", "IS_BLOCKED_BY", "DUPLICATES", "IS_DUPLICATED_BY", "RELATES");

    private static String relationLabel(String linkType, boolean fromSource) {
        return switch (linkType) {
            case IssueLink.BLOCKS -> fromSource ? "blocks" : "is blocked by";
            case IssueLink.DUPLICATES -> fromSource ? "duplicates" : "is duplicated by";
            default -> "relates to";
        };
    }

    /** Every link between two issues the caller can see — used for the "Blocked" badge on cards. */
    @GetMapping("/links")
    public List<Map<String, Object>> allLinks(Authentication authentication) {
        java.util.Set<Long> visible = new java.util.HashSet<>();
        for (Todo t : todoService.findVisibleToUser(authentication.getName(), org.springframework.data.domain.Pageable.unpaged()).getContent()) {
            visible.add(t.getId());
        }
        if (visible.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (IssueLink link : issueLinkRepository.findBySourceIdInOrTargetIdIn(visible, visible)) {
            if (visible.contains(link.getSourceId()) && visible.contains(link.getTargetId())) {
                out.add(Map.of("id", link.getId(), "sourceId", link.getSourceId(), "targetId", link.getTargetId(), "linkType", link.getLinkType()));
            }
        }
        return out;
    }

    @GetMapping("/{id}/links")
    public ResponseEntity<?> listLinks(@PathVariable Long id, Authentication authentication) {
        Optional<Todo> found = todoService.findById(id);
        if (found.isEmpty() || !canAccess(found.get(), authentication.getName())) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (IssueLink link : issueLinkRepository.findBySourceIdOrTargetId(id, id)) {
            boolean fromSource = link.getSourceId().equals(id);
            Long otherId = fromSource ? link.getTargetId() : link.getSourceId();
            Optional<Todo> other = todoService.findById(otherId);
            if (other.isEmpty() || !canAccess(other.get(), authentication.getName())) continue;
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("linkId", link.getId());
            row.put("linkType", link.getLinkType());
            row.put("relation", relationLabel(link.getLinkType(), fromSource));
            row.put("otherId", otherId);
            row.put("otherKey", other.get().getIssueKey());
            row.put("otherText", other.get().getText());
            row.put("otherStatus", other.get().getStatus());
            row.put("otherDone", other.get().isDone());
            row.put("otherType", other.get().getIssueType());
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/links")
    public ResponseEntity<?> addLink(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication authentication) {
        String actor = authentication.getName();
        Optional<Todo> found = todoService.findById(id);
        if (found.isEmpty() || !canAccess(found.get(), actor)) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        String type = body.get("type") == null ? "" : String.valueOf(body.get("type")).trim().toUpperCase();
        if (!LINK_INPUTS.contains(type)) {
            return bad("Link type must be BLOCKS, IS_BLOCKED_BY, DUPLICATES, IS_DUPLICATED_BY or RELATES");
        }
        Long targetId;
        try {
            targetId = toLong(body.get("targetId"));
        } catch (NumberFormatException | ArithmeticException e) {
            return bad("Invalid linked issue");
        }
        if (targetId == null) return bad("Pick an issue to link to");
        if (targetId.equals(id)) return bad("An issue can't be linked to itself");
        Optional<Todo> target = todoService.findById(targetId);
        if (target.isEmpty() || !canAccess(target.get(), actor)) return bad("Unknown issue to link to");

        // Normalise the "is ..." forms by swapping direction, so each relationship is stored once.
        Long sourceId = id;
        Long linkTarget = targetId;
        String storedType = type;
        if (type.equals("IS_BLOCKED_BY")) { sourceId = targetId; linkTarget = id; storedType = IssueLink.BLOCKS; }
        if (type.equals("IS_DUPLICATED_BY")) { sourceId = targetId; linkTarget = id; storedType = IssueLink.DUPLICATES; }
        boolean exists = issueLinkRepository.existsBySourceIdAndTargetIdAndLinkType(sourceId, linkTarget, storedType)
                || (IssueLink.RELATES.equals(storedType) && issueLinkRepository.existsBySourceIdAndTargetIdAndLinkType(linkTarget, sourceId, storedType));
        if (exists) return bad("Those issues are already linked that way");

        IssueLink saved = issueLinkRepository.save(new IssueLink(sourceId, linkTarget, storedType, actor));
        boolean fromSource = sourceId.equals(id);
        taskAuditService.log(id, actor, "UPDATED", "linked: " + relationLabel(storedType, fromSource) + " " + target.get().getIssueKey());
        eventBroadcastService.broadcast("todos-changed");
        return ResponseEntity.ok(Map.of("linkId", saved.getId()));
    }

    @DeleteMapping("/{id}/links/{linkId}")
    public ResponseEntity<?> removeLink(@PathVariable Long id, @PathVariable Long linkId, Authentication authentication) {
        String actor = authentication.getName();
        Optional<Todo> found = todoService.findById(id);
        if (found.isEmpty() || !canAccess(found.get(), actor)) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        Optional<IssueLink> link = issueLinkRepository.findById(linkId);
        if (link.isEmpty() || !(link.get().getSourceId().equals(id) || link.get().getTargetId().equals(id))) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Link not found"));
        }
        boolean fromSource = link.get().getSourceId().equals(id);
        Long otherId = fromSource ? link.get().getTargetId() : link.get().getSourceId();
        String otherKey = todoService.findById(otherId).map(Todo::getIssueKey).orElse("#" + otherId);
        issueLinkRepository.delete(link.get());
        taskAuditService.log(id, actor, "UPDATED", "unlinked: " + relationLabel(link.get().getLinkType(), fromSource) + " " + otherKey);
        eventBroadcastService.broadcast("todos-changed");
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

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

    /** ENHANCEMENT ("Jira-style status workflow"): human-readable form for audit log lines — "IN_REVIEW" reads a lot worse than "In Review". */
    private String statusLabel(String status) {
        if (status == null) return "To Do";
        return switch (status) {
            case Todo.STATUS_IN_PROGRESS -> "In Progress";
            case Todo.STATUS_IN_REVIEW -> "In Review";
            case Todo.STATUS_DONE -> "Done";
            default -> "To Do";
        };
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
