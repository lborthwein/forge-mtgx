package forge.bench.rl;

import java.util.ArrayList;
import java.util.List;

import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.trigger.Trigger;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

/**
 * Observation v2 (lane rl-obs-v2-1006, gap 1): what a person sees about a spell or ability on the stack beyond its
 * card: its targets (with the sub-ability depth and any divided amount), its X, its chosen modes, kicker, alternative
 * and optional costs, the zone it was cast from, which ability of its host it is, and whether it is a trigger. Read-only
 * on Forge state. Used for live stack items and, recorded at cast, for the event tail.
 */
public final class RlStackFacts {
    private RlStackFacts() {
    }

    public static final int MAX_DEPTH = 7;
    /** Target kinds in {@link #targetRefs}: a card (ref = card id), a player (ref = seat), a stack item (ref = its id). */
    public static final int T_CARD = 0, T_PLAYER = 1, T_STACK = 2;

    /** The stack facts of a spell or ability, flattened (fact id, arg, num) triples. */
    public static int[] facts(final SpellAbility root, final Card host) {
        final List<int[]> out = new ArrayList<>();
        if (root == null) {
            return new int[0];
        }
        try {
            final Integer x = root.getXManaCostPaid();
            if (x != null) {
                out.add(new int[] {RlSchemaV2.F_X, 0, Math.min(x, Short.MAX_VALUE)});
            }
            if (root.getApi() == ApiType.Charm) {
                final List<AbilitySub> choices = root.getAdditionalAbilityList("Choices");
                final List<AbilitySub> chosen = root.getChosenList();
                if (choices != null && chosen != null) {
                    for (AbilitySub ch : chosen) {
                        int i = choices.indexOf(ch);
                        if (i < 0) {
                            final String d = String.valueOf(ch.getDescription());
                            for (int k = 0; k < choices.size() && i < 0; k++) {
                                if (d.equals(String.valueOf(choices.get(k).getDescription()))) {
                                    i = k;
                                }
                            }
                        }
                        if (i >= 0 && i < RlSchemaV2.N_MODE_FACTS) {
                            out.add(new int[] {RlSchemaV2.F_MODE0 + i, 0, -1});
                        }
                    }
                }
            }
            int kicks = 0;
            for (OptionalCost oc : root.getOptionalCosts()) {
                if (oc == OptionalCost.Kicker1 || oc == OptionalCost.Kicker2) {
                    kicks++;
                }
                final int id = RlSchemaV2.factId("OPT:" + oc.name());
                if (id > 0) {
                    out.add(new int[] {id, 0, -1});
                }
            }
            if (kicks > 0) {
                out.add(new int[] {RlSchemaV2.F_KICKED, 0, kicks});
            }
            final AlternativeCost ac = root.getAlternativeCost();
            if (ac != null) {
                final int id = RlSchemaV2.factId("ALT:" + ac.name());
                if (id > 0) {
                    out.add(new int[] {id, 0, -1});
                }
            }
            if (root.isSpell() && host != null) {
                final Zone from = host.getCastFrom();
                final ZoneType zt = from == null ? null : from.getZoneType();
                if (zt == ZoneType.Graveyard || zt == ZoneType.Exile || zt == ZoneType.Library
                        || zt == ZoneType.Command) {
                    out.add(new int[] {RlSchemaV2.factId("CAST_FROM:" + zt.name()), 0, -1});
                }
            }
            if (!root.isSpell()) {
                out.add(new int[] {RlSchemaV2.F_ABILITY, 0, abilityIndex(host, root)});
            }
            if (root.isTrigger()) {
                out.add(new int[] {RlSchemaV2.F_TRIGGERED, 0, -1});
            }
        } catch (RuntimeException e) {
            // a stack item whose details Forge cannot give keeps the facts found so far
        }
        final int[] flat = new int[out.size() * 3];
        for (int i = 0; i < out.size(); i++) {
            System.arraycopy(out.get(i), 0, flat, 3 * i, 3);
        }
        return flat;
    }

    /** The ability's index on its host: a trigger among the host's triggers, else the cand_ability rule; 0-15. */
    static int abilityIndex(final Card host, final SpellAbility sa) {
        if (host == null || sa == null) {
            return 0;
        }
        if (sa.isTrigger() && sa.getTrigger() != null) {
            int i = 0;
            for (Trigger t : host.getTriggers()) {
                if (t == sa.getTrigger()) {
                    return Math.min(15, i);
                }
                i++;
            }
        }
        return Math.min(15, RlCandidates.abilityIndex(host, sa));
    }

    /** The targets of a spell or ability (every sub-ability), each {kind, ref, depth, divided amount or -1}. */
    public static List<int[]> targetRefs(final Game game, final SpellAbility root) {
        final List<int[]> out = new ArrayList<>();
        int depth = 0;
        try {
            for (SpellAbility x = root; x != null; x = x.getSubAbility(), depth++) {
                final TargetChoices tc = x.getTargets();
                if (tc == null) {
                    continue;
                }
                final boolean divided = x.isDividedAsYouChoose();
                for (GameObject t : tc) {
                    final Integer dv = divided ? tc.getDividedValue(t) : null;
                    final int div = dv == null ? -1 : dv;
                    final int d = Math.min(depth, MAX_DEPTH);
                    if (t instanceof Card) {
                        out.add(new int[] {T_CARD, ((Card) t).getId(), d, div});
                    } else if (t instanceof Player) {
                        out.add(new int[] {T_PLAYER, game.getRegisteredPlayers().indexOf(t), d, div});
                    } else if (t instanceof SpellAbility) {
                        final SpellAbility ts = (SpellAbility) t;
                        for (SpellAbilityStackInstance si : game.getStack()) {
                            if (si.getSpellAbility() == ts || si.getSpellAbility() == ts.getRootAbility()) {
                                out.add(new int[] {T_STACK, si.getId(), d, div});
                                break;
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // keep the targets found so far
        }
        return out;
    }
}
