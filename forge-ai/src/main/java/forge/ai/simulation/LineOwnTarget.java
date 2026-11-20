package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.game.CardTraitBase;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.Zone;
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
                    if (only && !couldFire(me, p, c, null)) {
                        return "destroy has no effect on " + c.getName() + " (" + c.getId() + ")";
                    }
                    continue; // nothing happens to it; the ability's other effects (or its cast) stand
                }
                if (ownHarmed(me, p, harm, c, planned)) {
                    return harm.name().toLowerCase() + " on our own " + c.getName() + " (" + c.getId() + ")";
                }
            }
            return null;
        }
        return openOwnOnly(me, p, harm, planned, only);
    }

    /**
     * {@code c} (a permanent the part {@code p} holds or would hold as its target) is ours and takes pure, payoff-free harm:
     * we control it, a pump does not spare it, the line did not plan it ({@code planned}), and no trigger of ours could
     * fire from it. The recorded-target test of {@link #refusePart}; target-picks-1119 reads it for a chosen target.
     */
    static boolean ownHarmed(final Player me, final SpellAbility p, final Harm harm, final Card c, final Set<Integer> planned) {
        return me.equals(c.getController()) && !spared(p, harm, c)
                && (planned == null || !planned.contains(c.getId())) && !couldFire(me, p, c, harm);
    }

    /**
     * The open-target test of {@link #refusePart} ({@code p}'s targets chosen as it is cast or put on the stack): the
     * harm with only our own permanents to target, no payoff; with {@code only}, also a destroy whose every legal target
     * is indestructible. Null = it may.
     */
    static String openOwnOnly(final Player me, final SpellAbility p, final Harm harm, final Set<Integer> planned,
            final boolean only) {
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
                if (couldFire(me, p, c, null)) {
                    affected = true; // nothing is destroyed, but casting it at this target could fire a trigger of ours
                    other = true;
                }
                continue;
            }
            affected = true;
            if (me.equals(c.getController())) {
                ours.add(c.getName() + " (" + c.getId() + ")");
                // a pump it survives, a planned object, or a trigger of ours this could fire is a choice worth making
                other |= spared(p, harm, c) || (planned != null && planned.contains(c.getId())) || couldFire(me, p, c, harm);
            } else {
                other = true;
            }
        }
        if (!affected) {
            return only ? "destroy has no effect: every legal target is indestructible" : null;
        }
        return other ? null : harm.name().toLowerCase() + " with only our own to target: " + ours;
    }

    /**
     * target-picks-1119 (the pick guard, {@code ownHarmPicks}): the targets the part {@code p} holds now (chosen by the seat,
     * Forge AI or a variant) put pure, payoff-free harm on a permanent we control, with the search's own-payoff exception
     * ({@link Whole#benefit} of {@code root}, the whole ability: Swords to Plowshares' life, Beast Within's token keep the
     * pick). A destroy that cannot destroy its target does nothing to it, and is not own harm here. Why, or null. Read-only;
     * never throws.
     */
    static String pickHarm(final Player me, final SpellAbility p, final SpellAbility root) {
        try {
            if (me == null || p == null || !p.usesTargeting() || p.getTargets().isEmpty()) {
                return null;
            }
            final Harm harm = harm(p);
            if (harm == null || ownByDesign(p)) {
                return null;
            }
            if (new Whole(root == null ? p : root).benefit) {
                return null;
            }
            for (Card c : p.getTargets().getTargetCards()) {
                if (!c.isInPlay() || harm == Harm.DESTROY && !c.canBeDestroyed()) {
                    continue;
                }
                if (ownHarmed(me, p, harm, c, null)) {
                    return harm.name().toLowerCase() + " on our own " + c.getName() + " (" + c.getId() + ")";
                }
            }
            return null;
        } catch (RuntimeException e) {
            return null; // unsure: the pick stands
        }
    }

    /**
     * target-picks-1119 (A2, under {@code ownHarmPicks}): {@code sa} casts a permanent whose mandatory "enters" trigger of
     * its own (this card entering from anywhere, its hand or the stack; no "you may", no cost) must target, and every legal
     * target of a harmful part now is a permanent we control, with no payoff ({@link #openOwnOnly}; the trigger's own other
     * effect for us keeps it): Woodfall Primus with no noncreature permanent of the opponent's to destroy. Why, or null.
     * The trigger's ability is read on a copy whose ids come from a scope of their own (the live game's ids are
     * unchanged). Read-only; never throws.
     */
    static String refuseEtb(final Player me, final SpellAbility sa) {
        try {
            if (me == null || sa == null || !sa.isSpell() || sa.getHostCard() == null || !sa.getHostCard().isPermanent()) {
                return null;
            }
            final Card host = sa.getHostCard();
            return forge.util.IdScope.detached(() -> refuseEtb0(me, host));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String refuseEtb0(final Player me, final Card host) {
        for (Trigger tr : host.getTriggers()) {
            if (tr.getMode() != TriggerType.ChangesZone || !ZoneType.Battlefield.toString().equals(tr.getParam("Destination"))
                    || !"Card.Self".equals(tr.getParam("ValidCard")) || tr.hasParam("OptionalDecider")) {
                continue;
            }
            final String origin = tr.getParamOrDefault("Origin", "Any");
            if (!"Any".equals(origin) && !"Hand".equals(origin) && !"Stack".equals(origin)) {
                continue;
            }
            if (!tr.requirementsCheck(host.getGame())) {
                continue;
            }
            final SpellAbility ex = tr.ensureAbility();
            if (ex == null || !(ex instanceof AbilitySub) && ex.hasParam("Cost") && !"0".equals(ex.getParam("Cost"))) {
                continue; // a trigger with a cost is optional
            }
            final SpellAbility root = ex.copy(me);
            root.setActivatingPlayer(me);
            if (new Whole(root).benefit) {
                continue;
            }
            for (SpellAbility p : tree(root)) {
                if (!p.usesTargeting()) {
                    continue;
                }
                final Harm harm = harm(p);
                if (harm == null || ownByDesign(p)) {
                    continue;
                }
                final String why = openOwnOnly(me, p, harm, null, false);
                if (why != null) {
                    return "enters: " + why;
                }
            }
        }
        return null;
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
     * A +X/-Y pump with positive power that {@code c} survives: a pump, not a shrink. Unsure (an amount that cannot be
     * read) = spared. A -X/-Y (no power gain) is a shrink whether or not it survives.
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
        return survives(c, def);
    }

    /**
     * Review 2 of #251, S1 5: {@code c} survives a toughness change of {@code def}: its toughness after it is above 0, and
     * the damage marked on it is below that toughness or it is indestructible (lethal damage destroys; zero toughness
     * does not need to).
     */
    static boolean survives(final Card c, final int def) {
        final int t = c.getNetToughness() + def;
        return t > 0 && (c.getDamage() < t || c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE));
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
     * Review 2 of #251 (architect-o: "could any trigger of ours in a public zone fire from this action?"), the one payoff
     * test both keys read. True (keep the action) when a card we control or own in a public zone (battlefield, graveyard,
     * exile, command; the stack for replacement effects only), or the spell's own card for its cast triggers, has a
     * trigger or a replacement effect, active in its zone by Forge's own zone check, whose mode could be set off by:
     * <ul>
     * <li>casting, copying or activating this ability, or its targeting {@code target} (always);</li>
     * <li>{@code target} being dealt damage ({@link Harm#DAMAGE});</li>
     * <li>{@code target} leaving the battlefield the way this harm moves it (every harm), or, for a bounce, entering the
     * battlefield again when it is replayed;</li>
     * <li>counters put on or removed from {@code target} (a -1/-1 counter shrink).</li>
     * </ul>
     * Filters are read with Forge's own matching ({@code ValidCard}, {@code ValidTarget}, {@code ValidSource},
     * {@code ValidActivatingPlayer}, {@code ValidSA}, {@code Origin}, {@code Destination}, ...) against {@code target}, this
     * ability and its card. A filter that cannot be read (a parameter not read here, an error) counts as passing: a
     * trigger in the family is a payoff unless a filter it states surely fails. {@code harm} null: nothing happens to
     * {@code target} (a destroy that cannot destroy it); only the cast and target family is read. Static abilities are
     * not read on their own: one that grants a trigger shows it on the card it grants it to, and those that scale or stop
     * triggers (Panharmonicon, Torpor Orb) set off nothing by themselves. Triggers Forge flags static ({@code Static$
     * True}: rules bookkeeping, not triggered abilities, such as Chrome Mox's and Animate Dead's imprint and attach
     * cleanup) are skipped (architect-o, review 2); Serra Paragon's grants its "exile it and you gain 2 life" rider as an
     * ordinary trigger of the permanent, which is read. Read-only; never throws (an error = true).
     */
    static boolean couldFire(final Player me, final SpellAbility p, final Card target, final Harm harm) {
        return couldFire(me, p, target, harm, false);
    }

    /**
     * {@link #couldFire}; {@code loose} (target-picks-1119, the pick guard's unsure test only; false = exactly
     * {@link #couldFire}): Forge's static triggers are read too (an effect's "these phase out until ~ leaves" return, Out of
     * Time), and for a shrink a card filter on the target that names power or toughness passes (the filter is read on the
     * target before the shrink; after it the card may match: Shirei, Shizo's Caretaker's "power 1 or less").
     */
    private static boolean couldFire(final Player me, final SpellAbility p, final Card target, final Harm harm,
            final boolean loose) {
        try {
            final Game game = me.getGame();
            final SpellAbility root = p.getRootAbility() == null ? p : p.getRootAbility();
            final Card source = p.getHostCard();
            final String dest = harm == null ? null : destination(p, harm);
            final Set<Card> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Card c : sources(me)) {
                if (!seen.add(c)) {
                    continue;
                }
                final Zone z = game.getZoneOf(c);
                final boolean stack = z != null && z.is(ZoneType.Stack);
                if (!stack) {
                    for (Trigger t : c.getTriggers()) {
                        if ((loose || !t.isStatic()) && !t.isSuppressed() && t.zonesCheck(z)
                                && fires(t, me, p, root, source, target, harm, dest, loose)) {
                            return true;
                        }
                    }
                }
                for (ReplacementEffect re : c.getReplacementEffects()) {
                    if (!re.isSuppressed() && re.zonesCheck(z) && replaces(re, p, source, target, harm, dest, loose)) {
                        return true;
                    }
                }
            }
            if (source != null && !seen.contains(source)) {
                // the spell's own card (in hand): its cast triggers fire from the stack
                for (Trigger t : source.getTriggers()) {
                    final Set<ZoneType> zs = t.getActiveZone();
                    if (!t.isStatic() && !t.isSuppressed() && CAST.contains(t.getMode())
                            && (zs == null || zs.isEmpty() || zs.contains(ZoneType.Stack))
                            && fires(t, me, p, root, source, target, null, null, loose)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (RuntimeException e) {
            return true; // unsure: keep
        }
    }

    /**
     * target-picks-1119 (the pick guard, {@code ownHarmPicks}; review 2: affirmative coverage, the coordinator's ruling):
     * the one predicate the guard reads before it retargets or declines a pick {@link #pickHarm} calls own harm, at the
     * pick (trigger or cast) and when an optional trigger resolves. The guard may act only when every permanent of ours the
     * pick {@code p} harms (its chosen targets; {@code root} the whole ability) is a plain asset taking plain loss: null =
     * a plain asset (the guard may act); otherwise the first condition that fails (the pick stands, logged
     * {@code kept-unsure} with it). All must hold:
     * <ol>
     * <li>the harm removes it: a destroy, an exile, a sacrifice, lethal damage or a lethal toughness loss. A bounce is
     * never overridden (on our own permanent it is usually a save, a reuse or an escape); damage or a shrink it survives
     * (or whose amount cannot be read) is not plain loss;</li>
     * <li>the part does not divide an amount (no allocation is made for a retarget);</li>
     * <li>no spell or ability an opponent controls is on the stack (any own-target pick may be a response to it: Unsummon
     * saving our creature from their Doom Blade);</li>
     * <li>no other part of the ability projects an event from the target (Garruk Relentless: "that creature deals damage
     * equal to its power to him"; a fight) and the ability's card has no state trigger (his transform);</li>
     * <li>nothing is attached to the target and it is attached to nothing (their Pacifism on our creature: bouncing it is
     * an escape; Equipment, Auras either side);</li>
     * <li>the target has no static ability of its own (keyword statics aside): a drawback or lock permanent (Steel Golem,
     * Ensnaring Bridge, Smokestack) may be removed on purpose;</li>
     * <li>it holds no pending duration or return: "until it leaves" commands (Banishing Light's exile), cards exiled with
     * it or until it leaves, permanents it gained control of;</li>
     * <li>no trigger of ours could fire from the harm, read loosely (Forge's static triggers too: Out of Time's phase-in;
     * a power/toughness filter for a shrink: Shirei, Shizo's Caretaker), and no delayed trigger of ours remembers it or
     * watches it leave (Adarkar Valkyrie);</li>
     * <li>the card does not hint that it wants to go ({@code SacMe});</li>
     * <li>everything can be read (an error fails).</li>
     * </ol>
     * A search variant's own pick is never guarded, and a retarget must also pass the bridge's legality and MustTarget
     * check: those are the bridge's. Read-only; never throws.
     */
    static String plainAsset(final Player me, final SpellAbility p, final SpellAbility root) {
        try {
            if (me == null || p == null || !p.usesTargeting()) {
                return null;
            }
            final Harm harm = harm(p);
            if (harm == null) {
                return null;
            }
            if (harm == Harm.BOUNCE) {
                return "a bounce is never overridden (a save, a reuse or an escape)";
            }
            if (p.isDividedAsYouChoose()) {
                return "divided amount: not retargeted";
            }
            final String response = opposingStack(me);
            if (response != null) {
                return response;
            }
            final SpellAbility whole = root == null ? p : root;
            final String shape = projected(p, whole);
            for (Card c : p.getTargets().getTargetCards()) {
                if (!c.isInPlay() || !me.equals(c.getController())) {
                    continue;
                }
                final String what = c.getName() + " (" + c.getId() + ")";
                if (shape != null) {
                    return shape + " (" + what + ")";
                }
                final String loss = removes(p, harm, c);
                if (loss != null) {
                    return loss + " (" + what + ")";
                }
                if (c.hasCardAttachments()) {
                    return "something is attached to it: " + c.getAttachedCards() + " (" + what + ")";
                }
                if (c.isAttachedToEntity()) {
                    return "it is attached to " + c.getEntityAttachedTo() + " (" + what + ")";
                }
                final String statics = ownStatic(c);
                if (statics != null) {
                    return statics + " (" + what + ")";
                }
                final String pending = pending(c);
                if (pending != null) {
                    return pending + " (" + what + ")";
                }
                if (couldFire(me, p, c, harm, true)) {
                    return "a trigger of ours could fire, static or on its projected state (" + what + ")";
                }
                final String delayed = delayed(me, c);
                if (delayed != null) {
                    return delayed + " (" + what + ")";
                }
                if (c.hasSVar("SacMe")) {
                    return "the card wants to go: SacMe (" + what + ")";
                }
            }
            return null;
        } catch (RuntimeException e) {
            return "unreadable: " + e; // unsure: the pick stands
        }
    }

    /** A spell or ability an opponent of {@code me} controls on the stack (see {@link #plainAsset}), or null. */
    private static String opposingStack(final Player me) {
        for (forge.game.spellability.SpellAbilityStackInstance si : me.getGame().getStack()) {
            final Player who = si.getActivatingPlayer();
            if (who != null && me.isOpponentOf(who)) {
                final Card src = si.getSourceCard();
                return "an opponent's spell or ability is on the stack (" + (src == null ? "?" : src.getName())
                        + "): the pick may be a response";
            }
        }
        return null;
    }

    /**
     * The harm {@code harm} of the part {@code p} removes {@code c} (see {@link #plainAsset}): null = it does (a destroy,
     * an exile, a sacrifice; damage of at least what kills it, a creature that is not indestructible or a planeswalker's
     * loyalty; a toughness loss it does not survive), else why not.
     */
    private static String removes(final SpellAbility p, final Harm harm, final Card c) {
        switch (harm) {
            case DESTROY:
                return c.canBeDestroyed() ? null : "a destroy it survives (indestructible)";
            case REMOVE:
            case SACRIFICE:
                return null;
            case DAMAGE: {
                final Integer n = amount(p, p.getParam("NumDmg"));
                if (n == null) {
                    return "damage of an amount that cannot be read";
                }
                if (c.isCreature() && !c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE)
                        && n >= c.getLethal() - c.getDamage()) {
                    return null;
                }
                if (c.isPlaneswalker() && n >= c.getCurrentLoyalty()) {
                    return null;
                }
                return "damage it survives (" + n + ")";
            }
            case SHRINK: {
                final Integer n = p.getApi() == ApiType.PutCounter
                        ? amount(p, "-" + p.getParamOrDefault("CounterNum", "1")) : amount(p, p.getParam("NumDef"));
                if (n == null) {
                    return "a toughness loss of an amount that cannot be read";
                }
                return survives(c, n) ? "a toughness loss it survives (" + n + ")" : null;
            }
            default:
                return "a bounce is never overridden (a save, a reuse or an escape)";
        }
    }

    private static final Set<TriggerType> TARGETED = Set.of(TriggerType.BecomesTarget, TriggerType.BecomesTargetOnce,
            TriggerType.Always);
    private static final Set<TriggerType> LEAVES = Set.of(TriggerType.ChangesZone, TriggerType.ChangesZoneAll,
            TriggerType.Destroyed, TriggerType.Exiled, TriggerType.Sacrificed, TriggerType.SacrificedOnce,
            TriggerType.Unattached);
    private static final Set<TriggerType> COUNTERS = Set.of(TriggerType.CounterAdded, TriggerType.CounterAddedOnce,
            TriggerType.CounterAddedAll, TriggerType.CounterTypeAddedAll, TriggerType.CounterPlayerAddedAll);
    private static final Set<TriggerType> DAMAGED = Set.of(TriggerType.DamageAll, TriggerType.DamageDealtOnce,
            TriggerType.DamageDone, TriggerType.DamageDoneOnce, TriggerType.DamageDoneOnceByController,
            TriggerType.DamagePreventedOnce, TriggerType.ExcessDamage, TriggerType.ExcessDamageAll,
            TriggerType.CounterRemoved, TriggerType.CounterRemovedOnce, TriggerType.LifeLost, TriggerType.LifeLostAll,
            TriggerType.LosesGame);

    /**
     * target-picks-1119 (review 3, the coordinator's ruling): the replacement side of the pick guard. The guard's retarget
     * of the part {@code p} to the targets it now holds is taken only when this affirms it is safe: null = safe; else the
     * first condition that fails. Every target must be:
     * <ul>
     * <li>a permanent an opponent controls with no triggered, static or replacement ability of any origin (keyword ones
     * included: persist, undying, ward; Perilous Myr's dies trigger; plain keywords such as flying make none), nothing
     * attached to it and attached to nothing; or an opponent, for damage only (the one player event modeled);</li>
     * <li>such that no trigger on any card in a public zone, either side's, can fire on the projected event: its being
     * targeted, and for a permanent its leaving the battlefield (a destroy, exile or sacrifice; damage or a shrink may
     * kill it), damage to it, counters put on or taken off it; for a player, damage to them and their life loss. A
     * state trigger ({@code Always}) anywhere is never modeled. A trigger of such a mode counts as firing unless Forge's
     * own filter on the object refuses it ({@code ValidCard}, {@code ValidCards}, {@code ValidTarget},
     * {@code ValidPlayer}), it watches another origin than the battlefield or only entering it, or it needs combat
     * damage.</li>
     * </ul>
     * Anything else (a bounce, a divided amount, a spell on the stack, an error) is not modeled: not safe.
     */
    static String plainReplacement(final Player me, final SpellAbility p) {
        try {
            final Harm harm = harm(p);
            if (harm == null || harm == Harm.BOUNCE) {
                return "no modeled event for " + (harm == null ? "this effect" : "a bounce");
            }
            if (p.isDividedAsYouChoose() || p.getTargets().isEmpty()) {
                return "not modeled: a divided amount or no target";
            }
            for (forge.game.GameObject o : p.getTargets()) {
                if (o instanceof Card c) {
                    final String what = c.getName() + " (" + c.getId() + ")";
                    if (!c.isInPlay() || !me.isOpponentOf(c.getController())) {
                        return "not an opponent's permanent: " + what;
                    }
                    final String ability = notPlain(c);
                    if (ability != null) {
                        return ability + ": " + what;
                    }
                    if (c.hasCardAttachments() || c.isAttachedToEntity()) {
                        return "an attachment: " + what;
                    }
                    final Set<TriggerType> modes = new java.util.HashSet<>(TARGETED);
                    modes.addAll(LEAVES);
                    if (harm == Harm.DAMAGE) {
                        modes.addAll(DAMAGED);
                        modes.addAll(COUNTERS); // wither or infect damage
                    } else if (harm == Harm.SHRINK) {
                        modes.addAll(COUNTERS);
                    }
                    final String fires = anyFires(me.getGame(), modes, c);
                    if (fires != null) {
                        return fires + " on " + what;
                    }
                } else if (o instanceof Player pl) {
                    if (!me.isOpponentOf(pl) || harm != Harm.DAMAGE) {
                        return "a player: only damage to an opponent is modeled (" + pl + ", " + harm + ")";
                    }
                    final Set<TriggerType> modes = new java.util.HashSet<>(TARGETED);
                    modes.addAll(DAMAGED);
                    modes.addAll(COUNTERS); // infect: poison counters
                    final String fires = anyFires(me.getGame(), modes, pl);
                    if (fires != null) {
                        return fires + " on " + pl;
                    }
                } else {
                    return "not modeled: " + o;
                }
            }
            return null;
        } catch (RuntimeException e) {
            return "unreadable: " + e;
        }
    }

    /** Evergreen keywords that trigger nothing: the statics or replacements Forge builds for them are plain. */
    private static final Set<forge.game.keyword.Keyword> EVERGREEN = Set.of(forge.game.keyword.Keyword.FLYING,
            forge.game.keyword.Keyword.REACH, forge.game.keyword.Keyword.VIGILANCE, forge.game.keyword.Keyword.TRAMPLE,
            forge.game.keyword.Keyword.HASTE, forge.game.keyword.Keyword.MENACE, forge.game.keyword.Keyword.DEFENDER,
            forge.game.keyword.Keyword.FIRST_STRIKE, forge.game.keyword.Keyword.DOUBLE_STRIKE,
            forge.game.keyword.Keyword.DEATHTOUCH, forge.game.keyword.Keyword.LIFELINK, forge.game.keyword.Keyword.HEXPROOF,
            forge.game.keyword.Keyword.SHROUD, forge.game.keyword.Keyword.INDESTRUCTIBLE,
            forge.game.keyword.Keyword.INTIMIDATE, forge.game.keyword.Keyword.FEAR, forge.game.keyword.Keyword.SHADOW,
            forge.game.keyword.Keyword.HORSEMANSHIP);

    /**
     * A triggered ability of any origin, or a static or replacement ability other than one Forge builds for an evergreen
     * keyword that triggers nothing (flying's blocking rule), on {@code c}: what, or null (see {@link #plainReplacement}).
     */
    private static String notPlain(final Card c) {
        for (Trigger t : c.getTriggers()) {
            return "it has a triggered ability (" + t.getMode() + ")";
        }
        for (forge.game.staticability.StaticAbility st : c.getStaticAbilities()) {
            if (st.getKeyword() == null || !EVERGREEN.contains(st.getKeyword().getKeyword())) {
                return "it has a static ability (" + st.getParamOrDefault("Mode", "?") + ")";
            }
        }
        for (ReplacementEffect re : c.getReplacementEffects()) {
            if (re.getKeyword() == null || !EVERGREEN.contains(re.getKeyword().getKeyword())) {
                return "it has a replacement effect (" + re.getMode() + ")";
            }
        }
        return null;
    }

    /** A trigger on any card in a public zone, either side's, that could fire on {@code o}'s event (see above), or null. */
    private static String anyFires(final Game game, final Set<TriggerType> modes, final GameEntity o) {
        for (ZoneType zt : new ZoneType[] {ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command}) {
            for (Card h : game.getCardsIn(zt)) {
                final Zone z = game.getZoneOf(h);
                for (Trigger t : h.getTriggers()) {
                    if (t.isSuppressed() || !t.zonesCheck(z) || !modes.contains(t.getMode())) {
                        continue;
                    }
                    if (t.getMode() == TriggerType.Always) {
                        return "a state trigger could fire (" + h.getName() + ")";
                    }
                    if (LEAVES.contains(t.getMode()) && (!zoneOk(t.getParam("Origin"), "Battlefield")
                            || "Battlefield".equals(t.getParamOrDefault("Destination", "").trim()))) {
                        continue; // another origin, or entering only
                    }
                    if ("True".equalsIgnoreCase(t.getParamOrDefault("CombatDamage", ""))) {
                        continue; // the guard's effects deal no combat damage
                    }
                    if (fails(t, "ValidCard", o) || fails(t, "ValidCards", o) || fails(t, "ValidTarget", o)
                            || fails(t, "ValidPlayer", o)) {
                        continue; // Forge's own filter refuses the object
                    }
                    return "a trigger could fire: " + h.getName() + " (" + t.getMode() + ")";
                }
            }
        }
        return null;
    }

    /** A static ability of {@code c}'s own, keyword statics aside (see {@link #plainAsset}), or null. */
    private static String ownStatic(final Card c) {
        for (forge.game.staticability.StaticAbility st : c.getStaticAbilities()) {
            if (st.getKeyword() == null) {
                final String d = st.toString();
                return "it has a static ability of its own: " + (d.length() > 80 ? d.substring(0, 80) + "..." : d);
            }
        }
        return null;
    }

    /** A pending duration or return held by {@code c} (see {@link #plainAsset}), or null. */
    private static String pending(final Card c) {
        if (!c.getLeavesPlayCommands().isEmpty()) {
            return "a duration ends when it leaves";
        }
        if (!c.getUntilLeavesBattlefield().isEmpty() || c.hasExiledCard()) {
            return "it holds exiled cards";
        }
        if (c.hasGainControlTarget()) {
            return "it holds control of a permanent";
        }
        return null;
    }

    /** A delayed trigger of ours that remembers {@code c} or watches a card leave the battlefield (null = none). */
    private static String delayed(final Player me, final Card c) {
        for (Trigger t : me.getGame().getTriggerHandler().getDelayedTriggersView()) {
            final Card host = t.getHostCard();
            if (host == null || !(me.equals(host.getController()) || me.equals(host.getOwner()))) {
                continue;
            }
            for (Object o : t.getTriggerRemembered()) {
                if (o instanceof Card && ((Card) o).getId() == c.getId()) {
                    return "a delayed trigger of ours remembers it (" + host.getName() + ")";
                }
            }
            final TriggerType mode = t.getMode();
            if ((mode == TriggerType.ChangesZone || mode == TriggerType.ChangesZoneAll || mode == TriggerType.Destroyed
                    || mode == TriggerType.Exiled || mode == TriggerType.Sacrificed || mode == TriggerType.SacrificedOnce)
                    && zoneOk(t.getParam("Origin"), "Battlefield") && !fails(t, "ValidCard", c) && !fails(t, "ValidCards", c)) {
                return "a delayed trigger of ours watches it leave (" + host.getName() + ")";
            }
        }
        return null;
    }

    /** Another part of the ability makes the target act, or the ability's card has a state trigger (null = neither). */
    private static String projected(final SpellAbility p, final SpellAbility whole) {
        for (SpellAbility x : tree(whole)) {
            if (x == p || x.getApi() == null) {
                continue;
            }
            if (x.getApi() == ApiType.Fight) {
                return "projected: a fight";
            }
            final String src = x.getParam("DamageSource");
            if (src != null && (src.contains("Targeted") || src.contains("ParentTarget"))) {
                return "projected: the target deals damage";
            }
        }
        final Card host = whole.getHostCard();
        if (host != null) {
            for (Trigger t : host.getTriggers()) {
                if (t.getMode() == TriggerType.Always) {
                    return "projected: its card has a state trigger";
                }
            }
        }
        return null;
    }

    /** The cards whose triggers and replacement effects could fire: ours in public zones, and ours they control. */
    private static List<Card> sources(final Player me) {
        final List<Card> out = new ArrayList<>();
        for (ZoneType z : new ZoneType[] {ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command,
                ZoneType.Stack}) {
            for (Card c : me.getGame().getCardsIn(z)) {
                if (me.equals(c.getController()) || me.equals(c.getOwner())) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** Where this harm puts {@code target}: a graveyard, or the move's own destination (exile, a library, a hand). */
    private static String destination(final SpellAbility p, final Harm harm) {
        switch (harm) {
            case BOUNCE:
                return "Hand";
            case REMOVE:
                return p.getParamOrDefault("Destination", "Exile");
            default:
                return "Graveyard";
        }
    }

    /** Cast, copy, activation and targeting modes: set off by playing the ability at {@code target}, whatever it does. */
    private static final Set<TriggerType> CAST = Set.of(TriggerType.SpellCast, TriggerType.SpellCastOrCopy,
            TriggerType.SpellCopy, TriggerType.SpellAbilityCast, TriggerType.SpellAbilityCopy, TriggerType.AbilityCast,
            TriggerType.BecomesTarget, TriggerType.BecomesTargetOnce);
    private static final Set<TriggerType> DAMAGE_MODES = Set.of(TriggerType.DamageDone, TriggerType.DamageDoneOnce,
            TriggerType.DamageDealtOnce, TriggerType.DamageDoneOnceByController, TriggerType.DamagePreventedOnce,
            TriggerType.DamageAll, TriggerType.ExcessDamage, TriggerType.ExcessDamageAll);
    private static final Set<TriggerType> COUNTER_MODES = Set.of(TriggerType.CounterAdded, TriggerType.CounterAddedOnce,
            TriggerType.CounterAddedAll, TriggerType.CounterTypeAddedAll, TriggerType.CounterRemoved,
            TriggerType.CounterRemovedOnce);

    /**
     * {@link #fails} for a card filter on {@code target}; {@code loose} (the pick guard's unsure test) with a shrink: a
     * filter naming power or toughness never fails (the shrink changes what it reads).
     */
    private static boolean failsCard(final CardTraitBase t, final String param, final Card target, final Harm harm,
            final boolean loose) {
        if (!fails(t, param, target)) {
            return false;
        }
        if (!loose || harm != Harm.SHRINK) {
            return true;
        }
        final String v = t.getParam(param).toLowerCase();
        return !(v.contains("power") || v.contains("toughness"));
    }

    /** Could trigger {@code t} (active, ours) fire from this action? See {@link #couldFire}; {@code loose}: its loose read. */
    private static boolean fires(final Trigger t, final Player me, final SpellAbility p, final SpellAbility root,
            final Card source, final Card target, final Harm harm, final String dest, final boolean loose) {
        final TriggerType mode = t.getMode();
        if (mode == null) {
            return false;
        }
        if (CAST.contains(mode)) {
            final boolean spell = root.isSpell();
            if (!spell && (mode == TriggerType.SpellCast || mode == TriggerType.SpellCastOrCopy || mode == TriggerType.SpellCopy)) {
                return false; // spells only; this is an activated ability
            }
            if (spell && mode == TriggerType.AbilityCast) {
                return false;
            }
            if (mode == TriggerType.BecomesTarget || mode == TriggerType.BecomesTargetOnce) {
                return !fails(t, "ValidSource", root) && !fails(t, "ValidTarget", target);
            }
            return !fails(t, "ValidActivatingPlayer", me) && !fails(t, "ValidCard", source) && !fails(t, "ValidSA", root)
                    && !fails(t, "TargetsValid", target);
        }
        if (harm == null) {
            return false;
        }
        if (harm == Harm.DAMAGE && DAMAGE_MODES.contains(mode)) {
            if ("True".equalsIgnoreCase(t.getParam("CombatDamage"))) {
                return false; // this damage is not combat damage
            }
            return !fails(t, "ValidSource", source) && !fails(t, "ValidTarget", target);
        }
        if (COUNTER_MODES.contains(mode)) {
            if (harm != Harm.SHRINK || !counters(p, root)) {
                return false; // no counter moves
            }
            final String type = t.getParam("CounterType");
            final boolean added = mode != TriggerType.CounterRemoved && mode != TriggerType.CounterRemovedOnce;
            if (added && type != null && !type.contains("M1M1")) {
                return false;
            }
            return !fails(t, "ValidCard", target);
        }
        switch (mode) {
            case ChangesZone:
                return (leaves(t, dest) || harm == Harm.BOUNCE && entersAgain(t)) && !failsCard(t, "ValidCard", target, harm, loose);
            case ChangesZoneAll:
                return (leaves(t, dest) || harm == Harm.BOUNCE && entersAgain(t)) && !failsCard(t, "ValidCards", target, harm, loose);
            case Destroyed:
                return (harm == Harm.DESTROY || harm == Harm.DAMAGE) && !fails(t, "ValidCard", target);
            case Sacrificed:
            case SacrificedOnce:
                return harm == Harm.SACRIFICE && !fails(t, "ValidCard", target) && !fails(t, "ValidPlayer", me);
            case Exiled:
                return "Exile".equals(dest) && (!t.hasParam("Origin") || t.getParam("Origin").contains("Battlefield")
                        || "Any".equals(t.getParam("Origin"))) && !fails(t, "ValidCard", target);
            default:
                return false;
        }
    }

    /** Could replacement effect {@code re} (active, ours) act on this action's event? Filters as in {@link #fires}. */
    private static boolean replaces(final ReplacementEffect re, final SpellAbility p, final Card source, final Card target,
            final Harm harm, final String dest, final boolean loose) {
        if (harm == null) {
            return false;
        }
        final ReplacementType mode = re.getMode();
        if (mode == null) {
            return false;
        }
        switch (mode) {
            case Moved:
                return (leaves(re, dest) || harm == Harm.BOUNCE && entersAgain(re)) && !failsCard(re, "ValidCard", target, harm, loose);
            case Destroy:
                return (harm == Harm.DESTROY || harm == Harm.DAMAGE) && !fails(re, "ValidCard", target);
            case DamageDone:
            case DealtDamage:
            case AssignDealDamage:
                return harm == Harm.DAMAGE && !fails(re, "ValidSource", source) && !fails(re, "ValidTarget", target)
                        && !fails(re, "ValidCard", target);
            case AddCounter:
            case RemoveCounter:
                return harm == Harm.SHRINK && counters(p, p.getRootAbility() == null ? p : p.getRootAbility())
                        && !fails(re, "ValidCard", target) && !fails(re, "ValidObject", target);
            default:
                return false;
        }
    }

    /** The harmful part {@code p}, or a part of its ability, puts or removes counters. */
    private static boolean counters(final SpellAbility p, final SpellAbility root) {
        final List<SpellAbility> parts = new ArrayList<>(tree(root));
        parts.add(p);
        for (SpellAbility x : parts) {
            final ApiType a = x.getApi();
            if (a == ApiType.PutCounter || a == ApiType.PutCounterAll || a == ApiType.RemoveCounter
                    || a == ApiType.RemoveCounterAll) {
                return true;
            }
        }
        return false;
    }

    /** A zone-change filter that a move from the battlefield to {@code dest} passes (absent or "Any" passes). */
    private static boolean leaves(final CardTraitBase t, final String dest) {
        return zoneOk(t.getParam("Origin"), "Battlefield") && zoneOk(t.getParam("Destination"), dest)
                && !listed(t.getParam("ExcludedOrigins"), "Battlefield") && !listed(t.getParam("ExcludedDestinations"), dest)
                && !listed(t.getParam("ExcludeDestination"), dest);
    }

    /** A zone-change filter that a card entering the battlefield passes (a bounced card played again). */
    private static boolean entersAgain(final CardTraitBase t) {
        return zoneOk(t.getParam("Destination"), "Battlefield") && !listed(t.getParam("ExcludedDestinations"), "Battlefield")
                && !listed(t.getParam("ExcludeDestination"), "Battlefield");
    }

    private static boolean zoneOk(final String param, final String zone) {
        return param == null || param.isBlank() || "Any".equals(param.trim()) || listed(param, zone);
    }

    private static boolean listed(final String param, final String zone) {
        if (param == null || zone == null) {
            return false;
        }
        for (String z : param.split(",")) {
            if (z.trim().equals(zone)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code t} states {@code param} and it surely does not match {@code o}. A filter on nothing to match, or one Forge
     * cannot evaluate here (an error), does not fail.
     */
    private static boolean fails(final CardTraitBase t, final String param, final Object o) {
        if (o == null || !t.hasParam(param)) {
            return false;
        }
        try {
            return !t.matchesValidParam(param, o);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
