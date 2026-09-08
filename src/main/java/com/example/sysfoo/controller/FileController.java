package com.example.sysfoo.controller;

import com.example.sysfoo.model.Attachment;
import com.example.sysfoo.model.Todo;
import com.example.sysfoo.repository.AttachmentRepository;
import com.example.sysfoo.repository.TodoRepository;
import com.example.sysfoo.service.FileStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * GET /api/files/{id} — downloads a previously-uploaded attachment.
 *
 * Access rules:
 *   - Attached to a task (todoId set): only that task's creator or assignee
 *     may download it — same rule as viewing the task at all (see
 *     TodoController.getAllTodos()). Requires authentication.
 *   - Attached to a post (postId set): public, since the Watering Hole
 *     board itself is public (GET /api/posts has always been permitAll).
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private FileStorageService fileStorageService;

    @GetMapping("/{id}")
    public ResponseEntity<?> download(@PathVariable Long id, Authentication authentication) {
        Optional<Attachment> attachmentOpt = attachmentRepository.findById(id);
        if (attachmentOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "File not found"));
        }
        Attachment attachment = attachmentOpt.get();

        if (attachment.getTodoId() != null) {
            boolean authenticated = authentication != null && authentication.isAuthenticated()
                    && !"anonymousUser".equals(authentication.getPrincipal());
            if (!authenticated) {
                return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Login required"));
            }
            Optional<Todo> todoOpt = todoRepository.findById(attachment.getTodoId());
            if (todoOpt.isEmpty() || !canAccessTodo(todoOpt.get(), authentication.getName())) {
                // 404 rather than 403 — don't confirm to a caller that a task
                // they're not part of even exists.
                return ResponseEntity.status(404).body(Map.of("status", "error", "message", "File not found"));
            }
        }
        // else: attached to a post — public, no check needed.

        File file = fileStorageService.resolve(attachment).toFile();
        if (!file.exists()) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "File not found on disk"));
        }

        ContentDisposition disposition = (attachment.isImage() ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(attachment.getOriginalFilename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(file));
    }

    /** Same creator-or-assignee rule as TodoController.getAllTodos() — kept in one place would be nicer, small enough to duplicate for now. */
    private boolean canAccessTodo(Todo todo, String username) {
        return username.equals(todo.getCreatedByUsername()) || username.equals(todo.getAssigneeUsername());
    }
}
