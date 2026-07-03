package com.arenabot.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * "My state" — the bot's published position, energy, and assigned hex color.
 * Per ARENA_API.md §2 the field {@code o_meu_estado} is the success tell of
 * the perceive endpoint; its absence means the bot is unregistered.
 */
public final class MeuEstado {

    private double x;
    private double y;
    private double z;
    private int energia;
    private String cor = "#0ea5e9";

    public MeuEstado() {}

    public MeuEstado(double x, double y, double z, int energia, String cor) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.energia = energia;
        this.cor = cor;
    }

    /** Forward-compat parser: ignores unknown keys instead of throwing. */
    public static MeuEstado fromJson(JsonNode n) {
        MeuEstado s = new MeuEstado();
        if (n == null || !n.isObject()) return s;
        s.x = numberOr(n, "x", s.x);
        s.y = numberOr(n, "y", s.y);
        s.z = numberOr(n, "z", s.z);
        s.energia = intOr(n, "energia", s.energia);
        JsonNode cor = n.get("cor");
        s.cor = cor == null || !cor.isTextual() ? s.cor : cor.asText();
        return s;
    }

    private static double numberOr(JsonNode n, String f, double dflt) {
        JsonNode v = n.get(f);
        return v == null || !v.isNumber() ? dflt : v.asDouble(dflt);
    }

    private static int intOr(JsonNode n, String f, int dflt) {
        JsonNode v = n.get(f);
        return v == null || !v.canConvertToInt() ? dflt : v.asInt(dflt);
    }

    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public int energia() { return energia; }
    public String cor() { return cor; }

    public void setX(double x) { this.x = x; }
    public void setY(double y) { this.y = y; }
    public void setZ(double z) { this.z = z; }
    public void setEnergia(int energia) { this.energia = energia; }
    public void setCor(String cor) { this.cor = cor; }
}
