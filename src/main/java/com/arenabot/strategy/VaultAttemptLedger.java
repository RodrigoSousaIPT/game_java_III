package com.arenabot.strategy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks how many unlock attempts have been made per vault. Per Phase 3
 * spec, each vault gets at most 2 unlock tries; if both fail we abandon
 * it for the rest of the session.
 *
 * <p>State is in-memory only — the arena itself resets when the admin
 * triggers a new {@code game_started=true}, so persisting this ledger to
 * {@link com.arenabot.memory.MapMemory} would lock out vaults in the new
 * session.
 */
public final class VaultAttemptLedger {

    public static final int MAX_ATTEMPTS_PER_VAULT = 2;

    private final Map<String, Integer> attempts = new LinkedHashMap<>();

    /** @return true if the vault still has an attempt budget left. */
    public synchronized boolean canAttempt(String vaultId) {
        int used = attempts.getOrDefault(vaultId, 0);
        return used < MAX_ATTEMPTS_PER_VAULT;
    }

    public synchronized void record(String vaultId, boolean success) {
        attempts.merge(vaultId, 1, Integer::sum);
        // Even a successful unlock consumes its slot — second attempts are
        // forbidden to avoid the admin dashboard seeing duplicate telemetry.
        if (success) attempts.merge(vaultId, MAX_ATTEMPTS_PER_VAULT, Integer::sum);
    }

    public synchronized boolean isTerminal(String vaultId) {
        return attempts.getOrDefault(vaultId, 0) >= MAX_ATTEMPTS_PER_VAULT;
    }

    public synchronized int attemptsUsed(String vaultId) {
        return attempts.getOrDefault(vaultId, 0);
    }

    public synchronized void clear(String vaultId) {
        attempts.remove(vaultId);
    }

    public synchronized int trackedVaultCount() { return attempts.size(); }
}
