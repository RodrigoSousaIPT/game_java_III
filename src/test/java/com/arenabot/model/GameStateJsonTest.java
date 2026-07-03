package com.arenabot.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameStateJsonTest {

    @Test void parsesAllFieldsFromARENA_APISample() throws Exception {
        String json = """
          {
            "o_meu_estado":   { "x": 2.0, "y": 6.0, "z": 0.4, "energia": 200, "cor": "#0ea5e9" },
            "recursos_no_mundo": [
              { "id": "bau_1", "type": "bau", "x": 1.0, "y": 3.0, "z": 0.4 }
            ],
            "objetos_fixos": [
              { "id": "parede_24", "type": "cubo", "model": "cubo.glb", "x": 0.0, "y": 2.0, "z": 0.5 },
              { "id": "parede_56", "type": "cubo", "model": "cubo.glb", "x": 4.0, "y": 10.0, "z": 0.5 }
            ],
            "outros_robots":  { "rival1": { "x": 5.0, "y": 5.0, "energia": 100, "cor": "#ff0000" } },
            "cofres_no_mundo": [
              { "id": "cofre_alpha", "x": 3.0, "y": 3.0, "z": 0.6, "cor": "#a855f7" }
            ],
            "game_started": true,
            "game_over": false,
            "vencedor": null
          }
        """;
        GameState g = GameState.fromJson(new ObjectMapper().readTree(json));
        assertTrue(g.isRegistered());
        assertTrue(g.hasGameStarted());
        assertEquals(200, g.meuEstado().energia());
        assertEquals(2, g.objetosFixos().size());
        assertEquals(1, g.recursosNoMundo().size());
        assertEquals(1, g.outrosRobots().size());
        assertEquals("rival1", g.outrosRobots().keySet().iterator().next());
        assertEquals(1, g.cofresNoMundo().size());
    }

    @Test void distinguishesUnregisteredEnvelope() throws Exception {
        String json = """
          { "error": "Robô não registado", "game_over": false }
        """;
        GameState g = GameState.fromJson(new ObjectMapper().readTree(json));
        assertFalse(g.isRegistered());
        assertEquals("Robô não registado", g.errorMessage());
    }

    @Test void ignoresUnknownKeys() throws Exception {
        String json = """
          {
            "o_meu_estado":   { "x": 2.0, "y": 6.0, "energia": 200, "hp": 80, "turno": 3 },
            "objetos_fixos":   [],
            "recursos_no_mundo": [],
            "outros_robots":   {},
            "cofres_no_mundo":  [],
            "tempo_restante": 42
          }
        """;
        GameState g = GameState.fromJson(new ObjectMapper().readTree(json));
        assertTrue(g.isRegistered());
        assertEquals(200, g.meuEstado().energia());
    }
}
