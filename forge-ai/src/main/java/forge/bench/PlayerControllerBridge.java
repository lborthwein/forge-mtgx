/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.bench;

import com.google.common.collect.*;
import forge.LobbyPlayer;
import forge.ai.AiCostDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.GameActionUtil;
import forge.game.ability.AbilityUtils;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.combat.AttackConstraints;
import forge.game.combat.AttackRequirement;
import forge.game.combat.CombatUtil;
import forge.game.cost.*;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.*;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.*;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.*;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;
import java.util.function.Predicate;

/**
 * mtgx benchmark bridge controller (wire protocol v1).
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link BenchSession.Mode#NULL} — behaviour identical to {@link PlayerControllerAi};
 *       every controller entry point is counted by name. This is both the protocol null
 *       (it must reproduce pure-Forge results) and the decision-surface measurement that
 *       tells us which overrides are worth writing.</li>
 *   <li>{@link BenchSession.Mode#BRIDGE} — the strategic decision set is answered by the
 *       host over stdio; everything else delegates to Forge's AI and is counted as
 *       contamination.</li>
 * </ul>
 *
 * <p>Every bridged decision validates the host's answer before applying it
 * ({@code CombatUtil} for combat, {@code TargetRestrictions}/{@code SpellAbility.canTarget}
 * for targeting, membership + min/max for menus). An illegal or unparseable answer is a
 * <em>refusal</em>: the call falls through to {@code super} and is counted separately from
 * an answer that explicitly asked to delegate.
 */
public class PlayerControllerBridge extends PlayerControllerAi implements AiCostDecision.PaymentDecisions {

    private final BenchSession session;
    private final BenchSession.Mode mode;
    private final int seat;
    private final CallCounter counters;
    /**
     * True while {@link #legalSpellAbilities} is enumerating the priority menu.
     * {@code ComputerUtilAbility.getOriginalAndAltCostAbilities} asks the controller to
     * pick optional costs WHILE BUILDING the list, and it drops the unkicked ability when
     * the answer is non-empty. Asking the host there would be a round trip per ability per
     * frame about a cast it has not chosen; declining instead keeps the base ability, and
     * the kicked variant is added alongside it as its own menu entry.
     */
    private boolean buildingMenu = false;

    /**
     * The `ChangeZone` resolution currently walking its one-at-a-time loop, and how many
     * cards it has taken. See `zoneChangeProgress` — v2.17's `chosen` field exists because
     * a five-card pile reaches this controller as five separate single-card asks.
     */
    private SpellAbility zoneChangeRun = null;
    private int zoneChangeChosen = 0;

    public PlayerControllerBridge(final Game game, final Player p, final LobbyPlayer lp,
            final BenchSession session, final BenchSession.Mode mode, final int seat,
            final CallCounter counters) {
        super(game, p, lp);
        this.session = session;
        this.mode = mode;
        this.seat = seat;
        this.counters = counters;
    }

    public CallCounter getCounters() {
        return counters;
    }

    public BenchSession.Mode getMode() {
        return mode;
    }

    // ------------------------------------------------------------------ plumbing

    private void count(final String method) {
        if (!isLiveGame()) {
            return; // search internals are not decisions the seat made
        }
        counters.count(method);
    }

    /*
     * Seat knowledge (RL observation v1, lane rl-r0-b5-1006): what this seat is shown (reveals) and what it looks at
     * while arranging cards (scry, surveil, "look at the top N, put them back in any order"). Reported to the
     * session's knowledge observer, which is null by default: then these are no-ops. Called before the bridged()
     * check, so a Forge-decided seat's looks are reported too (the other seat must forget the order it can no longer
     * know). Live game only.
     */
    private void observeReveal(final Iterable<Card> cards, final ZoneType zone, final Player owner) {
        final BenchSession.KnowledgeObserver o = session.getKnowledgeObserver();
        if (o == null || !isLiveGame() || cards == null) {
            return;
        }
        try {
            o.onReveal(getGame(), getPlayer(), Lists.newArrayList(cards), zone, owner);
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("knowledge observer failed on a reveal", e);
        }
    }

    private void observeRevealViews(final List<CardView> views, final ZoneType zone, final PlayerView owner) {
        final BenchSession.KnowledgeObserver o = session.getKnowledgeObserver();
        if (o == null || !isLiveGame() || views == null) {
            return;
        }
        final List<Card> cards = new ArrayList<>();
        for (CardView v : views) {
            final Card c = v == null ? null : getGame().findByView(v);
            if (c != null) {
                cards.add(c);
            }
        }
        Player own = null;
        for (Player p : getGame().getPlayers()) {
            if (owner != null && p.getView() == owner) {
                own = p;
            }
        }
        observeReveal(cards, zone, own);
    }

    private void observeLook(final Iterable<Card> cards, final ZoneType destination) {
        final BenchSession.KnowledgeObserver o = session.getKnowledgeObserver();
        if (o == null || !isLiveGame() || cards == null) {
            return;
        }
        try {
            o.onLook(getGame(), getPlayer(), Lists.newArrayList(cards), destination);
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("knowledge observer failed on a look", e);
        }
    }

    /**
     * False inside a copied game built by {@code GameCopier} for the simulation search.
     * Such a controller must behave as plain {@link PlayerControllerAi}: it is deciding
     * about a hypothetical position, so asking the host would both corrupt the host's
     * model of the real game and stall on an answer that means nothing.
     */
    private boolean isLiveGame() {
        final Game live = session.getLiveGame();
        return live == null || live == getGame();
    }

    private boolean bridged() {
        return mode == BenchSession.Mode.BRIDGE && isLiveGame() && !session.getChannel().isClosed();
    }

    /*
     * RL record mode (lane rl-r0-b1-1005): an observer that delegates every ask must leave the game exactly as Forge
     * alone would play it. Building the priority menu is not a pure read. ComputerUtilCost.canPayCost (Forge's AI
     * affordability check, run on every menu entry) draws from the game's random stream (the "try not to lose a
     * planeswalker" coin flip, ComputerUtilMana's reserve-mana roll), clears and fills Forge AI's card memory
     * (AiCardMemory: held mana sources, unpaid costs), and copies abilities, which takes ids. Measured on 24 TRAIN
     * games: a delegate-everything recorder matched RlSimBench policy=forge on 4 of 24, 22 of 24 with this isolation,
     * and 24 of 24 once the mana-ability channel is also skipped (see the menu build). So when the local answerer
     * says it only observes, the menu is built on a scratch random
     * stream and a scratch AI cache scope, and both seats' AI card memory and the IdScope counters are put back
     * afterwards. With no local answerer, or one that answers (an RL seat, whose chosen entry is played), this is
     * body.get() and nothing else.
     */
    /** True when the local answerer only observes (RL record mode); false on the default path. */
    private boolean observeOnly() {
        final BenchSession.LocalAnswerer local = session.getLocalAnswerer();
        return local != null && local.observeOnly();
    }

    private <T> T observing(final java.util.function.Supplier<T> body) {
        if (!observeOnly()) {
            return body.get();
        }
        return isolated(getGame(), body);
    }

    /**
     * The observe-only isolation above, for any caller that must read the live game without moving it (lane
     * game-review-1010: the review observer's menus and observations of a human seat): a scratch random stream, a
     * scratch AI cache scope, and both seats' AI card memory, express state and the IdScope counters put back after.
     */
    public static <T> T isolated(final Game game, final java.util.function.Supplier<T> body) {
        final java.util.Random live = forge.util.MyRandom.getThreadRandom();
        final int[] ids = IdSnap.take();
        final Object cache = forge.ai.AiCache.captureScope();
        final List<Object[]> memory = memorySnapshot(game);
        final List<Object[]> express = expressSnapshot(game);
        forge.util.MyRandom.setThreadRandom(new java.util.Random(0x0B5E47EL));
        forge.ai.AiCache.openScope();
        try {
            return body.get();
        } finally {
            forge.ai.AiCache.installScope(cache);
            forge.util.MyRandom.setThreadRandom(live);
            IdSnap.restore(ids);
            memoryRestore(memory);
            expressRestore(express);
        }
    }

