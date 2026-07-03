package com.arenabot.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 3-state breaker for outbound arena API calls. Per Phase 3 spec, we don't
 * want to hammer the FastAPI server every tick when a sustained outage is
 * happening (TLS, network, game keeps returning {@code bloqueado}).
 *
 * <p>When the breaker is OPEN, {@link #allowRequest()} returns false. After
 * {@link #OPEN_DURATION_MS} the breaker moves to HALF_OPEN and lets through
 * a single probe; if the probe succeeds the breaker closes, otherwise it
 * re-opens for another full interval.
 *
 * <p>Default values from the design review: K=5 consecutive failures, 3s
 * cooldown, 1 HALF_OPEN probe. All tunable via the constructor.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public static final int DEFAULT_FAILURE_THRESHOLD = 5;
    public static final long DEFAULT_OPEN_DURATION_MS = 3_000L;
    public static final int DEFAULT_HALF_OPEN_PROBES = 1;

    private final int failureThreshold;
    private final long openDurationMs;
    private final int halfOpenProbes;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAtMs = new AtomicLong(0L);
    private final AtomicInteger halfOpenProbesLeft = new AtomicInteger(0);

    public CircuitBreaker() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_OPEN_DURATION_MS, DEFAULT_HALF_OPEN_PROBES);
    }

    public CircuitBreaker(int failureThreshold, long openDurationMs, int halfOpenProbes) {
        this.failureThreshold = Math.max(1, failureThreshold);
        // No minimum coercion on openDurationMs — tests use small values
        // (e.g. 30ms) and a floor here would silently bump them up.
        this.openDurationMs = openDurationMs;
        this.halfOpenProbes = Math.max(1, halfOpenProbes);
    }

    /** Returns true if the next request may proceed under the current state. */
    public boolean allowRequest() {
        State s = state.get();
        if (s == State.CLOSED) return true;
        if (s == State.OPEN) {
            long elapsed = System.currentTimeMillis() - openedAtMs.get();
            if (elapsed < openDurationMs) return false;
            state.set(State.HALF_OPEN);
            halfOpenProbesLeft.set(halfOpenProbes);
            // Return true iff probes left AFTER this one is consumed > 0. First
            // call decrements 1 → 0, the second decrements 0 → -1 (blocked).
            return halfOpenProbesLeft.decrementAndGet() >= 0;
        }
        // HALF_OPEN — same accounting on follow-ups.
        return halfOpenProbesLeft.decrementAndGet() >= 0;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        state.set(State.CLOSED);
    }

    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (state.get() == State.HALF_OPEN
                || failures >= failureThreshold) {
            state.set(State.OPEN);
            openedAtMs.set(System.currentTimeMillis());
            halfOpenProbesLeft.set(0);
        }
    }

    public State state() { return state.get(); }

    public int consecutiveFailures() { return consecutiveFailures.get(); }
}
