package com.arenabot.strategy;

import com.arenabot.bot.Action;
import com.arenabot.bot.ActionVocabulary;
import com.arenabot.model.GameState;
import com.arenabot.pathfinding.GridPos;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Post-filters the action chosen by {@code BotOrchestrator.decide()} based
 * on the live world state. Currently implements two tactical overrides from
 * the Phase 3 spec:
 *
 * <ol>
 *   <li>Low-energy incentive to call {@code usar ECO-MARCH} once the bot
 *       drops below {@code LOW_ENERGY_THRESHOLD} (the manual SECÇÃO 05
 *       token). This runs in
 *       {@link com.arenabot.bot.BotOrchestrator#decide(GameState, com.arenabot.model.MissionObjective, com.arenabot.memory.MemorySnapshot)}.</li>
 *   <li>Opponent crowding: if the bot is chasing a chest that an opponent
 *       is already closer to, swap to {@code EXPLORE} and let commander
 *       re-evaluate.</li>
 * </ol>
 */
public final class AdaptiveStrategy {

    public static final int LOW_ENERGY_THRESHOLD = 100;

    /** SECÇÃO 05 says use ECO-MARCH under 50% energy. */
    public static final String ECO_MARCH_RAW = "usar ECO-MARCH";

    /** Returns an explicit {@code usar} payload that the verbal vocabulary
     *  doesn't ship with — the server accepts the raw manual token. */
    public static String ecoMarchPayload() { return ECO_MARCH_RAW; }

    /** True iff the bot's energy has fallen below the low-energy band. */
    public static boolean needsEcoMarch(GameState state) {
        return state.meuEstado().energia() < LOW_ENERGY_THRESHOLD;
    }

    /**
     * Returns the action to submit instead of {@code desired} based on the
     * live world state. Returns {@code desired} unchanged when nothing
     * applies.
     */
    public Action overrideWithWorld(Action desired,
                                     GameState state,
                                     com.arenabot.memory.MemorySnapshot snap,
                                     OpponentAwareness opponents) {
        if (needsEcoMarch(state)) return Action.USAR;
        if (desired == Action.ABRIR || desired == Action.MOVER) {
            // We were trying to reach a target. If an opponent beats us to it,
            // abandon and pick something else.
            GridPos me = GridPos.of(state.meuEstado().x(), state.meuEstado().y());
            List<GridPos> opTiles = snap.robots().values().stream()
                    .map(o -> GridPos.of(o.x(), o.y())).toList();
            // Score each salient resource: lose if any opponent asserts dominance.
            for (var r : snap.resources()) {
                if (r.isChest()) {
                    GridPos chest = GridPos.of(r.x(), r.y());
                    Optional<GridPos> blocker = opponents.anyOpponentCloserThanMe(me, chest, opTiles);
                    if (blocker.isPresent()) {
                        return Action.AVANCAR; // give up and keep moving
                    }
                }
            }
        }
        return desired;
    }
}
