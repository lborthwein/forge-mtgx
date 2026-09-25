package forge.ai.simulation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Client of the foundation-model leaf service (mtgx {@code tools/ml/foundation/serve.py}, {@code POST /v1/forge/score},
 * plan item C5). One request per searched decision carries every non-terminal play-out leaf of that decision, in a
 * fixed order; the reply is P(seat wins) per leaf.
 *
 * <p>Failures (connection, timeout, HTTP status, schema, length, a non-finite value) return {@code null}; the caller
 * falls back to Forge's static evaluator for that decision and counts it. The running digest folds the exact text of
 * every response's value array, so a replay audit also covers the model's answers.
 */
final class ModelClient {
    static final String REQUEST_SCHEMA = "mtgx-foundation-forge-request/1";
    static final String RESPONSE_SCHEMA = "mtgx-foundation-forge-response/1";

    private final URI uri;
    private final Duration timeout;
    private final HttpClient http;
    private final MessageDigest digest;
    String lastError = null;
    String checkpointSha256 = null;
    long unknownCards = 0;

    ModelClient(String baseUrl, int timeoutMs) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.uri = URI.create(b + "/v1/forge/score");
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return P(seat wins) per leaf, in request order; null on any failure ({@link #lastError} says why). */
    double[] score(JsonObject request, int nLeaves) {
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
            JsonArray v = o.getAsJsonArray("value");
            if (v == null || v.size() != nLeaves) {
                lastError = "length " + (v == null ? -1 : v.size()) + " != " + nLeaves;
                return null;
            }
            double[] out = new double[nLeaves];
            for (int i = 0; i < nLeaves; i++) {
                double x = v.get(i).getAsDouble();
                if (!Double.isFinite(x) || x < 0 || x > 1) {
                    lastError = "value " + x;
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
            digest.update(v.toString().getBytes(StandardCharsets.UTF_8));
            return out;
        } catch (Exception e) {
            lastError = "parse: " + e.getMessage();
            return null;
        }
    }

    /** Hex digest of every accepted response's value array so far (clones the running state). */
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
}
