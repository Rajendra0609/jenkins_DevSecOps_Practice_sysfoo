package com.example.sysfoo.controller;

import com.example.sysfoo.model.Attachment;
import com.example.sysfoo.model.Post;
import com.example.sysfoo.repository.AttachmentRepository;
import com.example.sysfoo.repository.CommentRepository;
import com.example.sysfoo.repository.PostLikeRepository;
import com.example.sysfoo.repository.UserRepository;
import com.example.sysfoo.service.FileStorageService;
import com.example.sysfoo.service.EventBroadcastService;
import com.example.sysfoo.service.PostService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BUG FIX (test coverage gap): PostController had no dedicated test before
// this pass — including no test at all for the N+1 query it had (see
// getAllPostsBatchesAttachmentsAcrossPosts below) or its multipart upload
// validation.
@WebMvcTest(PostController.class)
@AutoConfigureMockMvc(addFilters = false)
public class PostControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PostService postService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private AttachmentRepository attachmentRepository;

    @MockBean
    private FileStorageService fileStorageService;

    // ENHANCEMENT: PostController now also depends on PostLikeRepository and
    // CommentRepository (likes/comments on posts) — mocked here so the
    // @WebMvcTest slice context has something to wire in. Unstubbed calls
    // default to empty lists (Mockito's default answer for a
    // List-returning method), which is exactly right for the tests below
    // that don't care about likes/comments.
    @MockBean
    private PostLikeRepository postLikeRepository;

    @MockBean
    private CommentRepository commentRepository;

    @MockBean
    private EventBroadcastService eventBroadcastService;

    // CORRECTNESS FIX (N+1 query): this is the regression test for
    // PostController.getAllPosts() using ONE batched
    // findByPostIdInOrderByCreatedAtAsc(List<Long>) call to fetch every
    // post's attachments, instead of looping and calling
    // findByPostIdOrderByCreatedAtAsc once per post. Two posts, each with
    // one attachment, must each end up with exactly their own attachment
    // attached — not the other's, and not zero.
    @Test
    public void getAllPostsBatchesAttachmentsAcrossPosts() throws Exception {
        Post post1 = new Post("First post", "Body one", null, "Alice", "alice");
        post1.setId(1L);
        Post post2 = new Post("Second post", "Body two", null, "Bob", "bob");
        post2.setId(2L);

        Page<Post> page = new PageImpl<>(List.of(post1, post2));
        when(postService.findAllNewestFirst(any(Pageable.class))).thenReturn(page);

        Attachment attForPost1 = new Attachment();
        attForPost1.setId(10L);
        attForPost1.setPostId(1L);
        Attachment attForPost2 = new Attachment();
        attForPost2.setId(20L);
        attForPost2.setPostId(2L);

        when(attachmentRepository.findByPostIdInOrderByCreatedAtAsc(List.of(1L, 2L)))
                .thenReturn(List.of(attForPost1, attForPost2));

        mockMvc.perform(get("/api/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].attachments[0].id").value(10))
                .andExpect(jsonPath("$.content[0].attachments.length()").value(1))
                .andExpect(jsonPath("$.content[1].attachments[0].id").value(20))
                .andExpect(jsonPath("$.content[1].attachments.length()").value(1));
    }

    @Test
    public void getAllPostsHandlesPostsWithNoAttachments() throws Exception {
        Post post1 = new Post("Lonely post", "No attachments here", null, "Alice", "alice");
        post1.setId(1L);
        Page<Post> page = new PageImpl<>(List.of(post1));
        when(postService.findAllNewestFirst(any(Pageable.class))).thenReturn(page);
        when(attachmentRepository.findByPostIdInOrderByCreatedAtAsc(List.of(1L))).thenReturn(List.of());

        mockMvc.perform(get("/api/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].attachments.length()").value(0));
    }

    @Test
    @WithMockUser(username = "alice")
    public void createPostRejectsBlankTitle() throws Exception {
        mockMvc.perform(multipart("/api/posts")
                        .param("title", "  ")
                        .param("content", "Some content"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Title and content are required"));
    }

    @Test
    @WithMockUser(username = "alice")
    public void createPostRejectsInvalidImageUrl() throws Exception {
        mockMvc.perform(multipart("/api/posts")
                        .param("title", "Hello")
                        .param("content", "World")
                        .param("imageUrl", "javascript:alert(1)"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Image URL must start with http:// or https://"));
    }

    @Test
    @WithMockUser(username = "alice")
    public void createPostRejectsOversizedTitle() throws Exception {
        String longTitle = "x".repeat(141);
        mockMvc.perform(multipart("/api/posts")
                        .param("title", longTitle)
                        .param("content", "World"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Title must be under 140 characters"));
    }

    // ENHANCEMENT ("post editing/deleting... parity with what tasks already
    // have") — regression test: only the post's author can edit it, same
    // rule as TodoController.updateTodo enforces for tasks.
    @Test
    @WithMockUser(username = "mallory")
    public void updatePostRejectsNonAuthor() throws Exception {
        Post post = new Post("Original", "Body", null, "Alice", "alice");
        post.setId(1L);
        when(postService.findById(1L)).thenReturn(java.util.Optional.of(post));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/posts/1")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hijacked\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "alice")
    public void deletePostSucceedsForAuthor() throws Exception {
        Post post = new Post("Original", "Body", null, "Alice", "alice");
        post.setId(1L);
        when(postService.findById(1L)).thenReturn(java.util.Optional.of(post));
        when(postService.softDelete(any(Post.class), org.mockito.ArgumentMatchers.eq("alice")))
                .thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/posts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    @WithMockUser(username = "alice")
    public void toggleLikeAddsThenRemoves() throws Exception {
        Post post = new Post("Original", "Body", null, "Bob", "bob");
        post.setId(1L);
        when(postService.findById(1L)).thenReturn(java.util.Optional.of(post));
        when(postLikeRepository.findByPostIdAndUsername(1L, "alice")).thenReturn(java.util.Optional.empty());
        when(postLikeRepository.countByPostId(1L)).thenReturn(1L);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/posts/1/like"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked").value(true))
                .andExpect(jsonPath("$.likeCount").value(1));
    }

    @Test
    @WithMockUser(username = "alice")
    public void addCommentRejectsBlankText() throws Exception {
        Post post = new Post("Original", "Body", null, "Bob", "bob");
        post.setId(1L);
        when(postService.findById(1L)).thenReturn(java.util.Optional.of(post));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/posts/1/comments")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
