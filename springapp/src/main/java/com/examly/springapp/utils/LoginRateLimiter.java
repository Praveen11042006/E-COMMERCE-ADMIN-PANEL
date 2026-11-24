package com.examly.springapp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Phase 10A: in-memory brute-force protection for the login endpoint.
 *
 * Policy (configurable via application properties):
 *   - N failed login attempts (default 5) within a sliding window (default 900s)
 *     put the key into a temporary cooldown (default 900s) during which login
 *     attempts are rejected with HTTP 429.
 *   - Email keys use the strict threshold; IP keys use a higher threshold
 *     (default 20) so that a shared NAT/office IP does not lock out every
 *     legitimate user because of one attacker, while still slowing credential
 *     stuffing that sprays many accounts from a single source.
 *   - A successful login clears the failure state for that email key.
 *   - Keys are tracked per normalized email AND per client IP.
 *
 * Phase 16 hardening:
 *   - All state lives in ONE bounded map guarded by the instance monitor, so
 *     the counters can never grow without limit (the previous three-map design
 *     was unbounded). Every entry is one small object; the bound defaults to
 *     10 000 tracked keys and is configurable via
 *     {@code security.login.max-tracked-keys}.
 *   - Stale entries (no activity for window + lockout) are swept periodically
 *     and whenever the map is full. If the map is still full after a sweep, a
 *     previously unknown key is simply not tracked (fail open) instead of
 *     evicting an existing counter — a flood of random emails therefore cannot
 *     push out the per-IP counter that is bounding the same flood.
 *   - Thread safety: every read/write of the map happens inside synchronized
 *     methods. Login is dominated by BCrypt work, so this lock is not a
 *     throughput concern. The clock is injectable for deterministic tests.
 *
 * Deliberately in-memory: the application runs as a single instance, so no
 * Redis or external store is introduced. LIMITATION: the limits are per
 * application instance — behind a load balancer or with several replicas each
 * instance keeps its own counters, so the effective threshold multiplies.
 * A shared store (e.g. Redis) would be required for a horizontally scaled
 * deployment.
 *
 * This class never stores passwords, tokens or any credential material — only
 * counters and timestamps keyed by email/IP strings.
 */
@Component
public class LoginRateLimiter {

    /** Default upper bound on tracked keys. */
    private static final int DEFAULT_MAX_TRACKED_KEYS = 10_000;

    /** Sweep stale entries every N recorded failures (bounded background cost). */
    private static final int SWEEP_INTERVAL = 256;

    private final int maxFailedAttempts;
    private final int ipMaxFailedAttempts;
    private final long windowMillis;
    private final long lockoutMillis;
    private final int maxTrackedKeys;
    private final LongSupplier clock;

    /** key -> counters. Guarded by {@code this}. */
    private final Map<String, Entry> entries = new HashMap<>();

    /** Number of recordFailure calls; guarded by {@code this}. */
    private long operations;

    /** Per-key counters. Mutable but only touched while holding the monitor. */
    private static final class Entry {
        long windowStart;
        int failures;
        long lockedUntil;
        long lastSeen;
    }

    @Autowired
    public LoginRateLimiter(
            @Value("${security.login.max-failed-attempts:5}") int maxFailedAttempts,
            @Value("${security.login.max-failed-attempts-per-ip:20}") int ipMaxFailedAttempts,
            @Value("${security.login.window-seconds:900}") long windowSeconds,
            @Value("${security.login.lockout-seconds:900}") long lockoutSeconds,
            @Value("${security.login.max-tracked-keys:10000}") int maxTrackedKeys) {
        this(maxFailedAttempts, ipMaxFailedAttempts, windowSeconds, lockoutSeconds,
                maxTrackedKeys, System::currentTimeMillis);
    }

    private LoginRateLimiter(int maxFailedAttempts, int ipMaxFailedAttempts, long windowSeconds,
                             long lockoutSeconds, int maxTrackedKeys, LongSupplier clock) {
        this.maxFailedAttempts = Math.max(1, maxFailedAttempts);
        this.ipMaxFailedAttempts = Math.max(1, ipMaxFailedAttempts);
        this.windowMillis = Math.max(1, windowSeconds) * 1000L;
        this.lockoutMillis = Math.max(1, lockoutSeconds) * 1000L;
        this.maxTrackedKeys = Math.max(1, maxTrackedKeys);
        this.clock = clock;
    }

    /** Test seam: a limiter driven by a controllable clock, with a small capacity. */
    static LoginRateLimiter withClock(int maxFailedAttempts, int ipMaxFailedAttempts,
                                      long windowSeconds, long lockoutSeconds,
                                      int maxTrackedKeys, LongSupplier clock) {
        return new LoginRateLimiter(maxFailedAttempts, ipMaxFailedAttempts, windowSeconds,
                lockoutSeconds, maxTrackedKeys, clock);
    }

    /** True when the key is currently in cooldown. */
    public synchronized boolean isBlocked(String key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.lockedUntil == 0) return false;
        long now = clock.getAsLong();
        if (now >= entry.lockedUntil) {
            // Cooldown elapsed — drop the entry so counting restarts cleanly.
            entries.remove(key);
            return false;
        }
        return true;
    }

    /** Seconds remaining in the current cooldown (0 when not blocked). */
    public synchronized long secondsUntilUnblocked(String key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.lockedUntil == 0) return 0;
        long remaining = (entry.lockedUntil - clock.getAsLong() + 999) / 1000;
        return Math.max(0, remaining);
    }

    /**
     * Record one failed authentication for the key. When failures reach the
     * threshold inside the window, a temporary cooldown starts. Never locks
     * permanently: the cooldown always expires.
     */
    public synchronized void recordFailure(String key) {
        long now = clock.getAsLong();
        Entry entry = entries.get(key);

        if (entry == null) {
            if (entries.size() >= maxTrackedKeys) {
                sweep(now);
                if (entries.size() >= maxTrackedKeys) {
                    // Bounded store is still full of live keys: fail open for this
                    // new key rather than evicting an existing counter.
                    return;
                }
            }
            entry = new Entry();
            entry.windowStart = now;
            entries.put(key, entry);
        }

        entry.lastSeen = now;
        if (entry.lockedUntil != 0 && now >= entry.lockedUntil) {
            // Previous cooldown is over: the next failure starts a fresh window.
            entry.lockedUntil = 0;
            entry.windowStart = now;
            entry.failures = 0;
        }
        if (now - entry.windowStart > windowMillis) {
            // Counting window elapsed — restart it from this failure.
            entry.windowStart = now;
            entry.failures = 0;
        }

        entry.failures++;
        // IP keys get the looser threshold so shared NATs/office IPs don't lock
        // out every user when one attacker hammers the endpoint.
        int threshold = key.startsWith("ip:") ? ipMaxFailedAttempts : maxFailedAttempts;
        if (entry.failures >= threshold) {
            entry.lockedUntil = now + lockoutMillis;
        }

        if (++operations % SWEEP_INTERVAL == 0) {
            sweep(now);
        }
    }

    /** A successful login clears the failure state for the key. */
    public synchronized void recordSuccess(String key) {
        entries.remove(key);
    }

    /** Number of tracked keys (package-visible for tests). */
    synchronized int trackedKeys() {
        return entries.size();
    }

    /** Removes entries that have been idle for longer than window + lockout. */
    private void sweep(long now) {
        long ttl = windowMillis + lockoutMillis;
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (now - entry.lastSeen > ttl) {
                iterator.remove();
            }
        }
    }
}
