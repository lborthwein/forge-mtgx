package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import forge.game.GameEntity;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * line-own-target-1119 ({@link ForcedWinSolver.Config#lineOwnTarget}; false = off, and then nothing here runs): may the
 * live seat play a followed line's step now, or would its harmful effect land on one of our own permanents that the
 * line did not plan, or would its destroy effect do nothing?
 *
 * <p>Owner reports 2026-11-19T01-05-37 and 01-14-57. Proven ENGINE lines held removal steps that did nothing for the
 * win in the solver's copies: Abrade (modal; its mode recorded, its target left to Forge AI as the spell is cast) at our
 * own undamaged 5/4, the only creature, and Snuff Out at our own indestructible Ulamog (a recorded target). Live, the
 * first had become lethal to our creature (the opponent's copied Chain Lightning had marked 3 damage on it) and the
 * second paid 4 life for nothing. The engine search offers our own permanents as targets of any single-target ability
 * (an untap or a blink needs them), and a winning path keeps the moves that did not matter.
 *
 * <p>Harmful: damage, destroy, -N/-N (a negative toughness pump, -1/-1 counters), sacrifice by effect, and a move from
 * the battlefield to any zone but the battlefield (exile, graveyard, library, a bounce to hand) that does not come back.
 * "Ours" is by CONTROLLER, never owner: a stolen or borrowed permanent we control is ours, one we own that the opponent
 * controls is theirs. A step is refused (the line breaks before it is cast) when:
 * <ul>
 * <li>a destroy's target cannot be destroyed (indestructible), whoever controls it, or every legal target of an open
 * destroy (one Forge AI picks as the spell is cast) cannot;</li>
 * <li>a recorded target (an ENGINE step's) is a permanent we control, unless the line planned that object (a
 * probeObjects step's {@link ForcedWinPlanner.Step#objTarget}) or the effect pays off (a permanent we control triggers on
 * that card dying or leaving the battlefield, on it being destroyed or sacrificed, or, for damage, on it being dealt
 * damage; a bounce of a card with its own enters trigger);</li>
 * <li>an open target's only legal choices are permanents we control (or us), none of them planned or paying off.</li>
 * </ul>
 * The policy search's ownHarm key ({@link LookaheadSearch.Config#ownHarm}) reads the same rule over its candidates
 * through {@link #refuseOpen}: every target open, a modal spell refused only when every mode it could take is.
 * Never refused: an ability whose targets can only be ours ("target creature you control": a blink, a sacrifice outlet),
 * costs (a sacrifice cost is not a target), and effects that are not harmful.
 */
final class LineOwnTarget {
    private LineOwnTarget() {
    }

    /** The harmful effects read here. */
    enum Harm { DAMAGE, DESTROY, SHRINK, REMOVE, BOUNCE, SACRIFICE }

    /**
     * Why the located step ability {@code sa} (its recorded targets set; a modal spell's modes not yet chosen) must not be
     * played by {@code me} now; null = it may. {@code modeTok}: the step's recorded mode set ({@link ForcedWinChoices#MODE},
     * replayed by the follower under engineModeChoice), or null when Forge AI chooses the modes. {@code planned}: the
     * permanents the line planned this step on (null = none). Never throws.
     */
    static String refuse(final Player me, final SpellAbility sa, final String modeTok, final Set<Integer> planned) {
        try {
            return refuse0(me, sa, modeTok, planned);
        } catch (RuntimeException e) {
            return null; // a check that cannot be made refuses nothing (the follower plays the step as before)
        }
    }

    /**
     * line-own-target-1119 ({@link LookaheadSearch.Config#ownHarm}): why a search candidate {@code sa} (a menu entry, its
     * targets and modes not yet chosen: every target is open, a modal spell is refused only when every mode is) must not
     * be played by {@code me} now; null = it may. Never throws.
     */
    static String refuseOpen(final Player me, final SpellAbility sa) {
        try {
            return refuse0(me, sa, null, null, true);
        } catch (RuntimeException e) {
            return null; // a check that cannot be made refuses nothing (the candidate stays)
        }
    }

    private static String refuse0(final Player me, final SpellAbility sa, final String modeTok, final Set<Integer> planned) {
        return refuse0(me, sa, modeTok, planned, false);
    }

    private static String refuse0(final Player me, final SpellAbility sa, final String modeTok, final Set<Integer> planned,
            final boolean open) {
        if (me == null || sa == null) {
            return null;
        }
        if (sa.getApi() == ApiType.Charm) {
            // a modal spell: its modes (and their targets) are chosen as it is cast (CharmEffect.makeChoices)
            final List<AbilitySub> possible = CharmEffect.makePossibleOptions(sa);
            if (possible.isEmpty()) {
                return null;
            }
            final Card host = sa.getHostCard();
            final int num = AbilityUtils.calculateAmount(host, sa.getParamOrDefault("CharmNum", "1"), sa);
            final int min = sa.hasParam("MinCharmNum") ? AbilityUtils.calculateAmount(host, sa.getParam("MinCharmNum"), sa) : num;
            // the follower replays the line's mode set only when two or more are offered (Follower#modes)
            final List<AbilitySub> chosen = modeTok == null || possible.size() < 2 ? null
                    : ForcedWinChoices.applyMode(modeTok, possible, min, num);
            if (chosen != null) {
                for (AbilitySub mode : chosen) {
                    final String why = refuseChain(me, mode, true, planned);
                    if (why != null) {
                        return "mode " + possible.indexOf(mode) + ": " + why;
                    }
                }
                return null;
            }
            // Forge AI chooses the modes: refused only when every mode it could choose is refused
            String first = null;
            for (AbilitySub mode : possible) {
                final String why = refuseChain(me, mode, true, planned);
                if (why == null) {
                    return null;
                }
                first = first == null ? "every mode: " + why : first;
            }
            return first;
        }
        return refuseChain(me, sa, open, planned);
    }

    /** The first refusal of an ability and its sub-abilities, or null ({@code open}: every target is chosen at cast). */
    private static String refuseChain(final Player me, final SpellAbility sa, final boolean open, final Set<Integer> planned) {
        for (SpellAbility l = sa; l != null; l = l.getSubAbility()) {
            final String why = refusePart(me, l, open, planned);
            if (why != null) {
                return why;
            }
        }
        return null;
    }

    /**
     * One targeting part. {@code open}: a modal spell's mode, whose targets are chosen as it is cast (any targets it holds
     * are stale); otherwise the targets it holds are the step's recorded ones (none = Forge AI picks them at cast).
     */
    static String refusePart(final Player me, final SpellAbility p, final boolean open, final Set<Integer> planned) {
        if (!p.usesTargeting()) {
            return null;
        }
        final Harm harm = harm(p);
        if (harm == null || ownByDesign(p)) {
            return null;
        }
        if (!open && !p.getTargets().isEmpty()) {
            for (Card c : p.getTargets().getTargetCards()) {
                if (harm == Harm.DESTROY && !c.canBeDestroyed()) {
                    return "destroy has no effect on " + c.getName() + " (" + c.getId() + ")";
                }
                if (c.isInPlay() && me.equals(c.getController()) && (planned == null || !planned.contains(c.getId()))
                        && !paysOff(me, c, harm)) {
                    return harm.name().toLowerCase() + " on our own " + c.getName() + " (" + c.getId() + ")";
                }
            }
            return null;
        }
        if (p.getMinTargets() <= 0 || p.getTargetRestrictions() == null) {
            return null; // "up to": nothing has to be chosen
        }
        final List<GameEntity> cands = p.getTargetRestrictions().getAllCandidates(p);
        if (cands.isEmpty()) {
            return null; // not castable: the step is not playable anyway
        }
        boolean affected = false, other = false;
        final List<String> ours = new ArrayList<>();
        for (GameEntity e : cands) {
            if (e instanceof Card c) {
                if (harm == Harm.DESTROY && !c.canBeDestroyed()) {
                    continue;
                }
                affected = true;
                if (me.equals(c.getController())) {
                    ours.add(c.getName() + " (" + c.getId() + ")");
                    // a death (or leave) payoff on one of ours is a choice worth making (as for a recorded target)
                    other |= (planned != null && planned.contains(c.getId())) || paysOff(me, c, harm);
                } else {
                    other = true;
                }
            } else if (e instanceof Player pl) {
                affected = true;
                other |= !me.equals(pl);
            }
        }
        if (!affected) {
            return "destroy has no effect: every legal target is indestructible";
        }
        return other ? null : harm.name().toLowerCase() + " with only our own to target: " + ours;
    }

    /** The harm of one ability part, or null when it is not one read here. */
    static Harm harm(final SpellAbility p) {
        final ApiType api = p.getApi();
        if (api == null) {
            return null;
        }
        switch (api) {
            case DealDamage:
                return Harm.DAMAGE;
            case Destroy:
                return Harm.DESTROY;
            case Pump:
                return negative(p.getParam("NumDef")) ? Harm.SHRINK : null;
            case PutCounter:
                return "M1M1".equals(p.getParam("CounterType")) ? Harm.SHRINK : null;
            case Sacrifice:
                return Harm.SACRIFICE;
            case ChangeZone: {
                final String origin = p.getParamOrDefault("Origin", "");
                final String dest = p.getParamOrDefault("Destination", "");
                if (!origin.contains("Battlefield") || dest.isEmpty() || dest.contains("Battlefield") || returns(p)) {
                    return null; // not from the battlefield, onto it, or a blink (it comes back)
                }
                return dest.equals("Hand") ? Harm.BOUNCE : Harm.REMOVE;
            }
            default:
                return null;
        }
    }

    private static boolean negative(final String v) {
        return v != null && v.trim().startsWith("-");
    }

    /** A blink: a later part of the chain puts the card back onto the battlefield. */
    private static boolean returns(final SpellAbility p) {
        for (SpellAbility l = p.getSubAbility(); l != null; l = l.getSubAbility()) {
            if (l.getApi() == ApiType.ChangeZone && "Battlefield".equals(l.getParam("Destination"))) {
                return true;
            }
        }
        return false;
    }

    /** The card only lets it target permanents its controller controls (or owns): targeting ours is its design. */
    static boolean ownByDesign(final SpellAbility p) {
        final String valid = p.getParam("ValidTgts");
        if (valid == null || valid.isBlank()) {
            return false;
        }
        for (String v : valid.split(",")) {
            if (!(v.contains("YouCtrl") || v.contains("YouOwn"))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A death (or leave) payoff: a permanent we control (the target among them) triggers on {@code c} leaving the
     * battlefield, being destroyed or sacrificed, or (damage) being dealt damage; a bounce re-uses {@code c}'s own enters
     * trigger. Persist and undying are triggers of the card.
     */
    static boolean paysOff(final Player me, final Card c, final Harm harm) {
        for (Card src : me.getCardsIn(ZoneType.Battlefield)) {
            for (Trigger t : src.getTriggers()) {
                final TriggerType mode = t.getMode();
                if (mode == null) {
                    continue;
                }
                switch (mode) {
                    case ChangesZone:
                        if (harm == Harm.BOUNCE) {
                            if (src.equals(c) && "Battlefield".equals(t.getParam("Destination")) && t.matchesValidParam("ValidCard", c)) {
                                return true; // its own enters trigger, played again
                            }
                        } else if (t.getParamOrDefault("Origin", "").contains("Battlefield")
                                && !"Battlefield".equals(t.getParam("Destination")) && t.matchesValidParam("ValidCard", c)) {
                            return true;
                        }
                        break;
                    case ChangesZoneAll:
                        if (harm != Harm.BOUNCE && t.getParamOrDefault("Origin", "").contains("Battlefield")
                                && t.matchesValidParam("ValidCards", c)) {
                            return true;
                        }
                        break;
                    case Destroyed:
                    case Sacrificed:
                        if (harm != Harm.BOUNCE && harm != Harm.REMOVE && t.matchesValidParam("ValidCard", c)) {
                            return true;
                        }
                        break;
                    case DamageDone:
                    case DamageDoneOnce:
                        if (harm == Harm.DAMAGE && t.matchesValidParam("ValidTarget", c)) {
                            return true;
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        return false;
    }
}
