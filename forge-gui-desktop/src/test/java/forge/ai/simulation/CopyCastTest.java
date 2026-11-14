package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.bench.rl.RlLiveSeat;
import forge.bench.rl.RlSearch;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane sdcc-copycast-1114 ({@link LookaheadSearch.Config#copyCast}): a candidate cast that an effect permits from the
 * graveyard or from exile is found and played in the look-ahead's copies.
 * <ul>
 *   <li><b>G17</b> (the owner's game D119, second main phase of turn 7, after the "cast from your graveyard this turn"
 *       spell resolved): off, every graveyard cast fails in every copy (candidate_not_in_copy: the copy prints the
 *       permission's source differently); on, each is found, the modal spell with its three modes, and is played.</li>
 *   <li><b>A granted cast with an exile-other-cards cost</b> (a permanent gives the graveyard's cards an alternative
 *       cast): found with the key off and on (its description names no source), and playing it in the copy pays the
 *       cost from the copy's graveyard.</li>
 *   <li><b>A cast from exile</b> (the creature half of a card on an adventure): off it fails, on it is found.</li>
 *   <li><b>Identity</b>: the key absent leaves the spec JSON unchanged and no candidate carries a structure.</li>
 * </ul>
 */
public class CopyCastTest extends SimulationTest {

    static LookaheadSearch search(final boolean copyCast) {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 4;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 748_611_401L;
        c.copyCast = copyCast;
        return new LookaheadSearch(c);
    }

    /** The casts and land plays the live game offers {@code me} for its cards in {@code zone}. */
    static List<SpellAbility> offered(final Player me, final ZoneType zone) {
        final List<SpellAbility> out = new ArrayList<>();
        for (Card c : me.getCardsIn(zone)) {
            List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(new CardCollection(c), me);
            all = ComputerUtilAbility.getOriginalAndAltCostAbilities(all, me);
            for (SpellAbility sa : all) {
                if (sa.isSpell() || sa.isLandAbility()) {
                    sa.setActivatingPlayer(me);
                    if (sa.canPlay()) {
                        out.add(sa);
                    }
                }
            }
        }
        return out;
    }

    static long notInCopy(final LookaheadSearch s) {
        return s.getStats().toJson().getAsJsonObject("playoutFailures").get("candidate_not_in_copy").getAsLong();
    }

    static SpellAbility of(final List<SpellAbility> sas, final String card) {
        for (SpellAbility sa : sas) {
            if (sa.getHostCard().getName().equals(card)) {
                return sa;
            }
        }
        return null;
    }

    static Game g17AfterTheRecastSpell() {
        return OwnerMisplaysTest.g17(Arrays.asList("Dark Ritual", "Cabal Ritual", "Yawgmoth's Will"));
    }

    @Test(timeOut = 1800000)
    public void g17GraveyardCastsAreSearchableInTheCopiesWithTheirModes() {
        initAndCreateGame();
        // keys off: today's failure
        final Game g0 = g17AfterTheRecastSpell();
        final Player me0 = g0.getPlayers().get(0);
        final List<SpellAbility> gy0 = offered(me0, ZoneType.Graveyard);
        final SpellAbility modal0 = of(gy0, "Collective Brutality");
        Assert.assertNotNull(modal0, "the live game offers the modal spell from the graveyard: " + gy0);
        Assert.assertNotNull(modal0.getMayPlay(), "by an effect's permission");
        final List<SpellAbility> given0 = new ArrayList<>();
        given0.add(modal0);
        for (SpellAbility sa : gy0) {
            if (sa != modal0 && given0.size() < 4) {
                given0.add(sa);
            }
        }
        Assert.assertTrue(given0.size() >= 3, "graveyard casts offered: " + gy0);
        final String before = LookaheadSearch.fingerprint(g0);
        final LookaheadSearch off = search(false);
        try {
            final LookaheadSearch.GivenResult r = off.decideGiven(g0, me0, given0, 4);
            System.out.println("COPYCAST g17 off outcome=" + r.outcome + " failed=" + r.failed + " ev=" + Arrays.toString(r.ev)
                    + " not_in_copy=" + notInCopy(off) + " last=" + off.getStats().playoutLastCopyFailure);
            Assert.assertEquals(r.failed, given0.size(), "off: every graveyard cast fails in the copies");
            Assert.assertTrue(notInCopy(off) > 0);
            Assert.assertNull(off.cand(modal0, true).maySig, "off: no candidate carries a structure");
        } finally {
            off.shutdown();
        }
        Assert.assertEquals(LookaheadSearch.fingerprint(g0), before);

        // key on: found in the copies, searched, and the modal spell keeps its modes
        final Game g = g17AfterTheRecastSpell();
        final Player me = g.getPlayers().get(0);
        final List<SpellAbility> gy = offered(me, ZoneType.Graveyard);
        final SpellAbility modal = of(gy, "Collective Brutality");
        final List<SpellAbility> given = new ArrayList<>();
        given.add(modal);
        for (SpellAbility sa : gy) {
            if (sa != modal && given.size() < 4) {
                given.add(sa);
            }
        }
        final LookaheadSearch on = search(true);
        try {
            final LookaheadSearch.GivenResult r = on.decideGiven(g, me, given, 4);
            System.out.println("COPYCAST g17 on outcome=" + r.outcome + " failed=" + r.failed + " ev=" + Arrays.toString(r.ev)
                    + " not_in_copy=" + notInCopy(on) + " last=" + on.getStats().playoutLastCopyFailure);
            Assert.assertEquals(r.failed, 0, "on: no graveyard cast fails " + on.getStats().playoutLastCopyFailure);
            Assert.assertEquals(notInCopy(on), 0L);
            Assert.assertEquals(r.ev.length, given.size());
            for (double v : r.ev) {
                Assert.assertFalse(Double.isNaN(v) || Double.isInfinite(v), "every candidate has a value " + Arrays.toString(r.ev));
            }
            // each candidate maps to the same card's permitted cast in a copy; the modal spell with its three modes
            int played = 0;
            for (SpellAbility live : given) {
                final Game cp = on.copierOf(g).makeCopy();
                final Player cme = cp.getPlayers().get(0);
                final LookaheadSearch.Cand c = on.cand(live, false);
                Assert.assertNotNull(c.maySig, live.getHostCard() + " carries its permission's structure");
                final SpellAbility sa = LookaheadSearch.locate(cp, cme, c);
                Assert.assertNotNull(sa, "in the copy: " + c.label + " | " + LookaheadSearch.locateMiss(cp, cme, c));
                Assert.assertEquals(sa.getHostCard().getId(), live.getHostCard().getId());
                Assert.assertTrue(sa.getHostCard().isInZone(ZoneType.Graveyard));
                Assert.assertNotNull(sa.getMayPlay());
                Assert.assertEquals(sa.getMayPlay().getHostCard().getId(), live.getMayPlay().getHostCard().getId());
                Assert.assertEquals(sa.getAlternativeCost(), live.getAlternativeCost());
                if (live == modal) {
                    Assert.assertEquals(sa.getApi(), ApiType.Charm);
                    Assert.assertEquals(CharmEffect.makePossibleOptions(sa).size(), 3, "the modal spell's modes");
                }
                // and it is played there when the floating mana pays for it: the card leaves the copy's graveyard
                sa.setActivatingPlayer(cme);
                final boolean pays = forge.ai.ComputerUtilMana.canPayManaCost(sa, cme, 0, false);
                if (live == modal) {
                    Assert.assertTrue(pays, "the modal spell is affordable in the copy");
                }
                if (pays) {
                    final int id = sa.getHostCard().getId();
                    Assert.assertTrue(ComputerUtil.handlePlayingSpellAbility(cme, sa, null), "cast in the copy: " + c.label);
                    final Card after = cp.findById(id);
                    Assert.assertFalse(after.isInZone(ZoneType.Graveyard), c.label + " left the graveyard, now in " + after.getZone());
                    played++;
                }
            }
            Assert.assertTrue(played >= 1, "graveyard casts played in a copy: " + played);
        } finally {
            on.shutdown();
        }
        // the live game is untouched
        Assert.assertTrue(modal.getHostCard().isInZone(ZoneType.Graveyard));
    }

    /** A permanent gives each nonland card of the graveyard a cast whose cost exiles three other graveyard cards. */
    static final String GRANT_FRAME = "[state]\nturn=5\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=20\n"
            + "humanlandsplayed=1\nailandsplayed=0\naihand=\nailibrary=" + ForcedWinSolverTest.LIB20
            + "\naigraveyard=\naiexile=\naibattlefield=Forest;Forest\n"
            + "humanhand=Swamp\nhumanbattlefield=Island;Island;Mountain;Underworld Breach\n"
            + "humangraveyard=Brainstorm;Dark Ritual;Cabal Ritual;Duress;Night's Whisper\nhumanexile=\n"
            + "humanlibrary=Swamp;Swamp;Swamp;Duress;Night's Whisper;Grave Titan;Swamp;Swamp\n";

    @Test(timeOut = 1800000)
    public void aGrantedGraveyardCastWithAnExileOtherCardsCostIsFoundAndPaidInTheCopy() {
        initAndCreateGame();
        for (boolean key : new boolean[] {false, true}) {
            final Game g = ForcedWinSolverTest.game("Default", "Default", GRANT_FRAME);
            final Player me = g.getPlayers().get(0);
            g.getAction().checkStateEffects(true);
            LookaheadSearch.givePriority(g.getPhaseHandler(), me);
            final List<SpellAbility> gy = offered(me, ZoneType.Graveyard);
            final SpellAbility live = of(gy, "Brainstorm");
            Assert.assertNotNull(live, "the permanent grants the graveyard cast: " + gy);
            Assert.assertEquals(live.getAlternativeCost(), AlternativeCost.Escape);
            final List<SpellAbility> given = new ArrayList<>();
            given.add(null);
            given.add(live);
            final LookaheadSearch s = search(key);
            try {
                final LookaheadSearch.GivenResult r = s.decideGiven(g, me, given, 4);
                System.out.println("COPYCAST grant key=" + key + " outcome=" + r.outcome + " failed=" + r.failed + " ev="
                        + Arrays.toString(r.ev) + " not_in_copy=" + notInCopy(s) + " last=" + s.getStats().playoutLastCopyFailure);
                Assert.assertEquals(r.failed, 0, "key " + key + ": " + s.getStats().playoutLastCopyFailure);
                Assert.assertEquals(notInCopy(s), 0L);
                final Game cp = s.copierOf(g).makeCopy();
                final Player cme = cp.getPlayers().get(0);
                final LookaheadSearch.Cand c = s.cand(live, false);
                final SpellAbility sa = LookaheadSearch.locate(cp, cme, c);
                Assert.assertNotNull(sa, LookaheadSearch.locateMiss(cp, cme, c));
                Assert.assertEquals(sa.getAlternativeCost(), AlternativeCost.Escape);
                final int gyBefore = cme.getCardsIn(ZoneType.Graveyard).size(), exBefore = cme.getCardsIn(ZoneType.Exile).size();
                sa.setActivatingPlayer(cme);
                Assert.assertTrue(ComputerUtil.handlePlayingSpellAbility(cme, sa, null), "cast in the copy");
                Assert.assertEquals(cme.getCardsIn(ZoneType.Exile).size(), exBefore + 3, "three other graveyard cards exiled");
                Assert.assertEquals(cme.getCardsIn(ZoneType.Graveyard).size(), gyBefore - 4, "the spell and its cost left the graveyard");
                Assert.assertEquals(me.getCardsIn(ZoneType.Graveyard).size(), 5, "the live game is untouched");
            } finally {
                s.shutdown();
            }
        }
    }

    @Test(timeOut = 1800000)
    public void aCastFromExileIsFoundInTheCopies() {
        initAndCreateGame();
        for (boolean key : new boolean[] {false, true}) {
            final Game g = ForcedWinSolverTest.game("Default", "Default", BvwStep3PlayoutTest.borrower());
            final Player me = g.getPlayers().get(0);
            LookaheadSearch.givePriority(g.getPhaseHandler(), me);
            final List<SpellAbility> ex = offered(me, ZoneType.Exile);
            final SpellAbility live = of(ex, "Brazen Borrower");
            Assert.assertNotNull(live, "the exiled card may be cast: " + ex);
            Assert.assertNotNull(live.getMayPlay());
            final List<SpellAbility> given = new ArrayList<>();
            given.add(null);
            given.add(live);
            final LookaheadSearch s = search(key);
            try {
                final LookaheadSearch.GivenResult r = s.decideGiven(g, me, given, 4);
                System.out.println("COPYCAST exile key=" + key + " outcome=" + r.outcome + " failed=" + r.failed + " ev="
                        + Arrays.toString(r.ev) + " not_in_copy=" + notInCopy(s) + " last=" + s.getStats().playoutLastCopyFailure);
                final Game cp = s.copierOf(g).makeCopy();
                final Player cme = cp.getPlayers().get(0);
                final LookaheadSearch.Cand c = s.cand(live, false);
                final SpellAbility sa = LookaheadSearch.locate(cp, cme, c);
                if (!key) {
                    Assert.assertEquals(r.failed, 1, "off: the exile cast fails in the copies");
                    Assert.assertTrue(notInCopy(s) > 0);
                    Assert.assertNull(sa);
                } else {
                    Assert.assertEquals(r.failed, 0, s.getStats().playoutLastCopyFailure);
                    Assert.assertEquals(notInCopy(s), 0L);
                    Assert.assertNotNull(sa, LookaheadSearch.locateMiss(cp, cme, c));
                    Assert.assertTrue(sa.getHostCard().isInZone(ZoneType.Exile));
                    Assert.assertEquals(sa.getMayPlay().getHostCard().getId(), live.getMayPlay().getHostCard().getId());
                    sa.setActivatingPlayer(cme);
                    final int id = sa.getHostCard().getId();
                    Assert.assertTrue(ComputerUtil.handlePlayingSpellAbility(cme, sa, null), "cast in the copy");
                    Assert.assertFalse(cp.findById(id).isInZone(ZoneType.Exile));
                }
            } finally {
                s.shutdown();
            }
        }
    }

    /**
     * The live pilot's path (policy play-outs): the play-out seat takes the candidate from its own menu in the copy. Off,
     * the re-play of the exiled card's cast fails (candidate_not_in_copy); on, the copy holds it and the seat's menu
     * entry matches by the permission's structure, so the re-play scores. The decision's other parts do not move.
     */
    @Test(timeOut = 3600000)
    public void thePolicyPlayoutSeatFindsTheExileCastOnItsMenu() throws Exception {
        initAndCreateGame();
        final BvwStep3PlayoutTest.Looper pol = new BvwStep3PlayoutTest.Looper(BvwStep3PlayoutTest.index(), "Brazen Borrower");
        try (forge.bench.rl.FakeSearchService svc = BvwStep3PlayoutTest.service(pol)) {
            final BvwStep3PlayoutTest.Out off = BvwStep3PlayoutTest.run(svc, "", BvwStep3PlayoutTest.borrower(), "copycast-off");
            Assert.assertNotNull(off.r.s2t.tWhy, "key off: the re-play misses the candidate " + off.s2t);
            Assert.assertTrue(off.r.s2t.tWhy.contains("candidate_not_in_copy"), off.r.s2t.tWhy);
            final BvwStep3PlayoutTest.Out on = BvwStep3PlayoutTest.run(svc, "\"copyCast\":true", BvwStep3PlayoutTest.borrower(),
                    "copycast-on");
            System.out.println("COPYCAST policy seat off t_why=" + off.r.s2t.tWhy + " | on t_why=" + on.r.s2t.tWhy + " tSym=" + on.r.s2t.tSym);
            Assert.assertNull(on.r.s2t.tWhy, "key on: the re-play finds and plays the candidate " + on.s2t);
            Assert.assertFalse(Double.isNaN(on.r.s2t.tSym));
        }
    }

    @Test
    public void theKeyIsOffByDefaultAndAbsentFromTheSpec() {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        Assert.assertFalse(c.copyCast);
        Assert.assertFalse(c.toJson().has("copyCast"));
        c.copyCast = true;
        Assert.assertTrue(c.toJson().get("copyCast").getAsBoolean());
        final JsonObject base = BvwStep3PlayoutTest.block(1, "").getAsJsonObject("rlsearch");
        final RlSearch.Config off = RlSearch.Config.parse(base);
        Assert.assertFalse(off.copyCast);
        Assert.assertFalse(off.toJson().has("copyCast"), "a spec without the key is unchanged");
        final RlSearch.Config on = RlSearch.Config.parse(
                JsonParser.parseString(BvwStep3PlayoutTest.block(1, "\"copyCast\":true").toString()).getAsJsonObject().getAsJsonObject("rlsearch"));
        Assert.assertTrue(on.copyCast);
        Assert.assertTrue(on.toJson().get("copyCast").getAsBoolean());
        final String live = "server=127.0.0.1:1,cardIndex=/x,policySha=" + "0".repeat(64);
        Assert.assertFalse(RlLiveSeat.Spec.parse(live).search.copyCast);
        Assert.assertTrue(RlLiveSeat.Spec.parse(live + ",copyCast=1").search.copyCast);
    }
}
