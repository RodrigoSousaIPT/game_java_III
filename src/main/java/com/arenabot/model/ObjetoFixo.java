package com.arenabot.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A static world block: walls ({@code parede_*} with model {@code cubo.glb})
 * and any other non-moving decoration. Per ARENA_API.md §2 these should be
 * treated as a persistent map layer cached in {@link com.arenabot.memory.MapMemory}.
 */
public final class ObjetoFixo {

    private String id = "";
    private String type = "";
    private String model = "";
    private double x;
    private double y;
    private double z;

    public static ObjetoFixo fromJson(JsonNode n) {
        ObjetoFixo o = new ObjetoFixo();
        if (n == null || !n.isObject()) return o;
        JsonNode id = n.get("id");
        o.id = id == null || !id.isTextual() ? "" : id.asText();
        JsonNode type = n.get("type");
        o.type = type == null || !type.isTextual() ? "" : type.asText();
        JsonNode model = n.get("model");
        o.model = model == null || !model.isTextual() ? "" : model.asText();
        o.x = numb(n, "x", o.x);
        o.y = numb(n, "y", o.y);
        o.z = numb(n, "z", o.z);
        return o;
    }

    private static double numb(JsonNode n, String f, double d) {
        JsonNode v = n.get(f);
        return v == null || !v.isNumber() ? d : v.asDouble(d);
    }

    public boolean isWall() {
        // The server identifies walls with id="parede_..." or model="cubo.glb"
        return id.startsWith("parede_") || "cubo.glb".equals(model);
    }

    public String id() { return id; }
    public String type() { return type; }
    public String model() { return model; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
}
