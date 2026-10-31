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
 * or activates the answer with its live choices (Forge AI pays the costs), takes the {@link #effectKey}, resolves only that
 * stack item with no responses, applies state-based actions and takes the key again. The key covers every player's life,
 * poison, counters and keywords, every card of every zone (library order included) with its controller, tapped state, P/T,
 * damage, counters, type line, keywords and attachments, the combat, the monarch, pending until-end-of-turn commands and
 * delayed triggers. It leaves out mana pools and, for a spell, the spell's own card: the card and mana spent. When the key is
 * unchanged in every copy, the answer is a dud.
 *
 * <p><b>The guard.</b> ON: a dud is vetoed for this priority and Forge AI is asked again without it (as the policy pilot
 * re-asks, {@link forge.ai.AiController#setPolicyVeto}); its new answer is checked the same way, up to {@link #MAX_VETOES}
 * vetoes, then the seat passes. SHADOW: counts and logs, plays the answer. Anything the check cannot establish (the answer
 * not found or not payable in a copy, a copy or resolution error, a stack the copier does not support) keeps the answer.
 * Abilities whose point is information or mana the key cannot see are never checked: scry, surveil, look at / reveal / dig,
 * rearrange, clash, mana, extra turns and phases.
 */
public final class DudCast {

    /** Vetoes per priority before the seat passes. */
    static final int MAX_VETOES = 3;

    /** APIs whose effect may be invisible to the key (information, mana, turn structure): never called a dud. */
    static final Set<ApiType> EXEMPT = EnumSet.of(ApiType.Scry, ApiType.Surveil, ApiType.RearrangeTopOfLibrary,
            ApiType.PeekAndReveal, ApiType.RevealHand, ApiType.Reveal, ApiType.LookAt, ApiType.Clash, ApiType.Dig,
            ApiType.DigMultiple, ApiType.DigUntil, ApiType.Mana, ApiType.ManaReflected, ApiType.AddTurn, ApiType.AddPhase);

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
            // a permanent spell always puts its permanent onto the battlefield (dead ETBs are deadEtb's)
            if (sa.getApi() == ApiType.PermanentCreature || sa.getApi() == ApiType.PermanentNoncreature) {
                return false;
            }
        } else if (!sa.isActivatedAbility()) {
            return false;
        }
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.getApi() == null || EXEMPT.contains(s.getApi())) {
                return false;
            }
        }
        return true;
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
            final Boolean changed = resolveInCopy(p, liveSa.isSpell());
            if (changed == null) {
                return Verdict.UNKNOWN;
            }
            if (changed) {
                return Verdict.EFFECT;
            }
        }
        return Verdict.DUD;
    }

    /** In the prepared copy's own scopes: cast, key, resolve the item, key. Null = could not tell. */
    static Boolean resolveInCopy(LookaheadSearch.Prepared p, boolean spell) {
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
            final SpellAbility top = g.getStack().peekAbility();
            if (top == null || top.getHostCard() == null || top.getHostCard().getId() != host.getId()) {
                lastWhy = "top of stack is " + top;
                return null;
            }
            final int exclude = spell ? host.getId() : -1;
            final String before = effectKey(g, exclude);
            g.getStack().resolveStack();
            g.getAction().checkStateEffects(false, new HashSet<>());
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
        sb.append(" until=").append(until[0]).append(" delayed=").append(delayedTriggers(g)).append('\n');
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
