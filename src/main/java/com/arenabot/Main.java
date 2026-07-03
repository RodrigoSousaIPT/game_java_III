package com.arenabot;

import com.arenabot.api.ArenaApiClient;
import com.arenabot.brain.ChestSpecialist;
import com.arenabot.brain.CommanderStrategist;
import com.arenabot.brain.ManualEmbedder;
import com.arenabot.bot.BotOrchestrator;
import com.arenabot.config.AppConfig;
import com.arenabot.memory.MapMemory;
import com.arenabot.memory.MemorySnapshot;
import com.arenabot.model.MeuEstado;
import com.arenabot.ollama.OllamaClient;
import com.arenabot.resilience.CircuitBreaker;
import com.arenabot.resilience.PromptRingBuffer;
import com.arenabot.resilience.StuckTileDetector;
import com.arenabot.strategy.AdaptiveStrategy;
import com.arenabot.strategy.OpponentAwareness;
import com.arenabot.strategy.VaultAttemptLedger;
import com.arenabot.ui.TelemetryBus;
import com.arenabot.ui.TelemetryDashboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Paths;

/**
 * Entry point. Wires Phase 1 - 3 modules into the runtime:
 * <pre>
 *   AppConfig → ArenaApiClient → BotOrchestrator
 *     ↘ OllamaClient ↔ PromptRingBuffer (Phase 3 LLM leak guard)
 *     ↘ CommanderStrategist (T1) + ChestSpecialist (T3)
 *     ↘ MapMemory → A* within BotOrchestrator
 *     ↘ CircuitBreaker, StuckTileDetector, AdaptiveStrategy,
 *        OpponentAwareness, VaultAttemptLedger (Phase 3)
 *     ↘ TelemetryBus → TelemetryDashboard (Swing EDT)
 * </pre>
 * If the JVM has no GUI (headless server) the bot still runs, just without
 * the Swing frame — useful for CI smoke tests.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) throws IOException {
        AppConfig config = AppConfig.load(args.length > 0 ? Paths.get(args[0]) : null);
        LOG.info("loaded config room={} tick={}ms commander={}ms",
                config.roomCode(), config.tickMs(), config.commanderPeriodMs());

        ArenaApiClient api = new ArenaApiClient(config.arenaBaseUrl());
        MapMemory memory = new MapMemory();
        PromptRingBuffer prompts = new PromptRingBuffer();
        OllamaClient ollama = new OllamaClient(config, prompts);
        ManualEmbedder embedder = new ManualEmbedder();
        ChestSpecialist chest = new ChestSpecialist(ollama, embedder);
        CommanderStrategist commander = new CommanderStrategist(ollama, config);

        // Phase 3 helpers
        AdaptiveStrategy adaptive = new AdaptiveStrategy();
        OpponentAwareness opponents = new OpponentAwareness();
        VaultAttemptLedger vaultLedger = new VaultAttemptLedger();

        BotOrchestrator orchestrator = new BotOrchestrator(
                config, api, memory, commander, chest,
                new StuckTileDetector(),
                prompts,
                new CircuitBreaker(),
                adaptive, opponents, vaultLedger);
        TelemetryBus bus = new TelemetryBus();

        boolean headless = GraphicsEnvironment.isHeadless();
        if (!headless) {
            TelemetryDashboard ui = new TelemetryDashboard(config, orchestrator, bus);
            ui.show();
        } else {
            LOG.info("headless JVM — running without Swing dashboard");
        }

        // Graceful shutdown so the scheduler's executor really stops.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("shutdown hook: stopping orchestrator");
            orchestrator.stop();
        }, "shutdown"));
    }

    /** Convenience construction site for tests/smoke tests. */
    public static BotOrchestrator bootstrapOrchestrator(AppConfig config) {
        ArenaApiClient api = new ArenaApiClient(config.arenaBaseUrl());
        MapMemory memory = new MapMemory();
        PromptRingBuffer prompts = new PromptRingBuffer();
        OllamaClient ollama = new OllamaClient(config, prompts);
        ChestSpecialist chest = new ChestSpecialist(ollama, new ManualEmbedder());
        CommanderStrategist commander = new CommanderStrategist(ollama, config);
        return new BotOrchestrator(config, api, memory, commander, chest);
    }

    public static MapMemory bootstrapMemory() { return new MapMemory(); }

    @SuppressWarnings("unused")
    private static final Class<?> KEEP = MeuEstado.class;
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_SNAP = MemorySnapshot.class;
}
