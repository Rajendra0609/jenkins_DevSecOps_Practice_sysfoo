package com.example.sysfoo.repository;

import com.example.sysfoo.model.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findByTodoIdOrderByCreatedAtAsc(Long todoId);

    List<Comment> findByPostIdOrderByCreatedAtAsc(Long postId);

    long countByTodoId(Long todoId);

    /** Batched comment counts for a page of posts — see PostController.getAllPosts(). */
    List<Comment> findByPostIdIn(List<Long> postIds);
}
