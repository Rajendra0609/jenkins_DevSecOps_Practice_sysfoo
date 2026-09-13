package com.example.sysfoo.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// BUG FIX (test coverage gap): EventBroadcastService is new this pass.
// SseEmitter's internals (what was actually sent, to whom) aren't
// meaningfully inspectable from a plain unit test — a real round-trip
// belongs in an integration test with an actual HTTP client. What's worth
// locking in at this level: subscribing works, and broadcasting never
// throws regardless of how many emitters are subscribed (including zero),
// since a broadcast failure must never take down the request that
// triggered it (see EventBroadcastService.broadcast()'s per-emitter
// try/catch).
public class EventBroadcastServiceTest {

    @Test
    public void subscribeReturnsAUsableEmitter() {
        EventBroadcastService service = new EventBroadcastService();
        SseEmitter emitter = service.subscribe();
        assertNotNull(emitter);
    }

    @Test
    public void broadcastWithNoSubscribersDoesNotThrow() {
        EventBroadcastService service = new EventBroadcastService();
        assertDoesNotThrow(() -> service.broadcast("todos-changed"));
    }

    @Test
    public void broadcastAfterEmitterCompletesDoesNotThrow() {
        EventBroadcastService service = new EventBroadcastService();
        SseEmitter emitter = service.subscribe();
        emitter.complete(); // simulates the client disconnecting cleanly
        assertDoesNotThrow(() -> service.broadcast("todos-changed"));
    }

    @Test
    public void multipleSubscribersDoNotInterfereWithBroadcast() {
        EventBroadcastService service = new EventBroadcastService();
        service.subscribe();
        service.subscribe();
        service.subscribe();
        assertDoesNotThrow(() -> service.broadcast("posts-changed"));
    }
}
