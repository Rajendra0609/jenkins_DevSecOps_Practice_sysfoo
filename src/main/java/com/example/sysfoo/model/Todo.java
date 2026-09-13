package com.example.sysfoo.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    /** New workflow status field for the Jira-like status lanes. */
    @Column(nullable = false, length = 20)
    private String status = "TO_DO";

    /** Stable human-readable issue/key reference for each task. */
    @Column(nullable = false, unique = true, length = 30)
    private String issueKey;

    /** Jira-like ticket type classification. */
    @Column(nullable = false, length = 20)
    private String ticketType = "TASK";

    /** Optional engineering component area like "Auth", "Billing", "UI". */
    @Column(length = 50)
    private String component;

    /** Optional cross-functional team owning the ticket. */
    @Column(length = 50)
    private String team;

    /** Optional folder/category bucket for team-centric task grouping. */
    @Column(length = 50)
    private String folder;

    @Deprecated
    @Column(nullable = false)
    private boolean done = false;

    /** ENHANCEMENT ("task due dates + reminders... the task manager currently only has priority + done/not-done"). */
    @Column
    private java.time.LocalDate dueDate;

    /**
     * ENHANCEMENT ("...tags/labels"): stored as a single comma-separated
     * column rather than a proper many-to-many join table — this app has no
     * other multi-value relationships and a handful of free-text labels per
     * task doesn't earn a whole extra table + repository. getTagList()/
     * setTagList() below are what the controller actually works with; this
     * raw column is intentionally not exposed directly in the JSON response
     * (see @JsonIgnore) so API consumers always see a real array.
     */
    @Column(length = 200)
    private String tagsCsv;

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

    /**
     * SECURITY/CORRECTNESS FIX ("no soft-delete / audit trail — deleting a
     * task is permanent and untracked"): deleting a task used to be a hard
     * SQL DELETE — the row, and any trace it ever existed, was gone
     * instantly and irreversibly. TodoController.deleteTodo() now flips
     * this flag instead. A soft-deleted task is excluded from every normal
     * query (see TodoRepository.findVisibleToUser) but the row — and its
     * comments/attachments — physically remain, recoverable, with a record
     * of who deleted it and when. See TaskAuditLog for the "who changed
     * what when" trail on everything else (created/edited/reassigned).
     */
    @Column(nullable = false)
    private boolean deleted = false;

    @Column
    private LocalDateTime deletedAt;

    @Column(length = 40)
    private String deletedByUsername;

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

    public String getStatus() {
        return status == null ? "TO_DO" : status;
    }

    public void setStatus(String status) {
        if (status == null || status.isBlank()) {
            this.status = "TO_DO";
        } else {
            String normalized = status.trim().toUpperCase();
            switch (normalized) {
                case "TODO":
                case "TO_DO":
                case "TO-DO":
                    this.status = "TO_DO";
                    break;
                case "IN_PROGRESS":
                case "INPROGRESS":
                    this.status = "IN_PROGRESS";
                    break;
                case "IN_REVIEW":
                case "INREVIEW":
                    this.status = "IN_REVIEW";
                    break;
                case "DONE":
                case "COMPLETED":
                    this.status = "DONE";
                    break;
                default:
                    this.status = "TO_DO";
            }
        }
        this.done = "DONE".equals(this.status);
    }

    public String getIssueKey() {
        return issueKey;
    }

    public void setIssueKey(String issueKey) {
        this.issueKey = issueKey;
    }

    public String getTicketType() {
        return ticketType == null || ticketType.isBlank() ? "TASK" : ticketType.trim().toUpperCase();
    }

    public void setTicketType(String ticketType) {
        if (ticketType == null || ticketType.isBlank()) {
            this.ticketType = "TASK";
        } else {
            String normalized = ticketType.trim().toUpperCase();
            switch (normalized) {
                case "BUG":
                case "FEATURE":
                case "TASK":
                case "CHORE":
                case "RESEARCH":
                case "IMPROVEMENT":
                    this.ticketType = normalized;
                    break;
                default:
                    this.ticketType = "TASK";
            }
        }
    }

    public String getComponent() {
        return component == null || component.isBlank() ? null : component.trim();
    }

    public void setComponent(String component) {
        this.component = component == null || component.isBlank() ? null : component.trim();
    }

    public String getTeam() {
        return team == null || team.isBlank() ? null : team.trim();
    }

    public void setTeam(String team) {
        this.team = team == null || team.isBlank() ? null : team.trim();
    }

    public String getFolder() {
        return folder == null || folder.isBlank() ? null : folder.trim();
    }

    public void setFolder(String folder) {
        this.folder = folder == null || folder.isBlank() ? null : folder.trim();
    }

    public boolean isDone() {
        return "DONE".equalsIgnoreCase(getStatus()) || done;
    }

    public void setDone(boolean done) {
        this.done = done;
        setStatus(done ? "DONE" : "TO_DO");
    }

    public java.time.LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(java.time.LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    @JsonIgnore
    public String getTagsCsv() {
        return tagsCsv;
    }

    public void setTagsCsv(String tagsCsv) {
        this.tagsCsv = tagsCsv;
    }

    /** What the API actually exposes as "tags" — a real JSON array, never the raw CSV. */
    public List<String> getTags() {
        if (tagsCsv == null || tagsCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tagsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public void setTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            this.tagsCsv = null;
            return;
        }
        List<String> cleaned = new ArrayList<>();
        for (String tag : tags) {
            if (tag == null) continue;
            String t = tag.trim();
            if (!t.isEmpty() && !cleaned.contains(t)) {
                cleaned.add(t);
            }
        }
        this.tagsCsv = cleaned.isEmpty() ? null : String.join(",", cleaned);
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
}
