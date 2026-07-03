package com.arenabot.strategy;

import com.arenabot.bot.Action;
import com.arenabot.model.CofreNoMundo;
import com.arenabot.model.GameState;
import com.arenabot.model.MeuEstado;
import com.arenabot.model.ObjetoFixo;
import com.arenabot.model.OutroRobot;
import com.arenabot.model.RecursoNoMundo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveStrategyTest {

    @Test void lowEnergyForcesUsar() throws Exception {
        AdaptiveStrategy s = new AdaptiveStrategy();
        GameState state = emptyStateAt(0, 0, 80);
        com.arenabot.memory.MemorySnapshot snap = snapshotOf(state);
        Action result = s.overrideWithWorld(Action.AVANCAR, state, snap, new OpponentAwareness());
        assertEquals(Action.USAR, result, "below 100 energy must trigger ECO-MARCH USAR");
    }

    @Test void highEnergyKeepsDesiredAction() throws Exception {
        AdaptiveStrategy s = new AdaptiveStrategy();
        GameState state = emptyStateAt(0, 0, 180);
        com.arenabot.memory.MemorySnapshot snap = snapshotOf(state);
        Action result = s.overrideWithWorld(Action.AVANCAR, state, snap, new OpponentAwareness());
        assertEquals(Action.AVANCAR, result);
    }

    @Test void opponentCloserMeansDropChest() throws Exception {
        AdaptiveStrategy s = new AdaptiveStrategy();
        GameState state = emptyStateAt(0, 0, 180);
        GameState opp = opponentStateAt(4, 0, 50);
        OutroRobot rival = opp.outrosRobots().get("rival");
        RecursoNoMundo chest = RecursoNoMundo.fromJson(jsonOf(
                chestJson(5, 0)));
        com.arenabot.memory.MemorySnapshot snap = new com.arenabot.memory.MemorySnapshot(
                List.of(ObjetoFixo.fromJson(jsonOf(wallJson(0, 0)))),
                List.of(chest),
                java.util.Map.of("rival", rival),
                List.of(),
                state.meuEstado(), 0L);
        Action result = s.overrideWithWorld(Action.ABRIR, state, snap, new OpponentAwareness(5));
        assertEquals(Action.AVANCAR, result, "opponent closer to chest should make us drop and explore");
    }

    /* ---------- JSON helpers (explicit concat, no .formatted()) ---------- */

    private static GameState emptyStateAt(int x, int y, int energy) throws Exception {
        return GameState.fromJson(jsonOf(selfJson(x, y, energy)));
    }

    private static GameState opponentStateAt(int x, int y, int energy) throws Exception {
        return GameState.fromJson(jsonOf(worldWithOpponent(x, y, energy)));
    }

    private static String selfJson(int x, int y, int energy) {
        return "{\"o_meu_estado\":{\"x\":" + x + ",\"y\":" + y + ",\"z\":0.4,\"energia\":"
                + energy + ",\"cor\":\"#0ea5e9\"},"
                + "\"objetos_fixos\":[],\"recursos_no_mundo\":[],"
                + "\"outros_robots\":{},\"cofres_no_mundo\":[]}";
    }

    private static String worldWithOpponent(int x, int y, int energy) {
        return "{\"o_meu_estado\":{\"x\":0,\"y\":0,\"energia\":100},"
                + "\"objetos_fixos\":[],\"recursos_no_mundo\":[],"
                + "\"outros_robots\":{\"rival\":{\"x\":" + x + ",\"y\":" + y + ",\"energia\":" + energy + "}},"
                + "\"cofres_no_mundo\":[]}";
    }

    private static String chestJson(int x, int y) {
        return "{\"id\":\"c1\",\"type\":\"bau\",\"x\":" + x + ",\"y\":" + y + ",\"z\":0.4}";
    }

    private static String wallJson(int x, int y) {
        return "{\"id\":\"p\",\"type\":\"c\",\"model\":\"cubo.glb\",\"x\":" + x + ",\"y\":" + y + ",\"z\":0.5}";
    }

    private static com.arenabot.memory.MemorySnapshot snapshotOf(GameState s) {
        return new com.arenabot.memory.MemorySnapshot(
                List.of(), List.of(), java.util.Map.of(), List.of(),
                MeuEstado.fromJson(snapshotMeJson(s)), 0L);
    }

    private static com.fasterxml.jackson.databind.JsonNode snapshotMeJson(GameState s) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode n = new ObjectMapper().createObjectNode();
            n.put("x", s.meuEstado().x());
            n.put("y", s.meuEstado().y());
            n.put("z", s.meuEstado().z());
            n.put("energia", s.meuEstado().energia());
            n.put("cor", s.meuEstado().cor());
            return n;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static com.fasterxml.jackson.databind.JsonNode jsonOf(String s) throws Exception {
        return new ObjectMapper().readTree(s);
    }

    @SuppressWarnings("unused")
    private static final Class<?> KEEP_COFRES = CofreNoMundo.class;
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_OUTRO = OutroRobot.class;
}
