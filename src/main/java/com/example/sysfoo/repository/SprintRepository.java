package com.example.sysfoo.repository;

import com.example.sysfoo.model.Sprint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SprintRepository extends JpaRepository<Sprint, Long> {

    List<Sprint> findAllByOrderByCreatedAtDesc();

    Optional<Sprint> findFirstByState(String state);
}
