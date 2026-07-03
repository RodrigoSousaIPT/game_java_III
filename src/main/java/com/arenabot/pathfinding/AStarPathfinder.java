package com.arenabot.pathfinding;

import com.arenabot.memory.MapMemory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Plain A* on a 4-direction Manhattan grid. Costs are 1.0 per tile by default;
 * tiles already visited are slightly preferred (lower cost) to avoid jitter on
 * equal heuristic ties, and walls (per {@link MapMemory#isWallAt(int, int)})
 * are blocked with cost {@link Double#POSITIVE_INFINITY}.
 *
 * <p>The pathfinder is stateless and re-entrant — multiple call sites
 * (Tier-2 reflex engine, fallback movement oracle, UI debug overlay) can
 * invoke it concurrently. Memory access is through {@link MapMemory} which
 * already provides lock-correct snapshots.
 */
public final class AStarPathfinder {

    private static final int[] DX = { 1, -1, 0, 0 };
    private static final int[] DY = { 0, 0, 1, -1 };

    private final MapMemory memory;

    public AStarPathfinder(MapMemory memory) {
        this.memory = memory;
    }

    public PathResult findPath(GridPos start, GridPos goal) {
        return findPath(start, goal, 1.0);
    }

    /** Lower {@code energyCostFactor} means the bot is willing to walk further. */
    public PathResult findPath(GridPos start, GridPos goal, double energyCostFactor) {
        if (memory.isWallAt(goal.x(), goal.y())) {
            return PathResult.empty("goal is wall");
        }
        if (start.equals(goal)) {
            return PathResult.of(List.of(start), 0);
        }

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<GridPos, Double> g = new HashMap<>();
        Map<GridPos, GridPos> came = new HashMap<>();
        Set<GridPos> closed = new HashSet<>();

        g.put(start, 0.0);
        open.add(new Node(start, heuristic(start, goal, energyCostFactor), 0.0));

        while (!open.isEmpty()) {
            Node cur = open.poll();
            if (closed.contains(cur.pos)) continue;
            closed.add(cur.pos);
            if (cur.pos.equals(goal)) {
                return reconstruct(came, cur.pos, cur.g);
            }
            for (int i = 0; i < 4; i++) {
                GridPos nb = cur.pos.translate(DX[i], DY[i]);
                if (memory.isWallAt(nb.x(), nb.y())) continue;
                double step = 1.0 + (nb.x() == cur.pos.x() || nb.y() == cur.pos.y() ? 0.0 : 1.0);
                double tentative = cur.g + step * energyCostFactor;
                Double known = g.get(nb);
                if (known != null && tentative >= known) continue;
                g.put(nb, tentative);
                came.put(nb, cur.pos);
                open.add(new Node(nb, tentative + heuristic(nb, goal, energyCostFactor), tentative));
            }
        }
        return PathResult.empty("no path");
    }

    private double heuristic(GridPos a, GridPos b, double factor) {
        return a.manhattan(b) * factor;
    }

    private PathResult reconstruct(Map<GridPos, GridPos> came, GridPos end, double cost) {
        List<GridPos> path = new ArrayList<>();
        GridPos cur = end;
        while (cur != null) {
            path.add(0, cur);
            cur = came.get(cur);
        }
        return PathResult.of(path, cost);
    }

    private static final class Node implements Comparable<Node> {
        final GridPos pos;
        final double f;
        final double g;

        Node(GridPos pos, double f, double g) {
            this.pos = pos;
            this.f = f;
            this.g = g;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.f, o.f);
        }
    }
}
