package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EditorConnectionLimiterTest {
    @Test
    void countsEachEditorAndEvictsTheOldestSafeSession() {
        EditorConnectionLimiter limiter = new EditorConnectionLimiter();
        limiter.setMaximum(2);
        FakeSession oldest = new FakeSession(true, 10L);
        FakeSession busy = new FakeSession(false, 20L);
        AtomicInteger suspended = new AtomicInteger();

        assertTrue(limiter.acquire("workspace-a:editor-1", oldest, suspended::incrementAndGet));
        assertTrue(limiter.acquire("workspace-b:editor-2", busy, suspended::incrementAndGet));
        assertTrue(limiter.acquire("workspace-a:editor-3", new FakeSession(true, 30L), suspended::incrementAndGet));

        assertEquals(2, limiter.activeCount());
        assertEquals(1, suspended.get());
        assertEquals(1, oldest.suspensions.get());
        assertEquals(0, busy.suspensions.get());
    }

    @Test
    void refusesNewSessionWhenOnlyBusyOrDirtySessionsRemainAndLoweringIsNonDestructive() {
        EditorConnectionLimiter limiter = new EditorConnectionLimiter();
        limiter.setMaximum(2);
        assertTrue(limiter.acquire("one", new FakeSession(false, 1L), () -> { }));
        assertTrue(limiter.acquire("two", new FakeSession(false, 2L), () -> { }));

        limiter.setMaximum(1);
        assertEquals(2, limiter.activeCount());
        assertFalse(limiter.acquire("three", new FakeSession(true, 3L), () -> { }));
        assertEquals(2, limiter.activeCount());
    }

    @Test
    void concurrentAcquisitionNeverExceedsTheConfiguredMaximum() throws Exception {
        final EditorConnectionLimiter limiter = new EditorConnectionLimiter();
        limiter.setMaximum(10);
        final int attempts = 40;
        final CountDownLatch ready = new CountDownLatch(attempts);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger admitted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < attempts; i++) {
            final int index = i;
            futures.add(pool.submit(new Runnable() {
                @Override public void run() {
                    ready.countDown();
                    try { start.await(); } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt(); return;
                    }
                    if (limiter.acquire("editor-" + index, new FakeSession(false, index), () -> { })) {
                        admitted.incrementAndGet();
                    }
                }
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(10, admitted.get());
        assertEquals(10, limiter.activeCount());
    }

    private static final class FakeSession implements EditorConnectionLimiter.SessionControl {
        private final boolean safe;
        private final long lastTouched;
        private final AtomicInteger suspensions = new AtomicInteger();
        private FakeSession(boolean safe, long lastTouched) {
            this.safe = safe; this.lastTouched = lastTouched;
        }
        @Override public boolean canSuspend() { return safe; }
        @Override public boolean suspend() { suspensions.incrementAndGet(); return safe; }
        @Override public long lastTouched() { return lastTouched; }
    }
}
