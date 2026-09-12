package forge.game.combat;

import forge.game.GameEntity;
import forge.game.card.*;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.zone.ZoneType;
import java.util.*;

/** Engine-created assignment context, not an AI heuristic. CR 510.1e checks the
 * aggregate allocation; a partial trample assignment can become legal when a
 * later creature assigns the rest of lethal damage to a shared blocker. */
public final class CombatDamageAssignment {
    public final Combat combat;
    public final PlayerController controller;
    public final Card source;
    public final CardCollection recipients;
    public final CardCollection remaining;
    public final int damage;
    public final GameEntity defender;
    public final boolean overrideOrder, dividedAsChosen, attacking, allowDefender;
    public final Card throughPlaneswalker;
    private final Map<Card, Long> visits = new IdentityHashMap<>();
    private final Map<Card, List<Object>> characteristics = new IdentityHashMap<>();
    private final Map<Card, GameEntity> attackers;
    private final Map<Card, Set<Card>> blocks = new IdentityHashMap<>();
    private final CardDamageTable liveAssignments, priorAssignments;
    private Map<Card, Integer> accepted;
    private boolean deferred, completed;

    public CombatDamageAssignment(Combat combat, PlayerController controller, Card source,
            CardCollectionView recipients, CardCollectionView remaining, int damage,
            GameEntity defender, boolean overrideOrder, boolean dividedAsChosen,
            boolean attacking, Card throughPlaneswalker, CardDamageTable assignments) {
        this.combat = Objects.requireNonNull(combat);
        this.controller = Objects.requireNonNull(controller);
        this.source = Objects.requireNonNull(source);
        this.recipients = new CardCollection(recipients);
        this.remaining = remaining == null ? null : new CardCollection(remaining);
        this.damage = Math.max(0, damage);
        this.defender = defender;
        this.overrideOrder = overrideOrder;
        this.dividedAsChosen = dividedAsChosen;
        this.attacking = attacking;
        this.throughPlaneswalker = throughPlaneswalker;
        this.liveAssignments = assignments;
        this.priorAssignments = new CardDamageTable(assignments);
        this.allowDefender = defender != null && (dividedAsChosen
                || attacking && (source.hasKeyword(Keyword.TRAMPLE) || throughPlaneswalker != null));
        attackers = new LinkedHashMap<>(combat.getAttackersAndDefenders());
        for (Card a : attackers.keySet()) blocks.put(a, new HashSet<>(combat.getBlockers(a)));
        remember(source);
        for (Card c : this.recipients) remember(c);
        if (defender instanceof Card c) remember(c);
        if (throughPlaneswalker != null && !this.recipients.contains(throughPlaneswalker)) fail("missing planeswalker recipient");
        requireCurrent();
    }

    private static void fail(String message) { throw new IllegalStateException("Combat damage integrity: " + message); }
    private List<Object> properties(Card c) {
        return List.of(c.getController(), c.getType().toString(), c.getLethal(), c.getDamage(),
                c.getCurrentLoyalty(), c.getCurrentDefense(), c.getNetCombatDamage(),
                c.hasKeyword(Keyword.DEATHTOUCH), c.hasKeyword(Keyword.TRAMPLE));
    }
    private void remember(Card c) { visits.put(c, c.getGameTimestamp()); characteristics.put(c, properties(c)); }

    public void requireCurrent() {
        if (combat != source.getGame().getCombat() || controller.getGame() != source.getGame()) fail("foreign or stale combat");
        if (!attackers.equals(combat.getAttackersAndDefenders())) fail("attacker/defender mapping changed");
        for (var e : blocks.entrySet()) if (!e.getValue().equals(new HashSet<>(combat.getBlockers(e.getKey())))) fail("blockers changed");
        for (Card c : visits.keySet()) {
            if (visits.get(c) != c.getGameTimestamp()
                    || c.getController().getCardsIn(ZoneType.Battlefield).stream().noneMatch(live -> live == c)
                    || !characteristics.get(c).equals(properties(c))) fail("combat card changed during assignment");
        }
    }

    public boolean mayDefer() { return attacking && remaining != null && remaining.size() > 1; }

