package com.arenabot.api;

import com.arenabot.model.GameState;
import com.arenabot.model.MeuEstado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Thin wrapper around the FastAPI arena endpoints defined in
 * {@code context/ARENA_API.md}.
 *
 * <ul>
 *   <li>{@code POST /arena/{room}/register?robot_id=...} — spawn a new robot.</li>
 *   <li>{@code GET  /arena/{room}/perceive/{robot_id}} — snapshot JSON view.</li>
 *   <li>{@code POST /arena/action} with body {@code {room_id, robot_id, action}}.</li>
 *   <li>{@code POST /arena/{room}/unlock?robot_id=...&code=...&rag_chunk=...&llm_raw=...}
 *       — query-string body, no JSON.</li>
 *   <li>{@code GET  /arena/{room}/download_manual} — 15-section RAG corpus.</li>
 * </ul>
 *
 * <p>Failures come in two shapes per ARENA_API.md §7 — non-200 transport
 * errors AND 200-with-{status:erro} semantics. {@link #sendJson(HttpRequest, Function)}
 * throws on non-2xx; the occupancy rejection must be caught at the registration
 * site and routed through {@link #coolDownBackoff()} / {@link #resetBackoff()}.
 */
public final class ArenaApiClient {

    private static final Logger LOG = LoggerFactory.getLogger(ArenaApiClient.class);

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    private final AtomicLong backoffMs = new AtomicLong(2000L);

    public ArenaApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public record RegisterResult(String status, MeuEstado estado) {}

    public RegisterResult register(String roomId, String robotId) throws IOException {
        String url = baseUrl + "/arena/" + roomId + "/register?robot_id=" + enc(robotId);
        HttpRequest req = jsonRequest(url)
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
        return sendJson(req, root -> {
            String status = textOr(root, "status", "");
            MeuEstado s = root.has("o_meu_estado")
                    ? MeuEstado.fromJson(root.get("o_meu_estado"))
                    : new MeuEstado(
                        numOr(root, "x", 0), numOr(root, "y", 0), numOr(root, "z", 0),
                        intOr(root, "energia", 0), textOr(root, "cor", ""));
            return new RegisterResult(status, s);
        });
    }

    public GameState perceive(String roomId, String robotId) throws IOException {
        String url = baseUrl + "/arena/" + roomId + "/perceive/" + enc(robotId);
        HttpRequest req = jsonRequest(url).GET().build();
        return sendJson(req, GameState::fromJson);
    }

    /**
     * See ARENA_API.md §3 and §6. The action vocabulary is still speculative —
     * the server returns the same {@code bloqueado} envelope until the admin
     * starts the game, regardless of payload.
     *
     * @return true if the server explicitly rejected on occupancy grounds.
     */
    public boolean isOccupancyRejection(JsonNode responseBody) {
        return responseBody != null
                && responseBody.has("status")
                && "erro".equals(responseBody.get("status").asText())
                && responseBody.has("message")
                && responseBody.get("message").asText().contains("Aguarde");
    }

    public JsonNode action(String roomId, String robotId, String action) throws IOException {
        String payload = json.createObjectNode()
                .put("room_id", roomId)
                .put("robot_id", robotId)
                .put("action", action == null ? "" : action)
                .toString();
        HttpRequest req = jsonRequest(baseUrl + "/arena/action")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        return sendJson(req, Function.identity());
    }

    public JsonNode unlock(String roomId, String robotId, String code,
                            String ragChunk, String llmRaw) throws IOException {
        StringBuilder sb = new StringBuilder(baseUrl)
                .append("/arena/").append(roomId).append("/unlock?robot_id=").append(enc(robotId))
                .append("&code=").append(enc(code == null ? "" : code));
        if (ragChunk != null && !ragChunk.isEmpty()) sb.append("&rag_chunk=").append(enc(ragChunk));
        if (llmRaw != null && !llmRaw.isEmpty()) sb.append("&llm_raw=").append(enc(llmRaw));
        HttpRequest req = jsonRequest(sb.toString())
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
        return sendJson(req, Function.identity());
    }

    public String downloadManual(String roomId) throws IOException {
        HttpRequest req = jsonRequest(baseUrl + "/arena/" + roomId + "/download_manual").GET().build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + req.uri());
            }
            return resp.body();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling " + req.uri());
        }
    }

    public void coolDownBackoff() {
        long delay = backoffMs.get();
        LOG.warn("backing off room registration for {} ms (jittered)", delay);
        try {
            long jittered = delay / 2 + ThreadLocalRandom.current().nextLong(delay);
            Thread.sleep(jittered);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        backoffMs.set(Math.min(delay * 2, 60_000L));
    }

    public void resetBackoff() { backoffMs.set(2000L); }

    /* ---------- low-level helpers ---------- */

    private HttpRequest.Builder jsonRequest(String url) {
        // Content-Type is mandatory: FastAPI refuses to parse a JSON body
        // without it and answers 422 before reaching the game logic (seen
        // live on POST /arena/action).
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
    }

    private <T> T sendJson(HttpRequest req, Function<JsonNode, T> mapper) throws IOException {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling " + req.uri());
        }
        if (resp.statusCode() / 100 != 2) {
            // Keep the server's validation detail — a bare status code hid
            // the 422 root cause for far too long.
            throw new IOException("HTTP " + resp.statusCode() + " for " + req.uri()
                    + " body[:300]=" + truncate(resp.body(), 300));
        }
        JsonNode body = resp.body() == null || resp.body().isEmpty()
                ? json.createObjectNode() : json.readTree(resp.body());
        return mapper.apply(body);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "…");
    }

    private static String textOr(JsonNode n, String f, String d) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || !v.isTextual() ? d : v.asText();
    }

    private static double numOr(JsonNode n, String f, double d) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || !v.isNumber() ? d : v.asDouble();
    }

    private static int intOr(JsonNode n, String f, int d) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || !v.canConvertToInt() ? d : v.asInt();
    }

    public HttpClient rawClient() { return http; }
}
