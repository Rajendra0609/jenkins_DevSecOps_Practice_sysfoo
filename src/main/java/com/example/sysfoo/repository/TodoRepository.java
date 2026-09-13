package com.example.sysfoo.repository;

import com.example.sysfoo.model.Todo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TodoRepository extends JpaRepository<Todo, Long> {

    // Matches PostRepository's pattern — now that Todo has a real createdAt
    // column, the "Newest first" default actually means something server-side.
    // Kept for callers that legitimately want the whole table (e.g. a future
    // admin export); the main dashboard listing below is what actually
    // enforces visibility + pagination.
    List<Todo> findAllByOrderByCreatedAtDesc();

    /**
     * CORRECTNESS FIX ("no pagination anywhere... fine at demo scale, will
     * fall over with real data"): TodoController used to call
     * findAllNewestFirst() (the whole table, every request) and filter down
     * to "mine or assigned to me" IN JAVA. That's two problems in one query:
     * it fetches every user's tasks just to throw most of them away, and it
     * never had a LIMIT at all. This pushes both the visibility filter and
     * the page bound down into the database.
     *
     * Also excludes soft-deleted rows (see Todo.deleted) — a deleted task
     * should vanish from the list without touching this query's shape.
     */
    @Query("SELECT t FROM Todo t WHERE t.deleted = false "
            + "AND (t.createdByUsername = :username OR t.assigneeUsername = :username) "
            + "ORDER BY t.createdAt DESC")
    Page<Todo> findVisibleToUser(@Param("username") String username, Pageable pageable);

    @Query("SELECT COUNT(t) FROM Todo t WHERE t.deleted = false AND t.createdByUsername = :username")
    long countCreatedBy(@Param("username") String username);

    @Query("SELECT COUNT(t) FROM Todo t WHERE t.deleted = false AND t.assigneeUsername = :username")
    long countAssignedTo(@Param("username") String username);

    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(issue_key, 6) AS BIGINT)), 0) FROM todo WHERE issue_key LIKE 'TASK-%'", nativeQuery = true)
    Long findMaxIssueNumber();

    /** ENHANCEMENT ("search across tasks and posts... nothing global"): matches task text, tags, issue keys, and the richer Jira-like metadata fields, scoped to what the caller can already see. */
    @Query("SELECT t FROM Todo t WHERE t.deleted = false "
            + "AND (t.createdByUsername = :username OR t.assigneeUsername = :username) "
            + "AND (LOWER(t.text) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR LOWER(t.tagsCsv) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR LOWER(t.issueKey) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR LOWER(t.ticketType) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR LOWER(t.component) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR LOWER(t.team) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR LOWER(t.folder) LIKE LOWER(CONCAT('%', :q, '%'))) "
            + "ORDER BY t.createdAt DESC")
    List<Todo> search(@Param("username") String username, @Param("q") String query, Pageable pageable);
}
