package com.arenabot.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * UI-only configuration: grid pixel size, world extents, and the auto-fuzz
 * action list that runs at connect time when {@code game_started} is false
 * (per ARENA_API.md §8 recommendation #1).
 */
public final class UiConfig {

    private int gridPixelSize = 32;
    private int gridXMax = 16;
    private int gridYMax = 16;
    private boolean autoFuzzOnConnect = false;
    private List<String> autoFuzzActions = new ArrayList<>();

    public static UiConfig fromNode(JsonNode n) {
        UiConfig u = new UiConfig();
        JsonNode ps = n.get("grid_pixel_size");
        u.gridPixelSize = ps == null || !ps.isInt() ? u.gridPixelSize : ps.asInt(u.gridPixelSize);
        JsonNode gx = n.get("grid_x_max");
        u.gridXMax = gx == null || !gx.isInt() ? u.gridXMax : gx.asInt(u.gridXMax);
        JsonNode gy = n.get("grid_y_max");
        u.gridYMax = gy == null || !gy.isInt() ? u.gridYMax : gy.asInt(u.gridYMax);
        JsonNode af = n.get("auto_fuzz_on_connect");
        u.autoFuzzOnConnect = af != null && af.asBoolean(u.autoFuzzOnConnect);

        JsonNode acts = n.get("auto_fuzz_actions");
        if (acts != null && acts.isArray()) {
            for (JsonNode v : acts) {
                if (v.isTextual()) u.autoFuzzActions.add(v.asText());
            }
        }
        return u;
    }

    public int gridPixelSize() { return gridPixelSize; }
    public int gridXMax() { return gridXMax; }
    public int gridYMax() { return gridYMax; }
    public boolean autoFuzzOnConnect() { return autoFuzzOnConnect; }
    public List<String> autoFuzzActions() { return autoFuzzActions; }
}
