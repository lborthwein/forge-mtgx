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
 * Client of the L2 policy P1 service (mtgx {@code tools/ml/foundation/serve.py}, lane l2-tools-1001; contract in the
 * run folder {@code l2-fork-1001/contract.md}). Two calls, modelled on {@link PriorClient}:
 * <ul>
 * <li>{@code POST /v1/policy/plan} (our turn): per hand card {@code cast} = P(cast this turn) and {@code land} =
 * P(played as the land this turn), from our view at our first main-phase priority;</li>
 * <li>{@code POST /v1/policy/react} (the opponent's turn): per hand card {@code react} = P(we cast it this opponent
 * turn | the opponent's casts so far), from our view at the first priority of that turn.</li>
 * </ul>
 * Request: {@code schema, seat, startingSeat, mulligans, deck, root (ForgeState), cards [{id, fid}]} (+ {@code context}
 * for react). Response: {@code schema, kind, cards [{id, fid, <head>: p|null}], unknownCards, truncated, ms, model}.
 *
 * <p>Failures (connection, timeout, HTTP status, schema, kind, length, an id or fid out of order, a p outside [0, 1])
 * return {@code null}; the pilot plays Forge AI's answer and counts the failure. The running digest folds the text of
 * every accepted response's {@code cards} array, so a replay audit also covers the policy's answers
 * ({@code policyDigest}). Not final: tests substitute {@link #post}.
 */
public class PolicyClient {

    public static final String REQUEST_SCHEMA = "mtgx-l2-policy-request/1";
    public static final String RESPONSE_SCHEMA = "mtgx-l2-policy-response/1";
    public static final String PLAN = "plan";
    public static final String REACT = "react";

    private final String base;
    private final Duration timeout;
    private final HttpClient http;
    private final MessageDigest digest;
    String lastError = null;
    String checkpointSha256 = null;
    long unknownCards = 0;
    long truncated = 0;
    /** The service's own {@code ms} of the last accepted response (-1 if absent). */
    double lastServiceMs = -1;

    public PolicyClient(String baseUrl, int timeoutMs) {
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

    /**
     * One HTTP POST; returns the body of a 200 response. Throws on transport failure; a non-200 status throws
     * {@link HttpStatus}. Overridden by tests (a mock service).
     */
    protected String post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new HttpStatus(resp.statusCode());
        }
        return resp.body();
    }

    /** A non-200 reply. */
    public static final class HttpStatus extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;

        public HttpStatus(int status) {
            super("http " + status);
            this.status = status;
        }
    }

    /**
     * @param kind  {@link #PLAN} or {@link #REACT}
     * @param heads the per-card fields to read, e.g. {"cast", "land"} or {"react"}
     * @param fids  the request's cards, in order (card {@code i} carries {@code id = i})
     * @return {@code [card][head]} p (null where the service gives none); null on any failure ({@link #lastError})
     */
    Double[][] call(String kind, JsonObject request, int[] fids, String... heads) {
        lastError = null;
        final String body;
        try {
            body = post("/v1/policy/" + kind, request.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "interrupted";
            return null;
        } catch (HttpStatus e) {
            lastError = e.getMessage();
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
            if (!o.has("kind") || !kind.equals(o.get("kind").getAsString())) {
                lastError = "kind";
                return null;
            }
            JsonElement ce = o.get("cards");
            if (ce == null || !ce.isJsonArray() || ce.getAsJsonArray().size() != fids.length) {
                lastError = "cards length " + (ce == null || !ce.isJsonArray() ? -1 : ce.getAsJsonArray().size()) + " != " + fids.length;
                return null;
            }
            JsonArray cs = ce.getAsJsonArray();
            Double[][] out = new Double[fids.length][heads.length];
            for (int i = 0; i < fids.length; i++) {
                JsonObject c = cs.get(i).getAsJsonObject();
                if (!c.has("id") || c.get("id").isJsonNull() || c.get("id").getAsInt() != i) {
                    lastError = "card id at " + i;
                    return null;
                }
                if (c.has("fid") && !c.get("fid").isJsonNull() && c.get("fid").getAsInt() != fids[i]) {
                    lastError = "card fid at " + i;
                    return null;
                }
                for (int h = 0; h < heads.length; h++) {
                    JsonElement p = c.get(heads[h]);
                    if (p == null || p.isJsonNull()) {
                        continue;
                    }
                    double x = p.getAsDouble();
                    if (!Double.isFinite(x) || x < 0 || x > 1) {
                        lastError = heads[h] + " p " + x;
                        return null;
                    }
                    out[i][h] = x;
                }
            }
            JsonElement m = o.get("model");
            if (m != null && m.isJsonObject() && m.getAsJsonObject().has("checkpointSha256")) {
                checkpointSha256 = m.getAsJsonObject().get("checkpointSha256").getAsString();
            }
            JsonElement u = o.get("unknownCards");
            if (u != null && u.isJsonArray()) {
                unknownCards += u.getAsJsonArray().size();
            }
            JsonElement t = o.get("truncated");
            if (t != null && t.isJsonPrimitive()) {
                truncated += t.getAsLong();
            }
            JsonElement ms = o.get("ms");
            lastServiceMs = ms != null && ms.isJsonPrimitive() ? ms.getAsDouble() : -1;
            digest.update(kind.getBytes(StandardCharsets.UTF_8));
            digest.update(cs.toString().getBytes(StandardCharsets.UTF_8));
            return out;
        } catch (Exception e) {
            lastError = "parse: " + e.getMessage();
            return null;
        }
    }

    /** Hex digest of every accepted response's cards array so far (clones the running state). */
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
    public static String checkHealth(String baseUrl, String expected, int timeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("a policy seat needs policy.url");
        }
        if (expected == null || expected.isBlank()) {
            throw new IllegalStateException("a policy seat needs policy.checkpointSha256 (the pinned policy checkpoint)");
        }
        final String b = base(baseUrl);
        final String seen = CHECKED.get(b);
        if (seen != null) {
            if (!seen.equals(expected)) {
                throw new IllegalStateException("policy service " + b + " serves checkpoint " + seen + ", pinned " + expected);
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
                    throw new IllegalStateException("policy service " + b + " health: http " + r.statusCode());
                }
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                got = o.has("checkpointSha256") ? o.get("checkpointSha256").getAsString() : null;
            } catch (IllegalStateException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("policy service " + b + " health: interrupted", e);
            } catch (Exception e) {
                throw new IllegalStateException("policy service " + b + " health: " + e, e);
            }
            if (!expected.equals(got)) {
                throw new IllegalStateException("policy service " + b + " serves checkpoint " + got + ", pinned " + expected);
            }
            CHECKED.put(b, got);
            return got;
        }
    }
}
