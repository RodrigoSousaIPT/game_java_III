package com.arenabot.strategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VaultAttemptLedgerTest {

    @Test void allowsUpToTwoAttempts() {
        VaultAttemptLedger l = new VaultAttemptLedger();
        assertTrue(l.canAttempt("v1"));
        l.record("v1", false);
        assertTrue(l.canAttempt("v1"), "second attempt allowed after first failure");
        l.record("v1", false);
        assertFalse(l.canAttempt("v1"), "cap reached");
    }

    @Test void successLocksVaultFromFurtherAttempts() {
        VaultAttemptLedger l = new VaultAttemptLedger();
        l.record("v1", true);
        assertFalse(l.canAttempt("v1"), "successful unlock should not be retried");
    }

    @Test void clearResetsEntry() {
        VaultAttemptLedger l = new VaultAttemptLedger();
        l.record("v1", false);
        l.record("v1", false);
        l.clear("v1");
        assertTrue(l.canAttempt("v1"));
        assertEquals(0, l.trackedVaultCount());
    }
}
