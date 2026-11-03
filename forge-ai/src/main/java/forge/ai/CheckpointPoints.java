package forge.ai;

import java.util.ArrayList;
import java.util.List;

import forge.ai.dp.Kind;
import forge.game.GameObject;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;

/**
 * decisionpoint-1102 PR-C: the targets and X Forge AI fixed for an ability while deliberating (CENSUS.md §3, B2/B3/B5/
 * B6), as decision points at the AI tape's checkpoints ({@link AiTape#checkpoint}: {@code h} a cast, {@code s} a trigger
 * or free cast put on the stack, {@code n} an effect played without the stack, {@code c} a copy), right before payment.
 *
 * <ul>
 * <li>{@code seam:targets:<where>} (SELECT): the answer is one {@link TargetChoices} per link of the ability's chain
 * that targets, in chain order; Forge AI's targets are the default. A departure is legal when every link's targets
 * satisfy {@code canTarget} and {@code isTargetNumberValid} (checked on the ability, then restored).</li>
 * <li>{@code seam:x:<where>} (NUMBER): the announced X; a departure is legal when it is >= 0 and the ability stays
 * payable with it (ComputerUtilCost.canPayCost, checked, then restored).</li>
 * </ul>
 * Nothing is built unless the seat has an answerer or the census observer is installed.
 */
final class CheckpointPoints {
    private CheckpointPoints() {
    }

    static void at(final Player ai, final SpellAbility sa, final String where) {
        final PlayerControllerAi pc = PlayerControllerAi.cacOf(ai);
        if (sa == null || pc == null || !pc.dpInstrumented()) {
            return;
        }
        final List<SpellAbility> links = new ArrayList<>();
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting()) {
                links.add(s);
            }
        }
        boolean any = false;
        final List<TargetChoices> def = new ArrayList<>();
        for (SpellAbility s : links) {
            def.add(s.getTargets());
            any |= s.getTargets() != null && !s.getTargets().isEmpty();
        }
        if (any) {
            final List<TargetChoices> out = PlayerControllerAi.seam(ai, Kind.SELECT, "seam:targets:" + where, sa, null,
                    -1, -1, a -> legalTargets(links, a), () -> def, null, where);
            if (out != def) {
                for (int i = 0; i < links.size(); i++) {
                    links.get(i).setTargets(out.get(i));
                }
            }
        }
        final Integer x = sa.getXManaCostPaid();
        if (x != null) {
            final Integer outX = PlayerControllerAi.seam(ai, Kind.NUMBER, "seam:x:" + where, sa, null, 0, -1,
                    a -> legalX(ai, sa, a), () -> x, null, where);
            if (!x.equals(outX)) {
                sa.setXManaCostPaid(outX);
            }
        }
    }

    private static boolean legalTargets(final List<SpellAbility> links, final Object a) {
        if (!(a instanceof List<?> l) || l.size() != links.size()) {
            return false;
        }
        final List<TargetChoices> old = new ArrayList<>();
        for (SpellAbility s : links) {
            old.add(s.getTargets());
        }
        try {
            for (int i = 0; i < links.size(); i++) {
                if (!(l.get(i) instanceof TargetChoices tc)) {
                    return false;
                }
                links.get(i).setTargets(tc);
            }
            for (SpellAbility s : links) {
                if (!s.isTargetNumberValid()) {
                    return false;
                }
                for (GameObject o : s.getTargets()) {
                    if (!s.canTarget(o)) {
                        return false;
                    }
                }
            }
            return true;
        } finally {
            for (int i = 0; i < links.size(); i++) {
                links.get(i).setTargets(old.get(i));
            }
        }
    }

    private static boolean legalX(final Player ai, final SpellAbility sa, final Object a) {
        if (!(a instanceof Integer n) || n < 0) {
            return false;
        }
        final Integer old = sa.getXManaCostPaid();
        try {
            sa.setXManaCostPaid(n);
            return ComputerUtilCost.canPayCost(sa, ai, false);
        } finally {
            sa.setXManaCostPaid(old);
        }
    }
}
