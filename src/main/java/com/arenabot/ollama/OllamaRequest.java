package com.arenabot.ollama;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Ollama {@code POST /api/generate} payload. Kept as a builder so the
 * commander, chess specialist and movement oracle can construct distinct
 * prompts without re-typing Jackson calls.
 */
public final class OllamaRequest {

    public String model;
    public String prompt;
    public boolean stream = false;
    public double temperature = 0.4;
    /** Max tokens to generate; <= 0 means "leave to the model default". */
    public int numPredict = -1;
    public String keepAlive = "-1m";
    public final ArrayNode stop = new ObjectMapper().createArrayNode();

    public static OllamaRequest of(String model, String prompt, double temperature) {
        OllamaRequest r = new OllamaRequest();
        r.model = model;
        r.prompt = prompt;
        r.temperature = temperature;
        return r;
    }

    public OllamaRequest stopOn(String... tokens) {
        for (String t : tokens) stop.add(t);
        return this;
    }

    public String toJson() {
        ObjectMapper m = new ObjectMapper();
        ObjectNode n = m.createObjectNode();
        n.put("model", model);
        n.put("prompt", prompt);
        n.put("stream", stream);
        ObjectNode opts = n.putObject("options");
        opts.put("temperature", temperature);
        if (numPredict > 0) opts.put("num_predict", numPredict);
        if (!stop.isEmpty()) opts.set("stop", stop);
        n.put("keep_alive", keepAlive);
        return n.toString();
    }
}
