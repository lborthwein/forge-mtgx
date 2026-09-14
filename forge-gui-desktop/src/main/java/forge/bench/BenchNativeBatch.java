package forge.bench;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Explicit, bounded native-game configurations; never infer seeds or pilots. */
final class BenchNativeBatch {
    private BenchNativeBatch() { }
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
        "decks", "games", "seed", "seats", "cubeComboSeats", "aiProfile",
        "aiInformationPolicy", "useSimulation", "aiCanUseTimeout", "aiTimeoutSec", "timeoutSec"));

    static List<JsonObject> parse(JsonObject envelope) {
        require(envelope.keySet().equals(java.util.Collections.singleton("gameConfigs")), "batch envelope keys");
        require(envelope.get("gameConfigs").isJsonArray(), "gameConfigs array");
        int size = envelope.getAsJsonArray("gameConfigs").size();
        require(size > 0 && size <= 500, "batch size 1..500");
        final List<JsonObject> jobs = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        for (JsonElement entry : envelope.getAsJsonArray("gameConfigs")) {
            require(entry.isJsonObject(), "job object");
            JsonObject job = entry.getAsJsonObject();
            require(job.keySet().equals(new HashSet<>(Arrays.asList("id", "config"))), "job keys");
            String id = string(job.get("id"));
            require(id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}") && ids.add(id), "unique job id");
            require(job.get("config").isJsonObject(), "config object");
            JsonObject cfg = job.getAsJsonObject("config");
            require(cfg.keySet().equals(KEYS), "explicit native config keys");
            require(integer(cfg.get("games")) == 1 && integer(cfg.get("seed")) > 0, "one game and exact positive seed");
            require(integer(cfg.get("timeoutSec")) > 0 && integer(cfg.get("aiTimeoutSec")) > 0, "timeouts");
            require(isFalse(cfg.get("useSimulation")) && isFalse(cfg.get("aiCanUseTimeout")), "deterministic native mode");
            require("Default".equals(string(cfg.get("aiProfile"))), "Default profile");
            require("closed-decklist-repair-v1".equals(string(cfg.get("aiInformationPolicy"))), "closed information");
            require(cfg.get("decks").isJsonArray() && cfg.getAsJsonArray("decks").size() == 2, "two decks");
            for (JsonElement deck : cfg.getAsJsonArray("decks")) require(new File(string(deck)).isAbsolute(), "absolute deck path");
            require(cfg.get("seats").isJsonObject(), "seats object");
            JsonObject seats = cfg.getAsJsonObject("seats");
            require(seats.keySet().equals(new HashSet<>(Arrays.asList("0", "1"))), "two explicit seats");
            require("forge".equals(string(seats.get("0"))) && "forge".equals(string(seats.get("1"))), "native seats only");
            require(cfg.get("cubeComboSeats").isJsonArray(), "combo seats array");
            Set<Long> combo = new HashSet<>();
            for (JsonElement seat : cfg.getAsJsonArray("cubeComboSeats")) {
                long n = integer(seat);
                require((n == 0 || n == 1) && combo.add(n), "unique valid combo seat");
            }
            jobs.add(job.deepCopy());
        }
        return jobs;
    }

    private static String string(JsonElement e) {
        require(e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString(), "string required");
        return e.getAsString();
    }
    private static long integer(JsonElement e) {
        require(e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber(), "number required");
        try { return e.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException | NumberFormatException failure) { throw new IllegalArgumentException("exact integer required", failure); }
    }
    private static boolean isFalse(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && !e.getAsBoolean();
    }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
