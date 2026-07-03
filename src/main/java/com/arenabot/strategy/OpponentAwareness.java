package com.arenabot.strategy;

import com.arenabot.pathfinding.GridPos;
import com.arenabot.model.GameState;
import com.arenabot.memory.MemorySnapshot;

import java.util.Collection;
import java.util.Optional;

/**
 * Reads {@code outros_robots} from the latest perception and answers two
 * tactical questions:
 * <ol>
 *   <li>am I within {@code R} tiles of an opponent who is close to MY target?</li>
 *   <li>which opponent is currently closest to ME?</li>
 * </ol>
 *
 * <p>If an opponent is closer to a chest than me, the chest is effectively
 * lost — energy spent getting there burns without the prize. We abandon and
 * pick something else.
 */
public final class OpponentAwareness {

    public static final int DEFAULT_CROWD_RADIUS = 3;

    private final int crowdRadius;

    public OpponentAwareness() { this(DEFAULT_CROWD_RADIUS); }

    public OpponentAwareness(int crowdRadius) {
        this.crowdRadius = Math.max(1, crowdRadius);
    }

    /** Manhattan distance ignoring z. Good enough for the 2-D arena. */
    public static int distance(GridPos a, GridPos b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y());
    }

    /** @return the opponent's tile if any is strictly closer than me to {@code target}. */
    public Optional<GridPos> anyOpponentCloserThanMe(GridPos me,
                                                      GridPos target,
                                                      Collection<GridPos> opponents) {
        int myDist = distance(me, target);
        return opponents.stream()
                .filter(op -> distance(op, target) < myDist)
                .findFirst();
    }

    public boolean isChestCrowded(GridPos chest, GridPos me, Collection<GridPos> opponents) {
        return opponents.stream()
                .anyMatch(op -> distance(op, chest) <= crowdRadius)
                && opponents.stream()
                .anyMatch(op -> distance(op, chest) <= distance(me, chest));
    }

    public Optional<GridPos> nearestOpponentTo(GridPos me, Collection<GridPos> opponents) {
        return opponents.stream().min((a, b) -> Integer.compare(distance(a, me), distance(b, me)));
    }
}
