package com.arenabot.strategy;

import com.arenabot.pathfinding.GridPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OpponentAwarenessTest {

    @Test void detectsOpponentStrictlyCloserThanMe() {
        OpponentAwareness o = new OpponentAwareness();
        GridPos me = new GridPos(0, 0);
        GridPos target = new GridPos(5, 0); // 5 tiles away
        GridPos closerOp = new GridPos(3, 0); // 3 tiles away
        Optional<GridPos> r = o.anyOpponentCloserThanMe(me, target, List.of(closerOp));
        assertTrue(r.isPresent());
        assertEquals(closerOp, r.get());
    }

    @Test void notCrowdedWhenOpponentFartherThanMe() {
        OpponentAwareness o = new OpponentAwareness(3);
        GridPos me = new GridPos(0, 0);
        GridPos chest = new GridPos(2, 0);
        GridPos farOp = new GridPos(10, 10); // far from chest
        assertFalse(o.isChestCrowded(chest, me, List.of(farOp)));
    }

    @Test void crowdedWhenOpponentAdjacent() {
        OpponentAwareness o = new OpponentAwareness(3);
        GridPos me = new GridPos(0, 0);
        GridPos chest = new GridPos(2, 0);
        GridPos nearOp = new GridPos(2, 1); // adjacent to chest
        assertTrue(o.isChestCrowded(chest, me, List.of(nearOp)));
    }
}
