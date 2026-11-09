package forge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.util.IdScope;

import java.util.ArrayList;
import java.util.List;

/**
 * The AI tape's priority decision (lane replay-record-1021; see {@link AiTape}): the spell or ability the seat plays, as
 * {@code {h: host card, d: description hash, oc: optional costs, nm: without mana cost, x, kw: optional keyword amounts,
 * cfe: cast from a play effect, sid: the ids of its parts, cfg: its targets}}. Decoding finds it among what the host card
 * offers now (its abilities, their alternative and additional costs, with the recorded optional costs added), in a
 * detached id scope so that building the candidates never moves the game's own id counters, and gives a rebuilt variant
 * the ids the live one had.
 */
final class AiTapePlays {
    private AiTapePlays() {
    }

    /** The AI tape's per-link parameter the forced-win solver edits on its copies (Gitaxian Probe's life payment). */
    private static final String PHYREXIAN = "AIPhyrexianPayment";

    static JsonObject encode(final Game g, final Player seat, final SpellAbility sa) {
        final JsonObject o = new JsonObject();
        final Card host = sa.getHostCard();
        o.addProperty("h", host == null ? -1 : host.getId());
        o.addProperty("d", AiTapeCodec.descOf(sa));
        if (sa.isLandAbility()) {
            o.addProperty("land", true);
        }
        final JsonArray oc = new JsonArray();
        for (OptionalCost c : sa.getRootAbility().getOptionalCosts()) {
            oc.add(c.name());
        }
        if (!oc.isEmpty()) {
            o.add("oc", oc);
        }
        if (sa.hasParam("WithoutManaCost")) {
            o.addProperty("nm", true);
        }
        if (sa.getXManaCostPaid() != null) {
            o.addProperty("x", sa.getXManaCostPaid());
        }
        if (host != null && !sa.getOptionalKeywords().isEmpty()) {
            final JsonArray kw = new JsonArray();
            for (KeywordInterface k : host.getKeywords()) {
                if (sa.hasOptionalKeywordAmount(k)) {
                    final JsonArray row = new JsonArray();
                    row.add(k.getKeyword().name());
                    row.add(AiTapeCodec.h(k.getOriginal()));
                    row.add(sa.getOptionalKeywordAmount(k));
                    kw.add(row);
                }
            }
            if (!kw.isEmpty()) {
                o.add("kw", kw);
            }
        }
        if (sa.isCastFromPlayEffect()) {
            o.addProperty("cfe", true);
        }
        final JsonArray sid = new JsonArray();
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            sid.add(s.getId());
        }
        o.add("sid", sid);
        if (AiTape.EXTENDED && sa.getCopyRoot() != 0) {
            // bvw-decision-log-1027: a copy the AI made (the forced-win solver's line steps): the original it copies,
            // and per chain link what such a copy may carry that the original does not (reads only)
            o.addProperty("cp", sa.getCopyRoot());
            final JsonArray ph = new JsonArray(), ex = new JsonArray();
            boolean anyEx = false;
            for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
                ph.add(s.getMapParams().get(PHYREXIAN));
                final String x = s.getManaPart() == null ? null : s.getManaPart().getExpressChoice();
                anyEx |= x != null && !x.isEmpty();
                ex.add(x == null || x.isEmpty() ? null : x);
            }
            o.add("ph", ph);
            if (sa.hasParam(ComputerUtilMana.PHYREXIAN_LIFE_PARAM)) {
                o.addProperty("pl", true); // unified-jar-1106: the line paid this step's Phyrexian pips with life (followPay)
            }
            if (anyEx) {
                o.add("ex", ex);
            }
        }
        o.add("cfg", AiTapeCodec.config(g, seat, sa));
        return o;
    }

    static SpellAbility decode(final Game g, final Player seat, final JsonObject o) {
        return decode(g, seat, o, false, null);
    }

    static SpellAbility decode(final Game g, final Player seat, final JsonObject o, final boolean idsShifted) {
        return decode(g, seat, o, idsShifted, null);
    }

    /**
     * {@code idsShifted}: this replay's ability ids already ran ahead of the record's (the replay is then marked
     * inexact): every later id is offset, so an ability is matched by its description alone. Otherwise its id must be
     * the record's.
     */
    /**
     * {@code onIdShift} (codec-1103, class C; null = refuse as before): called when the record's ability is matched
     * although its id is not the record's (the replay is then inexact and later ids are matched as shifted).
     */
    static SpellAbility decode(final Game g, final Player seat, final JsonObject o, final boolean idsShifted,
            final Runnable onIdShift) {
        final int hostId = o.get("h").getAsInt();
        final Card host = (Card) AiTapeCodec.deref(g, "c" + hostId);
        final String d = AiTape.str(o, "d");
        final List<String> oc = new ArrayList<>();
        if (o.has("oc")) {
            for (JsonElement e : o.getAsJsonArray("oc")) {
                oc.add(e.getAsString());
            }
        }
        final boolean nm = o.has("nm") && o.get("nm").getAsBoolean();
        final int sid0 = o.has("sid") && o.getAsJsonArray("sid").size() > 0 ? o.getAsJsonArray("sid").get(0).getAsInt() : -1;
        final List<SpellAbility> found = IdScope.detached(AiTape.DECODE_BASE, () -> find(host, seat, d, oc, nm));
        // ai-timeout-1108 (ledger row 77): a record's COPY (cp) need not look like any ability the card offers (the copy's
        // description is the copy's own); it is rebuilt from its original below (the copied branch), so only a play that is
        // not a copy is refused here. Before, every such copy diverged here although its original was on the card.
        if (found.isEmpty() && !o.has("cp")) {
            throw new IllegalStateException("card " + hostId + " offers no ability like the record's (" + d + ")"
                    + offered(host, seat));
        }
        // the record's own ability by its id (review forge-mtgx#110 item 6: two abilities may share a description), else a
        // variant rebuilt here; an existing ability of another id is not the record's
        SpellAbility pick = null;
        for (SpellAbility c : found) {
            if (c.getId() == sid0) {
                pick = c;
                break;
            }
        }
        if (pick == null) {
            for (SpellAbility c : found) {
                if (AiTape.decodeId(c.getId())) {
                    pick = c;
                    break;
                }
            }
        }
        if (pick == null && o.has("cp") && onIdShift != null) {
            // codec-1103 (class C): an alternative-cost or optional-cost variant (overload, ...) is a copy of the card's
            // ability, built again here by find() as a look-alike of the record's; the one whose copy root is the record's
            // original (and only it) is the record's play
            final int cp = o.get("cp").getAsInt();
            SpellAbility only = null;
            int n = 0;
            for (SpellAbility c : found) {
                if (c.getCopyRoot() == cp || (c.getRootAbility() != null && c.getRootAbility().getCopyRoot() == cp)) {
                    only = c;
                    n++;
                }
            }
            if (n == 1) {
                pick = only;
            }
        }
        final boolean copied = pick == null && o.has("cp");
        if (copied) {
            // bvw-decision-log-1027: the AI played a copy of one of the card's abilities: copy that one again here (in
            // the decode scope; it takes the live copy's ids below), never a look-alike of another id
            final SpellAbility base = original(host, seat, o.get("cp").getAsInt(), d, found);
            if (base == null) {
                throw new IllegalStateException("card " + hostId + " no longer has ability " + o.get("cp").getAsInt()
                        + ", which the record's play (" + d + ") was a copy of" + offered(host, seat));
            }
            pick = IdScope.detached(AiTape.DECODE_BASE, () -> base.copy(seat));
            if (!AiTapeCodec.descOf(pick).equals(d) && onIdShift != null) {
                // codec-1103 (class C): the record's play was a cost variant of that ability (overload, ...): its one
                // alternative-cost variant with the record's description
                final List<SpellAbility> alts = IdScope.detached(AiTape.DECODE_BASE,
                        () -> GameActionUtil.getAlternativeCosts(base, seat, false));
                SpellAbility only = null;
                int n = 0;
                for (SpellAbility a : alts) {
                    if (AiTapeCodec.descOf(a).equals(d)) {
                        only = a;
                        n++;
                    }
                }
                if (n == 0) {
                    // ai-timeout-1108 (ledger row 77, 4 of walk-t1's 9 AiTape games): a play another card lets the AI make
                    // (Yawgmoth's Will, Lurrus, Serra Paragon, an adventure in exile) that the live game built from such a
                    // variant again, so its description carries the grant twice ("... by X (n) by X (n)"): the one variant of
                    // a variant with the record's description
                    for (SpellAbility a : alts) {
                        for (SpellAbility a2 : IdScope.detached(AiTape.DECODE_BASE,
                                () -> GameActionUtil.getAlternativeCosts(a, seat, false))) {
                            if (AiTapeCodec.descOf(a2).equals(d)) {
                                only = a2;
                                n++;
                            }
                        }
                    }
                }
                if (n == 1) {
                    pick = only;
                }
            }
            if (!AiTapeCodec.descOf(pick).equals(d)) {
                throw new IllegalStateException("card " + hostId + "'s ability " + o.get("cp").getAsInt()
                        + " copies to another ability than the record's (" + d + "): " + AiTapeCodec.descOf(pick) + " \""
                        + clip(pick.getDescription()) + "\"" + offered(host, seat));
            }
        }
        if (pick == null && sid0 >= 0 && !idsShifted && onIdShift != null && found.size() == 1) {
            // codec-1103 (class C): the card has exactly one ability like the record's, on another id (the record's id
            // counter ran one ahead in Forge AI's deliberation, which a forced replay does not run): it is the record's
            // play; the replay is marked inexact, and later ids are matched as shifted
            pick = found.get(0);
            onIdShift.run();
        }
        if (pick == null && sid0 >= 0 && !idsShifted) {
            throw new IllegalStateException("card " + hostId + "'s ability like the record's has id " + found.get(0).getId()
                    + ", the record's " + sid0);
        }
        final SpellAbility sa = pick != null ? pick : found.get(0);
        // a variant rebuilt here (scoped ids) takes the live variant's ids
        if (o.has("sid")) {
            final JsonArray sid = o.getAsJsonArray("sid");
            int i = 0;
            for (SpellAbility s = sa; s != null && i < sid.size(); s = s.getSubAbility(), i++) {
                if (AiTape.decodeId(s.getId())) {
                    s.dangerouslySetId(sid.get(i).getAsInt());
                }
            }
        }
        if (copied) {
            copyFields(sa, o);
        }
        sa.setActivatingPlayer(seat);
        if (o.has("x")) {
            sa.setXManaCostPaid(o.get("x").getAsInt());
        }
        if (o.has("kw")) {
            for (JsonElement r : o.getAsJsonArray("kw")) {
                final JsonArray row = r.getAsJsonArray();
                boolean set = false;
                for (KeywordInterface k : host.getKeywords()) {
                    if (k.getKeyword().name().equals(row.get(0).getAsString())
                            && AiTapeCodec.h(k.getOriginal()).equals(row.get(1).getAsString())) {
                        sa.setOptionalKeywordAmount(k, row.get(2).getAsInt());
                        set = true;
                        break;
                    }
                }
                if (!set) {
                    throw new IllegalStateException("card " + hostId + " no longer has the record's optional keyword");
                }
            }
        }
        if (o.has("cfe")) {
            sa.setCastFromPlayEffect(true);
        }
        AiTapeCodec.applyConfig(g, seat, sa, o.get("cfg"), true);
        if (!sa.canPlay()) {
            throw new IllegalStateException("card " + hostId + "'s recorded ability (" + d + ") cannot be played now");
        }
        return sa;
    }

    /**
     * bvw-decision-log-1027: the card's ability of id {@code id} (among {@code found} first); else its one ability whose
     * description is {@code d} (an ability a static grants is rebuilt, on new ids, whenever statics are re-applied, which
     * the AI's deliberation does on the live game; the copy is of its content); else null.
     */
    private static SpellAbility original(final Card host, final Player seat, final int id, final String d,
            final List<SpellAbility> found) {
        for (SpellAbility c : found) {
            if (c.getId() == id) {
                return c;
            }
        }
        return IdScope.detached(AiTape.DECODE_BASE, () -> {
            final List<SpellAbility> all = new ArrayList<>(host.getAllPossibleAbilities(seat, false));
            all.addAll(host.getSpellAbilities());
            all.addAll(host.getManaAbilities());
            for (SpellAbility c : all) {
                if (c.getId() == id) {
                    return c;
                }
            }
            SpellAbility like = null;
            for (SpellAbility c : all) {
                if (AiTapeCodec.descOf(c).equals(d) && c != like) {
                    if (like != null && like.getId() != c.getId()) {
                        return null; // two look-alikes: not the record's to pick
                    }
                    like = c;
                }
            }
            return like;
        });
    }

    /** bvw-decision-log-1027: what the record's copy carried per chain link ({@link #encode}'s ph / ex), set on ours. */
    private static void copyFields(final SpellAbility sa, final JsonObject o) {
        if (o.has("pl")) {
            sa.getMapParams().put(ComputerUtilMana.PHYREXIAN_LIFE_PARAM, "true"); // unified-jar-1106: as the record's copy
        }
        final JsonArray ph = o.has("ph") ? o.getAsJsonArray("ph") : null;
        final JsonArray ex = o.has("ex") ? o.getAsJsonArray("ex") : null;
        int i = 0;
        for (SpellAbility s = sa; s != null; s = s.getSubAbility(), i++) {
            if (ph != null && i < ph.size()) {
                if (ph.get(i).isJsonNull()) {
                    s.getMapParams().remove(PHYREXIAN);
                } else {
                    s.getMapParams().put(PHYREXIAN, ph.get(i).getAsString());
                }
            }
            if (ex != null && i < ex.size() && !ex.get(i).isJsonNull()) {
                if (s.getManaPart() == null) {
                    throw new IllegalStateException("the record's copy chose a mana colour on a link without mana");
                }
                s.getManaPart().setExpressChoice(ex.get(i).getAsString());
            }
        }
    }

    /** ai-timeout-1108: a divergence's diagnostics: the card, its zone and what it offers (hash and text). Reads only. */
    private static String offered(final Card host, final Player seat) {
        if (host == null) {
            return "; no such card";
        }
        final StringBuilder sb = new StringBuilder("; card ").append(host.getName()).append(" in ").append(host.getZone() == null
                ? "?" : host.getZone().getZoneType()).append(" offers");
        try {
            IdScope.detached(AiTape.DECODE_BASE, () -> {
                final List<SpellAbility> all = new ArrayList<>(host.getAllPossibleAbilities(seat, false));
                for (SpellAbility c : host.getSpellAbilities()) {
                    if (!all.contains(c)) {
                        all.add(c);
                    }
                }
                int n = 0;
                for (SpellAbility c : all) {
                    if (n++ >= 8) {
                        sb.append(" ...");
                        break;
                    }
                    sb.append(" [").append(c.getId()).append(' ').append(AiTapeCodec.descOf(c)).append(" \"")
                            .append(clip(c.getDescription())).append("\"]");
                }
                return null;
            });
        } catch (RuntimeException e) {
            sb.append(" (unreadable: ").append(e).append(')');
        }
        return sb.toString();
    }

    private static String clip(final String t) {
        if (t == null) {
            return "null";
        }
        final String one = t.replace('\n', ' ');
        return one.length() > 120 ? one.substring(0, 120) + "..." : one;
    }

    private static List<SpellAbility> find(final Card host, final Player seat, final String d, final List<String> oc, final boolean nm) {
        final List<SpellAbility> out = new ArrayList<>();
        final List<SpellAbility> candidates = new ArrayList<>();
        for (SpellAbility base : host.getAllPossibleAbilities(seat, false)) {
            base.setActivatingPlayer(seat);
            candidates.addAll(GameActionUtil.getAdditionalCostSpell(base));
        }
        for (SpellAbility base : host.getSpellAbilities()) {
            if (!candidates.contains(base)) {
                base.setActivatingPlayer(seat);
                candidates.add(base);
            }
        }
        for (SpellAbility c : candidates) {
            SpellAbility v = c;
            if (nm && !v.hasParam("WithoutManaCost")) {
                v = v.copyWithNoManaCost(seat);
            }
            // bvw-decision-log-1027: a candidate may carry optional costs already (an alternative-cost variant is built
            // with AltCost); only the record's other ones are added, and one the record's play did not have refuses it
            final List<String> have = new ArrayList<>();
            for (OptionalCost c0 : v.getRootAbility().getOptionalCosts()) {
                have.add(c0.name());
            }
            if (!oc.containsAll(have)) {
                continue;
            }
            final List<String> missing = new ArrayList<>(oc);
            missing.removeAll(have);
            if (!missing.isEmpty()) {
                final List<OptionalCostValue> vals = new ArrayList<>();
                for (OptionalCostValue ocv : GameActionUtil.getOptionalCostValues(v)) {
                    if (missing.contains(ocv.getType().name())) {
                        vals.add(ocv);
                    }
                }
                if (vals.size() != missing.size()) {
                    continue;
                }
                v = GameActionUtil.addOptionalCosts(v, vals);
            }
            if (AiTapeCodec.descOf(v).equals(d)) {
                out.add(v);
            }
        }
        return out;
    }
}
