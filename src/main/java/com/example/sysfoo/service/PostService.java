package com.example.sysfoo.service;

import com.example.sysfoo.model.Post;
import com.example.sysfoo.repository.PostRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class PostService {

    @Autowired
    private PostRepository postRepository;

    public Post save(Post post) {
        return postRepository.save(post);
    }

    public Optional<Post> findById(Long id) {
        return postRepository.findById(id);
    }

    /** Kept for callers that legitimately want the whole (non-deleted-aware) board — see findAllNewestFirst(Pageable) for what the API actually serves. */
    public List<Post> findAllNewestFirst() {
        return postRepository.findAllByOrderByCreatedAtDesc();
    }

    /** CORRECTNESS FIX ("no pagination anywhere"): what PostController.getAllPosts() actually calls now. Excludes soft-deleted posts. */
    public Page<Post> findAllNewestFirst(Pageable pageable) {
        return postRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public List<Post> search(String query, Pageable pageable) {
        return postRepository.search(query, pageable);
    }

    /** ENHANCEMENT ("post editing/deleting... parity with what tasks already have"): soft-delete, same pattern as TodoService.softDelete. */
    public Post softDelete(Post post, String deletedByUsername) {
        post.setDeleted(true);
        post.setDeletedAt(LocalDateTime.now());
        post.setDeletedByUsername(deletedByUsername);
        return postRepository.save(post);
    }
}
