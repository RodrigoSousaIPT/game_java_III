package com.arenabot.brain;

import com.arenabot.ollama.LlmRole;
import com.arenabot.ollama.OllamaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Tier-3 brain. Triggered only when standing on a chest tile. Uses a small
 * extractive model ({@link LlmRole#CHEST}, low temperature 0.1) so it stays
 * deterministic against the 15-section manual prompts.
 *
 * <p>Section retrieval is embedding-based: at prime time every manual section
 * is embedded with the {@link LlmRole#EMBEDDING} model ({@code /api/embed});
 * at solve time the vault prompt is embedded and the best section is picked
 * by cosine similarity ({@link VectorMath}). If Ollama embeddings are
 * unavailable the retrieval falls back to the lexical-overlap heuristic.
 *
 * <p>The returned record keeps the telemetry contract from ARENA_API.md §5:
 * the admin dashboard receives the raw retrieved chunk and the LLM raw
 * answer string for auditing, not just the {@code code}.
 */
public final class ChestSpecialist {

    private static final Logger LOG = LoggerFactory.getLogger(ChestSpecialist.class);

    private final OllamaClient ollama;
    private volatile List<ManualEmbedder.ManualSection> sections = List.of();
    /** One vector per section, same order as {@link #sections}; empty until primed. */
    private volatile float[][] sectionVectors = new float[0][];

    public ChestSpecialist(OllamaClient ollama, ManualEmbedder embedder) {
        this.ollama = ollama;
        // ManualEmbedder is stateless (static split); parameter kept for wiring compat.
    }

    public void primeSections(String rawManual) {
        List<ManualEmbedder.ManualSection> split = ManualEmbedder.split(rawManual);
        this.sections = split;
        this.sectionVectors = new float[0][];
        if (split.isEmpty()) return;
        List<String> texts = split.stream()
                .map(ManualEmbedder.ManualSection::textForEmbedding)
                .toList();
        ollama.embedAsync(texts).thenAccept(vectors -> {
            if (vectors.size() == split.size()) {
                this.sectionVectors = vectors.toArray(new float[0][]);
                LOG.info("manual primed: {} sections embedded (dim={})",
                        vectors.size(), vectors.get(0).length);
            } else {
                LOG.warn("manual embedding failed (got {}/{} vectors) — lexical fallback stays active",
                        vectors.size(), split.size());
            }
        });
    }

    public CompletableFuture<UnlockPlan> solveVault(String vaultPrompt) {
        return retrieveSection(vaultPrompt).thenCompose(best -> {
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
        });
    }

    /** Cosine search over the embedded sections; lexical overlap as fallback. */
    private CompletableFuture<ManualEmbedder.ManualSection> retrieveSection(String vaultPrompt) {
        List<ManualEmbedder.ManualSection> secs = sections;
        float[][] vectors = sectionVectors;
        if (secs.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new ManualEmbedder.ManualSection(-1, "NONE", ""));
        }
        if (vectors.length != secs.size()) {
            return CompletableFuture.completedFuture(lexicalBest(secs, vaultPrompt));
        }
        return ollama.embedAsync(List.of(vaultPrompt)).thenApply(qv -> {
            if (qv.isEmpty() || qv.get(0).length != vectors[0].length) {
                return lexicalBest(secs, vaultPrompt);
            }
            int[] top = VectorMath.topK(qv.get(0), vectors, 1);
            ManualEmbedder.ManualSection hit = secs.get(top[0]);
            LOG.info("RAG hit for vault prompt: '{}'", hit.header());
            return hit;
        });
    }

    private static ManualEmbedder.ManualSection lexicalBest(
            List<ManualEmbedder.ManualSection> secs, String vaultPrompt) {
        return secs.stream()
                .max((a, b) -> Integer.compare(
                        lexicalOverlap(a.body().toLowerCase(), vaultPrompt.toLowerCase()),
                        lexicalOverlap(b.body().toLowerCase(), vaultPrompt.toLowerCase())))
                .orElse(new ManualEmbedder.ManualSection(-1, "NONE", ""));
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
