package com.arenabot.memory;

import com.arenabot.model.GameState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MapMemoryWallTest {

    @Test void decorObjectsDoNotBlockPathfinding() throws Exception {
        MapMemory mem = new MapMemory();
        mem.observe(GameState.fromJson(new ObjectMapper().readTree("""
                {"o_meu_estado":{"x":0,"y":0,"energia":200},
                 "objetos_fixos":[
                   {"id":"parede_1","type":"cubo","model":"cubo.glb","x":2,"y":2,"z":0.5},
                   {"id":"estatua_1","type":"decor","model":"estatua.glb","x":3,"y":3,"z":0.5}
                 ],
                 "recursos_no_mundo":[],"outros_robots":{},"cofres_no_mundo":[]}""")));
        assertTrue(mem.isWallAt(2, 2), "parede_* with cubo.glb is a wall");
        assertFalse(mem.isWallAt(3, 3), "decor object must not block pathfinding");
        assertFalse(mem.isWallAt(5, 5), "empty tile is not a wall");
    }
}
