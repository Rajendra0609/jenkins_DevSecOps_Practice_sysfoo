package com.example.sysfoo.repository;

import com.example.sysfoo.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findAllByOrderByNameAsc();

    Optional<Project> findByProjectKey(String projectKey);

    boolean existsByProjectKey(String projectKey);
}
