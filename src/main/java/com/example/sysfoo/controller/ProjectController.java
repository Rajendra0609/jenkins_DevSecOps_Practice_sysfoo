package com.example.sysfoo.controller;

import com.example.sysfoo.model.Project;
import com.example.sysfoo.repository.ProjectRepository;
import com.example.sysfoo.repository.SprintRepository;
import com.example.sysfoo.repository.TodoRepository;
import com.example.sysfoo.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Jira-style projects. Any signed-in user can list and create projects; only
 * the creator (or an admin) can edit or delete one. A project can only be
 * deleted while it has no issues and no sprints. The key is permanent.
 */
@RestController
@RequestMapping("/projects")
public class ProjectController {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");
    private static final int MAX_NAME = 60;
    private static final int MAX_DESCRIPTION = 300;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private SprintRepository sprintRepository;

    @Autowired
    private UserRepository userRepository;

    @GetMapping
    public List<Project> list() {
        return projectRepository.findAllByOrderByNameAsc();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, Authentication authentication) {
        Project project = new Project();
        String name = text(body.get("name"));
        if (name.isEmpty()) return bad("Project name is required");
        if (name.length() > MAX_NAME) return bad("Project name must be under " + MAX_NAME + " characters");
        project.setName(name);

        String key = text(body.get("projectKey")).toUpperCase();
        if (!KEY_PATTERN.matcher(key).matches()) {
            return bad("Project key must be 2-10 characters: capital letters and digits, starting with a letter");
        }
        if (projectRepository.existsByProjectKey(key)) return bad("Project key \"" + key + "\" is already in use");
        project.setProjectKey(key);

        ResponseEntity<?> error = applyOptional(project, body);
        if (error != null) return error;
        project.setCreatedByUsername(authentication.getName());
        return ResponseEntity.ok(projectRepository.save(project));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication authentication) {
        Optional<Project> found = projectRepository.findById(id);
        if (found.isEmpty()) return notFound();
        Project project = found.get();
        if (!canManage(project, authentication)) return forbidden();
        if (body.containsKey("name")) {
            String name = text(body.get("name"));
            if (name.isEmpty()) return bad("Project name is required");
            if (name.length() > MAX_NAME) return bad("Project name must be under " + MAX_NAME + " characters");
            project.setName(name);
        }
        ResponseEntity<?> error = applyOptional(project, body);
        if (error != null) return error;
        return ResponseEntity.ok(projectRepository.save(project));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication authentication) {
        Optional<Project> found = projectRepository.findById(id);
        if (found.isEmpty()) return notFound();
        Project project = found.get();
        if (!canManage(project, authentication)) return forbidden();
        if (todoRepository.countByProjectIdAndDeletedFalse(id) > 0 || sprintRepository.countByProjectId(id) > 0) {
            return bad("A project that has issues or sprints can't be deleted");
        }
        projectRepository.delete(project);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Project deleted"));
    }

    private ResponseEntity<?> applyOptional(Project project, Map<String, Object> body) {
        if (body.containsKey("description")) {
            String description = text(body.get("description"));
            if (description.length() > MAX_DESCRIPTION) return bad("Description must be under " + MAX_DESCRIPTION + " characters");
            project.setDescription(description.isEmpty() ? null : description);
        }
        if (body.containsKey("leadUsername")) {
            String lead = text(body.get("leadUsername"));
            if (lead.isEmpty()) {
                project.setLeadUsername(null);
            } else if (userRepository.findByUsername(lead).isEmpty()) {
                return bad("Unknown project lead");
            } else {
                project.setLeadUsername(lead);
            }
        }
        return null;
    }

    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private boolean canManage(Project project, Authentication authentication) {
        if (authentication.getName().equals(project.getCreatedByUsername())) return true;
        return userRepository.findByUsername(authentication.getName())
                .map(com.example.sysfoo.model.User::isAdmin)
                .orElse(false);
    }

    private ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("status", "error", "message", message));
    }

    private ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Project not found"));
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("status", "error", "message", "Only the project's creator or an admin can do that"));
    }
}
