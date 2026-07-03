package com.arenabot.ollama;

import com.arenabot.config.AppConfig;
import com.arenabot.config.ModelConfig;
import com.arenabot.resilience.PromptRingBuffer;
import com.arenabot.ui.TelemetryBus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Thin async Ollama client. All four {@link LlmRole}s pull a different
 * {@link ModelConfig} from {@link AppConfig}, so swapping a model (e.g.
 * gemma4-e2b for lfm2.5) is a config edit, not a code change.
 *
 * <p>Text roles use {@code POST /api/generate}; the {@link LlmRole#EMBEDDING}
 * role uses {@code POST /api/embed} via {@link #embedAsync(List)}.
 *
 * <p>Calls are submitted on an internal {@link java.net.http.HttpClient};
 * callers chain via {@link CompletableFuture} and never block the tick loop.
 */
public final class OllamaClient {

    private static final Logger LOG = LoggerFactory.getLogger(OllamaClient.class);

    /** Generous per-request cap: first token after a cold model load on an
     *  RTX 5060 can take tens of seconds for the 3.1GB commander model. With
     *  keep_alive=-1m the model stays resident, so this only bites once. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final AppConfig config;
    private final HttpClient http;
    private final PromptRingBuffer prompts;
    private volatile TelemetryBus bus; // optional, may stay null in headless/tests

    public OllamaClient(AppConfig config) {
        this(config, new PromptRingBuffer());
    }

    public OllamaClient(AppConfig config, PromptRingBuffer prompts) {
        this.config = config;
        this.prompts = prompts == null ? new PromptRingBuffer() : prompts;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public PromptRingBuffer prompts() { return prompts; }

    /** Wire the Swing telemetry bus so the dashboard shows live LLM stats. */
    public void attachTelemetry(TelemetryBus bus) { this.bus = bus; }

    public CompletableFuture<OllamaResponse> generateAsync(LlmRole role, String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            long t0 = System.currentTimeMillis();
            try {
                ModelConfig mc = role.resolveFor(config);
                OllamaRequest req = OllamaRequest.of(
                        mc.modelName(), prompt, mc.temperature());
                req.numPredict = mc.numPredict();
                req.keepAlive = mc.keepAlive() == null ? "-1m" : mc.keepAlive();
                HttpRequest httpReq = HttpRequest.newBuilder()
                        .uri(URI.create(config.ollamaBaseUrl() + "/api/generate"))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(req.toJson(), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
                long elapsed = System.currentTimeMillis() - t0;
                if (resp.statusCode() / 100 != 2) {
                    LOG.warn("[{}] HTTP {} in {}ms — body[:200]={}", role,
                            resp.statusCode(), elapsed, truncate(resp.body(), 200));
                    prompts.push(prompt, role.name(), "HTTP " + resp.statusCode());
                    publish(role.name(), 0, 0, elapsed, "HTTP " + resp.statusCode());
                    return new OllamaResponse();
                }
                OllamaResponse r = OllamaResponse.fromJson(resp.body());
                LOG.info("[{}] ok model={} tokens(prompt={},eval={}) elapsed={}ms",
                        role, r.model, r.promptEvalCount, r.evalCount, elapsed);
                prompts.push(prompt, role.name(), "ok · model=" + r.model);
                publish(role.name(), (int) r.promptEvalCount, (int) r.evalCount, elapsed,
                        "ok · " + r.model + " · " + truncate(r.response, 60));
                return r;
            } catch (IOException io) {
                LOG.warn("[{}] IO error: {}", role, io.getMessage());
                prompts.push(prompt, role.name(), "IO error: " + io.getMessage());
                publish(role.name(), 0, 0, System.currentTimeMillis() - t0,
                        "IO error: " + truncate(io.getMessage(), 60));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return new OllamaResponse();
        });
    }

    /**
     * Embeds each input string with the {@link LlmRole#EMBEDDING} model via
     * {@code POST /api/embed}. Returns one vector per input, or an empty list
     * when Ollama is unreachable — callers must fall back gracefully.
     */
    public CompletableFuture<List<float[]>> embedAsync(List<String> inputs) {
        return CompletableFuture.supplyAsync(() -> {
            long t0 = System.currentTimeMillis();
            try {
                ModelConfig mc = LlmRole.EMBEDDING.resolveFor(config);
                ObjectMapper m = new ObjectMapper();
                ObjectNode n = m.createObjectNode();
                n.put("model", mc.modelName());
                ArrayNode arr = n.putArray("input");
                for (String s : inputs) arr.add(s);
                n.put("keep_alive", mc.keepAlive() == null ? "-1m" : mc.keepAlive());
                HttpRequest httpReq = HttpRequest.newBuilder()
                        .uri(URI.create(config.ollamaBaseUrl() + "/api/embed"))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(n.toString(), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
                long elapsed = System.currentTimeMillis() - t0;
                if (resp.statusCode() / 100 != 2) {
                    LOG.warn("[EMBEDDING] HTTP {} in {}ms — body[:200]={}",
                            resp.statusCode(), elapsed, truncate(resp.body(), 200));
                    return List.<float[]>of();
                }
                List<float[]> out = parseEmbeddings(m.readTree(resp.body()));
                LOG.info("[EMBEDDING] ok model={} vectors={} elapsed={}ms",
                        mc.modelName(), out.size(), elapsed);
                publish("EMBEDDING", inputs.size(), out.size(), elapsed, "ok · " + mc.modelName());
                return out;
            } catch (IOException io) {
                LOG.warn("[EMBEDDING] IO error: {}", io.getMessage());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return List.<float[]>of();
        });
    }

    private static List<float[]> parseEmbeddings(JsonNode root) {
        JsonNode arr = root == null ? null : root.get("embeddings");
        if (arr == null || !arr.isArray()) return List.of();
        java.util.ArrayList<float[]> out = new java.util.ArrayList<>(arr.size());
        for (JsonNode vecNode : arr) {
            if (!vecNode.isArray()) continue;
            float[] v = new float[vecNode.size()];
            for (int i = 0; i < v.length; i++) v[i] = (float) vecNode.get(i).asDouble();
            out.add(v);
        }
        return out;
    }

    /**
     * Fires a tiny prompt at every text role plus a one-string embed so all
     * models are loaded into VRAM up-front (keep_alive=-1m keeps them there).
     * Non-blocking; failures only log.
     */
    public void warmUpAll() {
        for (LlmRole role : new LlmRole[]{ LlmRole.CHEST, LlmRole.BOT_MOVE, LlmRole.COMMANDER }) {
            generateAsync(role, "ok").thenAccept(r ->
                    LOG.info("warm-up {} done (model={})", role, r.model));
        }
        embedAsync(List.of("ok")).thenAccept(v ->
                LOG.info("warm-up EMBEDDING done (vectors={})", v.size()));
    }

    private void publish(String role, int tokensIn, int tokensOut, long elapsedMs, String text) {
        TelemetryBus b = bus;
        if (b != null) b.publish(new TelemetryBus.Sample(role, tokensIn, tokensOut, elapsedMs, text));
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "…");
    }
}
