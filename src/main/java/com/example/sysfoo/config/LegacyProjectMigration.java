package com.example.sysfoo.config;

import com.example.sysfoo.model.Project;
import com.example.sysfoo.model.Sprint;
import com.example.sysfoo.model.Todo;
import com.example.sysfoo.repository.ProjectRepository;
import com.example.sysfoo.repository.SprintRepository;
import com.example.sysfoo.repository.TodoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-time, idempotent data migration. Issues and sprints created before
 * projects existed have no project; they are moved into a default project
 * with key TASK, keeping their old keys (TASK-<id>) exactly as they were.
 */
@Component
public class LegacyProjectMigration implements ApplicationRunner {

    static final String DEFAULT_KEY = "TASK";

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private SprintRepository sprintRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Override
    public void run(ApplicationArguments args) {
        List<Todo> orphanTodos = todoRepository.findAll().stream().filter(t -> t.getProjectId() == null).toList();
        List<Sprint> orphanSprints = sprintRepository.findAll().stream().filter(s -> s.getProjectId() == null).toList();
        if (orphanTodos.isEmpty() && orphanSprints.isEmpty()) {
            return;
        }
        Project project = projectRepository.findByProjectKey(DEFAULT_KEY).orElseGet(() -> {
            Project p = new Project();
            p.setProjectKey(DEFAULT_KEY);
            p.setName("General Tasks");
            p.setDescription("Issues created before projects were introduced.");
            p.setCreatedByUsername("system");
            return projectRepository.save(p);
        });
        long maxId = 0;
        for (Todo todo : orphanTodos) {
            todo.setProjectId(project.getId());
            todo.setProjectKey(project.getProjectKey());
            todo.setIssueNumber(todo.getId().intValue());
            todoRepository.save(todo);
            maxId = Math.max(maxId, todo.getId());
        }
        for (Sprint sprint : orphanSprints) {
            sprint.setProjectId(project.getId());
            sprintRepository.save(sprint);
        }
        project.setNextIssueNumber(Math.max(project.getNextIssueNumber(), (int) maxId + 1));
        projectRepository.save(project);
    }
}