    /** For the prompt only: what this recipient still needs at this moment.
     * Final validation uses the whole damage table, not this partial estimate. */
    public int remainingLethal(Card card) {
        int assigned = priorAssignments.column(card).values().stream().mapToInt(Integer::intValue).sum();
        int creatureRequired = Math.max(0, card.getLethal() - card.getDamage() - assigned);
        if (priorAssignments.column(card).entrySet().stream()
                .anyMatch(e -> e.getValue() > 0 && e.getKey().hasKeyword(Keyword.DEATHTOUCH))) creatureRequired = 0;
        if (source.hasKeyword(Keyword.DEATHTOUCH)) creatureRequired = Math.min(creatureRequired, 1);
        if (card == throughPlaneswalker) return Math.max(Math.max(0, card.getCurrentLoyalty() - assigned),
                combat.isBlocking(card, source) ? creatureRequired : 0);
        return creatureRequired;
    }

    private int baseLethal(Card card) {
        if (card == throughPlaneswalker) {
            int n = Math.max(0, card.getCurrentLoyalty());
            // A planeswalker may also itself be a blocking creature.
            if (combat.isBlocking(card, source)) n = Math.max(n, Math.max(0, card.getLethal() - card.getDamage()));
            return n;
        }
        return Math.max(0, card.getLethal() - card.getDamage());
    }

    /** Strict domain/sum validation before native mutation. No per-source
     * lethal restriction here: later simultaneous assignments may supply it. */
    public Map<Card, Integer> accept(Map<Card, Integer> proposed) {
        requireCurrent();
        if (!liveAssignments.cellSet().equals(priorAssignments.cellSet())) fail("assignments changed during host callback");
        if (accepted != null || deferred || completed) fail("reused assignment");
        if (proposed == null) {
            if (!mayDefer()) fail("cannot defer this assignment");
            deferred = true; return null;
        }
        Map<Card, Integer> exact = new LinkedHashMap<>(); long sum = 0;
        for (var e : proposed.entrySet()) {
            Card card = e.getKey(); Integer amount = e.getValue();
            if (amount == null || amount < 0) fail("negative/null amount");
            if (card == null ? !allowDefender : recipients.stream().noneMatch(c -> c == card)) fail("illegal recipient");
            sum += amount;
            if (amount > 0) exact.put(card, amount); // Never return a null-key zero to blocker application.
        }
        if (sum != damage) fail("wrong damage total");
        accepted = Collections.unmodifiableMap(exact);
        return new LinkedHashMap<>(exact);
    }

    private boolean lethal(Card card, CardDamageTable table) {
        long sum = 0; boolean deathtouch = false;
        for (var e : table.column(card).entrySet()) {
            sum += e.getValue();
            if (e.getValue() > 0 && e.getKey().hasKeyword(Keyword.DEATHTOUCH)) deathtouch = true;
        }
        if (card == throughPlaneswalker) {
            if (sum < Math.max(0, card.getCurrentLoyalty())) return false;
            return !combat.isBlocking(card, source) || deathtouch || sum >= Math.max(0, card.getLethal() - card.getDamage());
        }
        return deathtouch || sum >= baseLethal(card);
    }

    /** Verify actual native application and aggregate legality before damage
     * is dealt. This validates assignment, not prevention/replacement/resolution. */
    public void verifyComplete(CardDamageTable table) {
        requireCurrent();
        if (completed) fail("repeated completion");
        if (deferred) { completed = true; return; }
        if (accepted == null) fail("missing completion");
        Map<GameEntity, Integer> expected = new LinkedHashMap<>();
        for (var e : accepted.entrySet()) expected.merge(e.getKey() == null ? defender : e.getKey(), e.getValue(), Integer::sum);
        Map<GameEntity, Integer> actual = new LinkedHashMap<>(table.row(source));
        actual.entrySet().removeIf(e -> e.getValue() == 0);
        if (!expected.equals(actual)) fail("native application differs from host assignment");
        if (!dividedAsChosen) {
            boolean through = accepted.getOrDefault(null, 0) > 0;
            boolean toWalker = throughPlaneswalker != null && accepted.getOrDefault(throughPlaneswalker, 0) > 0;
            for (int i = 0; i < recipients.size(); i++) {
                Card card = recipients.get(i);
                boolean later = false;
                if (!overrideOrder) for (int j = i + 1; j < recipients.size(); j++)
                    later |= accepted.getOrDefault(recipients.get(j), 0) > 0;
                if ((through || later || toWalker && card != throughPlaneswalker) && !lethal(card, table))
                    fail("aggregate assignment does not satisfy lethal requirement");
            }
        }
        completed = true;
    }

    public Map<Card, Integer> accepted() {
        if (accepted == null) fail("no accepted assignment");
        return accepted;
    }

    public boolean isComplete() { return completed; }
    public boolean isDeferred() { return deferred; }
}
