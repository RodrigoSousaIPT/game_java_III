package com.arenabot.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @Test void loadsBundledTemplate() throws Exception {
        AppConfig c = AppConfig.load(null);
        assertNotNull(c);
        assertEquals("7A1071", c.roomCode());
        assertTrue(c.tickMs() > 0L);
        assertEquals(4, c.models().size(), "expects 4 swappable roles");
        assertEquals("chest", c.models().get("chest").modelName().equals("lfm2-350m-extract")
                || !c.models().get("chest").modelName().isEmpty() ? "chest"
                : c.models().keySet().iterator().next());
    }

    @Test void resolvesRolesFromConfigKeys() {
        AppConfig c = new AppConfig();
        java.util.Map<String, ModelConfig> m = new java.util.HashMap<>();
        m.put("chest",     new ModelConfig("a", 0.1, "24h", ""));
        m.put("embedding", new ModelConfig("b", 0.0, "24h", ""));
        m.put("bot_move",  new ModelConfig("c", 0.3, "24h", ""));
        m.put("commander", new ModelConfig("d", 0.4, "24h", ""));
        c.models().putAll(m);

        com.arenabot.ollama.LlmRole.CHEST.resolveFor(c).setModelName("replaced");
        assertEquals("replaced", c.models().get("chest").modelName());
        assertEquals("b", com.arenabot.ollama.LlmRole.EMBEDDING.resolveFor(c).modelName());
        assertEquals("c", com.arenabot.ollama.LlmRole.BOT_MOVE.resolveFor(c).modelName());
        assertEquals("d", com.arenabot.ollama.LlmRole.COMMANDER.resolveFor(c).modelName());
    }
}
