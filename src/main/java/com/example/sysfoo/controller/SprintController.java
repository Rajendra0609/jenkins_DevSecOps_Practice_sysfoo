package com.example.sysfoo.controller;

import com.example.sysfoo.model.Project;
import com.example.sysfoo.model.Sprint;
import com.example.sysfoo.model.Todo;
import com.example.sysfoo.repository.ProjectRepository;
import com.example.sysfoo.repository.SprintRepository;
import com.example.sysfoo.repository.TodoRepository;
import com.example.sysfoo.repository.UserRepository;
import com.example.sysfoo.service.EventBroadcastService;
import com.example.sysfoo.service.TaskAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Jira-style sprints. Any signed-in user can see and create sprints; only the
 * sprint's creator (or an admin) can edit, start, complete or delete one.
 * Only one sprint can be ACTIVE at a time.
 */
@RestController
@RequestMapping("/sprints")
public class SprintController {

    private static final int MAX_NAME = 60;
    private static final int MAX_GOAL = 300;
    private static final int DEFAULT_SPRINT_DAYS = 14;

    @Autowired
    private SprintRepository sprintRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskAuditService taskAuditService;

    @Autowired
    private EventBroadcastService eventBroadcastService;

    @GetMapping
    public List<Sprint> list() {
        return sprintRepository.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, Authentication authentication) {
        Sprint sprint = new Sprint();
        Long projectId;
        try {
            projectId = body.get("projectId") == null ? null : new java.math.BigDecimal(String.valueOf(body.get("projectId"))).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return bad("Invalid project");
        }
        if (projectId == null) return bad("Project is required");
        Optional<Project> project = projectRepository.findById(projectId);
        if (project.isEmpty()) return bad("Unknown project");
        sprint.setProjectId(projectId);
        ResponseEntity<?> error = applyFields(sprint, body, true);
        if (error != null) {
            return error;
        }
        sprint.setCreatedByUsername(authentication.getName());
        Sprint saved = sprintRepository.save(sprint);
        eventBroadcastService.broadcast("todos-changed");
        return ResponseEntity.ok(saved);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication authentication) {
        Optional<Sprint> found = sprintRepository.findById(id);
        if (found.isEmpty()) return notFound();
        Sprint sprint = found.get();
        if (!canManage(sprint, authentication)) return forbidden();
        if (Sprint.CLOSED.equals(sprint.getState())) return bad("A completed sprint can't be edited");
        ResponseEntity<?> error = applyFields(sprint, body, false);
        if (error != null) return error;
        Sprint saved = sprintRepository.save(sprint);
        eventBroadcastService.broadcast("todos-changed");
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<?> start(@PathVariable Long id, Authentication authentication) {
        Optional<Sprint> found = sprintRepository.findById(id);
        if (found.isEmpty()) return notFound();
        Sprint sprint = found.get();
        if (!canManage(sprint, authentication)) return forbidden();
        if (!Sprint.PLANNED.equals(sprint.getState())) return bad("Only a planned sprint can be started");
        Optional<Sprint> alreadyActive = sprintRepository.findFirstByProjectIdAndState(sprint.getProjectId(), Sprint.ACTIVE);
        if (alreadyActive.isPresent()) {
            return bad("\"" + alreadyActive.get().getName() + "\" is already active in this project — complete it first");
        }
        sprint.setState(Sprint.ACTIVE);
        if (sprint.getStartDate() == null) sprint.setStartDate(LocalDate.now());
        if (sprint.getEndDate() == null) sprint.setEndDate(sprint.getStartDate().plusDays(DEFAULT_SPRINT_DAYS));
        Sprint saved = sprintRepository.save(sprint);
        eventBroadcastService.broadcast("todos-changed");
        return ResponseEntity.ok(saved);
    }

    /** Completes the sprint: finished issues stay on it (history), unfinished ones return to the backlog. */
    @PostMapping("/{id}/complete")
    public ResponseEntity<?> complete(@PathVariable Long id, Authentication authentication) {
        Optional<Sprint> found = sprintRepository.findById(id);
        if (found.isEmpty()) return notFound();
        Sprint sprint = found.get();
        if (!canManage(sprint, authentication)) return forbidden();
        if (!Sprint.ACTIVE.equals(sprint.getState())) return bad("Only an active sprint can be completed");

        int completed = 0;
        int movedToBacklog = 0;
        for (Todo todo : todoRepository.findBySprintIdAndDeletedFalse(id)) {
            if (todo.isDone()) {
                completed++;
            } else {
                todo.setSprintId(null);
                todoRepository.save(todo);
                taskAuditService.log(todo.getId(), authentication.getName(), "UPDATED",
                        "sprint: " + sprint.getName() + " \u2192 (none) (sprint completed)");
                movedToBacklog++;
            }
        }
        sprint.setState(Sprint.CLOSED);
        sprint.setClosedAt(java.time.LocalDateTime.now());
        sprintRepository.save(sprint);
        eventBroadcastService.broadcast("todos-changed");
        return ResponseEntity.ok(Map.of("status", "ok", "completedIssues", completed, "movedToBacklog", movedToBacklog));
    }

    /** Only a planned (not yet started) sprint can be deleted; its issues go back to the backlog. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication authentication) {
        Optional<Sprint> found = sprintRepository.findById(id);
        if (found.isEmpty()) return notFound();
        Sprint sprint = found.get();
        if (!canManage(sprint, authentication)) return forbidden();
        if (!Sprint.PLANNED.equals(sprint.getState())) return bad("Only a planned sprint can be deleted");
        for (Todo todo : todoRepository.findBySprintIdAndDeletedFalse(id)) {
            todo.setSprintId(null);
            todoRepository.save(todo);
        }
        sprintRepository.delete(sprint);
        eventBroadcastService.broadcast("todos-changed");
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Sprint deleted"));
    }

    private ResponseEntity<?> applyFields(Sprint sprint, Map<String, Object> body, boolean creating) {
        if (creating || body.containsKey("name")) {
            String name = body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
            if (name.isEmpty()) return bad("Sprint name is required");
            if (name.length() > MAX_NAME) return bad("Sprint name must be under " + MAX_NAME + " characters");
            sprint.setName(name);
        }
        if (body.containsKey("goal")) {
            String goal = body.get("goal") == null ? null : String.valueOf(body.get("goal")).trim();
            if (goal != null && goal.length() > MAX_GOAL) return bad("Sprint goal must be under " + MAX_GOAL + " characters");
            sprint.setGoal(goal == null || goal.isEmpty() ? null : goal);
        }
        try {
            if (body.containsKey("startDate")) sprint.setStartDate(parseDate(body.get("startDate")));
            if (body.containsKey("endDate")) sprint.setEndDate(parseDate(body.get("endDate")));
        } catch (DateTimeParseException e) {
            return bad("Dates must be in YYYY-MM-DD format");
        }
        if (sprint.getStartDate() != null && sprint.getEndDate() != null && sprint.getEndDate().isBefore(sprint.getStartDate())) {
            return bad("End date can't be before the start date");
        }
        return null;
    }

    private static LocalDate parseDate(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        return LocalDate.parse(String.valueOf(raw));
    }

    private boolean canManage(Sprint sprint, Authentication authentication) {
        if (authentication.getName().equals(sprint.getCreatedByUsername())) return true;
        return userRepository.findByUsername(authentication.getName())
                .map(com.example.sysfoo.model.User::isAdmin)
                .orElse(false);
    }

    private ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("status", "error", "message", message));
    }

    private ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Sprint not found"));
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("status", "error", "message", "Only the sprint's creator or an admin can do that"));
    }
}
