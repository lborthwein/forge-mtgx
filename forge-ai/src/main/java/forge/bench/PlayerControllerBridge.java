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
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.*;
import forge.game.combat.Combat;
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
     */
    private JsonObject ask(final String method, final String kind, final JsonObject body) {
        final JsonObject ans = session.getChannel().ask(kind, body);
        if (ans == null || (ans.has("delegate") && ans.get("delegate").getAsBoolean())) {
            counters.delegateRequested(method);
            return null;
        }
        return ans;
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
        final List<SpellAbility> menu = legalSpellAbilities();
        final JsonObject body = envelope(true);
        final JsonArray items = new JsonArray();
        items.add(StateEncoder.encodeSpellAbility(null)); // choice 0 is always pass
        for (SpellAbility sa : menu) {
            items.add(StateEncoder.encodeSpellAbility(sa));
        }
        body.add("menu", items);
        final JsonObject ans = ask("chooseSpellAbilityToPlay", "priority", body);
        if (ans == null) {
            return super.chooseSpellAbilityToPlay();
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
        if (!ensureTargets(chosen)) {
            return super.chooseSpellAbilityToPlay();
        }
        return Lists.newArrayList(chosen);
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

    /**
     * The legal action menu offered at priority. Mana abilities are excluded: Forge plays
     * those during cost payment, never at priority, and offering them invites a
     * non-terminating priority loop.
     */
    private List<SpellAbility> legalSpellAbilities() {
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
            final List<SpellAbility> all = ComputerUtilAbility.getOriginalAndAltCostAbilities(
                    ComputerUtilAbility.getSpellAbilities(cards, p), p);
            for (SpellAbility sa : all) {
                if (sa.isManaAbility() || sa.isLandAbility()) {
                    continue;
                }
                sa.setActivatingPlayer(p);
                if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, p, sa.isTrigger())
                        && hasEnoughTargets(sa)) {
                    out.add(sa);
                }
            }
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("legalSpellAbilities failed; offering pass only", e);
        }
        return out;
    }

    /**
     * {@code SpellAbility.canPlay} does not check that legal targets exist, so without this
     * the menu offers e.g. a counterspell with an empty stack. Every entry we offer must be
     * an action the host can actually complete.
     */
    private static boolean hasEnoughTargets(final SpellAbility root) {
        SpellAbility cur = root;
        while (cur != null) {
            if (cur.usesTargeting()) {
                try {
                    if (cur.getTargetRestrictions().getAllCandidates(cur).size() < cur.getMinTargets()) {
                        return false;
                    }
                } catch (RuntimeException e) {
                    return false;
                }
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

        final JsonObject ans = ask("declareAttackers", "attackers", body);
        if (ans == null) {
            super.declareAttackers(attacker, combat);
            return;
        }
        final List<int[]> pairs = optPairs(ans, "pairs");
        if (pairs == null) {
            refuse("declareAttackers", "missing/!array 'pairs'");
            super.declareAttackers(attacker, combat);
            return;
        }
        combat.clearAttackers();
        String bad = null;
        for (int[] pr : pairs) {
            final Card c = findCard(possible, pr[0]);
            final GameEntity d = findEntity(defenders, pr[1]);
            if (c == null || d == null) {
                bad = "unknown attacker/defender " + pr[0] + "/" + pr[1];
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
            super.declareBlockers(defender, combat);
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
            return super.mulliganKeepHand(p, cardsToReturn);
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
            return super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
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
            return super.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
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
            return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
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
        final List<GameEntity> candidates;
        try {
            candidates = tgt.getAllCandidates(currentAbility);
        } catch (RuntimeException e) {
            JsonRpcChannel.logErr("target candidate enumeration failed", e);
            return super.chooseTargetsFor(currentAbility);
        }
        final int min = currentAbility.getMinTargets();
        final int max = currentAbility.getMaxTargets();

        final JsonObject body = envelope(true);
        body.add("ability", StateEncoder.encodeSpellAbility(currentAbility));
        body.add("menu", StateEncoder.encodeEntities(candidates));
        body.addProperty("min", min);
        body.addProperty("max", max);
        final JsonObject ans = ask("chooseTargetsFor", "targets", body);
        if (ans == null) {
            return super.chooseTargetsFor(currentAbility);
        }
        final List<Integer> ids = optIntList(ans, "choices");
        if (ids == null) {
            refuse("chooseTargetsFor", "missing/!array 'choices'");
            return super.chooseTargetsFor(currentAbility);
        }
        if (ids.size() < min || ids.size() > max) {
            refuse("chooseTargetsFor", "chose " + ids.size() + " targets outside [" + min + "," + max + "]");
            return super.chooseTargetsFor(currentAbility);
        }
        final TargetChoices before = currentAbility.getTargets();
        currentAbility.resetTargets();
        for (int id : ids) {
            final GameEntity ge = findEntity(candidates, id);
            if (ge == null || !currentAbility.canTarget(ge)) {
                currentAbility.setTargets(before);
                refuse("chooseTargetsFor", "illegal target id " + id);
                return super.chooseTargetsFor(currentAbility);
            }
            currentAbility.getTargets().add(ge);
        }
        return true;
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
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional,
                    relatedPlayer, params);
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
            return super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title,
                    relatedPlayer, params);
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
            return super.chooseNumber(sa, title, min, max);
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
            return super.chooseNumber(sa, title, values, relatedPlayer);
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
            return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
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
            return super.confirmAction(sa, mode0, message, options, cardToShow, params);
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
            return super.chooseBinary(sa, question, kindOfChoice, defaultChoice);
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
            return super.arrangeForScry(topN);
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
            return super.orderBlockers(attacker, blockers);
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
        final JsonObject ans = ask("assignCombatDamage", "assignDamage", body);
        if (ans == null) {
            return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
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
            // key -1 means "trample through to the defending player/planeswalker"
            final Card target = fid < 0 ? null : findCard(blockers, fid);
            if (fid >= 0 && target == null) {
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
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) { count("orderMoveToZoneList"); return super.orderMoveToZoneList(cards, destinationZone, source); }
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
    public Player chooseStartingPlayer(boolean isFirstGame) { count("chooseStartingPlayer"); return super.chooseStartingPlayer(isFirstGame); }
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
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) { count("tuckCardsViaMulligan"); return super.tuckCardsViaMulligan(hand, cardsToReturn); }
    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) { count("playChosenSpellAbility"); return super.playChosenSpellAbility(sa); }
    @Override
    public int chooseNumberForCostReduction(final SpellAbility sa, final int min, final int max) { count("chooseNumberForCostReduction"); return super.chooseNumberForCostReduction(sa, min, max); }
    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) { count("chooseNumberForKeywordCost"); return super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max); }
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
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility choosen, List<OptionalCostValue> optionalCostValues) { count("chooseOptionalCosts"); return super.chooseOptionalCosts(choosen, optionalCostValues); }
    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) { count("orderCosts"); return super.orderCosts(costs); }
    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) { count("payCostToPreventEffect"); return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers); }
    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) { count("payCostDuringRoll"); return super.payCostDuringRoll(cost, sa); }
    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) { count("payCombatCost"); return super.payCombatCost(card, cost, sa, prompt); }
    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect) { count("payManaCost"); return super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect); }
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
    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) { count("chooseSingleCardForZoneChange"); return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider); }
    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) { count("chooseCardsForZoneChange"); return super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider); }
    @Override
    public void autoPassCancel() { count("autoPassCancel"); super.autoPassCancel(); }
    @Override
    public void awaitNextInput() { count("awaitNextInput"); super.awaitNextInput(); }
    @Override
    public void cancelAwaitNextInput() { count("cancelAwaitNextInput"); super.cancelAwaitNextInput(); }

}
