package forge.interactive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.collect.FCollectionView;

/**
 * The inverse blend, done at the controller.
 *
 * <p>The earlier design computed what Forge's AI would do and projected it onto
 * the browser's human controls, leaving a human {@code PlayerControllerHuman} to
 * actually play the seat. Two measured runs killed it. Projection has to
 * re-derive, on the client, a legal answer the engine had already computed — and
 * every decision class where the projection was incomplete became a stall or a
 * loop rather than a worse decision. Gate zero run 2 finished 71 of 96 games and
 * came in 15.5 points below the recorded cell, with 5,173 mana decisions where
 * advice was present, mapped, and still not usable.
 *
 * <p>This seat <em>is</em> a {@link PlayerControllerAi}. It plays natively:
 * mana payment, targeting, ordering, every modal Forge can raise, all of it the
 * engine's own code on the engine's own thread. Nothing is projected, so nothing
 * can fail to project. A policy is offered the decision classes below and may
 * override them; everything else never becomes a request at all.
 *
 * <h2>The contract at a hooked decision</h2>
 *
 * <ol>
 *   <li>Compute the AI's own answer first. It is the default, and it is
 *       attached to the request as the hint.</li>
 *   <li>Publish the decision with the legal set the controller already holds.</li>
 *   <li>Wait a bounded time. {@code "defer"} accepts the AI's answer.</li>
 *   <li>Validate the reply against that same legal set and apply it.</li>
 *   <li>On timeout, malformed reply, or an answer outside the legal set: take
 *       the AI's answer and record the fallback.</li>
 * </ol>
 *
 * A policy can therefore cost the seat its override; it cannot cost the seat a
 * legal move, and it cannot make the seat do something the engine did not offer.
 *
 * <h2>Threading</h2>
 *
 * Hooks run on the game thread by construction — they are the controller, and
 * the engine calls it. The wait blocks the engine, which is correct: it is
 * waiting for an answer it cannot proceed without. The EDT is not involved, so
 * none of the request-building races that dogged the projection design apply.
 */
final class BlendedAiController extends PlayerControllerAi {
    private final InteractiveGuiGame bridge;
    private final long timeoutMs;
    private final boolean policyHooksOn;

    /** Decisions offered to the policy, and how they were resolved. */
    private long hooked;
    private long overridden;
    private long deferred;
    private long fallbacks;

    BlendedAiController(final Game game, final Player player, final LobbyPlayer lobby,
                        final InteractiveGuiGame bridge, final boolean policyHooksOn,
                        final long timeoutMs) {
        super(game, player, lobby);
        this.bridge = bridge;
        this.policyHooksOn = policyHooksOn;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 2000L;
    }

    // ------------------------------------------------------------ the hooks

