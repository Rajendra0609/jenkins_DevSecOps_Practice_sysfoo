package com.example.sysfoo.repository;

import com.example.sysfoo.model.Todo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TodoRepository extends JpaRepository<Todo, Long> {

    // Matches PostRepository's pattern — now that Todo has a real createdAt
    // column, the "Newest first" default actually means something server-side.
    List<Todo> findAllByOrderByCreatedAtDesc();
}
