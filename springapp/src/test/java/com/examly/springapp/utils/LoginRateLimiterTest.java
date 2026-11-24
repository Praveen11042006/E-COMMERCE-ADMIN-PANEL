package com.examly.springapp.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 16 — unit tests for the bounded, thread-safe login rate limiter.
 *
 * A controllable clock (instead of Thread.sleep) makes window/lockout expiry
 * deterministic and fast.
 */
class LoginRateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private LoginRateLimiter limiter(int maxAttempts, long lockoutSeconds, int maxKeys) {
        return LoginRateLimiter.withClock(maxAttempts, 20, 900, lockoutSeconds, maxKeys, now::get);
    }

    @Test
    void blocksAfterThresholdAndReportsRetryAfter() {
        LoginRateLimiter limiter = limiter(3, 900, 1000);
        limiter.recordFailure("email:a@example.com");
        limiter.recordFailure("email:a@example.com");
        assertFalse(limiter.isBlocked("email:a@example.com"));

        limiter.recordFailure("email:a@example.com");
        assertTrue(limiter.isBlocked("email:a@example.com"));
        assertEquals(900, limiter.secondsUntilUnblocked("email:a@example.com"));
    }

    @Test
    void lockoutExpiresAndStaleEntryIsDropped() {
        LoginRateLimiter limiter = limiter(3, 900, 1000);
        for (int i = 0; i < 3; i++) limiter.recordFailure("email:expire@example.com");
        assertTrue(limiter.isBlocked("email:expire@example.com"));

        now.addAndGet(900_000L);

        assertFalse(limiter.isBlocked("email:expire@example.com"));
        assertEquals(0, limiter.secondsUntilUnblocked("email:expire@example.com"));
        assertEquals(0, limiter.trackedKeys());
    }

    @Test
    void failureWindowExpiryRestartsTheCounter() {
        LoginRateLimiter limiter = limiter(3, 900, 1000);
        limiter.recordFailure("email:window@example.com");
        limiter.recordFailure("email:window@example.com");

        // Past the 900s window: the two old failures no longer count.
        now.addAndGet(901_000L);
        limiter.recordFailure("email:window@example.com");
        assertFalse(limiter.isBlocked("email:window@example.com"));

        limiter.recordFailure("email:window@example.com");
        limiter.recordFailure("email:window@example.com");
        assertTrue(limiter.isBlocked("email:window@example.com"));
    }

    @Test
    void successClearsFailureState() {
        LoginRateLimiter limiter = limiter(3, 900, 1000);
        limiter.recordFailure("email:reset@example.com");
        limiter.recordFailure("email:reset@example.com");
        limiter.recordSuccess("email:reset@example.com");
        assertFalse(limiter.isBlocked("email:reset@example.com"));
        assertEquals(0, limiter.trackedKeys());
    }

    @Test
    void ipKeysUseTheLooserThreshold() {
        LoginRateLimiter limiter = LoginRateLimiter.withClock(3, 5, 900, 900, 1000, now::get);
        for (int i = 0; i < 4; i++) limiter.recordFailure("ip:203.0.113.7");
        assertFalse(limiter.isBlocked("ip:203.0.113.7"));

        limiter.recordFailure("ip:203.0.113.7");
        assertTrue(limiter.isBlocked("ip:203.0.113.7"));
    }

    @Test
    void trackedKeysNeverExceedTheConfiguredCapacity() {
        LoginRateLimiter limiter = limiter(5, 900, 3);
        for (int i = 0; i < 100; i++) {
            limiter.recordFailure("email:flood-" + i + "@example.com");
        }
        assertEquals(3, limiter.trackedKeys());
    }

    @Test
    void fullCapacityDoesNotEvictLiveCounters() {
        LoginRateLimiter limiter = limiter(3, 900, 1);
        limiter.recordFailure("email:first@example.com");
        limiter.recordFailure("email:first@example.com");
        limiter.recordFailure("email:first@example.com");

        // Capacity is reached; this unknown key is not tracked (fail open) and
        // must not displace the existing counter.
        limiter.recordFailure("email:second@example.com");

        assertEquals(1, limiter.trackedKeys());
        assertTrue(limiter.isBlocked("email:first@example.com"));
    }

    @Test
    void staleEntriesAreSweptToMakeRoomForNewKeys() {
        LoginRateLimiter limiter = limiter(3, 60, 2);
        limiter.recordFailure("email:old-1@example.com");
        limiter.recordFailure("email:old-2@example.com");
        assertEquals(2, limiter.trackedKeys());

        // Window (900s in this helper) + lockout (60s) have elapsed.
        now.addAndGet(1_000_000L);
        limiter.recordFailure("email:new@example.com");

        assertEquals(1, limiter.trackedKeys());
        assertFalse(limiter.isBlocked("email:new@example.com"));
        assertFalse(limiter.isBlocked("email:old-1@example.com"));
    }

    @Test
    void concurrentFailuresAreCountedExactly() throws Exception {
        LoginRateLimiter limiter = limiter(200, 900, 10_000);
        int threads = 8;
        int perThread = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            limiter.recordFailure("email:race@example.com");
                        }
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertTrue(failures.isEmpty(), "concurrent access threw: " + failures);
        // 8 * 40 = 320 failures against a threshold of 200.
        assertTrue(limiter.isBlocked("email:race@example.com"));
        assertEquals(1, limiter.trackedKeys());
    }

    @Test
    void concurrentDistinctKeysStayWithinCapacityWithoutErrors() {
        LoginRateLimiter limiter = limiter(5, 900, 50);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();

        try {
            for (int t = 0; t < 8; t++) {
                final int thread = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 100; i++) {
                            limiter.recordFailure("email:thread-" + thread + "-" + i + "@example.com");
                        }
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }

        assertTrue(failures.isEmpty(), "concurrent access threw: " + failures);
        assertTrue(limiter.trackedKeys() <= 50);
    }
}
