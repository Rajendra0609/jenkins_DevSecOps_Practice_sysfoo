package com.example.sysfoo.repository;

import com.example.sysfoo.model.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {

    List<Post> findAllByOrderByCreatedAtDesc();

    // CORRECTNESS FIX (soft-delete — see Post.deleted): both listing queries
    // now exclude deleted posts, same as TodoRepository.findVisibleToUser.
    @Query("SELECT p FROM Post p WHERE p.deleted = false ORDER BY p.createdAt DESC")
    Page<Post> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** ENHANCEMENT ("search across tasks and posts... there's a task search box, but nothing global"). */
    @Query("SELECT p FROM Post p WHERE p.deleted = false "
            + "AND (LOWER(p.title) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(p.content) LIKE LOWER(CONCAT('%', :q, '%'))) "
            + "ORDER BY p.createdAt DESC")
    List<Post> search(@Param("q") String query, Pageable pageable);
}
