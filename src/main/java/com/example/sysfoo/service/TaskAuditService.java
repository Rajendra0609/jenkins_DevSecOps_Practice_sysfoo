package com.example.sysfoo.service;

import com.example.sysfoo.model.TaskAuditLog;
import com.example.sysfoo.repository.TaskAuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Records task history (see TaskAuditLog) and reads it back for the
 * GET /todos/{id}/audit endpoint. Kept deliberately dumb — it doesn't
 * inspect a Todo itself, just appends whatever line TodoController tells
 * it to, so it stays trivial to unit test and can't get out of sync with
 * whatever fields Todo happens to have.
 */
@Service
public class TaskAuditService {

    @Autowired
    private TaskAuditLogRepository auditLogRepository;

    public void log(Long todoId, String actorUsername, String action, String detail) {
        auditLogRepository.save(new TaskAuditLog(todoId, actorUsername, action, detail));
    }

    public List<TaskAuditLog> history(Long todoId) {
        return auditLogRepository.findByTodoIdOrderByCreatedAtDesc(todoId);
    }

    /** Convenience: logs a field change only if the value actually changed, e.g. "priority: medium → high". */
    public void logFieldChange(Long todoId, String actorUsername, String fieldLabel, Object oldValue, Object newValue) {
        if (Objects.equals(oldValue, newValue)) {
            return;
        }
        log(todoId, actorUsername, "UPDATED", fieldLabel + ": " + describe(oldValue) + " → " + describe(newValue));
    }

    private String describe(Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            return "(none)";
        }
        return value.toString();
    }
}
