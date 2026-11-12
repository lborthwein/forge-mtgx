package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * sdcc-leak-1111: the card scorer replays an answer on a copy of the real game ({@link ChoiceApply#applyPicks}). The
 * replay must say when it took a card the seat did not know ({@link ChoiceApply.Result#hiddenOwn},
 * {@link ChoiceApply.Result#hiddenOpp}), so the scorer can refuse the menu: a spell that draws before it asks
 * (Brainstorm) is replayed whole and draws three cards from the real top of the library; a tutor only takes the card
 * the seat picked, which it may see. Without the {@link ChoiceApply.Frames#hidden} set nothing is counted (as before).
 */
public class CardScorerHiddenReadTest extends forge.ai.AITest {

    private static String frame(final String hand, final String library) {
        return "[state]\nturn=7\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=20\n"
                + "humanlandsplayed=0\nailandsplayed=0\naihand=Forest;Forest;Grizzly Bears\nailibrary=" + ForcedWinSolverTest.LIB20
                + "\naigraveyard=\naiexile=\naibattlefield=Forest\nhumanhand=" + hand + "\nhumanbattlefield=Island;Swamp\n"
                + "humangraveyard=\nhumanexile=\nhumanlibrary=" + library + "\n";
    }

    /** The frame side of a test: the hand's names as the frame; every library and opposing hand card unknown. */
    private static ChoiceApply.Frames frames(final boolean count) {
        return new ChoiceApply.Frames() {
            @Override
            public Object onCopy(final Game copy, final Player me) {
                return null;
            }

            @Override
            public byte[] capture(final Object ctx, final Game g, final Player me) {
                final List<String> names = new ArrayList<>();
                for (Card c : me.getCardsIn(ZoneType.Hand)) {
                    names.add(c.getName());
                }
                java.util.Collections.sort(names);
                return names.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }

            @Override
            public Set<Integer> hidden(final Object ctx, final Game g, final Player me) {
                if (!count) {
                    return null;
                }
                final Set<Integer> out = new HashSet<>();
                for (Card c : me.getCardsIn(ZoneType.Library)) {
                    out.add(c.getId());
                }
                for (Player o : g.getPlayers()) {
                    if (o != me) {
                        for (Card c : o.getCardsIn(ZoneType.Hand)) {
                            out.add(c.getId());
                        }
                    }
                }
                return out;
            }
        };
    }

    /** The game with {@code spell} cast from hand and waiting on the stack (the position a resolving ask replays from). */
    public static Game cast(final String hand, final String library, final String spell) {
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame(hand, library));
        final Player me = g.getPlayers().get(0);
        final Card host = ForcedWinSolverTest.inZone(me, ZoneType.Hand, spell);
        for (int c = 0; c < 6; c++) {
            for (int k = 0; k < 3; k++) {
                me.getManaPool().addMana(new Mana(ForcedWinModel.FORGE_COLOR[c], host, null, me));
            }
        }
        SpellAbility sa = null;
        for (SpellAbility x : host.getAllPossibleAbilities(me, true)) {
            sa = x;
        }
        Assert.assertNotNull(sa, spell + " has a spell ability");
        Assert.assertTrue(ForcedWinProbe.play(g, me, sa), spell + " is cast");
        Assert.assertFalse(g.getStack().isEmpty(), spell + " waits on the stack");
        return g;
    }

    @Test
    public void aSpellThatDrawsBeforeItAsksReadsOurHiddenLibrary() {
        final Game g = cast("Brainstorm;Mountain;Plains", "Lightning Bolt;Shock;Giant Growth;" + ForcedWinSolverTest.OURLIB,
                "Brainstorm");
        final Player me = g.getPlayers().get(0);
        final List<Integer> back = Arrays.asList(ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Mountain").getId(),
                ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Plains").getId());
        final ChoiceApply.Result r = ChoiceApply.applyPicks(g, me, back, back, 5L, 4, 1, frames(true));
        Assert.assertEquals(r.hiddenOwn, 3, "the replay drew three cards the seat did not know (fail=" + r.fail + ")");
        Assert.assertEquals(r.hiddenOpp, 0);
        if (r.payload != null) {
            Assert.assertTrue(new String(r.payload, java.nio.charset.StandardCharsets.UTF_8).contains("Lightning Bolt"),
                    "and the frame shows the real top card");
        }
        Assert.assertEquals(me.getCardsIn(ZoneType.Library).get(0).getName(), "Lightning Bolt", "the real game is untouched");
        final ChoiceApply.Result off = ChoiceApply.applyPicks(g, me, back, back, 5L, 4, 1, frames(false));
        Assert.assertEquals(off.hiddenOwn, 0, "no hidden set: nothing is counted (the old behaviour)");
    }

    @Test
    public void aTutorTakingItsPickReadsNothingHidden() {
        final Game g = cast("Demonic Tutor;Mountain", ForcedWinSolverTest.OURLIB + ";Lightning Bolt;Island;Island", "Demonic Tutor");
        final Player me = g.getPlayers().get(0);
        final int bolt = ForcedWinSolverTest.inZone(me, ZoneType.Library, "Lightning Bolt").getId();
        final List<Integer> pick = java.util.Collections.singletonList(bolt);
        final ChoiceApply.Result r = ChoiceApply.applyPicks(g, me, pick, 5L, 4, 1, frames(true));
        Assert.assertNull(r.fail, "the pick is replayed");
        Assert.assertEquals(r.hiddenOwn, 0, "the picked card is the seat's own choice from a library it may look through");
        Assert.assertEquals(r.hiddenOpp, 0);
        Assert.assertTrue(new String(r.payload, java.nio.charset.StandardCharsets.UTF_8).contains("Lightning Bolt"));
    }
}
