package forge.ai.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * J1 (lane block-search-1019, review-replay-1019's jar spec): the AI seat's live choices, replayed.
 * {@code -Dforge.interactive.aiReplay=<jsonl>} names the game journal's {@code ai} marks (one JSON object per line:
 * {@code decision} = the decision LINE's index, {@code played} (or the older {@code chosen}) = the label of the
 * candidate the live game played, {@code outcome}). At each searched decision the search runs exactly as live (every
 * seed stream and cache advances the same), then, when the live game played another candidate that is among this
 * decision's candidates, that one is played ({@code "forced":true} on the decision line); when it is not among them,
 * the search's own stands ({@code "forcedMiss":true}).
 *
 * <p>The key is the decision line's own index: the policy seat's {@code searchedLines} (RlLiveSeat; every
 * {@code [lookahead-explain]} row carries it as {@code line}), the K8 seat's {@code decision}. Never the search's
 * internal decision counter, which also counts uncontested and skipped searches.
 *
 * <p>Absent property (every live game): {@link #current()} is null and nothing anywhere changes.
 */
public final class AiReplay {
    /** One live decision: the label of the candidate played, and the line's outcome ("proven" for a proven line). */
    public static final class Mark {
        public final String played;
        public final String outcome;

        Mark(final String played, final String outcome) {
            this.played = played;
            this.outcome = outcome;
        }
    }

    private final Map<Integer, Mark> byLine;

    AiReplay(final Map<Integer, Mark> byLine) {
        this.byLine = Collections.unmodifiableMap(byLine);
    }

    /** The live decision at line {@code line}, or null when the journal has none. */
    public Mark at(final int line) {
        return byLine.get(line);
    }

    public int size() {
        return byLine.size();
    }

    /**
     * The rows of a marks file: objects with an integer {@code decision}; {@code t}, when present, must be "ai". A row
     * without a label is kept with a null {@code played} (only its outcome can be matched). A later row for the same line
     * replaces an earlier one.
     */
    public static AiReplay parse(final List<String> lines) {
        final Map<Integer, Mark> m = new TreeMap<>();
        for (String raw : lines) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            final JsonElement e = JsonParser.parseString(raw);
            if (!e.isJsonObject()) {
                continue;
            }
            final JsonObject o = e.getAsJsonObject();
            if (o.has("t") && !"ai".equals(o.get("t").getAsString())) {
                continue;
            }
            if (!o.has("decision") || !o.get("decision").isJsonPrimitive()) {
                continue;
            }
            final String played = o.has("played") && o.get("played").isJsonPrimitive() ? o.get("played").getAsString()
                    : o.has("chosen") && o.get("chosen").isJsonPrimitive() ? o.get("chosen").getAsString() : null;
            final String outcome = o.has("outcome") && o.get("outcome").isJsonPrimitive() ? o.get("outcome").getAsString()
                    : null;
            m.put(o.get("decision").getAsInt(), new Mark(played, outcome));
        }
        return new AiReplay(m);
    }

    private static volatile AiReplay current;
    private static volatile String loadedFrom;

    /**
     * The replay named by {@code -Dforge.interactive.aiReplay}, read once per path; null when the property is unset or
     * the file cannot be read (said once on stderr: the replay then decides as the search does, and the host's check
     * reports the divergence).
     */
    public static AiReplay current() {
        final String path = System.getProperty("forge.interactive.aiReplay");
        if (path == null || path.isBlank()) {
            return null;
        }
        synchronized (AiReplay.class) {
            if (!path.equals(loadedFrom)) {
                loadedFrom = path;
                try {
                    current = parse(Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8));
                    System.err.println("[ai-replay] " + current.size() + " decisions from " + path);
                } catch (IOException | RuntimeException e) {
                    current = null;
                    System.err.println("[ai-replay] could not read " + path + ": " + e);
                }
            }
            return current;
        }
    }
}
