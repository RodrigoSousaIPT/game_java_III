package com.arenabot.brain;

import com.arenabot.ollama.LlmRole;
import com.arenabot.ollama.OllamaClient;
import com.arenabot.ollama.OllamaResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Tier-3 brain. Triggered only when standing on a chest tile. Uses a small
 * extractive model ({@link LlmRole#CHEST}, low temperature 0.1) so it stays
 * deterministic against the 15-section manual prompts.
 *
 * <p>The returned record keeps the telemetry contract from ARENA_API.md §5:
 * the admin dashboard receives the raw retrieved chunk and the LLM raw
 * answer string for auditing, not just the {@code code}.
 */
public final class ChestSpecialist {

    private final OllamaClient ollama;
    private final ManualEmbedder embedder;
    private List<ManualEmbedder.ManualSection> sections = List.of();

    public ChestSpecialist(OllamaClient ollama, ManualEmbedder embedder) {
        this.ollama = ollama;
        this.embedder = embedder;
    }

    public void primeSections(String rawManual) {
        this.sections = ManualEmbedder.split(rawManual);
    }

    public CompletableFuture<UnlockPlan> solveVault(String vaultPrompt) {
        // Without functioning local embeddings in this skeleton, fall back to
        // a lexical heuristic: pick the section whose header most resembles
        // the vault prompt. This is good enough for the bot's first unlock.
        ManualEmbedder.ManualSection best = sections.stream()
                .max((a, b) -> Integer.compare(
                        lexicalOverlap(a.body().toLowerCase(), vaultPrompt.toLowerCase()),
                        lexicalOverlap(b.body().toLowerCase(), vaultPrompt.toLowerCase())))
                .orElse(new ManualEmbedder.ManualSection(-1, "NONE", ""));
        String prompt = "PROMPT(system: low-temp extractive)\n"
                + "VAULT QUESTION: " + vaultPrompt + "\n\n"
                + "MANUAL CHUNK (" + best.header() + "):\n" + best.body() + "\n\n"
                + "INSTRUCTION: Reply ONLY with the unlock code token (e.g. SIGMA-3).\n";
        return ollama.generateAsync(LlmRole.CHEST, prompt).thenApply(resp -> {
            String code = resp.response == null ? "" : resp.response.trim().split("\\s+")[0];
            return new UnlockPlan(
                    code,
                    best.header().isEmpty() ? "NONE" : best.textForEmbedding(),
                    prompt + "\nLLM_RAW: " + resp.response);
        });
    }

    private static int lexicalOverlap(String body, String prompt) {
        int score = 0;
        for (String w : prompt.split("\\W+")) {
            if (w.length() >= 4 && body.contains(w)) score++;
        }
        return score;
    }

    public record UnlockPlan(String code, String ragChunk, String llmRaw) {}
}
