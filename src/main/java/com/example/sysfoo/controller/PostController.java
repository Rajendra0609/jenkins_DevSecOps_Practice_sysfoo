package com.example.sysfoo.controller;

import com.example.sysfoo.model.Post;
import com.example.sysfoo.repository.UserRepository;
import com.example.sysfoo.service.PostService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    @Autowired
    private PostService postService;

    @Autowired
    private UserRepository userRepository;

    /** Public: anyone can read the board, signed in or not. */
    @GetMapping
    public ResponseEntity<List<Post>> getAllPosts() {
        return ResponseEntity.ok(postService.findAllNewestFirst());
    }

    /** Requires authentication — enforced by SecurityConfig for POST /api/posts. */
    @PostMapping
    public ResponseEntity<?> createPost(@RequestBody Map<String, String> body, Authentication authentication) {
        String title = body.getOrDefault("title", "").trim();
        String content = body.getOrDefault("content", "").trim();
        String imageUrl = body.getOrDefault("imageUrl", "").trim();

        if (title.isEmpty() || content.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Title and content are required"));
        }
        if (title.length() > 140) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Title must be under 140 characters"));
        }
        if (content.length() > 4000) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Content must be under 4000 characters"));
        }
        if (!imageUrl.isEmpty() && !(imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Image URL must start with http:// or https://"));
        }

        String username = authentication.getName();
        String author = userRepository.findByUsername(username)
                .map(u -> (u.getDisplayName() != null && !u.getDisplayName().isBlank()) ? u.getDisplayName() : username)
                .orElse(username);

        Post post = new Post(title, content, imageUrl.isEmpty() ? null : imageUrl, author);
        Post saved = postService.save(post);
        return ResponseEntity.ok(saved);
    }
}
