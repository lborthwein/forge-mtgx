package forge.ai;

import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.cost.CostPayment;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;

/**
 * mtgx fork (lane rl-r0-b4b-1006): the AI never makes, and never pays for, an activation whose targets break their own
 * count.
 *
 * <p>The defect. Forge's stack refuses an activation when some ability of its chain has fewer (or more) targets than
 * its own count ({@code MagicStack.add}: "Couldn't add to stack, failed to target"). The AI's play path
 * ({@code ComputerUtil.handlePlayingSpellAbility}) pays the costs first and never looks at the refusal, and the stack
 * refuses before it counts the activation. So the cost stayed paid for an activation that never happened, and the AI
 * could choose it again: in W's corpus (20,000 games) Koth of the Hammer's +1 was refused 999 times in one turn and
 * its loyalty went from 3 to 1001; Garruk Wildspeaker's +1 was refused 65 times (a free loyalty counter and a second
 * activation that turn); Tear Asunder 4 times (a target left on the ability whose count is 0 on that cast). Under the
 * comprehensive rules an illegal action is reversed entirely, costs included.
 *
 * <p>The fix, in three places, each only where the stack would refuse:
 * <ul>
 * <li>AI choice ({@code AiController.canPlaySa}): an activation whose targets the stack would refuse is
 * {@code TargetingFailed}. Targets left on an ability whose target count is 0 on this activation are dropped first
 * (they can never be legal; the rest of the activation is);</li>
 * <li>AI targeting ({@code UntapAi}): a +loyalty (or AITapDown) untap ability activated "for the cost" completes its
 * minimum number of targets with the least valuable legal ones instead of leaving them empty;</li>
 * <li>engine ({@code ComputerUtil.handlePlayingSpellAbility}, {@code playStack} for non-triggers): the stack's own test
 * is applied before any cost is paid, and an activation it would refuse is reversed with the engine's own rollback
 * ({@code GameActionUtil.rollbackAbility}: the spell back where it was, nothing paid), with one line to stdout. The
 * AI's payment ({@code CostPayment.payComputerCosts}) keeps no record of what it paid, so the reversal has to come
 * before payment. If paying itself makes the targets illegal (an X chosen while paying), the stack still refuses it
 * after payment, as upstream, and a line is logged. With the AI choice fixed this is a second line.</li>
 * </ul>
 * A game in which the stack refuses no activation plays exactly as without the fix: every check is a read that draws
 * no random numbers, and each change applies only to an activation the stack would refuse.
 *
 * <p>On by default (a rules-correctness fix: it changes only rules-illegal outcomes). System property
 * {@code forge.ai.legalActivation=false} turns all three parts off and reproduces the defect.
 */
public final class AiLegalActivation {

    private static volatile boolean enabled = !"false".equalsIgnoreCase(System.getProperty("forge.ai.legalActivation"));

    private AiLegalActivation() {
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Tests and benches only. */
    public static void setEnabled(final boolean on) {
        enabled = on;
    }

    /** Another player chooses some ability's targets when it is played (TargetingPlayer): not set at choice time. */
    static boolean deferredTargeting(final SpellAbility sa) {
        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            if (cur.hasParam("TargetingPlayer")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drop targets left on an ability whose target count is 0 on this activation (a kicker-only target of an unkicked
     * spell, an unkicked-only target of a kicked one): they can never be legal, and they make the stack refuse the
     * whole activation. Only when that ability's targets are already invalid, so a legal activation is never touched.
     */
    static void dropOffTargets(final SpellAbility sa) {
        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            if (cur.usesTargeting() && !cur.getTargets().isEmpty() && cur.getMaxTargets() == 0
                    && !cur.isTargetNumberValid()) {
                cur.resetTargets();
            }
        }
    }

    /** X is still to be chosen while paying (an X cost with no X yet): target counts that depend on X are not final. */
    static boolean xPending(final SpellAbility sa) {
        final SpellAbility root = sa.getRootAbility();
        try {
            return root.getPayCosts() != null && root.getPayCosts().getTotalMana().countX() > 0
                    && root.getXManaCostPaid() == null;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Whether the stack would accept {@code sa} with the targets it carries now ({@code MagicStack.hasLegalTargeting}),
     * after {@link #dropOffTargets}. True when the fix is off, for copies (the stack does not check them), when another
     * player chooses targets at play time, and while X is still to be chosen in payment.
     */
    public static boolean legalTargeting(final Game game, final SpellAbility sa) {
        if (!enabled || sa == null || sa.isCopied() || deferredTargeting(sa) || xPending(sa)) {
            return true;
        }
        if (game.getStack().hasLegalTargeting(sa)) {
            return true;
        }
        dropOffTargets(sa);
        return game.getStack().hasLegalTargeting(sa);
    }

    /** After payment: the stack will still refuse it (paying changed its targets' legality): log it, as upstream. */
    public static void logIfRefusedAfterPayment(final Game game, final SpellAbility sa) {
        if (enabled && sa != null && !sa.isCopied() && !deferredTargeting(sa) && !game.getStack().hasLegalTargeting(sa)) {
            System.out.println("[ai-legal-activation] " + sa.getActivatingPlayer() + " " + sa.getHostCard()
                    + ": targets became illegal while paying; the stack refuses it (targets " + sa.getAllTargetChoices()
                    + "): " + sa);
        }
    }

    /**
     * Engine side: reverse an activation the stack would refuse, before it is paid for, as a player's cancelled
     * activation is rolled back (the spell back to {@code fromZone}), and log one line.
     */
    public static void rollback(final SpellAbility sa, final Zone fromZone, final int zonePosition,
            final CostPayment payment, final Card oldCard, final String when) {
        System.out.println("[ai-legal-activation] " + sa.getActivatingPlayer() + " " + oldCard + ": the stack would "
                + "refuse it (targets " + sa.getAllTargetChoices() + "), rolled back " + when + ": " + sa);
        GameActionUtil.rollbackAbility(sa, fromZone, zonePosition, payment, oldCard);
    }
}
