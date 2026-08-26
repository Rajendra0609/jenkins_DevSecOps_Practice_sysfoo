package com.example.sysfoo.controller;

import com.example.sysfoo.model.Todo;
import com.example.sysfoo.service.TodoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
// own request handling, not the security filter chain.
@WebMvcTest(TodoController.class)
@AutoConfigureMockMvc(addFilters = false)
public class TodoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TodoService todoService;

    @Test
    public void addTodoRejectsBlankText() throws Exception {
        mockMvc.perform(post("/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    public void addTodoRejectsOversizedText() throws Exception {
        String longText = "x".repeat(201);
        mockMvc.perform(post("/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + longText + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Task text must be under 200 characters"));
    }

    @Test
    public void addTodoRejectsInvalidPriority() throws Exception {
        mockMvc.perform(post("/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Ship the release\",\"priority\":\"urgent\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Priority must be high, medium or low"));
    }

    @Test
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
                .andExpect(jsonPath("$.done").value(false));
    }

    @Test
    public void updateTodoReturns404ForUnknownId() throws Exception {
        when(todoService.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/todos/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void updateTodoMarksDone() throws Exception {
        Todo existing = new Todo("Alice", "Ship the release");
        existing.setId(1L);
        when(todoService.findById(1L)).thenReturn(Optional.of(existing));
        when(todoService.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(patch("/todos/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true));
    }

    @Test
    public void updateTodoRejectsBlankText() throws Exception {
        Todo existing = new Todo("Alice", "Ship the release");
        existing.setId(1L);
        when(todoService.findById(1L)).thenReturn(Optional.of(existing));

        mockMvc.perform(patch("/todos/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void deleteTodoReturns404ForUnknownId() throws Exception {
        when(todoService.delete(99L)).thenReturn(false);

        mockMvc.perform(delete("/todos/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void deleteTodoSucceeds() throws Exception {
        when(todoService.delete(1L)).thenReturn(true);

        mockMvc.perform(delete("/todos/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
