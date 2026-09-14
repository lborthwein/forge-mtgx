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
import forge.ai.ComputerUtilAbility;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
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
import forge.game.combat.CombatDamageAssignment;
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
public class PlayerControllerBridge extends PlayerControllerAi implements forge.game.player.ScopedTriggerResolution,
        forge.game.player.ScopedReplacementExecution, ScopedCombatDamageAssignment {

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
    private SpellAbility pendingExternalAbility;
    private JsonObject pendingExternalAnswer;
    private RulesPaymentExecutor activeRulesPayment;
    private boolean selectingExternalTargets;
    private boolean announcingExternalAction;
    private SpellAbility announcingExternalAbility;
    private boolean failedExternalAction;
    private CombatDamageAssignment activeCombatDamage;
    private final Map<CombatDamageAssignment, CallCounter.Invocation> pendingCombatDamage = new IdentityHashMap<>();

    /**
     * The `ChangeZone` resolution currently walking its one-at-a-time loop, and how many
     * cards it has taken. See `zoneChangeProgress` — v2.17's `chosen` field exists because
     * a five-card pile reaches this controller as five separate single-card asks.
     */
    private SpellAbility zoneChangeRun = null;
    private final KnownHandObservation knownHand;
    private final RevealHistoryObservation revealHistory;
    private int zoneChangeChosen = 0;

    public PlayerControllerBridge(final Game game, final Player p, final LobbyPlayer lp,
            final BenchSession session, final BenchSession.Mode mode, final int seat,
            final CallCounter counters) {
        super(game, p, lp);
        this.session = session;
        this.mode = mode;
        this.seat = seat;
        this.counters = counters;
        this.knownHand = new KnownHandObservation(p);
        this.revealHistory = new RevealHistoryObservation(p);
        this.counters.configureControllerMode(mode.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
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

    /** Classify only after the selected path returned successfully. The argument
     * is evaluated before classification; exceptions retain the open invocation. */
    private <T> T classifiedResult(final CallCounter.Invocation invocation,
            final CallCounter.Ownership owner, final T result) {
        if (invocation != null) invocation.classify(owner);
        return result;
    }

    /** A real inherited AI answer, including on a bridged seat. STOCK is never
     * upgraded to HOST merely because a bridge controller is installed. */
    private <T> T stockCall(final String method, final java.util.function.Supplier<T> action) {
        final var invocation = isLiveGame() ? counters.beginCall(method) : null;
        return classifiedResult(invocation, CallCounter.Ownership.STOCK, action.get());
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

    /** Envelope shared by every ask: game id, seat and the seat-visible state. */
    private JsonObject envelope(final boolean withState) {
        final JsonObject o = new JsonObject();
        o.addProperty("game", session.getGameId());
        o.addProperty("seat", seat);
        if (withState) {
            try {
                final JsonObject state = StateEncoder.encodeWithStackInstances(getGame(), getPlayer());
                if (mode == BenchSession.Mode.BRIDGE && isLiveGame()) {
                    knownHand.augment(state);
                    revealHistory.augment(state);
                }
                o.add("state", state);
            } catch (RuntimeException e) {
                JsonRpcChannel.logErr("BENCH_INTEGRITY_FAILURE: state encoding failed", e);
                throw e;
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
        final JsonObject ans = session.getChannel().ask(kind, body);
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
        if (e == null || answer == null || session.getChannel().isClosed()) {
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
        if (selectingExternalTargets || strictHostTargets) throw new RulesCostFeasibility.Unsupported("selected action " + method + ": " + why);
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

    /** Exact scalar decoding for audited callbacks: Gson coercion is not a
     * host decision. Keep legacy decoders untouched for non-migrated surfaces. */
    private static Integer hostInteger(final JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("0|[1-9][0-9]*")) return null;
        try { return Integer.valueOf(value.getAsString()); }
        catch (NumberFormatException invalid) { return null; }
    }

    private void requireHostChannel(final String operation) {
        if (session.integrityFailure(getGame()) != null || session.getChannel().isClosed())
            throw new RulesCostFeasibility.Unsupported(operation + " requires a healthy open host channel");
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
        if (failedExternalAction) throw new RulesCostFeasibility.Unsupported("prior controlled action failed; game cannot continue");
        if (failedOptionalResolution) throw new RulesCostFeasibility.Unsupported("prior optional resolution failed; game cannot continue");
        final var invocation = isLiveGame() ? counters.beginCall("chooseSpellAbilityToPlay") : null;
        if (!bridged()) {
            final List<SpellAbility> out = super.chooseSpellAbilityToPlay();
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return out;
        }
        if (pendingExternalAbility != null) throw new RulesCostFeasibility.Unsupported("previous selected action was not executed");
        final PriorityDecision decision = buildPriorityDecision();
        final List<SpellAbility> menu = decision.menu();
        recordMenuCensus(decision.diag(), menu.size());
        final JsonObject body = decision.body();
        final JsonObject ans = ask("chooseSpellAbilityToPlay", "priority", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final List<SpellAbility> out = super.chooseSpellAbilityToPlay();
            echo(e, echoPriority(menu, out));
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return out;
        }
        // Priority authority requires an actual integral JSON number. Gson's
        // getAsInt coerces strings/arrays and truncates fractions or overflow;
        // those must not become validated host actions (including pass zero).
        Integer choice = null;
        final JsonElement rawChoice = ans.get("choice");
        if (rawChoice != null && rawChoice.isJsonPrimitive() && rawChoice.getAsJsonPrimitive().isNumber()) {
            try { choice = rawChoice.getAsBigDecimal().intValueExact(); }
            catch (NumberFormatException | ArithmeticException invalid) { /* existing explicit refusal below */ }
        }
        if (choice == null || choice < 0 || choice > menu.size()) {
            refuse("chooseSpellAbilityToPlay", "choice out of range: " + choice);
            final List<SpellAbility> out = super.chooseSpellAbilityToPlay();
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return out;
        }
        if (choice == 0) {
            if (invocation != null) invocation.classify(CallCounter.Ownership.HOST);
            return null;
        }
        final SpellAbility chosen = menu.get(choice - 1);
        if (!chosen.canPlay()) {
            refuse("chooseSpellAbilityToPlay", "chosen ability is no longer playable: " + chosen);
            final List<SpellAbility> out = super.chooseSpellAbilityToPlay();
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return out;
        }
        announceX(chosen, ans);
        if (chosen.isManaAbility()) new PriorityManaActivation(getPlayer(),chosen).select(ans);
        else if (ans.has("manaOutput")) throw new RulesCostFeasibility.Unsupported("mana output supplied for non-mana action");
        pendingExternalAbility = chosen;
        pendingExternalAnswer = ans.deepCopy();
        BenchActionAudit.chosen(getGame(), seat, chosen, pendingExternalAnswer);
        final List<SpellAbility> out = Lists.newArrayList(chosen);
        // This invocation owns the action/X selection only. Modes, targets and
        // payment are separate actual announcement calls; failure aborts game.
        if (invocation != null) invocation.classify(CallCounter.Ownership.HOST);
        return out;
    }

    private record PriorityDecision(List<SpellAbility> menu, JsonObject body, int[] diag) {}

    /** Same production enumeration/encoding, without host RPC, decisions, census
     * counters or execution. Intended for matched stock/null/probe controls. */
    public void probePriorityMenuPurity() { buildPriorityDecision(); }

    private PriorityDecision buildPriorityDecision() {
        final BenchRandomAudit.Token rngBeforeMenu = BenchRandomAudit.begin();
        final var stateBeforeMenu = BenchMenuStateAudit.capture(getGame());
        final int[] diag = new int[DIAG_LEN];
        final List<SpellAbility> menu = legalSpellAbilities(diag);
        final JsonObject body = envelope(true);
        body.addProperty("rulesCostVersion", RulesCostFeasibility.VERSION);
        body.addProperty("paymentVersion", RulesCostFeasibility.PAYMENT_VERSION);
        body.addProperty("paymentControl", "host-complete-witness");
        body.addProperty("priorityStackTargetsVersion", PriorityStackTargetDomain.VERSION);
        body.addProperty("priorityBoardTargetsVersion", PriorityBoardTargetDomain.VERSION);
        body.addProperty("priorityManaVersion", PriorityManaActivation.VERSION);
        body.add("menuDiag", menuDiagJson(diag, menu.size()));
        final JsonArray items = new JsonArray();
        items.add(StateEncoder.encodePriorityAbilityWithTargetDomains(null, getPlayer().getView())); // choice 0 is always pass
        for (SpellAbility sa : menu) {
            var item=StateEncoder.encodePriorityAbilityWithTargetDomains(sa, getPlayer().getView());
            if(sa.isManaAbility())item.add("manaActivation",new PriorityManaActivation(getPlayer(),sa).request());
            items.add(item);
        }
        body.add("menu", items);
        body.add("manaAbilities", manaAbilityChannel());
        BenchMenuStateAudit.assertUnchanged(stateBeforeMenu, getGame());
        BenchRandomAudit.assertUnchanged(rngBeforeMenu, "priority menu and seat-visible encoding");
        return new PriorityDecision(menu, body, diag);
    }

    /** Host choice, not a default or a strategic maximum. A bad announcement
     * invalidates the action; never clamp, silently pick zero, or delegate. */
    private void announceX(final SpellAbility chosen, final JsonObject ans) {
        boolean hasX = chosen.getPayCosts()!=null && chosen.getPayCosts().getTotalMana().countX()>0;
        if (!hasX) {
            if (ans.has("x")) throw new RulesCostFeasibility.Unsupported("X supplied for a non-X action");
            return;
        }
        var raw=ans.get("x");
        if (raw==null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()
                || !raw.getAsString().matches("0|[1-9][0-9]{0,2}"))
            throw new RulesCostFeasibility.Unsupported("explicit nonnegative integer X required");
        int x=raw.getAsInt();
        var range=RulesCostFeasibility.announcementRange(getPlayer(),chosen);
        if (x<range.min() || x>range.max()) throw new RulesCostFeasibility.Unsupported("X outside exact payable range");
        chosen.setXManaCostPaid(x);
        counters.instrument("x.announced");
        if (x>0) counters.instrument("x.announcedNonZero");
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
     * <p>During controlled announcement, routing through {@link #chooseTargetsFor}
     * requires an explicit host answer; delegation invalidates the game.
     */
    private boolean ensureTargets(final SpellAbility root, final RulesCastingAuthorization announcement) {
        // A land play is not a spell/casting cost. Its rules permission is checked
        // by LandAbility.canPlay, including again immediately before execution.
        if (root.isLandAbility()) return true;
        try (var routing = TargetingPlayerRouting.open(root, getPlayer())) {
            if (!root.setupTargets())
                throw new RulesCostFeasibility.Unsupported("native target setup rejected selected action");
            for (SpellAbility cur = root; cur != null; cur = cur.getSubAbility())
                if (cur.usesTargeting() && !cur.isTargetNumberValid())
                    throw new RulesCostFeasibility.Unsupported("native target setup returned invalid target count");
        }
        // Match SpellAbility.setupTargets: mandatory target restrictions apply
        // to the complete root/subability chain, not to each target group.
        // A later group may satisfy the requirement (for example Flagbearer).
        if (!forge.game.staticability.StaticAbilityMustTarget.meetsMustTargetRestriction(root)) {
            throw new RulesCostFeasibility.Unsupported("selected action violates whole-chain mandatory target restriction");
        }
        final var cost = RulesCostFeasibility.assess(getPlayer(), root, announcement);
        if (cost.status() == RulesCostFeasibility.Status.UNSUPPORTED) throw new RulesCostFeasibility.Unsupported(cost.reason());
        if (cost.status() != RulesCostFeasibility.Status.PAYABLE) {
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
     * The legal action menu offered at priority, including standalone mana
     * activations. Loop/liveness controls must not erase legal action classes.
     */
    private List<SpellAbility> legalSpellAbilities() {
        return legalSpellAbilities(null);
    }

    /**
     * v2.18 — THE MANA ABILITIES, ON A CHANNEL OF THEIR OWN.
     *
     * <p>Historical metadata channel, retained for ability context even when a
     * source is tapped or unavailable. It is NOT the executable action domain;
     * playable standalone mana activations also appear in the priority menu.
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
     * a preparation of an ability. An enumeration failure invalidates the observation;
     * silently omitting one producer can change the host's payment policy.
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
                    out.add(StateEncoder.encodeSpellAbility(sa, p.getView()));
                }
            } catch (RuntimeException e) {
                JsonRpcChannel.logErr("BENCH_INTEGRITY_FAILURE: mana ability channel failed for " + c, e);
                throw e;
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
                    for (SpellAbility sa : land.getAllPossibleAbilities(p, true, null, true)) {
                        if (sa.isLandAbility() && sa.canPlay()) {
                            out.add(sa);
                        }
                    }
                }
            }
            final CardCollection cards = ComputerUtilAbility.getAvailableCards(game, p);
            final List<SpellAbility> withVariants = BenchmarkAbilityEnumeration.spells(cards, p);
            for (SpellAbility sa : withVariants) {
                if (sa.isLandAbility()) {
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
                if (!RulesCostFeasibility.requireMenuPayable(p, sa)) {
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
            JsonRpcChannel.logErr("BENCH_INTEGRITY_FAILURE: legalSpellAbilities failed", e);
            throw e;
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
     * legacy estimated ceiling: mana left after the base cost, divided by the repeat's
     * own mana cost. Observation purity does not make this exact rules feasibility.
     */
    private int affordableRepeats(final SpellAbility sa, final Cost cost, final int max) {
        int ceiling;
        try {
            final int repeatMana = cost == null || cost.hasNoManaCost()
                    ? 0 : cost.getTotalMana().getCMC();
            if (repeatMana <= 0) {
                // A non-mana repeat (Casualty's sacrifice, Conspire's tap) -- Forge asks
                // these as a yes/no with max 1 and we have no cheap affordability model.
                ceiling = Math.min(max, 1);
            } else {
                final int available = LegacyManaEstimate.available(getPlayer());
                final int base = sa.getPayCosts() == null || sa.getPayCosts().hasNoManaCost()
                        ? 0 : sa.getPayCosts().getTotalMana().getCMC();
                ceiling = Math.min(max, Math.max(0, (available - base) / repeatMana));
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("keyword-cost ceiling estimate failed", e);
            throw new RulesCostFeasibility.Unsupported("Legacy keyword-cost ceiling estimate failed: " + e.getClass().getSimpleName());
        }
        return Math.max(0, ceiling);
    }

    /**
     * Every optional extra cost the host will be asked about once it commits to this cast
     * (protocol v2.4). Read-only, so a `{0}` ballot is not advertised as free while a
     * Multikicker is pending behind it.
     */
    @Override
    public int chooseNumberForKeywordCost(final SpellAbility sa, final Cost cost,
            final KeywordInterface keyword, final String prompt, final int max) {
        final var invocation = isLiveGame() ? counters.beginCall("chooseNumberForKeywordCost") : null;
        if (!bridged()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max));
        }
        final int ceiling = affordableRepeats(sa, cost, max);
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
        final JsonObject ans = ask("chooseNumberForKeywordCost", "keywordCost", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final int out = classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max));
            echo(e, echoInt("value", out));
            return out;
        }
        final Integer v = optInt(ans, "value");
        if (v == null || v < 0 || v > ceiling) {
            refuse("chooseNumberForKeywordCost",
                    "value " + v + " outside [0," + ceiling + "] for " + prompt);
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max));
        }
        counters.instrument("keywordCost.answered");
        if (v > 0) {
            counters.instrument("keywordCost.paid");
        }
        return classifiedResult(invocation, CallCounter.Ownership.HOST, v);
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(final SpellAbility chosen,
            final List<OptionalCostValue> optionalCostValues) {
        final var invocation = isLiveGame() ? counters.beginCall("chooseOptionalCosts") : null;
        if (buildingMenu) {
            // Decline while enumerating: taking them here would silently drop the unkicked
            // ability from the menu. Both variants are offered instead (see
            // legalSpellAbilities), so the host votes on the cost rather than inheriting it.
            return classifiedResult(invocation, CallCounter.Ownership.RULES, Collections.emptyList());
        }
        if (!bridged() || optionalCostValues == null || optionalCostValues.isEmpty()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseOptionalCosts(chosen, optionalCostValues));
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
        final JsonObject ans = ask("chooseOptionalCosts", "optionalCosts", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final List<OptionalCostValue> out = classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseOptionalCosts(chosen, optionalCostValues));
            echo(e, echoIndices(optionalCostValues, out));
            return out;
        }
        final List<Integer> idx = optIntList(ans, "choices");
        if (idx == null) {
            refuse("chooseOptionalCosts", "missing/!array 'choices'");
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseOptionalCosts(chosen, optionalCostValues));
        }
        final List<OptionalCostValue> picked = new ArrayList<>();
        for (int i : idx) {
            if (i < 0 || i >= optionalCostValues.size() || picked.contains(optionalCostValues.get(i))) {
                refuse("chooseOptionalCosts", "optional-cost index out of range/duplicate: " + i);
                return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseOptionalCosts(chosen, optionalCostValues));
            }
            picked.add(optionalCostValues.get(i));
        }
        return classifiedResult(invocation, CallCounter.Ownership.HOST, picked);
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
            JsonRpcChannel.logErr("BENCH_INTEGRITY_FAILURE: stack candidate enumeration failed for " + sa, e);
            throw e;
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
            JsonRpcChannel.logErr("BENCH_INTEGRITY_FAILURE: entity candidate enumeration failed for " + sa, e);
            throw e;
        }
        return n;
    }

    /**
     * {@code SpellAbility.canPlay} does not check that legal targets exist, so without this
     * the menu offers e.g. a counterspell with an empty stack. Every entry we offer must be
     * an action the host can actually complete.
     */
    private static boolean hasEnoughTargets(final SpellAbility original) {
        // Only enumeration copies receive prospective chooser metadata. In
        // particular, this must not invoke setupTargets or either player's AI.
        final SpellAbility root = original.copyForEnumeration(original.getActivatingPlayer());
        for (SpellAbility choice = root; choice != null; choice = choice.getSubAbility())
            if (choice.usesTargeting()) choice.setTargetingPlayer(TargetingPlayerRouting.chooser(choice));
        if (root.getApi() == forge.game.ability.ApiType.Charm) {
            // Exact ordinary single-mode choice; unresolved mode families must
            // invalidate coverage, not be hidden from the action menu.
            if (!"1".equals(root.getParamOrDefault("CharmNum", "1"))
                    || !"1".equals(root.getParamOrDefault("MinCharmNum", "1"))
                    || java.util.List.of("CanRepeatModes", "Random", "Chooser", "Optional", "ChoiceRestriction").stream().anyMatch(root::hasParam))
                throw new RulesCostFeasibility.Unsupported("unsupported modal choice domain");
            boolean viable = false;
            final var copy = root.copyForEnumeration(root.getActivatingPlayer());
            for (var option : copy.getAdditionalAbilityList("Choices")) {
                if (option.hasParam("ModeCost") || option.getApi() == forge.game.ability.ApiType.Charm)
                    throw new RulesCostFeasibility.Unsupported("unsupported modal cost or nested mode");
                if (hasEnoughTargets(option)) viable = true;
            }
            if (!viable) return false;
        }
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
        final var invocation = isLiveGame() ? counters.beginCall("declareAttackers") : null;
        if (mode == BenchSession.Mode.BRIDGE && isLiveGame()) {
            declareHostCombat(attacker, combat, true, invocation); return;
        }
        super.declareAttackers(attacker, combat);
        if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
    }

    private void declareHostCombat(Player player, Combat combat, boolean attack, CallCounter.Invocation invocation) {
        final String method = attack ? "declareAttackers" : "declareBlockers";
        try {
            requireHostChannel(method);
            // Follow the engine's declarer routing, including effects that let
            // another player declare. A simple player == getPlayer() would
            // incorrectly reject those legitimate callbacks.
            final Player declarer = Objects.requireNonNullElse(
                    attack ? player.getDeclaresAttackers() : player.getDeclaresBlockers(), player);
            if (player.getGame() != getGame() || declarer.getController() != this || combat != getGame().getCombat())
                throw new RulesCostFeasibility.Unsupported("combat callback is not the live routed declaration");
            var choice = new CombatDeclarationChoices(player, combat, attack);
            var body = envelope(true);
            choice.encode(body);
            if (attack) addAttackRequirements(body, combat, choice.cards, choice.defenders);
            var answer = ask(method, attack ? "attackers" : "blockers", body);
            requireHostChannel(method);
            var receipt = choice.apply(answer);
            receipt.addProperty("game", session.getGameId());
            receipt.addProperty("seat", seat);
            JsonRpcChannel.log("[bench-combat] " + receipt);
            invocation.classify(CallCounter.Ownership.HOST);
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, method, failure);
            throw failure;
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
        final var invocation = isLiveGame() ? counters.beginCall("declareBlockers") : null;
        if (mode == BenchSession.Mode.BRIDGE && isLiveGame()) {
            declareHostCombat(defender, combat, false, invocation); return;
        }
        super.declareBlockers(defender, combat);
        if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
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
        final var invocation = isLiveGame() ? counters.beginCall("chooseStartingPlayer") : null;
        if (!bridged()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseStartingPlayer(isFirstGame));
        }
        final JsonObject body = envelope(true);
        // The seat being asked IS the seat that won the roll; Forge never asks the other.
        body.addProperty("winner", seat);
        body.addProperty("choosingSeat", seat);
        body.addProperty("firstGame", isFirstGame);
        final JsonObject ans = ask("chooseStartingPlayer", "startingPlayer", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final Player out = classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseStartingPlayer(isFirstGame));
            // `play` is the answer field; Forge returns the player who goes first.
            echo(e, echoBool("play", out == getPlayer()));
            return out;
        }
        final Boolean play = optBool(ans, "play");
        if (play == null) {
            refuse("chooseStartingPlayer", "expected boolean 'play'");
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseStartingPlayer(isFirstGame));
        }
        counters.instrument(play ? "start.tookPlay" : "start.tookDraw");
        if (play) {
            return classifiedResult(invocation, CallCounter.Ownership.HOST, getPlayer());
        }
        // Decline: the opponent goes first. With more than two seats Forge's own rule is
        // "the chooser or nobody", so fall back rather than invent a seating order.
        Player other = null;
        for (Player p : getGame().getPlayers()) {
            if (p != getPlayer()) {
                if (other != null) {
                    refuse("chooseStartingPlayer", "cannot decline the play in a >2 player game");
                    return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseStartingPlayer(isFirstGame));
                }
                other = p;
            }
        }
        return classifiedResult(invocation, CallCounter.Ownership.HOST, other == null ? getPlayer() : other);
    }

    /**
     * London mulligan bottoming (protocol v2.6). Also previously uncounted-and-inherited:
     * Forge chose which cards our seat put on the bottom. Routed through the existing
     * {@code cardsChoice} machinery with min = max = the number to return.
     */
    @Override
    public CardCollectionView tuckCardsViaMulligan(final CardCollectionView hand, final int cardsToReturn) {
        final var invocation = isLiveGame() ? counters.beginCall("tuckCardsViaMulligan") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.tuckCardsViaMulligan(hand, cardsToReturn));
        }
        try {
        requireHostChannel("London bottom selection");
        if (hand == null || cardsToReturn < 0 || cardsToReturn > hand.size())
            throw new RulesCostFeasibility.Unsupported("invalid London bottom count/domain");
        final var originals = new java.util.LinkedHashMap<Integer, Card>();
        for (Card card : hand) {
            if (card == null || card.getGame() != getGame() || !card.isInZone(ZoneType.Hand)
                    || card.getOwner() != getPlayer() || originals.put(card.getId(), card) != null)
                throw new RulesCostFeasibility.Unsupported("invalid London hand instance");
        }
        if (cardsToReturn == 0) {
            if (invocation != null) invocation.classify(CallCounter.Ownership.FORCED);
            return new CardCollection();
        }
        final JsonObject body = envelope(true);
        body.addProperty("title", "put on the bottom (London mulligan)");
        body.addProperty("min", cardsToReturn); body.addProperty("max", cardsToReturn);
        body.add("menu", StateEncoder.encodeCards(hand));
        final var answer = ask("tuckCardsViaMulligan", "cardsChoice", body);
        if (answer == null || !answer.has("choices") || !answer.get("choices").isJsonArray()
                || answer.getAsJsonArray("choices").size() != cardsToReturn)
            throw new RulesCostFeasibility.Unsupported("London bottom requires exact host card count");
        final CardCollection picked = new CardCollection();
        final var used = new java.util.HashSet<Integer>();
        for (JsonElement raw : answer.getAsJsonArray("choices")) {
            final Integer id = hostInteger(raw);
            final Card card = id == null ? null : originals.get(id);
            if (card == null || !used.add(id))
                throw new RulesCostFeasibility.Unsupported("unknown/duplicate/nonintegral London card identity");
            picked.add(card);
        }
        if (invocation != null) invocation.classify(CallCounter.Ownership.HOST);
        return picked;
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "London bottom selection", failure);
            throw failure;
        }
    }

    @Override
    public boolean mulliganKeepHand(final Player firstPlayer, final int cardsToReturn) {
        final var invocation = isLiveGame() ? counters.beginCall("mulliganKeepHand") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.mulliganKeepHand(firstPlayer, cardsToReturn));
        }
        try {
        requireHostChannel("mulligan");
        // MulliganService supplies the STARTING player to every controller,
        // including the non-starting seat. This is not the decision's actor.
        if (firstPlayer == null || !getGame().getPlayers().contains(firstPlayer) || cardsToReturn < 0)
            throw new RulesCostFeasibility.Unsupported("invalid mulligan starting player/count");
        final JsonObject body = envelope(true);
        body.addProperty("cardsToReturn", cardsToReturn);
        body.add("hand", StateEncoder.encodeCards(getPlayer().getCardsIn(ZoneType.Hand)));
        final JsonObject ans = ask("mulliganKeepHand", "mulligan", body);
        if (ans == null) throw new RulesCostFeasibility.Unsupported("mulligan requires an explicit host answer");
        Boolean keep = null;
        if (ans.has("keep")) {
            final var raw = ans.get("keep");
            if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean())
                throw new RulesCostFeasibility.Unsupported("mulligan keep must be a JSON boolean");
            keep = raw.getAsBoolean();
        }
        if (ans.has("choice")) {
            final Integer choice = hostInteger(ans.get("choice"));
            if (choice == null || choice > 1 || (keep != null && keep != (choice == 1)))
                throw new RulesCostFeasibility.Unsupported("mulligan choice must be consistent integer 0/1");
            keep = choice == 1;
        }
        if (keep == null) throw new RulesCostFeasibility.Unsupported("missing mulligan decision");
        if (invocation != null) invocation.classify(CallCounter.Ownership.HOST);
        return keep;
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "mulligan decision", failure);
            throw failure;
        }
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(final int numDiscard) {
        final var invocation = isLiveGame() ? counters.beginCall("chooseCardsToDiscardToMaximumHandSize") : null;
        if (!bridged()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseCardsToDiscardToMaximumHandSize(numDiscard));
        }
        final CardCollectionView hand = getPlayer().getCardsIn(ZoneType.Hand);
        final CardCollection picked = askForCards("chooseCardsToDiscardToMaximumHandSize", hand,
                numDiscard, numDiscard, "discard to maximum hand size", null);
        if (picked == null) {
            final Echo e = takeEcho();
            final CardCollectionView out = classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseCardsToDiscardToMaximumHandSize(numDiscard));
            echo(e, echoCards(out));
            return out;
        }
        return classifiedResult(invocation, CallCounter.Ownership.HOST, picked);
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(final SpellAbility sa, final int min, final int max,
            final CardCollectionView validTargets, final String message) {
        final var invocation = isLiveGame() ? counters.beginCall("choosePermanentsToSacrifice") : null;
        if (!bridged()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.choosePermanentsToSacrifice(sa, min, max, validTargets, message));
        }
        final CardCollection picked = askForCards("choosePermanentsToSacrifice", validTargets, min, max, message, sa);
        if (picked == null) {
            final Echo e = takeEcho();
            final CardCollectionView out =
                    classifiedResult(invocation, CallCounter.Ownership.STOCK, super.choosePermanentsToSacrifice(sa, min, max, validTargets, message));
            echo(e, echoCards(out));
            return out;
        }
        return classifiedResult(invocation, CallCounter.Ownership.HOST, picked);
    }

    @Override
    public CardCollectionView chooseCardsForEffect(final CardCollectionView sourceList, final SpellAbility sa,
            final String title, final int min, final int max, final boolean isOptional,
            final Map<String, Object> params) {
        final var invocation = isLiveGame() ? counters.beginCall("chooseCardsForEffect") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) {
            var out = super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return out;
        }
        try {
            requireHostChannel("effect card choice");
            if (sourceList == null || min < 0 || max < min)
                throw new RulesCostFeasibility.Unsupported("invalid effect card bounds");
            final List<Card> domain = List.copyOf(sourceList);
            final int lower = isOptional ? 0 : Math.min(min, domain.size());
            final int upper = Math.min(max, domain.size());
            final var identities = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Card, Boolean>());
            final var timestamps = new java.util.IdentityHashMap<Card, Long>();
            final var zones = new java.util.IdentityHashMap<Card, forge.game.zone.Zone>();
            final var fids = new java.util.HashSet<Integer>();
            final var menu = new JsonArray();
            for (Card card : domain) {
                if (!identities.add(card) || !fids.add(card.getId()) || card.getGame() != getGame()
                        || card.getZone() == null || card.getZone().getCards().stream().noneMatch(live -> live == card))
                    throw new RulesCostFeasibility.Unsupported("invalid effect card identity");
                var visible = knownHand.visible(card);
                if (visible == null) throw new RulesCostFeasibility.Unsupported("effect card lacks visibility grant");
                menu.add(visible);
                timestamps.put(card, card.getGameTimestamp()); zones.put(card, card.getZone());
            }
            if (lower == upper && (upper == 0 || upper == domain.size())) {
                if (invocation != null) invocation.classify(CallCounter.Ownership.FORCED);
                return upper == 0 ? new CardCollection() : new CardCollection(domain);
            }
            var body = envelope(true);
            body.addProperty("title", String.valueOf(title)); body.addProperty("min", lower); body.addProperty("max", upper);
            body.addProperty("effectChoiceVersion", "host-card-domain-v1");
            if (sa != null) {
                body.addProperty("effectChoices", sa.getParamOrDefault("Choices", ""));
                body.addProperty("effectChoiceZone", sa.getParamOrDefault("ChoiceZone", "Battlefield"));
                body.addProperty("effectChoiceAmount", sa.getParamOrDefault("Amount", "1"));
                body.addProperty("effectChoiceMinAmount", sa.getParamOrDefault("MinAmount", "1"));
            }
            body.add("menu", menu);
            if (sa != null) body.add("ability", StateEncoder.encodeSpellAbility(sa));
            var answer = ask("chooseCardsForEffect", "cardsChoice", body);
            if (answer == null || !answer.has("choices") || !answer.get("choices").isJsonArray())
                throw new RulesCostFeasibility.Unsupported("effect requires explicit card selection");
            for (Card card : domain) if (timestamps.get(card) != card.getGameTimestamp() || zones.get(card) != card.getZone()
                    || card.getZone() == null || card.getZone().getCards().stream().noneMatch(live -> live == card))
                throw new RulesCostFeasibility.Unsupported("effect card domain changed during decision");
            var picked = new CardCollection();
            for (var id : answer.getAsJsonArray("choices")) {
                Integer fid = hostInteger(id);
                Card card = fid == null ? null : domain.stream().filter(c -> c.getId() == fid).findFirst().orElse(null);
                if (card == null || picked.contains(card))
                    throw new RulesCostFeasibility.Unsupported("effect choice has invalid or duplicate card ID");
                picked.add(card);
            }
            if (picked.size() < lower || picked.size() > upper)
                throw new RulesCostFeasibility.Unsupported("effect choice outside cardinality");
            if (invocation != null) invocation.classify(CallCounter.Ownership.HOST);
            return picked;
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "effect card choice", failure);
            throw failure;
        }
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
        final JsonObject ans = ask(method, "cardsChoice", body);
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

    private boolean strictHostTargets;

    @Override
    public boolean chooseTargetsFor(final SpellAbility currentAbility) {
        final var invocation = isLiveGame() ? counters.beginCall("chooseTargetsFor") : null;
        TargetingPlayerRouting.requireController(currentAbility, getPlayer(), mode == BenchSession.Mode.BRIDGE && isLiveGame());
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) {
            final boolean result = super.chooseTargetsFor(currentAbility);
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return result;
        }
        if (currentAbility == null || !currentAbility.usesTargeting() || !bridged())
            throw new RulesCostFeasibility.Unsupported("live host target callback lacks ability or transport");
        final boolean previous = strictHostTargets;
        strictHostTargets = true;
        try {
            final boolean result = chooseHostTargets(currentAbility);
            if (!result || !currentAbility.isTargetNumberValid())
                throw new RulesCostFeasibility.Unsupported("host target callback returned invalid target count");
            if (invocation != null) invocation.classify(CallCounter.Ownership.HOST);
            return result;
        } finally { strictHostTargets = previous; }
    }

    private boolean chooseHostTargets(final SpellAbility currentAbility) {
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
        final int max = currentAbility.getMaxTargets();
        if (preparingOptionalTrigger && min>0 && candidates.isEmpty() && stack.isEmpty())
            throw new NoLegalOptionalTriggerTarget();

        final JsonObject body = envelope(true);
        body.add("ability", selectingExternalTargets
                ? StateEncoder.encodePriorityAbility(currentAbility, getPlayer().getView())
                : StateEncoder.encodeSpellAbility(currentAbility, getPlayer().getView()));
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
        body.addProperty("targetAllocationVersion", "host-explicit-divide-v1");
        // This callback replaces the entire target set below; previous divided
        // allocations are not retained. The host must allocate the full total.
        body.addProperty("targetSelectionMode", "replace-all");
        body.addProperty("dividedAsYouChoose", divided);
        if (divided) {
            final Integer total = currentAbility.getDividedValue();
            if (total == null) {
                body.add("divideTotal", com.google.gson.JsonNull.INSTANCE);
                body.add("allocationTotal", com.google.gson.JsonNull.INSTANCE);
            } else {
                body.addProperty("divideTotal", total);
                body.addProperty("allocationTotal", total);
            }
            body.addProperty("divideRemaining", currentAbility.getStillToDivide());
        }

        final JsonObject ans = ask("chooseTargetsFor", "targets", body);
        if (ans == null) {
            if (strictHostTargets) throw new RulesCostFeasibility.Unsupported("explicit host targets required");
            final Echo e = takeEcho();
            final boolean out = super.chooseTargetsFor(currentAbility);
            // The chosen targets are on the ability, not in the return value.
            echo(e, echoTargets(currentAbility, out));
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
            final EntityRef ref = parseTargetRef(el);
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
        final String divideProblem = applyDividedAllocation(currentAbility, ans, stack);
        if (divideProblem != null) {
            currentAbility.setTargets(before);
            refuse("chooseTargetsFor", divideProblem);
            return super.chooseTargetsFor(currentAbility);
        }
        return true;
    }

    /** Strict target-only decoding. Do not coerce strings, singleton arrays,
     * fractions or overflowing values into another target's identity. The
     * legacy exact-integer forms retain their existing namespace resolution;
     * attacker references retain the unchanged parser above. */
    private EntityRef parseTargetRef(final JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        final JsonObject object = el.isJsonObject() ? el.getAsJsonObject() : null;
        final JsonElement rawId = object == null ? el : object.get("id");
        if (rawId == null || !rawId.isJsonPrimitive() || !rawId.getAsJsonPrimitive().isNumber()) return null;
        final int id;
        try { id = rawId.getAsBigDecimal().intValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { return null; }
        if (id < 0) return null;
        String kind = null;
        if (object != null && object.has("kind") && !object.get("kind").isJsonNull()) {
            final JsonElement rawKind = object.get("kind");
            if (!rawKind.isJsonPrimitive() || !rawKind.getAsJsonPrimitive().isString()) return null;
            kind = rawKind.getAsString().trim().toLowerCase(Locale.ROOT);
            if (!"player".equals(kind) && !"card".equals(kind) && !"spell".equals(kind)) return null;
        }
        // An id-only object is just as ambiguous as a bare integer. Preserve
        // its legacy meaning, but do not let it evade compatibility accounting.
        if (kind == null) counters.instrument("legacy.untypedRef");
        return new EntityRef(kind, id);
    }

    /**
     * Split a "divided as you choose" amount across the chosen targets (protocol v2.7).
     *
     * <p>Choosing targets is only half of targeting such a spell: {@code DamageDealEffect}
     * then reads {@code sa.getDividedValue(target)} per target and dereferences it. Adding
     * targets without an allocation left that null, and the NPE escaped as a crashed game
     * stamped "Draw" -- three of fifteen bridged crashes in one campaign.
     *
     * <p>The host supplies every allocation using typed target keys. No even
     * split or other policy is substituted for missing answers. Validate all
     * fields before changing any divided metadata.
     *
     * @return null on success, or the reason to refuse
     */
    private static String applyDividedAllocation(final SpellAbility sa, final JsonObject ans,
            final List<SpellAbilityStackInstance> stack) {
        if (!sa.isDividedAsYouChoose()) {
            return null;
        }
        if (!ans.has("targetAllocationVersion") || !ans.get("targetAllocationVersion").isJsonPrimitive()
                || !ans.getAsJsonPrimitive("targetAllocationVersion").isString()
                || !"host-explicit-divide-v1".equals(ans.get("targetAllocationVersion").getAsString()))
            return "explicit target allocation version required";
        if (!ans.has("divide") || !ans.get("divide").isJsonObject())
            return "explicit 'divide' object required";
        final JsonObject explicit = ans.getAsJsonObject("divide");
        final List<GameObject> chosen = Lists.newArrayList(sa.getTargets());
        if (chosen.isEmpty()) {
            return explicit.size() == 0 ? null : "'divide' has entries without chosen targets";
        }
        final Integer totalObj = sa.getDividedValue();
        if (totalObj == null) {
            // The engine has not told us how much there is to divide; guessing here is how
            // an illegal allocation gets built. Hand it back rather than invent one.
            return "divided-as-you-choose ability with no total to divide: " + sa;
        }
        final int total = totalObj;
        if (total < chosen.size()) {
            return "cannot divide " + total + " among " + chosen.size() + " targets";
        }
        final Map<GameObject, Integer> allocation = new LinkedHashMap<>();
        final Set<String> usedKeys = new HashSet<>();
        long sum = 0;
        for (GameObject go : chosen) {
            String key = kindOf(go) + ":" + idOf(go);
            if (go instanceof SpellAbility) {
                // The wire names a stack INSTANCE, not SpellAbility.getId().
                // Those sequences differ; resolve using the offered menu.
                final var matches = stack.stream().filter(si -> si.getSpellAbility() == go).toList();
                if (matches.size() != 1) return "ambiguous or absent allocated stack target";
                key = "spell:" + (StateEncoder.SPELL_TARGET_ID_BASE + matches.get(0).getId());
            }
            if (!usedKeys.add(key) || !explicit.has(key)) return "duplicate or omitted allocated target " + key;
            final JsonElement value = explicit.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !value.getAsString().matches("[1-9][0-9]{0,9}"))
                return "'divide' entry for " + key + " must be a positive integer";
            final long n = value.getAsLong();
            if (n > total) return "'divide' exceeds total for " + key;
            allocation.put(go, (int) n);
            sum += n;
        }
        if (!usedKeys.equals(explicit.keySet())) return "'divide' has extra or untyped target keys";
        if (sum != total) return "'divide' allocates " + sum + " of " + total;
        for (var entry : allocation.entrySet()) sa.addDividedAllocation(entry.getKey(), entry.getValue());
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
        final var invocation = isLiveGame() ? counters.beginCall("chooseSingleEntityForEffect") : null;
        final Player forced = TargetingPlayerRouting.forcedChooser(getPlayer(), sa, optionList,
                title, isOptional, delayedReveal, relatedPlayer, params);
        if (forced != null) {
            if (invocation != null) invocation.classify(CallCounter.Ownership.FORCED);
            return optionList.get(0);
        }
        if (mode == BenchSession.Mode.BRIDGE && isLiveGame() && optionList != null
                && optionList.size() == 1 && !isOptional && delayedReveal == null) {
            try {
                requireHostChannel("mandatory singleton entity choice");
                final T only = optionList.get(0);
                if (only == null || only.getGame() != getGame())
                    throw new RulesCostFeasibility.Unsupported("forced entity is not in this game");
                if (invocation != null) invocation.classify(CallCounter.Ownership.FORCED);
                return only;
            } catch (RuntimeException | Error failure) {
                session.noteIntegrityFailure(getGame(), seat, "forced entity choice", failure); throw failure;
            }
        }
        if (!bridged() || optionList == null || optionList.isEmpty()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional,
                    relatedPlayer, params));
        }
        final List<T> options = Lists.newArrayList(optionList);
        final JsonObject body = envelope(true);
        body.addProperty("title", String.valueOf(title));
        body.addProperty("optional", isOptional);
        body.add("menu", StateEncoder.encodeEntities(options));
        if (sa != null) {
            body.add("ability", StateEncoder.encodeSpellAbility(sa));
        }
        final JsonObject ans = ask("chooseSingleEntityForEffect", "entityChoice", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final T out = classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title,
                    isOptional, relatedPlayer, params));
            final int at = out == null ? -1 : indexOfIdentity(options, out);
            // `none` is a legal answer only when the ask said `optional`; a non-null
            // pick that is somehow not in the menu we published is reported as an
            // unmatched choice rather than silently as index -1.
            echo(e, out == null ? echoBool("none", true)
                    : at >= 0 ? echoInt("choice", at) : unmatchedChoice());
            return out;
        }
        if (isOptional && Boolean.TRUE.equals(optBool(ans, "none"))) {
            return classifiedResult(invocation, CallCounter.Ownership.HOST, (T) null);
        }
        final Integer choice = optInt(ans, "choice");
        if (choice == null || choice < 0 || choice >= options.size()) {
            refuse("chooseSingleEntityForEffect", "choice out of range: " + choice);
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional,
                    relatedPlayer, params));
        }
        return classifiedResult(invocation, CallCounter.Ownership.HOST, options.get(choice));
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(final FCollectionView<T> optionList,
            final int min, final int max, final DelayedReveal delayedReveal, final SpellAbility sa,
            final String title, final Player relatedPlayer, final Map<String, Object> params) {
        final var invocation = isLiveGame() ? counters.beginCall("chooseEntitiesForEffect") : null;
        if (!bridged() || optionList == null || optionList.isEmpty()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title,
                    relatedPlayer, params));
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
        final JsonObject ans = ask("chooseEntitiesForEffect", "entityChoice", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final List<T> out = classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa,
                    title, relatedPlayer, params));
            echo(e, echoIndices(options, out));
            return out;
        }
        final List<Integer> idx = optIntList(ans, "choices");
        if (idx == null || idx.size() < min || idx.size() > max) {
            refuse("chooseEntitiesForEffect", "bad 'choices' for [" + min + "," + max + "]");
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title,
                    relatedPlayer, params));
        }
        final List<T> picked = new ArrayList<>();
        for (int i : idx) {
            if (i < 0 || i >= options.size() || picked.contains(options.get(i))) {
                refuse("chooseEntitiesForEffect", "index out of range/duplicate: " + i);
                return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title,
                        relatedPlayer, params));
            }
            picked.add(options.get(i));
        }
        return classifiedResult(invocation, CallCounter.Ownership.HOST, picked);
    }

    @Override
    public int chooseNumber(final SpellAbility sa, final String title, final int min, final int max) {
        final var invocation = isLiveGame() ? counters.beginCall("chooseNumber") : null;
        if (!bridged()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseNumber(sa, title, min, max));
        }
        final JsonObject body = envelope(true);
        body.addProperty("title", String.valueOf(title));
        body.addProperty("min", min);
        body.addProperty("max", max);
        if (sa != null) {
            body.add("ability", StateEncoder.encodeSpellAbility(sa));
        }
        final JsonObject ans = ask("chooseNumber", "number", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final int out = classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseNumber(sa, title, min, max));
            echo(e, echoInt("value", out));
            return out;
        }
        final Integer v = optInt(ans, "value");
        if (v == null || v < min || v > max) {
            refuse("chooseNumber", "value " + v + " outside [" + min + "," + max + "]");
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseNumber(sa, title, min, max));
        }
        return classifiedResult(invocation, CallCounter.Ownership.HOST, v);
    }

    @Override
    public int chooseNumber(final SpellAbility sa, final String title, final List<Integer> values,
            final Player relatedPlayer) {
        final var invocation = isLiveGame() ? counters.beginCall("chooseNumber") : null;
        if (!bridged() || values == null || values.isEmpty()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseNumber(sa, title, values, relatedPlayer));
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
        final JsonObject ans = ask("chooseNumber", "number", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final int out = classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseNumber(sa, title, values, relatedPlayer));
            echo(e, echoInt("value", out));
            return out;
        }
        final Integer v = optInt(ans, "value");
        if (v == null || !values.contains(v)) {
            refuse("chooseNumber", "value " + v + " not offered");
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseNumber(sa, title, values, relatedPlayer));
        }
        return classifiedResult(invocation, CallCounter.Ownership.HOST, v);
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(final SpellAbility sa, final List<AbilitySub> possible,
            final int min, final int num, final boolean allowRepeat) {
        final var invocation = isLiveGame() ? counters.beginCall("chooseModeForAbility") : null;
        if (announcingExternalAction && sa != announcingExternalAbility)
            throw new RulesCostFeasibility.Unsupported("mode choice belongs to a different controlled announcement");
        if (announcingExternalAction && (mode != BenchSession.Mode.BRIDGE || !isLiveGame()
                || session.getChannel().isClosed() || possible == null || possible.isEmpty()))
            throw new RulesCostFeasibility.Unsupported("controlled mode choice requires actual open host domain");
        if (!bridged() || possible == null || possible.isEmpty()) {
            final var out = super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return out;
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
        final JsonObject ans = ask("chooseModeForAbility", "mode", body);
        if (announcingExternalAction && ans == null)
            throw new RulesCostFeasibility.Unsupported("explicit controlled mode answer required");
        if (ans == null) {
            final Echo e = takeEcho();
            final List<AbilitySub> out = super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
            // `possible` is the menu and the answer is its indices, repeats included:
            // `indexOfIdentity` maps a repeated mode back to the same index, which is
            // what an answer with `allowRepeat` would itself have sent.
            echo(e, echoIndices(possible, out));
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return out;
        }
        if (announcingExternalAction) {
            final var raw = ans.get("choices");
            if (raw == null || !raw.isJsonArray()) throw new RulesCostFeasibility.Unsupported("explicit mode indices required");
            for (var value : raw.getAsJsonArray()) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                    throw new RulesCostFeasibility.Unsupported("mode index must be numeric integer");
                try { value.getAsBigDecimal().intValueExact(); }
                catch (ArithmeticException | NumberFormatException bad) { throw new RulesCostFeasibility.Unsupported("invalid mode index integer"); }
            }
        }
        final List<Integer> idx = optIntList(ans, "choices");
        if (idx == null || idx.size() < min || idx.size() > num) {
            refuse("chooseModeForAbility", "bad 'choices' for [" + min + "," + num + "]");
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseModeForAbility(sa, possible, min, num, allowRepeat));
        }
        final List<AbilitySub> picked = new ArrayList<>();
        for (int i : idx) {
            if (i < 0 || i >= possible.size() || (!allowRepeat && picked.contains(possible.get(i)))) {
                refuse("chooseModeForAbility", "mode index out of range/repeat: " + i);
                return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseModeForAbility(sa, possible, min, num, allowRepeat));
            }
            picked.add(possible.get(i));
        }
        if (invocation != null)
            invocation.classify(CallCounter.Ownership.HOST);
        return picked;
    }

    @Override
    public boolean confirmAction(final SpellAbility sa, final PlayerActionConfirmMode mode0, final String message,
            final List<String> options, final Card cardToShow, final Map<String, Object> params) {
        final var invocation = isLiveGame() ? counters.beginCall("confirmAction") : null;
        if (!bridged()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.confirmAction(sa, mode0, message, options, cardToShow, params));
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
        final JsonObject ans = ask("confirmAction", "confirm", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final boolean out = classifiedResult(invocation, CallCounter.Ownership.STOCK, super.confirmAction(sa, mode0, message, options, cardToShow, params));
            echo(e, echoBool("yes", out));
            return out;
        }
        final Boolean yes = optBool(ans, "yes");
        if (yes == null) {
            refuse("confirmAction", "expected boolean 'yes'");
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.confirmAction(sa, mode0, message, options, cardToShow, params));
        }
        return classifiedResult(invocation, CallCounter.Ownership.HOST, yes);
    }

    @Override
    public boolean chooseBinary(final SpellAbility sa, final String question, final BinaryChoiceType kindOfChoice,
            final Boolean defaultChoice) {
        final var invocation = isLiveGame() ? counters.beginCall("chooseBinary") : null;
        if (!bridged()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseBinary(sa, question, kindOfChoice, defaultChoice));
        }
        final JsonObject body = envelope(true);
        body.addProperty("message", String.valueOf(question));
        body.addProperty("binaryKind", String.valueOf(kindOfChoice));
        if (sa != null) {
            body.add("ability", StateEncoder.encodeSpellAbility(sa));
        }
        final JsonObject ans = ask("chooseBinary", "confirm", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final boolean out = classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseBinary(sa, question, kindOfChoice, defaultChoice));
            echo(e, echoBool("yes", out));
            return out;
        }
        final Boolean yes = optBool(ans, "yes");
        if (yes == null) {
            refuse("chooseBinary", "expected boolean 'yes'");
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseBinary(sa, question, kindOfChoice, defaultChoice));
        }
        return classifiedResult(invocation, CallCounter.Ownership.HOST, yes);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(final CardCollection topN) {
        final var invocation = isLiveGame() ? counters.beginCall("arrangeForScry") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.arrangeForScry(topN));
        }
        try {
        requireHostChannel("scry");
        if (topN == null) throw new RulesCostFeasibility.Unsupported("null scry domain");
        if (topN.isEmpty()) {
            if (invocation != null) invocation.classify(CallCounter.Ownership.FORCED);
            return ImmutablePair.of(new CardCollection(), new CardCollection());
        }
        final var originals = new java.util.LinkedHashMap<Integer, Card>();
        for (Card card : topN) {
            if (card == null || card.getGame() != getGame() || originals.put(card.getId(), card) != null)
                throw new RulesCostFeasibility.Unsupported("invalid/duplicate scry object");
        }
        final JsonObject body = envelope(true);
        body.add("menu", StateEncoder.encodeCards(topN));
        final JsonObject ans = ask("arrangeForScry", "scry", body);
        if (ans == null || !ans.has("top") || !ans.get("top").isJsonArray()
                || !ans.has("bottom") || !ans.get("bottom").isJsonArray())
            throw new RulesCostFeasibility.Unsupported("scry requires an explicit top/bottom partition");
        final var top = ans.getAsJsonArray("top");
        final var bottom = ans.getAsJsonArray("bottom");
        if (top.size() + bottom.size() != originals.size())
            throw new RulesCostFeasibility.Unsupported("scry partition size mismatch");
        final CardCollection toTop = new CardCollection();
        final CardCollection toBottom = new CardCollection();
        final var used = new java.util.HashSet<Integer>();
        for (JsonElement raw : top) {
            final Integer fid = hostInteger(raw);
            final Card c = fid == null ? null : originals.get(fid);
            if (c == null || !used.add(fid))
                throw new RulesCostFeasibility.Unsupported("unknown/duplicate/nonintegral scry top card");
            toTop.add(c);
        }
        for (JsonElement raw : bottom) {
            final Integer fid = hostInteger(raw);
            final Card c = fid == null ? null : originals.get(fid);
            if (c == null || !used.add(fid))
                throw new RulesCostFeasibility.Unsupported("unknown/duplicate/nonintegral scry bottom card");
            toBottom.add(c);
        }
        if (invocation != null) invocation.classify(CallCounter.Ownership.HOST);
        return ImmutablePair.of(toTop, toBottom);
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "scry decision", failure);
            throw failure;
        }
    }

    @Override
    public CardCollection orderBlockers(final Card attacker, final CardCollection blockers) {
        final var invocation = isLiveGame() ? counters.beginCall("orderBlockers") : null;
        if (!bridged() || blockers == null || blockers.size() < 2) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.orderBlockers(attacker, blockers));
        }
        final JsonObject body = envelope(true);
        body.add("attacker", StateEncoder.encodeCardUnchecked(attacker));
        body.add("menu", StateEncoder.encodeCards(blockers));
        final JsonObject ans = ask("orderBlockers", "orderBlockers", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final CardCollection out = classifiedResult(invocation, CallCounter.Ownership.STOCK, super.orderBlockers(attacker, blockers));
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
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.orderBlockers(attacker, blockers));
        }
        final CardCollection out = new CardCollection();
        for (int fid : order) {
            final Card c = findCard(blockers, fid);
            if (c == null || out.contains(c)) {
                refuse("orderBlockers", "unknown/duplicate blocker " + fid);
                return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.orderBlockers(attacker, blockers));
            }
            out.add(c);
        }
        return classifiedResult(invocation, CallCounter.Ownership.HOST, out);
    }

    @Override public boolean requiresCombatDamageAssignmentScope() {
        return mode == BenchSession.Mode.BRIDGE && isLiveGame();
    }

    @Override public Map<Card, Integer> assignCombatDamageInScope(CombatDamageAssignment context) {
        if (!requiresCombatDamageAssignmentScope() || context.controller != this || activeCombatDamage != null)
            throw new RulesCostFeasibility.Unsupported("invalid combat damage scope");
        activeCombatDamage = context;
        try {
            return assignCombatDamage(context.source, context.recipients, context.remaining,
                    context.damage, context.defender, context.overrideOrder);
        } finally { activeCombatDamage = null; }
    }

    @Override public void failCombatDamageAssignment(Throwable failure) {
        session.noteIntegrityFailure(getGame(), seat, "combat damage assignment", failure);
        pendingCombatDamage.clear();
    }

    @Override public void finishCombatDamageAssignment(CombatDamageAssignment context, CardDamageTable table) {
        requireHostChannel("completed combat damage assignment");
        if (!context.isComplete()) throw new RulesCostFeasibility.Unsupported("combat damage has not passed native aggregate verification");
        var invocation = pendingCombatDamage.remove(context);
        if (invocation == null) throw new RulesCostFeasibility.Unsupported("unowned combat damage completion");
        JsonObject receipt = new JsonObject(), allocation = new JsonObject();
        if (!context.isDeferred()) for (var e : context.accepted().entrySet())
            allocation.addProperty(e.getKey() == null ? "-1" : String.valueOf(e.getKey().getId()), e.getValue());
        receipt.addProperty("schema", "host-combat-damage-v1");
        receipt.addProperty("scope", "native-aggregate-assignment-before-damage");
        receipt.addProperty("operation", context.isDeferred() ? "defer" : "assign");
        receipt.addProperty("game", session.getGameId()); receipt.addProperty("seat", seat);
        receipt.addProperty("source", context.source.getId()); receipt.add("assign", allocation);
        JsonRpcChannel.log("[bench-combat-damage] " + receipt);
        invocation.classify(CallCounter.Ownership.HOST);
    }

    private static int exactDamageInteger(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("0|[1-9][0-9]*"))
            throw new RulesCostFeasibility.Unsupported("noncanonical combat damage amount");
        try { return Integer.parseInt(value.getAsString()); }
        catch (NumberFormatException failure) { throw new RulesCostFeasibility.Unsupported("combat damage overflow"); }
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(final Card attacker, final CardCollectionView blockers,
            final CardCollectionView remaining, final int damageDealt, final GameEntity defender,
            final boolean overrideOrder) {
        final var invocation = isLiveGame() ? counters.beginCall("assignCombatDamage") : null;
        if (!requiresCombatDamageAssignmentScope()) {
            var result = super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return result;
        }
        try {
            requireHostChannel("combat damage assignment");
            var context = activeCombatDamage;
            if (context == null || context.source != attacker || context.recipients != blockers
                    || context.remaining != remaining || context.damage != damageDealt
                    || context.defender != defender || context.overrideOrder != overrideOrder)
                throw new RulesCostFeasibility.Unsupported("combat damage lacks exact native context");
            context.requireCurrent();
            JsonObject body = envelope(true), lethal = new JsonObject();
            body.addProperty("combatDamageVersion", "host-combat-damage-v1");
            body.add("attacker", StateEncoder.encodeCardUnchecked(attacker));
            body.add("menu", StateEncoder.encodeCards(blockers));
            body.addProperty("damage", damageDealt); body.addProperty("overrideOrder", overrideOrder);
            body.addProperty("dividedAsChosen", context.dividedAsChosen);
            body.addProperty("allowExcessToDefender", context.allowDefender);
            body.addProperty("mayDefer", context.mayDefer());
            body.addProperty("defenderId", defender == null ? -1 : defender.getId());
            if (defender != null) {
                body.add("defender", StateEncoder.entityRef(defender));
                body.addProperty("defenderKind", defender instanceof Player ? "player" : "card");
            }
            for (Card c : blockers) lethal.addProperty(String.valueOf(c.getId()), context.remainingLethal(c));
            body.add("remainingLethal", lethal);
            var answer = ask("assignCombatDamage", "assignDamage", body);
            requireHostChannel("combat damage answer");
            if (answer == null) throw new RulesCostFeasibility.Unsupported("combat damage host delegated/disconnected");
            if (answer.has("defer")) {
                if (!answer.get("defer").isJsonPrimitive() || !answer.get("defer").getAsJsonPrimitive().isBoolean()
                        || !answer.get("defer").getAsBoolean() || answer.has("assign"))
                    throw new RulesCostFeasibility.Unsupported("invalid combat damage defer");
                context.accept(null);
                pendingCombatDamage.put(context, invocation); // The phase must actually finish before this is certified.
                return null;
            }
            if (!answer.has("assign") || !answer.get("assign").isJsonObject())
                throw new RulesCostFeasibility.Unsupported("missing combat damage allocation");
            Map<Card, Integer> proposed = new LinkedHashMap<>();
            for (var e : answer.getAsJsonObject("assign").entrySet()) {
                Card target = null;
                if (!e.getKey().equals("-1")) {
                    if (!e.getKey().matches("0|[1-9][0-9]*"))
                        throw new RulesCostFeasibility.Unsupported("noncanonical combat recipient");
                    int id;
                    try { id = Integer.parseInt(e.getKey()); }
                    catch (NumberFormatException invalid) { throw new RulesCostFeasibility.Unsupported("combat recipient overflow"); }
                    for (Card c : blockers) if (c.getId() == id) target = c;
                    if (target == null) throw new RulesCostFeasibility.Unsupported("unknown combat recipient");
                }
                proposed.put(target, exactDamageInteger(e.getValue()));
            }
            var result = context.accept(proposed);
            pendingCombatDamage.put(context, invocation);
            return result; // HOST classification waits for actual aggregate verification in Combat.
        } catch (RuntimeException | Error failure) {
            failCombatDamageAssignment(failure); throw failure;
        }
    }

    // ------------------------------------------------------ counted delegations
    // Generated from the abstract surface of PlayerController: every remaining entry
    // point increments its own counter and delegates. This is the decision-surface
    // instrumentation; behaviour is byte-for-byte PlayerControllerAi.

    /** Ordinary mandatory triggers use engine target setup, not Default's
     * private strategic preparation. Enclosing rules callbacks receive ownership
     * only after successful execution and independent accounting of every child.
     */
    @Override
    protected boolean prepareSingleSa(final Card host, SpellAbility sa, boolean isMandatory) {
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame())
            return super.prepareSingleSa(host, sa, isMandatory);
        if (session.getChannel().isClosed())
            throw new RulesCostFeasibility.Unsupported("trigger preparation requires an open host channel");
        if (sa == null || sa.getHostCard() != host || sa.getActivatingPlayer() != getPlayer())
            throw new RulesCostFeasibility.Unsupported("trigger preparation actor/source identity mismatch");
        if (sa.isOptionalTrigger()) {
            if (OptionalManaTriggerExecution.hasPaidUnderlying(sa)) OptionalManaTriggerExecution.require(getPlayer(), sa);
            else OptionalZeroTriggerExecution.require(getPlayer(), sa);
        }
        else if (!isMandatory || (sa instanceof WrappedAbility wrapper && wrapper.getDecider() != null))
            throw new RulesCostFeasibility.Unsupported("unowned trigger confirmation");
        for (SpellAbility current = sa; current != null; current = current.getSubAbility()) {
            boolean scopedRepeat = current == sa && sa.isOptionalTrigger()
                    && OptionalManaTriggerExecution.hasPaidUnderlying(sa) && OptionalManaTriggerExecution.repeated(sa);
            if (current.getApi() == forge.game.ability.ApiType.Charm || (current.hasParam("Announce") && !scopedRepeat) || current.costHasX())
                throw new RulesCostFeasibility.Unsupported("modal/announced trigger preparation is not yet host controlled");
        }
        final boolean previous = selectingExternalTargets;
        final boolean previousOptional = preparingOptionalTrigger;
        preparingOptionalTrigger = sa.isOptionalTrigger();
        selectingExternalTargets = true; // existing target refusals must fail closed, never call the AI
        try {
            // Forge walks subabilities, TargetingPlayer and MustTarget here.
            // A failed setup is explicit unsupported coverage, not a silently
            // dropped mandatory trigger. No outer RULES label is earned here.
            if (!sa.setupTargets())
                throw new RulesCostFeasibility.Unsupported("ordinary trigger target setup failed");
            counters.instrument("hostTrigger.targetsPrepared");
            return true;
        } catch (NoLegalOptionalTriggerTarget empty) {
            counters.instrument("hostTrigger.noLegalTarget");
            return false;
        } finally { selectingExternalTargets = previous; preparingOptionalTrigger = previousOptional; }
    }

    // Inherited public AI entry points bypassed the old abstract-surface list.
    // Count the actual invocation, but do not claim forced/host ownership merely
    // because a particular input happens to yield a constant answer.
    @Override
    public boolean acceptsDrawOffer() { return stockCall("acceptsDrawOffer", () -> super.acceptsDrawOffer()); }
    @Override
    public CardCollectionView cheatShuffle(CardCollectionView cards) {
        final var invocation = isLiveGame() ? counters.beginCall("cheatShuffle") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) {
            var result = super.cheatShuffle(cards);
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return result;
        }
        try {
            requireHostChannel("shuffle completion");
            if (cards == null || getGame().getRules().isAllowCheatShuffle())
                throw new RulesCostFeasibility.Unsupported("benchmark requires unmodified engine shuffle");
            // Player.shuffle already shuffled once with the engine RNG. This
            // callback must not inspect/reorder the library or consult an AI.
            if (invocation != null) invocation.classify(CallCounter.Ownership.RULES);
            return cards;
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "shuffle completion", failure);
            throw failure;
        }
    }
    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kind, Map<String, Object> params) {
        return stockCall("chooseBinary", () -> super.chooseBinary(sa, question, kind, params));
    }
    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max, Map<String, Object> params) {
        return stockCall("chooseNumber", () -> super.chooseNumber(sa, title, min, max, params));
    }
    @Override
    public void setupAutoProfile(Deck deck) {
        final var invocation = isLiveGame() ? counters.beginCall("setupAutoProfile") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) { super.setupAutoProfile(deck);
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return; }
        try {
            requireHostChannel("profile notification");
            // A full-control host is not a Default combat policy. Do not infer
            // or configure Forge aggression from its registered deck.
            if (invocation != null) invocation.classify(CallCounter.Ownership.RULES);
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "profile notification", failure); throw failure;
        }
    }
    @Override
    public Map<DeckSection, List<? extends PaperCard>> complainCardsCantPlayWell(Deck deck) {
        final var invocation = isLiveGame() ? counters.beginCall("complainCardsCantPlayWell") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.complainCardsCantPlayWell(deck));
        try {
            requireHostChannel("deck diagnostic notification");
            // Match uses this only for warning broadcasts. Calling super also
            // configures Default's combat profile, which does not own this seat.
            if (invocation != null) invocation.classify(CallCounter.Ownership.RULES);
            return Collections.emptyMap();
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "deck diagnostic notification", failure); throw failure;
        }
    }
    @Override
    public void resetAtEndOfTurn() {
        final var invocation = isLiveGame() ? counters.beginCall("resetAtEndOfTurn") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) { super.resetAtEndOfTurn();
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return; }
        try {
            requireHostChannel("end-turn scratch cleanup");
            // Clear transient reservations, not the separate revealed-hand ledger.
            getAi().getCardMemory().clearAllRemembered();
            if (invocation != null) invocation.classify(CallCounter.Ownership.RULES);
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "end-turn scratch cleanup", failure); throw failure;
        }
    }

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) { return stockCall("getAbilityToPlay", () -> super.getAbilityToPlay(hostCard, abilities, triggerEvent)); }
    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean mayChoseNewTargets) {
        final var invocation = isLiveGame() ? counters.beginCall("playSpellAbilityNoStack") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) {
            super.playSpellAbilityNoStack(effectSA, mayChoseNewTargets);
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return;
        }
        try {
        requireHostChannel("trigger no-stack execution");
        if (activeReplacementExecution != null && effectSA.getReplacementEffect() != null) {
            if (session.getChannel().isClosed())
                throw new RulesCostFeasibility.Unsupported("replacement execution requires open host channel");
            activeReplacementExecution.consume(getPlayer(),effectSA,mayChoseNewTargets);
            var payment = new MandatoryZeroTriggerExecution(getPlayer(),effectSA,true,activeReplacementExecution);
            prepareSingleSa(effectSA.getHostCard(),effectSA,true);
            var previous = activeZeroTriggerPayment; activeZeroTriggerPayment=payment;
            try { payment.payCost(); } finally { activeZeroTriggerPayment=previous; }
            counters.instrument("hostReplacement.rulesNoStackExecution");
            forge.game.ability.AbilityUtils.resolve(effectSA);
            requireHostChannel("completed replacement execution");
            if (invocation != null) invocation.classifyRulesIfChildrenAccounted();
            return;
        }
        requireZeroTrigger(effectSA);
        if (effectSA.isOptionalTrigger()) {
            if (mayChoseNewTargets || (activeOptionalResolution==null && activeOptionalManaResolution==null))
                throw new RulesCostFeasibility.Unsupported("optional effect requires exact native resolution authorization");
            if (activeOptionalManaResolution != null) activeOptionalManaResolution.consume(getPlayer(), effectSA);
            else activeOptionalResolution.consume(getPlayer(),effectSA);
        }
        if (mayChoseNewTargets) prepareSingleSa(effectSA.getHostCard(), effectSA, true);
        if (activeOptionalManaResolution != null && activeOptionalManaResolution.repeated()) {
            do {
                payZeroTrigger(effectSA, true);
                activeOptionalManaResolution.paymentCompleted();
            } while (confirmTrigger(activeOptionalManaResolution.wrapper()));
            activeOptionalManaResolution.finishRepeatedPayments();
        } else payZeroTrigger(effectSA, true);
        counters.instrument("hostTrigger.rulesNoStackExecution");
        // Only an actual color choice belongs to the queued-trigger host
        // choice scope. Fixed-color mana triggers (e.g. Wild Growth) resolve
        // natively and may run immediately with an empty ordinary stack.
        if (effectSA.getApi() == forge.game.ability.ApiType.Mana && activeRulesPayment == null
                && effectSA.getManaPart() != null && "Any".equals(effectSA.getManaPart().getOrigProduced())) {
            var previous = activeTriggeredManaChoice;
            try (var scope = new TriggeredManaChoice(getPlayer(), effectSA)) {
                activeTriggeredManaChoice = scope;
                forge.game.ability.AbilityUtils.resolve(effectSA);
                scope.finish();
            } finally { activeTriggeredManaChoice = previous; }
        } else forge.game.ability.AbilityUtils.resolve(effectSA);
        requireHostChannel("completed trigger no-stack execution");
        if (invocation != null) invocation.classifyRulesIfChildrenAccounted();
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "trigger no-stack execution", failure);
            throw failure;
        }
        // Unknown/stock effect callbacks leave this enclosing call unclassified.
    }
    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        final var invocation = isLiveGame() ? counters.beginCall("orderSimultaneousSa") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.orderSimultaneousSa(activePlayerSAs));
        try {
            if (activePlayerSAs == null) throw new RulesCostFeasibility.Unsupported("null pending trigger list");
            if (activePlayerSAs.size()<2) {
                if (invocation != null) invocation.classify(CallCounter.Ownership.FORCED);
                return activePlayerSAs;
            }
            var order = new TriggerOrderChoices(getPlayer(), activePlayerSAs);
            while (order.needsChoice()) {
                var request=envelope(true);
                for(var field:order.request().entrySet())request.add(field.getKey(),field.getValue());
                order.choose(ask("orderSimultaneousSa","triggerOrder",request));
            }
            var result=order.finish();
            if (invocation != null) invocation.classify(CallCounter.Ownership.HOST);
            return result;
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "simultaneous trigger ordering", failure);
            throw failure;
        }
    }
    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        final var invocation = isLiveGame() ? counters.beginCall("orderAndPlaySimultaneousSa") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) {
            super.orderAndPlaySimultaneousSa(activePlayerSAs);
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return;
        }
        try {
        requireHostChannel("simultaneous trigger execution");
        // Reject already-known unsupported costs/preparation in the entire
        // batch before asking its order or inserting any earlier trigger.
        // Actual targets still must be selected at each insertion, when the
        // previous triggers are on the stack; a later host failure invalidates
        // the game, it is not a transaction rollback of those legal choices.
        if (activePlayerSAs == null) throw new RulesCostFeasibility.Unsupported("null pending trigger list");
        for (var ability : activePlayerSAs) requireZeroTrigger(ability);
        for (var ability : orderSimultaneousSa(activePlayerSAs)) {
            requireZeroTrigger(ability);
            if (!prepareSingleSa(ability.getHostCard(), ability, true)) continue;
            payZeroTrigger(ability, false);
            getGame().getStack().add(ability);
            counters.instrument("hostTrigger.rulesStackInsertion");
        }
        requireHostChannel("completed simultaneous trigger execution");
        if (invocation != null) invocation.classifyRulesIfChildrenAccounted();
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "simultaneous trigger execution", failure);
            throw failure;
        }
        // Target/order children retain independent receipts; unknowns block ours.
    }

    private MandatoryZeroTriggerExecution activeZeroTriggerPayment;
    private RulesReplacementExecution activeReplacementExecution;

    @Override public void withReplacementExecutionScope(forge.game.replacement.ReplacementEffect replacement,
            SpellAbility ability, Runnable nativeExecution) {
        if (mode!=BenchSession.Mode.BRIDGE || !isLiveGame()) { nativeExecution.run(); return; }
        var previous=activeReplacementExecution;
        try (var scope=new RulesReplacementExecution(getPlayer(),replacement,ability)) {
            activeReplacementExecution=scope;
            nativeExecution.run();
            scope.finish();
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(),seat,"replacement execution",failure);
            throw failure;
        } finally { activeReplacementExecution=previous; }
    }
    private OptionalZeroTriggerExecution activeOptionalTriggerPayment;
    private OptionalZeroTriggerExecution.Resolution activeOptionalResolution;
    private OptionalManaTriggerExecution.Resolution activeOptionalManaResolution;
    private OptionalManaTriggerExecution.WrapperPayment activeOptionalWrapperPayment;
    private TriggeredManaChoice activeTriggeredManaChoice;
    private boolean failedOptionalResolution;
    private boolean preparingOptionalTrigger;
    private static final class NoLegalOptionalTriggerTarget extends RuntimeException {}

    @Override public boolean requiresTriggerResolutionScope(WrappedAbility ability) {
        return mode==BenchSession.Mode.BRIDGE && isLiveGame() && ability.isOptionalTrigger();
    }
    @Override public void withTriggerResolutionScope(WrappedAbility ability, Runnable nativeResolution) {
        if (!requiresTriggerResolutionScope(ability)) { nativeResolution.run(); return; }
        var previous=activeOptionalResolution;
        var previousPaid=activeOptionalManaResolution;
        try {
            if (session.getChannel().isClosed()) throw new RulesCostFeasibility.Unsupported("optional resolution requires open host");
            if (OptionalManaTriggerExecution.hasPaidUnderlying(ability)) {
                try (var scope = new OptionalManaTriggerExecution.Resolution(getPlayer(), ability)) {
                    activeOptionalResolution = null; activeOptionalManaResolution = scope;
                    nativeResolution.run(); scope.finish();
                }
            } else try (var scope=new OptionalZeroTriggerExecution.Resolution(getPlayer(),ability)) {
                activeOptionalManaResolution = null;
                activeOptionalResolution=scope;
                nativeResolution.run();
                scope.finish();
            }
        } catch (RuntimeException | Error failure) {
            failedOptionalResolution=true;
            session.noteIntegrityFailure(getGame(), seat, "optional trigger resolution", failure);
            throw failure;
        }
        finally { activeOptionalResolution=previous; activeOptionalManaResolution=previousPaid; }
    }

    private void requireZeroTrigger(SpellAbility ability) {
        if (session.getChannel().isClosed())
            throw new RulesCostFeasibility.Unsupported("mandatory trigger execution requires an open host channel");
        if (ability.isOptionalTrigger()) {
            if (OptionalManaTriggerExecution.hasPaidUnderlying(ability)) OptionalManaTriggerExecution.require(getPlayer(), ability);
            else OptionalZeroTriggerExecution.require(getPlayer(),ability);
        }
        else MandatoryZeroTriggerExecution.require(getPlayer(), ability);
    }

    private void payZeroTrigger(SpellAbility ability, boolean effect) {
        if (ability.isOptionalTrigger() && OptionalManaTriggerExecution.hasPaidUnderlying(ability)) {
            if (ability instanceof WrappedAbility wrapper && !effect) {
                var payment = new OptionalManaTriggerExecution.WrapperPayment(getPlayer(), wrapper);
                var previous = activeOptionalWrapperPayment; activeOptionalWrapperPayment = payment;
                try { payment.payCost(); } finally { activeOptionalWrapperPayment = previous; }
            } else {
                if (!effect || activeOptionalManaResolution == null)
                    throw new RulesCostFeasibility.Unsupported("paid trigger outside native accepted resolution");
                activeOptionalManaResolution.requirePayment(getPlayer(), ability);
                var assessment = RulesCostFeasibility.assess(getPlayer(), ability, null, activeOptionalManaResolution);
                if (assessment.status() != RulesCostFeasibility.Status.PAYABLE || assessment.space() == null)
                    throw new RulesCostFeasibility.Unsupported("accepted trigger payment unavailable: " + assessment.reason());
                var domain = new RulesPaymentDomain(assessment.space(), getPlayer().getLife());
                final JsonObject request = envelope(true);
                for (var entry : domain.request().entrySet()) request.add(entry.getKey(), entry.getValue());
                request.add("selectedAbility", StateEncoder.encodeSpellAbility(ability, getPlayer().getView()));
                request.addProperty("paymentContext", "optional-trigger-resolution-v1");
                if (activeOptionalManaResolution.repeated()) {
                    request.addProperty("paymentContext", "repeated-trigger-resolution-v1");
                    request.addProperty("completedPayments", activeOptionalManaResolution.completed());
                }
                var answer = ask("payManaCost", "payment", request);
                var payment = new RulesPaymentExecutor(getPlayer(), ability, domain.select(answer), null, activeOptionalManaResolution);
                var previous = activeRulesPayment; activeRulesPayment = payment;
                try {
                    if (!new CostPayment(ability.getPayCosts(), ability).payComputerCosts(payment.decisions(ability)))
                        throw new RulesCostFeasibility.Unsupported("native optional trigger payment failed");
                    payment.assertPaid();
                } finally { activeRulesPayment = previous; }
                counters.instrument("hostTrigger.rulesPaidResolution");
            }
            return;
        }
        if (ability.isOptionalTrigger()) {
            var payment=new OptionalZeroTriggerExecution(getPlayer(),ability,effect);
            var previous=activeOptionalTriggerPayment; activeOptionalTriggerPayment=payment;
            try { payment.payCost(); } finally { activeOptionalTriggerPayment=previous; }
            return;
        }
        var payment = new MandatoryZeroTriggerExecution(getPlayer(), ability, effect);
        var previous = activeZeroTriggerPayment;
        activeZeroTriggerPayment = payment;
        try { payment.payCost(); }
        finally { activeZeroTriggerPayment = previous; }
    }
    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        final var invocation = isLiveGame() ? counters.beginCall("playTrigger") : null;
        if (mode == BenchSession.Mode.BRIDGE && isLiveGame()) {
            try {
            requireHostChannel("static trigger execution");
            requireZeroTrigger(wrapperAbility);
            if (!isMandatory || host != wrapperAbility.getHostCard() || !wrapperAbility.getTrigger().isStatic())
                throw new RulesCostFeasibility.Unsupported("static trigger is not mandatory or source identity changed");
            java.util.function.BooleanSupplier execute = () -> {
                prepareSingleSa(host, wrapperAbility, true);
                payZeroTrigger(wrapperAbility, true);
                counters.instrument("hostTrigger.rulesStaticExecution");
                forge.game.ability.AbilityUtils.resolve(wrapperAbility);
                return true;
            };
            final boolean result = activeRulesPayment != null
                ? activeRulesPayment.duringMandatoryTrigger(host, wrapperAbility, isMandatory, execute)
                : execute.getAsBoolean();
            requireHostChannel("completed static trigger execution");
            if (invocation != null) invocation.classifyRulesIfChildrenAccounted();
            return result;
            } catch (RuntimeException | Error failure) {
                session.noteIntegrityFailure(getGame(), seat, "static trigger execution", failure);
                throw failure;
            }
        }
        if (activeRulesPayment != null) return activeRulesPayment.duringMandatoryTrigger(host, wrapperAbility, isMandatory,
                () -> super.playTrigger(host, wrapperAbility, isMandatory));
        return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.playTrigger(host, wrapperAbility, isMandatory));
    }
    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) { return stockCall("playSaFromPlayEffect", () -> super.playSaFromPlayEffect(tgtSA)); }
    @Override
    public List<PaperCard> sideboard(final Deck deck, GameType gameType, String message) { return stockCall("sideboard", () -> super.sideboard(deck, gameType, message)); }
    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) { return stockCall("chooseCardsYouWonToAddToDeck", () -> super.chooseCardsYouWonToAddToDeck(losses)); }
    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) { return stockCall("divideShield", () -> super.divideShield(effectSource, affected, shieldAmount)); }
    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) { return stockCall("specifyManaCombo", () -> super.specifyManaCombo(sa, colorSet, manaAmount, different)); }
    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) { return stockCall("choosePermanentsToDestroy", () -> super.choosePermanentsToDestroy(sa, min, max, validTargets, message)); }
    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) { return stockCall("announceRequirements", () -> super.announceRequirements(ability, min, max, announce)); }
    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) { return stockCall("chooseNewTargetsFor", () -> super.chooseNewTargetsFor(ability, filter, optional)); }
    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) { return stockCall("chooseTarget", () -> super.chooseTarget(sa, allTargets)); }
    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) { return stockCall("helpPayForAssistSpell", () -> super.helpPayForAssistSpell(cost, sa, max, requested)); }
    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) { return stockCall("choosePlayerToAssistPayment", () -> super.choosePlayerToAssistPayment(optionList, sa, title, max)); }
    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) { return stockCall("chooseCardsForEffectMultiple", () -> super.chooseCardsForEffectMultiple(validMap, sa, title, isOptional)); }
    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num, Map<String, Object> params) { return stockCall("chooseSpellAbilitiesForEffect", () -> super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params)); }
    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) { return stockCall("chooseSingleSpellForEffect", () -> super.chooseSingleSpellForEffect(spells, sa, title, params)); }
    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string, int bid, Player winner) { return stockCall("confirmBidAction", () -> super.confirmBidAction(sa, bidlife, string, bid, winner)); }
    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) { return stockCall("confirmReplacementEffect", () -> super.confirmReplacementEffect(replacementEffect, effectSA, affected, question)); }
    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) { return stockCall("confirmStaticApplication", () -> super.confirmStaticApplication(hostCard, mode, message, logic)); }
    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        final var invocation=isLiveGame()?counters.beginCall("confirmTrigger"):null;
        if(mode!=BenchSession.Mode.BRIDGE || !isLiveGame()) {
            final boolean out=super.confirmTrigger(sa);
            if(invocation!=null)invocation.classify(CallCounter.Ownership.STOCK);
            return out;
        }
        if((activeOptionalResolution==null && activeOptionalManaResolution==null) || session.getChannel().isClosed())
            throw new RulesCostFeasibility.Unsupported("optional confirmation outside actual resolution");
        SpellAbility displayed = sa;
        if (activeOptionalManaResolution != null) {
            activeOptionalManaResolution.requireConfirmation(getPlayer(), sa);
            displayed = activeOptionalManaResolution.ability();
            var assessment = RulesCostFeasibility.assess(getPlayer(), displayed, null, activeOptionalManaResolution);
            if (assessment.status() == RulesCostFeasibility.Status.UNSUPPORTED)
                throw new RulesCostFeasibility.Unsupported("optional trigger affordability unknown: " + assessment.reason());
            if (assessment.status() == RulesCostFeasibility.Status.UNPAYABLE) {
                activeOptionalManaResolution.answer(false);
                if (invocation != null) invocation.classify(CallCounter.Ownership.FORCED);
                return false;
            }
        } else activeOptionalResolution.requireConfirmation(getPlayer(),sa);
        final JsonObject body=envelope(true);
        body.addProperty("mode","Trigger"); body.addProperty("message",sa.getDescription());
        body.add("ability",StateEncoder.encodeSpellAbility(displayed,getPlayer().getView()));
        if (activeOptionalManaResolution != null && activeOptionalManaResolution.repeated()) {
            body.addProperty("resolutionPaymentVersion", "native-repeated-mana-v1");
            body.addProperty("unitCost", activeOptionalManaResolution.manaCost(getPlayer(), displayed).toString());
            body.addProperty("completedPayments", activeOptionalManaResolution.completed());
        }
        final JsonObject answer=ask("confirmTrigger","confirm",body);
        final JsonElement yes=answer==null?null:answer.get("yes");
        if(yes==null || !yes.isJsonPrimitive() || !yes.getAsJsonPrimitive().isBoolean())
            throw new RulesCostFeasibility.Unsupported("explicit boolean trigger confirmation required");
        if (activeOptionalManaResolution != null) activeOptionalManaResolution.answer(yes.getAsBoolean());
        else activeOptionalResolution.answer(yes.getAsBoolean());
        if(invocation!=null)invocation.classify(CallCounter.Ownership.HOST);
        return yes.getAsBoolean();
    }
    @Override
    public List<Card> exertAttackers(List<Card> attackers) { return stockCall("exertAttackers", () -> super.exertAttackers(attackers)); }
    @Override
    public List<Card> enlistAttackers(List<Card> attackers) { return stockCall("enlistAttackers", () -> super.enlistAttackers(attackers)); }
    @Override
    public CardCollection orderBlocker(final Card attacker, final Card blocker, final CardCollection oldBlockers) { return stockCall("orderBlocker", () -> super.orderBlocker(attacker, blocker, oldBlockers)); }
    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) { return stockCall("orderAttackers", () -> super.orderAttackers(blocker, attackers)); }
    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addMsgSuffix) {
        final var invocation = isLiveGame() ? counters.beginCall("reveal") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) {
            super.reveal(cards, zone, owner, messagePrefix, addMsgSuffix);
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return;
        }
        try {
            requireHostChannel("reveal");
            if (zone == ZoneType.Hand) {
                knownHand.remember(cards, owner);
                rememberClosedHandReveal(cards, owner);
            }
            revealHistory.remember(cards, zone, owner);
            // The hand path also preserves current authorized characteristics.
            // Names-only non-hand history is partial observation support: it
            // must remain untrusted until richer reveal semantics are verified.
            if (zone == ZoneType.Hand && invocation != null) invocation.classify(CallCounter.Ownership.RULES);
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "reveal", failure); throw failure;
        }
    }
    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix, boolean addMsgSuffix) {
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) {
            final var invocation = isLiveGame() ? counters.beginCall("reveal") : null;
            super.reveal(cards, zone, owner, messagePrefix, addMsgSuffix);
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return;
        }
        if (zone != ZoneType.Hand) {
            final var invocation = counters.beginCall("reveal");
            try {
                requireHostChannel("reveal views");
                revealHistory.rememberViews(cards, zone, owner);
                // Deliberately UNCLASSIFIED: names-only delivery is not a
                // complete ordered/characteristic observation certificate.
            } catch (RuntimeException | Error failure) {
                session.noteIntegrityFailure(getGame(), seat, "reveal views", failure); throw failure;
            }
            return;
        }
        var player = getGame().getPlayers().stream().filter(p -> p.getView() == owner).findFirst().orElse(null);
        var live = new CardCollection();
        try {
            if (cards == null || player == null) throw new RulesCostFeasibility.Unsupported("invalid hand reveal views");
            for (var view : cards) {
                var card = view == null ? null : getGame().findByView(view);
                if (card == null || card.getView() != view)
                    throw new RulesCostFeasibility.Unsupported("hand reveal view lacks an exact live card");
                live.add(card);
            }
        } catch (RuntimeException | Error failure) {
            count("reveal"); session.noteIntegrityFailure(getGame(), seat, "hand reveal views", failure); throw failure;
        }
        reveal(live, zone, player, messagePrefix, addMsgSuffix);
    }
    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject realtedTarget, String value) { final var invocation = isLiveGame() ? counters.beginCall("notifyOfValue") : null; super.notifyOfValue(saSource, realtedTarget, value); if (invocation != null) invocation.classify(CallCounter.Ownership.RULES); }
    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) { return stockCall("arrangeForSurveil", () -> super.arrangeForSurveil(topN)); }
    @Override
    public boolean willPutCardOnTop(Card c) { return stockCall("willPutCardOnTop", () -> super.willPutCardOnTop(c)); }
    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        final var invocation = isLiveGame() ? counters.beginCall("orderMoveToZoneList") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.orderMoveToZoneList(cards, destinationZone, source));
        try {
            if (cards == null) throw new RulesCostFeasibility.Unsupported("null zone-order list");
            if (cards.size() < 2) {
                if (invocation != null) invocation.classify(CallCounter.Ownership.FORCED);
                return cards;
            }
            if (session.getChannel().isClosed()) throw new RulesCostFeasibility.Unsupported("zone-order channel closed");
            var ordered = bridgedOrderMoveToZoneList(cards, destinationZone, source);
            if (invocation != null) invocation.classify(CallCounter.Ownership.HOST);
            return ordered;
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "zone ordering", failure);
            throw failure;
        }
    }
    @Override
    public CardCollection chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        final var invocation = isLiveGame() ? counters.beginCall("chooseCardsToDiscardFrom") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) {
            var result = super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser);
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return result;
        }
        try {
            if (session.integrityFailure(getGame()) != null || !bridged())
                throw new RulesCostFeasibility.Unsupported("discard decision lacks live unfailed host");
            if (playerDiscard == null || playerDiscard.getGame() != getGame() || sa == null
                    || sa.getApi() != forge.game.ability.ApiType.Discard || validCards == null || visibleToChooser == null
                    || min < 0 || max < min || max > validCards.size())
                throw new RulesCostFeasibility.Unsupported("invalid discard decision domain");
            final String discardMode = sa.getParamOrDefault("Mode", "TgtChoose");
            if (playerDiscard != getPlayer() && !discardMode.startsWith("Look") && !discardMode.startsWith("Reveal"))
                throw new RulesCostFeasibility.Unsupported("foreign hand discard lacks explicit visibility grant");
            final Card source = sa.getHostCard();
            if (source == null || source.isFaceDown() || source.getGame() != getGame()
                    || !(source.isInZone(ZoneType.Stack) || source.isInZone(ZoneType.Battlefield) || source.isInZone(ZoneType.Graveyard)))
                throw new RulesCostFeasibility.Unsupported("discard source is not publicly identified");
            final List<Card> domain = List.copyOf(validCards), visible = List.copyOf(visibleToChooser);
            final var params = java.util.Map.copyOf(sa.getMapParams());
            var identities = new java.util.HashSet<Integer>();
            for (Card c : visible) {
                if (c == null || !identities.add(c.getId()) || c.getOwner() != playerDiscard
                        || !c.isInZone(ZoneType.Hand) || playerDiscard.getCardsIn(ZoneType.Hand).stream().noneMatch(live -> live == c))
                    throw new RulesCostFeasibility.Unsupported("invalid visible discard hand");
            }
            identities.clear();
            for (Card c : domain) if (!identities.add(c.getId()) || visible.stream().noneMatch(v -> v == c))
                throw new RulesCostFeasibility.Unsupported("discard domain not contained in visible hand");
            if (max == 0 || min == domain.size() && max == domain.size()) {
                if (invocation != null) invocation.classify(CallCounter.Ownership.FORCED);
                return max == 0 ? new CardCollection() : new CardCollection(domain);
            }
            final JsonObject body = envelope(true);
            body.addProperty("reason", "discard");
            body.addProperty("discardVersion", "native-discard-v1");
            body.addProperty("discardingSeat", getGame().getPlayers().indexOf(playerDiscard));
            body.addProperty("discardMode", discardMode);
            body.addProperty("discardCount", sa.getParamOrDefault("NumCards", "1"));
            body.addProperty("discardValid", sa.getParamOrDefault("DiscardValid", "Card"));
            body.addProperty("discardOptional", sa.hasParam("Optional"));
            body.addProperty("discardAnyNumber", sa.hasParam("AnyNumber"));
            body.addProperty("min", min); body.addProperty("max", max);
            body.add("menu", StateEncoder.encodeCards(domain));
            body.add("visibleCards", StateEncoder.encodeCards(visible));
            body.add("discardSource", StateEncoder.encodeCardUnchecked(source));
            body.add("ability", StateEncoder.encodeSpellAbility(sa));
            final JsonObject answer = ask("chooseCardsToDiscardFrom", "cardsChoice", body);
            if (answer == null || !answer.has("choices") || !answer.get("choices").isJsonArray())
                throw new RulesCostFeasibility.Unsupported("discard requires explicit host card selection");
            if (sa.getHostCard() != source || !params.equals(sa.getMapParams()))
                throw new RulesCostFeasibility.Unsupported("discard source changed during decision");
            for (Card c : visible) if (playerDiscard.getCardsIn(ZoneType.Hand).stream().noneMatch(live -> live == c))
                throw new RulesCostFeasibility.Unsupported("discard hand changed during decision");
            final CardCollection selected = new CardCollection();
            for (var entry : answer.getAsJsonArray("choices")) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isNumber()
                        || !entry.getAsString().matches("0|[1-9][0-9]*"))
                    throw new RulesCostFeasibility.Unsupported("discard requires integer card IDs");
                int fid = Integer.parseInt(entry.getAsString());
                Card found = domain.stream().filter(c -> c.getId() == fid).findFirst().orElse(null);
                if (found == null || selected.contains(found))
                    throw new RulesCostFeasibility.Unsupported("discard has unknown or duplicate card ID");
                selected.add(found);
            }
            if (selected.size() < min || selected.size() > max)
                throw new RulesCostFeasibility.Unsupported("discard selection outside cardinality");
            if (invocation != null) invocation.classify(CallCounter.Ownership.HOST);
            return selected;
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "discard selection", failure);
            throw failure;
        }
    }
    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand, String[] unlessTypes, SpellAbility sa) { return stockCall("chooseCardsToDiscardUnlessType", () -> super.chooseCardsToDiscardUnlessType(min, hand, unlessTypes, sa)); }
    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) { return stockCall("chooseCardsToDelve", () -> super.chooseCardsToDelve(genericAmount, grave)); }
    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) { return stockCall("chooseCardsForConvokeOrImprovise", () -> super.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction)); }
    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) { return stockCall("chooseCardsForSplice", () -> super.chooseCardsForSplice(sa, cards)); }
    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) { return stockCall("chooseCardsToRevealFromHand", () -> super.chooseCardsToRevealFromHand(min, max, valid)); }
    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) { return stockCall("chooseSaToActivateFromOpeningHand", () -> super.chooseSaToActivateFromOpeningHand(usableFromOpeningHand)); }
    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) { return stockCall("chooseStartingHand", () -> super.chooseStartingHand(zones)); }
    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) { return stockCall("chooseManaFromPool", () -> super.chooseManaFromPool(manaChoices)); }
    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) { return stockCall("chooseSomeType", () -> super.chooseSomeType(kindOfType, sa, validTypes, isOptional)); }
    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) { return stockCall("chooseSector", () -> super.chooseSector(assignee, ai, sectors)); }
    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) { return stockCall("chooseContraptionsToCrank", () -> super.chooseContraptionsToCrank(contraptions)); }
    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) { return stockCall("chooseSprocket", () -> super.chooseSprocket(assignee, sprockets)); }
    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) { return stockCall("choosePDRollToIgnore", () -> super.choosePDRollToIgnore(rolls)); }
    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) { return stockCall("chooseRollToIgnore", () -> super.chooseRollToIgnore(rolls)); }
    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) { return stockCall("chooseDiceToReroll", () -> super.chooseDiceToReroll(rolls)); }
    @Override
    public Integer chooseRollToModify(List<Integer> rolls) { return stockCall("chooseRollToModify", () -> super.chooseRollToModify(rolls)); }
    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) { return stockCall("chooseRollToSwap", () -> super.chooseRollToSwap(rolls)); }
    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness) { return stockCall("chooseRollSwapValue", () -> super.chooseRollSwapValue(swapChoices, currentResult, power, toughness)); }
    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) { return stockCall("vote", () -> super.vote(sa, prompt, options, votes, forPlayer, optional)); }
    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        final var invocation = isLiveGame() ? counters.beginCall("playChosenSpellAbility") : null;
        if (failedExternalAction) throw new RulesCostFeasibility.Unsupported("prior controlled action failed; game cannot continue");
        if (pendingExternalAbility == null) return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.playChosenSpellAbility(sa));
        final JsonObject selectedAnswer = pendingExternalAnswer;
        try {
            if (pendingExternalAbility != sa) throw new RulesCostFeasibility.Unsupported("selected/executed ability identity mismatch");
            pendingExternalAbility = null;
            pendingExternalAnswer = null;
            if (sa.isLandAbility()) {
                if (!sa.canPlay()) throw new RulesCostFeasibility.Unsupported("selected land no longer playable");
                BenchActionAudit.selected(getGame(), seat, sa, selectedAnswer);
                int id = sa.getHostCard().getId();
                sa.resolve();
                if (getPlayer().getCardsIn(ZoneType.Battlefield).stream().noneMatch(c -> c.getId() == id))
                    throw new RulesCostFeasibility.Unsupported("selected land did not enter battlefield");
                // Executes the previously validated host choice; this callback
                // does not itself make a host decision. Nested calls stay separate.
                if (invocation != null) invocation.classify(CallCounter.Ownership.RULES);
                return true;
            }
            if (!sa.canPlay()) throw new RulesCostFeasibility.Unsupported("selected spell no longer playable");
            final RulesCostFeasibility.SourceChoice manaOutput;
            if(sa.isManaAbility()) {
                var domain=new PriorityManaActivation(getPlayer(),sa);domain.select(selectedAnswer);manaOutput=domain.selected();
            } else manaOutput=null;
            try (final var authorization = RulesCastingAuthorization.capture(getPlayer(), sa)) {
                announcingExternalAction = true;
                announcingExternalAbility = sa;
                selectingExternalTargets = true;
                if (!forge.ai.ComputerUtil.handlePlayingSpellAbilityControlled(getPlayer(), sa,
                        new forge.ai.ComputerUtil.ControlledAnnouncement() {
                            public void sourceMoved(Card original, Card returned, SpellAbility actual) {
                                authorization.bind(original, returned, actual);
                            }
                            public forge.game.cost.CostDecisionMakerBase preparePayment(SpellAbility actual) {
                                if (!ensureTargets(actual, authorization))
                                    throw new RulesCostFeasibility.Unsupported("announced targets or complete cost invalid");
                                final var payments = new RulesPaymentDomain(getPlayer(), actual, authorization);
                                final JsonObject request = envelope(true);
                                for (var entry : payments.request().entrySet()) request.add(entry.getKey(), entry.getValue());
                                request.add("selectedAbility", StateEncoder.encodePriorityAbility(actual, getPlayer().getView()));
                                final JsonObject answer = ask("payManaCost", "payment", request);
                                activeRulesPayment = new RulesPaymentExecutor(getPlayer(), actual, payments.select(answer), authorization, null,
                                        PlayerControllerBridge.this::chooseDiscardCost, PlayerControllerBridge.this::chooseReturnCost);
                                BenchActionAudit.selected(getGame(), seat, actual, selectedAnswer);
                                return activeRulesPayment.decisions(actual);
                            }
                            public void resolveMana(SpellAbility actual, Runnable nativeExecution) {
                                if(manaOutput==null)throw new RulesCostFeasibility.Unsupported("unselected mana production");
                                activeRulesPayment.resolveSelectedMana(actual,manaOutput,nativeExecution);
                                BenchActionAudit.manaExecuted(getGame(),seat,actual,manaOutput);
                            }
                        })) throw new RulesCostFeasibility.Unsupported("controlled action execution failed");
                activeRulesPayment.assertPaid();
            }
            counters.instrument("rulesPayment.executed");
            if (invocation != null) invocation.classify(CallCounter.Ownership.RULES);
            return true;
        } catch (RuntimeException | Error failure) {
            failedExternalAction = true;
            session.noteIntegrityFailure(getGame(), seat, "controlled action execution", failure);
            JsonRpcChannel.logErr("BENCH_INTEGRITY_FAILURE: controlled action execution failed", failure);
            throw failure;
        } finally { activeRulesPayment = null; announcingExternalAction = false; announcingExternalAbility = null; selectingExternalTargets = false; }
    }
    @Override
    public int chooseNumberForCostReduction(final SpellAbility sa, final int min, final int max) { return stockCall("chooseNumberForCostReduction", () -> super.chooseNumberForCostReduction(sa, min, max)); }

    private List<Card> chooseReturnCost(RulesReturnCostDomain domain) {
        var invocation=counters.beginCall("chooseReturnForCost");
        try {
            requireHostChannel("chooseReturnForCost");
            if(domain.payer!=getPlayer() || domain.ability.getActivatingPlayer()!=getPlayer())
                throw new RulesCostFeasibility.Unsupported("return-cost actor mismatch");
            if(domain.forced()) {
                var chosen=domain.forcedSelection();invocation.classify(CallCounter.Ownership.FORCED);return chosen;
            }
            var body=envelope(true);
            for(var entry:domain.request().entrySet())body.add(entry.getKey(),entry.getValue());
            var answer=ask("chooseReturnForCost","cardsChoice",body);
            requireHostChannel("chooseReturnForCost");
            var chosen=domain.select(answer);invocation.classify(CallCounter.Ownership.HOST);return chosen;
        } catch(RuntimeException|Error failure) {
            session.noteIntegrityFailure(getGame(),seat,"chooseReturnForCost",failure);throw failure;
        }
    }
    private List<Card> chooseDiscardCost(RulesDiscardCostDomain domain) {
        var invocation=counters.beginCall("chooseDiscardForCost");
        try {
            requireHostChannel("chooseDiscardForCost");
            if(domain.payer!=getPlayer() || domain.ability.getActivatingPlayer()!=getPlayer())
                throw new RulesCostFeasibility.Unsupported("discard-cost actor mismatch");
            if(domain.forced()) {
                var chosen=domain.forcedSelection();invocation.classify(CallCounter.Ownership.FORCED);return chosen;
            }
            var body=envelope(true);
            for(var entry:domain.request().entrySet())body.add(entry.getKey(),entry.getValue());
            var answer=ask("chooseDiscardForCost","cardsChoice",body);
            requireHostChannel("chooseDiscardForCost");
            var chosen=domain.select(answer);invocation.classify(CallCounter.Ownership.HOST);return chosen;
        } catch(RuntimeException|Error failure) {
            session.noteIntegrityFailure(getGame(),seat,"chooseDiscardForCost",failure);throw failure;
        }
    }
    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) { return stockCall("chooseFlipResult", () -> super.chooseFlipResult(sa, flipper, call)); }
    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        if (isLiveGame() && activeRulesPayment != null) {
            var invocation = counters.beginCall("chooseColor");
            try {
                byte color = activeRulesPayment.chooseSourceColor(sa, colors);
                invocation.classify(CallCounter.Ownership.FORCED);
                return color;
            } catch (RuntimeException failure) {
                session.noteIntegrityFailure(getGame(),seat,"chooseColor",failure);throw failure;
            }
        }
        if (mode == BenchSession.Mode.BRIDGE && isLiveGame()) {
            var invocation = counters.beginCall("chooseColor");
            try {
                requireHostChannel("trigger color choice");
                if (activeTriggeredManaChoice == null)
                    throw new RulesCostFeasibility.Unsupported("color choice outside represented native effect");
                var request = envelope(true);
                for (var entry : activeTriggeredManaChoice.request(sa, colors).entrySet()) request.add(entry.getKey(), entry.getValue());
                byte color = activeTriggeredManaChoice.select(ask("chooseColor", "manaColor", request));
                invocation.classify(CallCounter.Ownership.HOST);
                return color;
            } catch (RuntimeException | Error failure) {
                session.noteIntegrityFailure(getGame(),seat,"chooseColor",failure);throw failure;
            }
        }
        return stockCall("chooseColor", () -> super.chooseColor(message, sa, colors));
    }
    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) { return stockCall("chooseColorAllowColorless", () -> super.chooseColorAllowColorless(message, c, colors)); }
    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) { return stockCall("chooseColors", () -> super.chooseColors(message, sa, min, max, options)); }
    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) { return stockCall("chooseSingleCardFace", () -> super.chooseSingleCardFace(sa, message, cpp, name)); }
    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) { return stockCall("chooseSingleCardFace", () -> super.chooseSingleCardFace(sa, faces, message)); }
    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) { return stockCall("chooseSingleCardState", () -> super.chooseSingleCardState(sa, states, message, params)); }
    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) { return stockCall("chooseCardsPile", () -> super.chooseCardsPile(sa, pile1, pile2, faceUp)); }
    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        final var invocation = isLiveGame() ? counters.beginCall("chooseCounterType") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) {
            var result = super.chooseCounterType(options, sa, prompt, params);
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return result;
        }
        try {
            requireHostChannel("counter type choice");
            if (options == null || options.stream().anyMatch(java.util.Objects::isNull))
                throw new RulesCostFeasibility.Unsupported("invalid counter type domain");
            if (options.size() > 1)
                throw new RulesCostFeasibility.Unsupported("multiple counter types require native host policy correspondence");
            if (invocation != null) invocation.classify(CallCounter.Ownership.FORCED);
            return options.isEmpty() ? null : options.get(0);
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "counter type choice", failure); throw failure;
        }
    }
    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) { return stockCall("chooseKeywordForPump", () -> super.chooseKeywordForPump(options, sa, prompt, tgtCard)); }
    @Override
    public boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) { return stockCall("confirmPayment", () -> super.confirmPayment(costPart, string, sa)); }
    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) { return stockCall("chooseSingleReplacementEffect", () -> super.chooseSingleReplacementEffect(possibleReplacers)); }
    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers) { return stockCall("chooseSingleStaticAbility", () -> super.chooseSingleStaticAbility(possibleReplacers)); }
    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) { return stockCall("chooseProtectionType", () -> super.chooseProtectionType(sa, choices)); }
    @Override
    public void revealAnte(String message, Multimap<Player, PaperCard> removedAnteCards) { final var invocation = isLiveGame() ? counters.beginCall("revealAnte") : null; super.revealAnte(message, removedAnteCards); if (invocation != null) invocation.classify(CallCounter.Ownership.RULES); }
    @Override
    public void revealAISkipCards(String message, Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) { final var invocation = isLiveGame() ? counters.beginCall("revealAISkipCards") : null; super.revealAISkipCards(message, deckCards); if (invocation != null) invocation.classify(CallCounter.Ownership.RULES); }
    @Override
    public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) { final var invocation = isLiveGame() ? counters.beginCall("revealUnsupported") : null; super.revealUnsupported(unsupported); if (invocation != null) invocation.classify(CallCounter.Ownership.RULES); }
    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) { return stockCall("orderCosts", () -> super.orderCosts(costs)); }
    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        final var invocation = isLiveGame() ? counters.beginCall("payCostToPreventEffect") : null;
        if (mode != BenchSession.Mode.BRIDGE || !isLiveGame()) {
            boolean result = super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
            if (invocation != null) invocation.classify(CallCounter.Ownership.STOCK);
            return result;
        }
        try {
            requireHostChannel("resolution cost");
            try (var scope = new EchoManaPayment(getPlayer(), cost, sa, alreadyPaid, allPayers)) {
                var assessment = RulesCostFeasibility.assess(getPlayer(), sa, null, scope);
                if (assessment.status() == RulesCostFeasibility.Status.UNSUPPORTED)
                    throw new RulesCostFeasibility.Unsupported("echo affordability unknown: " + assessment.reason());
                if (assessment.status() == RulesCostFeasibility.Status.UNPAYABLE) {
                    scope.answer(false); scope.finish(false);
                    if (invocation != null) invocation.classify(CallCounter.Ownership.FORCED);
                    return false;
                }
                var confirm = envelope(true);
                confirm.addProperty("resolutionPaymentVersion", "native-echo-mana-v1");
                confirm.addProperty("unitCost", scope.manaCost(getPlayer(), sa).toString());
                confirm.addProperty("completedPayments", 0);
                confirm.add("ability", StateEncoder.encodeSpellAbility(sa, getPlayer().getView()));
                var response = ask("payCostToPreventEffect", "confirm", confirm);
                var yes = response == null ? null : response.get("yes");
                if (yes == null || !yes.isJsonPrimitive() || !yes.getAsJsonPrimitive().isBoolean())
                    throw new RulesCostFeasibility.Unsupported("explicit boolean echo decision required");
                scope.answer(yes.getAsBoolean());
                if (!yes.getAsBoolean()) {
                    scope.finish(false);
                    if (invocation != null) invocation.classify(CallCounter.Ownership.HOST);
                    return false;
                }
                var domain = new RulesPaymentDomain(assessment.space(), getPlayer().getLife());
                var request = envelope(true);
                for (var entry : domain.request().entrySet()) request.add(entry.getKey(), entry.getValue());
                request.add("selectedAbility", StateEncoder.encodeSpellAbility(sa, getPlayer().getView()));
                request.addProperty("paymentContext", "echo-resolution-v1");
                var answer = ask("payManaCost", "payment", request);
                var payment = new RulesPaymentExecutor(getPlayer(), sa, domain.select(answer), null, scope);
                var previous = activeRulesPayment; activeRulesPayment = payment;
                try {
                    if (!new CostPayment(cost, sa).payComputerCosts(payment.decisions(sa)))
                        throw new RulesCostFeasibility.Unsupported("native echo cost payment failed");
                    payment.assertPaid(); scope.finish(true);
                } finally { activeRulesPayment = previous; }
                if (invocation != null) invocation.classifyRulesIfChildrenAccounted();
                counters.instrument("hostTrigger.rulesEchoPayment");
                return true;
            }
        } catch (RuntimeException | Error failure) {
            session.noteIntegrityFailure(getGame(), seat, "resolution cost", failure); throw failure;
        }
    }
    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) { return stockCall("payCostDuringRoll", () -> super.payCostDuringRoll(cost, sa)); }
    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) { return stockCall("payCombatCost", () -> super.payCombatCost(card, cost, sa, prompt)); }
    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect) {
        final var invocation = isLiveGame() ? counters.beginCall("payManaCost") : null;
        if (activeOptionalWrapperPayment != null) {
            boolean paid = activeOptionalWrapperPayment.pay(toPay, costPartMana, sa, matrix, effect);
            if (paid && invocation != null) invocation.classify(CallCounter.Ownership.RULES);
            return paid;
        }
        if (activeOptionalTriggerPayment != null) {
            boolean paid=activeOptionalTriggerPayment.pay(toPay,costPartMana,sa,matrix,effect);
            if(invocation!=null)invocation.classify(CallCounter.Ownership.RULES);
            return paid;
        }
        if (activeZeroTriggerPayment != null) {
            boolean paid = activeZeroTriggerPayment.pay(toPay, costPartMana, sa, matrix, effect);
            if (paid && invocation != null) invocation.classify(CallCounter.Ownership.RULES);
            return paid;
        }
        if (activeRulesPayment != null) {
            if (matrix != null) throw new RulesCostFeasibility.Unsupported("payment matrix not in witness");
            final boolean paid = activeRulesPayment.pay(toPay, costPartMana, sa, effect);
            if (paid && invocation != null) invocation.classify(CallCounter.Ownership.RULES);
            return paid;
        }
        return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect));
    }
    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt, ManaConversionMatrix matrix, boolean effect) { return stockCall("applyManaToCost", () -> super.applyManaToCost(toPay, ability, prompt, matrix, effect)); }
    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt) { return stockCall("chooseCardsForCost", () -> super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt)); }
    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability, boolean effect, String prompt) { return stockCall("getCostDecisionMaker", () -> super.getCostDecisionMaker(player, ability, effect, prompt)); }
    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) { return stockCall("chooseCardName", () -> super.chooseCardName(sa, cpp, valid, message)); }
    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) { return stockCall("chooseCardName", () -> super.chooseCardName(sa, faces, message)); }
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
        final var invocation = isLiveGame() ? counters.beginCall("chooseSingleCardForZoneChange") : null;
        if (!bridged() || decider != getPlayer() || fetchList == null || fetchList.isEmpty()) {
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal,
                    selectPrompt, isOptional, decider));
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
            final Card out = classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList,
                    null, selectPrompt, isOptional, decider));
            // Single-card ask, list-shaped answer: the host answers `choices` here even
            // when `max` is 1, and declining is the empty list rather than a `none`.
            echo(e, echoCards(out == null ? Collections.<Card>emptyList()
                    : Collections.singletonList(out)));
            return out;
        }
        if (picked.isEmpty()) {
            return classifiedResult(invocation, CallCounter.Ownership.HOST, (Card) null); // a legal answer when `isOptional`; `min` refused it otherwise
        }
        zoneChangeChosen++;
        return classifiedResult(invocation, CallCounter.Ownership.HOST, picked.get(0));
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt,
            Player decider) {
        final var invocation = isLiveGame() ? counters.beginCall("chooseCardsForZoneChange") : null;
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
            return classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max,
                    delayedReveal, selectPrompt, decider));
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
            final List<Card> out = classifiedResult(invocation, CallCounter.Ownership.STOCK, super.chooseCardsForZoneChange(destination, origin, sa, fetchList,
                    min, max, null, selectPrompt, decider));
            // `PlayerControllerAi`'s own body is `return null` under the comment "this
            // isn't used", so an empty `choices` here is the honest echo of a method
            // that decides nothing rather than a lost row.
            echo(e, echoCards(out));
            return out;
        }
        return classifiedResult(invocation, CallCounter.Ownership.HOST, picked);
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
        final JsonObject ans = ask(method, "zoneChange", body);
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
     * The live bridge now fails the game on any non-permutation, delegation or
     * closed transport. Only the explicit nonbridge path uses Default. A list
     * shorter than two is forced; it does not invoke an AI decision.
     */
    private CardCollectionView bridgedOrderMoveToZoneList(final CardCollectionView cards,
            final ZoneType destinationZone, final SpellAbility source) {
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
        final JsonObject ans = ask("orderMoveToZoneList", "orderZone", body);
        if (ans == null || !ans.has("choices") || !ans.get("choices").isJsonArray())
            throw new RulesCostFeasibility.Unsupported("zone order requires a host permutation");
        final List<Integer> ids = new ArrayList<>();
        for (var value : ans.getAsJsonArray("choices")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !value.getAsString().matches("0|[1-9][0-9]*"))
                throw new RulesCostFeasibility.Unsupported("zone order requires integer card IDs");
            ids.add(Integer.parseInt(value.getAsString()));
        }
        if (ids.size() != cards.size()) {
            throw new RulesCostFeasibility.Unsupported("zone order has wrong permutation size");
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
                throw new RulesCostFeasibility.Unsupported("zone order has unknown/duplicate card ID " + fid);
            }
            ordered.add(found);
        }
        return ordered;
    }

    @Override
    public void autoPassCancel() { final var invocation = isLiveGame() ? counters.beginCall("autoPassCancel") : null; super.autoPassCancel(); if (invocation != null) invocation.classify(CallCounter.Ownership.RULES); }
    @Override
    public void awaitNextInput() { final var invocation = isLiveGame() ? counters.beginCall("awaitNextInput") : null; super.awaitNextInput(); if (invocation != null) invocation.classify(CallCounter.Ownership.RULES); }
    @Override
    public void cancelAwaitNextInput() { final var invocation = isLiveGame() ? counters.beginCall("cancelAwaitNextInput") : null; super.cancelAwaitNextInput(); if (invocation != null) invocation.classify(CallCounter.Ownership.RULES); }

}
