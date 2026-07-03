package com.arenabot.resilience;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @Test void opensAfterThreshold() {
        CircuitBreaker b = new CircuitBreaker(3, 60_000L, 1);
        assertTrue(b.allowRequest(), "starts CLOSED");
        b.recordFailure();
        b.recordFailure();
        assertTrue(b.allowRequest(), "still CLOSED at threshold-1");
        b.recordFailure();
        assertFalse(b.allowRequest(), "OPEN at threshold");
    }

    @Test void successInClosedStateResetsCounter() {
        CircuitBreaker b = new CircuitBreaker(3, 60_000L, 1);
        b.recordFailure();
        b.recordFailure();
        b.recordSuccess();
        assertEquals(0, b.consecutiveFailures());
        b.recordFailure();
        assertTrue(b.allowRequest(), "after a success, K must be rebuilt from 0");
    }

    @Test void halfOpenPermitsOneProbe() {
        CircuitBreaker b = new CircuitBreaker(1, 50L, 1);
        b.recordFailure();
        assertFalse(b.allowRequest(), "OPEN immediately");
        try { Thread.sleep(80); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertTrue(b.allowRequest(), "first probe in HALF_OPEN is allowed");
        assertNotEquals(CircuitBreaker.State.CLOSED, b.state());
    }
}
