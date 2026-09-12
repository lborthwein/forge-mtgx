package forge.bench;

import com.google.gson.*;
import forge.game.GameEntity;
import forge.game.card.*;
import forge.game.combat.*;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import java.util.*;

/** Exact declaration transport and native validation, never a combat policy.
 * A whole-block restriction may reject after native assignment; the caller
 * permanently invalidates that game instead of undoing or asking another AI. */
final class CombatDeclarationChoices {
    static final String VERSION = "host-combat-declarations-v1";
    final Player player;
    final Combat combat;
    final boolean attack;
    final CardCollection cards = new CardCollection();
    final List<GameEntity> defenders;
    final CardCollection attackers;
    final Map<Card, GameEntity> initialAttackers;
    final Map<Card, Long> timestamps = new IdentityHashMap<>();

    CombatDeclarationChoices(Player player, Combat combat, boolean attack) {
        this.player = Objects.requireNonNull(player);
        this.combat = Objects.requireNonNull(combat);
        this.attack = attack;
        if (combat.getAttackingPlayer().getGame() != player.getGame()) fail("foreign combat");
        if (attack && combat.getAttackingPlayer() != player) fail("wrong attacking player");
        if (!attack && combat.getAllBlockers().stream().anyMatch(c -> c.getController() == player))
            fail("pre-existing declaration for blocking player");
        for (Card card : player.getCreaturesInPlay()) {
            if (attack ? CombatUtil.canAttack(card) : CombatUtil.canBlock(card, combat)) cards.add(card);
        }
        defenders = new ArrayList<>(combat.getDefenders());
        attackers = new CardCollection(combat.getAttackers());
        initialAttackers = new LinkedHashMap<>(combat.getAttackersAndDefenders());
        for (Card c : cards) timestamps.put(c, c.getGameTimestamp());
        for (Card c : attackers) timestamps.put(c, c.getGameTimestamp());
        for (GameEntity d : defenders) if (d instanceof Card c) timestamps.put(c, c.getGameTimestamp());
    }

    void encode(JsonObject body) {
        body.addProperty("combatDeclarationVersion", VERSION);
        body.add(attack ? "legalAttackers" : "legalBlockers", StateEncoder.encodeCards(cards));
        JsonObject pairs = new JsonObject();
        if (attack) {
            body.add("legalDefenders", StateEncoder.encodeEntities(defenders));
            JsonObject typed = new JsonObject();
            for (Card c : cards) {
                JsonArray ids = new JsonArray(), refs = new JsonArray();
                for (GameEntity d : defenders) if (CombatUtil.canAttack(c, d)) {
                    ids.add(d.getId()); refs.add(StateEncoder.entityRef(d));
                }
                pairs.add(String.valueOf(c.getId()), ids); typed.add(String.valueOf(c.getId()), refs);
            }
            body.add("legalPairsTyped", typed);
        } else {
            body.add("attackers", StateEncoder.encodeCards(attackers));
            JsonObject minimum = new JsonObject();
            for (Card a : attackers) minimum.addProperty(String.valueOf(a.getId()),
                    CombatUtil.getMinNumBlockersForAttacker(a, player));
            body.add("minBlockers", minimum);
            for (Card c : cards) {
                JsonArray ids = new JsonArray();
                for (Card a : attackers) if (CombatUtil.canBlock(a, c, combat)) ids.add(a.getId());
                pairs.add(String.valueOf(c.getId()), ids);
            }
        }
        body.add("legalPairs", pairs);
    }

