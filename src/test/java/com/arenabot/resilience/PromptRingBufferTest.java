package com.arenabot.resilience;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptRingBufferTest {

    @Test void capsAtCapacityFifo() {
        PromptRingBuffer b = new PromptRingBuffer();
        for (int i = 0; i < PromptRingBuffer.CAPACITY + 25; i++) {
            b.push("prompt #" + i, "CHEST", "ok");
        }
        assertTrue(b.snapshot(PromptRingBuffer.CAPACITY).size() <= PromptRingBuffer.CAPACITY,
                "buffer must not exceed capacity");
        assertTrue(b.lastEntry().contains("#" + (PromptRingBuffer.CAPACITY + 24)),
                "last entry should be the most recent push");
    }

    @Test void acceptsNullPromptWithoutThrowing() {
        PromptRingBuffer b = new PromptRingBuffer();
        assertDoesNotThrow(() -> b.push(null, "CHEST", "ignored"));
        assertEquals("", b.lastEntry());
    }

    @Test void snapshotLastNTakesTail() {
        PromptRingBuffer b = new PromptRingBuffer();
        for (int i = 0; i < 10; i++) b.push("p" + i, "X", "ok");
        assertEquals(3, b.snapshot(3).size());
        assertTrue(b.snapshot(3).get(0).contains("p7"));
    }
}
