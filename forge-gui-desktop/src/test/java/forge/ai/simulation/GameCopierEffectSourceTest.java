package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityNumLoyaltyAct;
import forge.game.zone.ZoneType;

/**
 * void-causes-1104 (S2 label void, game 2989384742, sneak-show, frozen jar 9489c3db; deterministic): the label search's
 * copy of a position held an effect whose static ability reads the ability that made it (here the planeswalker's "you
 * may activate this loyalty ability two more times this turn"). GameCopier copied the effect's source CARD only, so in
 * every copy {@code getEffectSourceAbility()} was null and StaticAbilityNumLoyaltyAct.additionalActivations threw
 * NullPointerException from R3LabelSearch.candidates (Card.getAllPossibleAbilities -> canPlay). After many throws C2
 * made it a frameless fast throw, which escaped the label row and crashed the replay.
 *
 * <p>The copy now carries the source ability (the copied host's own ability, marked as played from it); an effect whose
 * source ability is unknown grants no extra activation instead of crashing.
 */
public class GameCopierEffectSourceTest extends forge.ai.AITest {

    static final String PW = "Comet, Stellar Pup"; // a measurement fixture names its card (GATE-TIERS §4)

    /** The position: the planeswalker in play and the effect its die result 6 makes, made as a live game makes it. */
    static Object[] position() {
        final WinConditionCoverageTest.P p = new WinConditionCoverageTest.P("es", WinConditionCoverageTest.V.NOT_PROVEN, "es")
                .k("humanbattlefield", PW + "|Counters:LOYALTY=5").k("ailife", "20");
        final Game g = ForcedWinSolverTest.game("Default", "Default", p.frame());
        Card pw = null;
        for (Card c : g.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(PW)) {
                pw = c;
            }
        }
        Assert.assertNotNull(pw);
        final Player me = pw.getController();
        SpellAbility printed = null;
        for (SpellAbility sa : pw.getSpellAbilities()) {
            if (sa.isPwAbility()) {
                printed = sa;
            }
        }
        Assert.assertNotNull(printed);
        // as MagicStack#add: the activation is a fresh copy marked as played from the printed ability
        final SpellAbility played = printed.copy(pw, me, false, true);
        played.setOriginalAbility(printed);
        final AbilitySub effect = (AbilitySub) AbilityFactory.getAbility(pw.getSVar("DBEffect"), pw);
        effect.setActivatingPlayer(me);
        effect.setParent(played);
        AbilityUtils.resolve(effect);
        Card eff = null;
        for (Card c : g.getCardsIn(ZoneType.Command)) {
            if (c.getEffectSource() == pw) {
                eff = c;
            }
        }
        Assert.assertNotNull(eff, "the effect");
        Assert.assertSame(eff.getEffectSourceAbility(), effect);
        return new Object[] {g, pw, printed, eff};
    }

    static SpellAbility pwAbility(final Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isPwAbility()) {
                return sa;
            }
        }
        return null;
    }

    @Test
    public void theCopyKeepsTheEffectsSourceAbility() {
        final Object[] pos = position();
        final Game g = (Game) pos[0];
        final Card pw = (Card) pos[1];
        final SpellAbility printed = (SpellAbility) pos[2];
        Assert.assertEquals(StaticAbilityNumLoyaltyAct.additionalActivations(pw, printed), 2);

        // the label search's copies (R3LabelSearch.candidates, ForcedWinEngine): copy fidelity 1, which gives an effect
        // card its source card (Card#copyTurnStateFrom)
        final GameCopier copier = new GameCopier(g, true);
        copier.setCopyFidelity(1);
        final Game copy = copier.makeCopy();
        final Card pwCopy = (Card) copier.find(pw);
        Card effCopy = null;
        for (Card c : copy.getCardsIn(ZoneType.Command)) {
            if (c.getEffectSource() == pwCopy) {
                effCopy = c;
            }
        }
        Assert.assertNotNull(effCopy, "the copied effect");
        Assert.assertNotNull(effCopy.getEffectSourceAbility(), "the copy dropped the effect's source ability");
        Assert.assertSame(effCopy.getEffectSourceAbility().getHostCard(), pwCopy);
        final SpellAbility copied = pwAbility(pwCopy);
        // before the fix: NullPointerException here (the label void's stack)
        Assert.assertEquals(StaticAbilityNumLoyaltyAct.additionalActivations(pwCopy, copied), 2);
        // and the search's own enumeration of the copy's abilities
        Assert.assertFalse(forge.ai.ComputerUtilAbility.getSpellAbilities(pwCopy.getController().getCardsIn(
                ZoneType.Battlefield), pwCopy.getController()).isEmpty());
    }

    @Test
    public void anEffectWithoutItsSourceAbilityGrantsNothingAndDoesNotCrash() {
        final Object[] pos = position();
        final Card pw = (Card) pos[1];
        final SpellAbility printed = (SpellAbility) pos[2];
        final Card eff = (Card) pos[3];
        eff.setEffectSource((SpellAbility) null);
        eff.setEffectSource(pw);
        Assert.assertEquals(StaticAbilityNumLoyaltyAct.additionalActivations(pw, printed), 0);
    }
}
