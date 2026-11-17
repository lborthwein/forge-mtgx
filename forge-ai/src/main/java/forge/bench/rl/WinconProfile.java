package forge.bench.rl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.deck.Deck;
import forge.game.player.Player;
import forge.item.PaperCard;

/**
 * sdcc-wincon-1117 ({@link ChoiceScorer.Config#winconProfile}; empty = off): a per-deck card score computed OFFLINE by a
 * general solver process over the seat's own decklist (planner-fixes/suite/tools/wincon.py: solver lines on generic
 * goldfish positions of the deck, score = removal sensitivity x in-hand share). The names are data from the file; no
 * name, card or deck is known here.
 *
 * <p>File: {@code {"decks": {"<deckKey>": {"<card name>": score, ...}, ...}}}. deckKey ({@link #deckKey}): the seat's main
 * deck as one line {@code "<count> <name>"} per distinct card name (counts summed over printings), the lines sorted by
 * name (Java String order, case-sensitive), joined by "\n" with no trailing newline, UTF-8, SHA-256, lower-case hex, the
 * first 16 characters. For a .dck file the names are the [Main] section's, each line's text before the first '|'.
 * {@code java -cp <jar> forge.bench.rl.WinconProfile a.dck ...} prints each file's key.
 */
public final class WinconProfile {
    private WinconProfile() {
    }

    private static final Map<String, Map<String, Map<String, Double>>> FILES = new ConcurrentHashMap<>();

    /** The file's decks (cached per path); a missing or malformed file is an error. */
    public static Map<String, Map<String, Double>> load(final String path) {
        return FILES.computeIfAbsent(path, p -> {
            try {
                final JsonObject root = JsonParser.parseString(new String(Files.readAllBytes(Paths.get(p)),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                for (String k : root.keySet()) {
                    if (!"decks".equals(k)) {
                        throw new IllegalArgumentException("winconProfile: unknown key " + k);
                    }
                }
                final Map<String, Map<String, Double>> out = new TreeMap<>();
                final JsonObject decks = root.getAsJsonObject("decks");
                if (decks == null) {
                    throw new IllegalArgumentException("winconProfile: no decks");
                }
                for (String dk : decks.keySet()) {
                    final Map<String, Double> sc = new TreeMap<>();
                    final JsonObject d = decks.getAsJsonObject(dk);
                    for (String name : d.keySet()) {
                        final double v = d.get(name).getAsDouble();
                        if (!(v >= 0)) {
                            throw new IllegalArgumentException("winconProfile: score must be >= 0: " + dk + " " + name);
                        }
                        sc.put(name, v);
                    }
                    out.put(dk, Collections.unmodifiableMap(sc));
                }
                return Collections.unmodifiableMap(out);
            } catch (IOException | RuntimeException e) {
                throw new IllegalArgumentException("winconProfile: cannot read " + p + ": " + e, e);
            }
        });
    }

    /** The deckKey of a name -> count map (see the class comment). */
    public static String deckKey(final Map<String, Integer> counts) {
        final List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> e : new TreeMap<>(counts).entrySet()) {
            lines.add(e.getValue() + " " + e.getKey());
        }
        try {
            final byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A .dck file's [Main] section as name -> count (the text before the first '|'; counts summed). */
    public static Map<String, Integer> dckMain(final List<String> lines) {
        final Map<String, Integer> out = new TreeMap<>();
        boolean main = false;
        for (String raw : lines) {
            final String l = raw.trim();
            if (l.startsWith("[")) {
                main = l.equalsIgnoreCase("[Main]");
                continue;
            }
            if (!main || l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            final int sp = l.indexOf(' ');
            if (sp <= 0 || !l.substring(0, sp).matches("\\d+")) {
                continue;
            }
            String name = l.substring(sp + 1);
            final int bar = name.indexOf('|');
            if (bar >= 0) {
                name = name.substring(0, bar);
            }
            out.merge(name.trim(), Integer.parseInt(l.substring(0, sp)), Integer::sum);
        }
        return out;
    }

    /** The seat's registered main deck as name -> count, or null when it has none. */
    static Map<String, Integer> mainOf(final Player p) {
        final Deck d = p.getRegisteredPlayer() == null ? null : p.getRegisteredPlayer().getDeck();
        if (d == null || d.getMain() == null) {
            return null;
        }
        final Map<String, Integer> out = new TreeMap<>();
        for (Map.Entry<PaperCard, Integer> e : d.getMain()) {
            out.merge(e.getKey().getName(), e.getValue(), Integer::sum);
        }
        return out.isEmpty() ? null : out;
    }

    /** The profile of the seat's deck in the file, or null (no deck, or no entry: the rule is off for this seat). */
    static Map<String, Double> forSeat(final String path, final Player p) {
        final Map<String, Integer> main = mainOf(p);
        return main == null ? null : load(path).get(deckKey(main));
    }

    /**
     * The selection (pure): -1 (nothing changes) unless {@code allZero} (every scored option's goldfish and reach are 0);
     * then, among eligible options with a profile score > 0, the highest score; ties: the larger v (NaN lowest), then
     * the lower index (the options' order). -1 when no eligible option has a score > 0.
     */
    public static int select(final double[] score, final boolean[] eligible, final double[] v, final boolean allZero) {
        if (!allZero) {
            return -1;
        }
        int best = -1;
        for (int i = 0; i < score.length; i++) {
            if (!eligible[i] || !(score[i] > 0)) {
                continue;
            }
            if (best < 0 || score[i] > score[best] || score[i] == score[best] && tv(v[i]) > tv(v[best])) {
                best = i;
            }
        }
        return best;
    }

    private static double tv(final double x) {
        return Double.isNaN(x) ? Double.NEGATIVE_INFINITY : x;
    }

    /** Prints each .dck file's deckKey and its main deck's line count. */
    public static void main(final String[] args) throws IOException {
        for (String f : args) {
            final Map<String, Integer> m = dckMain(Files.readAllLines(Paths.get(f), StandardCharsets.UTF_8));
            int n = 0;
            for (int c : m.values()) {
                n += c;
            }
            System.out.println(deckKey(m) + "\t" + n + " cards\t" + f);
        }
    }
}
