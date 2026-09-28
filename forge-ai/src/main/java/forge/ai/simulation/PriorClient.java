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
 * Client of the 17Lands policy prior (mtgx {@code tools/ml/foundation/serve.py}, {@code POST /v1/forge/score} with
 * {@code root} + {@code candidates}; look-ahead option {@code priorExtra}, read HX). One request per qualifying
 * searched decision carries the decision's own ForgeState and Forge's full candidate list; the reply's {@code prior}
 * gives {id, p, logp} per candidate, p null where the model has no head (activate / other, or a card the root state
 * does not show).
 *
 * <p>Failures (connection, timeout, HTTP status, schema, length, an id out of order, a p outside [0, 1]) return
 * {@code null}; the caller keeps Forge's own candidate set for that decision and counts the failure. The running
 * digest folds the text of every accepted response's {@code prior} array (as {@link ModelClient} folds its value
 * array), so a replay audit also covers the prior's answers.
 */
final class PriorClient {

    private final URI uri;
    private final Duration timeout;
    private final HttpClient http;
    private final MessageDigest digest;
    String lastError = null;
    String checkpointSha256 = null;
    long unknownCards = 0;
    /** The service's own {@code ms} of the last accepted response (-1 if absent). */
    double lastServiceMs = -1;

    PriorClient(String baseUrl, int timeoutMs) {
        this.uri = URI.create(base(baseUrl) + "/v1/forge/score");
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

    /**
     * @return p per candidate in request order (an element is null where the service gives none); null on any
     *         failure ({@link #lastError} says why). Candidate {@code i} of the request must carry {@code id = i}.
     */
    Double[] prior(JsonObject request, int nCandidates) {
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
            if (!o.has("schema") || !ModelClient.RESPONSE_SCHEMA.equals(o.get("schema").getAsString())) {
                lastError = "schema";
                return null;
            }
            JsonElement pe = o.get("prior");
            if (pe == null || !pe.isJsonArray() || pe.getAsJsonArray().size() != nCandidates) {
                lastError = "prior length " + (pe == null || !pe.isJsonArray() ? -1 : pe.getAsJsonArray().size()) + " != " + nCandidates;
                return null;
            }
            JsonArray pr = pe.getAsJsonArray();
            Double[] out = new Double[nCandidates];
            for (int i = 0; i < nCandidates; i++) {
                JsonObject c = pr.get(i).getAsJsonObject();
                if (!c.has("id") || c.get("id").isJsonNull() || c.get("id").getAsInt() != i) {
                    lastError = "prior id at " + i;
                    return null;
                }
                JsonElement p = c.get("p");
                if (p == null || p.isJsonNull()) {
                    out[i] = null;
                    continue;
                }
                double x = p.getAsDouble();
                if (!Double.isFinite(x) || x < 0 || x > 1) {
                    lastError = "p " + x;
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
            JsonElement ms = o.get("ms");
            lastServiceMs = ms != null && ms.isJsonPrimitive() ? ms.getAsDouble() : -1;
            digest.update(pr.toString().getBytes(StandardCharsets.UTF_8));
            return out;
        } catch (Exception e) {
            lastError = "parse: " + e.getMessage();
            return null;
        }
    }

    /** Hex digest of every accepted response's prior array so far (clones the running state). */
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
     * The pin check, once per JVM and service: GET {@code /v1/health} and require its {@code checkpointSha256} to equal
     * {@code expected}. Throws {@link IllegalStateException} (the caller refuses to run) on a missing pin, an unreachable
     * service or a mismatch.
     */
    static String checkHealth(String baseUrl, String expected, int timeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("priorExtra/priorShadow need priorUrl");
        }
        if (expected == null || expected.isBlank()) {
            throw new IllegalStateException("priorExtra/priorShadow need priorCheckpointSha256 (the pinned policy checkpoint)");
        }
        final String b = base(baseUrl);
        final String seen = CHECKED.get(b);
        if (seen != null) {
            if (!seen.equals(expected)) {
                throw new IllegalStateException("prior service " + b + " serves checkpoint " + seen + ", pinned " + expected);
            }
            return seen;
        }
        synchronized (CHECKED) {
            final String again = CHECKED.get(b);
            if (again != null) {
                return checkHealth(baseUrl, expected, timeoutMs);
            }
            final Duration t = Duration.ofMillis(Math.max(timeoutMs, 5000));
            final String got;
            try {
                HttpClient h = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(t).build();
                HttpResponse<String> r = h.send(HttpRequest.newBuilder(URI.create(b + "/v1/health")).timeout(t).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() != 200) {
                    throw new IllegalStateException("prior service " + b + " health: http " + r.statusCode());
                }
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                got = o.has("checkpointSha256") ? o.get("checkpointSha256").getAsString() : null;
            } catch (IllegalStateException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("prior service " + b + " health: interrupted", e);
            } catch (Exception e) {
                throw new IllegalStateException("prior service " + b + " health: " + e, e);
            }
            if (!expected.equals(got)) {
                throw new IllegalStateException("prior service " + b + " serves checkpoint " + got + ", pinned " + expected);
            }
            CHECKED.put(b, got);
            return got;
        }
    }
}
