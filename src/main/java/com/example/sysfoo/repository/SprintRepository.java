package com.example.sysfoo.repository;

import com.example.sysfoo.model.Sprint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SprintRepository extends JpaRepository<Sprint, Long> {

    List<Sprint> findAllByOrderByCreatedAtDesc();

    /** One active sprint per project. */
    Optional<Sprint> findFirstByProjectIdAndState(Long projectId, String state);

    long countByProjectId(Long projectId);
}
