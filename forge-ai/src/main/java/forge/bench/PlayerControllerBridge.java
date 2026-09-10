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
import forge.ai.ComputerUtilMana;
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
public class PlayerControllerBridge extends PlayerControllerAi {

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
    private RulesPaymentExecutor pendingExternalPayment;
    private RulesPaymentExecutor activeRulesPayment;

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
        if (pendingExternalAbility != null) throw new RulesCostFeasibility.Unsupported("previous selected action was not executed");
        final BenchRandomAudit.Token rngBeforeMenu = BenchRandomAudit.begin();
        final int[] diag = new int[DIAG_LEN];
        final List<SpellAbility> menu = legalSpellAbilities(diag);
        recordMenuCensus(diag, menu.size());
        final JsonObject body = envelope(true);
        body.addProperty("rulesCostVersion", RulesCostFeasibility.VERSION);
        body.addProperty("paymentVersion", "rules-payment-v1");
        body.addProperty("paymentControl", "host-complete-witness");
        body.add("menuDiag", menuDiagJson(diag, menu.size()));
        final JsonArray items = new JsonArray();
        items.add(StateEncoder.encodeSpellAbility(null)); // choice 0 is always pass
        for (SpellAbility sa : menu) {
            items.add(StateEncoder.encodeSpellAbility(sa, getPlayer().getView()));
        }
        body.add("menu", items);
        body.add("manaAbilities", manaAbilityChannel());
        BenchRandomAudit.assertUnchanged(rngBeforeMenu, "priority menu and seat-visible encoding");
        final JsonObject ans = ask("chooseSpellAbilityToPlay", "priority", body);
        if (ans == null) {
            final Echo e = takeEcho();
            final List<SpellAbility> out = super.chooseSpellAbilityToPlay();
            echo(e, echoPriority(menu, out));
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
        if (!chosen.isLandAbility()) {
            final RulesPaymentChoices payments = new RulesPaymentChoices(getPlayer(), chosen);
            final JsonObject request = envelope(true);
            for (var entry : payments.request().entrySet()) request.add(entry.getKey(), entry.getValue());
            request.add("selectedAbility", StateEncoder.encodeSpellAbility(chosen));
            final JsonObject selectedPayment = ask("payManaCost", "payment", request);
            pendingExternalPayment = new RulesPaymentExecutor(getPlayer(), chosen, payments.select(selectedPayment));
        }
        pendingExternalAbility = chosen;
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
        // A land play is not a spell/casting cost. Its rules permission is checked
        // by LandAbility.canPlay, including again immediately before execution.
        if (root.isLandAbility()) return true;
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
        if (!RulesCostFeasibility.requirePayable(getPlayer(), root)) {
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
                    out.add(StateEncoder.encodeSpellAbility(sa, p.getView()));
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
                    // Discovery clears pips on its argument. Never mutate the base
                    // candidate while discovering independent optional subsets.
                    if (sa.getAlternateHost(sa.getHostCard()) != null) {
                        throw new RulesCostFeasibility.Unsupported("optional-cost alternate-host static simulation");
                    }
                    final List<OptionalCostValue> opts = GameActionUtil.getOptionalCostValues(sa.copy(sa.getHostCard(), p, true));
                    if (opts == null || opts.isEmpty()) {
                        continue;
                    }
                    withVariants.addAll(BenchmarkOptionalCosts.variants(sa, opts, p));
                } catch (RuntimeException e) {
                    JsonRpcChannel.logErr("optional-cost variant construction failed for " + sa, e);
                    throw e;
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
                if (!RulesCostFeasibility.requirePayable(p, sa)) {
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
     * ceiling: mana left after the base cost, divided by the repeat's own mana cost.
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
                final int available = ComputerUtilMana.getAvailableManaEstimate(getPlayer());
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
        final JsonObject ans = ask("chooseOptionalCosts", "optionalCosts", body);
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
    private static boolean hasEnoughTargets(final SpellAbility root) {
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

        final JsonObject ans = ask("declareAttackers", "attackers", body);
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

        final JsonObject ans = ask("declareBlockers", "blockers", body);
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
            return super.tuckCardsViaMulligan(hand, cardsToReturn);
        }
        final CardCollection picked = askForCards("tuckCardsViaMulligan", hand,
                cardsToReturn, cardsToReturn, "put on the bottom (London mulligan)", null);
        if (picked == null) {
            // `takeEcho()` is null on the REFUSAL branch of `askForCards` — `ask` clears
            // the token whenever the host actually answered — so the echo fires only on a
            // delegation, exactly as the ECHO block prescribes.
            final Echo e = takeEcho();
            final CardCollectionView out = super.tuckCardsViaMulligan(hand, cardsToReturn);
            echo(e, echoCards(out));
            return out;
        }
        return picked;
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
        final int max = currentAbility.getMaxTargets();

        final JsonObject body = envelope(true);
        body.add("ability", StateEncoder.encodeSpellAbility(currentAbility));
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

        final JsonObject ans = ask("chooseTargetsFor", "targets", body);
        if (ans == null) {
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
        final JsonObject ans = ask("chooseSingleEntityForEffect", "entityChoice", body);
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
        final JsonObject ans = ask("chooseEntitiesForEffect", "entityChoice", body);
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
        final JsonObject ans = ask("chooseNumber", "number", body);
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
        final JsonObject ans = ask("chooseNumber", "number", body);
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
        final JsonObject ans = ask("chooseModeForAbility", "mode", body);
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
        final JsonObject ans = ask("confirmAction", "confirm", body);
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
        final JsonObject ans = ask("chooseBinary", "confirm", body);
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
        if (!bridged() || topN == null || topN.isEmpty()) {
            return super.arrangeForScry(topN);
        }
        final JsonObject body = envelope(true);
        body.add("menu", StateEncoder.encodeCards(topN));
        final JsonObject ans = ask("arrangeForScry", "scry", body);
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
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) { count("getAbilityToPlay"); return super.getAbilityToPlay(hostCard, abilities, triggerEvent); }
    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean mayChoseNewTargets) { count("playSpellAbilityNoStack"); super.playSpellAbilityNoStack(effectSA, mayChoseNewTargets); }
    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) { count("orderSimultaneousSa"); return super.orderSimultaneousSa(activePlayerSAs); }
    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) { count("orderAndPlaySimultaneousSa"); super.orderAndPlaySimultaneousSa(activePlayerSAs); }
    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) { count("playTrigger"); return super.playTrigger(host, wrapperAbility, isMandatory); }
    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) { count("playSaFromPlayEffect"); return super.playSaFromPlayEffect(tgtSA); }
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
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num, Map<String, Object> params) { count("chooseSpellAbilitiesForEffect"); return super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params); }
    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) { count("chooseSingleSpellForEffect"); return super.chooseSingleSpellForEffect(spells, sa, title, params); }
    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string, int bid, Player winner) { count("confirmBidAction"); return super.confirmBidAction(sa, bidlife, string, bid, winner); }
    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) { count("confirmReplacementEffect"); return super.confirmReplacementEffect(replacementEffect, effectSA, affected, question); }
    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) { count("confirmStaticApplication"); return super.confirmStaticApplication(hostCard, mode, message, logic); }
    @Override
    public boolean confirmTrigger(WrappedAbility sa) { count("confirmTrigger"); return super.confirmTrigger(sa); }
    @Override
    public List<Card> exertAttackers(List<Card> attackers) { count("exertAttackers"); return super.exertAttackers(attackers); }
    @Override
    public List<Card> enlistAttackers(List<Card> attackers) { count("enlistAttackers"); return super.enlistAttackers(attackers); }
    @Override
    public CardCollection orderBlocker(final Card attacker, final Card blocker, final CardCollection oldBlockers) { count("orderBlocker"); return super.orderBlocker(attacker, blocker, oldBlockers); }
    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) { count("orderAttackers"); return super.orderAttackers(blocker, attackers); }
    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addMsgSuffix) { count("reveal"); super.reveal(cards, zone, owner, messagePrefix, addMsgSuffix); }
    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix, boolean addMsgSuffix) { count("reveal"); super.reveal(cards, zone, owner, messagePrefix, addMsgSuffix); }
    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject realtedTarget, String value) { count("notifyOfValue"); super.notifyOfValue(saSource, realtedTarget, value); }
    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) { count("arrangeForSurveil"); return super.arrangeForSurveil(topN); }
    @Override
    public boolean willPutCardOnTop(Card c) { count("willPutCardOnTop"); return super.willPutCardOnTop(c); }
    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) { count("orderMoveToZoneList"); return bridgedOrderMoveToZoneList(cards, destinationZone, source); }
    @Override
    public CardCollection chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) { count("chooseCardsToDiscardFrom"); return super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser); }
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
    public boolean playChosenSpellAbility(SpellAbility sa) {
        count("playChosenSpellAbility");
        if (pendingExternalAbility == null) return super.playChosenSpellAbility(sa);
        if (pendingExternalAbility != sa) throw new RulesCostFeasibility.Unsupported("selected/executed ability identity mismatch");
        pendingExternalAbility = null;
        try {
            if (sa.isLandAbility()) {
                if (!sa.canPlay()) throw new RulesCostFeasibility.Unsupported("selected land no longer playable");
                int id = sa.getHostCard().getId();
                sa.resolve();
                if (getPlayer().getCardsIn(ZoneType.Battlefield).stream().noneMatch(c -> c.getId() == id))
                    throw new RulesCostFeasibility.Unsupported("selected land did not enter battlefield");
                return true;
            }
            if (pendingExternalPayment == null) throw new RulesCostFeasibility.Unsupported("host payment witness missing");
            activeRulesPayment = pendingExternalPayment;
            pendingExternalPayment = null;
            if (!sa.canPlay()) throw new RulesCostFeasibility.Unsupported("selected spell no longer playable");
            if (!forge.ai.ComputerUtil.handlePlayingSpellAbility(getPlayer(), sa, null, activeRulesPayment::decisions))
                throw new RulesCostFeasibility.Unsupported("controlled action execution failed");
            activeRulesPayment.assertPaid();
            counters.instrument("rulesPayment.executed");
            return true;
        } catch (RuntimeException failure) {
            JsonRpcChannel.logErr("BENCH_INTEGRITY_FAILURE: controlled action execution failed", failure);
            throw failure;
        } finally { activeRulesPayment = null; pendingExternalPayment = null; }
    }
    @Override
    public int chooseNumberForCostReduction(final SpellAbility sa, final int min, final int max) { count("chooseNumberForCostReduction"); return super.chooseNumberForCostReduction(sa, min, max); }
    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) { count("chooseFlipResult"); return super.chooseFlipResult(sa, flipper, call); }
    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) { count("chooseColor"); return super.chooseColor(message, sa, colors); }
    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) { count("chooseColorAllowColorless"); return super.chooseColorAllowColorless(message, c, colors); }
    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) { count("chooseColors"); return super.chooseColors(message, sa, min, max, options); }
    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) { count("chooseSingleCardFace"); return super.chooseSingleCardFace(sa, message, cpp, name); }
    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) { count("chooseSingleCardFace"); return super.chooseSingleCardFace(sa, faces, message); }
    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) { count("chooseSingleCardState"); return super.chooseSingleCardState(sa, states, message, params); }
    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) { count("chooseCardsPile"); return super.chooseCardsPile(sa, pile1, pile2, faceUp); }
    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) { count("chooseCounterType"); return super.chooseCounterType(options, sa, prompt, params); }
    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) { count("chooseKeywordForPump"); return super.chooseKeywordForPump(options, sa, prompt, tgtCard); }
    @Override
    public boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) { count("confirmPayment"); return super.confirmPayment(costPart, string, sa); }
    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) { count("chooseSingleReplacementEffect"); return super.chooseSingleReplacementEffect(possibleReplacers); }
    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers) { count("chooseSingleStaticAbility"); return super.chooseSingleStaticAbility(possibleReplacers); }
    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) { count("chooseProtectionType"); return super.chooseProtectionType(sa, choices); }
    @Override
    public void revealAnte(String message, Multimap<Player, PaperCard> removedAnteCards) { count("revealAnte"); super.revealAnte(message, removedAnteCards); }
    @Override
    public void revealAISkipCards(String message, Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) { count("revealAISkipCards"); super.revealAISkipCards(message, deckCards); }
    @Override
    public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) { count("revealUnsupported"); super.revealUnsupported(unsupported); }
    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) { count("orderCosts"); return super.orderCosts(costs); }
    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) { count("payCostToPreventEffect"); return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers); }
    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) { count("payCostDuringRoll"); return super.payCostDuringRoll(cost, sa); }
    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) { count("payCombatCost"); return super.payCombatCost(card, cost, sa, prompt); }
    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect) {
        count("payManaCost");
        if (activeRulesPayment != null) {
            if (matrix != null) throw new RulesCostFeasibility.Unsupported("payment matrix not in witness");
            return activeRulesPayment.pay(toPay, costPartMana, sa, effect);
        }
        return super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
    }
    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt, ManaConversionMatrix matrix, boolean effect) { count("applyManaToCost"); return super.applyManaToCost(toPay, ability, prompt, matrix, effect); }
    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt) { count("chooseCardsForCost"); return super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt); }
    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability, boolean effect, String prompt) { count("getCostDecisionMaker"); return super.getCostDecisionMaker(player, ability, effect, prompt); }
    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) { count("chooseCardName"); return super.chooseCardName(sa, cpp, valid, message); }
    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) { count("chooseCardName"); return super.chooseCardName(sa, faces, message); }
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
        final JsonObject ans = ask("orderMoveToZoneList", "orderZone", body);
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

    @Override
    public void autoPassCancel() { count("autoPassCancel"); super.autoPassCancel(); }
    @Override
    public void awaitNextInput() { count("awaitNextInput"); super.awaitNextInput(); }
    @Override
    public void cancelAwaitNextInput() { count("cancelAwaitNextInput"); super.cancelAwaitNextInput(); }

}
