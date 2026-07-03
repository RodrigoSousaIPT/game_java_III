package com.arenabot.bot;

import com.arenabot.api.ArenaApiClient;
import com.arenabot.brain.ChestSpecialist;
import com.arenabot.brain.CommanderStrategist;
import com.arenabot.config.AppConfig;
import com.arenabot.memory.MapMemory;
import com.arenabot.memory.MemorySnapshot;
import com.arenabot.model.CofreNoMundo;
import com.arenabot.model.GameState;
import com.arenabot.model.MissionObjective;
import com.arenabot.pathfinding.AStarPathfinder;
import com.arenabot.pathfinding.GridPos;
import com.arenabot.pathfinding.PathResult;
import com.arenabot.resilience.ApiRetry;
import com.arenabot.resilience.CircuitBreaker;
import com.arenabot.resilience.PromptRingBuffer;
import com.arenabot.resilience.StuckTileDetector;
import com.arenabot.strategy.AdaptiveStrategy;
import com.arenabot.strategy.OpponentAwareness;
import com.arenabot.strategy.VaultAttemptLedger;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tier-2 reflex engine — Phase 3 wired.
 *
 * <p>Combines the resilience helpers (stuck-tile detector, prompt ring buffer,
 * circuit breaker around /arena/action, retry helper) and the strategy helpers
 * (adaptive override, opponent awareness, 2-attempt vault ledger).
 */
