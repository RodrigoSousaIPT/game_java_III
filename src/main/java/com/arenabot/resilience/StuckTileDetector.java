package com.arenabot.resilience;

import com.arenabot.pathfinding.GridPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Oscillation detector. A real stuck bot doesn't sit on one tile forever —
 * it *bounces* between 2-3 tiles because every other refinement step in the
 * tick loop nudges it differently. We treat "≤{@value #UNIQUE_LIMIT}
 * distinct tiles for ≥{@value #THRESHOLD} of the last
 * {@value #WINDOW} ticks" as a stuck condition.
 *
 * <p>Per Phase 3 spec from {@code context/PROMPT.txt} §PHASE 3 (infinite-loop
 * detection). The recovery action is to break the cycle with a deliberate
 * randomised step so the surrounding A* can pick a different route.
 */
public final class StuckTileDetector {

    /** Sliding window in ticks (~17s at the default 700ms tick). */
    public static final int WINDOW = 24;
    /** Distinct tiles under which we consider ourselves oscillating. */
    public static final int UNIQUE_LIMIT = 2;
    /** Minimum fraction of the window that must be on those ≤2 tiles.
     *  Strictly greater than ({@code THRESHOLD}) entries — so the 20th of
     *  any oscillation pair still has a chance to escape. */
    public static final int THRESHOLD = 20;
    private static final int STUCK_AT_LEAST = THRESHOLD + 1;

    private final Deque<GridPos> trail = new ArrayDeque<>(WINDOW + 1);

    /** Push the bot's current tile onto the trail and report whether we are stuck. */
    public synchronized boolean observe(GridPos pos) {
        if (pos == null) return false;
        trail.addLast(pos);
        while (trail.size() > WINDOW) trail.pollFirst();
        if (trail.size() < THRESHOLD) return false;

        Set<GridPos> unique = new HashSet<>(trail);
        if (unique.size() > UNIQUE_LIMIT) return false;

        // Whole window sits on ≤UNIQUE_LIMIT tiles; stuck once it has run
        // long enough (strictly more than THRESHOLD observations).
        return trail.size() > THRESHOLD;
    }

    /** Reset the trail (e.g. after a successful recovery action). */
    public synchronized void reset() {
        trail.clear();
    }

    public synchronized int trailSize() {
        return trail.size();
    }
}
