package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import com.google.common.collect.Multiset;

import forge.ai.AiCache;
import forge.ai.AiFixes;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Dud-cast guard of the live look-ahead seat (lane live-ai-quality-1030, owner 2026-10-30: "it sometimes casts spells that do
 * nothing": a reanimate spell with no valid target, Exhume with an empty own graveyard; measured in live play: Currency
 * Converter's exile ability with nothing exiled, Figure of Destiny re-levelled to the 2/2 it already is, Shallow Grave over a
 * non-creature top card). {@link LookaheadSearch.Config#dudCast} off|shadow|on, OFF by default: nothing here runs and every
 * decision is unchanged.
 *
 * <p><b>The check.</b> Before the seat commits its priority answer (Forge's own or a search departure), if that answer is a
 * non-permanent spell or a non-mana activated ability, the guard plays it in {@link LookaheadSearch.Config#dudWorlds} game
 * copies, each with the hidden information re-drawn from the seat's belief ({@link LookaheadSearch#prepare}: the opponents'
 * hand and library from the belief pool, our own library order shuffled; never the real hidden cards). In each copy it casts
 * or activates the answer with its live choices (Forge AI pays the costs) and puts the pending triggers on the stack: a cast
 * trigger (prowess, magecraft, Displacer Kitten) means the play does something. Otherwise it takes the {@link #effectKey},
 * resolves only that stack item with no responses, applies state-based actions and pending triggers (a new trigger is an
 * effect) and takes the key again. The key covers every player's life, poison, counters and keywords, every card of every
 * zone (library order included) with its controller, tapped state, P/T, damage, counters, type line, keywords, goad and
 * attachments, the combat, the monarch, every phase's pending commands (until end of turn, until your next turn, ...) and
 * delayed triggers. It leaves out mana pools and, for a spell, the spell's own card: the card and mana spent. When the key
 * is unchanged in every copy, the answer is a dud.
 *
 * <p><b>The guard.</b> ON: a dud is vetoed for this priority and Forge AI is asked again without it (as the policy pilot
 * re-asks, {@link forge.ai.AiController#setPolicyVeto}); its new answer is checked the same way, up to {@link #MAX_VETOES}
 * vetoes, then the seat passes. SHADOW: counts and logs, plays the answer. Anything the check cannot establish (a non-empty
 * stack, where a stack-only effect such as Stifle is outside the key; the answer not found or not payable in a copy; a copy
 * or resolution error) keeps the answer. Never checked: permanent spells (Auras too), planeswalker abilities and any ability
 * whose cost adds counters (the gain is the cost), and abilities whose point is information or mana the key cannot see:
 * scry, surveil, look at / reveal / dig, a reveal-and-choose discard (Duress), rearrange, clash, mana. Every ability the card
 * can resolve is scanned (the SubAbility chain and every ability held outside it: vote and choice lists, Charm modes, Repeat
 * and RepeatEach sub-abilities). A resolution-time vote or choice is UNKNOWN, since the copy's AI pick need not be the live
 * game's, except a root Vote or GenericChoice over listed outcomes (Plea for Power): each outcome is resolved in its own
 * copy and the answer is a dud only when none changes the key. The key holds the turn structure to come (the extra-turn
 * queue, extra phases, skipped damage steps).
 */
public final class DudCast {

    /** Vetoes per priority before the seat passes. */
    static final int MAX_VETOES = 3;

    /** APIs whose effect may be invisible to the key (information, mana, turn structure): never called a dud. */
    static final Set<ApiType> EXEMPT = EnumSet.of(ApiType.Scry, ApiType.Surveil, ApiType.RearrangeTopOfLibrary,
            ApiType.PeekAndReveal, ApiType.RevealHand, ApiType.Reveal, ApiType.LookAt, ApiType.Clash, ApiType.Dig,
            ApiType.DigMultiple, ApiType.DigUntil, ApiType.Mana, ApiType.ManaReflected);

    /** The check's verdict for one answer. */
    enum Verdict { EFFECT, DUD, UNKNOWN, SKIPPED }

    /** Diagnostics (tests, logs): why the last check could not tell, or null. */
    static volatile String lastWhy;

    private DudCast() {
    }

    /** True when the answer is one the guard checks: a non-permanent spell or a non-mana activated ability, not exempt. */
    static boolean checkable(SpellAbility sa) {
        if (sa == null || sa.getHostCard() == null || sa.isLandAbility() || sa.isManaAbility()) {
            return false;
        }
        if (sa.isSpell()) {
            // a permanent spell (an Aura too: Animate Dead is ApiType Attach) puts its permanent onto the battlefield;
            // dead ETBs are deadEtb's (review of #153, S1-a)
            if (sa.getHostCard().isPermanent() || sa.getApi() == ApiType.PermanentCreature
                    || sa.getApi() == ApiType.PermanentNoncreature || sa.getApi() == ApiType.Attach) {
                return false;
            }
        } else if (!sa.isActivatedAbility()) {
            return false;
        } else if (sa.isPwAbility() || (sa.getPayCosts() != null
                && sa.getPayCosts().hasSpecificCostType(forge.game.cost.CostPutCounter.class))) {
            // a cost that adds counters (a loyalty +N) is itself a gain, paid before the key is taken (review S1-b)
            return false;
        }
        for (SpellAbility s : chain(sa)) {
            if (s.getApi() == null || EXEMPT.contains(s.getApi())) {
                return false;
            }
            // Duress / Thoughtseize: seeing the hand has value with no legal pick (review S2)
            if (s.getApi() == ApiType.Discard && s.hasParam("Mode") && s.getParam("Mode").startsWith("Reveal")) {
                return false;
            }
        }
        return true;
    }

    /** Resolution-time votes and choices: the copy's AI pick need not be the live game's. */
    static final Set<ApiType> CHOICES = EnumSet.of(ApiType.Vote, ApiType.GenericChoice, ApiType.Charm, ApiType.ChooseCard,
            ApiType.ChooseColor, ApiType.ChooseDirection, ApiType.ChooseEvenOdd, ApiType.ChooseNumber, ApiType.ChoosePlayer,
            ApiType.ChooseSector, ApiType.ChooseSource, ApiType.ChooseType, ApiType.FlipCoin, ApiType.RollDice,
            ApiType.NameCard, ApiType.RepeatEach);

    /**
     * Every ability a card may resolve from {@code sa}: its SubAbility chain and every ability held outside it (vote and
     * generic-choice lists, Charm modes, RepeatEach / Repeat sub-abilities, additional abilities), each once.
     */
    static List<SpellAbility> chain(SpellAbility sa) {
        final List<SpellAbility> out = new ArrayList<>();
        final Set<SpellAbility> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final java.util.ArrayDeque<SpellAbility> todo = new java.util.ArrayDeque<>();
        todo.add(sa);
        while (!todo.isEmpty()) {
            final SpellAbility s = todo.poll();
            if (s == null || !seen.add(s)) {
                continue;
            }
            out.add(s);
            if (s.getSubAbility() != null) {
                todo.add(s.getSubAbility());
            }
            for (SpellAbility x : s.getAdditionalAbilities().values()) {
                todo.add(x);
            }
            for (List<? extends SpellAbility> l : s.getAdditionalAbilityLists().values()) {
                todo.addAll(l);
            }
        }
        return out;
    }

    /** The first vote or choice API anywhere in the answer's abilities, or null. */
    static ApiType resolutionChoice(SpellAbility sa) {
        for (SpellAbility s : chain(sa)) {
            if (s.getApi() != null && CHOICES.contains(s.getApi())) {
                return s.getApi();
            }
        }
        return null;
    }

    /** The veto key of an answer: its host card and description (the look-ahead candidate key's). */
    static String key(SpellAbility sa) {
        return sa.getHostCard().getId() + "|" + sa.getDescription();
    }

    /**
     * The guarded answer (ON) or the answer itself (SHADOW); counts in the search's stats. Called by
     * {@link PlayerControllerLookahead} in the live game only, after the search, and only when dudCast is not OFF.
     */
    static List<SpellAbility> guard(LookaheadSearch search, PlayerControllerAi ctrl, List<SpellAbility> answer) {
        final LookaheadSearch.Config cfg = search.getConfig();
        final LookaheadSearch.Stats st = search.getStats();
        final Game live = ctrl.getGame();
        final Player me = ctrl.getPlayer();
        final Set<String> vetoed = new HashSet<>();
        List<SpellAbility> a = answer;
        for (int round = 0; ; round++) {
            final SpellAbility sa = a == null || a.size() != 1 ? null : a.get(0);
            if (!checkable(sa)) {
                return a;
            }
            final long t0 = System.nanoTime();
            final Verdict v = check(search, live, me, sa, round);
            st.dudChecks++;
            st.dudNanos += System.nanoTime() - t0;
            if (v == Verdict.UNKNOWN) {
                st.dudUnknown++;
            }
            if (v != Verdict.DUD) {
                return a;
            }
            st.dudFound++;
            final String what = sa.getHostCard().getName() + " :: " + sa.getDescription();
            st.dudCards.merge(sa.getHostCard().getName(), 1L, Long::sum);
            if (cfg.dudCast != AiFixes.Mode.ON) {
                System.err.println("[dud-cast] shadow T" + live.getPhaseHandler().getTurn() + " "
                        + live.getPhaseHandler().getPhase() + " " + what);
                return a;
            }
            if (round >= MAX_VETOES) {
                st.dudPassed++;
                System.err.println("[dud-cast] pass T" + live.getPhaseHandler().getTurn() + " "
                        + live.getPhaseHandler().getPhase() + " " + what);
                return null;
            }
            vetoed.add(key(sa));
            st.dudVetoed++;
            System.err.println("[dud-cast] veto T" + live.getPhaseHandler().getTurn() + " "
                    + live.getPhaseHandler().getPhase() + " " + what);
            ctrl.getAi().setPolicyVeto(s -> s.getHostCard() != null && vetoed.contains(key(s)));
            try {
                a = ctrl.getAi().chooseSpellAbilityToPlay();
            } finally {
                ctrl.getAi().setPolicyVeto(null);
            }
        }
    }

    /** The check of one answer over the configured worlds (see the class comment). */
    static Verdict check(LookaheadSearch search, Game live, Player liveMe, SpellAbility liveSa, int round) {
        if (!checkable(liveSa)) {
            return Verdict.SKIPPED;
        }
        if (!live.getStack().isEmpty()) {
            // a stack-only effect (Stifle, a redirect, a counter) is outside the key: never call it a dud (review S2)
            lastWhy = "the stack is not empty";
            return Verdict.UNKNOWN;
        }
        final ApiType choice = resolutionChoice(liveSa);
        final boolean perChoice = choice != null && listedChoice(liveSa);
        if (choice != null && !perChoice) {
            // a vote or a choice the copy resolves with an AI's pick (Plea for Power: time or knowledge) need not match
            // the live game's (cut-4 smoke, 2026-10-30: six false Plea for Power vetoes)
            lastWhy = "resolution makes a choice (" + choice + ")";
            return Verdict.UNKNOWN;
        }
        final String ref = copylessReference(liveSa);
        if (ref != null && hasLinks(live, liveSa.getHostCard())) {
            // GameCopier does not carry "exiled with" / imprint links (its TODO ExiledWith): Currency Converter with a card
            // exiled with it would read empty in every copy (review of #153, S1 of the re-check)
            lastWhy = "references " + ref + ", which the copy does not carry";
            return Verdict.UNKNOWN;
        }
        final LookaheadSearch.Config cfg = search.getConfig();
        final int worlds = Math.max(1, cfg.dudWorlds);
        final LookaheadSearch.Cand c = new LookaheadSearch.Cand(liveSa, true);
        final int turn = live.getPhaseHandler().getTurn();
        for (int w = 0; w < worlds; w++) {
            final long seed = LookaheadSearch.mix(cfg.seed ^ 0xd0dca57L,
                    ((long) turn << 32) ^ ((long) liveSa.getHostCard().getId() << 12) ^ (round << 8) ^ w);
            final LookaheadSearch.Prepared p;
            try {
                p = search.prepare(live, liveMe, c, liveSa, seed, true, null);
            } catch (RuntimeException e) {
                lastWhy = "prepare: " + e;
                return Verdict.UNKNOWN;
            }
            if (p.failed || p.first == null || p.first.isEmpty()) {
                lastWhy = "not in copy: " + (p.error != null ? String.valueOf(p.error) : p.missWhy);
                return Verdict.UNKNOWN;
            }
            final String lost = historyLost(live, p.g);
            if (lost != null) {
                // the copy does not carry this turn's history (revolt: Fatal Push on a 3-drop): the copy's verdict could
                // be wrong, so never call it a dud (live report 2026-10-31T02-13-20)
                lastWhy = "turn history not in the copy: " + lost;
                return Verdict.UNKNOWN;
            }
            final Boolean changed = perChoice ? anyChoiceChanges(search, live, liveMe, c, liveSa, seed, p)
                    : resolveInCopy(p, liveSa.isSpell(), -1);
            if (changed == null) {
                return Verdict.UNKNOWN;
            }
            if (changed) {
                return Verdict.EFFECT;
            }
        }
        return Verdict.DUD;
    }

    /**
     * The first per-turn history fact a condition may read that differs between the live game and its copy (null when they
     * agree): revolt, spells cast, life gained and lost, lands played, for every player. (Cards drawn this turn
     * are not copied either, but nearly every copy differs there after the draw step and few conditions read it.)
     */
    static String historyLost(Game live, Game copy) {
        for (Player lp : live.getPlayers()) {
            Player cp = null;
            for (Player x : copy.getPlayers()) {
                if (x.getId() == lp.getId()) {
                    cp = x;
                }
            }
            if (cp == null) {
                return "player " + lp.getId();
            }
            if (lp.hasRevolt() != cp.hasRevolt()) {
                return "revolt";
            }
            if (lp.getSpellsCastThisTurn() != cp.getSpellsCastThisTurn()) {
                return "spells cast this turn";
            }
            if (lp.getLifeGainedThisTurn() != cp.getLifeGainedThisTurn() || lp.getLifeLostThisTurn() != cp.getLifeLostThisTurn()) {
                return "life gained/lost this turn";
            }
            if (lp.getLandsPlayedThisTurn() != cp.getLandsPlayedThisTurn()) {
                return "lands played this turn";
            }
            if (lp.getCreaturesAttackedThisTurn().size() != cp.getCreaturesAttackedThisTurn().size()) {
                return "creatures attacked this turn (raid)";
            }
        }
        if (live.getStack().getSpellsCastThisTurn().size() != copy.getStack().getSpellsCastThisTurn().size()) {
            return "spells cast this turn (ThisTurnCast)";
        }
        return null;
    }

    /**
     * True when the live game holds a link the copy would lose: a card exiled with {@code host}, or a card it imprinted.
     * With none (Currency Converter with nothing exiled with it) the copy is faithful and the check goes on.
     */
    static boolean hasLinks(Game live, Card host) {
        if (host == null) {
            return true;
        }
        if (!host.getImprintedCards().isEmpty()) {
            return true;
        }
        for (Player p : live.getPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Exile)) {
                if (c.getExiledWith() != null && c.getExiledWith().getId() == host.getId()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Words in an ability's parameters naming links GameCopier does not copy. */
    static final String[] COPYLESS = {"ExiledWith", "ExiledBy", "Imprinted", "Imprint"};

    /** The first copy-less link (exiled with / imprint) any part of the answer's chain names, or null. */
    static String copylessReference(SpellAbility sa) {
        for (SpellAbility s : chain(sa)) {
            for (java.util.Map.Entry<String, String> e : s.getMapParams().entrySet()) {
                for (String w : COPYLESS) {
                    if (e.getKey().contains(w) || (e.getValue() != null && e.getValue().contains(w))) {
                        return w;
                    }
                }
            }
            for (String svar : new String[] {"X", "Y", "Z"}) {
                final String v = s.getHostCard() == null ? null : s.getHostCard().getSVar(svar);
                if (v != null) {
                    for (String w : COPYLESS) {
                        if (v.contains(w)) {
                            return w;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** In the prepared copy's own scopes: cast, key, resolve the item, key. Null = could not tell. */
    /**
     * True when the answer's only resolution-time choice is its own root Vote or GenericChoice over a listed set of abilities
     * (Plea for Power: time or knowledge), and no listed ability makes a choice of its own: then every outcome is tried.
     */
    static boolean listedChoice(SpellAbility sa) {
        if (sa.getApi() != ApiType.Vote && sa.getApi() != ApiType.GenericChoice) {
            return false;
        }
        // council's dilemma (an outcome per vote: StoreVoteNum's VoteNum, EachVote) is only set while the vote resolves
        if (sa.hasParam("StoreVoteNum") || sa.hasParam("EachVote")) {
            return false;
        }
        final List<AbilitySub> choices = sa.getAdditionalAbilityList("Choices");
        if (choices == null || choices.isEmpty()) {
            return false;
        }
        for (SpellAbility s : chain(sa)) {
            if (s != sa && s.getApi() != null && CHOICES.contains(s.getApi())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Every outcome of a listed vote or choice, each in its own copy of the world {@code first} was prepared for: cast the
     * answer, then resolve that one outcome (and the answer's own sub-abilities) in place of the vote. TRUE when any outcome
     * changes the key, FALSE when none does, null when one cannot be told.
     */
    static Boolean anyChoiceChanges(LookaheadSearch search, Game live, Player liveMe, LookaheadSearch.Cand c, SpellAbility liveSa,
            long seed, LookaheadSearch.Prepared first) {
        final int n = liveSa.getAdditionalAbilityList("Choices").size();
        for (int i = 0; i < n; i++) {
            final LookaheadSearch.Prepared p = i == 0 ? first : search.prepare(live, liveMe, c, liveSa, seed, true, null);
            if (p.failed || p.first == null || p.first.isEmpty()) {
                return null;
            }
            final Boolean changed = resolveInCopy(p, liveSa.isSpell(), i);
            if (changed == null || changed) {
                return changed;
            }
        }
        return Boolean.FALSE;
    }

    /** As {@link #resolveInCopy(LookaheadSearch.Prepared, boolean, int)} with no forced outcome. */
    static Boolean resolveInCopy(LookaheadSearch.Prepared p, boolean spell) {
        return resolveInCopy(p, spell, -1);
    }

    /**
     * In the prepared copy's own scopes: cast, key, resolve the item, key. With {@code outcome} >= 0 the item is taken off the
     * stack and that listed outcome resolves in its place (then the item's own sub-abilities). Null = could not tell.
     */
    static Boolean resolveInCopy(LookaheadSearch.Prepared p, boolean spell, int outcome) {
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(p.rnd);
        AiCache.installScope(p.cache);
        forge.util.IdScope.install(p.ids);
        try {
            final Game g = p.g;
            final Player me = p.me;
            final SpellAbility sa = p.first.get(0);
            final Card host = sa.getHostCard();
            final int stackBefore = g.getStack().size();
            final boolean ok = ComputerUtil.handlePlayingSpellAbility(me, sa, x -> { });
            if (!ok || g.getStack().size() != stackBefore + 1) {
                lastWhy = "cast: ok=" + ok + " stack " + stackBefore + "->" + g.getStack().size();
                return null;
            }
            // cast triggers (prowess, magecraft, Displacer Kitten): casting it already does something (review S1-a, S2)
            g.getStack().addAllTriggeredAbilitiesToStack();
            if (g.getStack().size() > stackBefore + 1) {
                return Boolean.TRUE;
            }
            final SpellAbility top = g.getStack().peekAbility();
            if (top == null || top.getHostCard() == null || top.getHostCard().getId() != host.getId()) {
                lastWhy = "top of stack is " + top;
                return null;
            }
            final int exclude = spell ? host.getId() : -1;
            final String before = effectKey(g, exclude);
            if (outcome >= 0) {
                final SpellAbility played = g.getStack().peekAbility();
                final List<AbilitySub> choices = played.getAdditionalAbilityList("Choices");
                if (choices == null || outcome >= choices.size()) {
                    lastWhy = "outcome " + outcome + " not in the copy";
                    return null;
                }
                g.getStack().remove(g.getStack().peek());
                forge.game.ability.AbilityUtils.resolve(choices.get(outcome));
                if (played.getSubAbility() != null) {
                    forge.game.ability.AbilityUtils.resolve(played.getSubAbility());
                }
            } else {
                g.getStack().resolveStack();
            }
            g.getAction().checkStateEffects(false, new HashSet<>());
            g.getStack().addAllTriggeredAbilitiesToStack();
            if (g.getStack().size() > stackBefore) {
                return Boolean.TRUE; // the resolution put something on the stack (a trigger, a copy): it did something
            }
            return !before.equals(effectKey(g, exclude));
        } catch (RuntimeException | StackOverflowError e) {
            lastWhy = "resolve: " + e;
            return null;
        } finally {
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
    }

    /** The state the check compares (see the class comment); {@code excludeId} is the spell's own card, or -1. */
    static String effectKey(Game g, int excludeId) {
        final StringBuilder sb = new StringBuilder(4096);
        final Player monarch = g.getMonarch();
        sb.append("mon=").append(monarch == null ? -1 : monarch.getId());
        final int[] until = GameCopier.untilCounts(g);
        sb.append(" until=").append(until[0]).append(" delayed=").append(delayedTriggers(g)).append(" phases=")
                .append(phaseCommands(g)).append(" turns=").append(turnStructure(g)).append('\n');
        final Combat combat = g.getPhaseHandler().getCombat();
        if (combat != null) {
            sb.append("combat");
            for (Card a : combat.getAttackers()) {
                final GameEntity d = combat.getDefenderByAttacker(a);
                sb.append(' ').append(a.getId()).append('>').append(d == null ? "-" : d.getId())
                        .append(combat.isBlocked(a) ? "b" : "");
            }
            sb.append(" |");
            for (Card b : combat.getAllBlockers()) {
                sb.append(' ').append(b.getId()).append(':');
                for (Card a : combat.getAttackersBlockedBy(b)) {
                    sb.append(a.getId()).append(',');
                }
            }
            sb.append('\n');
        }
        for (Player p : g.getPlayers()) {
            sb.append("P").append(p.getId()).append(" life=").append(p.getLife()).append(" poison=")
                    .append(p.getPoisonCounters()).append(" lost=").append(p.hasLost()).append(" won=").append(p.hasWon())
                    .append(" hand=").append(p.getMaxHandSize());
            counters(sb, p);
            final List<String> kw = new ArrayList<>();
            for (KeywordInterface k : p.getKeywords()) {
                kw.add(k.getOriginal());
            }
            Collections.sort(kw);
            sb.append(" kw").append(kw).append('\n');
            for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile,
                    ZoneType.Battlefield, ZoneType.Command}) {
                sb.append(' ').append(z).append(':');
                for (Card c : p.getCardsIn(z)) {
                    if (c.getId() != excludeId) {
                        card(sb, c);
                    }
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static void card(StringBuilder sb, Card c) {
        sb.append(" {").append(c.getId()).append(' ').append(c.getName()).append(' ').append(c.getCurrentStateName())
                .append(c.isTapped() ? " T" : " U").append(c.isFaceDown() ? " fd" : "").append(c.isPhasedOut() ? " po" : "")
                .append(" c").append(c.getController() == null ? -1 : c.getController().getId());
        if (c.isInPlay()) {
            sb.append(" pt").append(c.getNetPower()).append('/').append(c.getNetToughness()).append(" d").append(c.getDamage())
                    .append(" ty[").append(c.getType()).append(']');
            final List<String> kw = new ArrayList<>();
            for (KeywordInterface k : c.getKeywords()) {
                kw.add(k.getOriginal());
            }
            Collections.sort(kw);
            sb.append(" kw").append(kw);
            if (c.getEntityAttachedTo() != null) {
                sb.append(" at").append(c.getEntityAttachedTo().getId());
            }
            if (c.isSick()) {
                sb.append(" sick");
            }
            if (!c.getGoaded().isEmpty()) {
                final List<Integer> gs = new ArrayList<>();
                for (Player p : c.getGoaded()) {
                    gs.add(p.getId());
                }
                Collections.sort(gs);
                sb.append(" goad").append(gs);
            }
        }
        counters(sb, c);
        if (c.getExiledWith() != null) {
            sb.append(" xw").append(c.getExiledWith().getId());
        }
        boolean first = true;
        for (Object o : c.getRemembered()) {
            sb.append(first ? " rem[" : ",").append(o instanceof GameObject go ? objId(go) : String.valueOf(o));
            first = false;
        }
        if (!first) {
            sb.append(']');
        }
        sb.append('}');
    }

    private static String objId(GameObject o) {
        if (o instanceof Card c) {
            return "C" + c.getId();
        }
        if (o instanceof Player p) {
            return "P" + p.getId();
        }
        return String.valueOf(o);
    }

    private static void counters(StringBuilder sb, GameEntity e) {
        final Map<String, Integer> cn = new TreeMap<>();
        for (Multiset.Entry<CounterType> x : e.getCounters().entrySet()) {
            cn.put(String.valueOf(x.getElement()), x.getCount());
        }
        if (!cn.isEmpty()) {
            sb.append(" n").append(cn);
        }
    }

    /** The pending commands of every phase (until EOT, until your next turn, ...): per phase and list, their counts. */
    private static String phaseCommands(Game g) {
        final StringBuilder sb = new StringBuilder();
        final forge.game.phase.Phase[] phases = {g.getUntap(), g.getUpkeep(), g.getBeginOfCombat(), g.getEndOfCombat(),
            g.getEndOfTurn(), g.getCleanup()};
        for (forge.game.phase.Phase ph : phases) {
            for (String f : new String[] {"at", "until", "untilMap", "untilEndMap", "registerMap"}) {
                try {
                    final java.lang.reflect.Field fl = forge.game.phase.Phase.class.getDeclaredField(f);
                    fl.setAccessible(true);
                    final Object v = fl.get(ph);
                    sb.append(v instanceof java.util.Collection<?> c ? c.size()
                            : v instanceof com.google.common.collect.Multimap<?, ?> m ? m.size() : -1).append(',');
                } catch (ReflectiveOperationException | RuntimeException e) {
                    sb.append("?,");
                }
            }
            sb.append('|');
        }
        return sb.toString();
    }

    /**
     * The turn structure to come (cut-4 smoke: an extra turn was invisible): the extra-turn queue (whose turns, in order),
     * the extra phases per phase type (extra combats, extra mains), skipped damage steps and a repeated cleanup.
     */
    static String turnStructure(Game g) {
        final StringBuilder sb = new StringBuilder();
        final forge.game.phase.PhaseHandler ph = g.getPhaseHandler();
        try {
            java.lang.reflect.Field f = forge.game.phase.PhaseHandler.class.getDeclaredField("extraTurns");
            f.setAccessible(true);
            sb.append("x[");
            for (Object t : (java.util.Collection<?>) f.get(ph)) {
                final Player p = t instanceof forge.game.phase.ExtraTurn et ? et.getPlayer() : null;
                sb.append(p == null ? "?" : p.getId()).append(',');
            }
            sb.append(']');
            f = forge.game.phase.PhaseHandler.class.getDeclaredField("extraPhases");
            f.setAccessible(true);
            sb.append(" xp{");
            for (java.util.Map.Entry<?, ?> e : ((Map<?, ?>) f.get(ph)).entrySet()) {
                sb.append(e.getKey()).append('=').append(((java.util.Collection<?>) e.getValue()).size()).append(',');
            }
            sb.append('}');
            for (String b : new String[] {"skipDamageSteps", "bRepeatCleanup"}) {
                f = forge.game.phase.PhaseHandler.class.getDeclaredField(b);
                f.setAccessible(true);
                sb.append(' ').append(b).append('=').append(f.get(ph));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            sb.append(" ?").append(e.getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static int delayedTriggers(Game g) {
        try {
            final java.lang.reflect.Field f = forge.game.trigger.TriggerHandler.class.getDeclaredField("delayedTriggers");
            f.setAccessible(true);
            return ((List<?>) f.get(g.getTriggerHandler())).size();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }
}
