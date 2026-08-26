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

    /** The person / owner name for this task (e.g. "Alice") */
    private String name;

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
}