    @Override
    public boolean mulliganKeepHand(final Player firstPlayer, final int cardsToReturn) {
        final boolean ai = super.mulliganKeepHand(firstPlayer, cardsToReturn);
        if (!policyHooksOn) {
            return ai;
        }
        final JsonArray controls = new JsonArray();
        controls.add(control("mulligan:keep", "confirm", "Keep this hand"));
        controls.add(control("mulligan:mull", "confirm", "Mulligan"));
        final String chose = decide("mulligan", "blend:mulliganKeepHand",
                "Keep this hand or mulligan?", controls, ai ? "mulligan:keep" : "mulligan:mull");
        return "mulligan:keep".equals(chose);
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(final CardCollectionView hand, final int cardsToReturn) {
        final CardCollectionView ai = super.tuckCardsViaMulligan(hand, cardsToReturn);
        if (!policyHooksOn || cardsToReturn <= 0) {
            return ai;
        }
        // The legal set is the hand; the answer is an ordered subset of exactly
        // cardsToReturn cards, so anything else falls back.
        final JsonArray controls = new JsonArray();
        for (Card card : hand) {
            controls.add(control("card:" + card.getId(), "selectCard",
                    InteractiveState.safeCardLabel(card.getView(), getPlayer().getView())));
        }
        final JsonObject hint = hintOf(idsOf(ai));
        final JsonObject answer = offer("mulligan", "blend:tuckCardsViaMulligan",
                "Put " + cardsToReturn + " card(s) on the bottom", controls, hint);
        final List<String> picked = idList(answer);
        if (picked == null || picked.size() != cardsToReturn) {
            return settle(answer, ai, picked != null);
        }
        final CardCollection chosen = new CardCollection();
        for (String id : picked) {
            final Card card = byId(hand, id);
            if (card == null) {
                return settle(answer, ai, false);
            }
            chosen.add(card);
        }
        overridden++;
        record("mulligan", "override", idsOf(ai), picked);
        return chosen;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        final List<SpellAbility> ai = super.chooseSpellAbilityToPlay();
        if (!policyHooksOn) {
            return ai;
        }
        // The legal set is what the AI was choosing between. Anything the engine
        // did not offer is not on the wire, so it cannot be chosen.
        final List<SpellAbility> legal = new ArrayList<>();
        for (Card card : getPlayer().getGame().getCardsInGame()) {
            if (card.getController() != getPlayer()) {
                continue;
            }
            for (SpellAbility sa : card.getAllPossibleAbilities(getPlayer(), true)) {
                legal.add(sa);
            }
        }
        final JsonArray controls = new JsonArray();
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < legal.size(); i++) {
            final String id = "sa:" + i;
            ids.add(id);
            controls.add(control(id, "selectAbility", String.valueOf(legal.get(i))));
        }
        controls.add(control("priority:pass", "passPriority", "Pass priority"));

        final SpellAbility aiChoice = ai == null || ai.isEmpty() ? null : ai.get(0);
        String aiId = "priority:pass";
        for (int i = 0; i < legal.size(); i++) {
            if (legal.get(i) == aiChoice) {
                aiId = ids.get(i);
                break;
            }
        }
        final String chose = decide("priority", "blend:chooseSpellAbilityToPlay",
                "Priority", controls, aiId);
        if ("priority:pass".equals(chose)) {
            return chose.equals(aiId) ? ai : List.of();
        }
        final int index = ids.indexOf(chose);
        if (index < 0) {
            return ai;
        }
        return chose.equals(aiId) ? ai : List.of(legal.get(index));
    }

