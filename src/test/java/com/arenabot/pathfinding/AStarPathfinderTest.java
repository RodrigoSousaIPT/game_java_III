package com.arenabot.pathfinding;

import com.arenabot.memory.MapMemory;
import com.arenabot.model.GameState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AStarPathfinderTest {

    @Test void findsPathOnEmptyGrid() throws Exception {
        MapMemory mem = new MapMemory();
        mem.observe(parse("""
                {"o_meu_estado":{"x":0,"y":0,"energia":200},
                 "objetos_fixos":[],"recursos_no_mundo":[],
                 "outros_robots":{},"cofres_no_mundo":[]}"""));
        AStarPathfinder pf = new AStarPathfinder(mem);
        PathResult r = pf.findPath(GridPos.of(0, 0), GridPos.of(3, 4));
        assertTrue(r.found(), "expected a path");
        assertEquals(GridPos.of(3, 4), r.steps().get(r.steps().size() - 1));
        // Step list includes both endpoints. Manhattan(0,0)->(3,4) = 7 hops +
        // 1 origin = 8 cells, but A* here returns 7 because reconstruct()
        // includes both endpoints only once (size == hops + 1).
        assertEquals(8, r.steps().size(),
                "manhattan (0,0)->(3,4) = 7 hops; step list should include origin + 7 = 8 cells");
    }

    @Test void routesAroundWalls() throws Exception {
        MapMemory mem = new MapMemory();
        // Wall barrier at y=4 across x=0..5 — bot at (0,0) heading to (5,5) must detour.
        mem.observe(parse("""
                {"o_meu_estado":{"x":0,"y":0,"energia":200},
                 "objetos_fixos":[%s],
                 "recursos_no_mundo":[],
                 "outros_robots":{},"cofres_no_mundo":[]}"""
                .formatted(wallsBlob())));
        AStarPathfinder pf = new AStarPathfinder(mem);
        PathResult r = pf.findPath(GridPos.of(0, 0), GridPos.of(5, 5));
        assertTrue(r.found());
        // Sanity: ensure any step on row 4 is at x outside the barrier.
        for (GridPos p : r.steps()) {
            if (p.y() == 4) {
                assertTrue(p.x() > 5, "step at y=4 should be past the wall barrier");
            }
        }
    }

    @Test void rejectsGoalOnWall() throws Exception {
        MapMemory mem = new MapMemory();
        mem.observe(parse("""
                {"o_meu_estado":{"x":0,"y":0,"energia":200},
                 "objetos_fixos":[{"id":"parede_w","type":"cubo","model":"cubo.glb","x":3,"y":3,"z":0.5}],
                 "recursos_no_mundo":[],
                 "outros_robots":{},"cofres_no_mundo":[]}"""));
        AStarPathfinder pf = new AStarPathfinder(mem);
        PathResult r = pf.findPath(GridPos.of(0, 0), GridPos.of(3, 3));
        assertFalse(r.found(), "goal on a wall should be rejected");
    }

    private static GameState parse(String json) throws Exception {
        return GameState.fromJson(new ObjectMapper().readTree(json));
    }

    /** Six wall tiles at (0..5, 4) — emitted as a JSON array. */
    private static String wallsBlob() {
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x <= 5; x++) {
            if (x > 0) sb.append(",");
            sb.append("{\"id\":\"parede_w").append(x).append("\",\"type\":\"cubo\",\"model\":\"cubo.glb\",\"x\":").append(x).append(",\"y\":4,\"z\":0.5}");
        }
        return sb.toString();
    }
}
