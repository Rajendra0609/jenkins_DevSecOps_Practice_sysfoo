package com.example.sysfoo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

/**
 * CORRECTNESS FIX ("no soft-delete / audit trail... no 'who changed what
 * when'"): one row per change to a task — created, each field edit,
 * reassignment, completion toggle, and deletion — so a task's full history
 * can be reconstructed instead of only ever showing its current state.
 *
 * Deliberately a flat, append-only log rather than a generic "old
 * JSON / new JSON" diff blob: {@link #detail} is already a short
 * human-readable line (e.g. "priority: medium → high") because that's what
 * both an API consumer and the "History" panel in the UI actually want to
 * show, and it keeps this simple enough to not need its own service layer
 * of diffing logic.
 */
@Entity
public class TaskAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long todoId;

    @Column(nullable = false, length = 40)
    private String actorUsername;

    /** CREATED, UPDATED, COMPLETED, REOPENED, REASSIGNED, DELETED */
    @Column(nullable = false, length = 20)
    private String action;

    @Column(length = 255)
    private String detail;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public TaskAuditLog() {
    }

    public TaskAuditLog(Long todoId, String actorUsername, String action, String detail) {
        this.todoId = todoId;
        this.actorUsername = actorUsername;
        this.action = action;
        this.detail = detail;
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

    public String getActorUsername() {
        return actorUsername;
    }

    public void setActorUsername(String actorUsername) {
        this.actorUsername = actorUsername;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
