package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.game.GameEntity;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * line-own-target-1119: may {@code me} play this ability now, or is it pure, payoff-free harm to a permanent we control
 * (or a destroy that does nothing at all)? Read by two default-off keys: the live line follower's
 * ({@link ForcedWinSolver.Config#lineOwnTarget}, {@link #refuse}: a refused step breaks the line, and the seat searches
 * again) and the policy search's ({@link LookaheadSearch.Config#ownHarm}, {@link #refuseOpen}: a refused candidate is
 * dropped). Off, nothing here runs.
 *
 * <p>Owner reports 2026-11-19T01-05-37 and 01-14-57. Proven ENGINE lines held removal steps that did nothing for the
 * win in the solver's copies: Abrade (modal; its mode recorded, its target left to Forge AI as the spell is cast) at our
 * own undamaged 5/4, the only creature, and Snuff Out at our own indestructible Ulamog (a recorded target). Live, the
 * first had become lethal to our creature and the second paid 4 life for nothing.
 *
 * <p>The guard forbids an action, so it refuses only what is surely pure harm; when unsure it keeps the action (review
 * 1 of #251: a missed refusal is acceptable, a false one is not).
 * <ul>
 * <li>Harmful parts: damage, destroy, -N/-N (a toughness-lowering pump with no power gain, -1/-1 counters), and a move
 * from the battlefield to exile, a graveyard, a library or a hand that does not come back. A move that comes back, at
 * once or later (a delayed trigger's return, a "until ..." duration), is a blink, not harm. A +X/-Y pump with positive
 * power on a target that survives it (toughness above the damage marked) is a pump, not harm.</li>
 * <li>Only permanents count, by CONTROLLER (never owner): a stolen permanent we control is ours, one we own that the
 * opponent controls is theirs. A player target, and a target that is not on the battlefield (a spell on the stack), is
 * outside the guard: a choice it never refuses.</li>
 * <li>A payoff keeps the action: a permanent we control that triggers on the card dying or leaving the battlefield,
 * being destroyed or sacrificed, or (damage) being dealt damage; for a bounce, an enters trigger on any permanent we
 * control that could fire for the card.</li>
 * <li>The search only ({@link #refuseOpen}, review 1b of #251): any other effect of the same ability that gives us
 * something (life, cards, tokens, lands, mana, counters, for us or for the target's controller, which is then us) keeps
 * it, so Swords to Plowshares, Oust, Path to Exile and Beast Within on our own permanent stay candidates; and a destroy
 * whose every target is indestructible is refused only when it is the ability's only effect (Snuff Out), so Beast
 * Within's token and Cleansing Wildfire's land and card stand. The line check ({@link #refuse}) takes neither
 * exception: a line step whose harm to our own permanent is paid for only by its own follow-up, or whose destroy hits
 * only indestructible targets, breaks the line, and the seat's normal search (which keeps those spells) decides.</li>
 * </ul>
 * Refused: a recorded target (an ENGINE step's) on a permanent we control, unless the line planned that object (a
 * probeObjects step's {@link ForcedWinPlanner.Step#objTarget}) or a payoff exists; an open target (chosen as it is cast)
 * whose only legal choices are permanents we control with no payoff. A modal spell is refused only when every mode it
 * could take is (a recorded mode set: its modes). Never refused: "target ... you control" (a blink, a sacrifice outlet),
 * costs, and effects that are not harmful. Read-only: no ability, mode or card is changed by a check.
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
            return refuse0(me, sa, modeTok, planned, false, false);
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
            return refuse0(me, sa, null, null, true, true);
        } catch (RuntimeException e) {
            return null; // a check that cannot be made refuses nothing (the candidate stays)
        }
    }

    /**
     * {@code open}: every target is chosen as it is cast. {@code search}: the policy search's check, which keeps an ability
     * whose own other effects pay for the harm (review 1b of #251: the line check does not; a modal mode in a line is
     * open but still the line's check).
     */
    private static String refuse0(final Player me, final SpellAbility sa, final String modeTok, final Set<Integer> planned,
            final boolean open, final boolean search) {
        if (me == null || sa == null) {
            return null;
        }
        if (sa.getApi() == ApiType.Charm) {
            // a modal spell: its modes (and their targets) are chosen as it is cast (CharmEffect.makeChoices)
            final List<AbilitySub> possible = possibleModes(sa);
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
                    final String why = refuseChain(me, mode, true, planned, search);
                    if (why != null) {
                        return "mode " + possible.indexOf(mode) + ": " + why;
                    }
                }
                return null;
            }
            // Forge AI chooses the modes: refused only when every mode it could choose is refused
            String first = null;
            for (AbilitySub mode : possible) {
                final String why = refuseChain(me, mode, true, planned, search);
                if (why == null) {
                    return null;
                }
                first = first == null ? "every mode: " + why : first;
            }
            return first;
        }
        return refuseChain(me, sa, open, planned, search);
    }

    /**
     * The modes Forge would offer for the modal spell {@code sa} now, in {@code CharmEffect.makePossibleOptions}' order
     * (a recorded mode index points into it), read without that method's writes: it numbers each offered mode's
     * {@code CharmOrder} SVar on the live mode objects (review 1 of #251, S1 7).
     */
    static List<AbilitySub> possibleModes(final SpellAbility sa) {
        final Card source = sa.getHostCard();
        final List<String> restriction = sa.hasParam("ChoiceRestriction")
                ? source.getChosenModes(sa, sa.getParam("ChoiceRestriction")) : null;
        final List<AbilitySub> choices = new ArrayList<>(sa.getAdditionalAbilityList("Choices"));
        if (source.getZone() != null) {
            // 603.3c: a mode that would be illegal can't be chosen (as makePossibleOptions)
            choices.removeIf(ch -> (ch.usesTargeting() && ch.getMinTargets() > 0
                    && ch.getTargetRestrictions().getNumCandidates(ch) == 0)
                    || (restriction != null && restriction.contains(ch.getDescription())));
        }
        return choices;
    }

    /**
     * The first refusal of an ability and its sub-abilities, or null ({@code open}: every target is chosen at cast;
     * {@code search}: the ability's own other effects may pay for the harm).
     */
    private static String refuseChain(final Player me, final SpellAbility sa, final boolean open, final Set<Integer> planned,
            final boolean search) {
        final Whole whole = search ? new Whole(sa) : null;
        for (SpellAbility l = sa; l != null; l = l.getSubAbility()) {
            final String why = refusePart(me, l, open, planned, whole);
            if (why != null) {
                return why;
            }
        }
        return null;
    }

    /** What the whole ability does besides one harmful part: something for us, or any other effect at all. */
    static final class Whole {
        /** A part gives us something (or the target's controller, which is us when only ours can be targeted). */
        final boolean benefit;
        /** The parts that do something (bookkeeping such as Cleanup not counted; a delayed trigger counts its return). */
        final int effects;

        Whole(final SpellAbility root) {
            boolean b = false;
            int n = 0;
            for (SpellAbility p : tree(root)) {
                if (p.getApi() == null || BOOKKEEPING.contains(p.getApi())) {
                    continue;
                }
                n++;
                b |= benefits(p);
            }
            benefit = b;
            effects = n;
        }
    }

    private static final Set<ApiType> BOOKKEEPING = Set.of(ApiType.Cleanup, ApiType.StoreSVar, ApiType.DelayedTrigger,
            ApiType.ImmediateTrigger);

    /**
     * Every ability part reachable from {@code root} (its sub-abilities, its additional abilities such as a delayed
     * trigger's Execute, and their lists), each once, in pre-order (the order {@link SpellAbility#setActivatingPlayer}
     * trickles down in).
     */
    static List<SpellAbility> tree(final SpellAbility root) {
        final List<SpellAbility> out = new ArrayList<>();
        final Set<SpellAbility> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        walk(root, out, seen);
        return out;
    }

    private static void walk(final SpellAbility p, final List<SpellAbility> out, final Set<SpellAbility> seen) {
        if (p == null || !seen.add(p) || out.size() > 256) {
            return;
        }
        out.add(p);
        walk(p.getSubAbility(), out, seen);
        for (SpellAbility x : p.getAdditionalAbilities().values()) {
            walk(x, out, seen);
        }
        for (Map.Entry<String, List<AbilitySub>> e : p.getAdditionalAbilityLists().entrySet()) {
            for (AbilitySub x : e.getValue()) {
                walk(x, out, seen);
            }
        }
    }

    /** One part gives us something: life, cards, tokens, lands, mana, counters (not to an opponent by its own words). */
    static boolean benefits(final SpellAbility p) {
        final ApiType api = p.getApi();
        if (api == null) {
            return false;
        }
        for (String k : new String[] {"Defined", "DefinedPlayer", "TokenOwner"}) {
            final String v = p.getParam(k);
            if (v != null && v.contains("Opponent")) {
                return false; // for an opponent: nothing for us
            }
        }
        switch (api) {
            case GainLife:
            case Draw:
            case Token:
            case CopyPermanent:
            case Investigate:
            case Amass:
            case Incubate:
            case Mana:
            case ManaReflected:
            case Dig:
            case DigUntil:
            case Discover:
            case Learn:
            case Seek:
            case Explore:
            case Connive:
            case Proliferate:
            case Endure:
            case Scry:
            case Surveil:
                return true;
            case PutCounter:
            case PutCounterAll:
                return !"M1M1".equals(p.getParam("CounterType"));
            case ChangeZone:
            case ChangeZoneAll: {
                // a card or land put into a hand or onto the battlefield from anywhere but the battlefield (a search)
                final String origin = p.getParamOrDefault("Origin", "");
                final String dest = p.getParamOrDefault("Destination", "");
                return !origin.contains("Battlefield") && (dest.contains("Hand") || dest.contains("Battlefield"));
            }
            default:
                return false;
        }
    }

    /**
     * One targeting part. {@code open}: a modal spell's mode, whose targets are chosen as it is cast (any targets it holds
     * are stale); otherwise the targets it holds are the step's recorded ones (none = Forge AI picks them at cast).
     * {@code whole}: the search's view of the whole ability (its own other effects may pay for the harm); null = the line
     * check, which takes no such exception (review 1b of #251).
     */
    static String refusePart(final Player me, final SpellAbility p, final boolean open, final Set<Integer> planned,
            final Whole whole) {
        if (!p.usesTargeting()) {
            return null;
        }
        final Harm harm = harm(p);
        if (harm == null || ownByDesign(p)) {
            return null; // not harmful, or ours by design
        }
        if (whole != null && whole.benefit) {
            return null; // the search: the ability's own other effect pays off (Swords' life, Beast Within's token)
        }
        // a destroy that can destroy nothing: refused in a line; in the search only when it is all the ability does
        final boolean only = whole == null || whole.effects <= 1;
        if (!open && !p.getTargets().isEmpty()) {
            for (Card c : p.getTargets().getTargetCards()) {
                if (!c.isInPlay()) {
                    continue; // a spell on the stack, a card elsewhere: never "our permanent"
                }
                if (harm == Harm.DESTROY && !c.canBeDestroyed()) {
                    if (only) {
                        return "destroy has no effect on " + c.getName() + " (" + c.getId() + ")";
                    }
                    continue; // nothing happens to it; the ability's other effects stand
                }
                if (me.equals(c.getController()) && !spared(p, harm, c)
                        && (planned == null || !planned.contains(c.getId())) && !paysOff(me, c, harm)) {
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
            if (!(e instanceof Card c) || !c.isInPlay()) {
                // a player (outside this guard), or a spell on the stack / a card elsewhere: a choice never refused
                affected = true;
                other = true;
                continue;
            }
            if (harm == Harm.DESTROY && !c.canBeDestroyed()) {
                continue;
            }
            affected = true;
            if (me.equals(c.getController())) {
                ours.add(c.getName() + " (" + c.getId() + ")");
                // a pump it survives, a planned object, or a payoff on one of ours is a choice worth making
                other |= spared(p, harm, c) || (planned != null && planned.contains(c.getId())) || paysOff(me, c, harm);
            } else {
                other = true;
            }
        }
        if (!affected) {
            return only ? "destroy has no effect: every legal target is indestructible" : null;
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
                    return null; // not from the battlefield, onto it, or a blink (it comes back, now or later)
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

    /**
     * A blink: the moved card comes back to the battlefield, at once (a later part of the chain), later (a delayed
     * trigger's Execute: Turn to Mist, Touch the Spirit Realm's channel) or when a duration ends ("until ... leaves").
     */
    static boolean returns(final SpellAbility p) {
        if (p.hasParam("Duration")) {
            return true;
        }
        final List<SpellAbility> rest = tree(p);
        for (SpellAbility l : rest) {
            if (l == p) {
                continue;
            }
            final ApiType api = l.getApi();
            if (api != ApiType.ChangeZone && api != ApiType.ChangeZoneAll) {
                continue;
            }
            final String origin = l.getParamOrDefault("Origin", "");
            if (l.getParamOrDefault("Destination", "").contains("Battlefield") && (l.hasParam("Defined")
                    || origin.contains("Exile") || origin.contains("Graveyard") || origin.contains("All"))) {
                return true; // the object (Defined: the remembered or targeted card) put back, not a library search
            }
        }
        return false;
    }

    /**
     * A +X/-Y pump with positive power that {@code c} survives (its toughness stays above the damage marked on it): a
     * pump, not a shrink. Unsure (an amount that cannot be read) = spared.
     */
    static boolean spared(final SpellAbility p, final Harm harm, final Card c) {
        if (harm != Harm.SHRINK || p.getApi() != ApiType.Pump) {
            return false;
        }
        final Integer att = amount(p, p.getParam("NumAtt"));
        if (att != null && att <= 0) {
            return false; // no power gain: a shrink
        }
        final Integer def = amount(p, p.getParam("NumDef"));
        if (att == null || def == null) {
            return true;
        }
        return c.getNetToughness() + def - c.getDamage() > 0;
    }

    /** A Pump amount ("+3", "-3", "X", "-X"), or null when it cannot be read (absent = 0). */
    private static Integer amount(final SpellAbility p, final String v) {
        if (v == null || v.isBlank()) {
            return 0;
        }
        String s = v.trim();
        int sign = 1;
        if (s.startsWith("+")) {
            s = s.substring(1);
        } else if (s.startsWith("-")) {
            sign = -1;
            s = s.substring(1);
        }
        try {
            if (s.matches("\\d+")) {
                return sign * Integer.parseInt(s);
            }
            return sign * AbilityUtils.calculateAmount(p.getHostCard(), s, p);
        } catch (RuntimeException e) {
            return null;
        }
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
     * A payoff: a permanent we control (the target among them) triggers on {@code c} leaving the battlefield, being
     * destroyed or sacrificed, or (damage) being dealt damage; for a bounce, an enters trigger on any permanent we control
     * that could fire for {@code c} when it is played again (its own, or Impact Tremors'). Persist and undying are
     * triggers of the card.
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
                            if (t.getParamOrDefault("Destination", "").contains("Battlefield")
                                    && t.matchesValidParam("ValidCard", c)) {
                                return true; // an enters trigger that could fire for it, played again
                            }
                        } else if (t.getParamOrDefault("Origin", "").contains("Battlefield")
                                && !"Battlefield".equals(t.getParam("Destination")) && t.matchesValidParam("ValidCard", c)) {
                            return true;
                        }
                        break;
                    case ChangesZoneAll:
                        if (harm == Harm.BOUNCE) {
                            if (t.getParamOrDefault("Destination", "").contains("Battlefield")
                                    && t.matchesValidParam("ValidCards", c)) {
                                return true;
                            }
                        } else if (t.getParamOrDefault("Origin", "").contains("Battlefield")
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
