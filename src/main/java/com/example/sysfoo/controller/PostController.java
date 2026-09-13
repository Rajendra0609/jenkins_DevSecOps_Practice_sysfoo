package com.example.sysfoo.controller;

import com.example.sysfoo.model.Attachment;
import com.example.sysfoo.model.Comment;
import com.example.sysfoo.model.Post;
import com.example.sysfoo.model.PostLike;
import com.example.sysfoo.repository.AttachmentRepository;
import com.example.sysfoo.repository.CommentRepository;
import com.example.sysfoo.repository.PostLikeRepository;
import com.example.sysfoo.repository.UserRepository;
import com.example.sysfoo.service.FileStorageService;
import com.example.sysfoo.service.PostService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 15;
    private static final int MAX_COMMENT_LENGTH = 1000;

    @Autowired
    private PostService postService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private PostLikeRepository postLikeRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private com.example.sysfoo.service.EventBroadcastService eventBroadcastService;

    /**
     * Public: anyone can read the board, signed in or not.
     *
     * CORRECTNESS FIX (N+1 query): attachments, like counts, and comment
     * counts are each fetched with ONE batched query for the whole page
     * (see AttachmentRepository/PostLikeRepository/CommentRepository's
     * *In() methods) and grouped back up in memory, instead of running a
     * separate query per post for each of those three things.
     *
     * CORRECTNESS FIX (no pagination): bounded by page/size.
     *
     * ENHANCEMENT (likes/comments on posts): likeCount/likedByMe/
     * commentCount are populated here. likedByMe is only meaningful for a
     * signed-in caller — it's always false for an anonymous request, same
     * as "notifiable" being false-by-default elsewhere for logged-out
     * callers who have no username to check against.
     */
    @GetMapping
    public ResponseEntity<Page<Post>> getAllPosts(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            Authentication authentication) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        Page<Post> posts = postService.findAllNewestFirst(PageRequest.of(safePage, safeSize));
        List<Long> postIds = posts.getContent().stream().map(Post::getId).collect(Collectors.toList());

        Map<Long, List<Attachment>> attachmentsByPostId = attachmentRepository.findByPostIdInOrderByCreatedAtAsc(postIds)
                .stream()
                .collect(Collectors.groupingBy(Attachment::getPostId));

        Map<Long, List<PostLike>> likesByPostId = postLikeRepository.findByPostIdIn(postIds)
                .stream()
                .collect(Collectors.groupingBy(PostLike::getPostId));

        Set<Long> likedByMePostIds = isAuthenticated(authentication)
                ? postLikeRepository.findByPostIdInAndUsername(postIds, authentication.getName())
                    .stream().map(PostLike::getPostId).collect(Collectors.toSet())
                : new HashSet<>();

        Map<Long, List<Comment>> commentsByPostId = commentRepository.findByPostIdIn(postIds)
                .stream()
                .collect(Collectors.groupingBy(Comment::getPostId));

        posts.getContent().forEach(p -> {
            p.setAttachments(attachmentsByPostId.getOrDefault(p.getId(), List.of()));
            p.setLikeCount(likesByPostId.getOrDefault(p.getId(), List.of()).size());
            p.setLikedByMe(likedByMePostIds.contains(p.getId()));
            p.setCommentCount(commentsByPostId.getOrDefault(p.getId(), List.of()).size());
        });

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

        Post post = new Post(title, content, imageUrl.isEmpty() ? null : imageUrl, author, username);
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
        eventBroadcastService.broadcast("posts-changed");
        return ResponseEntity.ok(saved);
    }

    /**
     * ENHANCEMENT ("post editing/deleting... parity with what tasks already
     * have"): title/content/imageUrl only — swapping attachments out isn't
     * supported here (delete-and-repost covers that rare case without
     * adding multipart handling to a JSON PATCH endpoint). Author-only,
     * same rule as delete below.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<?> updatePost(@PathVariable Long id, @RequestBody Map<String, Object> updates, Authentication authentication) {
        Optional<Post> existing = postService.findById(id);
        if (existing.isEmpty() || existing.get().isDeleted()) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Post not found"));
        }
        Post post = existing.get();
        if (!authentication.getName().equals(post.getCreatedByUsername())) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Post not found"));
        }

        if (updates.containsKey("title")) {
            String title = String.valueOf(updates.get("title")).trim();
            if (title.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Title is required"));
            }
            if (title.length() > 140) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Title must be under 140 characters"));
            }
            post.setTitle(title);
        }
        if (updates.containsKey("content")) {
            String content = String.valueOf(updates.get("content")).trim();
            if (content.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Content is required"));
            }
            if (content.length() > 4000) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Content must be under 4000 characters"));
            }
            post.setContent(content);
        }
        if (updates.containsKey("imageUrl")) {
            Object raw = updates.get("imageUrl");
            String imageUrl = raw == null ? "" : String.valueOf(raw).trim();
            if (!imageUrl.isEmpty() && !(imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Image URL must start with http:// or https://"));
            }
            post.setImageUrl(imageUrl.isEmpty() ? null : imageUrl);
        }

        Post saved = postService.save(post);
        saved.setAttachments(attachmentRepository.findByPostIdOrderByCreatedAtAsc(saved.getId()));
        eventBroadcastService.broadcast("posts-changed");
        return ResponseEntity.ok(saved);
    }

    /**
     * ENHANCEMENT ("post editing/deleting... parity with what tasks already
     * have"): soft-delete, same pattern as TodoController.deleteTodo.
     * Likes/comments/attachments are left in place, same rationale as tasks
     * (fully recoverable, nothing silently destroyed).
     *
     * ENHANCEMENT ("roles & permissions... moderate posts"): an admin can
     * delete anyone's post (moderation), not just their own — unlike edit
     * (updatePost above), which stays author-only. Rewriting someone else's
     * words isn't a moderation action the same way removing them is.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePost(@PathVariable Long id, Authentication authentication) {
        Optional<Post> existing = postService.findById(id);
        if (existing.isEmpty() || existing.get().isDeleted()) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Post not found"));
        }
        Post post = existing.get();
        if (!authentication.getName().equals(post.getCreatedByUsername()) && !isAdminCaller(authentication)) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Post not found"));
        }
        postService.softDelete(post, authentication.getName());
        eventBroadcastService.broadcast("posts-changed");
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Post deleted"));
    }

    /**
     * ENHANCEMENT ("likes ... on posts"): toggles — liking an already-liked
     * post unlikes it. Any signed-in user, including the post's own author.
     */
    @PostMapping("/{id}/like")
    public ResponseEntity<?> toggleLike(@PathVariable Long id, Authentication authentication) {
        Optional<Post> existing = postService.findById(id);
        if (existing.isEmpty() || existing.get().isDeleted()) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Post not found"));
        }
        String username = authentication.getName();
        Optional<PostLike> existingLike = postLikeRepository.findByPostIdAndUsername(id, username);

        boolean nowLiked;
        if (existingLike.isPresent()) {
            postLikeRepository.delete(existingLike.get());
            nowLiked = false;
        } else {
            postLikeRepository.save(new PostLike(id, username));
            nowLiked = true;
        }
        long count = postLikeRepository.countByPostId(id);
        return ResponseEntity.ok(Map.of("status", "ok", "liked", nowLiked, "likeCount", count));
    }

    /** Public — same visibility as the post itself. */
    @GetMapping("/{id}/comments")
    public ResponseEntity<?> getComments(@PathVariable Long id) {
        Optional<Post> existing = postService.findById(id);
        if (existing.isEmpty() || existing.get().isDeleted()) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Post not found"));
        }
        return ResponseEntity.ok(commentRepository.findByPostIdOrderByCreatedAtAsc(id));
    }

    /** ENHANCEMENT ("comments ... on posts, parity with what tasks already have"): any signed-in user, not just the author. */
    @PostMapping("/{id}/comments")
    public ResponseEntity<?> addComment(@PathVariable Long id, @RequestBody Map<String, String> body, Authentication authentication) {
        Optional<Post> existing = postService.findById(id);
        if (existing.isEmpty() || existing.get().isDeleted()) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Post not found"));
        }
        String text = body.getOrDefault("text", "").trim();
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Comment text is required"));
        }
        if (text.length() > MAX_COMMENT_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Comment must be under " + MAX_COMMENT_LENGTH + " characters"));
        }

        String username = authentication.getName();
        String displayName = userRepository.findByUsername(username)
                .map(u -> (u.getDisplayName() != null && !u.getDisplayName().isBlank()) ? u.getDisplayName() : username)
                .orElse(username);

        Comment comment = Comment.forPost(id, username, displayName, text);
        return ResponseEntity.ok(commentRepository.save(comment));
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());
    }

    /** ENHANCEMENT ("roles & permissions"): see AdminController's javadoc on why this is a plain field check rather than Spring Security's hasRole(). */
    private boolean isAdminCaller(Authentication authentication) {
        return userRepository.findByUsername(authentication.getName())
                .map(com.example.sysfoo.model.User::isAdmin)
                .orElse(false);
    }
}
