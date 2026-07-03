package com.arenabot.pathfinding;

import java.util.Objects;

/** Integer-tile coordinate for the A* grid. World coordinates are metrical
 * (doubles) per ARENA_API.md §2; this snaps to 1m increments. */
public record GridPos(int x, int y) {

    public GridPos {
        // Normalize: a grid coordinate is finite, non-negative based on the visible world.
    }

    public static GridPos of(double x, double y) {
        return new GridPos((int) Math.round(x), (int) Math.round(y));
    }

    public GridPos translate(int dx, int dy) {
        return new GridPos(x + dx, y + dy);
    }

    public int manhattan(GridPos other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GridPos p && p.x == x && p.y == y;
    }

    @Override
    public int hashCode() { return Objects.hash(x, y); }

    @Override
    public String toString() { return "(" + x + "," + y + ")"; }
}
