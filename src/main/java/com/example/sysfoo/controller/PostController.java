package com.example.sysfoo.controller;

import com.example.sysfoo.model.Attachment;
import com.example.sysfoo.model.Post;
import com.example.sysfoo.repository.AttachmentRepository;
import com.example.sysfoo.repository.UserRepository;
import com.example.sysfoo.service.FileStorageService;
import com.example.sysfoo.service.PostService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    @Autowired
    private PostService postService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private FileStorageService fileStorageService;

    /** Public: anyone can read the board, signed in or not. */
    @GetMapping
    public ResponseEntity<List<Post>> getAllPosts() {
        List<Post> posts = postService.findAllNewestFirst();
        posts.forEach(p -> p.setAttachments(attachmentRepository.findByPostIdOrderByCreatedAtAsc(p.getId())));
        return ResponseEntity.ok(posts);
    }

    /**
     * Requires authentication — enforced by SecurityConfig for POST /api/posts.
     *
     * ENHANCEMENT: switched from a plain JSON body to multipart/form-data so
     * a post can carry real uploaded files, not just an external image URL:
     *   - "imageUrl" (optional, text)      — unchanged, an external link
     *   - "image"    (optional, file)      — an uploaded image, PNG/JPG/GIF/WEBP
     *   - "file"     (optional, file)      — a separate general attachment,
     *                                        ≤20MB (see FileStorageService)
     * All three are optional and independent — a post can have any
     * combination of them (including none).
     */
    @PostMapping
    public ResponseEntity<?> createPost(@RequestParam("title") String title,
                                         @RequestParam("content") String content,
                                         @RequestParam(value = "imageUrl", required = false) String imageUrlParam,
                                         @RequestParam(value = "image", required = false) MultipartFile imageFile,
                                         @RequestParam(value = "file", required = false) MultipartFile file,
                                         Authentication authentication) {
        title = title == null ? "" : title.trim();
        content = content == null ? "" : content.trim();
        String imageUrl = imageUrlParam == null ? "" : imageUrlParam.trim();

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

        boolean hasImageFile = imageFile != null && !imageFile.isEmpty();
        boolean hasFile = file != null && !file.isEmpty();

        try {
            if (hasImageFile) {
                fileStorageService.validatePostImage(imageFile);
            }
            if (hasFile) {
                fileStorageService.validatePostUpload(file);
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }

        String username = authentication.getName();
        String author = userRepository.findByUsername(username)
                .map(u -> (u.getDisplayName() != null && !u.getDisplayName().isBlank()) ? u.getDisplayName() : username)
                .orElse(username);

        Post post = new Post(title, content, imageUrl.isEmpty() ? null : imageUrl, author);
        Post saved = postService.save(post);

        try {
            if (hasImageFile) {
                fileStorageService.store(imageFile, null, saved.getId(), username);
            }
            if (hasFile) {
                fileStorageService.store(file, null, saved.getId(), username);
            }
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", "Post was created, but the upload could not be saved"));
        }

        saved.setAttachments(attachmentRepository.findByPostIdOrderByCreatedAtAsc(saved.getId()));
        return ResponseEntity.ok(saved);
    }
}
