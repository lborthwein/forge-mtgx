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
 * Client of the expert-iteration ranker service (lane ei-1004; mtgx {@code tools/ml/ei/ei_serve.py}). One call per
 * ranked decision: {@code POST /v1/ei/rank} with the seat's ForgeState, the decision context and the search's candidate
 * set (candidate 0 = Forge AI's answer); the answer is P(the search would choose c) per candidate.
 *
 * <p>Failures (connection, timeout, HTTP status, schema, length, a p outside [0, 1]) return {@code null}; the seat then
 * plays Forge AI's answer and the failure is counted. The running digest folds the text of every accepted response's
 * {@code choice} array, so a replay audit also covers the ranker's answers. Not final: tests substitute {@link #post}.
 */
public class RankerClient {

    public static final String REQUEST_SCHEMA = "mtgx-ei-rank-request/1";
    public static final String RESPONSE_SCHEMA = "mtgx-ei-rank-response/1";

    private final String base;
    private final Duration timeout;
    private final HttpClient http;
    private final MessageDigest digest;
    String lastError = null;
    long unknownCards = 0;

    public RankerClient(String baseUrl, int timeoutMs) {
        this.base = base(baseUrl);
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = baseUrl == null ? null
                : HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    protected String post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("http " + resp.statusCode());
        }
        return resp.body();
    }

    /** @return P(choice) per candidate, in request order; null on any failure ({@link #lastError}). */
    double[] rank(JsonObject request, int n) {
        lastError = null;
        final String body;
        try {
            body = post("/v1/ei/rank", request.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "interrupted";
            return null;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return null;
        }
        try {
            final JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (!o.has("schema") || !RESPONSE_SCHEMA.equals(o.get("schema").getAsString())) {
                lastError = "schema";
                return null;
            }
            final JsonElement ce = o.get("choice");
            if (ce == null || !ce.isJsonArray() || ce.getAsJsonArray().size() != n) {
                lastError = "choice length";
                return null;
            }
            final JsonArray cs = ce.getAsJsonArray();
            final double[] p = new double[n];
            for (int i = 0; i < n; i++) {
                final double x = cs.get(i).getAsDouble();
                if (!Double.isFinite(x) || x < 0 || x > 1) {
                    lastError = "p " + x;
                    return null;
                }
                p[i] = x;
            }
            final JsonElement u = o.get("unknownCards");
            if (u != null && u.isJsonArray()) {
                unknownCards += u.getAsJsonArray().size();
            }
            digest.update(cs.toString().getBytes(StandardCharsets.UTF_8));
            return p;
        } catch (Exception e) {
            lastError = "parse: " + e.getMessage();
            return null;
        }
    }

    String digestHex() {
        try {
            final byte[] d = ((MessageDigest) digest.clone()).digest();
            final StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (CloneNotSupportedException e) {
            return "?";
        }
    }

    private static final Map<String, String> CHECKED = new ConcurrentHashMap<>();

    /** The checkpoint pin, once per JVM and service (as {@link PolicyClient#checkHealth}). */
    public static String checkHealth(String baseUrl, String expected, int timeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("a ranker seat needs ranker.url");
        }
        if (expected == null || expected.isBlank()) {
            throw new IllegalStateException("a ranker seat needs ranker.checkpointSha256 (the pinned ranker checkpoint)");
        }
        final String b = base(baseUrl);
        final String seen = CHECKED.get(b);
        if (seen != null) {
            if (!seen.equals(expected)) {
                throw new IllegalStateException("ranker service " + b + " serves checkpoint " + seen + ", pinned " + expected);
            }
            return seen;
        }
        synchronized (CHECKED) {
            final Duration t = Duration.ofMillis(Math.max(timeoutMs, 5000));
            final String got;
            try {
                HttpClient h = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(t).build();
                HttpResponse<String> r = h.send(HttpRequest.newBuilder(URI.create(b + "/v1/health")).timeout(t).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() != 200) {
                    throw new IllegalStateException("ranker service " + b + " health: http " + r.statusCode());
                }
                final JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                got = o.has("checkpointSha256") ? o.get("checkpointSha256").getAsString() : null;
            } catch (IllegalStateException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ranker service " + b + " health: interrupted", e);
            } catch (Exception e) {
                throw new IllegalStateException("ranker service " + b + " health: " + e, e);
            }
            if (!expected.equals(got)) {
                throw new IllegalStateException("ranker service " + b + " serves checkpoint " + got + ", pinned " + expected);
            }
            CHECKED.put(b, got);
            return got;
        }
    }
}
