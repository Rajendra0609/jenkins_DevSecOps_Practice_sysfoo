package com.example.sysfoo.repository;

import com.example.sysfoo.model.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByTodoIdOrderByCreatedAtAsc(Long todoId);

    List<Attachment> findByPostIdOrderByCreatedAtAsc(Long postId);
}
