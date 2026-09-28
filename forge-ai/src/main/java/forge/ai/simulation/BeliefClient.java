package forge.ai.simulation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Client of the opponent-hand belief service (mtgx {@code tools/ml/foundation/belief_serve.py},
 * {@code POST /v1/forge/belief}; look-ahead option {@code belief=human}, lane belief-sampling-0928). One request per
 * searched decision: the seat's ForgeState, the opponent's hidden hand size and the observation-only pool (unique
 * names). The reply gives one log-weight per pool name, in request order.
 *
 * <p>Failures (connection, timeout, HTTP status, schema, length, a non-finite weight) return {@code null}; the caller
 * samples that decision's worlds uniformly over the same pool and counts the fallback. The running digest folds the
 * text of every accepted {@code logw} array, so the replay audit also covers the service's answers.
 */
final class BeliefClient {
    static final String REQUEST_SCHEMA = "mtgx-belief-request/1";
    static final String RESPONSE_SCHEMA = "mtgx-belief-response/1";

    private final URI uri;
    private final Duration timeout;
    private final HttpClient http;
    private final MessageDigest digest;
    String lastError = null;
    String checkpointSha256 = null;
    long unknownCards = 0;

    BeliefClient(String baseUrl, int timeoutMs) {
        this.uri = URI.create(base(baseUrl) + "/v1/forge/belief");
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** @return one log-weight per pool name, or null on any failure ({@link #lastError} says why). */
    double[] logWeights(JsonObject request, int nPool) {
        lastError = null;
        final String body;
        try {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                lastError = "http " + resp.statusCode();
                return null;
            }
            body = resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "interrupted";
            return null;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return null;
        }
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (!o.has("schema") || !RESPONSE_SCHEMA.equals(o.get("schema").getAsString())) {
                lastError = "schema";
                return null;
            }
            JsonElement le = o.get("logw");
            if (le == null || !le.isJsonArray() || le.getAsJsonArray().size() != nPool) {
                lastError = "logw length " + (le == null || !le.isJsonArray() ? -1 : le.getAsJsonArray().size()) + " != " + nPool;
                return null;
            }
            JsonArray a = le.getAsJsonArray();
            double[] out = new double[nPool];
            for (int i = 0; i < nPool; i++) {
                double x = a.get(i).getAsDouble();
                if (!Double.isFinite(x)) {
                    lastError = "logw " + x + " at " + i;
                    return null;
                }
                out[i] = x;
            }
            JsonElement m = o.get("model");
            if (m != null && m.isJsonObject() && m.getAsJsonObject().has("checkpointSha256")) {
                checkpointSha256 = m.getAsJsonObject().get("checkpointSha256").getAsString();
            }
            JsonElement u = o.get("unknownCards");
            if (u != null && u.isJsonArray()) {
                unknownCards += u.getAsJsonArray().size();
            }
            digest.update(a.toString().getBytes(StandardCharsets.UTF_8));
            return out;
        } catch (Exception e) {
            lastError = "parse: " + e.getMessage();
            return null;
        }
    }

    /** Hex digest of every accepted response's logw array so far. */
    String digestHex() {
        try {
            return BeliefSampler.hex(((MessageDigest) digest.clone()).digest());
        } catch (CloneNotSupportedException e) {
            return "?";
        }
    }

    private static final Map<String, String> CHECKED = new ConcurrentHashMap<>();

    /**
     * The pin check, once per JVM and service: GET {@code /v1/health} and require its {@code checkpointSha256} to equal
     * {@code expected}. Throws {@link IllegalStateException} on a missing pin, an unreachable service or a mismatch.
     */
    static String checkHealth(String baseUrl, String expected, int timeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("belief=human needs beliefUrl");
        }
        if (expected == null || expected.isBlank()) {
            throw new IllegalStateException("belief=human needs beliefCheckpointSha256 (the pinned hand-model checkpoint)");
        }
        final String b = base(baseUrl);
        synchronized (CHECKED) {
            final String seen = CHECKED.get(b);
            if (seen != null) {
                if (!seen.equals(expected)) {
                    throw new IllegalStateException("belief service " + b + " serves checkpoint " + seen + ", pinned " + expected);
                }
                return seen;
            }
            final Duration t = Duration.ofMillis(Math.max(timeoutMs, 5000));
            final String got;
            try {
                HttpClient h = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(t).build();
                HttpResponse<String> r = h.send(HttpRequest.newBuilder(URI.create(b + "/v1/health")).timeout(t).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() != 200) {
                    throw new IllegalStateException("belief service " + b + " health: http " + r.statusCode());
                }
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                got = o.has("checkpointSha256") ? o.get("checkpointSha256").getAsString() : null;
            } catch (IllegalStateException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("belief service " + b + " health: interrupted", e);
            } catch (Exception e) {
                throw new IllegalStateException("belief service " + b + " health: " + e, e);
            }
            if (!expected.equals(got)) {
                throw new IllegalStateException("belief service " + b + " serves checkpoint " + got + ", pinned " + expected);
            }
            CHECKED.put(b, got);
            return got;
        }
    }
}
