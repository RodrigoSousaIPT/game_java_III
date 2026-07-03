package com.arenabot.pathfinding;

import java.util.List;
import java.util.Optional;

/**
 * Outcome of an A* run. {@link #found()} is true iff {@link #steps()} is
 * non-empty AND every intermediate tile is walkable per the supplied cost map.
 */
public record PathResult(boolean found, List<GridPos> steps, double cost, String reason) {

    public static PathResult empty(String reason) {
        return new PathResult(false, List.of(), Double.POSITIVE_INFINITY, reason);
    }

    public static PathResult of(List<GridPos> steps, double cost) {
        return new PathResult(!steps.isEmpty(), List.copyOf(steps), cost, "");
    }

    public Optional<GridPos> nextStep() {
        return steps.isEmpty() ? Optional.empty() : Optional.of(steps.get(0));
    }
}
