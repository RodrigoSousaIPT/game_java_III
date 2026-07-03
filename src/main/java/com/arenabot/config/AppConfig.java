package com.arenabot.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Root configuration loaded from a JSON file on disk. Tries {@code config/config.json}
 * first, then {@code ./config.json}, then falls back to the bundled template under
 * {@code src/main/resources/config.json.template} so the bot always boots with sane
 * defaults even if no config file is present.
 *
 * <p>The four LLM roles ({@code chest}, {@code embedding}, {@code bot_move},
 * {@code commander}) are stored as swappable {@link ModelConfig} entries — this
 * is the constraint from {@code context/ais_used.txt}: "the llms used need to be
 * easy changeable in config file near the ps1 file".
 */
public final class AppConfig {

    private String roomCode = "7A1071";
    private String robotIdPrefix = "javabot";
    private String arenaBaseUrl = "https://arena.pmonteiro.ovh";
    private String ollamaBaseUrl = "http://127.0.0.1:11434";
    private long tickMs = 700L;
    private long commanderPeriodMs = 7000L;
    private int energyCriticalThreshold = 80;
    private int energyLowThreshold = 120;
    private Map<String, ModelConfig> models = new HashMap<>();
    private UiConfig ui = new UiConfig();

    public static AppConfig load(Path userProvided) throws IOException {
        ObjectMapper m = new ObjectMapper();
        JsonNode root;

        Path primary = Path.of("config", "config.json");
        Path secondary = Path.of("config.json");

        if (userProvided != null && Files.isRegularFile(userProvided)) {
            root = m.readTree(Files.newInputStream(userProvided));
        } else if (Files.isRegularFile(primary)) {
            root = m.readTree(Files.newInputStream(primary));
        } else if (Files.isRegularFile(secondary)) {
            root = m.readTree(Files.newInputStream(secondary));
        } else {
            try (InputStream in = AppConfig.class.getResourceAsStream("/config.json.template")) {
                if (in == null) {
                    throw new IOException("No config file found and bundled template missing.");
                }
                root = m.readTree(in);
            }
        }
        return fromNode(root);
    }

    static AppConfig fromNode(JsonNode root) {
        AppConfig c = new AppConfig();
        c.roomCode = textOr(root, "room_code", c.roomCode);
        c.robotIdPrefix = textOr(root, "robot_id_prefix", c.robotIdPrefix);
        c.arenaBaseUrl = textOr(root, "arena_base_url", c.arenaBaseUrl);
        c.ollamaBaseUrl = textOr(root, "ollama_base_url", c.ollamaBaseUrl);
        c.tickMs = longOr(root, "tick_ms", c.tickMs);
        c.commanderPeriodMs = longOr(root, "commander_period_ms", c.commanderPeriodMs);
        c.energyCriticalThreshold = intOr(root, "energy_critical_threshold", c.energyCriticalThreshold);
        c.energyLowThreshold = intOr(root, "energy_low_threshold", c.energyLowThreshold);

        JsonNode modelsNode = root.get("models");
        if (modelsNode != null && modelsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = modelsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                c.models.put(e.getKey(), ModelConfig.fromNode(e.getValue()));
            }
        }

        JsonNode uiNode = root.get("ui");
        if (uiNode != null && uiNode.isObject()) {
            c.ui = UiConfig.fromNode(uiNode);
        }
        return c;
    }

    private static String textOr(JsonNode node, String field, String dflt) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? dflt : v.asText(dflt);
    }

    private static long longOr(JsonNode node, String field, long dflt) {
        JsonNode v = node.get(field);
        return v == null || !v.canConvertToLong() ? dflt : v.asLong(dflt);
    }

    private static int intOr(JsonNode node, String field, int dflt) {
        JsonNode v = node.get(field);
        return v == null || !v.canConvertToInt() ? dflt : v.asInt(dflt);
    }

    public String roomCode() { return roomCode; }
    public String robotIdPrefix() { return robotIdPrefix; }
    public String arenaBaseUrl() { return arenaBaseUrl; }
    public String ollamaBaseUrl() { return ollamaBaseUrl; }
    public long tickMs() { return tickMs; }
    public long commanderPeriodMs() { return commanderPeriodMs; }
    public int energyCriticalThreshold() { return energyCriticalThreshold; }
    public int energyLowThreshold() { return energyLowThreshold; }
    public Map<String, ModelConfig> models() { return models; }
    public UiConfig ui() { return ui; }
}
