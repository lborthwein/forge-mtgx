package forge.ai;

import com.google.common.collect.Multiset;
import com.google.common.collect.Table;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.card.ColorSet;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameEntityCounterTable;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.cost.PaymentDecision;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The AI tape's encodings (lane replay-record-1021; see {@link AiTape}). Object references are short strings:
 * {@code c<id>} a card, {@code p<seat>} a player, {@code s<i>/<host>} the i-th stack item from the top (its host card),
 * {@code a<host>:<hash>} an ability by host card and description hash. Decoding resolves them in the replay's game and
 * throws {@link IllegalStateException} with the reason when the reference is not there; the tape turns that into a
 * divergence.
 */
public final class AiTapeCodec {
    private AiTapeCodec() {
    }

    // ------------------------------------------------------------------ hashes and references

    /** First 8 hex digits of the SHA-1 of {@code s} (never the text itself: descriptions carry card names). */
    public static String h(final String s) {
        try {
            final byte[] d = MessageDigest.getInstance("SHA-1").digest(String.valueOf(s).getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String descOf(final SpellAbility sa) {
        return h(sa.getDescription());
    }

    static String saWhat(final SpellAbility sa) {
        if (sa == null) {
            return "(none)";
        }
        return "a" + (sa.getHostCard() == null ? "?" : sa.getHostCard().getId()) + ":" + descOf(sa);
    }

    public static String ref(final Game g, final Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Card c) {
            return "c" + c.getId();
        }
        if (o instanceof Player p) {
            final int i = g.getRegisteredPlayers().indexOf(p);
            if (i < 0) {
                throw new IllegalStateException("a player outside the seats");
            }
            return "p" + i;
        }
        if (o instanceof SpellAbility sa) {
            int i = 0;
            for (SpellAbilityStackInstance si : g.getStack()) {
                if (si.getSpellAbility() == sa || si.getSpellAbility() == sa.getRootAbility()) {
                    return "s" + i + "/" + (si.getSourceCard() == null ? -1 : si.getSourceCard().getId());
                }
                i++;
            }
            return saWhat(sa);
        }
        throw new IllegalStateException("cannot name a " + o.getClass().getSimpleName());
    }

    public static Object deref(final Game g, final String r) {
        if (r == null) {
            return null;
        }
        switch (r.charAt(0)) {
            case 'c': {
                final int id = Integer.parseInt(r.substring(1));
                final Card c = g.findById(id);
                if (c == null) {
                    throw new IllegalStateException("card " + r + " is not in the game");
                }
                return c;
            }
            case 'p': {
                final int i = Integer.parseInt(r.substring(1));
                if (i < 0 || i >= g.getRegisteredPlayers().size()) {
                    throw new IllegalStateException("no seat " + r);
                }
                return g.getRegisteredPlayers().get(i);
            }
            case 's': {
                final int slash = r.indexOf('/');
                final int i = Integer.parseInt(r.substring(1, slash));
                final int host = Integer.parseInt(r.substring(slash + 1));
                int k = 0;
                for (SpellAbilityStackInstance si : g.getStack()) {
                    if (k++ == i) {
                        if (si.getSourceCard() != null && si.getSourceCard().getId() != host) {
                            throw new IllegalStateException("stack item " + i + " is not from card " + host);
                        }
                        return si.getSpellAbility();
                    }
                }
                throw new IllegalStateException("no stack item " + i);
            }
            default:
                throw new IllegalStateException("unknown reference " + r);
        }
    }

    static JsonArray refs(final Game g, final Iterable<?> os) {
        final JsonArray a = new JsonArray();
        if (os != null) {
            for (Object o : os) {
                a.add(ref(g, o));
            }
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    static <T> List<T> derefs(final Game g, final JsonElement e) {
        final List<T> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) {
            return out;
        }
        for (JsonElement x : e.getAsJsonArray()) {
            out.add((T) deref(g, x.isJsonNull() ? null : x.getAsString()));
        }
        return out;
    }

    /** The element of {@code options} that {@code r} names (by identity), else an error. */
    static <T> T among(final Game g, final Collection<T> options, final String r) {
        if (r == null) {
            return null;
        }
        for (T o : options) {
            if (o != null && r.equals(safeRef(g, o))) {
                return o;
            }
        }
        throw new IllegalStateException(r + " is not among the " + options.size() + " options offered");
    }

    static String safeRef(final Game g, final Object o) {
        try {
            return ref(g, o);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static <T> List<T> amongAll(final Game g, final Collection<T> options, final JsonElement e) {
        final List<T> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) {
            return null;
        }
        for (JsonElement x : e.getAsJsonArray()) {
            out.add(among(g, options, x.isJsonNull() ? null : x.getAsString()));
        }
        return out;
    }

    // ------------------------------------------------------------------ positional choices

    /** Indices of {@code chosen} in {@code options} (by identity, then equality); null when {@code chosen} is null. */
    static <T> JsonElement indices(final List<T> options, final List<T> chosen) {
        if (chosen == null) {
            return JsonNull.INSTANCE;
        }
        final JsonArray a = new JsonArray();
        final boolean[] used = new boolean[options.size()];
        for (T c : chosen) {
            int found = -1;
            for (int i = 0; i < options.size() && found < 0; i++) {
                if (!used[i] && options.get(i) == c) {
                    found = i;
                }
            }
            for (int i = 0; i < options.size() && found < 0; i++) {
                if (!used[i] && Objects.equals(options.get(i), c)) {
                    found = i;
                }
            }
            if (found < 0) {
                throw new IllegalStateException("a choice outside its options");
            }
            used[found] = true;
            a.add(found);
        }
        final JsonObject o = new JsonObject();
        o.add("i", a);
        o.addProperty("of", options.size());
        return o;
    }

    static <T> List<T> pick(final List<T> options, final JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        final JsonObject o = e.getAsJsonObject();
        if (o.has("err")) {
            throw new IllegalStateException("the record could not name this choice (" + AiTape.str(o, "err") + ")");
        }
        final int of = o.get("of").getAsInt();
        if (of != options.size()) {
            throw new IllegalStateException(options.size() + " options offered, " + of + " recorded");
        }
        final List<T> out = new ArrayList<>();
        for (JsonElement x : o.getAsJsonArray("i")) {
            out.add(options.get(x.getAsInt()));
        }
        return out;
    }

    static <T> JsonElement index(final List<T> options, final T chosen) {
        if (chosen == null) {
            return JsonNull.INSTANCE;
        }
        final List<T> one = new ArrayList<>();
        one.add(chosen);
        return indices(options, one);
    }

    static <T> T pickOne(final List<T> options, final JsonElement e) {
        final List<T> l = pick(options, e);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    static JsonElement unencodable(final RuntimeException e) {
        final JsonObject o = new JsonObject();
        o.addProperty("err", e.getClass().getSimpleName() + ": " + e.getMessage());
        return o;
    }

    static void checkEncodable(final JsonElement a) {
        if (a != null && a.isJsonObject() && a.getAsJsonObject().has("err")) {
            throw new IllegalStateException("the record could not name this decision (" + AiTape.str(a.getAsJsonObject(), "err") + ")");
        }
    }

    // ------------------------------------------------------------------ cost decisions

    static JsonElement decision(final Game g, final PaymentDecision d) {
        if (d == null) {
            return JsonNull.INSTANCE;
        }
        final JsonObject o = new JsonObject();
        if (d.c != 0) {
            o.addProperty("c", d.c);
        }
        if (d.type != null) {
            o.addProperty("ty", d.type);
        }
        if (d.colors != null) {
            o.addProperty("col", d.colors.getColor());
        }
        if (!d.cards.isEmpty()) {
            o.add("cd", refs(g, d.cards));
        }
        if (d.players != null) {
            o.add("pl", refs(g, d.players));
        }
        if (d.sp != null) {
            o.add("sp", refs(g, d.sp));
        }
        if (d.counterTable != null) {
            final JsonArray ct = new JsonArray();
            for (Table.Cell<Optional<Player>, GameEntity, Multiset<CounterType>> cell : d.counterTable.cellSet()) {
                for (Multiset.Entry<CounterType> en : cell.getValue().entrySet()) {
                    final JsonArray row = new JsonArray();
                    row.add(cell.getRowKey().map(p -> ref(g, p)).orElse(null));
                    row.add(ref(g, cell.getColumnKey()));
                    row.add(en.getElement().getName());
                    row.add(en.getCount());
                    ct.add(row);
                }
            }
            o.add("ct", ct);
        }
        if (d.mana != null) {
            throw new IllegalStateException("a mana payment decision");
        }
        return o;
    }

    static PaymentDecision decision(final Game g, final JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        checkEncodable(e);
        final JsonObject o = e.getAsJsonObject();
        final PaymentDecision d;
        if (o.has("pl")) {
            d = PaymentDecision.players(derefs(g, o.get("pl")));
        } else if (o.has("sp")) {
            d = PaymentDecision.spellabilities(derefs(g, o.get("sp")));
        } else if (o.has("ct")) {
            final GameEntityCounterTable t = new GameEntityCounterTable();
            for (JsonElement r : o.getAsJsonArray("ct")) {
                final JsonArray row = r.getAsJsonArray();
                final Player putter = row.get(0).isJsonNull() ? null : (Player) deref(g, row.get(0).getAsString());
                final GameEntity ent = (GameEntity) deref(g, row.get(1).getAsString());
                t.put(putter, ent, CounterType.getType(row.get(2).getAsString()), row.get(3).getAsInt());
            }
            d = PaymentDecision.counters(t);
        } else if (o.has("col")) {
            d = PaymentDecision.colors(ColorSet.fromMask(o.get("col").getAsInt()));
        } else if (o.has("ty")) {
            d = PaymentDecision.type(o.get("ty").getAsString());
        } else if (o.has("cd")) {
            d = PaymentDecision.card(AiTapeCodec.<Card>derefs(g, o.get("cd")));
        } else {
            d = PaymentDecision.number(0);
        }
        if (o.has("cd") && d.cards.isEmpty()) {
            d.cards.addAll(AiTapeCodec.<Card>derefs(g, o.get("cd")));
        }
        if (o.has("ty") && d.type == null) {
            d.type = o.get("ty").getAsString();
        }
        if (o.has("col") && d.colors == null) {
            d.colors = ColorSet.fromMask(o.get("col").getAsInt());
        }
        d.c = o.has("c") ? o.get("c").getAsInt() : 0;
        return d;
    }

    // ------------------------------------------------------------------ mana sources

    static JsonElement manaChoice(final Game g, final SpellAbility ma) {
        if (ma == null) {
            return JsonNull.INSTANCE;
        }
        final JsonObject o = new JsonObject();
        final Card host = ma.getHostCard();
        o.addProperty("c", host == null ? -1 : host.getId());
        int i = -1;
        if (host != null) {
            int k = 0;
            for (SpellAbility m : host.getManaAbilities()) {
                if (m == ma) {
                    i = k;
                    break;
                }
                k++;
            }
        }
        o.addProperty("i", i);
        o.addProperty("d", descOf(ma));
        if (ma.getManaPart() != null && ma.getManaPart().getExpressChoice() != null
                && !ma.getManaPart().getExpressChoice().isEmpty()) {
            o.addProperty("x", ma.getManaPart().getExpressChoice());
        }
        return o;
    }

    static SpellAbility manaChoice(final Game g, final Collection<SpellAbility> options, final JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        checkEncodable(e);
        final JsonObject o = e.getAsJsonObject();
        final int host = o.get("c").getAsInt();
        final int i = o.get("i").getAsInt();
        final String d = AiTape.str(o, "d");
        SpellAbility byDesc = null;
        for (SpellAbility m : options) {
            if (m.getHostCard() == null || m.getHostCard().getId() != host) {
                continue;
            }
            int k = 0;
            int at = -1;
            for (SpellAbility x : m.getHostCard().getManaAbilities()) {
                if (x == m) {
                    at = k;
                    break;
                }
                k++;
            }
            if (at == i && i >= 0) {
                byDesc = m;
                break;
            }
            if (byDesc == null && descOf(m).equals(d)) {
                byDesc = m;
            }
        }
        if (byDesc == null) {
            throw new IllegalStateException("card " + host + "'s recorded mana ability is not among the " + options.size()
                    + " sources the payment offers");
        }
        if (o.has("x") && byDesc.getManaPart() != null) {
            byDesc.getManaPart().setExpressChoice(o.get("x").getAsString());
        }
        return byDesc;
    }

    // ------------------------------------------------------------------ an SA's targets and X

    /**
     * {@code {x, t: [per chain link: null | [refs]], dv: [per link: null | [amounts]], tp: [links another player
     * targets]}}: everything the seat decided about {@code sa} that payment and resolution read.
     */
    static JsonElement config(final Game g, final Player seat, final SpellAbility sa) {
        final JsonObject o = new JsonObject();
        o.addProperty("h", sa.getHostCard() == null ? -1 : sa.getHostCard().getId());
        if (sa.getXManaCostPaid() != null) {
            o.addProperty("x", sa.getXManaCostPaid());
        }
        final JsonArray t = new JsonArray();
        final JsonArray dv = new JsonArray();
        boolean anyDv = false;
        int links = 0;
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            links++;
            if (!s.usesTargeting()) {
                t.add(JsonNull.INSTANCE);
                dv.add(JsonNull.INSTANCE);
                continue;
            }
            if (s.getTargetingPlayer() != null && s.getTargetingPlayer() != seat) {
                t.add("other"); // another player chose these targets (their own inputs replay them)
                dv.add(JsonNull.INSTANCE);
                continue;
            }
            t.add(refs(g, s.getTargets()));
            if (s.isDividedAsYouChoose()) {
                final JsonArray d = new JsonArray();
                for (GameObject x : s.getTargets()) {
                    final Integer v = s.getDividedValue(x);
                    d.add(v == null ? JsonNull.INSTANCE : new JsonPrimitive(v));
                }
                dv.add(d);
                anyDv = true;
            } else {
                dv.add(JsonNull.INSTANCE);
            }
        }
        o.addProperty("n", links);
        o.add("t", t);
        if (anyDv) {
            o.add("dv", dv);
        }
        return o;
    }

    static void applyConfig(final Game g, final Player seat, final SpellAbility sa, final JsonElement e) {
        applyConfig(g, seat, sa, e, false);
    }

    /**
     * {@code charmModesLater} (bvw-decision-log-1027; the priority decision's own config only): a charm the AI chose
     * carries the modes its deliberation attached; a forced replay does not deliberate, and the play re-chooses the
     * modes (the recorded chooseModeForAbility) and sets their targets (the recorded "h" checkpoint). Then only the
     * charm's own part is set here. A play that does not re-choose them leaves those recorded decisions unused: a
     * divergence at the play, never a silent difference.
     */
    static void applyConfig(final Game g, final Player seat, final SpellAbility sa, final JsonElement e,
            final boolean charmModesLater) {
        checkEncodable(e);
        final JsonObject o = e.getAsJsonObject();
        final int host = o.get("h").getAsInt();
        if (sa.getHostCard() != null && sa.getHostCard().getId() != host) {
            throw new IllegalStateException("the record's ability is card " + host + "'s, the game's is card "
                    + sa.getHostCard().getId() + "'s");
        }
        int links = 0;
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            links++;
        }
        final boolean modesLater = charmModesLater && sa.getApi() == forge.game.ability.ApiType.Charm
                && links < o.get("n").getAsInt();
        if (links != o.get("n").getAsInt() && !modesLater) {
            throw new IllegalStateException("the record's ability has " + o.get("n").getAsInt() + " parts, the game's " + links);
        }
        if (o.has("x")) {
            sa.setXManaCostPaid(o.get("x").getAsInt());
        }
        final JsonArray t = o.getAsJsonArray("t");
        final JsonArray dv = o.has("dv") ? o.getAsJsonArray("dv") : null;
        int i = 0;
        for (SpellAbility s = sa; s != null; s = s.getSubAbility(), i++) {
            final JsonElement ti = t.get(i);
            if (ti.isJsonNull() || (ti.isJsonPrimitive() && "other".equals(ti.getAsString()))) {
                continue;
            }
            if (!s.usesTargeting()) {
                throw new IllegalStateException("part " + i + " is targeted in the record, not in the game");
            }
            final List<GameObject> want = derefs(g, ti);
            final JsonArray di = dv == null || dv.get(i).isJsonNull() ? null : dv.get(i).getAsJsonArray();
            if (sameTargets(s, want) && di == null) {
                continue;
            }
            s.resetTargets();
            for (int k = 0; k < want.size(); k++) {
                final GameObject x = want.get(k);
                if (!s.canTarget(x)) {
                    throw new IllegalStateException("the record's target " + ref(g, x) + " is not a legal target of part " + i);
                }
                s.getTargets().add(x);
                if (di != null && !di.get(k).isJsonNull()) {
                    s.addDividedAllocation(x, di.get(k).getAsInt());
                }
            }
        }
    }

    private static boolean sameTargets(final SpellAbility s, final List<GameObject> want) {
        if (s.getTargets().size() != want.size()) {
            return false;
        }
        int k = 0;
        for (GameObject x : s.getTargets()) {
            if (x != want.get(k++)) {
                return false;
            }
        }
        return true;
    }
}