public final class BotOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(BotOrchestrator.class);

    private final AppConfig config;
    private final ArenaApiClient api;
    private final MapMemory memory;
    private final AStarPathfinder pathfinder;
    private final CommanderStrategist commander;
    private final ChestSpecialist chestSpecialist;

    // Phase 3 helpers
    private final StuckTileDetector stuck;
    private final PromptRingBuffer prompts;
    private final CircuitBreaker actionBreaker;
    private final AdaptiveStrategy adaptive;
    private final OpponentAwareness opponents;
    private final VaultAttemptLedger vaultLedger;

    private final AtomicReference<GameState> last = new AtomicReference<>();
    private ScheduledExecutorService scheduler;
    private String robotId;
    private volatile boolean running;
    private volatile String activeRoomCode;

    /* --------- constructors --------- */

    public BotOrchestrator(AppConfig config,
                           ArenaApiClient api,
                           MapMemory memory,
                           CommanderStrategist commander,
                           ChestSpecialist chestSpecialist) {
        this(config, api, memory, commander, chestSpecialist,
                new StuckTileDetector(),
                new PromptRingBuffer(),
                new CircuitBreaker(),
                new AdaptiveStrategy(),
                new OpponentAwareness(),
                new VaultAttemptLedger());
    }

    public BotOrchestrator(AppConfig config,
                           ArenaApiClient api,
                           MapMemory memory,
                           CommanderStrategist commander,
                           ChestSpecialist chestSpecialist,
                           StuckTileDetector stuck,
                           PromptRingBuffer prompts,
                           CircuitBreaker actionBreaker,
                           AdaptiveStrategy adaptive,
                           OpponentAwareness opponents,
                           VaultAttemptLedger vaultLedger) {
        this.config = config;
        this.api = api;
        this.memory = memory;
        this.pathfinder = new AStarPathfinder(memory);
        this.commander = commander;
        this.chestSpecialist = chestSpecialist;
        this.stuck = stuck;
        this.prompts = prompts;
        this.actionBreaker = actionBreaker;
        this.adaptive = adaptive;
        this.opponents = opponents;
        this.vaultLedger = vaultLedger;
    }

    /* --------- lifecycle --------- */

    public synchronized void start() { startWithRoomCode(config.roomCode()); }

    public synchronized void startWithRoomCode(String roomCode) {
        if (running) return;
        this.activeRoomCode = roomCode == null || roomCode.isBlank() ? config.roomCode() : roomCode;
        running = true;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        robotId = config.robotIdPrefix() + "-" + suffix;
        try {
            tryRegister();
            primeManual();
        } catch (IOException io) {
            LOG.warn("initial setup failed, continuing with reduced capability: {}", io.toString());
        }
        commander.start();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bot-tick-t2");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tick, 0, config.tickMs(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        running = false;
        commander.stop();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        memory.flush();
    }

    /* --------- read-only views for UI --------- */

    public String robotId() { return robotId; }
    public GameState lastState() { return last.get(); }
    public String activeRoomCode() { return activeRoomCode; }
    public MemorySnapshot stateSnapshotForUi() { return memory.snapshot(); }
    public PromptRingBuffer promptBuffer() { return prompts; }
    public CircuitBreaker actionCircuit() { return actionBreaker; }
    public StuckTileDetector stuckDetector() { return stuck; }
    public VaultAttemptLedger vaultLedger() { return vaultLedger; }

    /* --------- inner loop --------- */

    private void tick() {
        if (!running) return;
        String room = activeRoomCode;
        try {
            GameState state = ApiRetry.call(
                    () -> api.perceive(room, robotId),
                    actionBreaker,
                    ex -> ex instanceof IOException);
            last.set(state);
            if (!state.isRegistered()) {
                tryRegister();
                return;
            }
            memory.observe(state);
            MemorySnapshot snap = memory.snapshot();
            commander.updateSnapshot(snap, isLowEnergy(state));

            // Phase 3 §1 — stuck-tile detection.
            GridPos meTile = GridPos.of(state.meuEstado().x(), state.meuEstado().y());
            if (stuck.observe(meTile)) {
                LOG.warn("stuck-tile detector triggered on {} (trail={}); randomising next step",
                        meTile, stuck.trailSize());
                submitRawAction(room, ActionVocabulary.primary(Action.AVANCAR));
                stuck.reset();
                return;
            }

            // Stand on a cofre? Let Tier 3 handle unlock in fire-and-forget mode.
            CofreNoMundo onTile = findVaultUnderMe(state, snap);
            if (onTile != null) {
                chestSpecialist.solveVault("Unlock cofre " + onTile.id())
                        .thenAccept(plan -> submitUnlock(onTile, plan.code(), plan.ragChunk(), plan.llmRaw()));
                return;
            }

            MissionObjective obj = commander.board().current();
            Action action = decide(state, snap, obj);
            action = adaptive.overrideWithWorld(action, state, snap, opponents);
            String token = forToken(action, state);
            if (!actionBreaker.allowRequest()) {
                LOG.info("[tick] action circuit breaker OPEN ({}) — skipping submit",
                        actionBreaker.consecutiveFailures());
                return;
            }
            submitRawAction(room, token);
        } catch (Exception ex) {
            if (ex instanceof IOException) {
                actionBreaker.recordFailure();
            }
            LOG.warn("[tick] error: {}", ex.toString());
        }
    }

    /** Token chosen for an action — picks the ECO-MARCH raw verb when needed. */
    private String forToken(Action action, GameState state) {
        if (action == Action.USAR && AdaptiveStrategy.needsEcoMarch(state)) {
            return AdaptiveStrategy.ecoMarchPayload();
        }
        return ActionVocabulary.primary(action);
    }

    private void submitRawAction(String room, String token) {
        try {
            JsonNode resp = ApiRetry.call(
                    () -> api.action(room, robotId, token),
                    actionBreaker,
                    ex -> ex instanceof IOException);
            if (api.isOccupancyRejection(resp)) {
                LOG.info("server says 'bloqueado' / game not started; will retry next tick");
            }
        } catch (IOException io) {
            LOG.warn("[action] IO error on submit '{}': {}", token, io.toString());
        }
    }

    private void primeManual() throws IOException {
        String manual = api.downloadManual(activeRoomCode);
        chestSpecialist.primeSections(manual);
    }

    private void tryRegister() throws IOException {
        int attempts = 0;
        while (running) {
            attempts++;
            try {
                var reg = api.register(activeRoomCode, robotId);
                if ("registado".equals(reg.status())) {
                    api.resetBackoff();
                    LOG.info("registered as {} (attempts={})", robotId, attempts);
                    return;
                }
                LOG.info("register returned status={}, retrying after backoff", reg.status());
                api.coolDownBackoff();
            } catch (IOException io) {
                api.coolDownBackoff();
                LOG.info("register failed ({}), backing off", io.toString());
            }
        }
    }

    /* --------- decision helpers --------- */

    private Action decide(GameState state, MemorySnapshot snap, MissionObjective obj) {
        if (!state.hasGameStarted() || state.hasGameOver()) return Action.STATUS;
        if (obj == null) return Action.STATUS;
        switch (obj.goal()) {
            case HOLD, FUZZ: return Action.STATUS;
            case RECHARGE, RETREAT: return routeTo(state, obj, Action.RECOLHER);
            case GOTO_ENERGY:       return routeTo(state, obj, Action.AVANCAR);
            case GOTO_CHEST:        return routeTo(state, obj, Action.ABRIR);
            case UNLOCK_VAULT:      return routeToOrStatus(state, snap, obj);
            case EXPLORE:
            default:
                return Action.AVANCAR;
        }
    }

    /** For UNLOCK_VAULT goal: respect the 2-attempt vault ledger cap. */
    private Action routeToOrStatus(GameState state, MemorySnapshot snap, MissionObjective obj) {
        CofreNoMundo target = firstMatchingVault(state, snap, obj);
        if (target == null) return Action.STATUS;
        if (vaultLedger.canAttempt(target.id())) {
            return routeTo(state, obj, Action.MOVER);
        }
        LOG.info("vault {} is at attempt cap; skipping", target.id());
        return Action.AVANCAR;
    }

    private CofreNoMundo firstMatchingVault(GameState state, MemorySnapshot snap, MissionObjective obj) {
        GridPos me = GridPos.of(state.meuEstado().x(), state.meuEstado().y());
        GridPos goal = obj == null ? me : GridPos.of(obj.targetX(), obj.targetY());
        for (CofreNoMundo c : snap.vaults()) {
            GridPos cPos = GridPos.of(c.x(), c.y());
            if (cPos.manhattan(goal) <= 1 || cPos.manhattan(me) <= 1) return c;
        }
        return snap.vaults().isEmpty() ? null : snap.vaults().get(0);
    }

    private Action routeTo(GameState state, MissionObjective obj, Action onArrival) {
        if (obj == null || !obj.hasTarget()) return Action.AVANCAR;
        GridPos me = GridPos.of(state.meuEstado().x(), state.meuEstado().y());
        GridPos goal = GridPos.of(obj.targetX(), obj.targetY());
        PathResult path = pathfinder.findPath(me, goal, 1.25);
        if (!path.found()) {
            LOG.info("no A* route {} -> {} ({}); falling back", me, goal, path.reason());
            return Action.AVANCAR;
        }
        GridPos next = path.nextStep().orElse(me);
        if (next.equals(me)) return onArrival;
        return Action.AVANCAR;
    }

    private boolean isLowEnergy(GameState state) {
        return state.meuEstado().energia() <= config.energyCriticalThreshold();
    }

    private CofreNoMundo findVaultUnderMe(GameState state, MemorySnapshot snap) {
        GridPos me = GridPos.of(state.meuEstado().x(), state.meuEstado().y());
        for (CofreNoMundo c : snap.vaults()) {
            if (GridPos.of(c.x(), c.y()).equals(me)) return c;
        }
        return null;
    }

    /* --------- unlock path --------- */

    private void submitUnlock(CofreNoMundo cofre, String code, String ragChunk, String llmRaw) {
        if (code == null || code.isBlank()) {
            LOG.info("skipping unlock at cofre {} — empty LLM code", cofre.id());
            return;
        }
        if (!vaultLedger.canAttempt(cofre.id())) {
            LOG.info("skipping unlock at cofre {} — 2-attempt cap reached", cofre.id());
            return;
        }
        GameState cur = last.get();
        if (cur != null && cur.isRegistered()) {
            GridPos me = GridPos.of(cur.meuEstado().x(), cur.meuEstado().y());
            if (!GridPos.of(cofre.x(), cofre.y()).equals(me)) {
                LOG.info("skipping unlock at cofre {} — bot moved to {}", cofre.id(), me);
                return;
            }
        }
        try {
            JsonNode resp = api.unlock(activeRoomCode, robotId, code, ragChunk, llmRaw);
            boolean success = resp != null
                    && resp.has("status")
                    && "sucesso".equals(resp.get("status").asText());
            vaultLedger.record(cofre.id(), success);
            prompts.push("LLM_RAW (chest specialist): " + llmRaw, "CHEST",
                    success ? "vault " + cofre.id() + " unlocked" : "vault " + cofre.id() + " rejected");
            LOG.info("unlock attempt at cofre {} code={} resp={} attempt={}/{}",
                    cofre.id(), code, resp, vaultLedger.attemptsUsed(cofre.id()),
                    VaultAttemptLedger.MAX_ATTEMPTS_PER_VAULT);
        } catch (IOException e) {
            LOG.warn("unlock failed: {}", e.toString());
            vaultLedger.record(cofre.id(), false);
        }
    }
}
