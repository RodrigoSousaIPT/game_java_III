package com.arenabot.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One entry in the live opponent map {@code outros_robots}, keyed by
 * {@code robot_id}. This is the asymmetric JSON shape that trips up parsers
 * per ARENA_API.md §2 — the rest are arrays, this is a dict.
 */
public final class OutroRobot {

    private String robotId = "";
    private double x;
    private double y;
    private double z;
    private int energia;
    private String cor = "";
    /** Future-compat: HP / score fields if the server adds them. */
    private int hp;
    private int score;

    public static OutroRobot fromJson(String key, JsonNode n) {
        OutroRobot r = new OutroRobot();
        r.robotId = key;
        if (n == null || !n.isObject()) return r;
        JsonNode x = n.get("x");
        r.x = x == null || !x.isNumber() ? r.x : x.asDouble();
        JsonNode y = n.get("y");
        r.y = y == null || !y.isNumber() ? r.y : y.asDouble();
        JsonNode z = n.get("z");
        r.z = z == null || !z.isNumber() ? r.z : z.asDouble();
        JsonNode en = n.get("energia");
        r.energia = en == null || !en.canConvertToInt() ? r.energia : en.asInt();
        JsonNode cor = n.get("cor");
        r.cor = cor == null || !cor.isTextual() ? r.cor : cor.asText();
        JsonNode hp = n.get("hp");
        r.hp = hp == null || !hp.canConvertToInt() ? r.hp : hp.asInt();
        JsonNode sc = n.get("score");
        r.score = sc == null || !sc.canConvertToInt() ? r.score : sc.asInt();
        return r;
    }

    public String robotId() { return robotId; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public int energia() { return energia; }
    public String cor() { return cor; }
    public int hp() { return hp; }
    public int score() { return score; }
}
