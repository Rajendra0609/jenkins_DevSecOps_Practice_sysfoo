package com.example.sysfoo.repository;

import com.example.sysfoo.model.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findByTodoIdOrderByCreatedAtAsc(Long todoId);
}
