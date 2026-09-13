package com.example.sysfoo.repository;

import com.example.sysfoo.model.TaskAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskAuditLogRepository extends JpaRepository<TaskAuditLog, Long> {

    List<TaskAuditLog> findByTodoIdOrderByCreatedAtDesc(Long todoId);
}
