package forge.bench.rl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import forge.ai.ComputerUtilMana;
import forge.bench.CallCounter;
import forge.bench.PlayerControllerBridge;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostDamage;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostSacrifice;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;

/**
 * The mana source of one shard (lane r4-payment-1015): which of Forge AI's candidate sources it would accept for the
 * shard, grouped into materially different options. Reads only: every evaluation of Forge AI's own acceptance test
 * ({@link ComputerUtilMana#chooseManaAbility}, which draws from the random stream, reserves cards in AI memory and sets
 * colour choices) runs inside {@link PlayerControllerBridge#isolated}, so the live game is left exactly as it was.
 *
 * <p><b>Materially different.</b> Two sources are the same option when paying with either leaves the same mana
 * capability behind and costs the same: a plain land (a land, not a creature, with no activated ability besides its
 * mana abilities) is known by every colour its mana abilities can make (and snow), any other source by its name; both
 * add the paying ability's own cost, product and sub-abilities. So two Islands are one option, an Island and a
 * Volcanic Island are two (the Volcanic leaves red behind), Lotus Petal and a land are two (one is sacrificed).
 */
public final class RlManaChoice {
    private RlManaChoice() {
    }

    /** One materially distinct way to pay a shard: the first candidate of its class in Forge AI's order. */
    public static final class Option {
        /** The ability Forge AI's acceptance test returned (it may swap in a sibling ability, Cavern of Souls). */
        public final SpellAbility ma;
        /** The candidate it was tested as (one of Forge AI's candidates: what a chooser returns). */
        public final SpellAbility cand;
        public final String key;
        public int members = 1;
        public final boolean sacrifice;
        public final boolean life;
        public final boolean plainLand;
        /** The host's producible colours (letters of WUBRGC, in that order; "*" for special mana). */
        public final String colours;

        Option(final SpellAbility ma, final SpellAbility cand) {
            this.ma = ma;
            this.cand = cand;
            this.key = classKey(ma);
            final Cost c = ma.getPayCosts();
            this.sacrifice = c != null && c.hasSpecificCostType(CostSacrifice.class);
            this.life = c != null && (c.hasSpecificCostType(CostPayLife.class) || c.hasSpecificCostType(CostDamage.class))
                    || hurts(ma);
            this.plainLand = plainLand(ma.getHostCard());
            this.colours = colours(ma.getHostCard());
        }
    }

    /** The options of one shard. */
    public static final class Ask {
        public final List<Option> options = new ArrayList<>();
        /** The option holding Forge AI's own pick, or -1 (Forge AI would not pay this shard from a source). */
        public int forge = -1;
        /** Forge AI's own pick (null: none). */
        public SpellAbility forgePick;
        /** Candidates Forge AI listed / would accept. */
        public int candidates, acceptable;
        /** Distinct (name, ability) among the acceptable candidates: the naive count of choices. */
        public int names;

        public boolean contested() {
            return options.size() >= 2 && forge >= 0;
        }
    }

    /**
     * Forge AI's acceptable candidates for the shard, as options, and its own pick. Each acceptance test runs isolated,
     * one candidate at a time (as Forge AI's choice tests them in turn), then Forge AI's whole choice, also isolated.
     */
    public static Ask enumerate(final Game game, final Player ai, final ManaCostBeingPaid cost, final SpellAbility sa,
            final ManaCostShard toPay, final List<SpellAbility> candidates, final boolean checkCosts) {
        final Ask ask = new Ask();
        ask.candidates = candidates.size();
        final Map<String, Option> byKey = new LinkedHashMap<>();
        final TreeSet<String> names = new TreeSet<>();
        for (SpellAbility ma : candidates) {
            final SpellAbility ok = PlayerControllerBridge.isolated(game, () -> ComputerUtilMana.chooseManaAbility(cost,
                    sa, ai, toPay, new ArrayList<>(Collections.singletonList(ma)), checkCosts));
            if (ok == null) {
                continue;
            }
            ask.acceptable++;
            names.add(ok.getHostCard().getName() + "|" + abilityText(ok));
            final String k = classKey(ok);
            final Option o = byKey.get(k);
            if (o == null) {
                byKey.put(k, new Option(ok, ma));
            } else {
                o.members++;
            }
        }
        ask.names = names.size();
        ask.options.addAll(byKey.values());
        final SpellAbility pick = PlayerControllerBridge.isolated(game, () -> ComputerUtilMana.chooseManaAbility(cost,
                sa, ai, toPay, new ArrayList<>(candidates), checkCosts));
        ask.forgePick = pick;
        if (pick != null) {
            final String k = classKey(pick);
            for (int i = 0; i < ask.options.size(); i++) {
                if (ask.options.get(i).key.equals(k)) {
                    ask.forge = i;
                    break;
                }
            }
            if (ask.forge < 0) {
                ask.options.add(new Option(pick, pick));
                ask.forge = ask.options.size() - 1;
            }
        }
        return ask;
    }

    /**
     * Whether every candidate is one option before any acceptance test (one class key): then the shard cannot be
     * contested, whatever Forge AI would accept. Reads only; lets the ask skip the isolated enumeration.
     */
    public static boolean oneOption(final List<SpellAbility> candidates) {
        String k0 = null;
        for (SpellAbility ma : candidates) {
            final String k = classKey(ma);
            if (k0 == null) {
                k0 = k;
            } else if (!k0.equals(k)) {
                return false;
            }
        }
        return true;
    }

