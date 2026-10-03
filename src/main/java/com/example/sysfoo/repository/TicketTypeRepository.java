package com.example.sysfoo.repository;

import com.example.sysfoo.model.TicketType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TicketTypeRepository extends JpaRepository<TicketType, Long> {
    Optional<TicketType> findByName(String name);
    boolean existsByName(String name);
}
