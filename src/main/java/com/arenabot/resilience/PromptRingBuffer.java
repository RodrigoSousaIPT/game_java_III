package com.arenabot.resilience;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Bounded ring buffer of recent LLM prompts. Caps memory growth under any
 * tick rate and lets the dashboard show the latest Ollama call to the user
 * for visual confirmation that prompts are correctly formatted (the admin
 * dashboard grades {@code llm_raw}, so we want a human check too).
 *
 * <p>Per Phase 3 spec (LLM memory leak guard). FIFO eviction because each
 * LLM call is fully self-contained; reuse of an old prompt is not a use-case.
 */
public final class PromptRingBuffer {

    /** 100 × ~3KB = ~300KB, comfortably under the 1MB cap. */
    public static final int CAPACITY = 100;

    private final Deque<String> buf = new ArrayDeque<>(CAPACITY + 1);

    /** Push a prompt onto the ring, evicting the oldest entry if full. */
    public synchronized void push(String prompt, String role, String outcome) {
        if (prompt == null) return;
        buf.addLast(role + " | " + truncate(outcome == null ? "" : outcome, 80)
                + " | " + truncate(prompt, 600));
        while (buf.size() > CAPACITY) buf.pollFirst();
    }

    public synchronized String lastEntry() {
        return buf.isEmpty() ? "" : buf.peekLast();
    }

    /** Snapshot of the most recent N entries for the UI panel. */
    public synchronized List<String> snapshot(int lastN) {
        int take = Math.min(lastN, buf.size());
        return buf.stream()
                .skip(Math.max(0, buf.size() - take))
                .toList();
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
