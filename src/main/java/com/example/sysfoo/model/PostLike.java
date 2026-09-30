package com.example.sysfoo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * ENHANCEMENT ("likes/comments on posts, parity with what tasks already
 * have"): one row per (post, user) like. The unique constraint is what
 * makes "like" idempotent — a second like attempt from the same user just
 * fails the insert / is checked for up front, rather than counting the
 * same person's like twice.
 */
@Entity
@Table(
    name = "post_likes",
    uniqueConstraints = @UniqueConstraint(name = "uk_post_likes_post_user", columnNames = {"postId", "username"})
)
public class PostLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long postId;

    @Column(nullable = false, length = 40)
    private String username;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public PostLike() {
    }

    public PostLike(Long postId, String username) {
        this.postId = postId;
        this.username = username;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
