package com.example.sysfoo.repository;

import com.example.sysfoo.model.Folder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FolderRepository extends JpaRepository<Folder, Long> {
    Optional<Folder> findByName(String name);
    boolean existsByName(String name);
}
