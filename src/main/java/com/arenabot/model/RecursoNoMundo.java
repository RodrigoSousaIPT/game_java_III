package com.arenabot.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A pickup lying on the floor: {@code baterias} (yellow energy) or
 * {@code bau} (purple chest) per the notes in {@code ARENA_API.md} §2
 * (crossed with {@code old.txt}'s lexicon).
 *
 * <p>The server may also use {@code type=baú} (with diacritic) and
 * {@code cor} may be a hex string or absent; parser tolerates both.
 */
public final class RecursoNoMundo {

    private String id = "";
    private String type = "";
    private String model = "";
    private double x;
    private double y;
    private double z;
    /** Optional field — some server versions omit it. */
    private String cor = "";

    public static RecursoNoMundo fromJson(JsonNode n) {
        RecursoNoMundo r = new RecursoNoMundo();
        if (n == null || !n.isObject()) return r;
        JsonNode id = n.get("id");
        r.id = id == null || !id.isTextual() ? "" : id.asText();
        JsonNode type = n.get("type");
        r.type = type == null || !type.isTextual() ? "" : type.asText();
        JsonNode model = n.get("model");
        r.model = model == null || !model.isTextual() ? "" : model.asText();
        r.x = numb(n, "x", r.x);
        r.y = numb(n, "y", r.y);
        r.z = numb(n, "z", r.z);
        JsonNode cor = n.get("cor");
        r.cor = cor == null || !cor.isTextual() ? "" : cor.asText();
        return r;
    }

    private static double numb(JsonNode n, String f, double d) {
        JsonNode v = n.get(f);
        return v == null || !v.isNumber() ? d : v.asDouble(d);
    }

    public boolean isChest() {
        String t = type.toLowerCase();
        return t.contains("chest") || t.contains("bau") || t.contains("baú") || t.contains("bau_");
    }

    public boolean isEnergy() {
        String t = type.toLowerCase();
        // baterias / battery / energy
        return t.contains("bateria") || t.contains("battery") || t.contains("energy");
    }

    public String id() { return id; }
    public String type() { return type; }
    public String model() { return model; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public String cor() { return cor; }
}
