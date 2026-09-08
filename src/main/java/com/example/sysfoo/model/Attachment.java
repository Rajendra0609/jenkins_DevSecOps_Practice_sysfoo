package com.example.sysfoo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

/**
 * A single uploaded file, stored on disk (see FileStorageService) with just
 * its metadata here in the database. Shared by two features that both
 * needed "upload a file, enforce a size limit, serve it back later":
 *   - a task attachment (todoId set, postId null) — .txt/.zip only, ≤20MB,
 *     downloadable only by that task's creator/assignee (same rule as
 *     viewing the task itself)
 *   - a post's image and/or general file (postId set, todoId null) — the
 *     Watering Hole board is public, so these are public downloads too
 * Exactly one of todoId/postId is set on any given row.
 */
@Entity
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long todoId;

    private Long postId;

    /** Original filename as uploaded, shown to the user — never used as the on-disk path. */
    @Column(nullable = false, length = 255)
    private String originalFilename;

    /** Random, collision-proof on-disk filename (see FileStorageService) — never derived from user input. */
    @Column(nullable = false, length = 100)
    private String storedFilename;

    @Column(nullable = false, length = 120)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 40)
    private String uploadedByUsername;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Attachment() {
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

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getStoredFilename() {
        return storedFilename;
    }

    public void setStoredFilename(String storedFilename) {
        this.storedFilename = storedFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getUploadedByUsername() {
        return uploadedByUsername;
    }

    public void setUploadedByUsername(String uploadedByUsername) {
        this.uploadedByUsername = uploadedByUsername;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isImage() {
        return contentType != null && contentType.startsWith("image/");
    }
}
