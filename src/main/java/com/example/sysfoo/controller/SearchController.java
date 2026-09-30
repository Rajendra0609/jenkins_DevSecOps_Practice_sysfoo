package com.example.sysfoo.controller;

import com.example.sysfoo.model.Post;
import com.example.sysfoo.model.Todo;
import com.example.sysfoo.service.PostService;
import com.example.sysfoo.service.TodoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * ENHANCEMENT ("search across tasks and posts — there's a task search box,
 * but nothing global"): one endpoint, two result sets. Kept as a thin
 * controller delegating straight to each existing service's own search
 * method — no shared "SearchResult" abstraction, since tasks and posts
 * don't have enough in common to make one meaningful (a task's visibility
 * rule doesn't apply to posts at all, for instance).
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final int MAX_RESULTS_PER_TYPE = 25;

    @Autowired
    private TodoService todoService;

    @Autowired
    private PostService postService;

    @GetMapping
    public ResponseEntity<?> search(@RequestParam(name = "q", defaultValue = "") String q,
                                     Authentication authentication) {
        String query = q.trim();
        if (query.isEmpty()) {
            return ResponseEntity.ok(Map.of("todos", List.of(), "posts", List.of()));
        }
        if (query.length() > 100) {
            query = query.substring(0, 100);
        }

        List<Todo> todos = todoService.search(authentication.getName(), query, PageRequest.of(0, MAX_RESULTS_PER_TYPE));
        List<Post> posts = postService.search(query, PageRequest.of(0, MAX_RESULTS_PER_TYPE));

        return ResponseEntity.ok(Map.of("todos", todos, "posts", posts));
    }
}
