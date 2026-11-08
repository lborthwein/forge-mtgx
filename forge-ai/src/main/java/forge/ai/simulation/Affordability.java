package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;

import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.CostPart;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * endstep-rows-1103 (measurement only; owner ruling 11-03 "affordable rows"): does a mana path exist for a two-step setup
 * line from this position, by the actor's own knowledge (its hand and battlefield; never its library or the next draw)?
 * Step 1 (now, the opponent's end step): cast an enabler from hand. Step 2 (our next turn): cast the payoff from hand, or the
 * payoff is already in play. Mana is read from Forge's mana-ability data, never from card names:
 * <ul>
 * <li>now: each untapped permanent's mana ability with a tap cost (no summoning sickness for creatures), one unit of the
 * colours it can produce; a source whose cost sacrifices it counts now only;</li>
 * <li>from hand, now and next turn: a permanent with mana cost 0 and a mana ability adds a unit; a mana permanent whose
 * production exceeds its cost adds the difference; a source sacrificed for mana, or a spell whose effect is mana
 * (ApiType.Mana), adds its net production to ONE of the two windows;</li>
 * <li>next turn: every non-sacrificed source untaps (creatures lose sickness), plus one land from hand (land drop).</li>
 * </ul>
 * Colours are matched: each coloured shard needs a unit able to make it (fewest-colour units first), then generic from the
 * rest; hybrid shards accept either colour; Phyrexian shards are paid with life. The enabler's and payoff's NAMES come from
 * the caller (the frozen selection's measurement lists); the mana rule itself knows no card.
 */
public final class Affordability {
    private static final String[] COL = {"W", "U", "B", "R", "G", "C"};

    private Affordability() {
    }

    /** One mana unit: a mask over W U B R G C. */
    private static int mask(final SpellAbility ma) {
        int m = 0;
        for (int i = 0; i < 6; i++) {
            try {
                if (ma.canProduce(COL[i])) m |= 1 << i;
            } catch (RuntimeException e) {
                // a source Forge cannot test here makes nothing
            }
        }
        return m;
    }

    private static int amount(final SpellAbility ma) {
        try {
            final String a = ma.getManaPart() == null ? null : ma.getManaPart().getOrigProduced();
            if (a == null) return 1;
            final String[] t = a.trim().split(" ");
            int n = 0;
            for (String x : t) if (!x.isEmpty()) n++;
            return Math.max(1, n);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static boolean sacrifices(final SpellAbility ma) {
        if (ma.getPayCosts() == null) return false;
        for (CostPart p : ma.getPayCosts().getCostParts()) if (p instanceof CostSacrifice) return true;
        return false;
    }

    private static boolean taps(final SpellAbility ma) {
        if (ma.getPayCosts() == null) return false;
        for (CostPart p : ma.getPayCosts().getCostParts()) if (p instanceof CostTap) return true;
        return false;
    }

    /** Units a battlefield card makes: {now (if untapped and able), next turn}. */
    private static void battlefield(final Card c, final List<Integer> now, final List<Integer> next) {
        // a source taps once: its abilities' colours are united (a dual land's two abilities), its largest amount taken
        int m = 0, n = 0;
        boolean sick = false, sac = true;
        for (SpellAbility ma : c.getManaAbilities()) {
            final int mm = mask(ma);
            if (mm == 0) continue;
            m |= mm;
            n = Math.max(n, amount(ma));
            sick |= c.isCreature() && c.hasSickness() && taps(ma);
            sac &= sacrifices(ma);
        }
        if (m == 0) return;
        for (int k = 0; k < n; k++) {
            if (!c.isTapped() && !sick) now.add(m);
            if (!sac) next.add(m);
        }
    }

    /**
     * Hand mana: a permanent that stays adds to {now, next}; a one-shot (a source sacrificed for its mana, a spell whose
     * effect is mana) goes to {@code once}: usable in ONE of the two windows.
     */
    private static void hand(final Card c, final List<Integer> now, final List<Integer> next, final List<Integer> once,
            final Set<String> skip) {
        if (skip.contains(c.getName())) return;
        final ManaCost cost = c.getManaCost();
        final int cmc = cost == null ? 0 : cost.getCMC();
        if (c.isPermanent() && !c.isLand() && !c.getManaAbilities().isEmpty()) {
            int um = 0;
            for (SpellAbility ma : c.getManaAbilities()) um |= mask(ma);
            for (SpellAbility ma : c.getManaAbilities()) {
                if (mask(ma) == 0) continue;
                final int m = um; // a source taps once: its abilities' colours united
                final int n = amount(ma);
                final int net = n - cmc;
                if (sacrifices(ma)) {
                    for (int k = 0; k < net; k++) once.add(m);
                } else {
                    for (int k = 0; k < net; k++) {
                        now.add(m);
                        next.add(m);
                    }
                }
                return;
            }
        }
        for (SpellAbility sa : c.getSpells()) {
            if (sa.getApi() == ApiType.Mana) {
                int m = mask(sa);
                if (m == 0) m = 1 << 5;
                final int net = amount(sa) - cmc;
                for (int k = 0; k < net; k++) once.add(m);
                return;
            }
        }
    }

    /** Can {@code units} pay {@code cost} (colours matched; Phyrexian by life)? */
    static boolean pays(final ManaCost cost, final List<Integer> units0) {
        if (cost == null) return true;
        final List<Integer> units = new ArrayList<>(units0);
        int generic = cost.getGenericCost();
        final List<int[]> shards = new ArrayList<>();
        for (ManaCostShard s : cost) {
            if (s == ManaCostShard.GENERIC || s == ManaCostShard.X) continue;
            if (s.isPhyrexian()) continue;
            int m = 0;
            for (int i = 0; i < 5; i++) if (s.canBePaidWithManaOfColor((byte) (1 << i))) m |= 1 << i;
            if (s.isColorless() && m == 0) m = 1 << 5;
            if (s.isOr2Generic() || s.isGeneric()) {
                generic += s.getCmc();
                continue;
            }
            shards.add(new int[] {m});
        }
        shards.sort((a, b) -> Integer.bitCount(a[0]) - Integer.bitCount(b[0]));
        for (int[] sh : shards) {
            int best = -1;
            for (int u = 0; u < units.size(); u++) {
                if ((units.get(u) & sh[0]) != 0 && (best < 0 || Integer.bitCount(units.get(u)) < Integer.bitCount(units.get(best)))) {
                    best = u;
                }
            }
            if (best < 0) return false;
            units.remove(best);
        }
        return units.size() >= generic;
    }

    /** Diagnostics: each battlefield/hand source's contribution "name:now/next/once" (no effect on the verdict). */
    public static String explain(final Player me) {
        final StringBuilder sb = new StringBuilder();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            final List<Integer> a = new ArrayList<>(), b = new ArrayList<>();
            battlefield(c, a, b);
            sb.append(c.getName()).append(':').append(a).append('/').append(b).append(' ');
        }
        return sb.toString();
    }

    /** The row's affordability JSON. */
    public static JsonObject of(final Player me, final Collection<String> enablers, final Collection<String> payoffs) {
        final List<Integer> now = new ArrayList<>(), next = new ArrayList<>(), once = new ArrayList<>();
        final Set<String> pieces = new java.util.HashSet<>(enablers);
        pieces.addAll(payoffs);
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) battlefield(c, now, next);
        int landMask = 0;
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            if (c.isLand()) {
                int lm = 0;
                for (SpellAbility ma : c.getManaAbilities()) lm |= mask(ma);
                if (Integer.bitCount(lm) > Integer.bitCount(landMask)) landMask = lm;
                continue;
            }
            hand(c, now, next, once, pieces);
        }
        final boolean landDrop = landMask != 0;
        if (landDrop) next.add(landMask);
        boolean enablerNow = false, payoffInPlay = false, payoffNext = false, both = false;
        String en = null, pay = null;
        final List<Integer> nowAll = new ArrayList<>(now), nextAll = new ArrayList<>(next);
        nowAll.addAll(once);
        nextAll.addAll(once);
        for (Card e : me.getCardsIn(ZoneType.Hand)) {
            if (!enablers.contains(e.getName())) continue;
            if (pays(e.getManaCost(), nowAll)) {
                enablerNow = true;
                en = e.getName();
            }
            for (Card p : me.getCardsIn(ZoneType.Hand)) {
                if (!payoffs.contains(p.getName())) continue;
                if (pays(p.getManaCost(), nextAll)) {
                    payoffNext = true;
                    pay = p.getName();
                }
                // the one-shot mana is spent in one window only
                if (pays(e.getManaCost(), nowAll) && pays(p.getManaCost(), next)
                        || pays(e.getManaCost(), now) && pays(p.getManaCost(), nextAll)) {
                    both = true;
                }
            }
        }
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (payoffs.contains(c.getName())) {
                payoffInPlay = true;
                pay = c.getName();
            }
        }
        final JsonObject o = new JsonObject();
        o.addProperty("now_units", now.size());
        o.addProperty("once_units", once.size());
        o.addProperty("next_units", next.size());
        o.addProperty("land_drop", landDrop);
        o.addProperty("enabler_now", enablerNow);
        o.addProperty("payoff_next", payoffNext);
        o.addProperty("payoff_in_play", payoffInPlay);
        o.addProperty("enabler", en);
        o.addProperty("payoff", pay);
        o.addProperty("affordable", payoffInPlay ? enablerNow : both);
        return o;
    }
}
