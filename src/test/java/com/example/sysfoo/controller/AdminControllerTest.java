package com.example.sysfoo.controller;

import com.example.sysfoo.model.User;
import com.example.sysfoo.repository.UserRepository;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BUG FIX (test coverage gap): AdminController is new this pass and had no
// test at all — these are the regression tests for the two things most
// worth locking in: a non-admin can't reach any of it, and the last
// remaining admin can't demote themselves into a zero-admin app.
@WebMvcTest(AdminController.class)
@AutoConfigureMockMvc(addFilters = false)
public class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    @Test
    @WithMockUser(username = "bob")
    public void nonAdminGetsNotFoundForUserList() throws Exception {
        User bob = new User("bob", "hashed", null, "Bob"); // defaults to MEMBER
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "alice")
    public void adminCanListUsersWithoutExposingPasswords() throws Exception {
        User alice = new User("alice", "hashed", "alice@example.com", "Alice");
        alice.setRole(User.ROLE_ADMIN);
        User bob = new User("bob", "hashed", null, "Bob");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(userRepository.findAll()).thenReturn(List.of(alice, bob));

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].role").value("ADMIN"))
                .andExpect(content -> {
                    String body = content.getResponse().getContentAsString();
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("hashed"),
                            "Response leaked a password hash: " + body);
                });
    }

    @Test
    @WithMockUser(username = "alice")
    public void adminCanPromoteAnotherUser() throws Exception {
        User alice = new User("alice", "hashed", null, "Alice");
        alice.setRole(User.ROLE_ADMIN);
        User bob = new User("bob", "hashed", null, "Bob");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));

        mockMvc.perform(patch("/api/admin/users/bob/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    // SECURITY/CORRECTNESS regression test: the sole admin demoting
    // themselves would leave the app with zero admins and no way back in
    // short of editing the database directly.
    @Test
    @WithMockUser(username = "alice")
    public void soleAdminCannotDemoteSelf() throws Exception {
        User alice = new User("alice", "hashed", null, "Alice");
        alice.setRole(User.ROLE_ADMIN);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(userRepository.findAll()).thenReturn(List.of(alice));

        mockMvc.perform(patch("/api/admin/users/alice/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "alice")
    public void adminCanUnlockAccount() throws Exception {
        User alice = new User("alice", "hashed", null, "Alice");
        alice.setRole(User.ROLE_ADMIN);
        User bob = new User("bob", "hashed", null, "Bob");
        bob.setFailedLoginAttempts(5);
        bob.setLockedUntil(java.time.LocalDateTime.now().plusMinutes(10));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));

        mockMvc.perform(post("/api/admin/users/bob/unlock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
