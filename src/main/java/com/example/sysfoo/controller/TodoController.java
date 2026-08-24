package com.example.sysfoo.controller;

import com.example.sysfoo.model.Todo;
import com.example.sysfoo.service.TodoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/todos")
public class TodoController {

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
        Todo savedTodo = todoService.save(todo);
        return ResponseEntity.ok(savedTodo);
    }

    @GetMapping
    public ResponseEntity<List<Todo>> getAllTodos() {
        List<Todo> todos = todoService.findAll();
        return ResponseEntity.ok(todos);
    }
}