    private static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("0|[1-9][0-9]*")) fail("noncanonical integer");
        try { return Integer.parseInt(value.getAsString()); }
        catch (NumberFormatException invalid) { throw new RulesCostFeasibility.Unsupported("combat integer overflow"); }
    }

    private static Card card(Iterable<Card> cards, int id) {
        for (Card c : cards) if (c.getId() == id) return c;
        throw new RulesCostFeasibility.Unsupported("unknown combat card " + id);
    }

    private GameEntity defender(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) fail("defender requires typed reference");
        JsonObject ref = raw.getAsJsonObject();
        if (ref.size() != 2 || !ref.has("kind") || !ref.get("kind").isJsonPrimitive()
                || !ref.get("kind").getAsJsonPrimitive().isString()) fail("invalid defender reference");
        String kind = ref.get("kind").getAsString(); int id = integer(ref.get("id"));
        if (!kind.equals("card") && !kind.equals("player")) fail("invalid defender kind");
        for (GameEntity d : defenders) if (d.getId() == id && kind.equals(d instanceof Player ? "player" : "card")) return d;
        throw new RulesCostFeasibility.Unsupported("unknown combat defender");
    }

    private void requireLive(Card c) {
        if (!Objects.equals(timestamps.get(c), c.getGameTimestamp())
                || c.getController().getCardsIn(ZoneType.Battlefield).stream().noneMatch(live -> live == c))
            fail("stale combat card instance");
    }

    JsonObject apply(JsonObject answer) {
        if (answer == null || !answer.has("pairs") || !answer.get("pairs").isJsonArray())
            fail("explicit pairs answer required");
        if (!combat.getAttackersAndDefenders().equals(initialAttackers)) fail("attacker declaration changed during request");
        if (!attack && combat.getAllBlockers().stream().anyMatch(c -> c.getController() == player))
            fail("combat changed during block request");
        Map<Card, GameEntity> attackIntent = new LinkedHashMap<>();
        List<Map.Entry<Card, Card>> blockIntent = new ArrayList<>();
        Set<String> distinct = new HashSet<>();
        for (JsonElement element : answer.getAsJsonArray("pairs")) {
            if (!element.isJsonArray() || element.getAsJsonArray().size() != 2) fail("invalid combat pair");
            JsonArray pair = element.getAsJsonArray();
            Card c = card(cards, integer(pair.get(0)));
            requireLive(c);
            if (c.getController() != player || !player.getCreaturesInPlay().contains(c)) fail("stale combat source");
            if (attack) {
                GameEntity d = defender(pair.get(1));
                if (d instanceof Card dc) requireLive(dc);
                if (!combat.getDefenders().contains(d) || !CombatUtil.canAttack(c, d)) fail("illegal attacker/defender pair");
                if (attackIntent.put(c, d) != null) fail("duplicate attacker");
            } else {
                Card a = card(attackers, integer(pair.get(1)));
                requireLive(a);
                if (!distinct.add(c.getId() + ":" + a.getId())) fail("duplicate block pair");
                if (!CombatUtil.canBlock(a, c, combat)) fail("illegal blocker/attacker pair");
                blockIntent.add(Map.entry(c, a));
            }
        }
        if (attack) {
            AttackConstraints constraints = combat.getAttackConstraints();
            int violations = constraints.countViolations(attackIntent);
            if (violations < 0 || violations > constraints.getLegalAttackers().getRight())
                fail("native attack constraints reject declaration");
            // A failed attack-cost attempt can legitimately re-enter declaration.
            // Preserve its prior declaration until the replacement intent validates.
            combat.clearAttackers();
            for (var entry : attackIntent.entrySet()) combat.addAttacker(entry.getKey(), entry.getValue());
            if (!CombatUtil.validateAttackers(combat) || !combat.getAttackersAndDefenders().equals(attackIntent))
                fail("native attack declaration differs from intent");
        } else {
            for (var pair : blockIntent) {
                // Native capacity checks depend on the already assigned blockers.
                if (!CombatUtil.canBlock(pair.getValue(), pair.getKey(), combat)) fail("native block capacity rejected");
                combat.addBlocker(pair.getValue(), pair.getKey());
            }
            String problem = CombatUtil.validateBlocks(combat, player);
            if (problem != null) fail("native block constraints: " + problem);
            Set<String> actual = new HashSet<>();
            for (Card b : combat.getAllBlockers()) if (b.getController() == player)
                for (Card a : combat.getAttackersBlockedBy(b)) actual.add(b.getId() + ":" + a.getId());
            if (!actual.equals(distinct)) fail("native block declaration differs from intent");
        }
        JsonObject receipt = new JsonObject();
        receipt.addProperty("schema", VERSION);
        receipt.addProperty("kind", attack ? "attackers-assigned" : "blockers-assigned");
        receipt.addProperty("declaringFor", player.getId());
        receipt.add("pairs", answer.getAsJsonArray("pairs").deepCopy());
        receipt.addProperty("nativeValidated", true);
        // Not a receipt for later optional/required attack costs or trigger execution.
        receipt.addProperty("scope", "controller-declaration-return");
        return receipt;
    }

    private static void fail(String reason) { throw new RulesCostFeasibility.Unsupported("combat declaration: " + reason); }
}