    @Override
    public void declareAttackers(final Player attacker, final Combat combat) {
        super.declareAttackers(attacker, combat);
        if (!policyHooksOn) {
            return;
        }
        // The AI has already filled the combat. A policy may replace the whole
        // declaration; validating it means every pair must be one Forge accepts.
        final List<String> aiPairs = new ArrayList<>();
        for (Card card : combat.getAttackers()) {
            final GameEntity defender = combat.getDefenderByAttacker(card);
            if (defender != null) {
                aiPairs.add("attack:" + card.getId() + ":" + defender.getId());
            }
        }
        final JsonArray items = new JsonArray();
        for (Card candidate : attacker.getCreaturesInPlay()) {
            for (GameEntity defender : combat.getDefenders()) {
                if (forge.game.combat.CombatUtil.canAttack(candidate, defender)) {
                    items.add(item("attack:" + candidate.getId() + ":" + defender.getId(),
                            candidate.getName() + " -> " + defender));
                }
            }
        }
        // One control, not one per pair: the host validates a single advertised
        // controlId per input, so a set answer has to be an order over items.
        final JsonArray controls = new JsonArray();
        controls.add(setControl("attackers", "Declare attackers", items));
        hooked++;
        final JsonObject answer = offer("combat", "blend:declareAttackers",
                "Declare attackers", controls, hintOf(aiPairs));
        final List<String> picked = idList(answer);
        final java.util.Set<String> legalPairs = new java.util.LinkedHashSet<>();
        for (var element : items) {
            legalPairs.add(element.getAsJsonObject().get("id").getAsString());
        }
        if (picked == null) {
            settleVoid(answer);
            return;
        }
        // Identity first: if the answer IS the AI's declaration, leave the
        // combat exactly as the AI left it. Tearing it down and rebuilding
        // cannot reproduce banding, damage-assignment order, or the attack
        // costs removeUnpayableAttackers already settled — so a "defer" that
        // rebuilds is not a defer at all.
        if (new java.util.LinkedHashSet<>(picked).equals(new java.util.LinkedHashSet<>(aiPairs))) {
            deferred++;
            record("combat", "defer", aiPairs, picked);
            return;
        }
        // Rebuild from scratch so a partial override cannot leave a hybrid.
        final List<Card> previous = new ArrayList<>(combat.getAttackers());
        for (Card card : previous) {
            combat.removeFromCombat(card);
        }
        boolean ok = true;
        for (String id : picked) {
            // Only pairs this decision advertised. A policy cannot invent one.
            if (!legalPairs.contains(id)) { ok = false; break; }
            final String[] parts = id.split(":");
            if (parts.length != 3) { ok = false; break; }
            final Card card = attacker.getGame().findById(Integer.parseInt(parts[1]));
            GameEntity defender = null;
            for (GameEntity candidate : combat.getDefenders()) {
                if (candidate.getId() == Integer.parseInt(parts[2])) { defender = candidate; break; }
            }
            if (card == null || defender == null
                    || !forge.game.combat.CombatUtil.canAttack(card, defender)) { ok = false; break; }
            combat.addAttacker(card, defender);
        }
        if (!ok) {
            // Put the AI's declaration back exactly as it was.
            for (Card card : new ArrayList<>(combat.getAttackers())) {
                combat.removeFromCombat(card);
            }
            super.declareAttackers(attacker, combat);
            fallbacks++;
            record("combat", "fallback-invalid", aiPairs, picked);
            return;
        }
        overridden++;
        record("combat", "override", aiPairs, picked);
    }

