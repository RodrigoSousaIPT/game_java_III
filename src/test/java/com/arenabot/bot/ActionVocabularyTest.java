package com.arenabot.bot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActionVocabularyTest {

    @Test void primaryTokensAreLowercasePortuguese() {
        assertEquals("mover", ActionVocabulary.primary(Action.MOVER));
        assertEquals("avancar", ActionVocabulary.primary(Action.AVANCAR));
        assertEquals("girar_esq", ActionVocabulary.primary(Action.GIRAR_ESQ));
        assertEquals("girar_dir", ActionVocabulary.primary(Action.GIRAR_DIR));
        assertEquals("abrir", ActionVocabulary.primary(Action.ABRIR));
        assertEquals("recolher", ActionVocabulary.primary(Action.RECOLHER));
        assertEquals("usar", ActionVocabulary.primary(Action.USAR));
    }

    @Test void autoFuzzPassthrough() {
        List<String> in = List.of("alpha", "beta");
        assertEquals(in, ActionVocabulary.autoFuzz(in));
        assertEquals(java.util.List.of(), ActionVocabulary.autoFuzz(null));
    }
}
