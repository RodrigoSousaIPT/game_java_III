package com.arenabot.config;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Per-role Ollama model configuration. Built so that the four LLM roles
 * (chest, embedding, bot_move, commander) can be swapped in the on-disk
 * JSON without touching code.
 */
public final class ModelConfig {

    private String modelName;
    private double temperature;
    private String keepAlive = "24h";
    private String notes = "";

    public ModelConfig() {}

    public ModelConfig(String modelName, double temperature, String keepAlive, String notes) {
        this.modelName = modelName;
        this.temperature = temperature;
        this.keepAlive = keepAlive;
        this.notes = notes;
    }

    public static ModelConfig fromNode(JsonNode n) {
        ModelConfig m = new ModelConfig();
        JsonNode name = n.get("model_name");
        m.modelName = name == null || name.isNull() ? "" : name.asText("");
        JsonNode temp = n.get("temperature");
        m.temperature = temp == null || !temp.isNumber() ? 0.2 : temp.asDouble();
        JsonNode ka = n.get("keep_alive");
        m.keepAlive = ka == null || ka.isNull() ? "24h" : ka.asText("24h");
        JsonNode nt = n.get("notes");
        m.notes = nt == null || nt.isNull() ? "" : nt.asText("");
        return m;
    }

    public String modelName() { return modelName; }
    public double temperature() { return temperature; }
    public String keepAlive() { return keepAlive; }
    public String notes() { return notes; }

    public void setModelName(String modelName) { this.modelName = modelName; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public void setKeepAlive(String keepAlive) { this.keepAlive = keepAlive; }
    public void setNotes(String notes) { this.notes = notes; }
}
