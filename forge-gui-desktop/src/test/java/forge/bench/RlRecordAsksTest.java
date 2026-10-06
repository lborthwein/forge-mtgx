package forge.bench;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiPlayDecision;
import forge.ai.PlayerControllerAi;
import forge.bench.rl.RlCandidates;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.cost.Cost;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Record-mode asks that W's corpus (w-corpus-1006, 20,000 r1-bank games, jar c972d75f) could not map (lane
 * rl-r0-b4b-1006). Each shape is rebuilt by hand from the corpus attribution:
 * <ul>
 * <li>TARGETS, Forge-illegal: Forge's AI activates a targeted +loyalty ability "for the cost" in main 2 with fewer
 * targets than the ability needs (Garruk Wildspeaker 65 rows in 51 games, Koth of the Hammer 999 rows in one game).
 * Forge's stack refuses it ("Couldn't add to stack, failed to target"): no label exists, and it is not a mapping
 * failure.</li>
 * <li>TARGETS, off targets: Tear Asunder with a stray target on the ability whose target count is 0 on that cast (4
 * rows: twice the kicker-only sub-ability of an unkicked cast, twice the root of a kicked cast from exile). The
 * seat's ask for that ability is trivial (min = max = 0), so the row is the empty answer.</li>
 * <li>NUMBER: the keyword-cost repeat ceiling (Squad, Multikicker) was a mana estimate below Forge's own payability
 * count (5 of 1,031 asks).</li>
 * </ul>
 */
public class RlRecordAsksTest extends AITest {

    private static Card withLoyalty(final Card c, final int n, final Player p) {
        c.addCounterInternal(CounterEnumType.LOYALTY, n, p, false, null, null);
        return c;
    }

    private static SpellAbility plusOne(final Card pw, final Player p) {
        for (SpellAbility sa : pw.getSpellAbilities()) {
            if (sa.isPwAbility() && sa.usesTargeting()) {
                sa.setActivatingPlayer(p);
                return sa;
            }
        }
        throw new AssertionError("no targeted loyalty ability on " + pw);
    }

    private static AiPlayDecision aiDecides(final Player p, final SpellAbility sa) {
        return ((PlayerControllerAi) p.getController()).getAi().canPlaySa(sa);
    }

    private void assertForgeIllegal(final Game game, final SpellAbility sa, final int forgeTargets) {
        Assert.assertEquals(sa.getTargets().size(), forgeTargets, "Forge's AI targets (the corpus shape)");
        Assert.assertFalse(RlCandidates.stackAccepts(game, sa), "Forge's stack refuses the activation");
        final List<Object[]> rows = RlCandidates.targetsFromChosen(game, sa);
        Assert.assertEquals(rows.size(), 1);
        final RlCandidates.Menu m = (RlCandidates.Menu) rows.get(0)[0];
        Assert.assertNotNull(m.forgeIllegal, "classified Forge-illegal");
        Assert.assertNull(m.unposable, "not a mapping failure: " + m.unposable);
        Assert.assertNull(rows.get(0)[1], "no teacher steps");
    }

