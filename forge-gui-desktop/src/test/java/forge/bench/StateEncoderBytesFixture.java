package forge.bench;

import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.game.spellability.LandAbility;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

/**
 * The bench encoder's bytes on fixed states (lane bridgemeta-1018). Every scenario of the interactive public-card-facts
 * tests (a stolen permanent and spell, a token, a Clone, a spell copy before and after it resolves, face-down objects,
 * a land played from another player's exile, hidden hands and libraries) is encoded with {@link StateEncoder#encode}
 * for both seats, one canonical line per (scenario, seat). Uses only the bench API, so the same compiled class runs
 * against an older jar: with {@code -Dbridgemeta.fixtureOut=<file>} it writes the lines, and two jars are the same
 * bench encoder on these states exactly when their files are byte-identical.
 */
public class StateEncoderBytesFixture extends AITest {

    private Game game;
    private Player seat0;
    private Player seat1;
    private final List<String> lines = new ArrayList<>();

    private void newGame() {
        game = initAndCreateGame();
        seat0 = game.getRegisteredPlayers().get(0);
        seat1 = game.getRegisteredPlayers().get(1);
    }

    private void snapshot(final String scenario) {
        game.getAction().checkStateEffects(true);
        for (Player viewer : List.of(seat0, seat1)) {
            final JsonObject encoded = StateEncoder.encode(game, viewer);
            lines.add(scenario + "\tseat" + StateEncoder.playerIndex(game, viewer) + "\t" + encoded);
        }
    }

    private SpellAbility cast(final Card card, final Player caster) {
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);
        sa.setHostCard(game.getAction().moveToStack(card, sa));
        game.getStack().add(sa);
        return sa;
    }

    @Test(timeOut = 900000)
    public void encodeEveryScenario() throws IOException {
        lines.clear();

        newGame();
        final Card bear = addCard("Grizzly Bears", seat0);
        bear.addTempController(seat1, game.getNextTimestamp());
        game.getAction().controllerChangeZoneCorrection(bear);
        snapshot("stolen-permanent");

        newGame();
        cast(addCardToZone("Grizzly Bears", seat0, ZoneType.Exile), seat1);
        snapshot("stolen-spell");

        newGame();
        addToken("c_a_treasure_sac", seat0);
        final Card original = addCard("Grizzly Bears", seat0);
        final Card clone = addCard("Clone", seat1);
        clone.addCloneState(CardFactory.getCloneStates(original, clone, clone.getFirstSpellAbility()),
                game.getNextTimestamp());
        clone.updateStateForView();
        snapshot("token-and-clone");

        newGame();
        final SpellAbility spell = cast(addCardToZone("Grizzly Bears", seat0, ZoneType.Hand), seat0);
        game.getStack().add(CardFactory.copySpellAbilityAndPossiblyHost(spell, spell, seat1));
        snapshot("spell-copy-on-stack");
        game.getStack().resolveStack();
        snapshot("spell-copy-resolved");

        newGame();
        addCardToZone("Lightning Bolt", seat0, ZoneType.Exile).turnFaceDown(true);
        addCard("Ainok Tracker", seat0).turnFaceDown(true);
        snapshot("face-down");

        newGame();
        final Card forest = addCardToZone("Forest", seat0, ZoneType.Exile);
        seat1.playLand(forest, new LandAbility(forest, forest.getCurrentState()));
        snapshot("land-from-exile");

        newGame();
        fillLibrary(seat1, 3);
        final Card top = createCard("Time Walk", seat1);
        top.setGameTimestamp(game.getNextTimestamp());
        seat1.getZone(ZoneType.Library).add(top, 0);
        final Card inHand = addCardToZone("Ancestral Recall", seat1, ZoneType.Hand);
        seat1.drawCard();
        game.getAction().moveToLibrary(inHand, null);
        snapshot("hidden-zones");

        assertEquals(lines.size(), 16);
        for (String line : lines) {
            for (String field : List.of("\"isToken\"", "\"isCopy\"", "\"ownerSeat\"", "\"publicCardFacts\"")) {
                assertFalse(line.contains(field), field + " in a bench encoding: " + line);
            }
        }
        final String out = System.getProperty("bridgemeta.fixtureOut");
        if (out != null && !out.isBlank()) {
            Files.write(Path.of(out), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }
}
