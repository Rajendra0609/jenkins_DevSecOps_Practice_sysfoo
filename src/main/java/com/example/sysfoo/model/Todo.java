package com.example.sysfoo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

@Entity
public class Todo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The person this task is assigned to, referenced by username (see
     * User.username) — not free text. Nullable: a task can be unassigned.
     * ENHANCEMENT: this used to be a free-text "name" the creator typed by
     * hand, with a separately-typed, unvalidated notification email — so
     * anyone could send a task-notification email to any address at all
     * (an open-relay-style abuse vector) and there was no way to look a
     * task up by "tasks assigned to me". Tying it to a real username fixes
     * both: the assignee's email is now looked up from their account
     * (TodoController/TodoService), never typed by the creator, and task
     * visibility (see below) can be enforced server-side.
     */
    @Column(length = 40)
    private String assigneeUsername;

    /**
     * Denormalized display name of the assignee, captured at assignment time
     * so the task list doesn't need a join just to render a name — same
     * pattern as Post.author. Kept in sync with assigneeUsername by
     * TodoService.
     */
    @Column(length = 60)
    private String name;

    /**
     * ENHANCEMENT: username of whoever created this task (the "assigner").
     * Non-null for every task created going forward — needed for the new
     * visibility rule: only the creator and the assignee can see a task
     * (see TodoController.getAllTodos()). Nullable at the JPA level only to
     * tolerate any pre-existing rows from before this column existed; the
     * application always sets it for new tasks.
     */
    @Column(length = 40)
    private String createdByUsername;

    /** The task description */
    private String text;

    /**
     * BUG FIX: "priority" (high/medium/low) and "done" didn't exist on this
     * entity at all, even though the dashboard UI has a priority picker and a
     * "mark complete" button. Because there was nowhere to persist them, the
     * frontend tracked both in memory only — every page reload silently reset
     * every task's priority to "medium" and every completed task back to
     * "active". Storing them here is what makes those actions actually stick.
     */
    @Column(nullable = false, length = 10)
    private String priority = "medium";

    @Column(nullable = false)
    private boolean done = false;

    /**
     * BUG FIX: with no createdAt column, the frontend stamped every task
     * loaded from the server with the CURRENT load time (the same instant,
     * for every row) instead of when it was actually created — "Newest/Oldest
     * first" sorting was meaningless after any reload, and older tasks
     * misleadingly displayed "just now" timestamps. Matches the pattern
     * already used by Post/User.
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Todo() {
    }

    public Todo(String text) {
        this.text = text;
    }

    public Todo(String name, String text) {
        this.name = name;
        this.text = text;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
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

    public String getAssigneeUsername() {
        return assigneeUsername;
    }

    public void setAssigneeUsername(String assigneeUsername) {
        this.assigneeUsername = assigneeUsername;
    }

    public String getCreatedByUsername() {
        return createdByUsername;
    }

    public void setCreatedByUsername(String createdByUsername) {
        this.createdByUsername = createdByUsername;
    }
}
