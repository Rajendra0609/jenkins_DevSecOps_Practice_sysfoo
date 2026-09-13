package com.example.sysfoo.repository;

import com.example.sysfoo.model.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByTodoIdOrderByCreatedAtAsc(Long todoId);

    List<Attachment> findByPostIdOrderByCreatedAtAsc(Long postId);

    /**
     * CORRECTNESS FIX (N+1 query — see PostController.getAllPosts()): one
     * query for every post on the page's attachments, instead of the
     * controller looping over each post and calling
     * findByPostIdOrderByCreatedAtAsc individually (1 query for the posts +
     * N more, one per post). The controller groups this flat list back by
     * postId in memory.
     */
    List<Attachment> findByPostIdInOrderByCreatedAtAsc(List<Long> postIds);
}
