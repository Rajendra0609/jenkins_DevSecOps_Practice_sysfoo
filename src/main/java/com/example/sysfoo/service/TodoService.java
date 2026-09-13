package com.example.sysfoo.service;

import com.example.sysfoo.model.Todo;
import com.example.sysfoo.repository.TodoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class TodoService {

    @Autowired
    private TodoRepository todoRepository;

    public Todo save(Todo todo) {
        if (todo.getIssueKey() == null || todo.getIssueKey().isBlank()) {
            String nextKey = nextIssueKey();
            todo.setIssueKey(nextKey);
        }
        normalizeLegacyStatus(todo);
        return todoRepository.save(todo);
    }

    public String nextIssueKey() {
        Long maxNumber = todoRepository.findMaxIssueNumber();
        long next = (maxNumber == null ? 0L : maxNumber) + 1L;
        return "TASK-" + next;
    }

    public void normalizeLegacyStatus(Todo todo) {
        if (todo.getStatus() == null || todo.getStatus().isBlank()) {
            todo.setStatus(todo.isDone() ? "DONE" : "TO_DO");
        }
        if (todo.isDone() && !"DONE".equals(todo.getStatus())) {
            todo.setStatus("DONE");
        }
        if (!todo.isDone() && "DONE".equals(todo.getStatus())) {
            todo.setDone(true);
        }
    }

    public List<Todo> findAll() {
        return todoRepository.findAll();
    }

    public List<Todo> findAllNewestFirst() {
        return todoRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * CORRECTNESS FIX (N+1 / no pagination): the creator-or-assignee
     * visibility check and the page bound both now happen in the database
     * query itself (see TodoRepository.findVisibleToUser) instead of
     * TodoController fetching every task in the system and filtering with
     * a Java stream.
     */
    public Page<Todo> findVisibleToUser(String username, Pageable pageable) {
        return todoRepository.findVisibleToUser(username, pageable);
    }

    /**
     * CORRECTNESS FIX (N+1): backs UserController's profile counts. Two
     * COUNT(*) queries with a WHERE clause, instead of loading the entire
     * todos table into memory just to call .stream().filter().count() on it
     * twice.
     */
    public long countCreatedBy(String username) {
        return todoRepository.countCreatedBy(username);
    }

    public long countAssignedTo(String username) {
        return todoRepository.countAssignedTo(username);
    }

    /** ENHANCEMENT ("search across tasks and posts"): see TodoRepository.search. */
    public List<Todo> search(String username, String query, Pageable pageable) {
        return todoRepository.search(username, query, pageable);
    }

    public Optional<Todo> findById(Long id) {
        return todoRepository.findById(id);
    }

    /**
     * BUG FIX: there was previously no way to remove a Todo from the database
     * at all — TodoController had no delete endpoint, so the frontend's
     * "Remove" / "Clear Done" / "Clear All" actions only ever touched the
     * in-browser array. Every "deleted" task reappeared on the next reload.
     *
     * Hard delete — actually removes the row. Kept available (e.g. for a
     * future admin purge job) but no longer what the user-facing delete
     * endpoint calls; see softDelete below.
     *
     * @return true if a row existed and was deleted, false if id was unknown.
     */
    public boolean delete(Long id) {
        if (!todoRepository.existsById(id)) {
            return false;
        }
        todoRepository.deleteById(id);
        return true;
    }

    /**
     * CORRECTNESS FIX ("no soft-delete / audit trail... permanent and
     * untracked"): what TodoController.deleteTodo() actually calls now.
     * Flags the row instead of removing it — see Todo.deleted javadoc for
     * why comments/attachments are deliberately left untouched.
     */
    public Todo softDelete(Todo todo, String deletedByUsername) {
        todo.setDeleted(true);
        todo.setDeletedAt(LocalDateTime.now());
        todo.setDeletedByUsername(deletedByUsername);
        return todoRepository.save(todo);
    }
}
