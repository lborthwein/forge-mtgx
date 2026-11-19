package forge.ai.simulation;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.card.CardRules;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/**
 * sdcc-counterworlds-1118 (solver key {@code counterWorlds}, off by default): a GRADED prior that the opponent holds a
 * castable counterspell, put into the solver's verification worlds (and, with {@code counterSearch}, the search's
 * worlds). Not a veto: with K worlds and prior P, round(K * P) worlds get one counter dealt into a hidden hand slot (0
 * when P is small), so a line that loses to a counter has P(belief) about 1 - P and stops being a hook; it stays a
 * candidate with that P.
 *
 * <p>Own information only. P is a table lookup on PUBLIC features of the live game: the opponent's blue-producing
 * sources (untapped now, or all of them when the line passes the turn: they untap), its hand SIZE, whether it has
 * shown blue, and (optional, {@code counterSeenGain}) whether a counterspell it showed this game or earlier in the
 * match is known. The table (resource counter-prior-1118.json) was fitted on aggregate counts of real hands in recorded
 * and live games (never this game's). The dealt counter is drawn from the seat's belief pool (the cube minus what the
 * seat knows; LookaheadSearch#beliefPoolFor), among cards whose Forge rules have a Counter spell and that the
 * opponent could cast in that world (mana it has, or an alternative cost whose condition holds). No card names in code.
 */
public final class CounterWorlds {
    private CounterWorlds() {
    }

    /** The table: bucket key "ob|hand|bs" to P, and the per open-blue-bucket fallback. */
    public static final class Table {
        final Map<String, Double> cells = new ConcurrentHashMap<>();
        final Map<String, Double> byOpenBlue = new ConcurrentHashMap<>();
        public final String id;

        Table(JsonObject o, String id) {
            this.id = id;
            for (Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("table").entrySet()) {
                cells.put(e.getKey(), e.getValue().getAsDouble());
            }
            for (Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("open_blue_rate").entrySet()) {
                byOpenBlue.put(e.getKey(), e.getValue().getAsDouble());
            }
        }

        public double p(int openBlue, int handSize, boolean blueSeen) {
            if (handSize <= 0) {
                return 0.0;
            }
            final String ob = openBlue >= 2 ? "2+" : String.valueOf(Math.max(0, openBlue));
            final String hb = handSize <= 3 ? "1-3" : handSize <= 5 ? "4-5" : "6+";
            final Double c = cells.get(ob + "|" + hb + "|" + (blueSeen ? 1 : 0));
            if (c != null) {
                return c;
            }
            final Double b = byOpenBlue.get(ob);
            return b == null ? 0.0 : b;
        }
    }

    private static final String RESOURCE = "/forge/ai/simulation/counter-prior-1118.json";
    private static final Map<String, Table> TABLES = new ConcurrentHashMap<>();

