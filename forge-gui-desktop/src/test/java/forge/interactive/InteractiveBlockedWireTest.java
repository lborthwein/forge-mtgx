package forge.interactive;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.bench.StateEncoder;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

/**
 * combat-damage-view-1111: the wire marks an attacker {@code "blocked": true} once it has no blockers left but is still
 * blocked (CR 509.1h). Every other attacker entry stays byte-identical.
 */
public class InteractiveBlockedWireTest extends forge.ai.AITest {

    private static JsonObject attackerEntry(JsonObject view, Card c) {
        for (JsonElement e : view.getAsJsonObject("combat").getAsJsonArray("attackers")) {
            if (e.getAsJsonObject().get("fid").getAsInt() == c.getId()) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no wire entry for " + c);
    }

    @Test
    public void onlyTheAttackerWhoseBlockersLeftIsMarked() {
        final Game game = initAndCreateGame();
        final Player p0 = game.getPlayers().get(0);
        final Player p1 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, p1);
        final Card lost = addCard("Grizzly Bears", p1);      // blocked, then its blocker leaves combat
        final Card free = addCard("Grizzly Bears", p1);      // never blocked
        final Card held = addCard("Grizzly Bears", p1);      // blocked, blocker still there
        final Card gone = addCard("Llanowar Elves", p0);
        final Card stays = addCard("Grizzly Bears", p0);
        final Combat c = new Combat(p1);
        c.addAttacker(lost, p0);
        c.addAttacker(free, p0);
        c.addAttacker(held, p0);
        c.addBlocker(lost, gone);
        c.setBlocked(lost, true);
        c.addBlocker(held, stays);
        c.setBlocked(held, true);
        game.getPhaseHandler().setCombat(c);

        final JsonObject before = StateEncoder.encode(game, p0).deepCopy();
        final JsonObject untouched = before.deepCopy();
        InteractiveState.addBlockedWithoutBlockers(before, game);
        Assert.assertEquals(before, untouched, "every attacker either unblocked or still has a blocker: no change");

        c.removeFromCombat(gone);
        Assert.assertTrue(c.isBlocked(lost), "CR 509.1h: still blocked");
        final JsonObject view = StateEncoder.encode(game, p0).deepCopy();
        InteractiveState.addBlockedWithoutBlockers(view, game);
        Assert.assertTrue(attackerEntry(view, lost).get("blocked").getAsBoolean());
        Assert.assertEquals(attackerEntry(view, lost).getAsJsonArray("blockedBy").size(), 0);
        Assert.assertFalse(attackerEntry(view, free).has("blocked"));
        Assert.assertFalse(attackerEntry(view, held).has("blocked"), "blockedBy already says it");

        final JsonObject noCombat = StateEncoder.encode(game, p0).deepCopy();
        game.getPhaseHandler().setCombat(null);
        InteractiveState.addBlockedWithoutBlockers(noCombat, game);
        Assert.assertFalse(attackerEntry(noCombat, lost).has("blocked"), "no live combat: nothing is added");
    }
}
