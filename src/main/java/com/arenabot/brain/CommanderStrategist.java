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
            // Periodic LLM review at half the cadence; complements the heuristic.
            // The reply is parsed and, when valid, overrides the board.
            if (id % 2 == 0) {
                String prompt = buildPrompt(s);
                ollama.generateAsync(LlmRole.COMMANDER, prompt).thenAccept(resp -> {
                    MissionObjective llmObj = parseObjective(resp.response);
                    if (llmObj != null) {
                        LOG.info("commander LLM override: {}", llmObj);
                        board.set(llmObj);
                    }
                });
            }
        } catch (Throwable t) {
            LOG.warn("commander tick failed: {}", t.toString());
        }
    }

    /** Compact world summary the small commander model can reason over. */
    private String buildPrompt(MemorySnapshot s) {
        StringBuilder sb = new StringBuilder(512);
        MeuEstado me = s.me();
        sb.append("You command a robot in a grid arena. Position=(")
          .append((int) Math.round(me.x())).append(',').append((int) Math.round(me.y()))
          .append(") energy=").append(me.energia()).append('\n');
        sb.append("Known walls: ").append(s.walls().size()).append('\n');
        s.resources().stream().limit(6).forEach(r ->
                sb.append("resource ").append(r.type()).append(" at (")
                  .append((int) Math.round(r.x())).append(',')
                  .append((int) Math.round(r.y())).append(")\n"));
        s.vaults().stream().limit(4).forEach(v ->
                sb.append("vault ").append(v.id()).append(" at (")
                  .append((int) Math.round(v.x())).append(',')
                  .append((int) Math.round(v.y())).append(")\n"));
        sb.append("Opponents: ").append(s.robots().size()).append('\n');
        sb.append("Current objective: ").append(board.current().goal()).append('\n');
        sb.append("Reply with EXACTLY one line: GOAL X Y\n");
        sb.append("GOAL is one of EXPLORE, GOTO_CHEST, GOTO_ENERGY, UNLOCK_VAULT, HOLD.\n");
        sb.append("X Y are integer target coordinates (use 0 0 for EXPLORE/HOLD).\n");
        return sb.toString();
    }

    /**
     * Parses "GOAL X Y" out of the LLM reply. Returns null when the reply is
     * malformed or names an unknown goal — the heuristic objective then stands.
     */
    static MissionObjective parseObjective(String llmReply) {
        if (llmReply == null || llmReply.isBlank()) return null;
        for (String line : llmReply.split("\\R")) {
            String[] parts = line.trim().toUpperCase().split("\\s+");
            if (parts.length == 0) continue;
            MissionObjective.Goal goal;
            try {
                goal = MissionObjective.Goal.valueOf(parts[0]);
            } catch (IllegalArgumentException notAGoal) {
                continue;
            }
            if (goal == MissionObjective.Goal.HOLD) return MissionObjective.hold("commander LLM: hold");
            if (goal == MissionObjective.Goal.EXPLORE) return MissionObjective.explore();
            if (parts.length < 3) return null;
            try {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                if (x < 0 || y < 0 || x > 512 || y > 512) return null;
                return new MissionObjective(goal, x, y, "commander LLM");
            } catch (NumberFormatException bad) {
                return null;
            }
        }
        return null;
    }
}
