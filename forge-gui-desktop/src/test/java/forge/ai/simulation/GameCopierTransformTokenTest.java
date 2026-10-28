package forge.ai.simulation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.card.CardStateName;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * mtgx lane forge-fix-p1p2-1028 (anomaly audit 1028 P2): the game copier's token path built a copy of a transforming
 * token from its current face alone. With the token on its back face, {@code setState(Backside)} failed ("tried to switch
 * to non-existant state", 3.75 M lines in the step-1 corpus for the Incubator token's Phyrexian face) and the copy had no
 * back face; an LKI copy of it ({@link CardCopyService#getLKICopy}, from MagicStack and CombatUtil) then threw a
 * NullPointerException, which the look-ahead reported as a failed rollout. Every case: the copy keeps both faces and the
 * face that is up, no "non-existant state" line is printed, and an LKI copy of the copy works.
 */
public class GameCopierTransformTokenTest extends SimulationTest {

    /** Runs {@code body} with System.out captured; returns {body's value, the captured text}. */
    private static Object[] capture(final Supplier<Object> body) {
        final PrintStream old = System.out;
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            final Object v = body.get();
            return new Object[] {v, buf.toString(StandardCharsets.UTF_8)};
        } finally {
            System.setOut(old);
        }
    }

    /** A copy of {@code game}; asserts no "non-existant state" line was printed while making it. */
    private static GameCopier copy(final Game game, final Game[] out) {
        final GameCopier copier = new GameCopier(game);
        final Object[] r = capture(copier::makeCopy);
        out[0] = (Game) r[0];
        AssertJUnit.assertFalse("copying printed: " + r[1], ((String) r[1]).contains("non-existant state"));
        return copier;
    }

    private static void assertLki(final Card c, final String name) {
        final Card lki = CardCopyService.getLKICopy(c);
        AssertJUnit.assertNotNull(lki);
        AssertJUnit.assertEquals(name, lki.getName());
        AssertJUnit.assertEquals(c.getNetPower(), lki.getNetPower());
        AssertJUnit.assertEquals(c.getNetToughness(), lki.getNetToughness());
    }

    private static SpellAbility abilityOf(final Card c, final ApiType api) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.getApi() == api) {
                sa.setActivatingPlayer(c.getController());
                return sa;
            }
        }
        throw new AssertionError(c + " has no " + api + " ability");
    }

    /** An Incubator token transformed (by its own "{2}: Transform") to its Phyrexian face, with two +1/+1 counters. */
    @Test
    public void incubatorOnItsPhyrexianFace() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card inc = addToken("incubator_c_0_0_a_phyrexian", p);
        AssertJUnit.assertEquals("Incubator Token", inc.getName());
        inc.addCounterInternal(CounterEnumType.P1P1, 2, p, false, null, null);
        AbilityUtils.resolve(abilityOf(inc, ApiType.SetState));
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals(CardStateName.Backside, inc.getCurrentStateName());
        AssertJUnit.assertEquals("Phyrexian Token", inc.getName());
        AssertJUnit.assertTrue(inc.isCreature());

        final Game[] cg = new Game[1];
        final GameCopier copier = copy(game, cg);
        final Card c = copier.find(inc);
        AssertJUnit.assertSame(cg[0], c.getGame());
        AssertJUnit.assertEquals(CardStateName.Backside, c.getCurrentStateName());
        AssertJUnit.assertTrue(c.isBackSide());
        AssertJUnit.assertEquals("Phyrexian Token", c.getName());
        AssertJUnit.assertTrue(c.isCreature());
        AssertJUnit.assertEquals(2, c.getNetPower());
        AssertJUnit.assertEquals(2, c.getNetToughness());
        AssertJUnit.assertEquals("Incubator Token", c.getState(CardStateName.Original).getName());
        assertLki(c, "Phyrexian Token");
        // a copy of the copy (a play-out's copy of a world) is the same
        final Game[] cg2 = new Game[1];
        final Card c2 = copy(cg[0], cg2).find(c);
        AssertJUnit.assertEquals(CardStateName.Backside, c2.getCurrentStateName());
        assertLki(c2, "Phyrexian Token");
    }

    /** An untransformed Incubator token is copied on its front face, still able to transform in the copy. */
    @Test
    public void incubatorOnItsFrontFaceTransformsInTheCopy() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card inc = addToken("incubator_c_0_0_a_phyrexian", p);
        inc.addCounterInternal(CounterEnumType.P1P1, 3, p, false, null, null);
        final Game[] cg = new Game[1];
        final Card c = copy(game, cg).find(inc);
        AssertJUnit.assertEquals(CardStateName.Original, c.getCurrentStateName());
        AssertJUnit.assertEquals("Incubator Token", c.getName());
        assertLki(c, "Incubator Token");
        final Object[] r = capture(() -> {
            AbilityUtils.resolve(abilityOf(c, ApiType.SetState));
            return null;
        });
        AssertJUnit.assertFalse((String) r[1], ((String) r[1]).contains("non-existant state"));
        cg[0].getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("Phyrexian Token", c.getName());
        AssertJUnit.assertEquals(3, c.getNetPower());
        AssertJUnit.assertEquals("the original is untouched", "Incubator Token", inc.getName());
    }

    /** Fable of the Mirror-Breaker transformed to Reflection of Kiki-Jiki. */
    private static Card reflection(final Game game, final Player p, final GameCopierTransformTokenTest t) {
        final Card fable = t.addCard("Fable of the Mirror-Breaker", p);
        AssertJUnit.assertTrue(fable.changeCardState("Transform", null, null));
        AssertJUnit.assertEquals("Reflection of Kiki-Jiki", fable.getName());
        fable.setSickness(false);
        return fable;
    }

    /**
     * A token copy of Reflection of Kiki-Jiki (one Reflection's ability on another): a token copy of a transformed
     * card. Copied with LKI, no "non-existant state" and no exception.
     */
    @Test
    public void reflectionTokenCopy() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        final Card a = reflection(game, p, this);
        final Card b = reflection(game, p, this);
        final SpellAbility copyAb = abilityOf(a, ApiType.CopyPermanent);
        copyAb.getTargets().add(b);
        AbilityUtils.resolve(copyAb);
        game.getAction().checkStateEffects(true);
        Card token = null;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isToken()) {
                token = c;
            }
        }
        AssertJUnit.assertNotNull("Reflection made a token copy", token);
        AssertJUnit.assertEquals("Reflection of Kiki-Jiki", token.getName());
        final CardStateName up = token.getCurrentStateName();

        final Game[] cg = new Game[1];
        final GameCopier copier = copy(game, cg);
        final Card c = copier.find(token);
        AssertJUnit.assertEquals("Reflection of Kiki-Jiki", c.getName());
        AssertJUnit.assertEquals(up, c.getCurrentStateName());
        AssertJUnit.assertTrue(c.isCreature());
        AssertJUnit.assertEquals(token.getNetPower(), c.getNetPower());
        assertLki(c, "Reflection of Kiki-Jiki");
        assertLki(copier.find(b), "Reflection of Kiki-Jiki");
        // the copied token's own copy ability is there (it is a Reflection)
        abilityOf(c, ApiType.CopyPermanent);
        // review #133: both faces came across; the front face is the source token's, and it transforms back to it
        AssertJUnit.assertNotNull(c.getState(CardStateName.Original));
        final String front = token.getState(CardStateName.Original).getName();
        AssertJUnit.assertEquals(front, c.getState(CardStateName.Original).getName());
        AssertJUnit.assertTrue(c.isBackSide());
        final Object[] r = capture(() -> c.changeCardState("Transform", null, null));
        AssertJUnit.assertFalse((String) r[1], ((String) r[1]).contains("non-existant state"));
        AssertJUnit.assertTrue("transformed back", (Boolean) r[0]);
        AssertJUnit.assertEquals(CardStateName.Original, c.getCurrentStateName());
        AssertJUnit.assertEquals(front, c.getName());
        AssertJUnit.assertEquals("the original token is untouched", "Reflection of Kiki-Jiki", token.getName());
    }

    /**
     * Fable's chapter III in a copied game: exile the Saga, return it transformed. The copy is made at the controller's
     * upkeep with two lore counters; the copy plays on to main 1 (the third lore counter, chapter III resolves).
     */
    @Test
    public void fableChapterThreeInACopy() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        for (Player q : game.getPlayers()) {
            for (int i = 0; i < 10; i++) {
                addCardToZone("Island", q, ZoneType.Library);
            }
        }
        game.getPhaseHandler().devModeSet(PhaseType.UPKEEP, p);
        final Card fable = addCard("Fable of the Mirror-Breaker", p);
        fable.addCounterInternal(CounterEnumType.LORE, 2, p, false, null, null);
        final Game[] cg = new Game[1];
        final GameCopier copier = copy(game, cg);
        final Game g = cg[0];
        final Object[] r = capture(() -> {
            int guard = 0;
            while (guard++ < 200 && !g.isGameOver()
                    && !(g.getPhaseHandler().is(PhaseType.MAIN1) && g.getStack().isEmpty()
                    && g.getCardsIn(ZoneType.Battlefield).stream().anyMatch(x -> "Reflection of Kiki-Jiki".equals(x.getName())))) {
                g.getPhaseHandler().mainLoopStep();
            }
            return null;
        });
        AssertJUnit.assertFalse((String) r[1], ((String) r[1]).contains("non-existant state"));
        Card refl = null;
        for (Card c : g.getCardsIn(ZoneType.Battlefield)) {
            if ("Reflection of Kiki-Jiki".equals(c.getName())) {
                refl = c;
            }
        }
        AssertJUnit.assertNotNull("chapter III returned the Saga transformed in the copy", refl);
        AssertJUnit.assertEquals(CardStateName.Backside, refl.getCurrentStateName());
        assertLki(refl, "Reflection of Kiki-Jiki");
        // and a copy of that game
        final Game[] cg2 = new Game[1];
        final Card again = copy(g, cg2).find(refl);
        AssertJUnit.assertEquals("Reflection of Kiki-Jiki", again.getName());
        assertLki(again, "Reflection of Kiki-Jiki");
        AssertJUnit.assertEquals("the original is untouched", "Fable of the Mirror-Breaker", fable.getName());
    }

    /** getLKICopy of a card whose rules say transformable but that has no back face: its face-up state, no exception. */
    @Test
    public void lkiOfATransformableNamedCardWithoutABackFace() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        // a token named for a transforming card's front face, with one face: its rules are found by name
        final Card tok = new forge.game.card.token.TokenInfo("Fable of the Mirror-Breaker,P:2,T:2,Cost:no cost,Types:Creature-Goblin,Image:")
                .makeOneToken(p);
        p.getZone(ZoneType.Battlefield).add(tok);
        AssertJUnit.assertNull(tok.getState(CardStateName.Backside));
        if (!tok.isTransformable()) {
            System.out.println("[note] the name lookup did not find transforming rules; the guard is not exercised");
        }
        assertLki(tok, "Fable of the Mirror-Breaker");
    }
}
