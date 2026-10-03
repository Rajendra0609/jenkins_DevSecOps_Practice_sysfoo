package com.example.sysfoo.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

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

    @Column(nullable = false)
    private boolean done = false;

    /**
     * ENHANCEMENT ("Jira-style status workflow — To Do / In Progress / In
     * Review / Done instead of just done/not-done"): the real, richer
     * status a task can be in. Kept in sync with the older `done` boolean
     * (see setStatus/setDone below) rather than replacing it outright,
     * because `done` is still what a lot of existing logic reads —
     * bulk "Clear Done", the profile task counts, the old REST shape a
     * caller might already depend on. Both fields always agree: done is
     * true if and only if status is DONE.
     *
     * Nullable at the JPA level ONLY so existing rows from before this
     * column existed don't fail to load — migrateStatusIfMissing() below
     * derives a sensible value for those the first time they're read
     * (DONE if they were already done, TODO otherwise). Every task
     * created from here on always has status set via the field
     * initializer below.
     */
    @Column(length = 20)
    private String status = STATUS_TODO;

    public static final String STATUS_TODO = "TODO";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_IN_REVIEW = "IN_REVIEW";
    public static final String STATUS_DONE = "DONE";

    /** ENHANCEMENT ("Jira-style... issue keys"): "TASK-47", computed from the DB id — see getIssueKey()'s javadoc for why. */
    @Transient
    private String issueKey;

    public static final String ISSUE_KEY_PREFIX = "TASK";

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

    // ── Jira-style fields (issue type, description, story points, sprint, components, epic, time tracking) ──
    public static final String TYPE_TASK = "TASK";
    public static final String TYPE_BUG = "BUG";
    public static final String TYPE_STORY = "STORY";
    public static final String TYPE_EPIC = "EPIC";

    /** TASK / BUG / STORY / EPIC. Null on rows from before this column existed; getIssueType() treats null as TASK. */
    @Column(length = 10)
    private String issueType = TYPE_TASK;

    /** Long-form description, like the body of a Jira ticket. */
    @Column(length = 4000)
    private String description;

    /** Agile estimate. Null = not estimated. */
    @Column
    private Integer storyPoints;

    /** The Sprint this issue belongs to; null = in the backlog. */
    @Column
    private Long sprintId;

    /** Components, stored like tags: comma-separated, exposed as a JSON array. */
    @Column(length = 200)
    private String componentsCsv;

    /** The EPIC-type issue this issue rolls up to; null = no epic. */
    @Column
    private Long epicId;

    /** Time tracking, in hours. */
    @Column
    private Integer estimateHours;

    /** The project this issue lives in (fixed at creation), plus its per-project number: key = projectKey-issueNumber. */
    @Column
    private Long projectId;

    @Column(length = 10)
    private String projectKey;

    @Column
    private Integer issueNumber;

    @Column(length = 40)
    private String fixVersion;

    /** When the issue last moved into Done (null while open). Drives the sprint burndown chart. */
    @Column
    private java.time.LocalDateTime completedAt;

    @Column(length = 300)
    private String environment;

    @Column
    private Integer loggedHours;

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

    public boolean isDone() {
        return done;
    }

    /**
     * Best-effort translation for callers that only know about done/not-done
     * (older API shape, bulk actions like "Clear Done") — see the `status`
     * field's javadoc. Setting done=false always lands on TODO, even if the
     * task was actually IN_PROGRESS or IN_REVIEW; callers that care about
     * that distinction should use setStatus() instead.
     */
    public void setDone(boolean done) {
        trackCompletion(this.done, done);
        this.done = done;
        this.status = done ? STATUS_DONE : STATUS_TODO;
    }

    /** ENHANCEMENT ("Jira-style status workflow"): the real workflow state — see the field's javadoc for why it's kept in sync with `done` rather than replacing it. */
    public String getStatus() {
        return status != null ? status : (done ? STATUS_DONE : STATUS_TODO);
    }

    public void setStatus(String status) {
        boolean nowDone = STATUS_DONE.equals(status);
        trackCompletion(this.done, nowDone);
        this.status = status;
        this.done = nowDone;
    }

    private void trackCompletion(boolean wasDone, boolean nowDone) {
        if (nowDone && !wasDone) {
            this.completedAt = java.time.LocalDateTime.now();
        } else if (!nowDone) {
            this.completedAt = null;
        }
    }

    public java.time.LocalDateTime getCompletedAt() { return completedAt; }

    /** Recomputes the (transient) issue key — needed after the project/number changes on an already-loaded issue. */
    public void refreshIssueKey() {
        if (projectKey != null && issueNumber != null) {
            issueKey = projectKey + "-" + issueNumber;
        } else if (id != null) {
            issueKey = ISSUE_KEY_PREFIX + "-" + id;
        }
    }

    /**
     * Runs after every INSERT and every load from the database, so
     * issueKey is always populated without every controller/service call
     * site needing to remember to set it (same idea as Post's @Transient
     * likeCount/commentCount, but computed automatically here instead of
     * by the controller, since — unlike a like count — this never needs a
     * second query to work out).
     *
     * Also backfills `status` for any row that predates this column (see
     * its javadoc) — @PostLoad is the natural place for that lazy
     * migration, since it runs exactly once per row, the first time it's
     * read after this column was added.
     */
    @PostLoad
    @PostPersist
    private void onLoadOrPersist() {
        if (status == null) {
            status = done ? STATUS_DONE : STATUS_TODO;
        }
        if (projectKey != null && issueNumber != null) {
            issueKey = projectKey + "-" + issueNumber;
        } else if (id != null) {
            issueKey = ISSUE_KEY_PREFIX + "-" + id;
        }
    }

    /** "TASK-47" — see onLoadOrPersist(). Null only for a brand-new, not-yet-saved instance (no id yet to build a key from). */
    public String getIssueKey() {
        return issueKey;
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

    // ── Jira-style field accessors ─────────────────────────────────────────
    public String getIssueType() { return issueType != null ? issueType : TYPE_TASK; }
    public void setIssueType(String issueType) { this.issueType = issueType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getStoryPoints() { return storyPoints; }
    public void setStoryPoints(Integer storyPoints) { this.storyPoints = storyPoints; }

    public Long getSprintId() { return sprintId; }
    public void setSprintId(Long sprintId) { this.sprintId = sprintId; }

    public Long getEpicId() { return epicId; }
    public void setEpicId(Long epicId) { this.epicId = epicId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getProjectKey() { return projectKey; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey; }

    public Integer getIssueNumber() { return issueNumber; }
    public void setIssueNumber(Integer issueNumber) { this.issueNumber = issueNumber; }

    public String getFixVersion() { return fixVersion; }
    public void setFixVersion(String fixVersion) { this.fixVersion = fixVersion; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public Integer getEstimateHours() { return estimateHours; }
    public void setEstimateHours(Integer estimateHours) { this.estimateHours = estimateHours; }

    public Integer getLoggedHours() { return loggedHours; }
    public void setLoggedHours(Integer loggedHours) { this.loggedHours = loggedHours; }

    @JsonIgnore
    public String getComponentsCsv() { return componentsCsv; }
    public void setComponentsCsv(String componentsCsv) { this.componentsCsv = componentsCsv; }

    public List<String> getComponents() {
        if (componentsCsv == null || componentsCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(componentsCsv.split(","))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .toList();
    }

    public void setComponents(List<String> components) {
        if (components == null || components.isEmpty()) {
            this.componentsCsv = null;
            return;
        }
        List<String> cleaned = new ArrayList<>();
        for (String c : components) {
            if (c == null) continue;
            String t = c.trim();
            if (!t.isEmpty() && !cleaned.contains(t)) {
                cleaned.add(t);
            }
        }
        this.componentsCsv = cleaned.isEmpty() ? null : String.join(",", cleaned);
    }
}
