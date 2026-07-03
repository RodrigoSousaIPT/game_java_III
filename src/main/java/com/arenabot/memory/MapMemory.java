package com.arenabot.memory;

import com.arenabot.model.CofreNoMundo;
import com.arenabot.model.GameState;
import com.arenabot.model.MeuEstado;
import com.arenabot.model.ObjetoFixo;
import com.arenabot.model.OutroRobot;
import com.arenabot.model.RecursoNoMundo;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent spatial memory. {@code objetos_fixos} is cached permanently once
 * discovered (per ARENA_API.md §9 "Map caching"); the live resources, robots,
 * and vaults are layered on top, replayed from {@code /perceive}.
 *
 * <p>Thread-safety model: UI thread reads frequently via {@link #snapshot()},
 * the Tier-1 commander reads the full snapshot every 5-10s, the tick thread
 * writes on every perceive. We use a {@link ReentrantReadWriteLock} so reads
 * are concurrent and writes do not tear readers with a partial delta.
 *
 * <p>JSON snapshot to {@code data/memory.json} on every successful snapshot
 * so we can resume across crashes.
 */
public final class MapMemory {

    private static final Logger LOG = LoggerFactory.getLogger(MapMemory.class);

    private static final long PERSIST_INTERVAL_MS = 5_000L;

    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    private final List<ObjetoFixo> walls = new ArrayList<>();
    private final List<RecursoNoMundo> resources = new ArrayList<>();
    private final Map<String, OutroRobot> robots = new LinkedHashMap<>();
    private final List<CofreNoMundo> vaults = new ArrayList<>();
    private MeuEstado me = new MeuEstado();
    private long lastUpdateMs;
    private long lastPersistMs;

    public void observe(GameState current) {
        rw.writeLock().lock();
        try {
            // Merge objs_fixos, never drop a known wall — only insert newly-seen ones.
            for (ObjetoFixo f : current.objetosFixos()) {
                if (walls.stream().noneMatch(w -> w.id().equals(f.id()))) {
                    walls.add(f);
                }
            }
            resources.clear();
            resources.addAll(current.recursosNoMundo());
            robots.clear();
            robots.putAll(current.outrosRobots());
            vaults.clear();
            vaults.addAll(current.cofresNoMundo());
            me = current.meuEstado();
            lastUpdateMs = System.currentTimeMillis();
        } finally {
            rw.writeLock().unlock();
        }
        // Coalesce persistence: write at most once per PERSIST_INTERVAL_MS.
        // We still hold a snapshot() reference above, so writes are cheap and
        // the JSON serialisation never lands on the tick thread's hot path.
        long now = System.currentTimeMillis();
        if (now - lastPersistMs >= PERSIST_INTERVAL_MS) {
            lastPersistMs = now;
            persist();
        }
    }

    /** Read-only view for pathfinding / UI. Never returns null entries. */
    public MemorySnapshot snapshot() {
        rw.readLock().lock();
        try {
            return new MemorySnapshot(
                    new ArrayList<>(walls),
                    new ArrayList<>(resources),
                    new LinkedHashMap<>(robots),
                    new ArrayList<>(vaults),
                    me, lastUpdateMs);
        } finally {
            rw.readLock().unlock();
        }
    }

    /** True iff any {@code parede_*} id has been seen at (tileX, tileY). */
    public boolean isWallAt(int tileX, int tileY) {
        rw.readLock().lock();
        try {
            for (ObjetoFixo w : walls) {
                if ((int) Math.round(w.x()) == tileX && (int) Math.round(w.y()) == tileY) return true;
            }
            return false;
        } finally {
            rw.readLock().unlock();
        }
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        ObjectMapper m = configuredMapper();
        m.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), snapshot());
    }

    public void load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return;
        ObjectMapper m = configuredMapper();
        MemorySnapshot snap = m.readValue(file.toFile(), MemorySnapshot.class);
        rw.writeLock().lock();
        try {
            walls.clear(); walls.addAll(snap.walls());
            resources.clear(); resources.addAll(snap.resources());
            robots.clear(); robots.putAll(snap.robots());
            vaults.clear(); vaults.addAll(snap.vaults());
            me = snap.me();
            lastUpdateMs = snap.lastUpdateMs();
        } finally {
            rw.writeLock().unlock();
        }
    }

    /** Configure Jackson to look at private fields directly — our model
     *  classes use {@code x()}-style accessors which Jackson would otherwise
     *  reject as "no bean properties discovered". */
    private static ObjectMapper configuredMapper() {
        ObjectMapper m = new ObjectMapper();
        m.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        m.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
        m.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
        m.setVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.NONE);
        return m;
    }

    private void persist() {
        try {
            save(Path.of("data", "memory.json"));
        } catch (IOException e) {
            // The next coalesced write will retry; we just rebuild on next start.
            LOG.warn("memory persist failed (will retry): {}", e.toString());
        }
    }

    /** Force a synchronous persist (e.g. on shutdown). Coalescing ignored. */
    public void flush() {
        rw.readLock().lock();
        try {
            persist();
            lastPersistMs = System.currentTimeMillis();
        } finally {
            rw.readLock().unlock();
        }
    }

    // ---- unused import collectors (kept for forward-compat import insight above)
    @SuppressWarnings("unused")
    private static final Class<?> KEEP = GameState.class;
}
