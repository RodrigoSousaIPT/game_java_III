package com.arenabot.ollama;

import com.arenabot.config.AppConfig;
import com.arenabot.config.ModelConfig;

/**
 * Identifies which of the four swappable LLM roles a request targets.
 * Each role corresponds to a key in {@code AppConfig.models()}; per
 * {@code context/ais_used.txt} these must be easy to change in the
 * config file near the launcher.
 */
public enum LlmRole {
    CHEST("chest"),
    EMBEDDING("embedding"),
    BOT_MOVE("bot_move"),
    COMMANDER("commander");

    private final String configKey;

    LlmRole(String configKey) {
        this.configKey = configKey;
    }

    public String configKey() { return configKey; }

    public ModelConfig resolveFor(AppConfig cfg) {
        ModelConfig mc = cfg.models().get(configKey);
        if (mc == null) {
            throw new IllegalStateException("No model configured for role " + name() + " (key="
                    + configKey + "). Update config/config.json.");
        }
        return mc;
    }
}
