package com.example.sysfoo.controller;

import com.example.sysfoo.service.EventBroadcastService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ENHANCEMENT ("real-time updates"): the frontend opens one
 * `new EventSource('/api/events')` connection on load and listens for
 * "todos-changed" / "posts-changed" — see EventBroadcastService's javadoc
 * for the full design rationale.
 */
@RestController
public class EventsController {

    @Autowired
    private EventBroadcastService eventBroadcastService;

    @GetMapping("/api/events")
    public SseEmitter subscribe() {
        return eventBroadcastService.subscribe();
    }
}