    /** The named table of the resource ("drafted" or "pooled"; null or "" = the resource's default). */
    public static Table table(String name) {
        final String key = name == null ? "" : name;
        return TABLES.computeIfAbsent(key, k -> {
            try (java.io.InputStream in = CounterWorlds.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("counterWorlds: missing " + RESOURCE);
                }
                final JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                final String n = k.isEmpty() ? root.get("default").getAsString() : k;
                if (!root.has(n)) {
                    throw new IllegalStateException("counterWorlds: no table " + n + " in " + RESOURCE);
                }
                return new Table(root.getAsJsonObject(n), n);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("counterWorlds: cannot read " + RESOURCE, e);
            }
        });
    }

    // ------------------------------------------------------------------ public features (live game, seat's view)

    /** A permanent that can add blue mana (untapped only unless {@code allSources}; no summoning-sick creatures then). */
    static boolean blueSource(Card c, boolean allSources) {
        if (!allSources && (c.isTapped() || c.isCreature() && c.hasSickness())) {
            return false;
        }
        for (SpellAbility ma : c.getManaAbilities()) {
            if (ma.canProduce("U")) {
                return true;
            }
        }
        return false;
    }

    static boolean manaSource(Card c, boolean allSources) {
        if (!allSources && (c.isTapped() || c.isCreature() && c.hasSickness())) {
            return false;
        }
        return !c.getManaAbilities().isEmpty();
    }

    /** The opponent's blue sources it can use against a line that wins on turn+{@code winTurn} (public). */
    public static int openBlue(Player opp, int winTurn) {
        int n = 0;
        for (Card c : opp.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isFaceDown() && blueSource(c, winTurn > 0)) {
                n++;
            }
        }
        return n;
    }

    /** The opponent has shown blue: a public card of its with blue in its colour, or a blue mana source. */
    public static boolean blueSeen(Player opp) {
        for (ZoneType z : new ZoneType[] {ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile}) {
            for (Card c : opp.getCardsIn(z)) {
                if (c.isFaceDown()) {
                    continue;
                }
                if (c.getColor().hasBlue() || z == ZoneType.Battlefield && blueSource(c, true)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final Map<String, Boolean> COUNTER_RULES = new ConcurrentHashMap<>();

    /** Forge rules: an instant whose main face has a Counter spell (no names; cached by card name). */
    public static boolean isCounter(forge.item.IPaperCard pc) {
        if (pc == null) {
            return false;
        }
        return COUNTER_RULES.computeIfAbsent(pc.getName(), n -> {
            final CardRules r = pc.getRules();
            if (r == null || r.getType() == null || !r.getType().isInstant()) {
                return false;
            }
            for (String a : r.getMainPart().getAbilities()) {
                if (a.startsWith("SP$ Counter") || a.startsWith("SP$Counter")) {
                    return true;
                }
            }
            return false;
        });
    }

    /** A counterspell the opponent showed in public this game (graveyard / exile / battlefield, face up). */
    public static boolean counterShown(Player opp, Collection<String> seenEarlierNames, Map<String, PaperCard> paper) {
        for (ZoneType z : new ZoneType[] {ZoneType.Graveyard, ZoneType.Exile}) {
            for (Card c : opp.getCardsIn(z)) {
                if (!c.isFaceDown() && c.getOwner() == opp && isCounter(c.getPaperCard())) {
                    return true;
                }
            }
        }
        if (seenEarlierNames != null && paper != null) {
            for (String n : seenEarlierNames) {
                if (isCounter(paper.get(n))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * P that the opponent holds a castable counter, from public features only. {@code seenGain} (1 = no effect) scales
     * the odds when a counter of theirs is known this match (no fitted value yet: an input, inert at 1).
     */
    public static double prior(Table t, Player liveOpp, int winTurn, boolean counterKnown, double seenGain) {
        final double p = t.p(openBlue(liveOpp, winTurn), liveOpp.getCardsIn(ZoneType.Hand).size(), blueSeen(liveOpp));
        if (!counterKnown || seenGain == 1.0 || p <= 0.0 || p >= 1.0) {
            return p;
        }
        final double odds = p / (1.0 - p) * seenGain;
        return odds / (1.0 + odds);
    }

    /** Counter worlds out of {@code k}: round(k * p) (0 when p is small: thin evidence deals none). */
    public static int worlds(int k, double p) {
        return (int) Math.max(0, Math.min(k, Math.round(k * p)));
    }

    // ------------------------------------------------------------------ the world deal

    /**
     * Can {@code opp} (in a world copy) cast the counter {@code pc}: its mana (blue pips from blue sources, the rest from
     * any source; all sources when {@code allSources}), or an alternative cost (Forge rules static AlternativeCost)
     * whose condition holds: return an Island it controls, or exile another blue card from hand.
     */
    static boolean castable(PaperCard pc, Player opp, boolean allSources, Card replacing) {
        final CardRules r = pc.getRules();
        int any = 0, blue = 0;
        boolean island = false;
        for (Card c : opp.getCardsIn(ZoneType.Battlefield)) {
            if (c.getType().hasSubtype("Island")) {
                island = true;
            }
            if (manaSource(c, allSources)) {
                any++;
                if (blueSource(c, allSources)) {
                    blue++;
                }
            }
        }
        final int u = r.getManaCost().getShardCount(ManaCostShard.BLUE);
        if (any >= r.getManaCost().getCMC() && blue >= u) {
            return true;
        }
        for (String st : r.getMainPart().getStaticAbilities()) {
            if (!st.contains("Mode$ AlternativeCost")) {
                continue;
            }
            if (st.contains("Return<1/Island>")) {
                if (island) {
                    return true;
                }
            } else if (st.contains("ExileFromHand<1/Card.Blue+Other>")) {
                for (Card h : opp.getCardsIn(ZoneType.Hand)) {
                    if (h != replacing && h.getColor().hasBlue()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The pool's counterspells (by Forge rules), in pool order. */
    static List<String> counters(List<String> pool, Map<String, PaperCard> paper) {
        final List<String> out = new ArrayList<>();
        for (String n : pool) {
            if (isCounter(paper.get(n))) {
                out.add(n);
            }
        }
        return out;
    }

    /** Picks a counter of {@code cands} castable by {@code opp} in this world (null if none), uniformly by {@code rng}. */
    static String pick(List<String> cands, Map<String, PaperCard> paper, Player opp, boolean allSources, Card replacing, Random rng) {
        final List<String> ok = new ArrayList<>();
        for (String n : cands) {
            if (castable(paper.get(n), opp, allSources, replacing)) {
                ok.add(n);
            }
        }
        return ok.isEmpty() ? null : ok.get(rng.nextInt(ok.size()));
    }
}
