package com.arenabot.resilience;

import com.arenabot.pathfinding.GridPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StuckTileDetectorTest {

    @Test void flagsOscillationBetweenTwoTiles() {
        StuckTileDetector d = new StuckTileDetector();
        for (int i = 0; i < 20; i++) {
            assertFalse(d.observe(new GridPos(i % 2, 0)), "should not flag yet — threshold not met");
        }
        assertTrue(d.observe(new GridPos(0, 0)), "20-of-24 between 2 tiles ⇒ stuck");
        assertTrue(d.observe(new GridPos(1, 0)), "second consecutive flag still holds");
    }

    @Test void doesNotFlagWhenMovingToManyTiles() {
        StuckTileDetector d = new StuckTileDetector();
        for (int x = 0; x < 30; x++) {
            assertFalse(d.observe(new GridPos(x, 0)),
                    "should not flag — many unique tiles");
        }
    }

    @Test void resetClearsHistory() {
        StuckTileDetector d = new StuckTileDetector();
        for (int i = 0; i < 22; i++) d.observe(new GridPos(0, 0));
        assertEquals(22, d.trailSize());
        d.reset();
        assertEquals(0, d.trailSize());
    }
}
