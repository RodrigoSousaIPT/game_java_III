package com.arenabot.ollama;

import com.arenabot.config.AppConfig;
import com.arenabot.config.ModelConfig;
import com.arenabot.resilience.PromptRingBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Thin async Ollama client. All four {@link LlmRole}s use the same HTTP path
 * ({@code POST /api/generate}) but pull a different {@link ModelConfig} from
 * {@link AppConfig}, so swapping a model (e.g. qwen3.5 for LFM2.5) is a config
 * edit, not a code change.
 *
 * <p>Calls are submitted on an internal {@link java.net.http.HttpClient};
 * callers chain via {@link CompletableFuture} and never block the tick loop.
 */
public final class OllamaClient {

    private static final Logger LOG = LoggerFactory.getLogger(OllamaClient.class);

    private final AppConfig config;        private final HttpClient http;
    private final PromptRingBuffer prompts;

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

    public CompletableFuture<OllamaResponse> generateAsync(LlmRole role, String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            long t0 = System.currentTimeMillis();
            try {
                ModelConfig mc = role.resolveFor(config);
                OllamaRequest req = OllamaRequest.of(
                        mc.modelName(), prompt, mc.temperature());
                req.keepAlive = mc.keepAlive() == null ? "24h" : mc.keepAlive();
                HttpRequest httpReq = HttpRequest.newBuilder()
                        .uri(URI.create(config.ollamaBaseUrl() + "/api/generate"))
                        .timeout(Duration.ofSeconds(15)) // below default 700ms tick rate so the bot never freezes the loop
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(req.toJson(), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
                long elapsed = System.currentTimeMillis() - t0;
                if (resp.statusCode() / 100 != 2) {
                    LOG.warn("[{}] HTTP {} in {}ms — body[:200]={}", role,
                            resp.statusCode(), elapsed, truncate(resp.body(), 200));
                    prompts.push(prompt, role.name(), "HTTP " + resp.statusCode());
                    return new OllamaResponse();
                }
                OllamaResponse r = OllamaResponse.fromJson(resp.body());
                LOG.info("[{}] ok model={} tokens(prompt={},eval={}) elapsed={}ms",
                        role, r.model, r.promptEvalCount, r.evalCount, elapsed);
                prompts.push(prompt, role.name(), "ok · model=" + r.model);
                return r;
            } catch (IOException io) {
                LOG.warn("[{}] IO error: {}", role, io.getMessage());
                prompts.push(prompt, role.name(), "IO error: " + io.getMessage());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return new OllamaResponse();
        });
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "…");
    }
}
