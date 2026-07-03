package com.arenabot.memory;

import com.arenabot.model.CofreNoMundo;
import com.arenabot.model.MeuEstado;
import com.arenabot.model.ObjetoFixo;
import com.arenabot.model.OutroRobot;
import com.arenabot.model.RecursoNoMundo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable Jackson-friendly view of {@link MapMemory} saved to
 * {@code data/memory.json}. Kept as a separate type so the live memory
 * representation can evolve without breaking on-disk persistence.
 */
public final class MemorySnapshot {

    public List<ObjetoFixo> walls = new ArrayList<>();
    public List<RecursoNoMundo> resources = new ArrayList<>();
    public Map<String, OutroRobot> robots = new LinkedHashMap<>();
    public List<CofreNoMundo> vaults = new ArrayList<>();
    public MeuEstado me = new MeuEstado();
    public long lastUpdateMs;

    public MemorySnapshot() {}

    public MemorySnapshot(List<ObjetoFixo> walls,
                          List<RecursoNoMundo> resources,
                          Map<String, OutroRobot> robots,
                          List<CofreNoMundo> vaults,
                          MeuEstado me,
                          long lastUpdateMs) {
        this.walls = new ArrayList<>(walls);
        this.resources = new ArrayList<>(resources);
        this.robots = new LinkedHashMap<>(robots);
        this.vaults = new ArrayList<>(vaults);
        this.me = me;
        this.lastUpdateMs = lastUpdateMs;
    }

    public List<ObjetoFixo> walls() { return walls; }
    public List<RecursoNoMundo> resources() { return resources; }
    public Map<String, OutroRobot> robots() { return robots; }
    public List<CofreNoMundo> vaults() { return vaults; }
    public MeuEstado me() { return me; }
    public long lastUpdateMs() { return lastUpdateMs; }
}
