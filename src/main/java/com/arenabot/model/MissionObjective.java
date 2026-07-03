package com.arenabot.model;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Macro-mission set by the Tier-1 Commander-Strategist (per
 * {@code context/PROMPT.txt}) and read every tick by the Tier-2 Reflex Engine.
 *
 * <p>Held in an {@link AtomicReference} so updates from the slow LLM loop never
 * tear against reads on the tick thread. {@link #toAction()} gives the tick
 * engine a simple "what to do next" answer without parsing JSON on the hot path.
 */
public final class MissionObjective {

    public enum Goal {
        EXPLORE,
        GOTO_CHEST,
        GOTO_ENERGY,
        RECHARGE,
        RETREAT,
        UNLOCK_VAULT,
        HOLD,
        FUZZ
    }

    private final Goal goal;
    private final double targetX;
    private final double targetY;
    private final String rationale;
    private final long setAtMs;

    public MissionObjective(Goal goal, double targetX, double targetY, String rationale) {
        this.goal = Objects.requireNonNull(goal);
        this.targetX = targetX;
        this.targetY = targetY;
        this.rationale = rationale == null ? "" : rationale;
        this.setAtMs = System.currentTimeMillis();
    }

    public static MissionObjective explore() {
        return new MissionObjective(Goal.EXPLORE, Double.NaN, Double.NaN, "explore surroundings");
    }

    public static MissionObjective hold(String reason) {
        return new MissionObjective(Goal.HOLD, Double.NaN, Double.NaN, reason);
    }

    public Goal goal() { return goal; }
    public double targetX() { return targetX; }
    public double targetY() { return targetY; }
    public String rationale() { return rationale; }
    public long setAtMs() { return setAtMs; }

    public boolean hasTarget() {
        return !Double.isNaN(targetX) && !Double.isNaN(targetY);
    }

    @Override
    public String toString() {
        return "MissionObjective{" + goal + " target=(" + targetX + "," + targetY + ") rationale='" + rationale + "'}";
    }

    /** Holder for an opinionated default + safe atomic swap. */
    public static final class Board {
        private final AtomicReference<MissionObjective> ref = new AtomicReference<>(hold("init"));

        public MissionObjective current() { return ref.get(); }
        public void set(MissionObjective next) {
            if (next != null) ref.set(next);
        }
    }
}
