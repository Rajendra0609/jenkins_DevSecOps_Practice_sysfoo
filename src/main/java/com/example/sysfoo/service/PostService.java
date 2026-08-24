package com.example.sysfoo.service;

import com.example.sysfoo.model.Post;
import com.example.sysfoo.repository.PostRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PostService {

    @Autowired
    private PostRepository postRepository;

    public Post save(Post post) {
        return postRepository.save(post);
    }

    public List<Post> findAllNewestFirst() {
        return postRepository.findAllByOrderByCreatedAtDesc();
    }
}
