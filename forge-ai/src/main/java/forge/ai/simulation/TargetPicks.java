package forge.ai.simulation;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * target-picks-1119: the public face of {@link LineOwnTarget}'s classification for the pick guard ({@code ownHarmPicks})
 * and the pick log ({@code targetLog}) of the policy seat's bridged controller ({@code forge.bench}). Nothing here is
 * new classification: every answer is {@link LineOwnTarget}'s. Read-only; never throws.
 */
public final class TargetPicks {
    private TargetPicks() {
    }

    /**
     * Why the targets the part {@code part} of the ability {@code root} holds now are pure, payoff-free harm to a permanent
     * {@code me} controls ({@link LineOwnTarget#pickHarm}: the search's own-payoff exception), or null.
     */
    public static String ownHarm(final Player me, final SpellAbility part, final SpellAbility root) {
        return LineOwnTarget.pickHarm(me, part, root);
    }

    /**
     * Review 2 (affirmative coverage): the one predicate the pick guard reads before it retargets or declines an own-harm
     * pick of the part {@code part} of {@code root} ({@link LineOwnTarget#plainAsset}). Null = every permanent of ours it
     * harms is a plain asset taking plain loss (the guard may act); otherwise the first condition that fails (the pick
     * stands, {@code kept-unsure}).
     */
    public static String plainAsset(final Player me, final SpellAbility part, final SpellAbility root) {
        return LineOwnTarget.plainAsset(me, part, root);
    }

    /** The harm of one ability part ({@link LineOwnTarget#harm}: damage, destroy, shrink, remove, bounce, sacrifice), or null. */
    public static String harm(final SpellAbility part) {
        try {
            final LineOwnTarget.Harm h = part == null ? null : LineOwnTarget.harm(part);
            return h == null ? null : h.name().toLowerCase();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The part targets only what its controller controls or owns by its own words ("target creature you control"). */
    public static boolean ownByDesign(final SpellAbility part) {
        try {
            return part != null && LineOwnTarget.ownByDesign(part);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Why {@code sa} casts a permanent whose mandatory enters trigger can harm only our own permanents
     * ({@link LineOwnTarget#refuseEtb}), or null.
     */
    public static String etbOwnOnly(final Player me, final SpellAbility sa) {
        return LineOwnTarget.refuseEtb(me, sa);
    }

    /** The side of a target for {@code me}: "own" (a card we control), "opp" (one an opponent controls), "self", "face", "other". */
    public static String side(final Player me, final Object e) {
        if (e instanceof Player) {
            return e.equals(me) ? "self" : ((Player) e).isOpponentOf(me) ? "face" : "other";
        }
        if (e instanceof Card) {
            final Card c = (Card) e;
            if (!c.isInPlay()) {
                return "other";
            }
            return me.equals(c.getController()) ? "own" : c.getController() != null && c.getController().isOpponentOf(me)
                    ? "opp" : "other";
        }
        return "other";
    }

    /** An opposing target for {@code me}: an opponent, or a permanent an opponent controls. */
    public static boolean opposing(final Player me, final GameEntity e) {
        final String s = side(me, e);
        return "opp".equals(s) || "face".equals(s);
    }
}
