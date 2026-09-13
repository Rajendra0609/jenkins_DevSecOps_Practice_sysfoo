package com.example.sysfoo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

/**
 * ENHANCEMENT ("subtasks/checklists... the task manager currently only has
 * priority + done/not-done"): a small, ordered (by creation time — no
 * separate position/reordering field, same "keep it simple" call as
 * Todo.tagsCsv) checklist that lives inside a single task. Visible to, and
 * editable by, the same creator-or-assignee pair who can see the task
 * itself — see TodoController's canAccess check, applied identically here.
 */
@Entity
public class Subtask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long todoId;

    @Column(nullable = false, length = 200)
    private String text;

    @Column(nullable = false)
    private boolean done = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Subtask() {
    }

    public Subtask(Long todoId, String text) {
        this.todoId = todoId;
        this.text = text;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTodoId() {
        return todoId;
    }

    public void setTodoId(Long todoId) {
        this.todoId = todoId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
