package com.arenabot.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Tiny pub-sub bus that lets the tick thread and the commander thread
 * publish live telemetry to the Swing EDT without blocking. The EDT pulls
 * via {@link #drainTo(Consumer)} which atomically swaps the queue.
 *
 * <p>Old messages (older than ~5 s) are kept out by the caller — UI never
 * needs historical accuracy, only the latest picture.
 */
public final class TelemetryBus {

    public record Sample(String label, int tokensIn, int tokensOut, long elapsedMs, String text) {}

    private final AtomicReference<Sample> latest = new AtomicReference<>(new Sample("idle", 0, 0, 0L, ""));
    private final AtomicInteger totalCalls = new AtomicInteger();
    private final Deque<String> recentLines = new ArrayDeque<>();
    private static final int MAX_LINES = 80;

    public void publish(Sample s) {
        latest.set(s);
        totalCalls.incrementAndGet();
        synchronized (recentLines) {
            recentLines.addLast(s.label() + " · " + s.text());
            while (recentLines.size() > MAX_LINES) recentLines.pollFirst();
        }
    }

    public Sample latest() { return latest.get(); }

    /** Total LLM/embedding samples published since boot ("Chamadas LLM"). */
    public int totalCalls() { return totalCalls.get(); }

    public String[] recentLinesSnapshot() {
        synchronized (recentLines) {
            return recentLines.toArray(new String[0]);
        }
    }

    public void drainIntoLabel(Consumer<String> label) {
        synchronized (recentLines) {
            String[] snap = recentLines.toArray(new String[0]);
            for (String l : snap) label.accept(l);
        }
    }
}
