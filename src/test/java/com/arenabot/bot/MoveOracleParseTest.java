package com.arenabot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MoveOracleParseTest {

    @Test void parsesEachMovementWord() {
        assertEquals(Action.AVANCAR, BotOrchestrator.parseMove("avancar").orElseThrow());
        assertEquals(Action.RECUAR, BotOrchestrator.parseMove("Recuar!").orElseThrow());
        assertEquals(Action.GIRAR_ESQ, BotOrchestrator.parseMove("girar_esq").orElseThrow());
        assertEquals(Action.GIRAR_DIR, BotOrchestrator.parseMove("girar_dir\nextra").orElseThrow());
    }

    @Test void toleratesAccentAndEnglish() {
        assertEquals(Action.AVANCAR, BotOrchestrator.parseMove("Avançar").orElseThrow());
        assertEquals(Action.GIRAR_ESQ, BotOrchestrator.parseMove("left").orElseThrow());
    }

    @Test void rejectsGarbage() {
        assertTrue(BotOrchestrator.parseMove(null).isEmpty());
        assertTrue(BotOrchestrator.parseMove("").isEmpty());
        assertTrue(BotOrchestrator.parseMove("dance").isEmpty());
    }
}
