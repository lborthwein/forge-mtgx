package forge.ai.simulation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * nestfix-1118: what the costTax read does to the REAL game when it runs there (the non-honest readPosition branch):
 * (1) does it draw from the game's random stream, (2) does an Emerge read mark a real permanent usedToPay when the
 * controller answers it (the plan line's Forge answer, or a policy seat whose service does not check dec_idx).
 * Measured on bundle9 (before the fix): random not moved, Sol Ring usedToPay = true (a real permanent made unusable).
 * Card names are test data only.
 */
public class RealGameSideEffectsTest extends AITest {
    static final String POSITION = String.join("\n", List.of(
            "[state]", "turn=5", "activeplayer=human", "activephase=MAIN1", "humanlife=20", "humanlandsplayed=0",
            "humanlandsplayedlastturn=0", "humannumringtemptedyou=0", "humanspeed=0",
            "humanhand=Crabomination;Fact or Fiction;Brainstorm;Island",
            "humanlibrary=Island;Island;Island;Island;Island;Island;Island;Island;Island;Island",
            "humangraveyard=", "humanbattlefield=Goblin Electromancer;Sol Ring;Island;Island;Mountain;Swamp",
            "humanexile=", "humancommand=", "humansideboard=", "ailife=20", "ailandsplayed=0", "ailandsplayedlastturn=1",
            "ainumringtemptedyou=0", "aispeed=0", "aihand=Plains;Plains", "ailibrary=Plains;Plains;Plains;Plains;Plains;Plains",
            "aigraveyard=", "aibattlefield=Plains;Plains", "aiexile=", "aicommand=", "aisideboard=")) + "\n";

    static ForcedWinSolver.Config cfg(final boolean on) {
        final Map<String, String> kv = new HashMap<>();
        kv.put("costTax", on ? "true" : "false");
        kv.put("hideOwnLibrary", "true");
        kv.put("engine", "false");
        kv.put("defense", "false");
        kv.put("act", "false");
        return ForcedWinSolver.Config.fromProps(kv::get);
    }

    private static byte[] state(final Random r) throws IOException {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ObjectOutputStream o = new ObjectOutputStream(b)) {
            o.writeObject(r);
        }
        return b.toByteArray();
    }

    /** Reads the position with costTax on / off on the real game; returns {random moved, Sol Ring usedToPay, tax}. */
    private Object[] read(final boolean costTax) throws IOException {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", POSITION);
        final Player me = g.getPlayers().get(0);
        final Random r = new Random(1118);
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(r);
        try {
            final byte[] before = state(r);
            final ForcedWinSolver s = new ForcedWinSolver(me, cfg(costTax));
            final ForcedWinModel.Position pos = s.readPosition(g, s.knownTop(g));
            Assert.assertNotNull(pos);
            final byte[] after = state(r);
            boolean used = false;
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                if (c.getName().equals("Sol Ring")) {
                    used = c.isUsedToPay();
                }
            }
            System.err.println("[side-effects] costTax=" + costTax + " tax=" + pos.tax + " randomMoved="
                    + !Arrays.equals(before, after) + " solRingUsedToPay=" + used);
            return new Object[] {!Arrays.equals(before, after), used, pos.tax == null ? new HashMap<String, Integer>() : pos.tax};
        } finally {
            MyRandom.setThreadRandom(prev);
        }
    }

    @Test
    public void theCostReadLeavesTheRealGameAlone() throws IOException {
        final Object[] on = read(true);
        Assert.assertFalse((Boolean) on[0], "the real game's random stream moved");
        Assert.assertFalse((Boolean) on[1], "a real permanent was marked usedToPay");
        @SuppressWarnings("unchecked")
        final Map<String, Integer> tax = (Map<String, Integer>) on[2];
        // the ReduceCost static is read on a copy, with the same answer: Fact or Fiction costs one less
        Assert.assertTrue(tax.values().contains(-1), String.valueOf(tax));
        Assert.assertEquals(tax.size(), 1, "Emerge's sacrifice choice is not a tax: " + tax);
        final Object[] off = read(false);
        Assert.assertFalse((Boolean) off[0]);
        Assert.assertFalse((Boolean) off[1]);
    }
}