    /** Every AI card-memory set of every AI-controlled player: {live set, copy of its contents}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static List<Object[]> memorySnapshot(final Game game) {
        final List<Object[]> out = new ArrayList<>();
        for (Player p : game.getPlayers()) {
            if (!p.getController().isAI()) {
                continue;
            }
            final List<forge.ai.AiCardMemory.MemoryType> types = new ArrayList<>();
            types.addAll(Arrays.asList(forge.ai.AiCardMemory.MemorySet.values()));
            types.addAll(Arrays.asList(forge.ai.AiCardMemory.MemorySetMana.values()));
            for (forge.ai.AiCardMemory.MemoryType t : types) {
                final Set live = forge.ai.AiCardMemory.getMemorySet(p, t);
                if (live != null) {
                    out.add(new Object[] {live, new ArrayList<Object>(live)});
                }
            }
        }
        return out;
    }

    /**
     * The colour choice every mana ability of every card in the game carries ({@code AbilityManaPart}'s express
     * choice). Forge's test payment (canPayCost) sets it on combo sources it considers and clears it only on the ones
     * it ends up using; the auto-tapper reads it later (lane rl-r0-b5-1006: 1 recorded game in 200 tapped a different
     * source).
     */
    static List<Object[]> expressSnapshot(final Game game) {
        final List<Object[]> out = new ArrayList<>();
        for (Card c : game.getCardsInGame()) {
            for (SpellAbility sa : c.getAllSpellAbilities()) {
                for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
                    final forge.game.spellability.AbilityManaPart mp = cur.getManaPart();
                    if (mp != null) {
                        out.add(new Object[] {mp, mp.getExpressChoice()});
                    }
                }
            }
        }
        return out;
    }

    static void expressRestore(final List<Object[]> snap) {
        for (Object[] e : snap) {
            ((forge.game.spellability.AbilityManaPart) e[0]).setExpressChoice((String) e[1]);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void memoryRestore(final List<Object[]> snap) {
        for (Object[] e : snap) {
            final Set live = (Set) e[0];
            live.clear();
            live.addAll((List) e[1]);
        }
    }

    /** IdScope counter values (record-mode observation; see {@link #observing}). */
    static final class IdSnap {
        private IdSnap() {
        }

        static int[] take() {
            final Object cap = forge.util.IdScope.capture();
            if (!(cap instanceof java.util.concurrent.atomic.AtomicInteger[])) {
                return null;
            }
            final java.util.concurrent.atomic.AtomicInteger[] a = (java.util.concurrent.atomic.AtomicInteger[]) cap;
            final int[] v = new int[a.length];
            for (int i = 0; i < a.length; i++) {
                v[i] = a[i].get();
            }
            return v;
        }

        static void restore(final int[] v) {
            final Object cap = forge.util.IdScope.capture();
            if (v == null || !(cap instanceof java.util.concurrent.atomic.AtomicInteger[])) {
                return;
            }
            final java.util.concurrent.atomic.AtomicInteger[] a = (java.util.concurrent.atomic.AtomicInteger[]) cap;
            for (int i = 0; i < a.length && i < v.length; i++) {
                a[i].set(v[i]);
            }
        }
    }

    /** Envelope shared by every ask: game id, seat and the seat-visible state. */
    private JsonObject envelope(final boolean withState) {
        final JsonObject o = new JsonObject();
        o.addProperty("game", session.getGameId());
        o.addProperty("seat", seat);
        if (withState && session.isEncodeState()) {
            try {
                o.add("state", StateEncoder.encode(getGame(), getPlayer()));
            } catch (RuntimeException e) {
                JsonRpcChannel.logErr("state encoding failed", e);
            }
        }
        return o;
    }

    /**
     * Send an ask and return the answer, or null when the host asked to delegate
     * (which is counted, not an error).
     *
     * <p>On the delegate branch this also arms {@link #pendingEcho}. See the ECHO block
     * below for why the arming and the reading are two steps.
     */
    private JsonObject ask(final String method, final String kind, final JsonObject body) {
        return ask(method, kind, body, null);
    }

    /** As above; {@code menuObjects} reach an in-process answerer only (see BenchSession.LocalAnswerer). */
    private JsonObject ask(final String method, final String kind, final JsonObject body, final Object menuObjects) {
        final BenchSession.LocalAnswerer local = session.getLocalAnswerer();
        final JsonObject ans = local != null ? local.answer(getGame(), getPlayer(), method, kind, body, menuObjects)
                : session.getChannel().ask(kind, body);
        if (ans == null || (ans.has("delegate") && ans.get("delegate").getAsBoolean())) {
            counters.delegateRequested(method);
            final Integer id = optInt(ans, "id");
            pendingEcho = new Echo(id == null ? -1 : id, kind, method);
            return null;
        }
        pendingEcho = null;
        return ans;
    }

    /*
     * =======================================================================
     * THE ECHO — v2.20, AND IT IS THE FIRST THING ON THIS WIRE THAT REPORTS
     * WHAT HAPPENED RATHER THAN WHAT IS TRUE.
     * =======================================================================
     * `{"delegate": true}` was WRITE-ONLY. The host declined, `super....` ran,
     * Forge's AI decided, the game moved on and nothing said what it had picked.
     * `ResultMessage.delegationCounts` is per-METHOD counts — `calls`,
     * `delegatedRequested`, `delegatedRefused` — and a count is not a choice. So
     * the host's `MTGX_FORGEDELEG` instrument could price a delegated kind by
     * WIN RATE and could record its own counterfactual answer (the shadow arm),
     * and the one column it could never fill was the one the behaviour-clone
     * lane is built on: `forgeAction`, the imitation TARGET, empty on 100% of
     * rows because the target was never on the wire.
     *
     * WHAT IS SENT, AND THE ONE RULE THAT MAKES IT USEFUL. One fire-and-forget
     * `delegated` line per declined ask, carrying the ask's own `id` and Forge's
     * decision IN THE SHAPE THAT ASK'S OWN ANSWER WOULD HAVE TAKEN. Not a new
     * vocabulary — the host already encodes `{"choice": n}`, `{"pairs": [...]}`,
     * `{"choices": [fid, ...]}` for every one of the eighteen kinds and already
     * owns a decoder and an action-space grammar for them. An echo in a second
     * shape would need a second grammar, and a second grammar is one that can
     * drift from the first without either side going red.
     *
     * ONLY ON A DELEGATION, NEVER ON A REFUSAL. Every handler below also calls
     * `super....` after `refuse(...)` — a fallback taken because OUR answer was
     * unusable. Forge decides there too, and echoing it would be easy and wrong:
     * a refusal is a defect in the host's encoding, so its outcome is our bug's
     * consequence rather than Forge's judgement, and folding the two together
     * would put our own encoding failures into a teacher corpus labelled
     * "what Forge would do". `pendingEcho` is armed only on the `{"delegate":
     * true}` branch, which is exactly `MTGX_FORGEDELEG`'s population.
     *
     * WHY ARMING AND READING ARE TWO STEPS. `super....` RE-ENTERS this
     * controller: `chooseSpellAbilityToPlay` reaches `chooseOptionalCosts`,
     * targeting reaches `chooseTargetsFor`, and each of those runs its own ask
     * and may arm its own echo. A single "last delegated id" field read AFTER
     * the super call would report the innermost ask's id for the outermost
     * decision. So every handler takes its token with `takeEcho()` BEFORE it
     * calls super — the token is consumed, the nested asks arm and consume their
     * own, and the ids can never cross.
     */

    /** One armed echo: the ask the host just declined. */
    private static final class Echo {
        final int id;
        final String kind;
        final String method;
        Echo(final int id, final String kind, final String method) {
            this.id = id;
            this.kind = kind;
            this.method = method;
        }
    }

    /** Armed by {@link #ask} on the delegate branch; consumed by {@link #takeEcho}. */
    private Echo pendingEcho = null;

    /**
     * Take the armed echo token. MUST be called before the {@code super....} that
     * makes the decision, because that call re-enters this controller and arms
     * echoes of its own.
     */
    private Echo takeEcho() {
        final Echo e = pendingEcho;
        pendingEcho = null;
        return e;
    }

    /**
     * Publish one delegated decision. Never throws: an echo that fails is a lost
     * training row, and a lost training row must not be a lost game.
     */
    private void echo(final Echo e, final JsonObject answer) {
        echo(e, answer, null);
    }

    /**
     * As above, with the raw decision for an in-process answerer (RL record mode, lane rl-r0-b1-1005). The local
     * hook runs before the closed-channel return; with no local answerer this is the method above to the byte.
     */
    private void echo(final Echo e, final JsonObject answer, final Object decision) {
        if (e == null || answer == null) {
            return;
        }
        final BenchSession.LocalAnswerer local = session.getLocalAnswerer();
        if (local != null) {
            try {
                local.onEcho(getGame(), getPlayer(), e.method, e.kind, answer, decision);
            } catch (RuntimeException ex) {
                counters.instrument("echo.local.failed." + e.kind);
                JsonRpcChannel.logErr("local echo failed for " + e.method + " (" + e.kind + ")", ex);
            }
        }
        if (session.getChannel().isClosed()) {
            return;
        }
        try {
            final JsonObject m = new JsonObject();
            m.addProperty("type", "delegated");
            m.addProperty("game", session.getGameId());
            m.addProperty("seat", seat);
            m.addProperty("id", e.id);
            m.addProperty("kind", e.kind);
            m.addProperty("method", e.method);
            m.addProperty("protocolMinor", JsonRpcChannel.PROTOCOL_MINOR);
            m.add("answer", answer);
            session.getChannel().send(m);
            counters.instrument("echo.sent." + e.kind);
            // v2.21 -- the frame-batch stop. See BenchSession.noteEcho for why the
            // game ends here rather than after the ask returns: the row this JVM was
            // booted for is now on the wire, and every further millisecond is Forge
            // playing on from our board, which is a distribution we already have.
            if (session.noteEcho(e.kind)) {
                counters.instrument("echo.stop." + e.kind);
            }
        } catch (RuntimeException ex) {
            counters.instrument("echo.failed." + e.kind);
            JsonRpcChannel.logErr("delegated echo failed for ask " + e.id + " (" + e.kind + ")", ex);
        }
    }

    /** Fids in order, the primitive under every card-shaped echo. */
    private static JsonArray fidArray(final Iterable<Card> cards) {
        final JsonArray ids = new JsonArray();
        if (cards != null) {
            for (Card c : cards) {
                if (c != null) {
                    ids.add(c.getId());
                }
            }
        }
        return ids;
    }

    /** {@code {"choices": [fid, ...]}} — the cardsChoice / zoneChange / orderZone shape. */
    private static JsonObject echoCards(final Iterable<Card> cards) {
        final JsonObject o = new JsonObject();
        o.add("choices", fidArray(cards));
        return o;
    }

    /** {@code {"choices": [i, ...]}} — menu INDICES, for the index-answered kinds. */
    private static <T> JsonObject echoIndices(final List<T> menu, final Iterable<T> picked) {
        final JsonObject o = new JsonObject();
        final JsonArray idx = new JsonArray();
        if (picked != null && menu != null) {
            for (T t : picked) {
                final int i = indexOfIdentity(menu, t);
                if (i >= 0) {
                    idx.add(i);
                }
            }
        }
        o.add("choices", idx);
        return o;
    }

    /** Object identity, not {@code equals}: two menu entries may compare equal and differ. */
    private static <T> int indexOfIdentity(final List<T> menu, final T t) {
        for (int i = 0; i < menu.size(); i++) {
            if (menu.get(i) == t) {
                return i;
            }
        }
        return -1;
    }

    private static JsonObject echoBool(final String key, final boolean v) {
        final JsonObject o = new JsonObject();
        o.addProperty(key, v);
        return o;
    }

    private static JsonObject echoInt(final String key, final int v) {
        final JsonObject o = new JsonObject();
        o.addProperty(key, v);
        return o;
    }

    /**
     * A choice Forge made that this ask's menu cannot name. A null index and a stated
     * {@code match}, never an out-of-range integer: a host decoding {@code -1} as an
     * ordinal would train on a label pointing at nothing.
     */
    private static JsonObject unmatchedChoice() {
        final JsonObject o = new JsonObject();
        o.add("choice", com.google.gson.JsonNull.INSTANCE);
        o.addProperty("match", "none");
        return o;
    }

    /**
     * The {@code priority} echo, and the only one that can fail to name an index.
     *
     * <p>{@code AiController.chooseSpellAbilityToPlay} enumerates its own abilities
     * through {@code ComputerUtilAbility.getAvailableSpellAbilities}, independently of
     * {@link #legalSpellAbilities}, and {@code getOriginalAndAltCostAbilities} hands back
     * COPIES for alt-cost variants. So the object Forge returns is often not an object in
     * the menu we published one message earlier. Identity is tried first because it is
     * exact; the structural key (host card fid + API + description) is tried second
     * because it is the same triple the host's own decoder keys on; and where neither
     * lands the echo says {@code match: "none"} with a null {@code choice} rather than
     * guessing an index. The full {@code sa} encoding rides along in every case, so an
     * unmatched echo is still a usable label — it is a decision this menu could not name,
     * which is a fact about the menu.
     */
    private static JsonObject echoPriority(final List<SpellAbility> menu, final List<SpellAbility> out) {
        final JsonObject o = new JsonObject();
        final SpellAbility sa = (out == null || out.isEmpty()) ? null : out.get(0);
        if (sa == null) {
            // Forge passed. Choice 0 is always pass; the menu index space agrees.
            o.addProperty("choice", 0);
            o.addProperty("match", "identity");
            return o;
        }
        int at = indexOfIdentity(menu, sa);
        String match = at >= 0 ? "identity" : null;
        if (at < 0) {
            final String key = saKey(sa);
            for (int i = 0; i < menu.size(); i++) {
                if (key.equals(saKey(menu.get(i)))) {
                    at = i;
                    match = "structural";
                    break;
                }
            }
        }
        if (at >= 0) {
            o.addProperty("choice", at + 1); // menu index 0 is pass
            o.addProperty("match", match);
        } else {
            o.add("choice", com.google.gson.JsonNull.INSTANCE);
            o.addProperty("match", "none");
        }
        o.add("sa", StateEncoder.encodeSpellAbility(sa));
        try {
            final int x = sa.getXManaCostPaid();
            if (x > 0) {
                o.addProperty("x", x);
            }
        } catch (RuntimeException e) {
            // No announcement to report. Absent means here what it means on an
            // answer we send ourselves: Forge's default of 0 stands.
        }
        return o;
    }

    /** Host card + API + description: what a structural match means, in one place. */
    private static String saKey(final SpellAbility sa) {
        if (sa == null) {
            return "null";
        }
        String host = "?";
        String api = "?";
        String desc = "?";
        try {
            host = sa.getHostCard() == null ? "?" : String.valueOf(sa.getHostCard().getId());
        } catch (RuntimeException e) { /* keep the sentinel */ }
        try {
            api = sa.getApi() == null ? "?" : sa.getApi().toString();
        } catch (RuntimeException e) { /* keep the sentinel */ }
        try {
            desc = String.valueOf(sa.getDescription());
        } catch (RuntimeException e) { /* keep the sentinel */ }
        return host + "|" + api + "|" + desc;
    }

    /** {@code {"pairs": [[attackerFid, defenderRef], ...]}}, read off the declared combat. */
    private static JsonObject echoAttackers(final Combat combat) {
        final JsonObject o = new JsonObject();
        final JsonArray pairs = new JsonArray();
        try {
            for (Card a : combat.getAttackers()) {
                final GameEntity d = combat.getDefenderByAttacker(a);
                if (d == null) {
                    continue;
                }
                final JsonArray pr = new JsonArray();
                pr.add(a.getId());
                pr.add(StateEncoder.entityRef(d));
                pairs.add(pr);
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("attacker echo enumeration failed", e);
        }
        o.add("pairs", pairs);
        return o;
    }

    /** {@code {"pairs": [[blockerFid, attackerFid], ...]}}, read off the declared combat. */
    private static JsonObject echoBlockers(final Combat combat) {
        final JsonObject o = new JsonObject();
        final JsonArray pairs = new JsonArray();
        try {
            for (Card a : combat.getAttackers()) {
                for (Card b : combat.getBlockers(a)) {
                    final JsonArray pr = new JsonArray();
                    pr.add(b.getId());
                    pr.add(a.getId());
                    pairs.add(pr);
                }
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("blocker echo enumeration failed", e);
        }
        o.add("pairs", pairs);
        return o;
    }

    /**
     * The {@code targets} echo: typed refs in the SAME flat space an answer uses, plus
     * the divided allocation when there is one.
     *
     * <p>Read off {@code sa.getTargets()} after the delegated call, because that is where
     * Forge's own per-API targeting puts them and there is no return value that carries
     * them. {@code ok} is what {@code super.chooseTargetsFor} returned: a {@code false}
     * with an empty {@code choices} is Forge declining to target at all, which is a
     * decision and not a missing row.
     */
    private static JsonObject echoTargets(final SpellAbility sa, final boolean ok) {
        final JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        final JsonArray choices = new JsonArray();
        final JsonObject divide = new JsonObject();
        boolean divided = false;
        try {
            divided = sa.isDividedAsYouChoose();
            for (GameObject go : sa.getTargets()) {
                final JsonObject ref = new JsonObject();
                ref.addProperty("kind", kindOf(go));
                ref.addProperty("id", idOf(go));
                choices.add(ref);
                if (divided) {
                    final Integer n = sa.getDividedValue(go);
                    if (n != null) {
                        divide.addProperty(kindOf(go) + ":" + idOf(go), n);
                    }
                }
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("target echo enumeration failed for " + sa, e);
        }
        o.add("choices", choices);
        if (divided) {
            o.add("divide", divide);
        }
        return o;
    }

    private void refuse(final String method, final String why) {
        counters.delegateRefused(method, why);
        final BenchSession.LocalAnswerer local = session.getLocalAnswerer();
        if (local != null) {
            local.onRefused(getGame(), getPlayer(), method, why);
        }
    }

    private static Integer optInt(final JsonObject o, final String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Boolean optBool(final JsonObject o, final String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        try {
            return o.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<Integer> optIntList(final JsonObject o, final String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) {
            return null;
        }
        final List<Integer> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(key)) {
            try {
                out.add(e.getAsInt());
            } catch (RuntimeException ex) {
                return null;
            }
        }
        return out;
    }

    /** Answer form for combat: an array of [id, id] pairs. */
    private static List<int[]> optPairs(final JsonObject o, final String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) {
            return null;
        }
        final List<int[]> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(key)) {
            if (!e.isJsonArray()) {
                return null;
            }
            final JsonArray a = e.getAsJsonArray();
            if (a.size() != 2) {
                return null;
            }
            try {
                out.add(new int[] { a.get(0).getAsInt(), a.get(1).getAsInt() });
            } catch (RuntimeException ex) {
                return null;
            }
        }
        return out;
    }

    private static Card findCard(final Iterable<Card> pool, final int fid) {
        for (Card c : pool) {
            if (c.getId() == fid) {
                return c;
            }
        }
        return null;
    }

    private static GameEntity findEntity(final Iterable<? extends GameEntity> pool, final int id) {
        for (GameEntity ge : pool) {
            if (ge.getId() == id) {
                return ge;
            }
        }
        return null;
    }

    // --------------------------------------------------------- strategic overrides

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        count("chooseSpellAbilityToPlay");
        if (!bridged()) {
            return super.chooseSpellAbilityToPlay();
        }
        final int[] diag = new int[DIAG_LEN];
        final List<SpellAbility> menu = new ArrayList<>();
        final JsonObject body = envelope(true);
        observing(() -> {
            menu.addAll(legalSpellAbilities(diag));
            body.add("menuDiag", menuDiagJson(diag, menu.size()));
            final JsonArray items = new JsonArray();
            items.add(StateEncoder.encodeSpellAbility(null)); // choice 0 is always pass
            for (SpellAbility sa : menu) {
                items.add(StateEncoder.encodeSpellAbility(sa));
            }
            body.add("menu", items);
            // An observe-only recorder never reads this host-side field, and building it is not a pure read:
            // encoding each mana ability (descriptions, stack descriptions) leaves state on combo mana abilities
            // (Talismans) that Forge's auto-tapper reads later, so a recorded game tapped a different source than the
            // Forge-only game on 2 of 24 seeds (lane rl-r0-b5-1006). Skipped for observers only.
            body.add("manaAbilities", observeOnly() ? new JsonArray() : manaAbilityChannel());
            return null;
        });
        recordMenuCensus(diag, menu.size());
        final JsonObject ans = ask("chooseSpellAbilityToPlay", "priority", body, menu);
        if (ans == null) {
            final Echo e = takeEcho();
            final List<SpellAbility> out = super.chooseSpellAbilityToPlay();
            echo(e, echoPriority(menu, out), out);
            return out;
        }
        final Integer choice = optInt(ans, "choice");
        if (choice == null || choice < 0 || choice > menu.size()) {
            refuse("chooseSpellAbilityToPlay", "choice out of range: " + choice);
            return super.chooseSpellAbilityToPlay();
        }
        if (choice == 0) {
            return null; // pass
        }
        final SpellAbility chosen = menu.get(choice - 1);
        if (!chosen.canPlay()) {
            refuse("chooseSpellAbilityToPlay", "chosen ability is no longer playable: " + chosen);
            return super.chooseSpellAbilityToPlay();
        }
        // X must be announced BEFORE targeting and before the affordability re-check:
        // an X spell's legal target count can be derived from X, and canPayCost has to
        // price the announcement.
        if (!announceX(chosen, ans)) {
            return super.chooseSpellAbilityToPlay();
        }
        if (!ensureTargets(chosen)) {
            return super.chooseSpellAbilityToPlay();
        }
        return Lists.newArrayList(chosen);
    }

    /**
     * Apply the host's announced X to the chosen ability (protocol v2.3).
     *
     * <p>Without this every {X} spell the bridged seat casts is announced at X=0, because
     * Forge sets X inside {@code canPlayAI} — a path a host-chosen ability never goes down
     * — and {@code XManaCostPaid} defaults to zero. Walking Ballista arrived as a 0/0 and
     * died to state-based actions on the adjacent event; Forth Eorlingas! made no tokens.
     * The announcement is read back by
     * {@code ComputerUtilMana.calculateManaCost} via {@code calculateAmount(host, "X", sa)},
     * so setting it here <em>is</em> the announcement.
     *
     * @return false when the answer should be refused and delegated
     */
    private boolean announceX(final SpellAbility chosen, final JsonObject ans) {
        final Integer x = optInt(ans, "x");
        if (x == null) {
            return true; // no announcement offered; Forge's default of 0 stands
        }
        boolean hasX;
        try {
            hasX = chosen.costHasX()
                    || (chosen.getPayCosts() != null && chosen.getPayCosts().hasXInAnyCostPart());
        } catch (RuntimeException e) {
            hasX = false;
        }
        if (!hasX) {
            // A decode mismatch: the host announced X for an ability that has none. Counted
            // and delegated rather than ignored, so a TS-side menu-indexing bug cannot hide
            // behind a field the JVM quietly drops.
            refuse("chooseSpellAbilityToPlay", "answer announced x=" + x
                    + " for an ability with no {X}: " + chosen);
            return false;
        }
        final int ceiling = StateEncoder.maxAnnounceableX(chosen);
        int clamped = Math.max(0, Math.min(x, ceiling));
        // CR 601.2b: an X with a stated minimum may not be announced below it.
        try {
            if (chosen.getPayCosts() != null && chosen.getPayCosts().getCostMana() != null) {
                clamped = Math.max(clamped, Math.min(ceiling, chosen.getPayCosts().getCostMana().getXMin()));
            }
        } catch (RuntimeException e) {
            // no stated minimum available; the [0, ceiling] clamp stands
        }
        chosen.setXManaCostPaid(clamped);
        counters.instrument("x.announced");
        if (clamped > 0) {
            counters.instrument("x.announcedNonZero");
        }
        if (clamped != x) {
            counters.instrument("x.clampedByJvm");
            JsonRpcChannel.log("clamped announced X " + x + " -> " + clamped
                    + " (ceiling " + ceiling + ", " + StateEncoder.xSymbolCount(chosen)
                    + " {X} symbols) for " + chosen);
        }
        return true;
    }

    /**
     * Assign targets to a host-chosen ability before handing it back to Forge.
     *
     * <p>Load-bearing. Forge's AI assigns targets inside {@code canPlayAI} while it is
     * deciding <em>whether</em> to play the ability;
     * {@code ComputerUtil.handlePlayingSpellAbility} then puts the ability on the stack
     * with whatever targets are already on it and never asks again (measured: zero
     * {@code chooseTargetsFor} calls across three AI-vs-AI cube games). The abilities in
     * our priority menu have not been through {@code canPlayAI}, so without this step a
     * host-chosen targeted spell would reach the stack with no targets at all.
     *
     * <p>Routing through {@link #chooseTargetsFor} means the host gets a {@code targets}
     * ask, and a host that delegates falls back to Forge's own per-API targeting logic.
     */
    private boolean ensureTargets(final SpellAbility root) {
        SpellAbility cur = root;
        while (cur != null) {
            if (cur.usesTargeting()) {
                cur.clearTargets();
                cur.setTargetingPlayer(getPlayer());
                if (!chooseTargetsFor(cur) || !cur.isTargetNumberValid()) {
                    refuse("chooseSpellAbilityToPlay", "could not legally target " + cur);
                    return false;
                }
            }
            cur = cur.getSubAbility();
        }
        if (!ComputerUtilCost.canPayCost(root, getPlayer(), root.isTrigger())) {
            refuse("chooseSpellAbilityToPlay", "cost became unpayable after targeting: " + root);
            return false;
        }
        return true;
    }

    private static JsonObject menuDiagJson(final int[] diag, final int offered) {
        final JsonObject o = new JsonObject();
        o.addProperty("candidates", diag[DIAG_CANDIDATES]);
        o.addProperty("offered", offered);
        o.addProperty("rejectedTiming", diag[DIAG_TIMING]);
        o.addProperty("rejectedUnaffordable", diag[DIAG_UNAFFORDABLE]);
        o.addProperty("rejectedNoTarget", diag[DIAG_NO_TARGET]);
        return o;
    }

    /**
     * Pool the census, split by whose turn it is. The their-turn split is the one that
     * settles whether a quiet opponent-turn window is the bridge pruning the menu or the
     * seat having nothing left to do with.
     */
    private void recordMenuCensus(final int[] diag, final int offered) {
        final String scope = getGame().getPhaseHandler().isPlayerTurn(getPlayer())
                ? "menu.ourTurn." : "menu.theirTurn.";
        counters.instrument(scope + "frames");
        if (offered == 0) {
            counters.instrument(scope + "passOnlyFrames");
        }
        counters.instrument(scope + "candidates", diag[DIAG_CANDIDATES]);
        counters.instrument(scope + "rejectedTiming", diag[DIAG_TIMING]);
        counters.instrument(scope + "rejectedUnaffordable", diag[DIAG_UNAFFORDABLE]);
        counters.instrument(scope + "rejectedNoTarget", diag[DIAG_NO_TARGET]);
        counters.instrument(scope + "offered", offered);
    }

    /**
     * The legal action menu offered at priority. Mana abilities are excluded: Forge plays
     * those during cost payment, never at priority, and offering them invites a
     * non-terminating priority loop.
     */
    private List<SpellAbility> legalSpellAbilities() {
        return legalSpellAbilities(null);
    }

    /**
     * v2.18 — THE MANA ABILITIES, ON A CHANNEL OF THEIR OWN.
     *
     * <p>The exclusion documented one method above is right and stays: a mana ability is
     * part of paying for something, not a thing to do at priority, and offering them
     * invites a non-terminating loop. What was never true is the host's inference from it.
     * The host builds {@code CardView.abilities} by walking THIS menu
     * ({@code answer.ts:publishAbilities}), so on the bench a permanent's mana abilities
     * are not in its ability list at all — measured on the host side, {@code isManaAbility}
     * is on the wire <b>57,053 times and false every time</b>, and <b>0 of 118,481</b> menu
     * options is a mana ability. A Grim Monolith published exactly one ability,
     * {@code "{4}: Untap this artifact."}, so the predicate that asks <i>what does one
     * {@code T} of this permanent add</i> answered zero on <b>every frame of a 384-game
     * corpus</b>, on a board where the Monolith and its cost reducer stood together 610
     * times.
     *
     * <p>So they are published beside the menu rather than inside it. Same encoding
     * ({@code encodeSpellAbility}: {@code fid} of the host card, {@code isManaAbility},
     * {@code payCosts}, {@code description}), and the {@code menu} array is byte-identical
     * to v2.17, so every index a host answers with means exactly what it meant before and
     * a host that does not read the key cannot behave differently.
     *
     * <p>Scope is the bridged seat's own battlefield: the reader this exists for
     * ({@code activations.ts:selfRestoringSources}) walks our permanents, and a channel
     * that enumerated the opponent's would publish hidden information for nobody's
     * benefit. {@code setActivatingPlayer} is NOT called — this is a read of the card, not
     * a preparation of an ability — and an enumeration that throws is swallowed per card,
     * so the key is shorter and never wrong.
     */
    private JsonArray manaAbilityChannel() {
        final JsonArray out = new JsonArray();
        final Player p = getPlayer();
        if (p == null) {
            return out;
        }
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c == null) {
                continue;
            }
            try {
                for (SpellAbility sa : c.getManaAbilities()) {
                    if (sa == null) {
                        continue;
                    }
                    out.add(StateEncoder.encodeSpellAbility(sa));
                }
            } catch (RuntimeException e) {
                JsonRpcChannel.logErr("mana ability channel failed for " + c, e);
            }
        }
        return out;
    }

    /**
     * @param diag optional per-stage rejection census, filled in as the menu is built
     */
    private List<SpellAbility> legalSpellAbilities(final int[] diag) {
        final List<SpellAbility> out = new ArrayList<>();
        final Player p = getPlayer();
        final Game game = getGame();
        try {
            final CardCollection lands = ComputerUtilAbility.getAvailableLandsToPlay(game, p);
            if (lands != null) {
                for (Card land : lands) {
                    for (SpellAbility sa : land.getAllPossibleAbilities(p, true)) {
                        if (sa.isLandAbility() && sa.canPlay()) {
                            out.add(sa);
                        }
                    }
                }
            }
            final CardCollection cards = ComputerUtilAbility.getAvailableCards(game, p);
            final List<SpellAbility> all;
            buildingMenu = true;
            try {
                all = ComputerUtilAbility.getOriginalAndAltCostAbilities(
                        ComputerUtilAbility.getSpellAbilities(cards, p), p);
            } finally {
                buildingMenu = false;
            }
            // Offer the optional-cost variants as their own entries, with their true total
            // cost, rather than letting Forge pick one and hand us a ballot that reads
            // "{0}". Everflowing Chalice was voted on as a free cast and then multikicked
            // for ten mana sources.
            final List<SpellAbility> withVariants = new ArrayList<>(all);
            for (SpellAbility sa : all) {
                try {
                    final List<OptionalCostValue> opts = GameActionUtil.getOptionalCostValues(sa);
                    if (opts == null || opts.isEmpty()) {
                        continue;
                    }
                    final SpellAbility kicked = GameActionUtil.addOptionalCosts(sa, opts);
                    if (kicked != null && kicked != sa) {
                        kicked.setActivatingPlayer(p);
                        withVariants.add(kicked);
                    }
                } catch (RuntimeException e) {
                    JsonRpcChannel.logErr("optional-cost variant construction failed for " + sa, e);
                }
            }
            for (SpellAbility sa : withVariants) {
                if (sa.isManaAbility() || sa.isLandAbility()) {
                    continue;
                }
                sa.setActivatingPlayer(p);
                if (diag != null) {
                    diag[DIAG_CANDIDATES]++;
                }
                if (!sa.canPlay()) {
                    bump(diag, DIAG_TIMING);
                    continue;
                }
                if (!ComputerUtilCost.canPayCost(sa, p, sa.isTrigger())) {
                    bump(diag, DIAG_UNAFFORDABLE);
                    continue;
                }
                if (!hasEnoughTargets(sa)) {
                    bump(diag, DIAG_NO_TARGET);
                    continue;
                }
                out.add(sa);
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("legalSpellAbilities failed; offering pass only", e);
        }
        return out;
    }

    // ---- menu census (protocol v2.5) --------------------------------------------
    // Why a priority menu is short is a question the harness has been answering by
    // inference. These four counts answer it at the frame, so an empty their-end-step
    // window is attributable rather than assumed.
    private static final int DIAG_CANDIDATES = 0;
    private static final int DIAG_TIMING = 1;      // canPlay() false: wrong timing/zone/restriction
    private static final int DIAG_UNAFFORDABLE = 2; // legal, but the mana or cost is not there
    private static final int DIAG_NO_TARGET = 3;   // legal and affordable, but no legal target exists
    private static final int DIAG_LEN = 4;

    private static void bump(final int[] diag, final int slot) {
        if (diag != null) {
            diag[slot]++;
        }
    }


    // ---- typed entity references (protocol v2.8) --------------------------------
    // Players and cards share one flat id space on the wire: a Player's id is its SEAT
    // INDEX (0, 1) and a Card's id is its fid, which starts at 1. So id 1 is BOTH seat 1
    // and the first card ever created, and a bare int cannot say which. Reproduced in the
    // field: the host named a creature, the bare id resolved to the player first, and
    // Chain Lightning hit its own controller's face.
    //
    // The answer now echoes the `kind` the menu already published. Bare ints are still
    // accepted for one minor version and resolve EXACTLY as they always have -- first
    // match in menu order, which is players before cards. That legacy path is ambiguous by
    // construction and cannot be made correct; it is preserved rather than "fixed" so the
    // deprecation window does not silently move any existing host's decisions. Every use
    // is counted under `legacy.untypedRef`.
    private static final class EntityRef {
        final String kind;   // "player" | "card" | "spell", or null for a legacy bare int
        final int id;
        EntityRef(final String kind, final int id) {
            this.kind = kind;
            this.id = id;
        }
    }

    /** Parse one answer element as a typed ref, or null if malformed. */
    private EntityRef parseRef(final JsonElement el, final String method) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.isJsonObject()) {
            final JsonObject o = el.getAsJsonObject();
            if (!o.has("id")) {
                return null;
            }
            final int id;
            try {
                id = o.get("id").getAsInt();
            } catch (RuntimeException e) {
                return null;
            }
            String kind = o.has("kind") && !o.get("kind").isJsonNull()
                    ? o.get("kind").getAsString() : null;
            if (kind != null) {
                kind = kind.trim().toLowerCase();
                if (!"player".equals(kind) && !"card".equals(kind) && !"spell".equals(kind)) {
                    return null;
                }
            }
            return new EntityRef(kind, id);
        }
        try {
            counters.instrument("legacy.untypedRef");
            return new EntityRef(null, el.getAsInt());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when this pool member is what the ref says it is. */
    private static boolean refMatches(final EntityRef ref, final GameEntity ge) {
        if (ge.getId() != ref.id) {
            return false;
        }
        if (ref.kind == null) {
            return true; // legacy: first match in menu order wins, as before
        }
        if ("player".equals(ref.kind)) {
            return ge instanceof Player;
        }
        if ("card".equals(ref.kind)) {
            return ge instanceof Card;
        }
        return false; // "spell" never lives in the entity pool
    }

    private static GameEntity findTyped(final Iterable<? extends GameEntity> pool, final EntityRef ref) {
        for (GameEntity ge : pool) {
            if (refMatches(ref, ge)) {
                return ge;
            }
        }
        return null;
    }

    /** A typed ref rendered for a log or refusal message. */
    private static String refText(final EntityRef ref) {
        return (ref.kind == null ? "untyped" : ref.kind) + ":" + ref.id;
    }

    /**
     * How many times an optional extra cost may be paid (protocol v2.4).
     *
     * <p>{@code addExtraKeywordCost} passes {@code Integer.MAX_VALUE} for Multikicker, so
     * the raw {@code max} is not a range a host can price against. This is the affordable
     * ceiling: mana left after the base cost, divided by the repeat's own mana cost.
     */
    private int affordableRepeats(final SpellAbility sa, final Cost cost, final int max) {
        return affordableRepeats(getPlayer(), sa, cost, max);
    }

    static int affordableRepeats(final Player payer, final SpellAbility sa, final Cost cost, final int max) {
        int ceiling;
        try {
            final int repeatMana = cost == null || cost.hasNoManaCost()
                    ? 0 : cost.getTotalMana().getCMC();
            if (repeatMana <= 0) {
                // A non-mana repeat (Casualty's sacrifice, Conspire's tap) -- Forge asks
                // these as a yes/no with max 1 and we have no cheap affordability model.
                ceiling = Math.min(max, 1);
            } else {
                final int available = ComputerUtilMana.getAvailableManaEstimate(payer);
                final int base = sa.getPayCosts() == null || sa.getPayCosts().hasNoManaCost()
                        ? 0 : sa.getPayCosts().getTotalMana().getCMC();
                ceiling = Math.min(max, Math.max(0, (available - base) / repeatMana));
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("keyword-cost ceiling estimate failed", e);
            ceiling = Math.min(max, 1);
        }
        return Math.max(0, ceiling);
    }

    /** Upper bound on the repeats {@link #payableRepeats} tries (an unbounded mana source must not spin forever). */
    static final int REPEAT_CAP = 99;

    /**
     * Keyword-cost repeats for an RL seat or recorder (lane rl-r0-b4b-1006): Forge's own count, as
     * {@code PlayerControllerAi.chooseNumberForKeywordCost} makes it, the most repeats whose total cost
     * {@code canPayCost} accepts, and never fewer than the amount Forge's AI preset on the ability (Multikicker's
     * {@code PermanentAi} count). The mana estimate in {@link #affordableRepeats} is not Forge's test: it offered fewer
     * repeats than Forge's AI then chose (Squad, Multikicker), so a recorder could not name Forge's answer and an RL
     * seat could not choose every amount Forge would pay. canPayCost draws from the game's random stream and AI card
     * memory, so a recorder runs it isolated ({@link #observing}).
     */
    private int payableRepeats(final SpellAbility sa, final Cost cost, final KeywordInterface keyword, final int max) {
        return observing(() -> payableRepeats(getPlayer(), sa, cost, keyword, max));
    }

    static int payableRepeats(final Player payer, final SpellAbility sa, final Cost cost,
            final KeywordInterface keyword, final int max) {
        int n = 0;
        try {
            final Cost soFar = sa.getPayCosts().copy();
            for (int i = 0; i < Math.min(max, REPEAT_CAP); i++) {
                soFar.add(cost);
                if (!ComputerUtilCost.canPayCost(sa.copyWithDefinedCost(soFar), payer, sa.isTrigger())) {
                    break;
                }
                n++;
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("keyword-cost payable count failed", e);
            n = affordableRepeats(payer, sa, cost, max);
        }
        if (keyword != null && sa.hasOptionalKeywordAmount(keyword)) {
            n = Math.max(n, Math.min(sa.getOptionalKeywordAmount(keyword), Math.min(max, REPEAT_CAP)));
        }
        return Math.max(0, n);
    }

    /**
     * Every optional extra cost the host will be asked about once it commits to this cast
     * (protocol v2.4). Read-only, so a `{0}` ballot is not advertised as free while a
     * Multikicker is pending behind it.
     */
    @Override
    public int chooseNumberForKeywordCost(final SpellAbility sa, final Cost cost,
            final KeywordInterface keyword, final String prompt, final int max) {
        count("chooseNumberForKeywordCost");
        if (!bridged()) {
            return super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max);
        }
        final int ceiling = localAnswers() ? payableRepeats(sa, cost, keyword, max) : affordableRepeats(sa, cost, max);
        final JsonObject body = envelope(true);
        body.add("ability", StateEncoder.encodeSpellAbility(sa));
        body.addProperty("prompt", String.valueOf(prompt));
        body.addProperty("keyword", keyword == null ? "" : String.valueOf(keyword.getOriginal()));
        body.addProperty("keywordTitle", keyword == null ? "" : String.valueOf(keyword.getTitle()));
        final JsonObject c = new JsonObject();
        c.addProperty("rendered", cost == null ? "" : cost.toSimpleString());
        c.addProperty("mana", cost == null || cost.hasNoManaCost() ? "" : String.valueOf(cost.getTotalMana()));
        c.addProperty("cmc", cost == null || cost.hasNoManaCost() ? 0 : cost.getTotalMana().getCMC());
        body.add("cost", c);
        body.addProperty("min", 0);
        body.addProperty("max", ceiling);
        // The engine's own bound, which is Integer.MAX_VALUE for Multikicker; -1 when it is
        // effectively unbounded, so a host never sees a range it cannot price.
        body.addProperty("engineMax", max == Integer.MAX_VALUE ? -1 : max);
        final JsonObject ans = ask("chooseNumberForKeywordCost", "keywordCost", body, sa);
        if (ans == null) {
            final Echo e = takeEcho();
            final int out = super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max);
            echo(e, echoInt("value", out));
            return out;
        }
        final Integer v = optInt(ans, "value");
        if (v == null || v < 0 || v > ceiling) {
            refuse("chooseNumberForKeywordCost",
                    "value " + v + " outside [0," + ceiling + "] for " + prompt);
            return super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max);
        }
        counters.instrument("keywordCost.answered");
        if (v > 0) {
            counters.instrument("keywordCost.paid");
        }
        return v;
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(final SpellAbility chosen,
            final List<OptionalCostValue> optionalCostValues) {
        count("chooseOptionalCosts");
        if (buildingMenu) {
            note("probe.chooseOptionalCosts"); // RL census (lane rl-r0-b4-1006): a menu-build probe, not a decision
            // Decline while enumerating: taking them here would silently drop the unkicked
            // ability from the menu. Both variants are offered instead (see
            // legalSpellAbilities), so the host votes on the cost rather than inheriting it.
            return Collections.emptyList();
        }
        if (!bridged() || optionalCostValues == null || optionalCostValues.isEmpty()) {
            return super.chooseOptionalCosts(chosen, optionalCostValues);
        }
        final JsonObject body = envelope(true);
        body.add("ability", StateEncoder.encodeSpellAbility(chosen));
        final JsonArray menu = new JsonArray();
        for (OptionalCostValue ocv : optionalCostValues) {
            final JsonObject o = new JsonObject();
            o.addProperty("type", String.valueOf(ocv.getType()));
            o.addProperty("rendered", ocv.getCost() == null ? "" : ocv.getCost().toSimpleString());
            o.addProperty("mana", ocv.getCost() == null || ocv.getCost().hasNoManaCost()
                    ? "" : String.valueOf(ocv.getCost().getTotalMana()));
            o.addProperty("cmc", ocv.getCost() == null || ocv.getCost().hasNoManaCost()
                    ? 0 : ocv.getCost().getTotalMana().getCMC());
            menu.add(o);
        }
        body.add("menu", menu);
        final JsonObject ans = ask("chooseOptionalCosts", "optionalCosts", body,
                new Object[] {chosen, optionalCostValues});
        if (ans == null) {
            final Echo e = takeEcho();
            final List<OptionalCostValue> out = super.chooseOptionalCosts(chosen, optionalCostValues);
            echo(e, echoIndices(optionalCostValues, out));
            return out;
        }
        final List<Integer> idx = optIntList(ans, "choices");
        if (idx == null) {
            refuse("chooseOptionalCosts", "missing/!array 'choices'");
            return super.chooseOptionalCosts(chosen, optionalCostValues);
        }
        final List<OptionalCostValue> picked = new ArrayList<>();
        for (int i : idx) {
            if (i < 0 || i >= optionalCostValues.size() || picked.contains(optionalCostValues.get(i))) {
                refuse("chooseOptionalCosts", "optional-cost index out of range/duplicate: " + i);
                return super.chooseOptionalCosts(chosen, optionalCostValues);
            }
            picked.add(optionalCostValues.get(i));
        }
        return picked;
    }

    /**
     * Stack-instance candidates for one ability.
     *
     * <p>{@code TargetRestrictions.getAllCandidates} enumerates only
     * {@code game.getCardsIn(tgtZone)} through {@code sa.canTarget(Card)}, which for a
     * stack-zone target runs {@code isValid("Spell")} against the <em>card</em>. A
     * permanent spell on the stack fails that predicate, so counter-vs-permanent was never
     * offered at all. Forge's own count ({@code TargetRestrictions.getNumCandidates})
     * handles the stack on a separate branch via {@code canTargetSpellAbility}; this is
     * that branch.
     *
     * <p>Returns empty — never throws, never short-circuits the caller — when the
     * restriction does not name the stack, so callers can always sum it with
     * {@code getAllCandidates}.
     */
    private static List<SpellAbilityStackInstance> stackCandidates(final SpellAbility sa) {
        final List<SpellAbilityStackInstance> out = new ArrayList<>();
        if (sa == null || !sa.usesTargeting()) {
            return out;
        }
        try {
            final TargetRestrictions tgt = sa.getTargetRestrictions();
            if (tgt == null || tgt.getZone() == null || !tgt.getZone().contains(ZoneType.Stack)) {
                return out;
            }
            final Card host = sa.getHostCard();
            if (host == null || host.getGame() == null) {
                return out;
            }
            for (SpellAbilityStackInstance si : host.getGame().getStack()) {
                if (sa.canTargetSpellAbility(si.getSpellAbility())) {
                    out.add(si);
                }
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("stack candidate enumeration failed for " + sa, e);
        }
        return out;
    }

    /**
     * Every legal target for one ability: the stack branch summed with the card/player
     * branch, exactly as {@code TargetRestrictions.getNumCandidates} sums them.
     *
     * <p>Deliberately an OR, never an either/or on {@code tgtZone.contains(Stack)}: a
     * "counter target spell or destroy target permanent" shape names both zones, and
     * branching exclusively on the stack would make it vanish whenever the stack is empty
     * — the same defect this method exists to fix, pointed the other way.
     */
    private static int candidateCount(final SpellAbility sa) {
        int n = stackCandidates(sa).size();
        try {
            n += sa.getTargetRestrictions().getAllCandidates(sa).size();
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("entity candidate enumeration failed for " + sa, e);
        }
        return n;
    }

    /**
     * {@code SpellAbility.canPlay} does not check that legal targets exist, so without this
     * the menu offers e.g. a counterspell with an empty stack. Every entry we offer must be
     * an action the host can actually complete.
     */
    public static boolean hasEnoughTargets(final SpellAbility root) {
        SpellAbility cur = root;
        while (cur != null) {
            if (cur.usesTargeting() && candidateCount(cur) < cur.getMinTargets()) {
                return false;
            }
            cur = cur.getSubAbility();
        }
        return true;
    }

    @Override
    public void declareAttackers(final Player attacker, final Combat combat) {
        count("declareAttackers");
        if (!bridged()) {
            super.declareAttackers(attacker, combat);
            return;
        }
        final CardCollection possible = new CardCollection();
        for (Card c : attacker.getCreaturesInPlay()) {
            if (CombatUtil.canAttack(c)) {
                possible.add(c);
            }
        }
        final List<GameEntity> defenders = new ArrayList<>(combat.getDefenders());

        final JsonObject body = envelope(true);
        body.add("legalAttackers", StateEncoder.encodeCards(possible));
        body.add("legalDefenders", StateEncoder.encodeEntities(defenders));
        final JsonObject legalPairs = new JsonObject();
        for (Card c : possible) {
            final JsonArray defs = new JsonArray();
            for (GameEntity d : defenders) {
                if (CombatUtil.canAttack(c, d)) {
                    defs.add(d.getId());
                }
            }
            legalPairs.add(String.valueOf(c.getId()), defs);
        }
        body.add("legalPairs", legalPairs);
        // v2.8: the same map with typed defender refs. `legalPairs` keeps bare ids for one
        // minor version; these are unambiguous and are what an answer should echo.
        final JsonObject legalPairsTyped = new JsonObject();
        for (Card c : possible) {
            final JsonArray defs = new JsonArray();
            for (GameEntity d : defenders) {
                if (CombatUtil.canAttack(c, d)) {
                    defs.add(StateEncoder.entityRef(d));
                }
            }
            legalPairsTyped.add(String.valueOf(c.getId()), defs);
        }
        body.add("legalPairsTyped", legalPairsTyped);
        addAttackRequirements(body, combat, possible, defenders);

        final JsonObject ans = ask("declareAttackers", "attackers", body,
                new Object[] {possible, defenders, combat});
        if (ans == null) {
            final Echo e = takeEcho();
            super.declareAttackers(attacker, combat);
            // The declaration is the return value here; `combat` IS the answer.
            echo(e, echoAttackers(combat));
            return;
        }
        if (!ans.has("pairs") || !ans.get("pairs").isJsonArray()) {
            refuse("declareAttackers", "missing/!array 'pairs'");
            super.declareAttackers(attacker, combat);
            return;
        }
        combat.clearAttackers();
        String bad = null;
        // The defender side shares the players-and-cards id space (an opposing planeswalker
        // or battle is a legal defender), so it takes a typed ref exactly like a target.
        for (JsonElement pairEl : ans.getAsJsonArray("pairs")) {
            if (!pairEl.isJsonArray() || pairEl.getAsJsonArray().size() != 2) {
                bad = "each pair must be [attackerFid, defenderRef]: " + pairEl;
                break;
            }
            final JsonArray pr = pairEl.getAsJsonArray();
            final int attackerFid;
            try {
                attackerFid = pr.get(0).getAsInt();
            } catch (RuntimeException e) {
                bad = "attacker id is not a number: " + pr.get(0);
                break;
            }
            final EntityRef dref = parseRef(pr.get(1), "declareAttackers");
            if (dref == null) {
                bad = "unparseable defender reference: " + pr.get(1);
                break;
            }
            final Card c = findCard(possible, attackerFid);
            final GameEntity d = findTyped(defenders, dref);
            if (c == null || d == null) {
                bad = "unknown attacker/defender " + attackerFid + "/" + refText(dref);
                break;
            }
            if (!CombatUtil.canAttack(c, d)) {
                bad = c.getName() + " cannot attack " + d;
                break;
            }
            combat.addAttacker(c, d);
        }
        if (bad == null && !CombatUtil.validateAttackers(combat)) {
            bad = "CombatUtil.validateAttackers rejected the declaration";
        }
        if (bad != null) {
            combat.clearAttackers();
            refuse("declareAttackers", bad);
            super.declareAttackers(attacker, combat);
        }
    }

    /**
     * Attack requirements on the {@code attackers} ask (protocol v2.2).
     *
     * <p>{@code CombatUtil.validateAttackers} rejects a declaration that leaves more attack
     * requirements unmet than the best legal attack would (CR 508.1d). The host could not
     * see those requirements: Forge models must-attack as a <em>static ability</em>
     * ({@code StaticAbilityMustAttack}), never as a keyword string, so no amount of reading
     * {@code keywords} finds it — and the cards it bit on were <em>tokens</em>, which have
     * no cube entry to read text from at all. Same token-blindness class that
     * {@code minBlockers} closed for the block step.
     *
     * <p>Emits, all keyed by attacker fid:
     * <ul>
     *   <li>{@code mustAttack} — defender ids this attacker is required to attack.</li>
     *   <li>{@code mustAttackAny} — attackers required to attack, with no specific
     *       defender (every legal defender carries the requirement).</li>
     *   <li>{@code requiresAlso} — <em>other</em> attackers whose not attacking counts as a
     *       violation. This is the Goblin Rabblemaster shape: "other Goblin creatures you
     *       control attack each combat if able".</li>
     *   <li>{@code bestAttackViolations} — the ceiling. A declaration is legal iff its own
     *       violation count is less than or equal to this, so a non-zero value is how the
     *       host tells a hard requirement from one it may leave unmet.</li>
     * </ul>
     */
    private static void addAttackRequirements(final JsonObject body, final Combat combat,
            final CardCollection possible, final List<GameEntity> defenders) {
        final JsonObject mustAttack = new JsonObject();
        final JsonObject mustAttackTyped = new JsonObject();
        final JsonArray mustAttackAny = new JsonArray();
        final JsonObject requiresAlso = new JsonObject();
        boolean anyRequirement = false;
        try {
            final AttackConstraints constraints = combat.getAttackConstraints();
            for (Card c : possible) {
                final AttackRequirement req = constraints.getRequirements().get(c);
                if (req == null || !req.hasRequirement()) {
                    continue;
                }
                anyRequirement = true;
                final JsonArray defs = new JsonArray();
                for (Pair<GameEntity, Integer> e : req.getSortedRequirements()) {
                    if (e.getValue() != null && e.getValue() > 0 && e.getKey() != null) {
                        defs.add(e.getKey().getId());
                    }
                }
                if (defs.size() > 0) {
                    mustAttack.add(String.valueOf(c.getId()), defs);
                    final JsonArray typed = new JsonArray();
                    for (Pair<GameEntity, Integer> e : req.getSortedRequirements()) {
                        if (e.getValue() != null && e.getValue() > 0 && e.getKey() != null) {
                            typed.add(StateEncoder.entityRef(e.getKey()));
                        }
                    }
                    mustAttackTyped.add(String.valueOf(c.getId()), typed);
                    // A requirement spread across every legal defender is "must attack",
                    // not "must attack that one".
                    if (defs.size() == defenders.size()) {
                        mustAttackAny.add(c.getId());
                    }
                }
                if (!req.getCausesToAttack().isEmpty()) {
                    final JsonArray also = new JsonArray();
                    for (Card other : req.getCausesToAttack().keySet()) {
                        also.add(other.getId());
                    }
                    requiresAlso.add(String.valueOf(c.getId()), also);
                }
            }
            body.add("mustAttack", mustAttack);
            body.add("mustAttackTyped", mustAttackTyped);
            body.add("mustAttackAny", mustAttackAny);
            body.add("requiresAlso", requiresAlso);
            // getLegalAttackers() searches the attack space, so only pay for it when a
            // requirement actually exists; with none, the ceiling is trivially 0.
            body.addProperty("bestAttackViolations",
                    anyRequirement ? constraints.getLegalAttackers().getRight() : 0);
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("attack requirement enumeration failed", e);
            if (!body.has("mustAttack")) {
                body.add("mustAttack", mustAttack);
                body.add("mustAttackTyped", mustAttackTyped);
                body.add("mustAttackAny", mustAttackAny);
                body.add("requiresAlso", requiresAlso);
            }
            // Absent rather than wrong: a host that sees no ceiling must not assume zero.
            body.add("bestAttackViolations", com.google.gson.JsonNull.INSTANCE);
        }
    }

    @Override
    public void declareBlockers(final Player defender, final Combat combat) {
        count("declareBlockers");
        if (!bridged()) {
            super.declareBlockers(defender, combat);
            return;
        }
        final CardCollection possible = new CardCollection();
        for (Card c : defender.getCreaturesInPlay()) {
            if (CombatUtil.canBlock(c, combat)) {
                possible.add(c);
            }
        }
        final CardCollection attackers = combat.getAttackers();

        final JsonObject body = envelope(true);
        body.add("legalBlockers", StateEncoder.encodeCards(possible));
        body.add("attackers", StateEncoder.encodeCards(attackers));
        // Protocol v2. Forge validates a block declaration AS A WHOLE, so a single blocker
        // on a menacing attacker refuses the entire step. The keyword list on each card
        // now carries "Menace", but the requirement can also come from an effect with no
        // keyword at all, so state the number outright.
        final JsonObject minBlockers = new JsonObject();
        for (Card a : attackers) {
            try {
                minBlockers.addProperty(String.valueOf(a.getId()),
                        CombatUtil.getMinNumBlockersForAttacker(a, defender));
            } catch (RuntimeException e) {
                minBlockers.addProperty(String.valueOf(a.getId()), 1);
            }
        }
        body.add("minBlockers", minBlockers);
        final JsonObject legalPairs = new JsonObject();
        for (Card b : possible) {
            final JsonArray atk = new JsonArray();
            for (Card a : attackers) {
                if (CombatUtil.canBlock(a, b, combat)) {
                    atk.add(a.getId());
                }
            }
            legalPairs.add(String.valueOf(b.getId()), atk);
        }
        body.add("legalPairs", legalPairs);

        final JsonObject ans = ask("declareBlockers", "blockers", body,
                new Object[] {possible, attackers, combat});
        if (ans == null) {
            final Echo e = takeEcho();
            super.declareBlockers(defender, combat);
            echo(e, echoBlockers(combat));
            return;
        }
        final List<int[]> pairs = optPairs(ans, "pairs");
        if (pairs == null) {
            refuse("declareBlockers", "missing/!array 'pairs'");
            super.declareBlockers(defender, combat);
            return;
        }
        final CardCollection applied = new CardCollection();
        String bad = null;
        for (int[] pr : pairs) {
            final Card b = findCard(possible, pr[0]);
            final Card a = findCard(attackers, pr[1]);
            if (b == null || a == null) {
                bad = "unknown blocker/attacker " + pr[0] + "/" + pr[1];
                break;
            }
            if (!CombatUtil.canBlock(a, b, combat)) {
                bad = b.getName() + " cannot block " + a.getName();
                break;
            }
            combat.addBlocker(a, b);
            applied.add(b);
        }
        if (bad == null) {
            final String problem = CombatUtil.validateBlocks(combat, defender);
            if (problem != null) {
                bad = "CombatUtil.validateBlocks: " + problem;
            }
        }
        if (bad != null) {
            for (Card b : applied) {
                combat.undoBlockingAssignment(b);
            }
            refuse("declareBlockers", bad);
            super.declareBlockers(defender, combat);
        }
    }

    /**
     * Play or draw (protocol v2.6).
     *
     * <p>Was an <em>uncounted</em> decision surface: the bridge inherited
     * {@code PlayerControllerAi}'s "AI is brave" — always take the play — so Forge silently
     * decided play/draw for the bridged seat, 88 times in one panel.
     *
     * <p>Who is asked, from {@code GameAction.java:2415-2440}: game 1 picks the chooser by
     * {@code Aggregates.random}; every later game in a match gives the choice to the
     * <em>loser of the previous game</em>. Only that one player's controller is called, and
     * the return value is the player who actually goes first. So our seat is asked only
     * when it won the roll or lost the last game — and because the bridged seat loses more
     * often than it wins, it was the chooser more often, and always took the play. That is
     * how a bridge arm reached 61% on the play against a null arm's 50%: on-the-play rate
     * is a <em>function of</em> win rate here, so it is a collider, not a stray covariate.
     */
    @Override
    public Player chooseStartingPlayer(final boolean isFirstGame) {
        count("chooseStartingPlayer");
        if (!bridged()) {
            return super.chooseStartingPlayer(isFirstGame);
        }
        final JsonObject body = envelope(true);
        // The seat being asked IS the seat that won the roll; Forge never asks the other.
        body.addProperty("winner", seat);
        body.addProperty("choosingSeat", seat);
        body.addProperty("firstGame", isFirstGame);
        final JsonObject ans = ask("chooseStartingPlayer", "startingPlayer", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final Player out = super.chooseStartingPlayer(isFirstGame);
            // `play` is the answer field; Forge returns the player who goes first.
            echo(e, echoBool("play", out == getPlayer()));
            return out;
        }
        final Boolean play = optBool(ans, "play");
        if (play == null) {
            refuse("chooseStartingPlayer", "expected boolean 'play'");
            return super.chooseStartingPlayer(isFirstGame);
        }
        counters.instrument(play ? "start.tookPlay" : "start.tookDraw");
        if (play) {
            return getPlayer();
        }
        // Decline: the opponent goes first. With more than two seats Forge's own rule is
        // "the chooser or nobody", so fall back rather than invent a seating order.
        Player other = null;
        for (Player p : getGame().getPlayers()) {
            if (p != getPlayer()) {
                if (other != null) {
                    refuse("chooseStartingPlayer", "cannot decline the play in a >2 player game");
                    return super.chooseStartingPlayer(isFirstGame);
                }
                other = p;
            }
        }
        return other == null ? getPlayer() : other;
    }

    /**
     * London mulligan bottoming (protocol v2.6). Also previously uncounted-and-inherited:
     * Forge chose which cards our seat put on the bottom. Routed through the existing
     * {@code cardsChoice} machinery with min = max = the number to return.
     */
    @Override
    public CardCollectionView tuckCardsViaMulligan(final CardCollectionView hand, final int cardsToReturn) {
        count("tuckCardsViaMulligan");
        if (!bridged() || cardsToReturn <= 0) {
            return forgeTuck(hand, cardsToReturn);
        }
        final CardCollection picked = askForCards("tuckCardsViaMulligan", hand,
                cardsToReturn, cardsToReturn, "put on the bottom (London mulligan)", null);
        if (picked == null) {
            // `takeEcho()` is null on the REFUSAL branch of `askForCards` — `ask` clears
            // the token whenever the host actually answered — so the echo fires only on a
            // delegation, exactly as the ECHO block prescribes.
            final Echo e = takeEcho();
            final CardCollectionView out = forgeTuck(hand, cardsToReturn);
            echo(e, echoCards(out));
            return out;
        }
        return picked;
    }

    /**
     * Forge's own London-mulligan choice. PlayerControllerAi.tuckCardsViaMulligan probes this controller's
     * willPutCardOnTop as a heuristic; inside it that is not a decision, so it is never asked (lane rl-r0-b4-1006).
     */
    private CardCollectionView forgeTuck(final CardCollectionView hand, final int cardsToReturn) {
        probing++;
        try {
            return super.tuckCardsViaMulligan(hand, cardsToReturn);
        } finally {
            probing--;
        }
    }

    @Override
    public boolean mulliganKeepHand(final Player p, final int cardsToReturn) {
        count("mulliganKeepHand");
        if (!bridged()) {
            return super.mulliganKeepHand(p, cardsToReturn);
        }
        final JsonObject body = envelope(true);
        body.addProperty("cardsToReturn", cardsToReturn);
        body.add("hand", StateEncoder.encodeCards(getPlayer().getCardsIn(ZoneType.Hand)));
        final JsonObject ans = ask("mulliganKeepHand", "mulligan", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final boolean out = super.mulliganKeepHand(p, cardsToReturn);
            echo(e, echoBool("keep", out));
            return out;
        }
        Boolean keep = optBool(ans, "keep");
        if (keep == null) {
            final Integer choice = optInt(ans, "choice");
            if (choice == null || choice < 0 || choice > 1) {
                refuse("mulliganKeepHand", "expected boolean 'keep' or choice 0/1");
                return super.mulliganKeepHand(p, cardsToReturn);
            }
            keep = choice == 1;
        }
        return keep;
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(final int numDiscard) {
        count("chooseCardsToDiscardToMaximumHandSize");
        if (!bridged()) {
            return super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
        }
        final CardCollectionView hand = getPlayer().getCardsIn(ZoneType.Hand);
        final CardCollection picked = askForCards("chooseCardsToDiscardToMaximumHandSize", hand,
                numDiscard, numDiscard, "discard to maximum hand size", null);
        if (picked == null) {
            final Echo e = takeEcho();
            final CardCollectionView out = super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
            echo(e, echoCards(out));
            return out;
        }
        return picked;
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(final SpellAbility sa, final int min, final int max,
            final CardCollectionView validTargets, final String message) {
        count("choosePermanentsToSacrifice");
        if (!bridged()) {
            return super.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
        }
        final CardCollection picked = askForCards("choosePermanentsToSacrifice", validTargets, min, max, message, sa);
        if (picked == null) {
            final Echo e = takeEcho();
            final CardCollectionView out =
                    super.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
            echo(e, echoCards(out));
            return out;
        }
        return picked;
    }

    @Override
    public CardCollectionView chooseCardsForEffect(final CardCollectionView sourceList, final SpellAbility sa,
            final String title, final int min, final int max, final boolean isOptional,
            final Map<String, Object> params) {
        count("chooseCardsForEffect");
        if (!bridged()) {
            return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
        }
        final CardCollection picked = askForCards("chooseCardsForEffect", sourceList,
                isOptional ? 0 : min, max, title, sa);
        if (picked == null) {
            final Echo e = takeEcho();
            final CardCollectionView out =
                    super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
            echo(e, echoCards(out));
            return out;
        }
        return picked;
    }

    /** Shared {@code cardsChoice} round trip. Returns null to mean "delegate". */
    private CardCollection askForCards(final String method, final CardCollectionView pool,
            final int min, final int max, final String title, final SpellAbility sa) {
        final JsonObject body = envelope(true);
        body.addProperty("title", String.valueOf(title));
        body.addProperty("min", min);
        body.addProperty("max", max);
        body.add("menu", StateEncoder.encodeCards(pool));
        if (sa != null) {
            body.add("ability", StateEncoder.encodeSpellAbility(sa));
        }
        final JsonObject ans = ask(method, "cardsChoice", body, pool);
        if (ans == null) {
            return null;
        }
        final List<Integer> ids = optIntList(ans, "choices");
        if (ids == null) {
            refuse(method, "missing/!array 'choices'");
            return null;
        }
        if (ids.size() < min || (max >= 0 && ids.size() > max)) {
            refuse(method, "chose " + ids.size() + " outside [" + min + "," + max + "]");
            return null;
        }
        final CardCollection picked = new CardCollection();
        for (int fid : ids) {
            final Card c = findCard(pool, fid);
            if (c == null || picked.contains(c)) {
                refuse(method, "unknown/duplicate card id " + fid);
                return null;
            }
            picked.add(c);
        }
        return picked;
    }

    @Override
    public boolean chooseTargetsFor(final SpellAbility currentAbility) {
        count("chooseTargetsFor");
        if (!bridged() || currentAbility == null || !currentAbility.usesTargeting()) {
            return super.chooseTargetsFor(currentAbility);
        }
        final TargetRestrictions tgt = currentAbility.getTargetRestrictions();
        // Mixed-zone by construction: both branches are always enumerated and the menu is
        // their union. See stackCandidates/candidateCount.
        final List<GameEntity> candidates;
        try {
            candidates = tgt.getAllCandidates(currentAbility);
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("target candidate enumeration failed", e);
            refuse("chooseTargetsFor", "candidate enumeration threw: " + e);
            return super.chooseTargetsFor(currentAbility);
        }
        final List<SpellAbilityStackInstance> stack = stackCandidates(currentAbility);
        final int min = currentAbility.getMinTargets();
        final int max = dividedTargetCap(currentAbility, currentAbility.getMaxTargets());

        final JsonObject body = envelope(true);
        body.add("ability", StateEncoder.encodeSpellAbility(currentAbility));
        if (session.getLocalAnswerer() != null) {
            body.addProperty("origin", targetingOrigin); // RL seat census (lane rl-r0-b4-1006); never on the stdio wire
        }
        final JsonArray menu = StateEncoder.encodeEntities(candidates);
        for (SpellAbilityStackInstance si : stack) {
            menu.add(StateEncoder.encodeStackCandidate(getGame(), si));
        }
        body.add("menu", menu);
        body.addProperty("min", min);
        body.addProperty("max", max);
        // Protocol v2.1: tell the host that this menu can contain stack candidates, and how
        // to read them, without making it infer either from the entries present.
        body.addProperty("stackCandidates", stack.size());
        body.addProperty("spellTargetIdBase", StateEncoder.SPELL_TARGET_ID_BASE);
        body.addProperty("targetsStackZone",
                tgt.getZone() != null && tgt.getZone().contains(ZoneType.Stack));
        // v2.9: the divided-as-you-choose total. Without it a host cannot emit a `divide`
        // map at all -- it has no number to partition -- so allocations silently fell back
        // to the JVM's even split. `divideRemaining` is what is still unassigned, which is
        // what a re-entrant targeting pass actually has to work with.
        final boolean divided = currentAbility.isDividedAsYouChoose();
        body.addProperty("dividedAsYouChoose", divided);
        if (divided) {
            final Integer total = currentAbility.getDividedValue();
            if (total == null) {
                body.add("divideTotal", com.google.gson.JsonNull.INSTANCE);
            } else {
                body.addProperty("divideTotal", total);
            }
            body.addProperty("divideRemaining", currentAbility.getStillToDivide());
        }

        final JsonObject ans = ask("chooseTargetsFor", "targets", body,
                new Object[] {currentAbility, candidates, stack});
        if (ans == null) {
            final Echo e = takeEcho();
            final boolean out = super.chooseTargetsFor(currentAbility);
            // The chosen targets are on the ability, not in the return value.
            echo(e, echoTargets(currentAbility, out), currentAbility);
            return out;
        }
        if (!ans.has("choices") || !ans.get("choices").isJsonArray()) {
            refuse("chooseTargetsFor", "missing/!array 'choices'");
            return super.chooseTargetsFor(currentAbility);
        }
        final JsonArray rawChoices = ans.getAsJsonArray("choices");
        if (rawChoices.size() < min || rawChoices.size() > max) {
            refuse("chooseTargetsFor", "chose " + rawChoices.size()
                    + " targets outside [" + min + "," + max + "]");
            return super.chooseTargetsFor(currentAbility);
        }
        final TargetChoices before = currentAbility.getTargets();
        currentAbility.resetTargets();
        for (JsonElement el : rawChoices) {
            final EntityRef ref = parseRef(el, "chooseTargetsFor");
            if (ref == null) {
                currentAbility.setTargets(before);
                refuse("chooseTargetsFor", "unparseable target reference: " + el);
                return super.chooseTargetsFor(currentAbility);
            }
            // Stack instances live in their own namespace and are resolved only there.
            final boolean toStack = "spell".equals(ref.kind)
                    || (ref.kind == null && ref.id >= StateEncoder.SPELL_TARGET_ID_BASE);
            final String problem = toStack
                    ? addSpellTarget(currentAbility, stack,
                            ref.id >= StateEncoder.SPELL_TARGET_ID_BASE
                                    ? ref.id : ref.id + StateEncoder.SPELL_TARGET_ID_BASE)
                    : addEntityTarget(currentAbility, candidates, ref);
            if (problem != null) {
                currentAbility.setTargets(before);
                // Counted, always. A silent fall-through to Forge's AI here would have it
                // pick our targets and the attribution would credit the pilot for them.
                refuse("chooseTargetsFor", problem);
                return super.chooseTargetsFor(currentAbility);
            }
        }
        final String divideProblem = applyDividedAllocation(currentAbility, ans);
        if (divideProblem != null) {
            currentAbility.setTargets(before);
            refuse("chooseTargetsFor", divideProblem);
            return super.chooseTargetsFor(currentAbility);
        }
        return true;
    }

    /**
     * RL seats (lane rl-r0-b4b-1006): a divided-as-you-choose ability gives every target at least one (CR 601.2d), so
     * no more targets than the amount to divide can be chosen. The seat's menu says so, rather than refusing the
     * answer afterwards ("cannot divide 2 among 4 targets": Fire Covenant with X = 2). Other hosts keep the
     * engine's own maximum.
     */
    private int dividedTargetCap(final SpellAbility sa, final int max) {
        return localAnswers() ? dividedTargetCap0(sa, max) : max;
    }

    static int dividedTargetCap0(final SpellAbility sa, final int max) {
        if (!sa.isDividedAsYouChoose() || sa.getDividedValue() == null) {
            return max;
        }
        return Math.max(0, Math.min(max, sa.getStillToDivide() + sa.getTargets().size()));
    }

    /**
     * Clear an ability's targets before the seat chooses them: {@code clearTargets}, not {@code resetTargets}, because
     * it also (re)computes the amount a divided-as-you-choose ability divides. Forge's AI never sets it on the triggers
     * it prepares (Inferno Titan, Fury, The Grand Evolution), so every seat ask for one was refused "no total to
     * divide" and fell back to Forge (lane rl-r0-b4b-1006: 119 of 119 divided refusals in 1,000 r1-bank train games).
     */
    static void clearForSeat(final SpellAbility cur) {
        cur.clearTargets();
    }

    /**
     * Split a "divided as you choose" amount across the chosen targets (protocol v2.7).
     *
     * <p>Choosing targets is only half of targeting such a spell: {@code DamageDealEffect}
     * then reads {@code sa.getDividedValue(target)} per target and dereferences it. Adding
     * targets without an allocation left that null, and the NPE escaped as a crashed game
     * stamped "Draw" -- three of fifteen bridged crashes in one campaign.
     *
     * <p>The answer may carry {@code "divide": {"<targetId>": n}}; otherwise the amount is
     * split evenly with the remainder on the first target, which is Forge's own convention
     * ({@code PossibleTargetSelector}).
     *
     * @return null on success, or the reason to refuse
     */
    private static String applyDividedAllocation(final SpellAbility sa, final JsonObject ans) {
        if (!sa.isDividedAsYouChoose()) {
            return null;
        }
        final List<GameObject> chosen = Lists.newArrayList(sa.getTargets());
        if (chosen.isEmpty()) {
            return null;
        }
        final Integer totalObj = sa.getDividedValue();
        if (totalObj == null) {
            // The engine has not told us how much there is to divide; guessing here is how
            // an illegal allocation gets built. Hand it back rather than invent one.
            return "divided-as-you-choose ability with no total to divide: " + sa;
        }
        final int total = totalObj;
        final JsonObject explicit = ans.has("divide") && ans.get("divide").isJsonObject()
                ? ans.getAsJsonObject("divide") : null;
        if (explicit != null) {
            int sum = 0;
            for (GameObject go : chosen) {
                // Same flat id space as `choices`, so accept a typed key first
                // ("card:1" / "player:1") and fall back to the bare id.
                final String typedKey = kindOf(go) + ":" + idOf(go);
                final String bareKey = String.valueOf(idOf(go));
                final String key = explicit.has(typedKey) ? typedKey : bareKey;
                if (!explicit.has(key)) {
                    return "'divide' omits target " + typedKey;
                }
                final int n;
                try {
                    n = explicit.get(key).getAsInt();
                } catch (RuntimeException e) {
                    return "'divide' entry for " + key + " is not a number";
                }
                if (n < 1) {
                    // CR 601.2d: every target must get at least one.
                    return "'divide' gives " + n + " to target " + key;
                }
                sa.addDividedAllocation(go, n);
                sum += n;
            }
            if (sum != total) {
                return "'divide' allocates " + sum + " of " + total;
            }
            return null;
        }
        if (total < chosen.size()) {
            return "cannot divide " + total + " among " + chosen.size() + " targets";
        }
        final int each = total / chosen.size();
        int leftover = total - each * chosen.size();
        for (GameObject go : chosen) {
            sa.addDividedAllocation(go, each + leftover);
            leftover = 0;
        }
        return null;
    }

    private static String kindOf(final GameObject go) {
        if (go instanceof Player) {
            return "player";
        }
        if (go instanceof Card) {
            return "card";
        }
        if (go instanceof SpellAbility) {
            return "spell";
        }
        return "entity";
    }

    private static int idOf(final GameObject go) {
        if (go instanceof GameEntity) {
            return ((GameEntity) go).getId();
        }
        if (go instanceof SpellAbility) {
            return StateEncoder.SPELL_TARGET_ID_BASE + ((SpellAbility) go).getId();
        }
        return -1;
    }

    /** Apply one card/player target. Returns null on success, or the reason to refuse. */
    private static String addEntityTarget(final SpellAbility sa, final List<GameEntity> candidates,
            final EntityRef ref) {
        final GameEntity ge = findTyped(candidates, ref);
        if (ge == null) {
            return "unknown entity target " + refText(ref);
        }
        if (!sa.canTarget(ge)) {
            return "illegal entity target " + refText(ref) + " (" + ge + ")";
        }
        return sa.getTargets().add(ge) ? null : "TargetChoices refused entity " + refText(ref);
    }

    /** Apply one stack (spell/ability) target. Returns null on success, or the refusal reason. */
    private static String addSpellTarget(final SpellAbility sa,
            final List<SpellAbilityStackInstance> stack, final int id) {
        final int stackId = id - StateEncoder.SPELL_TARGET_ID_BASE;
        SpellAbilityStackInstance found = null;
        for (SpellAbilityStackInstance si : stack) {
            if (si.getId() == stackId) {
                found = si;
                break;
            }
        }
        if (found == null) {
            return "unknown stack target stackId " + stackId + " (wire id " + id + ")";
        }
        final SpellAbility target = found.getSpellAbility();
        if (target == null || !sa.canTargetSpellAbility(target)) {
            return "illegal stack target stackId " + stackId + " (" + found.getStackDescription() + ")";
        }
        return sa.getTargets().add(target) ? null : "TargetChoices refused stack target " + stackId;
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(final FCollectionView<T> optionList,
            final DelayedReveal delayedReveal, final SpellAbility sa, final String title,
            final boolean isOptional, final Player relatedPlayer, final Map<String, Object> params) {
        count("chooseSingleEntityForEffect");
        if (!bridged() || optionList == null || optionList.isEmpty()) {
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional,
                    relatedPlayer, params);
        }
        final List<T> options = Lists.newArrayList(optionList);
        final JsonObject body = envelope(true);
        body.addProperty("title", String.valueOf(title));
        body.addProperty("optional", isOptional);
        body.add("menu", StateEncoder.encodeEntities(options));
        if (sa != null) {
            body.add("ability", StateEncoder.encodeSpellAbility(sa));
        }
        final JsonObject ans = ask("chooseSingleEntityForEffect", "entityChoice", body,
                new Object[] {options, sa, isOptional});
        if (ans == null) {
            final Echo e = takeEcho();
            final T out = super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title,
                    isOptional, relatedPlayer, params);
            final int at = out == null ? -1 : indexOfIdentity(options, out);
            // `none` is a legal answer only when the ask said `optional`; a non-null
            // pick that is somehow not in the menu we published is reported as an
            // unmatched choice rather than silently as index -1.
            echo(e, out == null ? echoBool("none", true)
                    : at >= 0 ? echoInt("choice", at) : unmatchedChoice());
            return out;
        }
        if (isOptional && Boolean.TRUE.equals(optBool(ans, "none"))) {
            return null;
        }
        final Integer choice = optInt(ans, "choice");
        if (choice == null || choice < 0 || choice >= options.size()) {
            refuse("chooseSingleEntityForEffect", "choice out of range: " + choice);
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional,
                    relatedPlayer, params);
        }
        return options.get(choice);
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(final FCollectionView<T> optionList,
            final int min, final int max, final DelayedReveal delayedReveal, final SpellAbility sa,
            final String title, final Player relatedPlayer, final Map<String, Object> params) {
        count("chooseEntitiesForEffect");
        if (!bridged() || optionList == null || optionList.isEmpty()) {
            return super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title,
                    relatedPlayer, params);
        }
        final List<T> options = Lists.newArrayList(optionList);
        final JsonObject body = envelope(true);
        body.addProperty("title", String.valueOf(title));
        body.addProperty("min", min);
        body.addProperty("max", max);
        body.add("menu", StateEncoder.encodeEntities(options));
        if (sa != null) {
            body.add("ability", StateEncoder.encodeSpellAbility(sa));
        }
        final JsonObject ans = ask("chooseEntitiesForEffect", "entityChoice", body,
                new Object[] {options, sa, Boolean.FALSE});
        if (ans == null) {
            final Echo e = takeEcho();
            final List<T> out = super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa,
                    title, relatedPlayer, params);
            echo(e, echoIndices(options, out));
            return out;
        }
        final List<Integer> idx = optIntList(ans, "choices");
        if (idx == null || idx.size() < min || idx.size() > max) {
            refuse("chooseEntitiesForEffect", "bad 'choices' for [" + min + "," + max + "]");
            return super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title,
                    relatedPlayer, params);
        }
        final List<T> picked = new ArrayList<>();
        for (int i : idx) {
            if (i < 0 || i >= options.size() || picked.contains(options.get(i))) {
                refuse("chooseEntitiesForEffect", "index out of range/duplicate: " + i);
                return super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title,
                        relatedPlayer, params);
            }
            picked.add(options.get(i));
        }
        return picked;
    }

    @Override
    public int chooseNumber(final SpellAbility sa, final String title, final int min, final int max) {
        count("chooseNumber");
        if (!bridged()) {
            return super.chooseNumber(sa, title, min, max);
        }
        final JsonObject body = envelope(true);
        body.addProperty("title", String.valueOf(title));
        body.addProperty("min", min);
        body.addProperty("max", max);
        if (sa != null) {
            body.add("ability", StateEncoder.encodeSpellAbility(sa));
        }
        final JsonObject ans = ask("chooseNumber", "number", body, sa);
        if (ans == null) {
            final Echo e = takeEcho();
            final int out = super.chooseNumber(sa, title, min, max);
            echo(e, echoInt("value", out));
            return out;
        }
        final Integer v = optInt(ans, "value");
        if (v == null || v < min || v > max) {
            refuse("chooseNumber", "value " + v + " outside [" + min + "," + max + "]");
            return super.chooseNumber(sa, title, min, max);
        }
        return v;
    }

    @Override
    public int chooseNumber(final SpellAbility sa, final String title, final List<Integer> values,
            final Player relatedPlayer) {
        count("chooseNumber");
        if (!bridged() || values == null || values.isEmpty()) {
            return super.chooseNumber(sa, title, values, relatedPlayer);
        }
        final JsonObject body = envelope(true);
        body.addProperty("title", String.valueOf(title));
        final JsonArray vals = new JsonArray();
        for (int v : values) {
            vals.add(v);
        }
        body.add("values", vals);
        if (sa != null) {
            body.add("ability", StateEncoder.encodeSpellAbility(sa));
        }
        final JsonObject ans = ask("chooseNumber", "number", body, sa);
        if (ans == null) {
            final Echo e = takeEcho();
            final int out = super.chooseNumber(sa, title, values, relatedPlayer);
            echo(e, echoInt("value", out));
            return out;
        }
        final Integer v = optInt(ans, "value");
        if (v == null || !values.contains(v)) {
            refuse("chooseNumber", "value " + v + " not offered");
            return super.chooseNumber(sa, title, values, relatedPlayer);
        }
        return v;
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(final SpellAbility sa, final List<AbilitySub> possible,
            final int min, final int num, final boolean allowRepeat) {
        count("chooseModeForAbility");
        if (!bridged() || possible == null || possible.isEmpty()) {
            return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        }
        final JsonObject body = envelope(true);
        body.addProperty("min", min);
        body.addProperty("num", num);
        body.addProperty("max", num); // protocol v2 alias; `num` kept for v1 hosts
        body.addProperty("allowRepeat", allowRepeat);
        body.add("ability", StateEncoder.encodeSpellAbility(sa));
        final JsonArray modes = new JsonArray();
        for (int i = 0; i < possible.size(); i++) {
            final AbilitySub s = possible.get(i);
            final JsonObject m = new JsonObject();
            m.addProperty("index", i); // answers are indices; state them
            m.addProperty("api", s.getApi() == null ? "" : s.getApi().toString());
            m.addProperty("description", String.valueOf(s.getDescription()));
            m.addProperty("stackDescription", String.valueOf(s.getStackDescription()));
            m.addProperty("usesTargeting", s.usesTargeting());
            modes.add(m);
        }
        body.add("menu", modes);
        final JsonObject ans = ask("chooseModeForAbility", "mode", body, new Object[] {sa, possible});
        if (ans == null) {
            final Echo e = takeEcho();
            final List<AbilitySub> out = super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
            // `possible` is the menu and the answer is its indices, repeats included:
            // `indexOfIdentity` maps a repeated mode back to the same index, which is
            // what an answer with `allowRepeat` would itself have sent.
            echo(e, echoIndices(possible, out));
            return out;
        }
        final List<Integer> idx = optIntList(ans, "choices");
        if (idx == null || idx.size() < min || idx.size() > num) {
            refuse("chooseModeForAbility", "bad 'choices' for [" + min + "," + num + "]");
            return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        }
        final List<AbilitySub> picked = new ArrayList<>();
        for (int i : idx) {
            if (i < 0 || i >= possible.size() || (!allowRepeat && picked.contains(possible.get(i)))) {
                refuse("chooseModeForAbility", "mode index out of range/repeat: " + i);
                return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
            }
            picked.add(possible.get(i));
        }
        return picked;
    }

    @Override
    public boolean confirmAction(final SpellAbility sa, final PlayerActionConfirmMode mode0, final String message,
            final List<String> options, final Card cardToShow, final Map<String, Object> params) {
        count("confirmAction");
        if (!bridged()) {
            return super.confirmAction(sa, mode0, message, options, cardToShow, params);
        }
        final JsonObject body = envelope(true);
        body.addProperty("mode", String.valueOf(mode0));
        body.addProperty("message", String.valueOf(message));
        if (sa != null) {
            body.add("ability", StateEncoder.encodeSpellAbility(sa));
        }
        if (cardToShow != null) {
            body.add("card", StateEncoder.encodeCardUnchecked(cardToShow));
        }
        final JsonObject ans = ask("confirmAction", "confirm", body, new Object[] {sa, cardToShow});
        if (ans == null) {
            final Echo e = takeEcho();
            final boolean out = super.confirmAction(sa, mode0, message, options, cardToShow, params);
            echo(e, echoBool("yes", out));
            return out;
        }
        final Boolean yes = optBool(ans, "yes");
        if (yes == null) {
            refuse("confirmAction", "expected boolean 'yes'");
            return super.confirmAction(sa, mode0, message, options, cardToShow, params);
        }
        return yes;
    }

    @Override
    public boolean chooseBinary(final SpellAbility sa, final String question, final BinaryChoiceType kindOfChoice,
            final Boolean defaultChoice) {
        count("chooseBinary");
        if (!bridged()) {
            return super.chooseBinary(sa, question, kindOfChoice, defaultChoice);
        }
        final JsonObject body = envelope(true);
        body.addProperty("message", String.valueOf(question));
        body.addProperty("binaryKind", String.valueOf(kindOfChoice));
        if (sa != null) {
            body.add("ability", StateEncoder.encodeSpellAbility(sa));
        }
        final JsonObject ans = ask("chooseBinary", "confirm", body, new Object[] {sa, null});
        if (ans == null) {
            final Echo e = takeEcho();
            final boolean out = super.chooseBinary(sa, question, kindOfChoice, defaultChoice);
            echo(e, echoBool("yes", out));
            return out;
        }
        final Boolean yes = optBool(ans, "yes");
        if (yes == null) {
            refuse("chooseBinary", "expected boolean 'yes'");
            return super.chooseBinary(sa, question, kindOfChoice, defaultChoice);
        }
        return yes;
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(final CardCollection topN) {
        count("arrangeForScry");
        observeLook(topN, ZoneType.Library);
        if (!bridged() || topN == null || topN.isEmpty()) {
            return super.arrangeForScry(topN);
        }
        final JsonObject body = envelope(true);
        body.add("menu", StateEncoder.encodeCards(topN));
        final JsonObject ans = ask("arrangeForScry", "scry", body, topN);
        if (ans == null) {
            final Echo e = takeEcho();
            final ImmutablePair<CardCollection, CardCollection> out = super.arrangeForScry(topN);
            final JsonObject a = new JsonObject();
            a.add("top", fidArray(out == null ? null : out.getLeft()));
            a.add("bottom", fidArray(out == null ? null : out.getRight()));
            echo(e, a);
            return out;
        }
        final List<Integer> top = optIntList(ans, "top");
        final List<Integer> bottom = optIntList(ans, "bottom");
        if (top == null || bottom == null || top.size() + bottom.size() != topN.size()) {
            refuse("arrangeForScry", "top+bottom must partition the " + topN.size() + " revealed cards");
            return super.arrangeForScry(topN);
        }
        final CardCollection toTop = new CardCollection();
        final CardCollection toBottom = new CardCollection();
        for (int fid : top) {
            final Card c = findCard(topN, fid);
            if (c == null || toTop.contains(c)) {
                refuse("arrangeForScry", "unknown/duplicate top card " + fid);
                return super.arrangeForScry(topN);
            }
            toTop.add(c);
        }
        for (int fid : bottom) {
            final Card c = findCard(topN, fid);
            if (c == null || toTop.contains(c) || toBottom.contains(c)) {
                refuse("arrangeForScry", "unknown/duplicate bottom card " + fid);
                return super.arrangeForScry(topN);
            }
            toBottom.add(c);
        }
        return ImmutablePair.of(toTop, toBottom);
    }

    @Override
    public CardCollection orderBlockers(final Card attacker, final CardCollection blockers) {
        count("orderBlockers");
        if (!bridged() || blockers == null || blockers.size() < 2) {
            return super.orderBlockers(attacker, blockers);
        }
        final JsonObject body = envelope(true);
        body.add("attacker", StateEncoder.encodeCardUnchecked(attacker));
        body.add("menu", StateEncoder.encodeCards(blockers));
        final JsonObject ans = ask("orderBlockers", "orderBlockers", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final CardCollection out = super.orderBlockers(attacker, blockers);
            // `orderBlockers` answers on `order`; `orderZone` answers on `choices`. The
            // two ordering kinds are NOT uniform and the host's own signature reader
            // says so — echoing the wrong key would publish an empty order for every
            // damage-assignment ordering this instrument is pointed at.
            final JsonObject a = new JsonObject();
            a.add("order", fidArray(out));
            echo(e, a);
            return out;
        }
        final List<Integer> order = optIntList(ans, "order");
        if (order == null || order.size() != blockers.size()) {
            refuse("orderBlockers", "'order' must be a permutation of all " + blockers.size() + " blockers");
            return super.orderBlockers(attacker, blockers);
        }
        final CardCollection out = new CardCollection();
        for (int fid : order) {
            final Card c = findCard(blockers, fid);
            if (c == null || out.contains(c)) {
                refuse("orderBlockers", "unknown/duplicate blocker " + fid);
                return super.orderBlockers(attacker, blockers);
            }
            out.add(c);
        }
        return out;
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(final Card attacker, final CardCollectionView blockers,
            final CardCollectionView remaining, final int damageDealt, final GameEntity defender,
            final boolean overrideOrder) {
        count("assignCombatDamage");
        if (!bridged()) {
            return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
        }
        final JsonObject body = envelope(true);
        body.add("attacker", StateEncoder.encodeCardUnchecked(attacker));
        body.add("menu", StateEncoder.encodeCards(blockers));
        body.addProperty("damage", damageDealt);
        body.addProperty("overrideOrder", overrideOrder);
        body.addProperty("defenderId", defender == null ? -1 : defender.getId());
        // v2.7: whether the -1 "excess through to the defender" key is legal at all here.
        // Forge calls this for BLOCKERS too, dividing a blocker's damage among the
        // attackers it blocks, and there `defender` is null.
        body.addProperty("allowExcessToDefender", defender != null);
        final JsonObject ans = ask("assignCombatDamage", "assignDamage", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final Map<Card, Integer> out = super.assignCombatDamage(attacker, blockers, remaining,
                    damageDealt, defender, overrideOrder);
            final JsonObject a = new JsonObject();
            final JsonObject assign = new JsonObject();
            if (out != null) {
                for (Map.Entry<Card, Integer> en : out.entrySet()) {
                    final int n = en.getValue() == null ? 0 : en.getValue();
                    // ZERO ENTRIES ARE DROPPED, and that is canonicalisation rather than
                    // loss. `Combat` treats a recipient absent from the map exactly as it
                    // treats one assigned 0, the answer reader's own `total` sums the same
                    // either way, and this side's `damage.droppedZeroExcess` instrument
                    // already applies the rule to the `-1` slot. Keeping them would put
                    // the echo in a shape the host's action grammar never emits, so an
                    // imitation target and a policy's output would differ on a difference
                    // that is not one — measured: 5 of 88 `assignDamage` echoes on the
                    // 2026-08-27 smoke, every one of them an ask with `damage: 0`.
                    if (n == 0) {
                        continue;
                    }
                    // The null key is the excess-to-defender sentinel, and it is spelled
                    // "-1" on the wire in both directions.
                    assign.addProperty(en.getKey() == null ? "-1" : String.valueOf(en.getKey().getId()), n);
                }
            }
            a.add("assign", assign);
            echo(e, a);
            return out;
        }
        if (!ans.has("assign") || !ans.get("assign").isJsonObject()) {
            refuse("assignCombatDamage", "missing 'assign' object");
            return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
        }
        final Map<Card, Integer> out = new HashMap<>();
        int total = 0;
        for (Map.Entry<String, JsonElement> e : ans.getAsJsonObject("assign").entrySet()) {
            final int amount;
            final int fid;
            try {
                fid = Integer.parseInt(e.getKey());
                amount = e.getValue().getAsInt();
            } catch (RuntimeException ex) {
                refuse("assignCombatDamage", "non-numeric assignment entry " + e.getKey());
                return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
            }
            if (amount < 0) {
                refuse("assignCombatDamage", "negative assignment to " + fid);
                return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
            }
            total += amount;
            // key -1 means "excess through to the defending player/planeswalker"
            if (fid < 0) {
                // FAIL CLOSED. Forge uses this same controller call to divide a BLOCKER's
                // damage among the attackers it blocks, and passes defender == null there.
                // Forwarding the sentinel makes Combat.assignBlockersDamage:750 call
                // damageMap.put(blocker, null, n), which Guava rejects -- and the NPE
                // escapes as a crashed game stamped "Draw". Ten of fifteen bridged crashes
                // in one campaign were this. The sentinel never leaves this method unless
                // there is a defender to receive it.
                if (defender == null) {
                    if (amount > 0) {
                        refuse("assignCombatDamage", "answer routed " + amount
                                + " to the defender, but this assignment has none"
                                + " (blocker path, CR 510.1d)");
                        return super.assignCombatDamage(attacker, blockers, remaining, damageDealt,
                                defender, overrideOrder);
                    }
                    counters.instrument("damage.droppedZeroExcess");
                    continue;
                }
                out.put(null, amount);
                continue;
            }
            final Card target = findCard(blockers, fid);
            if (target == null) {
                refuse("assignCombatDamage", "unknown blocker " + fid);
                return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
            }
            out.put(target, amount);
        }
        if (total != damageDealt) {
            refuse("assignCombatDamage", "assigned " + total + " of " + damageDealt);
            return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
        }
        return out;
    }

    // ------------------------------------------------------ counted delegations
    // Generated from the abstract surface of PlayerController: every remaining entry
    // point increments its own counter and delegates. This is the decision-surface
    // instrumentation; behaviour is byte-for-byte PlayerControllerAi.

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) { count("getAbilityToPlay"); return askOne("getAbilityToPlay", null, abilities, () -> super.getAbilityToPlay(hostCard, abilities, triggerEvent)); }
    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean mayChoseNewTargets) { count("playSpellAbilityNoStack"); bridgedPlayNoStack(effectSA, mayChoseNewTargets); }
    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) { if (!quietOrder) { count("orderSimultaneousSa"); } return super.orderSimultaneousSa(activePlayerSAs); }
    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) { count("orderAndPlaySimultaneousSa"); bridgedOrderAndPlay(activePlayerSAs); }
    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) { count("playTrigger"); return bridgedPlayTrigger(host, wrapperAbility, isMandatory); }
    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) { count("playSaFromPlayEffect"); return bridgedPlayFromEffect(tgtSA); }
    @Override
    public List<PaperCard> sideboard(final Deck deck, GameType gameType, String message) { count("sideboard"); return super.sideboard(deck, gameType, message); }
    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) { count("chooseCardsYouWonToAddToDeck"); return super.chooseCardsYouWonToAddToDeck(losses); }
    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) { count("divideShield"); return super.divideShield(effectSource, affected, shieldAmount); }
    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) { count("specifyManaCombo"); return super.specifyManaCombo(sa, colorSet, manaAmount, different); }
    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) { count("choosePermanentsToDestroy"); return super.choosePermanentsToDestroy(sa, min, max, validTargets, message); }
    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) { count("announceRequirements"); return super.announceRequirements(ability, min, max, announce); }
    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) { count("chooseNewTargetsFor"); return super.chooseNewTargetsFor(ability, filter, optional); }
    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) { count("chooseTarget"); return super.chooseTarget(sa, allTargets); }
    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) { count("helpPayForAssistSpell"); return super.helpPayForAssistSpell(cost, sa, max, requested); }
    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) { count("choosePlayerToAssistPayment"); return super.choosePlayerToAssistPayment(optionList, sa, title, max); }
    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) { count("chooseCardsForEffectMultiple"); return super.chooseCardsForEffectMultiple(validMap, sa, title, isOptional); }
    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num, Map<String, Object> params) { count("chooseSpellAbilitiesForEffect"); return bridgedSpellAbilitiesForEffect(spells, sa, title, num, params); }
    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) { count("chooseSingleSpellForEffect"); return askOne("chooseSingleSpellForEffect", sa, spells, () -> super.chooseSingleSpellForEffect(spells, sa, title, params)); }
    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string, int bid, Player winner) { count("confirmBidAction"); return super.confirmBidAction(sa, bidlife, string, bid, winner); }
    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) { count("confirmReplacementEffect"); return bridgedConfirmReplacement(replacementEffect, effectSA, affected, question); }
    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) { count("confirmStaticApplication"); return super.confirmStaticApplication(hostCard, mode, message, logic); }
    @Override
    public boolean confirmTrigger(WrappedAbility sa) { count("confirmTrigger"); return bridgedConfirmTrigger(sa); }
    @Override
    public List<Card> exertAttackers(List<Card> attackers) { count("exertAttackers"); return super.exertAttackers(attackers); }
    @Override
    public List<Card> enlistAttackers(List<Card> attackers) { count("enlistAttackers"); return super.enlistAttackers(attackers); }
    @Override
    public CardCollection orderBlocker(final Card attacker, final Card blocker, final CardCollection oldBlockers) { count("orderBlocker"); return super.orderBlocker(attacker, blocker, oldBlockers); }
    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) { count("orderAttackers"); return super.orderAttackers(blocker, attackers); }
    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addMsgSuffix) { count("reveal"); observeReveal(cards, zone, owner); super.reveal(cards, zone, owner, messagePrefix, addMsgSuffix); }
    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix, boolean addMsgSuffix) { count("reveal"); observeRevealViews(cards, zone, owner); super.reveal(cards, zone, owner, messagePrefix, addMsgSuffix); }
    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject realtedTarget, String value) { count("notifyOfValue"); super.notifyOfValue(saSource, realtedTarget, value); }
    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) { count("arrangeForSurveil"); observeLook(topN, ZoneType.Library); return bridgedArrangeForSurveil(topN); }
    @Override
    public boolean willPutCardOnTop(Card c) { count("willPutCardOnTop"); return bridgedWillPutCardOnTop(c); }
    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) { count("orderMoveToZoneList"); observeLook(cards, destinationZone); return bridgedOrderMoveToZoneList(cards, destinationZone, source); }
    @Override
    public CardCollection chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) { count("chooseCardsToDiscardFrom"); return bridgedDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser); }
    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand, String[] unlessTypes, SpellAbility sa) { count("chooseCardsToDiscardUnlessType"); return super.chooseCardsToDiscardUnlessType(min, hand, unlessTypes, sa); }
    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) { count("chooseCardsToDelve"); return super.chooseCardsToDelve(genericAmount, grave); }
    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) { count("chooseCardsForConvokeOrImprovise"); return super.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction); }
    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) { count("chooseCardsForSplice"); return super.chooseCardsForSplice(sa, cards); }
    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) { count("chooseCardsToRevealFromHand"); return super.chooseCardsToRevealFromHand(min, max, valid); }
    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) { count("chooseSaToActivateFromOpeningHand"); return super.chooseSaToActivateFromOpeningHand(usableFromOpeningHand); }
    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) { count("chooseStartingHand"); return super.chooseStartingHand(zones); }
    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) { count("chooseManaFromPool"); return super.chooseManaFromPool(manaChoices); }
    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) { count("chooseSomeType"); return super.chooseSomeType(kindOfType, sa, validTypes, isOptional); }
    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) { count("chooseSector"); return super.chooseSector(assignee, ai, sectors); }
    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) { count("chooseContraptionsToCrank"); return super.chooseContraptionsToCrank(contraptions); }
    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) { count("chooseSprocket"); return super.chooseSprocket(assignee, sprockets); }
    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) { count("choosePDRollToIgnore"); return super.choosePDRollToIgnore(rolls); }
    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) { count("chooseRollToIgnore"); return super.chooseRollToIgnore(rolls); }
    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) { count("chooseDiceToReroll"); return super.chooseDiceToReroll(rolls); }
    @Override
    public Integer chooseRollToModify(List<Integer> rolls) { count("chooseRollToModify"); return super.chooseRollToModify(rolls); }
    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) { count("chooseRollToSwap"); return super.chooseRollToSwap(rolls); }
    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness) { count("chooseRollSwapValue"); return super.chooseRollSwapValue(swapChoices, currentResult, power, toughness); }
    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) { count("vote"); return super.vote(sa, prompt, options, votes, forPlayer, optional); }
    @Override
    public int chooseNumberForCostReduction(final SpellAbility sa, final int min, final int max) { count("chooseNumberForCostReduction"); return super.chooseNumberForCostReduction(sa, min, max); }
    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) { count("chooseFlipResult"); return super.chooseFlipResult(sa, flipper, call); }
    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) { count("chooseColor"); return bridgedChooseColor(message, sa, colors); }
    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) { count("chooseColorAllowColorless"); return super.chooseColorAllowColorless(message, c, colors); }
    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) { count("chooseColors"); return bridgedChooseColors(message, sa, min, max, options); }
    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) { count("chooseSingleCardFace"); return super.chooseSingleCardFace(sa, message, cpp, name); }
    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) { count("chooseSingleCardFace"); return askOne("chooseSingleCardFace", sa, faces, () -> super.chooseSingleCardFace(sa, faces, message)); }
    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) { count("chooseSingleCardState"); return super.chooseSingleCardState(sa, states, message, params); }
    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) { count("chooseCardsPile"); return bridgedChooseCardsPile(sa, pile1, pile2, faceUp); }
    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) { count("chooseCounterType"); return super.chooseCounterType(options, sa, prompt, params); }
    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) { count("chooseKeywordForPump"); return askOne("chooseKeywordForPump", sa, options, () -> super.chooseKeywordForPump(options, sa, prompt, tgtCard)); }
    @Override
    public boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) { count("confirmPayment"); return super.confirmPayment(costPart, string, sa); }
    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) { count("chooseSingleReplacementEffect"); return super.chooseSingleReplacementEffect(possibleReplacers); }
    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers) { count("chooseSingleStaticAbility"); return super.chooseSingleStaticAbility(possibleReplacers); }
    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) { count("chooseProtectionType"); return askOne("chooseProtectionType", sa, choices, () -> super.chooseProtectionType(sa, choices)); }
    @Override
    public void revealAnte(String message, Multimap<Player, PaperCard> removedAnteCards) { count("revealAnte"); super.revealAnte(message, removedAnteCards); }
    @Override
    public void revealAISkipCards(String message, Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) { count("revealAISkipCards"); super.revealAISkipCards(message, deckCards); }
    @Override
    public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) { count("revealUnsupported"); super.revealUnsupported(unsupported); }
    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) { count("orderCosts"); return super.orderCosts(costs); }
    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) { count("payCostToPreventEffect"); return bridgedPayToPrevent(cost, sa, alreadyPaid, allPayers); }
    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) { count("payCostDuringRoll"); return super.payCostDuringRoll(cost, sa); }
    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) { count("payCombatCost"); return super.payCombatCost(card, cost, sa, prompt); }
    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect) { count("payManaCost"); return super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect); }
    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt, ManaConversionMatrix matrix, boolean effect) { count("applyManaToCost"); return super.applyManaToCost(toPay, ability, prompt, matrix, effect); }
    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt) { count("chooseCardsForCost"); return askCostCards(optionList, sa, cpl, amount, isOptional, prompt, () -> super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt)); }
    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability, boolean effect, String prompt) { count("getCostDecisionMaker"); return super.getCostDecisionMaker(player, ability, effect, prompt); }
    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) { count("chooseCardName"); return bridgedChooseCardName(sa, cpp, null, () -> super.chooseCardName(sa, cpp, valid, message)); }
    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) { count("chooseCardName"); return bridgedChooseCardName(sa, null, faces, () -> super.chooseCardName(sa, faces, message)); }
    /*
     * ---------------------------------------------------------------------
     * THE ZONE-CHANGE ASKS — v2.17, and until this they were COUNTED AND
     * HANDED TO FORGE'S OWN AI.
     * ---------------------------------------------------------------------
     * Both methods below used to read `count(); return super....`. The counter
     * is in every manifest the campaign has ever written and it says
     * `chooseSingleCardForZoneChange: 1072` per 384 games — a thousand library
     * searches a bench, every one of them decided by `brains
     * .chooseCardToHiddenOriginChangeZone` while the host's pilot watched. See
     * `docs/qa/losing-lines/recon/archetype-bench-0825.md` §5.4 (mtgx): our
     * pilot cast Doomsday 135 times on that corpus and never once chose the
     * five, because Doomsday is `ChangeZone | Origin$ Graveyard,Library |
     * ChangeNum$ 5` and every one of its picks came through here.
     *
     * THE SHAPE IS `chooseCardsForEffect`'S — a `bridged()` guard, one
     * `askForCards`-style round trip, and `null` meaning *delegate*, so the
     * fallback is byte-identical to the old behaviour on every path the host
     * cannot answer. Three things are new and all three are on the wire rather
     * than inferred by the host:
     *
     *  · `destination` / `origin` — a fetch to HAND, to the BATTLEFIELD, to the
     *    GRAVEYARD and to the LIBRARY are four different decisions and the
     *    printed prompt does not reliably say which.
     *  · `changeNum` / `chosen` — `ChangeZoneEffect` only takes its multi-select
     *    branch when `!decider.getController().isAI()`, and this controller
     *    extends `PlayerControllerAi`, so a five-card pile arrives here as FIVE
     *    SEQUENTIAL SINGLE-CARD ASKS off a shrinking `fetchList`. A host that
     *    cannot tell pick 1 from pick 4 re-derives a different pile at every
     *    one of them. `chosen` is counted here, against `sa` identity, because
     *    the effect's loop is the only place the index exists and it is not a
     *    parameter.
     *  · `optional` — `isOptional` is `!mandatory` at the call site and it is
     *    what makes `min` 0 rather than 1.
     *
     * `delayedReveal` is honoured on the bridged path exactly as `super` does
     * (`PlayerControllerAi` calls `reveal(delayedReveal)` before deciding), so
     * the AI card memory is written whichever side answers.
     */
    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional,
            Player decider) {
        count("chooseSingleCardForZoneChange");
        if (localAnswers() && (decider != getPlayer() || fetchList == null || fetchList.isEmpty())) {
            note(decider != getPlayer() ? "zoneChange.otherDecider" : "zoneChange.empty"); // RL census, rl-r0-b4-1006
        }
        if (!bridged() || decider != getPlayer() || fetchList == null || fetchList.isEmpty()) {
            return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal,
                    selectPrompt, isOptional, decider);
        }
        final int changeNum = zoneChangeNum(sa);
        final int chosen = zoneChangeProgress(sa, changeNum);
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        final CardCollection picked = askForZoneChange("chooseSingleCardForZoneChange", destination, origin,
                sa, fetchList, isOptional ? 0 : 1, 1, selectPrompt, changeNum, chosen, true);
        if (picked == null) {
            final Echo e = takeEcho();
            final Card out = super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList,
                    null, selectPrompt, isOptional, decider);
            // Single-card ask, list-shaped answer: the host answers `choices` here even
            // when `max` is 1, and declining is the empty list rather than a `none`.
            echo(e, echoCards(out == null ? Collections.<Card>emptyList()
                    : Collections.singletonList(out)));
            return out;
        }
        if (picked.isEmpty()) {
            return null; // a legal answer when `isOptional`; `min` refused it otherwise
        }
        zoneChangeChosen++;
        return picked.get(0);
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt,
            Player decider) {
        count("chooseCardsForZoneChange");
        /*
         * UNORDERED, and unlike its sibling it has NEVER been called on this
         * bench: `ChangeZoneEffect.allowMultiSelect` requires
         * `!decider.getController().isAI()` and this controller is an AI one,
         * so the counter reads 0 in 384 games. `PlayerControllerAi`'s own body
         * is `return null` under the comment "this isn't used". It is bridged
         * anyway — the caller loops `while (selectedCards != null &&
         * selectedCards.size() > changeNum)`, so an over-long answer is an
         * infinite loop rather than a refusal, and `askForZoneChange` enforces
         * the ceiling before it can happen.
         */
        if (!bridged() || decider != getPlayer() || fetchList == null || fetchList.isEmpty()) {
            return super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max,
                    delayedReveal, selectPrompt, decider);
        }
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        final int lo = Math.max(0, min);
        final int hi = Math.max(lo, max);
        final CardCollection picked = askForZoneChange("chooseCardsForZoneChange", destination, origin, sa,
                fetchList, lo, hi, selectPrompt, hi, 0, false);
        if (picked == null) {
            final Echo e = takeEcho();
            final List<Card> out = super.chooseCardsForZoneChange(destination, origin, sa, fetchList,
                    min, max, null, selectPrompt, decider);
            // `PlayerControllerAi`'s own body is `return null` under the comment "this
            // isn't used", so an empty `choices` here is the honest echo of a method
            // that decides nothing rather than a lost row.
            echo(e, echoCards(out));
            return out;
        }
        return picked;
    }

    /**
     * `ChangeNum`, evaluated — how many cards this whole resolution will take.
     *
     * The host needs it to size a pile, and it is not a parameter of either
     * method: the effect reads it once and then loops. `1` where the ability
     * does not print one, and where evaluating it throws (a `Count$` SVar that
     * needs a context this call does not have).
     */
    private int zoneChangeNum(final SpellAbility sa) {
        if (sa == null || !sa.hasParam("ChangeNum")) {
            return 1;
        }
        try {
            return Math.max(1, AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("ChangeNum"), sa));
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /**
     * How many cards this resolution has already taken, by `sa` IDENTITY.
     *
     * `ChangeZoneEffect`'s one-at-a-time loop calls this method `changeNum`
     * times with the same `SpellAbility` object and a `fetchList` one card
     * shorter each time. Nothing else can interleave — the loop is synchronous
     * inside one resolution — so object identity plus a bound at `changeNum` is
     * the whole state. A second resolution of the same object (a copied spell)
     * re-enters at 0 because the previous run reached its bound.
     */
    private int zoneChangeProgress(final SpellAbility sa, final int changeNum) {
        if (sa != zoneChangeRun || zoneChangeChosen >= changeNum) {
            zoneChangeRun = sa;
            zoneChangeChosen = 0;
        }
        return zoneChangeChosen;
    }

    /** Shared {@code zoneChange} round trip. Returns null to mean "delegate". */
    private CardCollection askForZoneChange(final String method, final ZoneType destination,
            final List<ZoneType> origin, final SpellAbility sa, final CardCollection fetchList,
            final int min, final int max, final String title, final int changeNum, final int chosen,
            final boolean single) {
        final JsonObject body = envelope(true);
        body.addProperty("title", String.valueOf(title));
        body.addProperty("min", min);
        body.addProperty("max", max);
        body.addProperty("destination", destination == null ? "" : destination.name());
        final JsonArray zones = new JsonArray();
        if (origin != null) {
            for (ZoneType z : origin) {
                if (z != null) {
                    zones.add(z.name());
                }
            }
        }
        body.add("origin", zones);
        body.addProperty("changeNum", changeNum);
        body.addProperty("chosen", chosen);
        body.addProperty("optional", min == 0);
        body.addProperty("single", single);
        body.add("menu", StateEncoder.encodeCards(fetchList));
        if (sa != null) {
            body.add("ability", StateEncoder.encodeSpellAbility(sa));
        }
        final JsonObject ans = ask(method, "zoneChange", body, fetchList);
        if (ans == null) {
            return null;
        }
        final List<Integer> ids = optIntList(ans, "choices");
        if (ids == null) {
            refuse(method, "missing/!array 'choices'");
            return null;
        }
        if (ids.size() < min || ids.size() > max) {
            refuse(method, "chose " + ids.size() + " outside [" + min + "," + max + "]");
            return null;
        }
        final CardCollection picked = new CardCollection();
        for (int fid : ids) {
            final Card c = findCard(fetchList, fid);
            if (c == null || picked.contains(c)) {
                refuse(method, "unknown/duplicate card id " + fid);
                return null;
            }
            picked.add(c);
        }
        return picked;
    }

    /*
     * ---------------------------------------------------------------------
     * THE ORDER HALF — v2.18, AND IT IS THE SAME BUG ONE METHOD ALONG.
     * ---------------------------------------------------------------------
     * v2.17 closed `chooseSingleCardForZoneChange` and left this one open, and
     * the campaign's own measurement named the consequence exactly: over 288
     * games `orderMoveToZoneList` was called **495 times** and every one of them
     * was `count(); return super....`.
     *
     * It matters for one card in particular. Doomsday is
     *
     *     A:SP$ ChangeZone | ... | ChangeNum$ 5 | SubAbility$ DBChangeZone
     *     SVar:DBChangeZone: ... | SubAbility$ DBDig
     *     SVar:DBDig: DB$ RearrangeTopOfLibrary | Defined$ You | NumCards$ X
     *
     * so the five chosen cards are put on top **in any order** and then RE-ORDERED
     * through `RearrangeTopOfLibraryEffect`, which is to say through this method.
     * After v2.17 the host chose WHICH five and `PlayerControllerAi`'s scry
     * heuristic still chose in WHAT ORDER — and the order is the whole plan: a
     * five-card library whose finisher is under two cantrips it cannot pay for is
     * a loss, and the host's own `MTGX_PILEREACH` exists to put it at the
     * shallowest depth the mana reaches.
     *
     * THE ANSWER IS A PERMUTATION IN MOVE ORDER, AND THE BRIDGE TRANSFORMS
     * NOTHING. `orderMoveToZoneList`'s contract is *the order in which the cards
     * will be moved, one at a time, to the destination* — and its own javadoc
     * warns that "when moving cards to the top of a deck, this will be the
     * reverse of the order they will ultimately end up in", because
     * `RearrangeTopOfLibraryEffect` walks the returned list calling
     * `moveToLibrary(next, 0)`. Both of Forge's own controllers handle that by
     * building a top-first list and reversing at `orderedMoveToTopOfLibrary`.
     * This bridge publishes that predicate as `topFirst` and reverses NOTHING, so
     * there is exactly one place in the system where the flip happens and it is
     * the host's, where the pile is. A bridge that also reversed would be
     * indistinguishable from this one in the wire log and wrong in the game.
     *
     * FAIL CLOSED. A non-permutation answer — the wrong arity, a duplicate, an
     * fid that is not on the menu — is REFUSED and the call falls through to
     * `super`, byte-identically to the pre-2.18 behaviour. So is a null answer,
     * an unbridged session, a list of one, and a decider that is not our seat.
     */
    private CardCollectionView bridgedOrderMoveToZoneList(final CardCollectionView cards,
            final ZoneType destinationZone, final SpellAbility source) {
        if (!bridged() || cards == null || cards.size() < 2) {
            return super.orderMoveToZoneList(cards, destinationZone, source);
        }
        final JsonObject body = envelope(true);
        body.addProperty("destination", destinationZone == null ? "" : destinationZone.name());
        body.addProperty("count", cards.size());
        /*
         * `PlayerController.orderedMoveToTopOfLibrary` — a deck destination whose
         * `LibraryPosition`/`RevealedLibraryPosition` is non-negative (or absent).
         * True means the list the host sends back is walked onto the TOP one card
         * at a time, so its LAST entry is the one drawn first.
         */
        body.addProperty("topFirst", destinationZone != null
                && orderedMoveToTopOfLibrary(destinationZone, source));
        body.add("menu", StateEncoder.encodeCards(cards));
        if (source != null) {
            body.add("ability", StateEncoder.encodeSpellAbility(source));
        }
        final JsonObject ans = ask("orderMoveToZoneList", "orderZone", body,
                new Object[] {cards, destinationZone, body.get("topFirst").getAsBoolean(), source});
        if (ans == null) {
            final Echo e = takeEcho();
            final CardCollectionView out = super.orderMoveToZoneList(cards, destinationZone, source);
            // MOVE ORDER, untransformed, exactly as an answer would be. The `topFirst`
            // flip is the host's and stays the host's; echoing a reversed list would put
            // the flip in two places and make one of them wrong.
            echo(e, echoCards(out));
            return out;
        }
        final List<Integer> ids = optIntList(ans, "choices");
        if (ids == null) {
            refuse("orderMoveToZoneList", "missing/!array 'choices'");
            return super.orderMoveToZoneList(cards, destinationZone, source);
        }
        if (ids.size() != cards.size()) {
            refuse("orderMoveToZoneList", "ordered " + ids.size() + " of " + cards.size());
            return super.orderMoveToZoneList(cards, destinationZone, source);
        }
        final CardCollection ordered = new CardCollection();
        for (int fid : ids) {
            Card found = null;
            for (Card c : cards) {
                if (c != null && c.getId() == fid) {
                    found = c;
                    break;
                }
            }
            if (found == null || ordered.contains(found)) {
                refuse("orderMoveToZoneList", "unknown/duplicate card id " + fid);
                return super.orderMoveToZoneList(cards, destinationZone, source);
            }
            ordered.add(found);
        }
        return ordered;
    }

    // ============================================================================ RL seat, Phase B (rl-r0-b4-1006)
    /*
     * THE PHASE B ASKS (lane rl-r0-b4-1006; GOAL rev 5 R0, interfaces.md Appendix B.1). Every method below is posed
     * ONLY to an in-process answerer (BenchSession.LocalAnswerer: the RL seat, playing or recording). With no local
     * answerer, or an unbridged seat, each one is exactly its old one-line delegation to PlayerControllerAi, so the
     * stdio host never sees the new kinds and the default path is unchanged. The house shape throughout: a light body,
     * the Forge objects behind the menu passed in-process, null = delegate (Forge decides; its answer is echoed in the
     * ask's own answer shape, for record mode), and every answer validated before it is applied; a refusal falls back
     * to Forge's own decision and is counted.
     *
     * Three things are not asks but make the RL seat answer what was Forge's before:
     *  - COST CARDS. Forge's AI never calls chooseCardsForCost: it pays sacrifice / discard / exile / return costs
     *    through `new AiCostDecision(...)`. AiCostDecision.forPayment (the four real-payment sites) asks this
     *    controller first (costDecisionForPayment), and the seat's decision maker poses those card choices as the
     *    `costCards` ask, falling back to Forge's own visit.
     *  - TRIGGER TARGETS. PlayerControllerAi puts a triggered ability on the stack through prepareSingleSa ->
     *    doTrigger, which sets its targets without chooseTargetsFor. For a seat whose answers are played, Forge still
     *    prepares the trigger (X, AI parameters, charm modes) and the seat then chooses the targets through the
     *    ordinary `targets` ask (orchestrator ruling 10-05, ICR B4-families-coordination §4). The same holds for the
     *    targets of a spell cast from an effect (playSaFromPlayEffect) and of an effect played without the stack.
     *  - CHARM SPELLS. A modal spell the seat casts gets its modes at handlePlayingSpellAbility (CharmEffect), after
     *    the bridge's ensureTargets ran; the chosen modes' targets are now asked there too.
     */

    /** An in-process answerer (the RL seat, playing or recording) answers this seat. */
    private boolean localAnswers() {
        return bridged() && session.getLocalAnswerer() != null;
    }

    /** ...and its answers are played (an RL seat), rather than only observed (record mode). */
    private boolean seatPlays() {
        return localAnswers() && !session.getLocalAnswerer().observeOnly();
    }

    /** A census note for the local answerer: counts only, never a decision. */
    private void note(final String key) {
        final BenchSession.LocalAnswerer l = session.getLocalAnswerer();
        if (l == null || !isLiveGame()) {
            return;
        }
        try {
            l.note(getGame(), getPlayer(), key);
        } catch (RuntimeException e) {
            counters.instrument("note.failed");
        }
    }

    /** Diagnosis switch (-Dbridge.forgeTriggerTargets=true): leave a seat's trigger targets to Forge, as before this
     *  lane, for the before/after census. Off by default. */
    static final boolean FORGE_TRIGGER_TARGETS = Boolean.getBoolean("bridge.forgeTriggerTargets");

    /** Where the targeting being asked comes from (`targets` ask body "origin"): cast, trigger, playFromEffect, noStack. */
    private String targetingOrigin = "cast";

    /** True while orderAndPlay re-enters super for one copied spell: its singleton orderSimultaneousSa is not a call. */
    private boolean quietOrder = false;

    /** > 0 while Forge's AI runs a heuristic that probes this controller (never asked: forgeTuck, NAME's fallback). */
    private int probing = 0;

    /** {@code {"choices":[fid…]}} → cards of {@code pool}, count in [min, max], no repeats; null (refused) otherwise. */
    private CardCollection pickCards(final String method, final JsonObject ans, final Iterable<Card> pool,
            final int min, final int max) {
        final List<Integer> ids = optIntList(ans, "choices");
        if (ids == null) {
            refuse(method, "missing/!array 'choices'");
            return null;
        }
        if (ids.size() < min || ids.size() > max) {
            refuse(method, "chose " + ids.size() + " outside [" + min + "," + max + "]");
            return null;
        }
        final CardCollection picked = new CardCollection();
        for (int fid : ids) {
            final Card c = findCard(pool, fid);
            if (c == null || picked.contains(c)) {
                refuse(method, "unknown/duplicate card id " + fid);
                return null;
            }
            picked.add(c);
        }
        return picked;
    }

    /** {@code {top:[fid…], <second>:[fid…]}} partitioning {@code cards}; null (refused) otherwise. */
    private ImmutablePair<CardCollection, CardCollection> partition(final String method, final JsonObject ans,
            final CardCollection cards, final String second) {
        final List<Integer> top = optIntList(ans, "top");
        final List<Integer> rest = optIntList(ans, second);
        if (top == null || rest == null || top.size() + rest.size() != cards.size()) {
            refuse(method, "top+" + second + " must partition the " + cards.size() + " cards");
            return null;
        }
        final CardCollection a = new CardCollection();
        final CardCollection b = new CardCollection();
        for (int fid : top) {
            final Card c = findCard(cards, fid);
            if (c == null || a.contains(c)) {
                refuse(method, "unknown/duplicate top card " + fid);
                return null;
            }
            a.add(c);
        }
        for (int fid : rest) {
            final Card c = findCard(cards, fid);
            if (c == null || a.contains(c) || b.contains(c)) {
                refuse(method, "unknown/duplicate " + second + " card " + fid);
                return null;
            }
            b.add(c);
        }
        return ImmutablePair.of(a, b);
    }

    // ---- 16 DISCARD_FROM
    private CardCollection bridgedDiscardFrom(final Player playerDiscard, final SpellAbility sa,
            final CardCollection validCards, final int min, final int max, final CardCollectionView visibleToChooser) {
        if (!localAnswers() || validCards == null || validCards.isEmpty()) {
            return super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser);
        }
        final JsonObject body = envelope(true);
        body.addProperty("min", min);
        body.addProperty("max", max);
        body.addProperty("ownHand", playerDiscard == getPlayer());
        final JsonObject ans = ask("chooseCardsToDiscardFrom", "discardFrom", body,
                new Object[] {validCards, sa, playerDiscard});
        if (ans == null) {
            final Echo e = takeEcho();
            final CardCollection out = super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max,
                    visibleToChooser);
            echo(e, echoCards(out));
            return out;
        }
        final CardCollection picked = pickCards("chooseCardsToDiscardFrom", ans, validCards, min, max);
        return picked != null ? picked
                : super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser);
    }

    // ---- 17 COST_CARDS
    /** The {@code costCards} round trip; {@code forge} is Forge's own choice (delegate and refusal). */
    private CardCollectionView askCostCards(final CardCollectionView pool, final SpellAbility sa,
            final CostPartWithList cpl, final int amount, final boolean optional, final String prompt,
            final java.util.function.Supplier<CardCollectionView> forge) {
        if (!localAnswers() || pool == null || pool.isEmpty() || amount <= 0) {
            return forge.get();
        }
        final int min = optional ? 0 : amount;
        final JsonObject body = envelope(true);
        body.addProperty("min", min);
        body.addProperty("max", amount);
        body.addProperty("cost", cpl == null ? "" : cpl.getClass().getSimpleName());
        final JsonObject ans = ask("chooseCardsForCost", "costCards", body, new Object[] {pool, sa, cpl});
        if (ans == null) {
            final Echo e = takeEcho();
            final CardCollectionView out = forge.get();
            echo(e, echoCards(out));
            return out;
        }
        final CardCollection picked = pickCards("chooseCardsForCost", ans, pool, min, amount);
        return picked != null ? picked : forge.get();
    }

    /** AiCostDecision.PaymentDecisions: the seat's decision maker for a real payment, or null (Forge's own). */
    @Override
    public AiCostDecision costDecisionForPayment(final Player payer, final SpellAbility sa, final boolean effect) {
        if (!localAnswers() || payer != getPlayer() || sa == null) {
            return null;
        }
        return new SeatCostDecision(payer, sa, effect);
    }

    /** "+withTotalCMCGE", "+WithDifferentNames", … : the shapes Forge pays by its own special rules. */
    private static boolean specialType(final String t) {
        return t == null || t.contains("+with") || t.contains("+With");
    }

    /**
     * Forge's AI cost decisions with the card choices of typed discard / exile / sacrifice / return costs posed to the
     * seat ({@code costCards}). Every other part, and every special shape, is Forge's own visit.
     */
    private final class SeatCostDecision extends AiCostDecision {
        SeatCostDecision(final Player p, final SpellAbility sa, final boolean effect) {
            super(p, sa, effect);
        }

        private PaymentDecision asked(final CostPartWithList cost, final CardCollectionView valid, final int c,
                final java.util.function.Supplier<PaymentDecision> forge) {
            if (c <= 0 || valid == null || valid.size() < c) {
                return forge.get();
            }
            count("chooseCardsForCost");
            final PaymentDecision[] fd = {null};
            final boolean[] forgeDecided = {false};
            final CardCollectionView pick = askCostCards(valid, ability, cost, c, false, null, () -> {
                forgeDecided[0] = true;
                fd[0] = forge.get();
                return fd[0] == null ? null : fd[0].cards;
            });
            if (forgeDecided[0]) {
                return fd[0];
            }
            return pick == null ? forge.get() : PaymentDecision.card(pick);
        }

        @Override
        public PaymentDecision visit(final CostDiscard cost) {
            final String t = cost.getType();
            if (cost.payCostFromSource() || "Hand".equals(t) || "LastDrawn".equals(t) || "Random".equals(t)
                    || specialType(t)) {
                return super.visit(cost);
            }
            final CardCollectionView valid = CardLists.getValidCards(player.getCardsIn(ZoneType.Hand), t.split(";"),
                    player, source, ability);
            return asked(cost, valid, cost.getAbilityAmount(ability), () -> super.visit(cost));
        }

        @Override
        public PaymentDecision visit(final CostExile cost) {
            final String t = cost.getType();
            if (cost.payCostFromSource() || "All".equals(t) || "OriginalHost".equals(t) || t.contains("FromTopGrave")
                    || specialType(t) || cost.zoneRestriction == 0
                    || (cost.getFrom().size() == 1 && cost.getFrom().get(0) == ZoneType.Library)) {
                return super.visit(cost);
            }
            CardCollectionView list = cost.zoneRestriction != 1 ? player.getGame().getCardsIn(cost.getFrom())
                    : player.getCardsIn(cost.getFrom());
            list = CardLists.getValidCards(list, t.split(";"), player, source, ability);
            list = CardLists.filter(list, CardPredicates.canExiledBy(ability, isEffect()));
            return asked(cost, list, cost.getAbilityAmount(ability), () -> super.visit(cost));
        }

        @Override
        public PaymentDecision visit(final CostSacrifice cost) {
            final String t = cost.getType();
            if (cost.payCostFromSource() || "OriginalHost".equals(t) || "All".equals(cost.getAmount())
                    || specialType(t)) {
                return super.visit(cost);
            }
            CardCollectionView list = CardLists.filter(player.getCardsIn(ZoneType.Battlefield),
                    CardPredicates.canBeSacrificedBy(ability, isEffect()));
            list = CardLists.getValidCards(list, t.split(";"), player, source, ability);
            return asked(cost, list, cost.getAbilityAmount(ability), () -> super.visit(cost));
        }

        @Override
        public PaymentDecision visit(final CostReturn cost) {
            final String t = cost.getType();
            if (cost.payCostFromSource() || specialType(t) || ability.getActivatingPlayer() == null) {
                return super.visit(cost);
            }
            final CardCollectionView list = CardLists.getValidCards(
                    ability.getActivatingPlayer().getCardsIn(ZoneType.Battlefield), t.split(";"), player, source,
                    ability);
            return asked(cost, list, cost.getAbilityAmount(ability), () -> super.visit(cost));
        }
    }

    // ---- 19 SURVEIL
    private ImmutablePair<CardCollection, CardCollection> bridgedArrangeForSurveil(final CardCollection topN) {
        if (!localAnswers() || topN == null || topN.isEmpty()) {
            return super.arrangeForSurveil(topN);
        }
        final JsonObject ans = ask("arrangeForSurveil", "surveil", envelope(true), topN);
        if (ans == null) {
            final Echo e = takeEcho();
            final ImmutablePair<CardCollection, CardCollection> out = super.arrangeForSurveil(topN);
            final JsonObject a = new JsonObject();
            a.add("top", fidArray(out == null ? null : out.getLeft()));
            a.add("graveyard", fidArray(out == null ? null : out.getRight()));
            echo(e, a);
            return out;
        }
        final ImmutablePair<CardCollection, CardCollection> split = partition("arrangeForSurveil", ans, topN,
                "graveyard");
        return split != null ? split : super.arrangeForSurveil(topN);
    }

    // ---- 20 PUT_ON_TOP
    private boolean bridgedWillPutCardOnTop(final Card c) {
        if (!localAnswers() || c == null) {
            return super.willPutCardOnTop(c);
        }
        if (probing > 0) {
            note("probe.willPutCardOnTop");
            return super.willPutCardOnTop(c);
        }
        final JsonObject ans = ask("willPutCardOnTop", "putOnTop", envelope(true), c);
        if (ans == null) {
            final Echo e = takeEcho();
            final boolean out = super.willPutCardOnTop(c);
            echo(e, echoBool("yes", out));
            return out;
        }
        final Boolean yes = optBool(ans, "yes");
        if (yes == null) {
            refuse("willPutCardOnTop", "expected boolean 'yes'");
            return super.willPutCardOnTop(c);
        }
        return yes;
    }

    // ---- 18 PILE
    private boolean bridgedChooseCardsPile(final SpellAbility sa, final CardCollectionView pile1,
            final CardCollectionView pile2, final String faceUp) {
        if (!localAnswers() || pile1 == null || pile2 == null) {
            return super.chooseCardsPile(sa, pile1, pile2, faceUp);
        }
        final JsonObject body = envelope(true);
        body.addProperty("faceUp", String.valueOf(faceUp));
        final JsonObject ans = ask("chooseCardsPile", "pile", body, new Object[] {sa, pile1, pile2, faceUp});
        if (ans == null) {
            final Echo e = takeEcho();
            final boolean out = super.chooseCardsPile(sa, pile1, pile2, faceUp);
            echo(e, echoInt("pile", out ? 0 : 1));
            return out;
        }
        final Integer pile = optInt(ans, "pile");
        if (pile == null || pile < 0 || pile > 1) {
            refuse("chooseCardsPile", "expected 'pile' 0 or 1");
            return super.chooseCardsPile(sa, pile1, pile2, faceUp);
        }
        return pile == 0;
    }

    // ---- 21 OPTIONAL_TRIGGER
    private boolean bridgedConfirmTrigger(final WrappedAbility wrapper) {
        if (!localAnswers() || wrapper == null) {
            return super.confirmTrigger(wrapper);
        }
        final boolean mandatory = wrapper.isMandatory();
        final JsonObject body = envelope(true);
        body.addProperty("mandatory", mandatory);
        final JsonObject ans = ask("confirmTrigger", "optionalTrigger", body, new Object[] {wrapper, mandatory});
        if (ans == null) {
            final Echo e = takeEcho();
            final boolean out = super.confirmTrigger(wrapper);
            echo(e, echoBool("yes", out));
            return out;
        }
        final Boolean yes = optBool(ans, "yes");
        if (yes == null || (mandatory && !yes)) {
            refuse("confirmTrigger", "expected boolean 'yes' (true for a mandatory trigger)");
            return super.confirmTrigger(wrapper);
        }
        return yes;
    }

    // ---- 22 PAY_TO_PREVENT
    private boolean bridgedPayToPrevent(final Cost cost, final SpellAbility sa, final boolean alreadyPaid,
            final FCollectionView<Player> allPayers) {
        if (!localAnswers() || cost == null || sa == null) {
            return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
        }
        // Payability only for a seat whose answers are played: canPayCost draws from the game's random stream (the
        // record-mode do-no-harm residual), so a recorder offers YES unchecked.
        boolean payable = true;
        if (seatPlays()) {
            try {
                payable = ComputerUtilCost.canPayCost(cost, sa, getPlayer(), true);
            } catch (RuntimeException e) {
                payable = false;
            }
        }
        int generic = 0;
        try {
            generic = cost.hasNoManaCost() ? 0 : cost.getTotalMana().getCMC();
        } catch (RuntimeException e) {
            generic = 0;
        }
        final JsonObject body = envelope(true);
        body.addProperty("payable", payable);
        body.addProperty("alreadyPaid", alreadyPaid);
        body.addProperty("mana", generic);
        final JsonObject ans = ask("payCostToPreventEffect", "payToPrevent", body,
                new Object[] {cost, sa, payable, generic});
        if (ans == null) {
            final Echo e = takeEcho();
            final boolean out = super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
            echo(e, echoBool("yes", out));
            return out;
        }
        final Boolean yes = optBool(ans, "yes");
        if (yes == null || (yes && !payable)) {
            refuse("payCostToPreventEffect", "expected boolean 'yes' (and a payable cost for yes)");
            return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
        }
        if (!yes) {
            return false;
        }
        // as PlayerControllerAi pays it, with the seat's card choices for any card-shaped part
        final boolean paid = new CostPayment(cost, sa).payComputerCosts(AiCostDecision.forPayment(getPlayer(), sa, true));
        if (!paid) {
            note("payToPrevent.payFailed");
        }
        return paid;
    }

    // ---- 23 NAME
    private String bridgedChooseCardName(final SpellAbility sa, final Predicate<ICardFace> cpp,
            final List<ICardFace> faces, final java.util.function.Supplier<String> forge) {
        if (!localAnswers()) {
            return forge.get();
        }
        if (probing > 0) {
            note("probe.chooseCardName");
            return forge.get();
        }
        final JsonObject ans = ask("chooseCardName", "name", envelope(true), new Object[] {sa, cpp, faces});
        if (ans == null) {
            final Echo e = takeEcho();
            final String out = forgeName(forge);
            final JsonObject a = new JsonObject();
            a.addProperty("name", out == null ? "" : out);
            echo(e, a);
            return out;
        }
        final String name = ans.has("name") && !ans.get("name").isJsonNull() ? ans.get("name").getAsString() : null;
        if (name == null || !nameLegal(name, cpp, faces)) {
            refuse("chooseCardName", "name not legal here: " + name);
            return forgeName(forge);
        }
        return name;
    }

    /** Forge's name (AILogic MakeCard re-enters chooseCardName with faces: a probe, never asked). */
    private String forgeName(final java.util.function.Supplier<String> forge) {
        probing++;
        try {
            return forge.get();
        } finally {
            probing--;
        }
    }

    /** A name the ask allows: one of {@code faces}, or a card face {@code cpp} accepts. */
    public static boolean nameLegal(final String name, final Predicate<ICardFace> cpp, final List<ICardFace> faces) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (faces != null) {
            for (ICardFace f : faces) {
                if (f != null && name.equals(f.getName())) {
                    return true;
                }
            }
            return false;
        }
        try {
            final ICardFace f = forge.StaticData.instance().getCommonCards().getFaceByName(name);
            return f != null && (cpp == null || cpp.test(f));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- 24 COLOR
    /** A colour chosen while a mana ability resolves (Forge's auto-payment): mechanical, never asked. */
    private static boolean manaColour(final SpellAbility sa) {
        if (sa == null) {
            return true;
        }
        try {
            final forge.game.ability.ApiType api = sa.getApi();
            return sa.isManaAbility() || api == forge.game.ability.ApiType.Mana
                    || api == forge.game.ability.ApiType.ManaReflected
                    || api == forge.game.ability.ApiType.ReplaceMana;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** WUBRG index 0..4 of a single-colour mask, or -1. */
    public static int colourIndex(final byte mask) {
        final byte[] m = MagicColor.WUBRG;
        for (int i = 0; i < m.length; i++) {
            if (m[i] == mask) {
                return i;
            }
        }
        return -1;
    }

    private byte bridgedChooseColor(final String message, final SpellAbility sa, final ColorSet colors) {
        if (!localAnswers() || colors == null || colors.isColorless() || manaColour(sa)) {
            if (localAnswers() && manaColour(sa)) {
                note("chooseColor.mana");
            }
            return super.chooseColor(message, sa, colors);
        }
        final JsonObject ans = ask("chooseColor", "color", envelope(true), new Object[] {sa, colors, 1, 1});
        if (ans == null) {
            final Echo e = takeEcho();
            final byte out = super.chooseColor(message, sa, colors);
            echo(e, echoInt("color", colourIndex(out)));
            return out;
        }
        final Integer i = optInt(ans, "color");
        if (i == null || i < 0 || i >= MagicColor.WUBRG.length || (colors.getColor() & MagicColor.WUBRG[i]) == 0) {
            refuse("chooseColor", "colour " + i + " not offered");
            return super.chooseColor(message, sa, colors);
        }
        return MagicColor.WUBRG[i];
    }

    private ColorSet bridgedChooseColors(final String message, final SpellAbility sa, final int min, final int max,
            final ColorSet options) {
        if (!localAnswers() || options == null || options.isColorless() || manaColour(sa)) {
            if (localAnswers() && manaColour(sa)) {
                note("chooseColors.mana");
            }
            return super.chooseColors(message, sa, min, max, options);
        }
        final JsonObject ans = ask("chooseColors", "color", envelope(true), new Object[] {sa, options, min, max});
        if (ans == null) {
            final Echo e = takeEcho();
            final ColorSet out = super.chooseColors(message, sa, min, max, options);
            final JsonObject a = new JsonObject();
            final JsonArray idx = new JsonArray();
            if (out != null) {
                for (int i = 0; i < MagicColor.WUBRG.length; i++) {
                    if ((out.getColor() & MagicColor.WUBRG[i]) != 0) {
                        idx.add(i);
                    }
                }
            }
            a.add("colors", idx);
            echo(e, a);
            return out;
        }
        final List<Integer> idx = optIntList(ans, "colors");
        if (idx == null || idx.size() < min || idx.size() > max) {
            refuse("chooseColors", "bad 'colors' for [" + min + "," + max + "]");
            return super.chooseColors(message, sa, min, max, options);
        }
        byte mask = 0;
        for (int i : idx) {
            if (i < 0 || i >= MagicColor.WUBRG.length || (options.getColor() & MagicColor.WUBRG[i]) == 0
                    || (mask & MagicColor.WUBRG[i]) != 0) {
                refuse("chooseColors", "colour " + i + " not offered or repeated");
                return super.chooseColors(message, sa, min, max, options);
            }
            mask |= MagicColor.WUBRG[i];
        }
        return ColorSet.fromMask(mask);
    }

    // ---- the remaining strategic choices (ICR B4-strategic-leftovers, ruled 10-05 21:41 PT): MODE and CONFIRM
    /**
     * One option of {@code options} (MODE, SUBSET min = max = 1; the answer is {@code {"choices": [i]}}): protection
     * type, pump keyword, a spell for an effect (dungeon rooms, copies), a card face (which dungeon), which ability to
     * cast from an effect. Forge's choice on delegation (echoed as its index) and on refusal.
     */
    private <T> T askOne(final String method, final SpellAbility sa, final List<T> options,
            final java.util.function.Supplier<T> forge) {
        if (!localAnswers() || options == null || options.isEmpty()) {
            return forge.get();
        }
        if (probing > 0) {
            note("probe." + method);
            return forge.get();
        }
        final List<Integer> pick = askOptions(method, sa, options, 1, 1);
        if (pick == null) {
            final Echo e = takeEcho();
            final T out = forgeProbe(forge);
            final JsonObject a = new JsonObject();
            final JsonArray idx = new JsonArray();
            int at = out == null ? -1 : indexOfIdentity(options, out);
            if (at < 0 && out != null) {
                at = options.indexOf(out); // Forge may return an equal String it built itself (protection type)
            }
            if (at >= 0) {
                idx.add(at);
            }
            a.add("choices", idx);
            echo(e, a);
            return out;
        }
        return options.get(pick.get(0));
    }

    /** The {@code mode} ask over arbitrary options; the chosen indices, or null (delegated, or refused). */
    private List<Integer> askOptions(final String method, final SpellAbility sa, final List<?> options, final int min,
            final int max) {
        final JsonObject body = envelope(true);
        body.addProperty("min", min);
        body.addProperty("num", max);
        body.addProperty("max", max);
        final JsonObject ans = ask(method, "mode", body, new Object[] {sa, options, min, max});
        if (ans == null) {
            return null;
        }
        final List<Integer> idx = optIntList(ans, "choices");
        if (idx == null || idx.size() < min || idx.size() > max) {
            refuse(method, "bad 'choices' for [" + min + "," + max + "]");
            return null;
        }
        final Set<Integer> seen = new HashSet<>();
        for (int i : idx) {
            if (i < 0 || i >= options.size() || !seen.add(i)) {
                refuse(method, "option index out of range/repeated: " + i);
                return null;
            }
        }
        return idx;
    }

    private <T> T forgeProbe(final java.util.function.Supplier<T> forge) {
        probing++;
        try {
            return forge.get();
        } finally {
            probing--;
        }
    }

    private List<SpellAbility> bridgedSpellAbilitiesForEffect(final List<SpellAbility> spells, final SpellAbility sa,
            final String title, final int num, final Map<String, Object> params) {
        if (!localAnswers() || spells == null || spells.isEmpty() || num <= 0) {
            return super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params);
        }
        final int n = Math.min(num, spells.size());
        final List<Integer> pick = askOptions("chooseSpellAbilitiesForEffect", sa, spells, n, n);
        if (pick == null) {
            final Echo e = takeEcho();
            // PlayerControllerAi loops on chooseSingleSpellForEffect: a probe there, never asked
            final List<SpellAbility> out = forgeProbe(
                    () -> super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params));
            echo(e, echoIndices(spells, out));
            return out;
        }
        final List<SpellAbility> chosen = new ArrayList<>();
        for (int i : pick) {
            chosen.add(spells.get(i));
        }
        return chosen;
    }

    private boolean bridgedConfirmReplacement(final ReplacementEffect re, final SpellAbility effectSA,
            final GameEntity affected, final String question) {
        if (!localAnswers() || re == null) {
            return super.confirmReplacementEffect(re, effectSA, affected, question);
        }
        Card host = re.getHostCard();
        final JsonObject body = envelope(true);
        body.addProperty("message", String.valueOf(question));
        final JsonObject ans = ask("confirmReplacementEffect", "confirm", body, new Object[] {effectSA, host});
        if (ans == null) {
            final Echo e = takeEcho();
            final boolean out = super.confirmReplacementEffect(re, effectSA, affected, question);
            echo(e, echoBool("yes", out));
            return out;
        }
        final Boolean yes = optBool(ans, "yes");
        if (yes == null) {
            refuse("confirmReplacementEffect", "expected boolean 'yes'");
            return super.confirmReplacementEffect(re, effectSA, affected, question);
        }
        // PlayerControllerAi's own preparation before it decides
        if (host != null && host.hasAlternateState()) {
            host = host.getGame().getCardState(host);
        }
        if (effectSA != null && host != null) {
            effectSA.setActivatingPlayer(host.getController());
        }
        return yes;
    }

    // ---- TARGETS for triggers, effect casts, no-stack effects, charm modes
    /** The ability itself, or the ability a trigger wraps. */
    private static SpellAbility unwrap(final SpellAbility sa) {
        return sa instanceof WrappedAbility ? ((WrappedAbility) sa).getWrappedAbility() : sa;
    }

    /** Whether any ability of the chain targets. */
    private static boolean chainTargets(final SpellAbility sa) {
        for (SpellAbility cur = unwrap(sa); cur != null; cur = cur.getSubAbility()) {
            if (cur.usesTargeting()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Forge has prepared {@code root} (its AI set the targets); the seat now chooses them, one {@code targets} ask per
     * targeting ability in chain order. An ask the seat cannot complete keeps Forge's targets (counted).
     * {@code onlyUntargeted}: leave an ability that already has targets alone (charm modes after CharmEffect).
     */
    private void retargetForSeat(final SpellAbility root, final String origin, final boolean onlyUntargeted) {
        for (SpellAbility cur = unwrap(root); cur != null; cur = cur.getSubAbility()) {
            if (!cur.usesTargeting()) {
                continue;
            }
            note(origin + ".targeting");
            if (onlyUntargeted && !cur.getTargets().isEmpty()) {
                continue;
            }
            final Player tp = cur.getTargetingPlayer();
            if (tp != null && tp != getPlayer()) {
                continue; // another player targets (TargetingPlayer): theirs to choose
            }
            final TargetChoices forge = cur.getTargets();
            clearForSeat(cur);
            cur.setTargetingPlayer(getPlayer());
            final String before = targetingOrigin;
            targetingOrigin = origin;
            boolean ok;
            try {
                ok = chooseTargetsFor(cur) && cur.isTargetNumberValid();
            } catch (RuntimeException e) {
                JsonRpcChannel.logErr("seat retargeting failed for " + cur, e);
                ok = false;
            } finally {
                targetingOrigin = before;
            }
            if (!ok) {
                cur.resetTargets();
                cur.setTargets(forge);
                note(origin + ".keptForge");
            }
        }
    }

    /** Record mode: show the recorder the targets Forge chose for {@code sa}'s chain. */
    private void forgeTargeted(final SpellAbility sa, final String origin) {
        final BenchSession.LocalAnswerer l = session.getLocalAnswerer();
        if (l == null || !isLiveGame()) {
            return;
        }
        try {
            l.onForgeTargeted(getGame(), getPlayer(), origin, unwrap(sa));
        } catch (RuntimeException e) {
            counters.instrument("forgeTargeted.failed");
            JsonRpcChannel.logErr("forge-targeted hook failed", e);
        }
    }

    /** PlayerControllerAi.prepareSingleSa (private there), with the seat choosing the targets afterwards. */
    private boolean prepareForSeat(final SpellAbility sa0, final boolean isMandatory) {
        SpellAbility sa = sa0;
        final Card host = sa.getHostCard();
        if (sa.getApi() == forge.game.ability.ApiType.Charm) {
            if (!forge.game.ability.effects.CharmEffect.makeChoices(sa)) {
                return false;
            }
            if (!sa.hasParam("Random")) {
                retargetForSeat(sa, "trigger", false);
                return true;
            }
            sa = sa.getSubAbility();
        }
        if (sa.hasParam("TargetingPlayer")) {
            final Player tp = AbilityUtils.getDefinedPlayers(host, sa.getParam("TargetingPlayer"), sa).get(0);
            sa.setTargetingPlayer(tp);
            return tp.getController().chooseTargetsFor(sa);
        }
        if (!getAi().doTrigger(sa, isMandatory)) {
            return false;
        }
        retargetForSeat(sa, "trigger", false);
        return true;
    }

    /** Before-census (diagnosis switch on): targeting abilities whose targets Forge chose with a real choice. */
    private void countForgeChoice(final SpellAbility root, final String origin) {
        for (SpellAbility cur = unwrap(root); cur != null; cur = cur.getSubAbility()) {
            if (!cur.usesTargeting()) {
                continue;
            }
            note(origin + ".targeting");
            final int n = candidateCount(cur);
            if (cur.getMaxTargets() > 0 && n > cur.getMinTargets()) {
                note(origin + ".forgeChoice");
            }
        }
    }

    private void bridgedOrderAndPlay(final List<SpellAbility> sas) {
        if (!localAnswers() || sas == null) {
            super.orderAndPlaySimultaneousSa(sas);
            return;
        }
        if (!seatPlays() || FORGE_TRIGGER_TARGETS) {
            super.orderAndPlaySimultaneousSa(sas);
            for (SpellAbility sa : sas) {
                // only the triggers Forge put on the stack (prepareSingleSa drops one it cannot or will not target)
                if (sa != null && sa.isTrigger() && !sa.isCopied() && chainTargets(sa)
                        && getGame().getStack().getInstanceMatchingSpellAbilityID(sa) != null) {
                    if (seatPlays()) {
                        countForgeChoice(sa, "trigger");
                    } else {
                        forgeTargeted(sa, "trigger");
                    }
                }
            }
            return;
        }
        // PlayerControllerAi.orderAndPlaySimultaneousSa with the seat's trigger targets; copies are super's
        for (final SpellAbility sa : orderSimultaneousSa(sas)) {
            if (sa.isTrigger() && !sa.isCopied()) {
                if (prepareForSeat(sa, true)) {
                    forge.ai.ComputerUtil.playStack(sa, getPlayer(), getGame());
                }
            } else {
                quietOrder = true;
                try {
                    super.orderAndPlaySimultaneousSa(Lists.newArrayList(sa));
                } finally {
                    quietOrder = false;
                }
            }
        }
    }

    private boolean bridgedPlayTrigger(final Card host, final WrappedAbility w, final boolean isMandatory) {
        if (!seatPlays() || FORGE_TRIGGER_TARGETS || w == null) {
            return super.playTrigger(host, w, isMandatory);
        }
        if (prepareForSeat(w, isMandatory)) {
            return forge.ai.ComputerUtil.playNoStack(w.getActivatingPlayer(), w, getGame(), true);
        }
        return false;
    }

    /**
     * A spell cast from an effect ("you may cast it without paying its mana cost"). Census: calls, the optional ones
     * (Forge's AI may decline: a real yes/no, family-25 candidate) and Forge's declines. For a seat whose answers are
     * played, Forge's AI still makes the yes/no (counted) and the seat chooses the targets.
     */
    private boolean bridgedPlayFromEffect(final SpellAbility tgtSA) {
        if (!localAnswers() || tgtSA == null) {
            return super.playSaFromPlayEffect(tgtSA);
        }
        boolean optional;
        try {
            optional = !tgtSA.getPayCosts().isMandatory();
        } catch (RuntimeException e) {
            optional = false;
        }
        note("playFromEffect.calls");
        if (optional) {
            note("playFromEffect.optional");
        }
        if (!seatPlays() || !(tgtSA instanceof Spell)) {
            final boolean out = super.playSaFromPlayEffect(tgtSA);
            if (optional && !out) {
                note("playFromEffect.declined");
            }
            return out;
        }
        final boolean noManaCost = tgtSA.hasParam("WithoutManaCost");
        final boolean will = getAi().canPlayFromEffectAI((Spell) tgtSA, !optional, noManaCost)
                == forge.ai.AiPlayDecision.WillPlay;
        if (will || !optional) {
            if (chainTargets(tgtSA)) {
                retargetForSeat(tgtSA, "playFromEffect", false);
            }
            return forge.ai.ComputerUtil.playStack(tgtSA, getPlayer(), getGame());
        }
        note("playFromEffect.declined");
        return false;
    }

    /** An effect played without the stack (replacement effects, opening-hand actions, resolving triggers). */
    private void bridgedPlayNoStack(final SpellAbility effectSA, final boolean canSetupTargets) {
        if (!localAnswers() || effectSA == null) {
            super.playSpellAbilityNoStack(effectSA, canSetupTargets);
            return;
        }
        note("noStack.calls");
        final boolean targeting = canSetupTargets && chainTargets(effectSA);
        if (targeting) {
            note("noStack.setsTargets");
        }
        if (!seatPlays() || !targeting) {
            super.playSpellAbilityNoStack(effectSA, canSetupTargets);
            return;
        }
        // PlayerControllerAi.playSpellAbilityNoStack, with the seat's targets
        getAi().doTrigger(effectSA, true);
        retargetForSeat(effectSA, "noStack", false);
        forge.ai.ComputerUtil.playNoStack(getPlayer(), effectSA, getGame(), true);
    }

    /**
     * A spell or ability the seat chose at priority. A modal spell gets its modes inside handlePlayingSpellAbility
     * (CharmEffect.makeChoices, the `mode` ask), after ensureTargets ran, so the chosen modes' targets are asked
     * there. Everything else is PlayerControllerAi's.
     */
    @Override
    public boolean playChosenSpellAbility(final SpellAbility sa) {
        count("playChosenSpellAbility");
        if (!seatPlays() || sa == null || sa.isLandAbility() || sa.getApi() != forge.game.ability.ApiType.Charm) {
            return super.playChosenSpellAbility(sa);
        }
        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            if (cur.hasParam("TargetingPlayer")) {
                return super.playChosenSpellAbility(sa);
            }
        }
        forge.ai.ComputerUtil.handlePlayingSpellAbility(getPlayer(), sa, root -> retargetForSeat(root, "cast", true));
        return true;
    }

    @Override
    public void autoPassCancel() { count("autoPassCancel"); super.autoPassCancel(); }
    @Override
    public void awaitNextInput() { count("awaitNextInput"); super.awaitNextInput(); }
    @Override
    public void cancelAwaitNextInput() { count("cancelAwaitNextInput"); super.cancelAwaitNextInput(); }

}
