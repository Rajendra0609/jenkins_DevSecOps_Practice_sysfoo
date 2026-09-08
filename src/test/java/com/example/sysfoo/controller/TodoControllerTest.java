package com.example.sysfoo.controller;

import com.example.sysfoo.model.Todo;
import com.example.sysfoo.repository.AttachmentRepository;
import com.example.sysfoo.repository.CommentRepository;
import com.example.sysfoo.repository.UserRepository;
import com.example.sysfoo.service.FileStorageService;
import com.example.sysfoo.service.TodoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BUG FIX: TodoController had no dedicated test at all before this pass — its
// validation logic (blank text, oversized text, invalid priority) and the new
// PATCH/DELETE endpoints (which are what makes editing/completing/deleting a
// task actually persist) were previously untested.
// Security filters are disabled here, same as AuthControllerTest/
// SystemInfoControllerTest — this slice test only exercises TodoController's
// own request handling, not the security filter chain (that's what
// addFilters = false means). @WithMockUser still works with filters disabled
// — it populates the SecurityContext directly, which is what lets the
// controller's `Authentication authentication` parameter resolve, without
// needing SecurityConfig's actual authorization rules to run.
//
// ENHANCEMENT: TodoController now also depends on UserRepository (assignee
// lookup), CommentRepository/AttachmentRepository (comment/attachment
// sub-resources) and FileStorageService (attachment uploads) — all mocked
// here so the @WebMvcTest slice context has something to wire in, even
// though most tests below don't touch them directly.
@WebMvcTest(TodoController.class)
@AutoConfigureMockMvc(addFilters = false)
public class TodoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TodoService todoService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private CommentRepository commentRepository;

    @MockBean
    private AttachmentRepository attachmentRepository;

    @MockBean
    private FileStorageService fileStorageService;

    @Test
    @WithMockUser(username = "alice")
    public void addTodoRejectsBlankText() throws Exception {
        mockMvc.perform(post("/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    @WithMockUser(username = "alice")
    public void addTodoRejectsOversizedText() throws Exception {
        String longText = "x".repeat(201);
        mockMvc.perform(post("/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + longText + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Task text must be under 200 characters"));
    }

    @Test
    @WithMockUser(username = "alice")
    public void addTodoRejectsInvalidPriority() throws Exception {
        mockMvc.perform(post("/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Ship the release\",\"priority\":\"urgent\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Priority must be high, medium or low"));
    }

    @Test
    @WithMockUser(username = "alice")
    public void addTodoDefaultsPriorityToMedium() throws Exception {
        when(todoService.save(any(Todo.class))).thenAnswer(invocation -> {
            Todo t = invocation.getArgument(0);
            t.setId(1L);
            return t;
        });

        mockMvc.perform(post("/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Ship the release\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("medium"))
                .andExpect(jsonPath("$.done").value(false))
                // ENHANCEMENT: the creator is now stamped from the
                // authenticated user, not left blank.
                .andExpect(jsonPath("$.createdByUsername").value("alice"));
    }

    @Test
    @WithMockUser(username = "alice")
    public void addTodoRejectsUnknownAssignee() throws Exception {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        mockMvc.perform(post("/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Ship the release\",\"assigneeUsername\":\"nobody\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown assignee"));
    }

    // ENHANCEMENT: GET /todos now filters to only what the caller created or
    // is assigned to (see TodoController.getAllTodos()) — this is the actual
    // regression test for "assignee can only see the task, rest can't view
    // it": bob's task must not appear for alice.
    @Test
    @WithMockUser(username = "alice")
    public void getAllTodosOnlyReturnsOwnedOrAssignedTasks() throws Exception {
        Todo mine = new Todo("Ship the release");
        mine.setId(1L);
        mine.setCreatedByUsername("alice");

        Todo assignedToMe = new Todo("Review the PR");
        assignedToMe.setId(2L);
        assignedToMe.setCreatedByUsername("bob");
        assignedToMe.setAssigneeUsername("alice");

        Todo notMine = new Todo("Bob's private task");
        notMine.setId(3L);
        notMine.setCreatedByUsername("bob");

        when(todoService.findAllNewestFirst()).thenReturn(List.of(mine, assignedToMe, notMine));

        mockMvc.perform(get("/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].id").value(2));
    }

    @Test
    @WithMockUser(username = "alice")
    public void updateTodoReturns404ForUnknownId() throws Exception {
        when(todoService.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/todos/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "alice")
    public void updateTodoMarksDone() throws Exception {
        Todo existing = new Todo("Ship the release");
        existing.setId(1L);
        existing.setCreatedByUsername("alice");
        when(todoService.findById(1L)).thenReturn(Optional.of(existing));
        when(todoService.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(patch("/todos/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true));
    }

    @Test
    @WithMockUser(username = "alice")
    public void updateTodoRejectsBlankText() throws Exception {
        Todo existing = new Todo("Ship the release");
        existing.setId(1L);
        existing.setCreatedByUsername("alice");
        when(todoService.findById(1L)).thenReturn(Optional.of(existing));

        mockMvc.perform(patch("/todos/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    // ENHANCEMENT: someone who is neither the creator nor the assignee gets a
    // 404 (not a 403 — see TodoController's comment on why: not confirming
    // the task even exists to someone outside it), even though the row
    // itself is real.
    @Test
    @WithMockUser(username = "mallory")
    public void updateTodoReturns404WhenNotOwnerOrAssignee() throws Exception {
        Todo existing = new Todo("Ship the release");
        existing.setId(1L);
        existing.setCreatedByUsername("alice");
        when(todoService.findById(1L)).thenReturn(Optional.of(existing));

        mockMvc.perform(patch("/todos/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "alice")
    public void deleteTodoReturns404ForUnknownId() throws Exception {
        when(todoService.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/todos/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "alice")
    public void deleteTodoSucceeds() throws Exception {
        Todo existing = new Todo("Ship the release");
        existing.setId(1L);
        existing.setCreatedByUsername("alice");
        when(todoService.findById(1L)).thenReturn(Optional.of(existing));
        when(todoService.delete(1L)).thenReturn(true);

        mockMvc.perform(delete("/todos/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    // ENHANCEMENT: delete is creator-only — an assignee (not the creator)
    // gets 404, same as someone with no relationship to the task at all.
    @Test
    @WithMockUser(username = "assignee-bob")
    public void deleteTodoRejectsNonCreatorAssignee() throws Exception {
        Todo existing = new Todo("Ship the release");
        existing.setId(1L);
        existing.setCreatedByUsername("alice");
        existing.setAssigneeUsername("assignee-bob");
        when(todoService.findById(1L)).thenReturn(Optional.of(existing));

        mockMvc.perform(delete("/todos/1"))
                .andExpect(status().isNotFound());
    }
}
