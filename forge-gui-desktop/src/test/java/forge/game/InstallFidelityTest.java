package forge.game;

import forge.ai.AITest;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * install-fidelity-1105: the four classes of install change that scenario-gen-1105's round-trip check found in real
 * frames (13/169 U starts), each as a fixture. With {@code forge.gamestate.installAsIs} on, each position installs as
 * recorded and the round-trip check ({@link GameState#installDiff}) is empty. With the key off (today's install) the
 * same records change, and the check, asked for explicitly, names the change. On this (live) line the zone-less
 * battlefield move (33a9b68cb6a) is already unconditional, so with the key off only class 4 still changes.
 * <ol>
 * <li>an "as this enters, you may pay 2 life" land: the install paid the life;</li>
 * <li>an "if this would enter, discard a land instead or put it into the graveyard" artifact: the install discarded
 * a land from the hand, or binned the artifact when the hand had none;</li>
 * <li>a "nontoken creatures that weren't cast are exiled as they enter" creature: the install exiled the creatures
 * recorded after it;</li>
 * <li>a returned-creature aura whose Enchant an effect changed as it entered (the record keeps no keyword change):
 * the end-of-install state-based check unattached it and put it into the graveyard.</li>
 * </ol>
 */
public class InstallFidelityTest extends AITest {

    static String position(final String humanHand, final String humanBattlefield, final String humanGraveyard) {
        return String.join("\n",
                "turn=8", "activeplayer=p0", "activephase=MAIN1",
                "p0life=11", "p1life=20",
                "p0hand=" + humanHand,
                "p0library=Island;Island;Island;Island",
                "p0graveyard=" + humanGraveyard, "p0exile=",
                "p0battlefield=" + humanBattlefield,
                "p1hand=", "p1library=Plains;Plains;Plains;Plains", "p1graveyard=", "p1exile=",
                "p1battlefield=Plains");
    }

    static final String SHOCK = position("Island", "Temple Garden;Swamp|Tapped", "");
    static final String MOX_WITH_LAND = position("Forest;Lightning Bolt", "Mox Diamond;Swamp", "");
    static final String MOX_NO_LAND = position("Lightning Bolt", "Mox Diamond;Swamp", "");
    static final String PRIEST = position("", "Containment Priest|Tapped;Grizzly Bears|SummonSick;Tough Cookie", "");
    static final String ANIMATE = position("", "Swamp|Tapped;Animate Dead|AttachedTo:51|RememberedCards:51;"
            + "Grizzly Bears|Id:51|SummonSick", "Lightning Bolt");

    private GameState state;

    private Game install(final String record, final boolean asIs, final String check) {
        final Game game = initAndCreateGame();
        state = new GameState().setInstallAsIs(asIs).setInstallCheck(check);
        state.parse(Arrays.asList(record.split("\\R")));
        state.applyGameOnThread(game);
        return game;
    }

    private static List<String> names(final Player p, final ZoneType z) {
        final List<String> out = new ArrayList<>();
        for (Card c : p.getCardsIn(z)) {
            out.add(c.getPaperCard().getName());
        }
        Collections.sort(out);
        return out;
    }

    private static Card named(final Player p, final String name) {
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.getPaperCard().getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private void assertNoDiff() {
        assertNotNull(state.getInstallDiff(), "the check runs by default with the key on");
        assertTrue(state.getInstallDiff().isEmpty(), "round trip: " + state.getInstallDiff());
    }

    // --- 1. pay-life entry --------------------------------------------------------------------------------------

    @Test
    public void payLifeLandKeepsTheRecordedLife() {
        final Game g = install(SHOCK, true, null);
        final Player me = g.getPlayers().get(0);
        assertEquals(me.getLife(), 11);
        assertFalse(named(me, "Temple Garden").isTapped());
        assertTrue(named(me, "Swamp").isTapped());
        assertNoDiff();
    }

    // --- 2. discard-or-graveyard entry cost ---------------------------------------------------------------------

    @Test
    public void entryCostArtifactDiscardsNothing() {
        final Game g = install(MOX_WITH_LAND, true, null);
        final Player me = g.getPlayers().get(0);
        assertNotNull(named(me, "Mox Diamond"));
        assertEquals(names(me, ZoneType.Hand), List.of("Forest", "Lightning Bolt"));
        assertEquals(names(me, ZoneType.Graveyard), List.of());
        assertNoDiff();
    }

    @Test
    public void entryCostArtifactStaysWithNoLandInHand() {
        final Game g = install(MOX_NO_LAND, true, null);
        final Player me = g.getPlayers().get(0);
        assertNotNull(named(me, "Mox Diamond"));
        assertEquals(names(me, ZoneType.Graveyard), List.of());
        assertNoDiff();
    }

    @Test
    public void keyOffEntryCostArtifactAlreadyInstallsAsRecordedOnThisLine() {
        final Game g = install(MOX_NO_LAND, false, "warn");
        assertNotNull(named(g.getPlayers().get(0), "Mox Diamond"), "the live line's zone-less move (33a9b68cb6a)");
        assertTrue(state.getInstallDiff().isEmpty(), state.getInstallDiff().toString());
    }

    // --- 3. exile-on-entry static -------------------------------------------------------------------------------

    @Test
    public void exileOnEntryCreatureExilesNothingInstalledAfterIt() {
        final Game g = install(PRIEST, true, null);
        final Player me = g.getPlayers().get(0);
        assertEquals(names(me, ZoneType.Battlefield), List.of("Containment Priest", "Grizzly Bears", "Tough Cookie"));
        assertEquals(names(me, ZoneType.Exile), List.of());
        assertTrue(named(me, "Grizzly Bears").hasSickness());
        assertNoDiff();
    }

    @Test
    public void keyOffExileOnEntryAlreadyInstallsAsRecordedOnThisLine() {
        final Game g = install(PRIEST, false, "warn");
        assertEquals(names(g.getPlayers().get(0), ZoneType.Exile), List.of(), "the live line's zone-less move");
        assertTrue(state.getInstallDiff().isEmpty(), state.getInstallDiff().toString());
    }

    // --- 4. a changed-Enchant aura ------------------------------------------------------------------------------

    @Test
    public void returnedCreatureAuraStaysAttached() {
        final Game g = install(ANIMATE, true, null);
        final Player me = g.getPlayers().get(0);
        final Card aura = named(me, "Animate Dead");
        final Card bears = named(me, "Grizzly Bears");
        assertNotNull(aura, "the aura stays on the battlefield");
        assertEquals(aura.getEntityAttachedTo(), bears);
        assertEquals(bears.getNetPower(), 1, "its static still applies (-1/-0)");
        assertEquals(names(me, ZoneType.Graveyard), List.of("Lightning Bolt"));
        assertNoDiff();
        // the next state-based check (the game's first priority) keeps it too
        g.getAction().checkStateEffects(true);
        assertEquals(aura.getEntityAttachedTo(), bears);
        assertTrue(aura.isInPlay());
    }

    @Test
    public void auraLeavingDropsTheInstalledEnchant() {
        final Game g = install(ANIMATE, true, null);
        final Player me = g.getPlayers().get(0);
        final Card moved = g.getAction().moveToHand(named(me, "Animate Dead"), null);
        assertTrue(moved.getKeywords(forge.game.keyword.Keyword.ENCHANT).toString().contains("graveyard"),
                "a new object: its printed Enchant again, " + moved.getKeywords(forge.game.keyword.Keyword.ENCHANT));
    }

    @Test
    public void keyOffReturnedCreatureAuraIsBinnedAndTheCheckSaysSo() {
        final Game g = install(ANIMATE, false, "warn");
        final Player me = g.getPlayers().get(0);
        assertNull(named(me, "Animate Dead"), "today's install: the state-based check bins it");
        assertTrue(String.join(";", state.getInstallDiff()).contains("p0graveyard"), state.getInstallDiff().toString());
    }

    // --- the check itself ---------------------------------------------------------------------------------------

    @Test
    public void keyOffRunsNoCheckUnlessAsked() {
        install(MOX_NO_LAND, false, null);
        assertNull(state.getInstallDiff());
    }

    @Test
    public void diffSeesLifeTappedCountersAndAttachments() {
        final String a = "p0life=11\np0battlefield=Swamp|Tapped;Gemstone Mine|Counters:MINING=2;"
                + "Bonesplitter|AttachedTo:7;Grizzly Bears|Id:7";
        assertEquals(GameState.installDiff(a, a), List.of());
        // the same position written with other ids, other order, the "human" key
        final String b = "humanlife=11\nhumanbattlefield=Grizzly Bears|Id:99;Bonesplitter|AttachedTo:99;"
                + "Gemstone Mine|Counters:MINING=2|Set:MIR;Swamp|Tapped";
        assertEquals(GameState.installDiff(a, b), List.of());
        assertEquals(GameState.installDiff(a, b.replace("humanlife=11", "humanlife=9")).size(), 1);
        assertEquals(GameState.installDiff(a, b.replace("Swamp|Tapped", "Swamp")).size(), 1);
        assertEquals(GameState.installDiff(a, b.replace("MINING=2", "MINING=3")).size(), 1);
        assertEquals(GameState.installDiff(a, b.replace("Bonesplitter|AttachedTo:99;", "Bonesplitter;")).size(), 1);
    }

    @Test
    public void throwModeFailsAChangedInstall() {
        boolean threw = false;
        try {
            install(ANIMATE, false, "throw");
        } catch (IllegalStateException e) {
            threw = e.getMessage().contains("changed the position");
        }
        assertTrue(threw);
    }
}
