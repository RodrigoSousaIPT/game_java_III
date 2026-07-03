package com.arenabot.brain;

import com.arenabot.config.AppConfig;
import com.arenabot.memory.MemorySnapshot;
import com.arenabot.model.MeuEstado;
import com.arenabot.model.MissionObjective;
import com.arenabot.ollama.LlmRole;
import com.arenabot.ollama.OllamaClient;
import com.arenabot.ollama.OllamaResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tier-1 brain. Pulled the commander gemma4-nano-e2b per
 * {@code context/ais_used.txt}. Runs every {@code commander_period_ms}
 * (default 7s) and writes a {@link MissionObjective} into the
 * {@link MissionObjective.Board} used by the Tier-2 tick engine.
 *
 * <p>The strategist is a "smart scheduler"; we don't expect it to be cheap, so
 * its loop runs on a private single-thread executor and never touches the
 * tick thread.
 */
public final class CommanderStrategist {

    private static final Logger LOG = LoggerFactory.getLogger(CommanderStrategist.class);

    private final OllamaClient ollama;
    private final AppConfig config;
    private final MissionObjective.Board board = new MissionObjective.Board();
    private final AtomicInteger runId = new AtomicInteger();
    private ScheduledExecutorService scheduler;
    private volatile MemorySnapshot latest;
    private volatile boolean lowEnergy;

    public CommanderStrategist(OllamaClient ollama, AppConfig config) {
        this.ollama = ollama;
        this.config = config;
    }

    public MissionObjective.Board board() { return board; }

    public void updateSnapshot(MemorySnapshot snapshot, boolean lowEnergy) {
        this.latest = snapshot;
        this.lowEnergy = lowEnergy;
    }

    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bot-commander-t1");
            t.setDaemon(true);
            return t;
        });
        long period = config.commanderPeriodMs();
        scheduler.scheduleAtFixedRate(this::runOnce, period, period, TimeUnit.MILLISECONDS);
        LOG.info("Commander-Strategist started, period={}ms", period);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void runOnce() {
        try {
            MemorySnapshot s = latest;
            if (s == null) {
                board.set(MissionObjective.hold("no snapshot yet"));
                return;
            }
            // Heuristic fallback: decide based on local signals without burning
            // an LLM call every period. The LLM is invoked only periodically
            // and only when the heuristic says "act now".
            MeuEstado me = s.me();
            if (lowEnergy) {
                // Find nearest yellow pickup or retreat to holder pose.
                var yellow = s.resources().stream()
                        .filter(r -> r.type().toLowerCase().contains("bateria")
                                || r.type().toLowerCase().contains("energy"))
                        .findFirst();
                if (yellow.isPresent()) {
                    board.set(new MissionObjective(
                            MissionObjective.Goal.GOTO_ENERGY,
                            yellow.get().x(), yellow.get().y(),
                            "energy critical, rush yellow pickup"));
                } else {
                    board.set(MissionObjective.hold("low energy, no yellow visible"));
                }
                return;
            }
            // Otherwise chase the nearest chest.
            s.resources().stream()
                    .filter(r -> r.type().toLowerCase().contains("bau")
                            || r.type().toLowerCase().contains("chest"))
                    .findFirst()
                    .ifPresentOrElse(
                            chest -> board.set(new MissionObjective(
                                    MissionObjective.Goal.GOTO_CHEST,
                                    chest.x(), chest.y(),
                                    "commander: chase nearest chest")),
                            () -> {
                                if (!s.vaults().isEmpty()) {
                                    var v = s.vaults().get(0);
                                    board.set(new MissionObjective(
                                            MissionObjective.Goal.UNLOCK_VAULT,
                                            v.x(), v.y(),
                                            "commander: pursue vault"));
                                } else {
                                    board.set(MissionObjective.explore());
                                }
                            });
            int id = runId.incrementAndGet();
            // Periodic LLM sanity-check at half the cadence; complements heuristic.
            if (id % 2 == 0) ollama.generateAsync(LlmRole.COMMANDER,
                    "Commander prompt with map of size " + s.walls().size()
                            + " walls; current objective: " + board.current().goal());
        } catch (Throwable t) {
            LOG.warn("commander tick failed: {}", t.toString());
        }
    }
}