    /** The class key of a source (see the class note). */
    public static String classKey(final SpellAbility ma) {
        final Card c = ma.getHostCard();
        final String ab = abilityText(ma);
        if (plainLand(c)) {
            return "L:" + colours(c) + (c.isSnow() ? "s" : "") + "|" + ab;
        }
        return "N:" + c.getName() + "|" + ab;
    }

    /** The paying ability's cost, product, amount and sub-abilities. */
    static String abilityText(final SpellAbility ma) {
        final StringBuilder sb = new StringBuilder();
        final Cost c = ma.getPayCosts();
        sb.append(c == null ? "" : c.toSimpleString()).append('>');
        final AbilityManaPart mp = ma.getManaPart();
        sb.append(mp == null ? "" : mp.getOrigProduced());
        if (ma.hasParam("Amount")) {
            sb.append('x').append(ma.getParam("Amount"));
        }
        for (SpellAbility s = ma.getSubAbility(); s != null; s = s.getSubAbility()) {
            sb.append('+').append(s.getApi() == null ? "?" : s.getApi().name());
        }
        return sb.toString();
    }

    /** A land, not a creature, with no activated ability besides mana abilities. */
    static boolean plainLand(final Card c) {
        if (c == null || !c.isLand() || c.isCreature()) {
            return false;
        }
        for (SpellAbility s : c.getNonManaAbilities()) {
            if (s.isActivatedAbility()) {
                return false;
            }
        }
        return true;
    }

    /** Every colour the card's mana abilities can make, as letters of WUBRGC in that order ("*" = special mana). */
    static String colours(final Card c) {
        final boolean[] has = new boolean[6];
        boolean special = false;
        if (c != null) {
            for (SpellAbility ma : c.getManaAbilities()) {
                final AbilityManaPart mp = ma.getManaPart();
                if (mp == null) {
                    continue;
                }
                String p;
                if (mp.isAnyMana()) {
                    p = "W U B R G";
                } else if (mp.isComboMana()) {
                    p = mp.getComboColors(ma);
                } else if (mp.isSpecialMana()) {
                    special = true;
                    continue;
                } else {
                    p = mp.getOrigProduced();
                    if (p.contains("Chosen")) {
                        p = p.replace("Chosen", mp.getChosenColor(ma));
                    }
                }
                for (String t : p.split("[ ,]+")) {
                    final int i = "WUBRGC".indexOf(t);
                    if (t.length() == 1 && i >= 0) {
                        has[i] = true;
                    }
                }
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (has[i]) {
                sb.append("WUBRGC".charAt(i));
            }
        }
        if (special) {
            sb.append('*');
        }
        return sb.toString();
    }

    /** A sub-ability that deals damage to, or makes lose life, the source's controller (painlands, Ancient Tomb). */
    static boolean hurts(final SpellAbility ma) {
        for (SpellAbility s = ma.getSubAbility(); s != null; s = s.getSubAbility()) {
            if (s.getApi() == forge.game.ability.ApiType.DealDamage || s.getApi() == forge.game.ability.ApiType.LoseLife) {
                return true;
            }
        }
        return false;
    }

    /** A strict subset of colour letters (the narrower source). */
    static boolean narrower(final String a, final String b) {
        if (a.length() >= b.length()) {
            return false;
        }
        for (char ch : a.toCharArray()) {
            if (b.indexOf(ch) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The census (lane r4-payment-1015; instruments {@code pay.*} of the paying seat's counter): one shard's options.
     * {@code newPayment}: the first shard of this cost object; {@code firstContested}: no earlier shard of this payment
     * was contested.
     */
    public static void census(final CallCounter cc, final Ask a, final SpellAbility sa, final boolean newPayment,
            final boolean firstContested, final long enumNanos) {
        cc.instrument("pay.shards");
        if (newPayment) {
            cc.instrument("pay.payments");
        }
        cc.instrument("pay.enumUs", (int) Math.min(Integer.MAX_VALUE, enumNanos / 1000));
        if (a.names >= 2) {
            cc.instrument("pay.names");
        }
        if (!a.contested()) {
            return;
        }
        cc.instrument("pay.contested");
        if (firstContested) {
            cc.instrument("pay.contestedPayments");
        }
        cc.instrument("pay.options", a.options.size());
        boolean colour = false, sac = false, life = false, kind = false;
        final Option f = a.options.get(a.forge);
        boolean forgeSac = false, forgeLife = false, forgeWide = false;
        for (Option o : a.options) {
            colour |= !o.colours.equals(f.colours);
            sac |= o.sacrifice != f.sacrifice;
            life |= o.life != f.life;
            kind |= o.plainLand != f.plainLand;
            forgeSac |= f.sacrifice && !o.sacrifice;
            forgeLife |= f.life && !o.life;
            forgeWide |= narrower(o.colours, f.colours);
        }
        if (colour) {
            cc.instrument("pay.differs.colour");
        }
        if (sac) {
            cc.instrument("pay.differs.sac");
        }
        if (life) {
            cc.instrument("pay.differs.life");
        }
        if (kind) {
            cc.instrument("pay.differs.kind");
        }
        if (!colour && !sac && !life && !kind) {
            cc.instrument("pay.differs.other");
        }
        if (forgeSac) {
            cc.instrument("pay.forge.sac");
        }
        if (forgeLife) {
            cc.instrument("pay.forge.life");
        }
        if (forgeWide) {
            cc.instrument("pay.forge.wide");
        }
        final Card host = sa == null ? null : sa.getHostCard();
        cc.instrument("paySpell:" + (host == null ? "?" : host.getName()));
    }
}
