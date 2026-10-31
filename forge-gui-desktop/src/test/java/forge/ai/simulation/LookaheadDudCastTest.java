package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AiFixes;
import forge.ai.ComputerUtil;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The dud-cast guard (Config.dudCast, lane live-ai-quality-1030). Owner 2026-10-30: the live Hard AI sometimes casts spells
 * that do nothing (a reanimate spell with no valid target, Exhume with an empty graveyard of its own). Measured in live play
 * (100 Hard-AI games): Currency Converter's exile ability with nothing exiled, Figure of Destiny re-levelled to the 2/2 it
 * already is, Shallow Grave over a graveyard with no creature. OFF (the default) changes nothing; ON vetoes a dud and asks
 * Forge AI again; SHADOW counts.
 */
public class LookaheadDudCastTest extends SimulationTest {

    private static LookaheadSearch.Config cfg(AiFixes.Mode mode) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 4;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 1030_000_001L;
        c.dudCast = mode;
        return c;
    }

    /** Player 1 (the seat that acts) has six Swamps and two Mountains and Plains; libraries of 20. */
    private Game board() {
        Game game = initAndCreateGame();
        Player me = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (Player p : new Player[] {me, opp}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Swamp" : "Grizzly Bears", p, ZoneType.Library);
            }
        }
        for (int i = 0; i < 6; i++) {
            addCard("Swamp", me);
        }
        addCard("Mountain", me);
        addCard("Plains", me);
        addCard("Mountain", me);
        addCard("Plains", me);
        addCard("Island", me);
        addCard("Island", me);
        addCard("Island", opp);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static SpellAbility spellOf(Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isSpell()) {
                sa.setActivatingPlayer(c.getController() != null ? c.getController() : c.getOwner());
                return sa;
            }
        }
        return null;
    }

    private static SpellAbility abilityOf(Card c, String descPrefix) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isActivatedAbility() && sa.toString().contains(descPrefix)) {
                sa.setActivatingPlayer(c.getController());
                return sa;
            }
        }
        throw new AssertionError("no ability '" + descPrefix + "' on " + c + ": " + c.getSpellAbilities());
    }

    private DudCast.Verdict check(Game g, SpellAbility sa, LookaheadSearch.Config c) {
        LookaheadSearch s = new LookaheadSearch(c);
        try {
            String before = LookaheadSearch.fingerprint(g);
            DudCast.lastWhy = null;
            DudCast.Verdict v = DudCast.check(s, g, g.getPlayers().get(1), sa, 0);
            System.err.println("[dud-test] " + sa.getHostCard().getName() + " -> " + v + (DudCast.lastWhy == null ? "" : " why " + DudCast.lastWhy));
            AssertJUnit.assertEquals("the check never touches the live game", before, LookaheadSearch.fingerprint(g));
            return v;
        } finally {
            s.shutdown();
        }
    }

    private DudCast.Verdict check(Game g, SpellAbility sa) {
        return check(g, sa, cfg(AiFixes.Mode.ON));
    }

    @Test
    public void offByDefaultAndSilent() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        AssertJUnit.assertEquals(AiFixes.Mode.OFF, c.dudCast);
        AssertJUnit.assertFalse(c.toJson().has("dudCast"));
        LookaheadSearch s = new LookaheadSearch(c);
        try {
            AssertJUnit.assertFalse(s.getStats().toJson().has("dudCast"));
        } finally {
            s.shutdown();
        }
        LookaheadSearch on = new LookaheadSearch(cfg(AiFixes.Mode.ON));
        try {
            AssertJUnit.assertEquals("on", on.getConfig().toJson().get("dudCast").getAsString());
            AssertJUnit.assertEquals("on", on.getStats().toJson().getAsJsonObject("dudCast").get("mode").getAsString());
        } finally {
            on.shutdown();
        }
    }

    @Test
    public void exhume() {
        Game g = board();
        Player me = g.getPlayers().get(1);
        Card ex = addCardToZone("Exhume", me, ZoneType.Hand);
        AssertJUnit.assertEquals("both graveyards empty", DudCast.Verdict.DUD, check(g, spellOf(ex)));
        addCardToZone("Grizzly Bears", me, ZoneType.Graveyard);
        AssertJUnit.assertEquals("a creature in our graveyard", DudCast.Verdict.EFFECT, check(g, spellOf(ex)));
    }

    @Test
    public void shallowGrave() {
        Game g = board();
        Player me = g.getPlayers().get(1);
        Card sg = addCardToZone("Shallow Grave", me, ZoneType.Hand);
        addCardToZone("Forest", me, ZoneType.Graveyard);
        AssertJUnit.assertEquals("no creature card in our graveyard", DudCast.Verdict.DUD, check(g, spellOf(sg)));
        addCardToZone("Grizzly Bears", me, ZoneType.Graveyard);
        AssertJUnit.assertEquals("a creature card in our graveyard", DudCast.Verdict.EFFECT, check(g, spellOf(sg)));
    }

    @Test
    public void currencyConverter() {
        Game g = board();
        Player me = g.getPlayers().get(1);
        Card cc = addCard("Currency Converter", me);
        addCardToZone("Grizzly Bears", me, ZoneType.Hand);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("nothing exiled with it", DudCast.Verdict.DUD, check(g, abilityOf(cc, "Put a card exiled")));
        AssertJUnit.assertEquals("its loot", DudCast.Verdict.EFFECT, check(g, abilityOf(cc, "Draw a card")));
    }

    @Test
    public void figureOfDestiny() {
        Game g = board();
        Player me = g.getPlayers().get(1);
        Card fig = addCard("Figure of Destiny", me);
        fig.setSickness(false);
        g.getAction().checkStateEffects(true);
        SpellAbility lvl1 = abilityOf(fig, "Figure of Destiny becomes a Kithkin Spirit");
        AssertJUnit.assertEquals("a 1/1 Kithkin becomes a 2/2 Spirit", DudCast.Verdict.EFFECT, check(g, lvl1));
        // level it up in the live test game, then the same ability again does nothing
        AssertJUnit.assertTrue(ComputerUtil.handlePlayingSpellAbility(me, lvl1, x -> { }));
        g.getStack().resolveStack();
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals(2, fig.getNetPower());
        SpellAbility again = abilityOf(fig, "Figure of Destiny becomes a Kithkin Spirit");
        AssertJUnit.assertEquals("already a 2/2 Kithkin Spirit", DudCast.Verdict.DUD, check(g, again));
        AssertJUnit.assertEquals("the 4/4 level from a Spirit", DudCast.Verdict.EFFECT,
                check(g, abilityOf(fig, "If Figure of Destiny is a Spirit")));
    }

    @Test
    public void effectsAndExemptions() {
        Game g = board();
        Player me = g.getPlayers().get(1);
        Player opp = g.getPlayers().get(0);
        Card bolt = addCardToZone("Lightning Bolt", me, ZoneType.Hand);
        SpellAbility b = spellOf(bolt);
        b.getTargets().add(opp);
        AssertJUnit.assertEquals(DudCast.Verdict.EFFECT, check(g, b));
        AssertJUnit.assertEquals("a permanent spell", DudCast.Verdict.SKIPPED,
                check(g, spellOf(addCardToZone("Grizzly Bears", me, ZoneType.Hand))));
        AssertJUnit.assertEquals("mana", DudCast.Verdict.SKIPPED, check(g, spellOf(addCardToZone("Dark Ritual", me, ZoneType.Hand))));
        AssertJUnit.assertEquals("scry", DudCast.Verdict.SKIPPED, check(g, spellOf(addCardToZone("Preordain", me, ZoneType.Hand))));
        AssertJUnit.assertEquals("extra turn", DudCast.Verdict.SKIPPED, check(g, spellOf(addCardToZone("Time Walk", me, ZoneType.Hand))));
        AssertJUnit.assertEquals("draw", DudCast.Verdict.EFFECT, check(g, spellOf(addCardToZone("Brainstorm", me, ZoneType.Hand))));
    }

    /** Hidden information: the check discards from the believed hand (all Islands here), never the real one. */
    @Test
    public void hiddenHandFromTheBeliefNeverTheRealCards() {
        Game g = board();
        Player me = g.getPlayers().get(1);
        Player opp = g.getPlayers().get(0);
        addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        Card duress = addCardToZone("Duress", me, ZoneType.Hand);
        SpellAbility d = spellOf(duress);
        d.getTargets().add(opp);
        LookaheadSearch.Config honest = cfg(AiFixes.Mode.ON);
        honest.belief = "uniform";
        honest.beliefPool = Arrays.asList("Island", "Grizzly Bears");
        honest.beliefSingleton = false;
        honest.beliefBasics = 2;
        AssertJUnit.assertEquals("a believed hand of Islands and creatures: nothing to take", DudCast.Verdict.DUD, check(g, d, honest));
        LookaheadSearch.Config bolts = cfg(AiFixes.Mode.ON);
        bolts.belief = "uniform";
        bolts.beliefPool = Arrays.asList("Lightning Bolt", "Shock");
        bolts.beliefSingleton = false;
        bolts.beliefBasics = 0;
        AssertJUnit.assertEquals("a believed hand of instants", DudCast.Verdict.EFFECT, check(g, d, bolts));
    }

    // ------------------------------------------------------------------ the guard in the live seat

    /** A game whose seat 0 is the look-ahead seat (bound to {@code c}), active in its first main phase. */
    private Game seatGame(LookaheadSearch.Config c) {
        initAndCreateGame();
        List<RegisteredPlayer> players = new ArrayList<>();
        Deck d = new Deck();
        LobbyPlayerLookahead lp = new LobbyPlayerLookahead("k8");
        players.add(new RegisteredPlayer(d).setPlayer(lp));
        players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi("p1", null)));
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "Test");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        game.getPhaseHandler().onStackResolved();
        lp.bind(game, new LookaheadSearch(c));
        return game;
    }

    /** The seat's controller at a position with Currency Converter (nothing exiled), two Forests and Grizzly Bears in hand. */
    private PlayerControllerLookahead converterSeat(AiFixes.Mode mode) {
        Game g = seatGame(cfg(mode));
        Player me = g.getPlayers().get(0);
        Player opp = g.getPlayers().get(1);
        for (Player p : new Player[] {me, opp}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Swamp" : "Grizzly Bears", p, ZoneType.Library);
            }
        }
        addCard("Currency Converter", me);
        addCard("Forest", me);
        addCard("Forest", me);
        addCardToZone("Grizzly Bears", me, ZoneType.Hand);
        addCard("Island", opp);
        g.getAction().checkStateEffects(true);
        return (PlayerControllerLookahead) me.getController();
    }

    private static LookaheadSearch searchOf(PlayerControllerLookahead ctrl) {
        return ((LobbyPlayerLookahead) ctrl.getLookaheadLobby()).getSearch();
    }

    private List<SpellAbility> converterExile(PlayerControllerLookahead ctrl) {
        Card cc = findCardWithName(ctrl.getGame(), "Currency Converter");
        List<SpellAbility> l = new ArrayList<>();
        l.add(abilityOf(cc, "Put a card exiled"));
        return l;
    }

    private static boolean isExile(List<SpellAbility> a) {
        return a != null && a.size() == 1 && a.get(0).getHostCard().getName().equals("Currency Converter")
                && a.get(0).getDescription().contains("Put a card exiled");
    }

    private static Object vetoOf(PlayerControllerLookahead ctrl) {
        try {
            java.lang.reflect.Field f = forge.ai.AiController.class.getDeclaredField("policyVeto");
            f.setAccessible(true);
            return f.get(ctrl.getAi());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static String label(List<SpellAbility> a) {
        return a == null || a.isEmpty() ? "pass" : a.get(0).getHostCard().getName() + " :: " + a.get(0).getDescription();
    }

    /** The live departure of session 7e9e3f59 (turn 1, MAIN2): Currency Converter's exile ability with nothing exiled. */
    @Test
    public void onVetoesTheDudAndAsksForgeAgain() {
        PlayerControllerLookahead ctrl = converterSeat(AiFixes.Mode.ON);
        LookaheadSearch s = searchOf(ctrl);
        List<SpellAbility> a = DudCast.guard(s, ctrl, converterExile(ctrl));
        System.err.println("[dud-test] on: " + label(a) + " " + s.getStats().toJson().get("dudCast"));
        AssertJUnit.assertFalse("vetoed: " + label(a), isExile(a));
        AssertJUnit.assertEquals(1, s.getStats().dudFound);
        AssertJUnit.assertEquals(1, s.getStats().dudVetoed);
        AssertJUnit.assertEquals(1L, (long) s.getStats().dudCards.get("Currency Converter"));
        // the veto is lifted after the decision
        AssertJUnit.assertNull(vetoOf(ctrl));
        s.shutdown();
    }

    @Test
    public void shadowCountsAndPlaysTheAnswer() {
        PlayerControllerLookahead ctrl = converterSeat(AiFixes.Mode.SHADOW);
        LookaheadSearch s = searchOf(ctrl);
        List<SpellAbility> a = DudCast.guard(s, ctrl, converterExile(ctrl));
        AssertJUnit.assertTrue(label(a), isExile(a));
        AssertJUnit.assertEquals(1, s.getStats().dudFound);
        AssertJUnit.assertEquals(0, s.getStats().dudVetoed);
        s.shutdown();
    }

    /** OFF: the seat never runs the guard (no stats block, no check), whatever it answers. */
    @Test
    public void offSeatNeverChecks() {
        PlayerControllerLookahead ctrl = converterSeat(AiFixes.Mode.OFF);
        LookaheadSearch s = searchOf(ctrl);
        List<SpellAbility> a = ctrl.chooseSpellAbilityToPlay();
        System.err.println("[dud-test] off seat: " + label(a));
        AssertJUnit.assertFalse(s.getStats().toJson().has("dudCast"));
        AssertJUnit.assertEquals(0, s.getStats().dudChecks);
        s.shutdown();
        PlayerControllerLookahead on = converterSeat(AiFixes.Mode.ON);
        List<SpellAbility> b = on.chooseSpellAbilityToPlay();
        System.err.println("[dud-test] on seat: " + label(b) + " " + searchOf(on).getStats().toJson().get("dudCast"));
        AssertJUnit.assertTrue(searchOf(on).getStats().toJson().has("dudCast"));
        searchOf(on).shutdown();
    }
}
