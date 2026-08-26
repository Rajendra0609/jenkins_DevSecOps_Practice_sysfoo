package com.example.sysfoo.controller;

import com.example.sysfoo.model.Todo;
import com.example.sysfoo.service.TodoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/todos")
public class TodoController {

    private static final Set<String> VALID_PRIORITIES = Set.of("high", "medium", "low");

    @Autowired
    private TodoService todoService;

    @PostMapping
    public ResponseEntity<?> addTodo(@RequestBody Todo todo) {
        // FIX: the original endpoint had no validation and would happily persist
        // a Todo with a null/blank text — this silently corrupted the task list
        // (empty task rows) whenever the client sent a malformed request.
        if (todo.getText() == null || todo.getText().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Task text is required"));
        }
        if (todo.getText().length() > 200) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Task text must be under 200 characters"));
        }

        // BUG FIX: priority is now a real, validated, persisted column instead
        // of being silently dropped on the floor (see Todo.java / model comment).
        String priority = todo.getPriority() == null ? "medium" : todo.getPriority().trim().toLowerCase();
        if (!VALID_PRIORITIES.contains(priority)) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Priority must be high, medium or low"));
        }
        todo.setPriority(priority);
        todo.setDone(false);

        Todo savedTodo = todoService.save(todo);
        return ResponseEntity.ok(savedTodo);
    }

    @GetMapping
    public ResponseEntity<List<Todo>> getAllTodos() {
        List<Todo> todos = todoService.findAllNewestFirst();
        return ResponseEntity.ok(todos);
    }

    /**
     * BUG FIX: there was previously no way to mark a task done, edit its text,
     * or change its priority on the server — those actions only ever mutated
     * the frontend's in-memory array, so a page reload (or a teammate loading
     * the dashboard fresh) reverted every task to "active", "medium priority".
     * Accepts a partial update — only the fields present in the body are
     * changed, everything else on the task is left as-is.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<?> updateTodo(@PathVariable Long id, @RequestBody Map<String, Object> updates) {
        Optional<Todo> existing = todoService.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        Todo todo = existing.get();

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

        if (updates.containsKey("name")) {
            Object rawName = updates.get("name");
            todo.setName(rawName == null ? null : String.valueOf(rawName).trim());
        }

        Todo saved = todoService.save(todo);
        return ResponseEntity.ok(saved);
    }

    /**
     * BUG FIX: this endpoint didn't exist at all. "Remove", "Clear Done", and
     * "Clear All" in the dashboard only removed tasks from the browser's local
     * array — the rows stayed in the database forever and reappeared on the
     * next page load. See TodoService.delete().
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTodo(@PathVariable Long id) {
        boolean deleted = todoService.delete(id);
        if (!deleted) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Task not found"));
        }
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Task deleted"));
    }
}
