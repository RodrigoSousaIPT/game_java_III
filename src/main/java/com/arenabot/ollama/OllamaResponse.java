package com.arenabot.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Defensive parser for {@code {"response": "...", "model": "...", "done": bool,
 * "prompt_eval_count": N, "eval_count": N, "total_duration": ns}}. Forward-compat
 * like the rest of the codebase — unknown keys are ignored.
 */
public final class OllamaResponse {

    public String model = "";
    public String response = "";
    public boolean done;
    public long promptEvalCount;
    public long evalCount;
    public long totalDurationNs;
    public long loadDurationNs;

    public static OllamaResponse fromJson(String body) {
        OllamaResponse r = new OllamaResponse();
        if (body == null || body.isBlank()) return r;
        try {
            JsonNode n = new ObjectMapper().readTree(body);
            JsonNode m = n.get("model"); r.model = m == null ? "" : m.asText("");
            JsonNode resp = n.get("response"); r.response = resp == null ? "" : resp.asText("");
            JsonNode d = n.get("done"); r.done = d != null && d.asBoolean();
            JsonNode pec = n.get("prompt_eval_count");
            if (pec != null && pec.canConvertToLong()) r.promptEvalCount = pec.asLong();
            JsonNode ec = n.get("eval_count");
            if (ec != null && ec.canConvertToLong()) r.evalCount = ec.asLong();
            JsonNode td = n.get("total_duration");
            if (td != null && td.canConvertToLong()) r.totalDurationNs = td.asLong();
            JsonNode ld = n.get("load_duration");
            if (ld != null && ld.canConvertToLong()) r.loadDurationNs = ld.asLong();
        } catch (Exception e) {
            r.response = body;
        }
        return r;
    }

    public boolean isSuccess() {
        return done && !response.isEmpty();
    }
}
