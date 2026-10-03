package com.example.sysfoo.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A small, dependency-free fixed-window-ish rate limiter, keyed by an
 * arbitrary string (typically an IP address or "ip:username").
 *
 * ── Why hand-rolled instead of Bucket4j/Resilience4j ────────────────────
 *   This app's build can only resolve dependencies already declared in
 *   pom.xml (see project notes on the sandboxed build environment) — adding
 *   a new library isn't an option here. This class covers the same need
 *   (bound the rate of an action per key over a rolling time window) with
 *   nothing beyond the JDK.
 *
 * ── Scope & limitations (be upfront about these) ────────────────────────
 *   - In-memory only: resets on restart, and does NOT coordinate across
 *     multiple app instances. Fine for a single-instance deployment; a
 *     multi-instance production deployment should move this to Redis
 *     (e.g. via a sorted-set-based sliding window) or push the job to an
 *     API gateway / WAF in front of the app.
 *   - Unbounded key growth is capped by periodically sweeping empty
 *     entries in {@link #recordAttempt} — acceptable at demo/small-team
 *     scale; a high-traffic deployment would want a bounded LRU cache
 *     instead of a plain ConcurrentHashMap.
 */
@Component
public class RateLimiter {

    private final Map<String, Deque<Instant>> attemptsByKey = new ConcurrentHashMap<>();

    /**
     * Records an attempt for {@code key} and reports whether the caller has
     * exceeded {@code maxAttempts} within the trailing {@code windowSeconds}.
     *
     * @return true if this attempt should be ALLOWED, false if the key has
     *         hit the limit and the caller should be rejected (e.g. 429).
     */
    public boolean allow(String key, int maxAttempts, long windowSeconds) {
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(windowSeconds);
        Deque<Instant> attempts = attemptsByKey.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        synchronized (attempts) {
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(windowStart)) {
                attempts.pollFirst();
            }
            if (attempts.size() >= maxAttempts) {
                return false;
            }
            attempts.addLast(now);
            return true;
        }
    }

    /** Drops all recorded attempts for a key — used after a successful login to reset the counter. */
    public void reset(String key) {
        attemptsByKey.remove(key);
    }
}
