package com.arenabot.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root perception response, parsed defensively so unknown keys never crash
 * the bot (ARENA_API.md §2 forward-compat note).
 *
 * <p>The success tell is {@code root.has("o_meu_estado")} — not HTTP 200.
 * When the bot is unregistered the server returns
 * {@code {"error":"Robô não registado","game_over":false}} with status 200.
 */
public final class GameState {

    private MeuEstado meuEstado = new MeuEstado();
    private List<RecursoNoMundo> recursosNoMundo = new ArrayList<>();
    private List<ObjetoFixo> objetosFixos = new ArrayList<>();
    private Map<String, OutroRobot> outrosRobots = new LinkedHashMap<>();
    private List<CofreNoMundo> cofresNoMundo = new ArrayList<>();
    private boolean gameStarted;
    private boolean gameOver;
    private String vencedor;
    private String errorMessage;

    public static GameState fromJson(JsonNode root) {
        GameState g = new GameState();
        if (root == null || !root.isObject()) return g;

        if (root.has("o_meu_estado")) {
            g.meuEstado = MeuEstado.fromJson(root.get("o_meu_estado"));
        } else if (root.has("error")) {
            JsonNode e = root.get("error");
            g.errorMessage = e == null || !e.isTextual() ? "" : e.asText();
        }

        g.recursosNoMundo = parseList(root.get("recursos_no_mundo"), RecursoNoMundo::fromJson);
        g.objetosFixos = parseList(root.get("objetos_fixos"), ObjetoFixo::fromJson);
        g.cofresNoMundo = parseList(root.get("cofres_no_mundo"), CofreNoMundo::fromJson);

        JsonNode others = root.get("outros_robots");
        if (others != null && others.isObject()) {
            Map<String, OutroRobot> m = new LinkedHashMap<>();
            others.fields().forEachRemaining(e -> m.put(e.getKey(), OutroRobot.fromJson(e.getKey(), e.getValue())));
            g.outrosRobots = m;
        }

        JsonNode gs = root.get("game_started");
        g.gameStarted = gs != null && gs.asBoolean(false);
        JsonNode go = root.get("game_over");
        g.gameOver = go != null && go.asBoolean(false);
        JsonNode v = root.get("vencedor");
        g.vencedor = v == null || v.isNull() ? null : v.asText();

        return g;
    }

    private static <T> List<T> parseList(JsonNode arr, java.util.function.Function<JsonNode, T> mapper) {
        if (arr == null || !arr.isArray()) return new ArrayList<>();
        List<T> out = new ArrayList<>(arr.size());
        for (JsonNode el : arr) out.add(mapper.apply(el));
        return out;
    }

    public boolean isRegistered() { return errorMessage == null || errorMessage.isEmpty(); }
    public boolean hasGameStarted() { return gameStarted; }
    public boolean hasGameOver() { return gameOver; }
    public String vencedor() { return vencedor; }
    public String errorMessage() { return errorMessage; }
    public MeuEstado meuEstado() { return meuEstado; }
    public List<RecursoNoMundo> recursosNoMundo() { return Collections.unmodifiableList(recursosNoMundo); }
    public List<ObjetoFixo> objetosFixos() { return Collections.unmodifiableList(objetosFixos); }
    public Map<String, OutroRobot> outrosRobots() { return Collections.unmodifiableMap(outrosRobots); }
    public List<CofreNoMundo> cofresNoMundo() { return Collections.unmodifiableList(cofresNoMundo); }

    /** Dev helper used by the UI layer; exposes mutable list for in-place updates. */
    void replaceWorld(GameState other) {
        this.meuEstado = other.meuEstado;
        this.recursosNoMundo = new ArrayList<>(other.recursosNoMundo);
        this.objetosFixos = new ArrayList<>(other.objetosFixos);
        this.outrosRobots = new LinkedHashMap<>(other.outrosRobots);
        this.cofresNoMundo = new ArrayList<>(other.cofresNoMundo);
        this.gameStarted = other.gameStarted;
        this.gameOver = other.gameOver;
        this.vencedor = other.vencedor;
        this.errorMessage = other.errorMessage;
    }

    /** For test purposes only — produces a stub state without a JSON payload. */
    public static GameState empty() {
        GameState g = new GameState();
        g.errorMessage = "stub";
        return g;
    }

    /** Suppress unused import warning while keeping HashMap available for future use. */
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_HASHMAP = HashMap.class;
}