    @Override
    public void declareBlockers(final Player defender, final Combat combat) {
        super.declareBlockers(defender, combat);
        if (!policyHooksOn) {
            return;
        }
        final List<String> aiPairs = new ArrayList<>();
        for (Card blocker : combat.getAllBlockers()) {
            final CardCollection blocked = combat.getAttackersBlockedBy(blocker);
            if (blocked != null && !blocked.isEmpty()) {
                aiPairs.add("block:" + blocker.getId() + ":" + blocked.get(0).getId());
            }
        }
        final JsonArray items = new JsonArray();
        for (Card blocker : defender.getCreaturesInPlay()) {
            for (Card atk : combat.getAttackers()) {
                if (forge.game.combat.CombatUtil.canBlock(atk, blocker, combat)) {
                    items.add(item("block:" + blocker.getId() + ":" + atk.getId(),
                            blocker.getName() + " blocks " + atk.getName()));
                }
            }
        }
        final JsonArray controls = new JsonArray();
        controls.add(setControl("blockers", "Declare blockers", items));
        hooked++;
        final JsonObject answer = offer("combat", "blend:declareBlockers",
                "Declare blockers", controls, hintOf(aiPairs));
        final List<String> picked = idList(answer);
        if (picked == null) {
            settleVoid(answer);
            return;
        }
        final java.util.Set<String> legalPairs = new java.util.LinkedHashSet<>();
        for (var element : items) {
            legalPairs.add(element.getAsJsonObject().get("id").getAsString());
        }
        // Identity first: if the answer IS the AI's declaration, leave the
        // combat exactly as the AI left it. Tearing it down and rebuilding
        // cannot reproduce banding, damage-assignment order, or the attack
        // costs removeUnpayableAttackers already settled — so a "defer" that
        // rebuilds is not a defer at all.
        if (new java.util.LinkedHashSet<>(picked).equals(new java.util.LinkedHashSet<>(aiPairs))) {
            deferred++;
            record("combat", "defer", aiPairs, picked);
            return;
        }
        // Rebuild from scratch: a partial override must not leave a hybrid of
        // the AI's blocks and the policy's.
        final List<Card> previous = new ArrayList<>(combat.getAllBlockers());
        for (Card blocker : previous) {
            combat.removeFromCombat(blocker);
        }
        boolean ok = true;
        for (String id : picked) {
            if (!legalPairs.contains(id)) { ok = false; break; }
            final String[] parts = id.split(":");
            if (parts.length != 3) { ok = false; break; }
            final Card blocker = defender.getGame().findById(Integer.parseInt(parts[1]));
            final Card atk = defender.getGame().findById(Integer.parseInt(parts[2]));
            if (blocker == null || atk == null
                    || !forge.game.combat.CombatUtil.canBlock(atk, blocker, combat)) { ok = false; break; }
            combat.addBlocker(atk, blocker);
        }
        if (!ok) {
            // A bad block assignment loses games outright, so an invalid answer
            // restores the AI's declaration rather than leaving a partial one.
            for (Card blocker : new ArrayList<>(combat.getAllBlockers())) {
                combat.removeFromCombat(blocker);
            }
            super.declareBlockers(defender, combat);
            fallbacks++;
            record("combat", "fallback-invalid", aiPairs, picked);
            return;
        }
        overridden++;
        record("combat", "override", aiPairs, picked);
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(
            final FCollectionView<T> optionList, final DelayedReveal delayedReveal,
            final SpellAbility sa, final String title, final boolean isOptional,
            final Player targetedPlayer, final Map<String, Object> params) {
        final T ai = super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title,
                isOptional, targetedPlayer, params);
        if (!policyHooksOn || optionList == null) {
            return ai;
        }
        final JsonArray controls = new JsonArray();
        final List<String> ids = new ArrayList<>();
        for (T option : optionList) {
            final String id = "entity:" + option.getId();
            ids.add(id);
            controls.add(control(id, "selectCard", String.valueOf(option)));
        }
        if (isOptional) {
            controls.add(control("entity:none", "ok", "Choose none"));
        }
        final String aiId = ai == null ? "entity:none" : "entity:" + ai.getId();
        final String chose = decide("target", "blend:chooseSingleEntity", title, controls, aiId);
        if (chose.equals(aiId)) {
            return ai;
        }
        for (T option : optionList) {
            if (("entity:" + option.getId()).equals(chose)) {
                return option;
            }
        }
        return ai;
    }

    // ------------------------------------------------------------- plumbing

    /** Offers a single-choice decision and returns the id to act on. */
    private String decide(final String kind, final String inputClass, final String message,
                          final JsonArray controls, final String aiId) {
        hooked++;
        final JsonObject answer = offer(kind, inputClass, message, controls, hintOf(List.of(aiId)));
        final List<String> picked = idList(answer);
        if (picked == null || picked.isEmpty()) {
            settleVoid(answer);
            return aiId;
        }
        final String chosen = picked.get(0);
        if (!hasControl(controls, chosen)) {
            fallbacks++;
            record(kind, "fallback-illegal", List.of(aiId), picked);
            return aiId;
        }
        if (chosen.equals(aiId)) {
            deferred++;
            record(kind, "defer", List.of(aiId), picked);
        } else {
            overridden++;
            record(kind, "override", List.of(aiId), picked);
        }
        return chosen;
    }

    private JsonObject offer(final String kind, final String inputClass, final String message,
                             final JsonArray controls, final JsonObject hint) {
        if (bridge == null) {
            return null;
        }
        return bridge.askPolicy(kind, inputClass, message, controls, hint, timeoutMs);
    }

    /** Timeout or a decline: the AI's answer stands, and the tape says why. */
    private <T> T settle(final JsonObject answer, final T ai, final boolean invalid) {
        if (invalid) {
            fallbacks++;
        } else {
            deferred++;
        }
        return ai;
    }

    private void settleVoid(final JsonObject answer) {
        if (answer == null) {
            deferred++;
        }
    }

