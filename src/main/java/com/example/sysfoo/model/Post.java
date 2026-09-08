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

    public List<Attachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<Attachment> attachments) {
        this.attachments = attachments;
    }

    public Post() {
    }

    public Post(String title, String content, String imageUrl, String author) {
        this.title = title;
        this.content = content;
        this.imageUrl = imageUrl;
        this.author = author;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
