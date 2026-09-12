package forge.bench;

import forge.game.GameEntity;
import forge.game.ability.AbilityUtils;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.collect.FCollectionView;
import java.util.IdentityHashMap;

/** Rules-only, identity-bound routing for native setupTargets. No policy or RNG. */
final class TargetingPlayerRouting {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private TargetingPlayerRouting() {}

    static Player chooser(final SpellAbility ability) {
        final Player actor = ability.getActivatingPlayer();
        if (actor == null) throw unsupported("missing activating player");
        if (!ability.hasParam("TargetingPlayer")) return actor;
        final String definition = ability.getParam("TargetingPlayer");
        if ("TriggeredPlayer".equals(definition)) {
            final var root = ability.getRootAbility();
            final Object triggered = root.getTriggeringObject(forge.game.ability.AbilityKey.Player);
            final var players = AbilityUtils.getDefinedPlayers(ability.getHostCard(), definition, ability);
            if (!root.isTrigger() || root.getTrigger() == null || !(triggered instanceof Player player)
                    || player.getGame() != actor.getGame() || !player.isInGame()
                    || players.size() != 1 || players.get(0) != player)
                throw unsupported("targeting-player is not one exact engine-triggered player");
            return player;
        }
        if (!"Player.Opponent".equals(definition) && !"Opponent".equals(definition))
            throw unsupported("unrepresented targeting-player definition: " + definition);
        final var players = AbilityUtils.getDefinedPlayers(ability.getHostCard(), definition, ability);
        if (players.size() != 1 || players.get(0) == actor
                || players.get(0).getGame() != actor.getGame())
            throw unsupported("targeting-player domain is not one exact opponent");
        return players.get(0);
    }

    static Scope open(final SpellAbility root, final Player actor) {
        return new Scope(root, actor);
    }

    static Player forcedChooser(final Player controller, final SpellAbility ability,
            final FCollectionView<? extends GameEntity> options, final String title,
            final boolean optional, final Object reveal, final Player related, final Object params) {
        final Scope scope = CURRENT.get();
        if (scope == null || ability == null || !ability.hasParam("TargetingPlayer")) return null;
        final Player expected = scope.require(ability);
        if (controller != scope.actor || optional || reveal != null || related != null || params != null
                || !"Choose the targeting player".equals(title) || options == null || options.size() != 1
                || options.get(0) != expected)
            throw unsupported("native targeting-player selection does not match exact mandatory domain");
        return expected;
    }

    static void requireController(final SpellAbility ability, final Player controller, final boolean host) {
        final Scope scope = CURRENT.get();
        if (scope != null) {
            if (scope.require(ability) != controller || ability.getTargetingPlayer() != controller)
                throw unsupported("target callback sent to wrong scoped chooser");
        } else if (host && ability != null && ability.hasParam("TargetingPlayer")) {
            // Native Default's deferred target callback runs outside a host cast scope.
            // It must still reach the actual chooser, never AI on behalf of a host.
            if (chooser(ability) != controller || ability.getTargetingPlayer() != controller)
                throw unsupported("native deferred target callback sent to wrong chooser");
        }
    }

    static final class Scope implements AutoCloseable {
        private final Scope previous;
        private final SpellAbility root;
        private final Player actor;
        private final IdentityHashMap<SpellAbility,Player> groups = new IdentityHashMap<>();
        private final IdentityHashMap<SpellAbility,forge.game.card.Card> sources = new IdentityHashMap<>();
        private final IdentityHashMap<SpellAbility,SpellAbility> children = new IdentityHashMap<>();
        private boolean closed;
        private Scope(final SpellAbility root, final Player actor) {
            this.root = root; this.actor = actor; this.previous = CURRENT.get();
            for (SpellAbility group = root; group != null; group = group.getSubAbility()) {
                if (groups.containsKey(group) || group.getActivatingPlayer() != actor
                        || group.getHostCard().getGame() != actor.getGame())
                    throw unsupported("invalid target chain identity or actor");
                groups.put(group, chooser(group)); sources.put(group, group.getHostCard());
                children.put(group, group.getSubAbility());
            }
            CURRENT.set(this);
        }
        private Player require(final SpellAbility ability) {
            if (closed || !groups.containsKey(ability) || ability.getActivatingPlayer() != actor
                    || ability.getHostCard() != sources.get(ability))
                throw unsupported("target callback escaped bound ability chain");
            SpellAbility current = root;
            int count = 0;
            while (current != null && count++ <= groups.size()) {
                if (!groups.containsKey(current) || current.getSubAbility() != children.get(current))
                    throw unsupported("target chain changed inside routing scope");
                current = current.getSubAbility();
            }
            if (current != null || count != groups.size()) throw unsupported("target chain changed inside routing scope");
            if (chooser(ability) != groups.get(ability)) throw unsupported("targeting-player domain changed inside routing scope");
            return groups.get(ability);
        }
        @Override public void close() {
            if (closed || CURRENT.get() != this) throw unsupported("target scope unwind mismatch");
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    private static RulesCostFeasibility.Unsupported unsupported(final String message) {
        return new RulesCostFeasibility.Unsupported(message);
    }
}