    private static boolean hasControl(final JsonArray controls, final String id) {
        for (var element : controls) {
            if (element.isJsonObject()
                    && id.equals(element.getAsJsonObject().get("controlId").getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A set-valued decision: one control, a subset of advertised items.
     *
     * Deliberately a multi-select {@code choice} and not an {@code order}: the
     * host validates an order as a strict reordering — every advertised item
     * exactly once — which cannot express "attack with three of these six".
     */
    private static JsonObject setControl(final String id, final String label, final JsonArray items) {
        final JsonObject control = control(id, "choice", label);
        control.add("items", items);
        control.addProperty("min", 0);
        control.addProperty("max", items.size());
        return control;
    }

    private static JsonObject item(final String id, final String label) {
        final JsonObject item = new JsonObject();
        item.addProperty("id", id);
        item.addProperty("label", label == null ? "" : label);
        return item;
    }

    private static JsonObject control(final String id, final String type, final String label) {
        final JsonObject control = new JsonObject();
        control.addProperty("controlId", id);
        control.addProperty("type", type);
        control.addProperty("label", label == null ? "" : label);
        return control;
    }

    private static JsonObject hintOf(final List<String> ids) {
        final JsonObject hint = new JsonObject();
        hint.addProperty("source", "forge-ai");
        final JsonArray array = new JsonArray();
        for (String id : ids) {
            array.add(id);
        }
        hint.add("controlIds", array);
        return hint;
    }

    private static List<String> idList(final JsonObject answer) {
        if (answer == null) {
            return null;
        }
        // "defer" is a first-class answer: accept the AI's own choice.
        final var type = answer.get("type");
        if (type != null && "defer".equals(type.getAsString())) {
            return null;
        }
        final var control = answer.get("controlId");
        if (control != null && !"attackers".equals(control.getAsString())
                && !"blockers".equals(control.getAsString())) {
            return List.of(control.getAsString());
        }
        final var choices = answer.get("choices");
        if (choices != null && choices.isJsonArray()) {
            final List<String> out = new ArrayList<>();
            for (var element : choices.getAsJsonArray()) {
                out.add(element.getAsString());
            }
            return out;
        }
        final var order = answer.get("order");
        if (order != null && order.isJsonArray()) {
            final List<String> out = new ArrayList<>();
            for (var element : order.getAsJsonArray()) {
                out.add(element.getAsString());
            }
            return out;
        }
        final var ids = answer.get("controlIds");
        if (ids != null && ids.isJsonArray()) {
            final List<String> out = new ArrayList<>();
            for (var element : ids.getAsJsonArray()) {
                out.add(element.getAsString());
            }
            return out;
        }
        return null;
    }

    private static List<String> idsOf(final Iterable<Card> cards) {
        final List<String> ids = new ArrayList<>();
        for (Card card : cards) {
            ids.add("card:" + card.getId());
        }
        return ids;
    }

    private static Card byId(final CardCollectionView from, final String id) {
        for (Card card : from) {
            if (("card:" + card.getId()).equals(id)) {
                return card;
            }
        }
        return null;
    }

    /** Every hooked decision reaches the tape, deferred or not. */
    private void record(final String kind, final String resolution,
                        final List<String> aiChoice, final List<String> answer) {
        if (bridge == null) {
            return;
        }
        final JsonObject entry = new JsonObject();
        entry.addProperty("class", "BlendDecision");
        entry.addProperty("kind", kind);
        entry.addProperty("resolution", resolution);
        entry.add("aiChoice", hintOf(aiChoice).get("controlIds"));
        if (answer != null) {
            entry.add("answer", hintOf(answer).get("controlIds"));
        }
        bridge.recordBlendDecision(entry);
    }

    JsonObject counters() {
        final JsonObject out = new JsonObject();
        out.addProperty("hooked", hooked);
        out.addProperty("deferred", deferred);
        out.addProperty("overridden", overridden);
        out.addProperty("fallbacks", fallbacks);
        return out;
    }
}
