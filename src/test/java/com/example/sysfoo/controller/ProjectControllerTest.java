package com.example.sysfoo.controller;

import com.example.sysfoo.model.Project;
import com.example.sysfoo.repository.ProjectRepository;
import com.example.sysfoo.repository.SprintRepository;
import com.example.sysfoo.repository.TodoRepository;
import com.example.sysfoo.repository.UserRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProjectController.class)
@AutoConfigureMockMvc(addFilters = false)
public class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProjectRepository projectRepository;

    @MockBean
    private TodoRepository todoRepository;

    @MockBean
    private SprintRepository sprintRepository;

    @MockBean
    private UserRepository userRepository;

    @Test
    @WithMockUser(username = "alice")
    public void createProjectUppercasesKey() throws Exception {
        when(projectRepository.existsByProjectKey("SYS")).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sysfoo\",\"projectKey\":\"sys\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectKey").value("SYS"))
                .andExpect(jsonPath("$.createdByUsername").value("alice"));
    }

    @Test
    @WithMockUser(username = "alice")
    public void createProjectRejectsBadKey() throws Exception {
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sysfoo\",\"projectKey\":\"1-x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "alice")
    public void createProjectRejectsDuplicateKey() throws Exception {
        when(projectRepository.existsByProjectKey("SYS")).thenReturn(true);
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sysfoo\",\"projectKey\":\"SYS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "alice")
    public void deleteProjectBlockedWhenItHasIssues() throws Exception {
        Project p = new Project();
        p.setId(1L);
        p.setCreatedByUsername("alice");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(todoRepository.countByProjectIdAndDeletedFalse(1L)).thenReturn(3L);

        mockMvc.perform(delete("/projects/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A project that has issues or sprints can't be deleted"));
    }
}
