package com.example.sysfoo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** A community post created by an authenticated user (e.g. an update or announcement). */
@Entity
@Table(name = "posts")
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 140)
    private String title;

    @Column(nullable = false, length = 4000)
    private String content;

    /** Optional image URL used to illustrate the post. */
    @Column(length = 1000)
    private String imageUrl;

    /** Display name (or username) of the post's author, captured at creation time. */
    @Column(nullable = false, length = 60)
    private String author;

    /**
     * ENHANCEMENT ("post editing/deleting... parity with what tasks already
     * have"): the actual account that created this post, used to enforce
     * "only the author can edit or delete" — {@link #author} alone can't be
     * used for that check since it's just a display label captured at
     * creation time (could collide across two people, or go stale if
     * someone later changes their display name).
     *
     * Nullable for backward compatibility: posts created before this field
     * existed have NULL here and simply can't be edited/deleted by anyone
     * through the API (PostController treats a null createdByUsername as
     * "no one is authorized"), rather than the app crashing on old rows.
     */
    @Column(length = 40)
    private String createdByUsername;

    /** CORRECTNESS FIX ("no soft-delete / audit trail... deleting a task OR POST is permanent") — see Todo.deleted for the identical pattern. */
    @Column(nullable = false)
    private boolean deleted = false;

    @Column
    private LocalDateTime deletedAt;

    @Column(length = 40)
    private String deletedByUsername;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * ENHANCEMENT: uploaded image/file(s) for this post, e.g. an uploaded
     * image (instead of, or alongside, imageUrl) and/or a separately
     * attached file — both go through FileStorageService/Attachment, same
     * as task attachments. @Transient because Attachment rows reference
     * this post by postId rather than through a JPA relation; PostService
     * populates this list when building the response.
     */
    @Transient
    private List<Attachment> attachments = new ArrayList<>();

    /** Populated by PostController (batched, not per-post — see getAllPosts()), not stored. */
    @Transient
    private long likeCount = 0;

    @Transient
    private boolean likedByMe = false;

    /** Populated by PostController: how many comments this post has (see getAllPosts()). */
    @Transient
    private long commentCount = 0;

    public List<Attachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<Attachment> attachments) {
        this.attachments = attachments;
    }

    public long getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(long likeCount) {
        this.likeCount = likeCount;
    }

    public boolean isLikedByMe() {
        return likedByMe;
    }

    public void setLikedByMe(boolean likedByMe) {
        this.likedByMe = likedByMe;
    }

    public long getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(long commentCount) {
        this.commentCount = commentCount;
    }

    public Post() {
    }

    public Post(String title, String content, String imageUrl, String author, String createdByUsername) {
        this.title = title;
        this.content = content;
        this.imageUrl = imageUrl;
        this.author = author;
        this.createdByUsername = createdByUsername;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getCreatedByUsername() {
        return createdByUsername;
    }

    public void setCreatedByUsername(String createdByUsername) {
        this.createdByUsername = createdByUsername;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getDeletedByUsername() {
        return deletedByUsername;
    }

    public void setDeletedByUsername(String deletedByUsername) {
        this.deletedByUsername = deletedByUsername;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
