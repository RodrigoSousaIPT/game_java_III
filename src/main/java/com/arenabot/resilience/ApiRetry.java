package com.arenabot.resilience;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * Lightweight retry decorator used by the orchestrator for arena API calls.
 * Three attempts max with a fixed 1-second gap. We deliberately do NOT
 * use exponential backoff here because the arena server is fast; long waits
 * would only widen the chance of getting stale state.
 *
 * <p>Honours {@link CircuitBreaker#allowRequest()} so a sustained outage
 * does NOT keep us looping — the breaker decides.
 */
public final class ApiRetry {

    private static final int MAX_ATTEMPTS = 3;
    private static final long GAP_MS = 1_000L;

    private ApiRetry() {}

    public static <T> T call(Callable<T> op,
                              CircuitBreaker breaker,
                              Predicate<Throwable> retriable) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (!breaker.allowRequest()) {
                throw new IOException("circuit breaker OPEN — skipping call");
            }
            try {
                T result = op.call();
                breaker.recordSuccess();
                return result;
            } catch (IOException io) {
                last = io;
                breaker.recordFailure();
                if (!retriable.test(io) || attempt == MAX_ATTEMPTS) throw io;
                try { Thread.sleep(GAP_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw io; }
            } catch (Exception ex) {
                // Callable.call() throws Exception broadly — wrap non-IO so the
                // narrow throws-clause on this method stays tight.
                throw new RuntimeException("non-IO failure in retryable op: " + ex.getMessage(), ex);
            }
        }
        throw last == null ? new IOException("retry exhausted") : last;
    }
}
