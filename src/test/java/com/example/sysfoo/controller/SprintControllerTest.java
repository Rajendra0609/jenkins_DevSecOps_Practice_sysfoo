package com.example.sysfoo.controller;

import com.example.sysfoo.model.Sprint;
import com.example.sysfoo.repository.SprintRepository;
import com.example.sysfoo.repository.TodoRepository;
import com.example.sysfoo.repository.UserRepository;
import com.example.sysfoo.service.EventBroadcastService;
import com.example.sysfoo.service.TaskAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Same slice-test setup as TodoControllerTest: security filters off,
// @WithMockUser supplies the Authentication, collaborators are mocked.
@WebMvcTest(SprintController.class)
@AutoConfigureMockMvc(addFilters = false)
public class SprintControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SprintRepository sprintRepository;

    @MockBean
    private TodoRepository todoRepository;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private TaskAuditService taskAuditService;

    @MockBean
    private EventBroadcastService eventBroadcastService;

    @Test
    @WithMockUser(username = "alice")
    public void createSprintRequiresName() throws Exception {
        mockMvc.perform(post("/sprints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Sprint name is required"));
    }

    @Test
    @WithMockUser(username = "alice")
    public void createSprintStartsAsPlanned() throws Exception {
        when(sprintRepository.save(any(Sprint.class))).thenAnswer(invocation -> invocation.getArgument(0));
        mockMvc.perform(post("/sprints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sprint 1\",\"goal\":\"Ship it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sprint 1"))
                .andExpect(jsonPath("$.state").value("PLANNED"))
                .andExpect(jsonPath("$.createdByUsername").value("alice"));
    }

    @Test
    @WithMockUser(username = "alice")
    public void startSprintFailsWhenAnotherIsActive() throws Exception {
        Sprint planned = new Sprint();
        planned.setId(2L);
        planned.setName("Sprint 2");
        planned.setCreatedByUsername("alice");
        Sprint active = new Sprint();
        active.setId(1L);
        active.setName("Sprint 1");
        active.setState(Sprint.ACTIVE);
        when(sprintRepository.findById(2L)).thenReturn(Optional.of(planned));
        when(sprintRepository.findFirstByState(Sprint.ACTIVE)).thenReturn(Optional.of(active));

        mockMvc.perform(post("/sprints/2/start"))
                .andExpect(status().isBadRequest());
    }
}
