package forge.ai;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;

import forge.game.GlobalIds;
import forge.game.spellability.SpellAbility;
import forge.util.IdScope;

/**
 * Lane bvw-decision-log-1027: the AI tape inside a bench game, whose ids come from its own {@link IdScope} (1e9 up), not
 * the process-wide counters. No game is played.
 */
public class AiTapeBenchScopeTest {

    @Test
    public void aScopedGamesCountersAreReadAndRestoredForwardInGlobalIdsForm() {
        IdScope.open();
        try {
            final Object game = IdScope.capture();
            final int[] global0 = GlobalIds.capture();
            final int[] a = GlobalIds.capture(game);
            Assert.assertEquals(a[1], 1_000_000_000, "no SpellAbility id handed out yet");
            Assert.assertEquals(a[6], -1_000_000_001, "combat views count down: the next one");
            IdScope.next(IdScope.Kind.SPELL_ABILITY);
            IdScope.next(IdScope.Kind.COMBAT_VIEW);
            final int[] b = GlobalIds.capture(game);
            Assert.assertEquals(b[1], a[1] + 1);
            Assert.assertEquals(b[6], a[6] - 1);
            // forward only: restoring the earlier state moves nothing back and counts the counters already past it
            Assert.assertEquals(GlobalIds.restoreForward(game, a), 2);
            Assert.assertEquals(GlobalIds.capture(game), b);
            final int[] later = b.clone();
            later[1] += 5;
            later[6] -= 3;
            Assert.assertEquals(GlobalIds.restoreForward(game, later), 0);
            Assert.assertEquals(GlobalIds.capture(game)[1], b[1] + 5);
            Assert.assertEquals(GlobalIds.capture(game)[6], b[6] - 3);
            Assert.assertEquals(IdScope.next(IdScope.Kind.COMBAT_VIEW), b[6] - 3, "the restored next combat view id");
            // the scope's counters are the game's: the process-wide ones never moved
            Assert.assertEquals(GlobalIds.capture(), global0);
            // a decode's scope hands out ids in its own range, never a game scope's or a cube combo probe scope's
            final int d = IdScope.detached(AiTape.DECODE_BASE, () -> IdScope.next(IdScope.Kind.SPELL_ABILITY));
            Assert.assertTrue(AiTape.decodeId(d));
            Assert.assertFalse(AiTape.decodeId(b[1] + 1), "a game-scope id");
            Assert.assertFalse(AiTape.decodeId(1_100_000_001), "a cube combo probe id");
            Assert.assertEquals(IdScope.capture(), game, "the game's scope is back after the decode");
        } finally {
            IdScope.close();
        }
    }

    /** A stand-in ability with a fixed id (only getId is read by the order codec; Mockito's inline maker stubs it). */
    private static SpellAbility ab(final int id) {
        final SpellAbility sa = org.mockito.Mockito.mock(SpellAbility.class);
        org.mockito.Mockito.when(sa.getId()).thenReturn(id);
        return sa;
    }

    @Test
    public void aTriggerOrderIsReplayedByAbilityIdWhenTheSeatReorderedItsList() {
        final SpellAbility x = ab(11), y = ab(22), z = ab(33);
        // live: the seat moved z first (the forced-win solver's forced trigger) and its native order chose z, x, y
        final List<SpellAbility> reordered = List.of(z, x, y);
        final JsonElement a = AiTape.orderAnswer(reordered, List.of(z, x, y));
        // forced: the engine's own list, never reordered by a seat that does not decide
        final List<SpellAbility> engine = new ArrayList<>(List.of(x, y, z));
        Assert.assertEquals(AiTape.pickOrder(engine, a), List.of(z, x, y), "by id, not by the reordered positions");
        // the positions alone would have played x, y, z: the order the record did not play
        Assert.assertEquals(AiTapeCodec.pick(engine, a), List.of(x, y, z));
        // an ability the record named and the game does not offer is refused (a divergence at the tape)
        Assert.expectThrows(IllegalStateException.class, () -> AiTape.pickOrder(List.of(x, y, ab(44)), a));
        // a record without ids (format before this lane) is read by position, as before
        final JsonElement old = AiTapeCodec.indices(reordered, List.of(z, x, y));
        Assert.assertEquals(AiTape.pickOrder(reordered, old), List.of(z, x, y));
    }
}
