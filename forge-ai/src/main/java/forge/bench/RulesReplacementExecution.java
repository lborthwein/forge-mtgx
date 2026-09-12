package forge.bench;

import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;

/** Single-use authority supplied only around ReplacementHandler's selected
 * no-stack callback. Not authority for optional decisions, targets, or costs. */
final class RulesReplacementExecution implements AutoCloseable {
    private final Player payer;
    private final ReplacementEffect replacement;
    private final SpellAbility ability;
    private boolean consumed;
    private boolean closed;

    RulesReplacementExecution(Player payer, ReplacementEffect replacement, SpellAbility ability) {
        this.payer=payer; this.replacement=replacement; this.ability=ability;
        validate(payer,ability);
    }

    private void validate(Player actualPayer, SpellAbility actual) {
        if (closed || payer==null || actualPayer!=payer || actual==null || actual!=ability
                || replacement==null || !replacement.hasRun() || replacement.getOverridingAbility()!=ability
                || ability.getReplacementEffect()!=replacement || ability.getActivatingPlayer()!=payer
                || ability.getHostCard()==null || ability.getHostCard().getGame()!=payer.getGame()
                || replacement.getHostCard().getGame()!=payer.getGame()
                || ability.isSpell() || ability.isTrigger() || ability.isCopied())
            fail("changed, inactive or unrelated replacement callback");
    }

    void consume(Player actualPayer, SpellAbility actual, boolean mayChooseNewTargets) {
        validate(actualPayer,actual);
        if (consumed || !mayChooseNewTargets) fail("repeated or non-native no-stack callback");
        consumed=true;
    }

    void requirePayment(Player actualPayer, SpellAbility actual) {
        validate(actualPayer,actual);
        if (!consumed) fail("payment precedes exact replacement callback");
    }

    void finish() { if (closed || !consumed) fail("replacement callback was not executed exactly once"); }
    @Override public void close() { closed=true; }
    private static void fail(String message) {
        throw new RulesCostFeasibility.Unsupported("replacement execution: "+message);
    }
}
