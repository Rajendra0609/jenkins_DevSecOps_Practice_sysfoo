package com.example.sysfoo.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ENHANCEMENT ("real-time updates — right now everything is 30-second
 * polling... a natural next step given you already track 'who's online'"):
 * pushes a lightweight "something changed, go refetch" signal to every
 * connected browser tab instead of making everyone wait for their next
 * poll.
 *
 * ── Why Server-Sent Events instead of WebSocket/STOMP ────────────────────
 * All the actual mutations already go through plain REST endpoints
 * (POST/PATCH/DELETE) — nothing here needs the browser to SEND anything
 * over this channel, only receive a notification. That's exactly what SSE
 * is for, and it ships in spring-web (already a dependency of
 * spring-boot-starter-web) via SseEmitter — no new dependency needed,
 * unlike spring-boot-starter-websocket + a client-side STOMP/SockJS
 * library, which this project's sandboxed build couldn't have verified
 * resolves (see project notes on the build environment). A plain
 * `new EventSource(...)` in the browser is all the client needs.
 *
 * ── Why the push payload doesn't carry the actual changed data ───────────
 * Broadcasting is intentionally NOT scoped per-user — every connected
 * client gets the same "todos-changed" ping regardless of who can
 * actually see the task that changed. That's safe specifically BECAUSE the
 * payload is just a signal, not the task/post content: it tells the
 * client "go call GET /todos again", and that request still goes through
 * TodoController's normal creator-or-assignee filtering. Putting real data
 * on this channel would mean re-implementing that visibility check a
 * second time, for a stream that's much easier to get wrong.
 *
 * ── Scope & limitations ───────────────────────────────────────────────────
 * In-memory only, like RateLimiter/SessionRegistry elsewhere in this app —
 * only works correctly for a single instance. A multi-instance deployment
 * would need to publish these events through something shared (e.g. Redis
 * pub/sub) so an emitter connected to instance A hears about a change made
 * on instance B.
 */
@Service
public class EventBroadcastService {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** Default of 0 = never time out on the server side; the browser's EventSource reconnects on its own if the connection drops. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void broadcast(String eventName) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data("ping"));
            } catch (IOException | IllegalStateException e) {
                // Client disconnected without a clean close — drop it, same
                // as onCompletion/onTimeout/onError would eventually do.
                emitters.remove(emitter);
            }
        }
    }
}
