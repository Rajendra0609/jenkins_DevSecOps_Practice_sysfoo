package com.example.sysfoo.controller;

import com.example.sysfoo.model.User;
import com.example.sysfoo.repository.TodoRepository;
import com.example.sysfoo.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BUG FIX (test coverage gap): UserController had no dedicated test at all
// before this pass. That's exactly the endpoint that had the email-leak
// SECURITY FIX in it — an endpoint with a real privacy bug is precisely
// where a regression test earns its keep, so listUsersNeverExposesEmail
// below is a permanent guard against that leak coming back.
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
public class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private TodoRepository todoRepository;

    @Test
    @WithMockUser(username = "alice")
    public void listUsersNeverExposesEmail() throws Exception {
        User withEmail = new User("bob", "hashed", "bob@example.com", "Bob");
        User withoutEmail = new User("carol", "hashed", null, "Carol");
        when(userRepository.findAll()).thenReturn(List.of(withEmail, withoutEmail));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                // The raw address must never appear anywhere in the payload —
                // this is the regression test for the GET /api/users leak.
                .andExpect(content -> {
                    String body = content.getResponse().getContentAsString();
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("bob@example.com"),
                            "Response leaked a raw email address: " + body);
                })
                .andExpect(jsonPath("$[0].username").value("bob"))
                .andExpect(jsonPath("$[0].notifiable").value(true))
                .andExpect(jsonPath("$[1].username").value("carol"))
                .andExpect(jsonPath("$[1].notifiable").value(false));
    }

    @Test
    @WithMockUser(username = "alice")
    public void myProfileReturns404WhenUserMissing() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/users/me/profile"))
                .andExpect(status().isNotFound());
    }

    // CORRECTNESS FIX (N+1): this is the regression test for
    // UserController.myProfile() now using two COUNT queries
    // (todoRepository.countCreatedBy/countAssignedTo) instead of loading
    // the entire todos table and filtering it in Java.
    @Test
    @WithMockUser(username = "alice")
    public void myProfileReturnsCountsFromRepository() throws Exception {
        User alice = new User("alice", "hashed", "alice@example.com", "Alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(todoRepository.countCreatedBy("alice")).thenReturn(4L);
        when(todoRepository.countAssignedTo("alice")).thenReturn(2L);

        mockMvc.perform(get("/api/users/me/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.tasksCreated").value(4))
                .andExpect(jsonPath("$.tasksAssigned").value(2));
    }
}
