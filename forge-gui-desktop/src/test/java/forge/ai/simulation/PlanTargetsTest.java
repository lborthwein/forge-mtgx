package forge.ai.simulation;

import java.util.Arrays;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.ai.simulation.ForcedWinModel.Eff;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane plantargets-1114 (solver key planTargets, slice 0 of the target-choice design; card names are test data only).
 * A permanent's triggered damage that may target the opposing player is learned aimed at that player, so the model
 * holds the damage whatever the sandbox's controller would have aimed at; a triggered damage that may only hit a
 * creature, and a spell we aim ourselves, are learned as without the key; with the key off nothing changes.
 */
public class PlanTargetsTest extends AITest {
    /** Enters: 2 damage to any target (a creature; an artifact). */
    static final List<String> ANY = Arrays.asList("Ghitu Slinger", "Legion Extruder");
    /** Enters: 4 damage to target creature. */
    static final String CREATURE_ONLY = "Flametongue Kavu";
    static final String SPELL = "Lightning Bolt";
    /** Enters: damage to any target equal to a computed amount (its power, a count of the graveyards). */
    static final String COMPUTED = "Pyrogoyf";

    private static String shape(final ACard a) {
        return new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(a.instance(0));
    }

    private static Game game(final String hand, final String oppField) {
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default",
                ForcedWinSolverTest.frame(hand, "Mountain;Mountain;Mountain;Mountain", 20, "", oppField, 20, "human"));
    }

    private static ACard learn(final Game g, final String name, final boolean key) {
        final Player me = g.getPlayers().get(0);
        final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
        r.planFace = key;
        final Card c = ForcedWinSolverTest.inZone(me, ZoneType.Hand, name);
        Assert.assertNotNull(c, name);
        final ACard a = r.card(g, me, c);
        Assert.assertEquals(r.templates.containsKey("ptf|" + name), key, "the key's own template key");
        Assert.assertEquals(r.templates.containsKey(name), !key, "the plain template key");
        return a;
    }

    /** The damage to the opponent of the card's cast from hand (the first playable one), 0 when it has none. */
    private static int drain(final ACard a) {
        for (Abil ab : a.fromHand) {
            if (ab.unplayable) {
                continue;
            }
            int n = 0;
            for (Eff e : ab.effects) {
                if (e.kind == EffKind.DRAIN) {
                    n += e.n;
                }
            }
            return n;
        }
        return -1;
    }

    @Test
    public void theKeyIsOffByDefault() {
        Assert.assertNull(ForcedWinSolver.Config.fromProps(k -> null).planTargets);
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(k -> null).planTargets("face"));
        final ForcedWinSolver.Config face = ForcedWinSolver.Config.fromProps(k -> "planTargets".equals(k) ? "face" : null);
        Assert.assertTrue(face.planTargets("face"));
        Assert.assertFalse(face.planTargets("attackers"));
        // a list of slices; one this build does not know turns nothing on
        final ForcedWinSolver.Config both = ForcedWinSolver.Config.fromProps(k -> "planTargets".equals(k) ? "face,attackers" : null);
        Assert.assertTrue(both.planTargets("face"));
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(k -> "planTargets".equals(k) ? "true" : null).planTargets("face"));
        Assert.assertFalse(new ForcedWinModel.Reader().planFace);
        Assert.assertFalse(ForcedWinProbe.PLAN_TARGETS.get());
        final Game g = game(SPELL, "Plains");
        final Player me = g.getPlayers().get(0);
        Assert.assertFalse(new ForcedWinSolver(me, ForcedWinSolver.Config.fromProps(k -> null)).reader.planFace);
        final ForcedWinSolver on = new ForcedWinSolver(me, face);
        Assert.assertTrue(on.reader.planFace);
        Assert.assertFalse(on.oppReader.planFace, "our own cards only");
        Assert.assertFalse(new ForcedWinSolver(me, ForcedWinSolver.Config.fromProps(k -> "planTargets".equals(k) ? "attackers" : null)).reader.planFace);
    }

    @Test
    public void anEntersDamageToAnyTargetIsLearnedAtTheOpposingPlayer() {
        for (String name : ANY) {
            // the opponent holds a creature the damage would kill: the sandbox's controller has something to aim at
            final ACard rich = learn(game(name, "Plains;Grizzly Bears;Llanowar Elves"), name, true);
            final ACard bare = learn(game(name, "Plains"), name, true);
            System.err.println("PT on  " + name + " rich " + shape(rich));
            Assert.assertEquals(drain(rich), 2, name + ": the damage is the opposing player's, with a creature to aim at");
            Assert.assertEquals(drain(bare), 2, name + ": and with none");
            Assert.assertEquals(shape(rich), shape(bare), name + ": the model does not depend on the opponent's board");
            // (the card-level unmodeled flag is not this key's business: the artifact keeps it for its other ability's cost)
            // without the key: as it was (the bare position leaves only the player to aim at; the rich one is the
            // sandbox controller's choice, printed, not asserted)
            final ACard offBare = learn(game(name, "Plains"), name, false);
            final ACard offRich = learn(game(name, "Plains;Grizzly Bears;Llanowar Elves"), name, false);
            System.err.println("PT off " + name + " rich drain " + drain(offRich) + " bare drain " + drain(offBare));
            Assert.assertEquals(drain(offBare), 2, name + ": key off, nothing else to aim at");
            Assert.assertEquals(shape(bare), shape(offBare), name + ": the key's model is the one the bare position gives without it");
        }
    }

    @Test
    public void aCreatureOnlyDamageAndASpellWeAimAreUnchanged() {
        for (String name : new String[] {CREATURE_ONLY, SPELL}) {
            for (String opp : new String[] {"Plains", "Plains;Grizzly Bears;Llanowar Elves"}) {
                final ACard on = learn(game(name, opp), name, true);
                final ACard off = learn(game(name, opp), name, false);
                Assert.assertEquals(shape(on), shape(off), name + " against " + opp);
            }
        }
        Assert.assertEquals(drain(learn(game(CREATURE_ONLY, "Plains;Grizzly Bears"), CREATURE_ONLY, true)), 0,
                "a creature-only damage never becomes damage to the player");
    }

    @Test
    public void aComputedDamageAmountIsLeftToTheSandbox() {
        // the amount depends on the position (here the graveyards): the key must not save a sandbox's number as the card's
        for (String opp : new String[] {"Plains", "Plains;Grizzly Bears;Llanowar Elves"}) {
            final ACard on = learn(game(COMPUTED, opp), COMPUTED, true);
            final ACard off = learn(game(COMPUTED, opp), COMPUTED, false);
            Assert.assertEquals(shape(on), shape(off), COMPUTED + " against " + opp);
        }
    }

    @Test
    public void onlyOurOwnTriggeredDamageThatAcceptsThePlayerIsAimed() {
        final Game g = game(SPELL, "Plains");
        final Player me = g.getPlayers().get(0);
        Assert.assertFalse(ForcedWinProbe.facePlayer(null, me));
        // a spell (not a trigger) is never touched, whatever it may target
        final Card bolt = ForcedWinSolverTest.inZone(me, ZoneType.Hand, SPELL);
        Assert.assertFalse(ForcedWinProbe.facePlayer(bolt.getFirstSpellAbility(), me));
    }

    @Test
    public void theTableGeneratesTheVariantUnderItsOwnKeys() {
        final ForcedWinCanon.Table tb = ForcedWinCanon.newTable(Arrays.asList("Ghitu Slinger", "Legion Extruder", "Flametongue Kavu",
                "Lightning Bolt", "Grizzly Bears", "Llanowar Elves", "Sol Ring", "Brainstorm"));
        try {
            for (String name : ANY) {
                Assert.assertTrue(ForcedWinCanon.generate(tb, "", name), name);
                Assert.assertTrue(ForcedWinCanon.generate(tb, "ptf|", name), "ptf|" + name);
                final ACard plain = tb.entries.get(name).card, pt = tb.entries.get("ptf|" + name).card;
                System.err.println("PT table " + name + " plain drain " + drain(plain) + " pt drain " + drain(pt));
                Assert.assertEquals(drain(pt), 2, name + ": the canonical sandbox holds a creature, the variant still aims at the player");
            }
            Assert.assertTrue(ForcedWinCanon.generate(tb, "", CREATURE_ONLY));
            Assert.assertTrue(ForcedWinCanon.generate(tb, "ptf|", CREATURE_ONLY));
            Assert.assertEquals(shape(tb.entries.get("ptf|" + CREATURE_ONLY).card), shape(tb.entries.get(CREATURE_ONLY).card));
        } finally {
            ForcedWinCanon.forgetForTest();
        }
    }
}
