package forge.game;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.util.Arrays;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.zone.ZoneType;

/**
 * Lane ledger-sweep-1107, ERROR-LEDGER row 18. The frame is a2-measure-1106's crash repro (data/crash-repro.frame.txt,
 * sha256 1b1aec94; S1PS pair-001 d222): seat 0's Preordain is ExiledWith:84 and no card 84 is in the record. With
 * {@code installAsIs} the install links it to a stand-in object. Key {@code forge.standInEffect} off: the stand-in has a
 * blank name, and a last-known-information copy of the Preordain (what Spell.canPlayFromHost takes when Forge AI asks
 * whether it may play that card) throws in the paper-card lookup of the stand-in. Key on: the copy succeeds, and the copy
 * of the stand-in is an effect object with no paper card.
 */
public class StandInEffectTest extends AITest {

    static final String FRAME = String.join("\n",
            "turn=18",
            "activeplayer=p0",
            "activephase=MAIN1",
            "p0life=20",
            "p0landsplayed=0",
            "p0landsplayedlastturn=0",
            "p0numringtemptedyou=0",
            "p0speed=0",
            "p0hand=Imperial Seal|Set:PTK|Art:1;Expressive Iteration|Set:STX|Art:1;Verdant Catacombs|Set:ZEN|Art:1;Baleful Strix|Set:PC2|Art:1",
            "p0library=Splinter Twin|Set:ROE|Art:1;Mana Drain|Set:2X2|Art:1;Mountain|Set:UNH|Art:1;Ponder|Set:C18|Art:1;Pestermite|Set:MMA|Art:1;Time Warp|Set:M10|Art:1;Island|Set:UNH|Art:1;Gitaxian Probe|Set:NPH|Art:1;The One Ring|Set:LTR|Art:1;Ancient Tomb|Set:TMP|Art:1;Tishana's Tidebinder|Set:LCI|Art:1;Island|Set:UNH|Art:1",
            "p0graveyard=Lotus Petal|Set:TMP|Art:1;Ragavan, Nimble Pilferer|Set:MH2|Art:1;Island|Set:UNH|Art:1;Kiki-Jiki, Mirror Breaker|Set:CHK|Art:1;Cryptic Command|Set:LRW|Art:1;Remand|Set:MM2|Art:1;Mountain|Set:UNH|Art:1;Island|Set:UNH|Art:1;Blood Crypt|Set:RNA|Art:1",
            "p0battlefield=Island|Set:UNH|Art:1;Swamp|Set:UNH|Art:1;Smuggler's Copter|Set:KLD|Art:1;Talisman of Indulgence|Set:SLD|Art:1;Island|Set:UNH|Art:1;Jace, Vryn's Prodigy|Set:ORI|Art:1|Transformed|Counters:LOYALTY=1;Mountain|Set:UNH|Art:1;Jace, the Mind Sculptor|Set:WWK|Art:1|Counters:LOYALTY=2;Island|Set:UNH|Art:1;True-Name Nemesis|Set:TSR|Art:1|ChosenPlayer:P1;Steam Vents|Set:GRN|Art:1;Chaos Defiler|Set:40K|Art:1;Sicarian Infiltrator|Set:40K|Art:1;Sicarian Infiltrator|Set:40K|Art:1|IsToken;Sicarian Infiltrator|Set:40K|Art:1|IsToken|Tapped;Badlands|Set:LEA|Art:1",
            "p0exile=Preordain|Set:CMR|Art:1|ExiledWith:84",
            "p0command=",
            "p0sideboard=",
            "p1life=12",
            "p1landsplayed=0",
            "p1landsplayedlastturn=1",
            "p1numringtemptedyou=0",
            "p1speed=0",
            "p1hand=",
            "p1library=Doomsday|Set:PLST|Art:1;Brain Freeze|Set:SCG|Art:1;Cabal Ritual|Set:TOR|Art:1;Raffine's Tower|Set:SNC|Art:1;Gitaxian Probe|Set:NPH|Art:1;Swamp|Set:UNH|Art:1;Mox Emerald|Set:OVNT|Art:1;Thassa's Oracle|Set:THB|Art:1;Jace, the Mind Sculptor|Set:WWK|Art:1;Gush|Set:MMQ|Art:1;Troll of Khazad-dûm|Set:LTR|Art:1;Island|Set:UNH|Art:1;Echo of Eons|Set:MH1|Art:1;Chromatic Star|Set:2XM|Art:1",
            "p1graveyard=Brainstorm|Set:C18|Art:1;Consider|Set:MID|Art:1;Jace, Vryn's Prodigy|Set:ORI|Art:1;Remand|Set:MM2|Art:1;Vampiric Tutor|Set:VIS|Art:1;Dark Ritual|Set:4ED|Art:1;Daze|Set:NMS|Art:1;Mishra's Bauble|Set:CSP|Art:1;Kaito Shizuki|Set:NEO|Art:1;Island|Set:UNH|Art:1;Marsh Flats|Set:ZEN|Art:1;Lotus Petal|Set:TMP|Art:1;Bayou|Set:LEA|Art:1;Chart a Course|Set:XLN|Art:1;Liliana of the Veil|Set:ISD|Art:1;Underground Sea|Set:LEA|Art:1;Island|Set:UNH|Art:1;Emry, Lurker of the Loch|Set:ELD|Art:1",
            "p1battlefield=Botanical Sanctum|Set:KLD|Art:1;Urborg, Tomb of Yawgmoth|Set:UMA|Art:1;Bazaar of Baghdad|Set:ARN|Art:1;Tolarian Academy|Set:USG|Art:1;Swamp|Set:UNH|Art:1;Library of Alexandria|Set:ARN|Art:1;Spellseeker|Set:BBD|Art:1;Swamp|Set:UNH|Art:1",
            "p1exile=",
            "p1command=",
            "p1sideboard=");

