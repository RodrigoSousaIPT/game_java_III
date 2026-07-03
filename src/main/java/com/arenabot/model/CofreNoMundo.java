package com.arenabot.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A vault (cofre) lying on the map. Per ARENA_API.md §5 the bot must stand
 * on the cofre tile and POST {@code /arena/{room_id}/unlock} with the right
 * code; telemetry fields {@code rag_chunk} and {@code llm_raw} MUST be sent
 * to the admin dashboard so reasoning is auditable.
 */
public final class CofreNoMundo {

    private String id = "";
    private double x;
    private double y;
    private double z;
    private String cor = "#a855f7";

    public static CofreNoMundo fromJson(JsonNode n) {
        CofreNoMundo c = new CofreNoMundo();
        if (n == null || !n.isObject()) return c;
        JsonNode id = n.get("id");
        c.id = id == null || !id.isTextual() ? "" : id.asText();
        JsonNode x = n.get("x");
        c.x = x == null || !x.isNumber() ? c.x : x.asDouble();
        JsonNode y = n.get("y");
        c.y = y == null || !y.isNumber() ? c.y : y.asDouble();
        JsonNode z = n.get("z");
        c.z = z == null || !z.isNumber() ? c.z : z.asDouble();
        JsonNode cor = n.get("cor");
        c.cor = cor == null || !cor.isTextual() ? c.cor : cor.asText();
        return c;
    }

    public String id() { return id; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public String cor() { return cor; }
}
