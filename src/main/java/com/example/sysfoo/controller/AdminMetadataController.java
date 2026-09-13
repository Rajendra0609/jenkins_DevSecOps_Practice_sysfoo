package com.example.sysfoo.controller;

import com.example.sysfoo.model.Folder;
import com.example.sysfoo.model.TicketType;
import com.example.sysfoo.model.User;
import com.example.sysfoo.repository.FolderRepository;
import com.example.sysfoo.repository.TicketTypeRepository;
import com.example.sysfoo.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminMetadataController {

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private TicketTypeRepository ticketTypeRepository;

    @Autowired
    private UserRepository userRepository;

    private ResponseEntity<?> requireAdmin(Authentication authentication) {
        User caller = userRepository.findByUsername(authentication.getName()).orElse(null);
        if (caller == null || !caller.isAdmin()) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Not found"));
        }
        return null;
    }

    @GetMapping("/folders")
    public ResponseEntity<?> listFolders(Authentication authentication) {
        ResponseEntity<?> denied = requireAdmin(authentication);
        if (denied != null) return denied;
        return ResponseEntity.ok(folderRepository.findAll().stream()
                .map(f -> Map.of("name", f.getName()))
                .collect(Collectors.toList()));
    }

    @PostMapping("/folders")
    public ResponseEntity<?> createFolder(@RequestBody Map<String, String> body, Authentication authentication) {
        ResponseEntity<?> denied = requireAdmin(authentication);
        if (denied != null) return denied;
        String name = body.getOrDefault("name", "").trim();
        if (name.isBlank() || name.length() > 50) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Folder name must be 1-50 characters"));
        }
        if (folderRepository.existsByName(name)) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Folder already exists"));
        }
        Folder folder = new Folder(name);
        folderRepository.save(folder);
        return ResponseEntity.ok(Map.of("status", "ok", "name", folder.getName()));
    }

    @GetMapping("/ticket-types")
    public ResponseEntity<?> listTicketTypes(Authentication authentication) {
        ResponseEntity<?> denied = requireAdmin(authentication);
        if (denied != null) return denied;
        return ResponseEntity.ok(ticketTypeRepository.findAll().stream()
                .map(t -> Map.of("name", t.getName()))
                .collect(Collectors.toList()));
    }

    @PostMapping("/ticket-types")
    public ResponseEntity<?> createTicketType(@RequestBody Map<String, String> body, Authentication authentication) {
        ResponseEntity<?> denied = requireAdmin(authentication);
        if (denied != null) return denied;
        String name = body.getOrDefault("name", "").trim();
        if (name.isBlank() || name.length() > 40) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Ticket type must be 1-40 characters"));
        }
        name = name.toUpperCase();
        if (ticketTypeRepository.existsByName(name)) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Ticket type already exists"));
        }
        TicketType ticketType = new TicketType(name);
        ticketTypeRepository.save(ticketType);
        return ResponseEntity.ok(Map.of("status", "ok", "name", ticketType.getName()));
    }
}
