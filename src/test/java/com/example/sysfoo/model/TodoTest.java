package com.example.sysfoo.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// BUG FIX (test coverage gap): the status/done sync logic behind the
// ENHANCEMENT ("Jira-style status workflow") is exactly the kind of small,
// easy-to-get-backwards logic that deserves a direct test rather than only
// being exercised indirectly through TodoControllerTest's mocks.
public class TodoTest {

    @Test
    public void newTodoDefaultsToTodoStatusAndNotDone() {
        Todo todo = new Todo("Ship the release");
        assertEquals(Todo.STATUS_TODO, todo.getStatus());
        assertFalse(todo.isDone());
    }

    @Test
    public void setStatusDoneAlsoMarksDone() {
        Todo todo = new Todo("Ship the release");
        todo.setStatus(Todo.STATUS_DONE);
        assertTrue(todo.isDone());
    }

    @Test
    public void setStatusInProgressMarksNotDone() {
        Todo todo = new Todo("Ship the release");
        todo.setStatus(Todo.STATUS_DONE); // start from done...
        todo.setStatus(Todo.STATUS_IN_PROGRESS); // ...and move it back
        assertFalse(todo.isDone());
        assertEquals(Todo.STATUS_IN_PROGRESS, todo.getStatus());
    }

    @Test
    public void setDoneTrueAlsoSetsStatusDone() {
        Todo todo = new Todo("Ship the release");
        todo.setDone(true);
        assertEquals(Todo.STATUS_DONE, todo.getStatus());
    }

    // Documents the one accepted lossy edge case: the old boolean-only
    // setter can't express "back to IN_REVIEW", only "back to TODO" — see
    // Todo.setDone's javadoc.
    @Test
    public void setDoneFalseAlwaysLandsOnTodoEvenFromInReview() {
        Todo todo = new Todo("Ship the release");
        todo.setStatus(Todo.STATUS_IN_REVIEW);
        todo.setDone(false);
        assertEquals(Todo.STATUS_TODO, todo.getStatus());
    }

    // Regression test for the fallback in getStatus() — the same fallback
    // that lets a pre-migration database row (no `status` column value)
    // still return a sensible status the first time it's read.
    @Test
    public void getStatusFallsBackToDoneFlagWhenStatusIsNull() {
        Todo todo = new Todo("Ship the release");
        todo.setStatus(null);
        assertEquals(Todo.STATUS_TODO, todo.getStatus());
        assertFalse(todo.isDone());
    }

    @Test
    public void issueKeyIsNullBeforeThePersistenceLifecycleRuns() {
        Todo todo = new Todo("Ship the release");
        // No id yet (never went through the JPA @PostPersist/@PostLoad
        // callback that computes it) — see Todo.onLoadOrPersist().
        assertNull(todo.getIssueKey());
    }
}
