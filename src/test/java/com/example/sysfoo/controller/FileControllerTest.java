package com.example.sysfoo.controller;

import com.example.sysfoo.model.Attachment;
import com.example.sysfoo.model.Todo;
import com.example.sysfoo.repository.AttachmentRepository;
import com.example.sysfoo.repository.TodoRepository;
import com.example.sysfoo.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BUG FIX (test coverage gap): FileController had no dedicated test before
// this pass, despite being where the creator-or-assignee access check for
// task attachments actually lives — exactly the kind of access-control
// logic that most needs a regression test.
@WebMvcTest(FileController.class)
@AutoConfigureMockMvc(addFilters = false)
public class FileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AttachmentRepository attachmentRepository;

    @MockBean
    private TodoRepository todoRepository;

    @MockBean
    private FileStorageService fileStorageService;

    @Test
    public void downloadReturns404ForUnknownAttachment() throws Exception {
        when(attachmentRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/files/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void downloadRequiresLoginForTaskAttachment() throws Exception {
        Attachment attachment = new Attachment();
        attachment.setId(1L);
        attachment.setTodoId(10L);
        attachment.setContentType("text/plain");
        when(attachmentRepository.findById(1L)).thenReturn(Optional.of(attachment));

        // No @WithMockUser here — anonymous request.
        mockMvc.perform(get("/api/files/1"))
                .andExpect(status().isUnauthorized());
    }

    // SECURITY regression test: someone who is neither the task's creator
    // nor its assignee gets a 404 (not a 403 — matches TodoController's own
    // "don't confirm the task even exists" pattern), even for a real
    // attachment id.
    @Test
    @WithMockUser(username = "mallory")
    public void downloadRejectsNonCreatorAssigneeForTaskAttachment() throws Exception {
        Todo todo = new Todo("Ship the release");
        todo.setId(10L);
        todo.setCreatedByUsername("alice");
        todo.setAssigneeUsername("bob");

        Attachment attachment = new Attachment();
        attachment.setId(1L);
        attachment.setTodoId(10L);
        attachment.setContentType("text/plain");

        when(attachmentRepository.findById(1L)).thenReturn(Optional.of(attachment));
        when(todoRepository.findById(10L)).thenReturn(Optional.of(todo));

        mockMvc.perform(get("/api/files/1"))
                .andExpect(status().isNotFound());
    }

    // CORRECTNESS FIX (soft-delete consistency): a task's attachment isn't
    // downloadable once the task itself is soft-deleted, even by its
    // creator — same as every other sub-resource endpoint on a deleted task.
    @Test
    @WithMockUser(username = "alice")
    public void downloadRejectsAttachmentOnDeletedTask() throws Exception {
        Todo todo = new Todo("Ship the release");
        todo.setId(10L);
        todo.setCreatedByUsername("alice");
        todo.setDeleted(true);

        Attachment attachment = new Attachment();
        attachment.setId(1L);
        attachment.setTodoId(10L);
        attachment.setContentType("text/plain");

        when(attachmentRepository.findById(1L)).thenReturn(Optional.of(attachment));
        when(todoRepository.findById(10L)).thenReturn(Optional.of(todo));

        mockMvc.perform(get("/api/files/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "alice")
    public void downloadAllowsCreatorForTaskAttachment() throws Exception {
        Todo todo = new Todo("Ship the release");
        todo.setId(10L);
        todo.setCreatedByUsername("alice");

        Attachment attachment = new Attachment();
        attachment.setId(1L);
        attachment.setTodoId(10L);
        attachment.setOriginalFilename("notes.txt");
        attachment.setContentType("text/plain");

        when(attachmentRepository.findById(1L)).thenReturn(Optional.of(attachment));
        when(todoRepository.findById(10L)).thenReturn(Optional.of(todo));
        when(fileStorageService.loadAsResource(attachment, false)).thenReturn(new ByteArrayResource("hello".getBytes()));

        mockMvc.perform(get("/api/files/1"))
                .andExpect(status().isOk());
    }

    // Public board: no authentication required for a post's attachment.
    @Test
    public void downloadAllowsAnonymousForPostAttachment() throws Exception {
        Attachment attachment = new Attachment();
        attachment.setId(2L);
        attachment.setPostId(5L);
        attachment.setOriginalFilename("photo.png");
        attachment.setContentType("image/png");

        when(attachmentRepository.findById(2L)).thenReturn(Optional.of(attachment));
        when(fileStorageService.loadAsResource(attachment, false)).thenReturn(new ByteArrayResource("bytes".getBytes()));

        mockMvc.perform(get("/api/files/2"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "alice")
    public void downloadReturns404WhenNotInStorage() throws Exception {
        Todo todo = new Todo("Ship the release");
        todo.setId(10L);
        todo.setCreatedByUsername("alice");

        Attachment attachment = new Attachment();
        attachment.setId(1L);
        attachment.setTodoId(10L);
        attachment.setContentType("text/plain");

        when(attachmentRepository.findById(1L)).thenReturn(Optional.of(attachment));
        when(todoRepository.findById(10L)).thenReturn(Optional.of(todo));
        when(fileStorageService.loadAsResource(attachment, false)).thenReturn(new FileSystemResource("/nonexistent/path"));

        mockMvc.perform(get("/api/files/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("File not found in storage"));
    }

    // ENHANCEMENT ("image thumbnailing"): ?thumb=true routes to the 2-arg
    // loadAsResource overload with preferThumbnail=true — this is the
    // regression test that the query param actually reaches the service call.
    @Test
    public void downloadWithThumbParamRequestsThumbnail() throws Exception {
        Attachment attachment = new Attachment();
        attachment.setId(2L);
        attachment.setPostId(5L);
        attachment.setOriginalFilename("photo.png");
        attachment.setContentType("image/png");
        attachment.setThumbnailStoredFilename("thumb-abc.jpg");

        when(attachmentRepository.findById(2L)).thenReturn(Optional.of(attachment));
        when(fileStorageService.loadAsResource(attachment, true)).thenReturn(new ByteArrayResource("thumb-bytes".getBytes()));

        mockMvc.perform(get("/api/files/2?thumb=true"))
                .andExpect(status().isOk());
    }
}
