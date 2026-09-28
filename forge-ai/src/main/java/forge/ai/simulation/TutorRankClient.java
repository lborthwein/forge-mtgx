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
 * Client of the tutor-target ranker (mtgx {@code tools/ml/tutorrank/serve_tutor.py}, {@code POST /v1/tutor/rank};
 * look-ahead option {@code tutorRank}, lane tutor-ranking-0928). One request per searched library-search choice
 * carries the search's source card, the destination, the distinct candidate names, the deck and a small state; the
 * reply's {@code scores} rank the candidates (higher = more likely a human's pick).
 *
 * <p>Failures (connection, timeout, HTTP status, schema, length, a non-finite score) return {@code null}; the caller
 * keeps Forge AI's own pick for that choice and counts the failure. The running digest folds the text of every
 * accepted response's {@code scores} array, so a replay audit also covers the ranker's answers.
 */
final class TutorRankClient {

    static final String SCHEMA = "tutor-rank/1";

    private final URI uri;
    private final Duration timeout;
    private final HttpClient http;
    private final MessageDigest digest;
    String lastError = null;
    String checkpointSha256 = null;
    long unknownCards = 0;

    TutorRankClient(String baseUrl, int timeoutMs) {
        this.uri = URI.create(base(baseUrl) + "/v1/tutor/rank");
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

    /** @return one score per candidate in request order; null on any failure ({@link #lastError} says why). */
    double[] rank(JsonObject request, int nCandidates) {
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
            if (!o.has("schema") || !SCHEMA.equals(o.get("schema").getAsString())) {
                lastError = "schema";
                return null;
            }
            JsonElement se = o.get("scores");
            if (se == null || !se.isJsonArray() || se.getAsJsonArray().size() != nCandidates) {
                lastError = "scores length " + (se == null || !se.isJsonArray() ? -1 : se.getAsJsonArray().size()) + " != " + nCandidates;
                return null;
            }
            JsonArray sa = se.getAsJsonArray();
            double[] out = new double[nCandidates];
            for (int i = 0; i < nCandidates; i++) {
                double x = sa.get(i).getAsDouble();
                if (!Double.isFinite(x)) {
                    lastError = "score " + x + " at " + i;
                    return null;
                }
                out[i] = x;
            }
            if (o.has("checkpointSha256")) {
                checkpointSha256 = o.get("checkpointSha256").getAsString();
            }
            JsonElement u = o.get("unknownCards");
            if (u != null && u.isJsonPrimitive()) {
                unknownCards += u.getAsLong();
            }
            digest.update(sa.toString().getBytes(StandardCharsets.UTF_8));
            return out;
        } catch (Exception e) {
            lastError = "parse: " + e.getMessage();
            return null;
        }
    }

    /** Hex digest of every accepted response's scores so far (clones the running state). */
    String digestHex() {
        try {
            byte[] d = ((MessageDigest) digest.clone()).digest();
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (CloneNotSupportedException e) {
            return "?";
        }
    }

    /** Services whose {@code /v1/health} checkpoint was checked in this JVM: base URL -> checkpointSha256. */
    private static final Map<String, String> CHECKED = new ConcurrentHashMap<>();

    /**
     * The pin, once per JVM and service: GET {@code /v1/health}; its {@code checkpointSha256} must equal
     * {@code expected}. Throws {@link IllegalStateException} (the runner refuses to start) on a missing pin, an
     * unreachable service or a mismatch.
     */
    static String checkHealth(String baseUrl, String expected, int timeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("tutorRank/tutorShadow need tutorUrl");
        }
        if (expected == null || expected.isBlank()) {
            throw new IllegalStateException("tutorRank/tutorShadow need tutorCheckpointSha256 (the pinned ranker checkpoint)");
        }
        final String b = base(baseUrl);
        final String seen = CHECKED.get(b);
        if (seen != null) {
            if (!seen.equals(expected)) {
                throw new IllegalStateException("tutor ranker " + b + " serves checkpoint " + seen + ", pinned " + expected);
            }
            return seen;
        }
        synchronized (CHECKED) {
            if (CHECKED.containsKey(b)) {
                return checkHealth(baseUrl, expected, timeoutMs);
            }
            final Duration t = Duration.ofMillis(Math.max(timeoutMs, 5000));
            final String got;
            try {
                HttpClient h = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(t).build();
                HttpResponse<String> r = h.send(HttpRequest.newBuilder(URI.create(b + "/v1/health")).timeout(t).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() != 200) {
                    throw new IllegalStateException("tutor ranker " + b + " health: http " + r.statusCode());
                }
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                got = o.has("checkpointSha256") ? o.get("checkpointSha256").getAsString() : null;
            } catch (IllegalStateException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("tutor ranker " + b + " health: interrupted", e);
            } catch (Exception e) {
                throw new IllegalStateException("tutor ranker " + b + " health: " + e, e);
            }
            if (!expected.equals(got)) {
                throw new IllegalStateException("tutor ranker " + b + " serves checkpoint " + got + ", pinned " + expected);
            }
            CHECKED.put(b, got);
            return got;
        }
    }
}
