package com.example.sysfoo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

/**
 * A Jira-style project: the container every issue is created in. The
 * project key (e.g. "SYS") prefixes every issue key ("SYS-12"), and each
 * project numbers its own issues from 1.
 */
@Entity
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 2-10 chars, uppercase letters/digits, starts with a letter. Immutable once created. */
    @Column(name = "project_key", nullable = false, unique = true, length = 10)
    private String projectKey;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(length = 300)
    private String description;

    /** Project lead (a username). Optional. */
    @Column(length = 40)
    private String leadUsername;

    @Column(length = 40)
    private String createdByUsername;

    /** The number the next issue in this project will get. */
    @Column(nullable = false)
    private int nextIssueNumber = 1;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Project() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectKey() { return projectKey; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLeadUsername() { return leadUsername; }
    public void setLeadUsername(String leadUsername) { this.leadUsername = leadUsername; }
    public String getCreatedByUsername() { return createdByUsername; }
    public void setCreatedByUsername(String createdByUsername) { this.createdByUsername = createdByUsername; }
    public int getNextIssueNumber() { return nextIssueNumber; }
    public void setNextIssueNumber(int nextIssueNumber) { this.nextIssueNumber = nextIssueNumber; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
