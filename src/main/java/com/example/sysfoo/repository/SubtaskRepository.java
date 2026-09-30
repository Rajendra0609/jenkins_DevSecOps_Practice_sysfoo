package com.example.sysfoo.repository;

import com.example.sysfoo.model.Subtask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubtaskRepository extends JpaRepository<Subtask, Long> {

    List<Subtask> findByTodoIdOrderByCreatedAtAsc(Long todoId);

    long countByTodoId(Long todoId);

    long countByTodoIdAndDoneTrue(Long todoId);
}
