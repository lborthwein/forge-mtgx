package forge.ai.dp;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.player.Player;

/**
 * decisionpoint-1102 (PR-B; CENSUS.md §7): the DYNAMIC census. Counts every observed decision point by scope (live game
 * or copy), whether it fell inside a label row and whose seat decided relative to the row's seat ({@code row} /
 * {@code opp}), kind, ctx and who answered; and per label row, the same counts. Option counts are bucketed (1, 2+,
 * unknown); ctx carries the ability's ApiType ({@code method:Api}) and {@code ~nested} for a nested ask. Installed only by a run's config key (RlActorBench {@code dpCensus}); writes {@code out} as JSON on
 * {@link #write} (the bench's end) and from a shutdown hook. Reads only ids already on the point: no game change.
 */
public final class CensusObserver implements DecisionObserver {
    private final Path out;
    private final Map<String, LongAdder> totals = new ConcurrentHashMap<>();
    private final Map<String, Map<String, LongAdder>> rows = new ConcurrentHashMap<>();
    private volatile boolean written;

    public CensusObserver(final Path out) {
        this.out = out;
        Runtime.getRuntime().addShutdownHook(new Thread(this::writeQuietly, "dp-census-write"));
    }

    @Override
    public void observe(final DecisionPoint dp, final Object answer, final String by) {
        final Player p = dp.seat();
        final Game g = p == null ? null : p.getGame();
        final String scope = DecisionPoints.isCopy(g) ? "copy" : "live";
        final String row = DecisionPoints.rowOf(g);
        String rel = "-";
        if (row != null && p != null && g != null) {
            final int c2 = row.indexOf(':');
            final int c3 = c2 < 0 ? -1 : row.indexOf(':', c2 + 1);
            final String rowSeat = c2 < 0 || c3 < 0 ? null : row.substring(c2 + 1, c3);
            int idx = g.getRegisteredPlayers().indexOf(p);
            if (idx < 0) {
                idx = g.getPlayers().indexOf(p);
            }
            rel = rowSeat == null || idx < 0 ? "?" : rowSeat.equals(String.valueOf(idx)) ? "row" : "opp";
        }
        final int n = dp.optionCount();
        final String nb = n < 0 ? "n?" : n <= 1 ? "n1" : "n2+";
        final String api = dp.source() == null || dp.source().getApi() == null ? "" : ":" + dp.source().getApi();
        final String ctx = dp.ctx() + api + (dp.nested() ? "~nested" : "");
        final String key = scope + "|" + (row == null ? "norow" : "row") + "|" + rel + "|" + dp.kind() + "|" + ctx
                + "|" + by + "|" + nb;
        totals.computeIfAbsent(key, k -> new LongAdder()).increment();
        if (row != null) {
            rows.computeIfAbsent(row, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(scope + "|" + rel + "|" + dp.kind() + "|" + ctx + "|" + by + "|" + nb,
                            k -> new LongAdder()).increment();
        }
    }

    private void writeQuietly() {
        try {
            write();
        } catch (IOException e) {
            System.err.println("[dp-census] write failed: " + e);
        }
    }

    /** Writes the census (idempotent: the shutdown hook does nothing after an explicit write). */
    public synchronized void write() throws IOException {
        if (written) {
            return;
        }
        written = true;
        final JsonObject o = new JsonObject();
        o.addProperty("schema", "mtgx-dp-census/1");
        o.addProperty("key", "scope|row|seat_rel|kind|ctx|by|options");
        final JsonObject t = new JsonObject();
        for (Map.Entry<String, LongAdder> e : new TreeMap<>(totals).entrySet()) {
            t.addProperty(e.getKey(), e.getValue().sum());
        }
        o.add("totals", t);
        final JsonObject r = new JsonObject();
        for (Map.Entry<String, Map<String, LongAdder>> e : new TreeMap<>(rows).entrySet()) {
            final JsonObject c = new JsonObject();
            for (Map.Entry<String, LongAdder> f : new TreeMap<>(e.getValue()).entrySet()) {
                c.addProperty(f.getKey(), f.getValue().sum());
            }
            r.add(e.getKey(), c);
        }
        o.add("rows", r);
        o.addProperty("row_key", "scope|seat_rel|kind|ctx|by|options");
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(o, w);
        }
        System.err.println("[dp-census] wrote " + out + " (" + totals.size() + " keys, " + rows.size() + " rows)");
    }
}