    @AfterMethod
    public void reset() {
        EngineFixes.setStandInEffectForTest(false);
    }

    private Card preordain() {
        final Game game = initAndCreateGame();
        final GameState state = new GameState().setInstallAsIs(true).setInstallCheck("off");
        state.parse(Arrays.asList(FRAME.split("\\R")));
        state.applyGameOnThread(game);
        final Card p = game.getPlayers().get(0).getCardsIn(ZoneType.Exile).get(0);
        assertEquals(p.getName(), "Preordain");
        assertNotNull(p.getExiledWith(), "linked to a stand-in");
        assertNull(p.getExiledWith().getZone(), "the stand-in is in no zone");
        return p;
    }

    @Test
    public void keyOffTheLkiCopyThrows() {
        final Card p = preordain();
        assertEquals(p.getExiledWith().getName(), "");
        try {
            CardCopyService.getLKICopy(p);
            fail("expected the blank-name paper-card lookup to throw");
        } catch (RuntimeException e) {
            assertTrue(e instanceof ArrayIndexOutOfBoundsException || e.getCause() instanceof ArrayIndexOutOfBoundsException,
                    e.toString());
        }
    }

    @Test
    public void keyOnTheLkiCopySucceeds() {
        EngineFixes.setStandInEffectForTest(true);
        final Card p = preordain();
        assertEquals(p.getExiledWith().getName(), GameState.STAND_IN_NAME);
        assertEquals(p.getExiledWith().getGamePieceType(), forge.card.GamePieceType.EFFECT);
        final Card lki = CardCopyService.getLKICopy(p);
        assertEquals(lki.getName(), "Preordain");
        assertNotNull(lki.getExiledWith());
        assertEquals(lki.getExiledWith().getName(), GameState.STAND_IN_NAME);
        assertNull(lki.getExiledWith().getPaperCard(), "no card has the stand-in's name");
    }
}