    @Test
    public void garrukPlusOneForTheCostIsForgeIllegal() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        addCards("Forest", 3, p);
        final Card garruk = withLoyalty(addCard("Garruk Wildspeaker", p), 3, p);
        moveToMain2(game, p);
        final SpellAbility sa = plusOne(garruk, p);
        Assert.assertEquals(aiDecides(p, sa), AiPlayDecision.WillPlay, "Forge's AI activates +1 in main 2");
        assertForgeIllegal(game, sa, 0);
    }

    @Test
    public void garrukPlusOneWithOneTappedLandIsForgeIllegal() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        addCards("Forest", 2, p);
        addCard("Forest", p).setTapped(true);
        final Card garruk = withLoyalty(addCard("Garruk Wildspeaker", p), 3, p);
        moveToMain2(game, p);
        final SpellAbility sa = plusOne(garruk, p);
        Assert.assertEquals(aiDecides(p, sa), AiPlayDecision.WillPlay);
        assertForgeIllegal(game, sa, 1);
    }

    @Test
    public void kothPlusOneForTheCostIsForgeIllegal() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        addCards("Mountain", 2, p);
        final Card koth = withLoyalty(addCard("Koth of the Hammer", p), 3, p);
        moveToMain2(game, p);
        final SpellAbility sa = plusOne(koth, p);
        Assert.assertEquals(aiDecides(p, sa), AiPlayDecision.WillPlay);
        assertForgeIllegal(game, sa, 0);
    }

    @Test
    public void garrukLegalTargetsStillMap() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        addCard("Forest", p).setTapped(true);
        addCard("Forest", p).setTapped(true);
        addCards("Forest", 1, p);
        final Card garruk = withLoyalty(addCard("Garruk Wildspeaker", p), 3, p);
        moveToMain2(game, p);
        final SpellAbility sa = plusOne(garruk, p);
        Assert.assertEquals(aiDecides(p, sa), AiPlayDecision.WillPlay);
        Assert.assertEquals(sa.getTargets().size(), 2);
        Assert.assertTrue(RlCandidates.stackAccepts(game, sa));
        final List<Object[]> rows = RlCandidates.targetsFromChosen(game, sa);
        final RlCandidates.Menu m = (RlCandidates.Menu) rows.get(0)[0];
        Assert.assertNull(m.forgeIllegal);
        Assert.assertNull(m.unposable);
        Assert.assertEquals(((short[]) rows.get(0)[1]).length, 2);
    }

    private static SpellAbility tearAsunder(final Card c, final Player p) {
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        return sa;
    }

    @Test
    public void unkickedTearAsunderStrayKickerTargetIsTrivial() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final Card ring = addCard("Sol Ring", opp);
        final Card bears = addCard("Grizzly Bears", opp);
        final Card spell = addCardToZone("Tear Asunder", p, ZoneType.Hand);
        final SpellAbility root = tearAsunder(spell, p);
        final SpellAbility sub = root.getSubAbility();
        Assert.assertEquals(root.getMaxTargets(), 1, "unkicked: one artifact or enchantment");
        Assert.assertEquals(sub.getMaxTargets(), 0, "unkicked: the kicker-only target is off");
        root.getTargets().add(ring);
        Assert.assertTrue(RlCandidates.stackAccepts(game, root), "no stray target: Forge accepts");
        List<Object[]> rows = RlCandidates.targetsFromChosen(game, root);
        Assert.assertEquals(rows.size(), 2);
        RlCandidates.Menu s = (RlCandidates.Menu) rows.get(1)[0];
        Assert.assertTrue(s.trivial);
        Assert.assertFalse(s.offTargets);
        // the corpus shape: Forge's AI also left a target on the kicker-only sub-ability
        sub.getTargets().add(bears);
        Assert.assertFalse(RlCandidates.stackAccepts(game, root), "a stray target makes Forge refuse the cast");
        rows = RlCandidates.targetsFromChosen(game, root);
        final RlCandidates.Menu r = (RlCandidates.Menu) rows.get(0)[0];
        Assert.assertNull(r.unposable);
        Assert.assertEquals(((short[]) rows.get(0)[1]).length, 1, "the root's own target still maps");
        s = (RlCandidates.Menu) rows.get(1)[0];
        Assert.assertNull(s.unposable, "not a mapping failure: " + s.unposable);
        Assert.assertNull(s.forgeIllegal);
        Assert.assertTrue(s.trivial);
        Assert.assertTrue(s.offTargets);
        Assert.assertEquals(((short[]) rows.get(1)[1]).length, 0, "the seat's trivial answer: no target");
    }

    @Test
    public void kickedTearAsunderStrayRootTargetIsTrivial() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final Card ring = addCard("Sol Ring", opp);
        final Card spell = addCardToZone("Tear Asunder", p, ZoneType.Hand);
        final SpellAbility root = tearAsunder(spell, p);
        root.addOptionalCost(OptionalCost.Kicker1);
        final SpellAbility sub = root.getSubAbility();
        Assert.assertEquals(root.getMaxTargets(), 0, "kicked: the artifact-or-enchantment target is off");
        Assert.assertEquals(sub.getMaxTargets(), 1, "kicked: one nonland permanent");
        // the corpus shape (2 of the 4 Tear Asunder rows, both cast from exile): the same target on both abilities
        root.getTargets().add(ring);
        sub.getTargets().add(ring);
        Assert.assertFalse(RlCandidates.stackAccepts(game, root));
        final List<Object[]> rows = RlCandidates.targetsFromChosen(game, root);
        final RlCandidates.Menu r = (RlCandidates.Menu) rows.get(0)[0];
        Assert.assertNull(r.unposable, "not a mapping failure: " + r.unposable);
        Assert.assertTrue(r.trivial && r.offTargets);
        final RlCandidates.Menu s = (RlCandidates.Menu) rows.get(1)[0];
        Assert.assertNull(s.unposable);
        Assert.assertFalse(s.offTargets);
        Assert.assertEquals(((short[]) rows.get(1)[1]).length, 1, "the kicked target maps");
    }

    private static KeywordInterface keyword(final Card c, final Keyword k) {
        for (KeywordInterface ki : c.getKeywords(k)) {
            return ki;
        }
        throw new AssertionError("no " + k + " on " + c);
    }

    @Test
    public void squadCeilingIsForgesPayableCount() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        addCards("Island", 6, p);
        addCard("Etherium Sculptor", p); // artifact spells cost {1} less: the mana estimate does not see it
        final Card inf = addCardToZone("Sicarian Infiltrator", p, ZoneType.Hand);
        final SpellAbility sa = inf.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        final KeywordInterface ki = keyword(inf, Keyword.SQUAD);
        final Cost squad = new Cost("2", false);
        final int forge = ((PlayerControllerAi) p.getController()).chooseNumberForKeywordCost(sa, squad, ki, "squad",
                Integer.MAX_VALUE);
        final int estimate = PlayerControllerBridge.affordableRepeats(p, sa, squad, Integer.MAX_VALUE);
        final int payable = PlayerControllerBridge.payableRepeats(p, sa, squad, ki, Integer.MAX_VALUE);
        System.err.println("[RlRecordAsksTest] squad: forge " + forge + " estimate " + estimate + " payable " + payable);
        Assert.assertEquals(forge, 2, "{1}{U} after the reduction + 2 x {2} = 6 mana");
        Assert.assertTrue(estimate < forge, "the old ceiling could not name Forge's answer (" + estimate + ")");
        Assert.assertEquals(payable, forge, "the ceiling is Forge's own count");
    }

    @Test
    public void multikickerCeilingCoversForgesPreset() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        addCards("Forest", 7, p);
        final Card chalice = addCardToZone("Everflowing Chalice", p, ZoneType.Hand);
        final SpellAbility sa = chalice.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        final KeywordInterface ki = keyword(chalice, Keyword.MULTIKICKER);
        final Cost kick = new Cost("2", false);
        final int payable = PlayerControllerBridge.payableRepeats(p, sa, kick, ki, Integer.MAX_VALUE);
        Assert.assertEquals(payable, 3, "7 mana pays three kicks");
        sa.setOptionalKeywordAmount(ki, 5); // Forge's AI preset (PermanentAi) is on the menu even above canPayCost's
        Assert.assertEquals(PlayerControllerBridge.payableRepeats(p, sa, kick, ki, Integer.MAX_VALUE), 5);
        Assert.assertEquals(((PlayerControllerAi) p.getController()).chooseNumberForKeywordCost(sa, kick, ki, "mk",
                Integer.MAX_VALUE), 5, "Forge's AI answers its preset");
    }
}
