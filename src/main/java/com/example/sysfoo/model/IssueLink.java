package com.example.sysfoo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

/**
 * A directed link between two issues, like Jira's issue links.
 * Stored once, from the "source" issue's point of view:
 *   BLOCKS      source blocks target        (target "is blocked by" source)
 *   DUPLICATES  source duplicates target    (target "is duplicated by" source)
 *   RELATES     symmetric "relates to"
 */
@Entity
public class IssueLink {

    public static final String BLOCKS = "BLOCKS";
    public static final String DUPLICATES = "DUPLICATES";
    public static final String RELATES = "RELATES";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sourceId;

    @Column(nullable = false)
    private Long targetId;

    @Column(nullable = false, length = 12)
    private String linkType;

    @Column(length = 40)
    private String createdByUsername;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public IssueLink() {
    }

    public IssueLink(Long sourceId, Long targetId, String linkType, String createdByUsername) {
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.linkType = linkType;
        this.createdByUsername = createdByUsername;
    }

    public Long getId() { return id; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getLinkType() { return linkType; }
    public void setLinkType(String linkType) { this.linkType = linkType; }
    public String getCreatedByUsername() { return createdByUsername; }
    public void setCreatedByUsername(String createdByUsername) { this.createdByUsername = createdByUsername; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
