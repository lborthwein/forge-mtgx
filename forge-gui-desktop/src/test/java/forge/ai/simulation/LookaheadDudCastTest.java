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

    /** Duress / Thoughtseize show the hand: never called a dud (review of #153, S2). */
    @Test
    public void revealDiscardIsExempt() {
        Game g = board();
        Player me = g.getPlayers().get(1);
        SpellAbility d = spellOf(addCardToZone("Duress", me, ZoneType.Hand));
        d.getTargets().add(g.getPlayers().get(0));
        AssertJUnit.assertEquals(DudCast.Verdict.SKIPPED, check(g, d));
    }

    /** Hidden information: the check's copies hold the belief's cards in the opponent's hand, never the real ones. */
    @Test
    public void copiesUseTheBeliefNeverTheRealHand() {
        Game g = board();
        Player me = g.getPlayers().get(1);
        Player opp = g.getPlayers().get(0);
        addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        SpellAbility sg = spellOf(addCardToZone("Shallow Grave", me, ZoneType.Hand));
        LookaheadSearch.Config honest = cfg(AiFixes.Mode.ON);
        honest.belief = "uniform";
        honest.beliefPool = Arrays.asList("Island", "Grizzly Bears");
        honest.beliefSingleton = false;
        honest.beliefBasics = 2;
        LookaheadSearch s = new LookaheadSearch(honest);
        try {
            for (int w = 0; w < 2; w++) {
                LookaheadSearch.Prepared p = s.prepare(g, me, new LookaheadSearch.Cand(sg, true), sg, 77L + w, true, null);
                AssertJUnit.assertFalse(p.failed);
                Player copyOpp = null;
                for (Player x : p.g.getPlayers()) {
                    if (x.getId() == opp.getId()) {
                        copyOpp = x;
                    }
                }
                AssertJUnit.assertEquals(2, copyOpp.getCardsIn(ZoneType.Hand).size());
                for (Card c : copyOpp.getCardsIn(ZoneType.Hand)) {
                    AssertJUnit.assertFalse("a real hidden card in the copy: " + c, c.getName().equals("Lightning Bolt"));
                }
            }
        } finally {
            s.shutdown();
        }
    }

    // ------------------------------------------------------------------ review of #153: real plays the detector called duds

    private Card pw(String name, Player p, int loyalty) {
        Card c = addCard(name, p);
        c.setCounters(forge.game.card.CounterEnumType.LOYALTY, loyalty);
        return c;
    }

    @Test
    public void reviewProbesAreNotDuds() {
        Game g = board();
        Player me = g.getPlayers().get(1);
        Player opp = g.getPlayers().get(0);
        // S1-a: an Aura (ApiType Attach) is a permanent spell
        Card bears = addCardToZone("Grizzly Bears", me, ZoneType.Graveyard);
        SpellAbility ad = spellOf(addCardToZone("Animate Dead", me, ZoneType.Hand));
        ad.getTargets().add(bears);
        AssertJUnit.assertEquals("Animate Dead on our Grizzly Bears", DudCast.Verdict.SKIPPED, check(g, ad));
        // S1-b: loyalty abilities whose gain is their cost
        Card lili = pw("Liliana of the Veil", me, 3);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("Liliana of the Veil +1, empty hands", DudCast.Verdict.SKIPPED,
                check(g, abilityOf(lili, "Each player discards")));
        Card emp = pw("The Wandering Emperor", me, 3);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("Wandering Emperor +1, no creature", DudCast.Verdict.SKIPPED,
                check(g, abilityOf(emp, "+1/+1 counter")));
        Card karn = pw("Karn, the Great Creator", me, 5);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("Karn +1, no target", DudCast.Verdict.SKIPPED, check(g, abilityOf(karn, "Until your next turn")));
        // S2: goad
        Card theirBears = addCard("Grizzly Bears", opp);
        g.getAction().checkStateEffects(true);
        SpellAbility dd = spellOf(addCardToZone("Disrupt Decorum", me, ZoneType.Hand));
        AssertJUnit.assertEquals("Disrupt Decorum goads " + theirBears, DudCast.Verdict.EFFECT, check(g, dd));
        // sanity: a pump on our own creature
        Card ours = addCard("Grizzly Bears", me);
        g.getAction().checkStateEffects(true);
        SpellAbility bf = spellOf(addCardToZone("Brute Force", me, ZoneType.Hand));
        bf.getTargets().add(ours);
        AssertJUnit.assertEquals("Brute Force on our Bears", DudCast.Verdict.EFFECT, check(g, bf));
    }

    /** S1-a / S2: a cast trigger (prowess) means casting it does something, even when its own effect would not. */
    @Test
    public void castTriggersCount() {
        Game g = board();
        Player me = g.getPlayers().get(1);
        SpellAbility ex = spellOf(addCardToZone("Exhume", me, ZoneType.Hand));
        AssertJUnit.assertEquals("Exhume, empty graveyards", DudCast.Verdict.DUD, check(g, ex));
        addCard("Monastery Swiftspear", me);
        g.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("Exhume, empty graveyards, Monastery Swiftspear (prowess)", DudCast.Verdict.EFFECT, check(g, ex));
    }

    /** S2: with anything on the stack, a stack-only effect is outside the key: never a dud. */
    @Test
    public void nonEmptyStackIsUnknown() {
        Game g = board();
        Player me = g.getPlayers().get(1);
        Player opp = g.getPlayers().get(0);
        Card bolt = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        addCard("Mountain", opp);
        SpellAbility b = spellOf(bolt);
        b.setActivatingPlayer(opp);
        b.getTargets().add(me);
        AssertJUnit.assertTrue(ComputerUtil.handlePlayingSpellAbility(opp, b, x -> { }));
        AssertJUnit.assertEquals(1, g.getStack().size());
        SpellAbility sg = spellOf(addCardToZone("Shallow Grave", me, ZoneType.Hand));
        AssertJUnit.assertEquals(DudCast.Verdict.UNKNOWN, check(g, sg));
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

    private static LookaheadSearch searchOf(PlayerControllerLookahead ctrl) {
        return ((LobbyPlayerLookahead) ctrl.getLookaheadLobby()).getSearch();
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

    // ------------------------------------------------------------------ seats

    private PlayerControllerLookahead seatAt(AiFixes.Mode mode, PhaseType phase, boolean seatActive, String[] bf, String[] hand,
            String[] gy) {
        Game g = seatGame(cfg(mode));
        Player me = g.getPlayers().get(0);
        Player opp = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(phase, seatActive ? me : opp);
        for (Player p : new Player[] {me, opp}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Swamp" : "Grizzly Bears", p, ZoneType.Library);
            }
        }
        for (String n : bf) {
            addCard(n, me).setSickness(false);
        }
        for (String n : hand) {
            addCardToZone(n, me, ZoneType.Hand);
        }
        for (String n : gy) {
            addCardToZone(n, me, ZoneType.Graveyard);
        }
        addCard("Island", opp);
        g.getAction().checkStateEffects(true);
        return (PlayerControllerLookahead) me.getController();
    }

    /**
     * The live Figure of Destiny dud (session 6cc1b95d): the seat's Figure is already a 2/2 Kithkin Spirit, two Mountains
     * open (not enough for the 4/4 level), Goblin Guide in hand, its own second main phase.
     */
    private PlayerControllerLookahead figureSeat(AiFixes.Mode mode) {
        PlayerControllerLookahead c = seatAt(mode, PhaseType.MAIN2, true,
                new String[] {"Figure of Destiny", "Mountain", "Mountain", "Mountain"}, new String[] {"Goblin Guide"}, new String[] {});
        Card fig = findCardWithName(c.getGame(), "Figure of Destiny");
        SpellAbility lvl1 = abilityOf(fig, "Figure of Destiny becomes a Kithkin Spirit");
        AssertJUnit.assertTrue(ComputerUtil.handlePlayingSpellAbility(c.getPlayer(), lvl1, x -> { }));
        c.getGame().getStack().resolveStack();
        c.getGame().getAction().checkStateEffects(true);
        AssertJUnit.assertEquals(2, fig.getNetPower());
        return c;
    }

    /** The level-1 ability of the seat's Figure, as a departure would hand it to the guard. */
    private List<SpellAbility> level1(PlayerControllerLookahead c) {
        List<SpellAbility> l = new ArrayList<>();
        l.add(abilityOf(findCardWithName(c.getGame(), "Figure of Destiny"), "Figure of Destiny becomes a Kithkin Spirit"));
        return l;
    }

    private static boolean isLevel1(List<SpellAbility> a) {
        return a != null && a.size() == 1 && a.get(0).getHostCard().getName().equals("Figure of Destiny")
                && a.get(0).toString().contains("becomes a Kithkin Spirit with base power and toughness 2/2");
    }

    /** OFF: the seat's answer is a checkable one (a 1/1 Figure: Forge AI levels it), and the guard never runs. */
    @Test
    public void offFacesACheckableAnswerAndNeverChecks() {
        PlayerControllerLookahead ctrl = seatAt(AiFixes.Mode.OFF, PhaseType.MAIN2, true,
                new String[] {"Figure of Destiny", "Mountain", "Mountain"}, new String[] {}, new String[] {});
        List<SpellAbility> a = ctrl.chooseSpellAbilityToPlay();
        System.err.println("[dud-test] off seat: " + label(a));
        AssertJUnit.assertTrue("checkable: " + label(a), a != null && a.size() == 1 && DudCast.checkable(a.get(0)));
        AssertJUnit.assertFalse(searchOf(ctrl).getStats().toJson().has("dudCast"));
        AssertJUnit.assertEquals(0, searchOf(ctrl).getStats().dudChecks);
        searchOf(ctrl).shutdown();
        // the same position ON: the same answer is checked (an effect: 1/1 to 2/2) and played
        PlayerControllerLookahead on = seatAt(AiFixes.Mode.ON, PhaseType.MAIN2, true,
                new String[] {"Figure of Destiny", "Mountain", "Mountain"}, new String[] {}, new String[] {});
        List<SpellAbility> b = on.chooseSpellAbilityToPlay();
        AssertJUnit.assertEquals(label(a), label(b));
        AssertJUnit.assertEquals(1, searchOf(on).getStats().dudChecks);
        AssertJUnit.assertEquals(0, searchOf(on).getStats().dudFound);
        searchOf(on).shutdown();
    }

    /** ON: a level-1 departure on the 2/2 Figure is vetoed; Forge AI asked again casts Goblin Guide; the veto is lifted. */
    @Test
    public void onVetoesTheDudAndForgeCastsGoblinGuide() {
        PlayerControllerLookahead ctrl = figureSeat(AiFixes.Mode.ON);
        LookaheadSearch s = searchOf(ctrl);
        List<SpellAbility> a = DudCast.guard(s, ctrl, level1(ctrl));
        System.err.println("[dud-test] on seat: " + label(a) + " " + s.getStats().toJson().get("dudCast"));
        AssertJUnit.assertTrue("Forge AI's answer after the veto: " + label(a),
                a != null && a.size() == 1 && a.get(0).getHostCard().getName().equals("Goblin Guide"));
        AssertJUnit.assertEquals(1, s.getStats().dudFound);
        AssertJUnit.assertEquals(1, s.getStats().dudVetoed);
        AssertJUnit.assertEquals(1L, (long) s.getStats().dudCards.get("Figure of Destiny"));
        AssertJUnit.assertNull("the veto is lifted after the decision", vetoOf(ctrl));
        s.shutdown();
    }

    @Test
    public void shadowCountsAndPlaysTheAnswer() {
        PlayerControllerLookahead ctrl = figureSeat(AiFixes.Mode.SHADOW);
        LookaheadSearch s = searchOf(ctrl);
        List<SpellAbility> a = DudCast.guard(s, ctrl, level1(ctrl));
        AssertJUnit.assertTrue(label(a), isLevel1(a));
        AssertJUnit.assertEquals(1, s.getStats().dudFound);
        AssertJUnit.assertEquals(0, s.getStats().dudVetoed);
        s.shutdown();
    }

    /**
     * Live report 2026-10-31T02-13-20 (session 67b8040a, turn 9, its draw step): Forge AI's own answer was Fatal Push on
     * Spellseeker (mana value 3) with no revolt this turn; the target is legal and the mana value is checked on
     * resolution, so it does nothing. The detector calls it a dud; with revolt it is an effect.
     */
    @Test
    public void fatalPushWithoutRevolt() {
        Game g = board();
        Player me = g.getPlayers().get(1);
        Player opp = g.getPlayers().get(0);
        Card seeker = addCard("Spellseeker", opp);
        g.getAction().checkStateEffects(true);
        Card pushCard = addCardToZone("Fatal Push", me, ZoneType.Hand);
        SpellAbility push = spellOf(pushCard);
        push.getTargets().add(seeker);
        AssertJUnit.assertEquals("mana value 3, no revolt", DudCast.Verdict.DUD, check(g, push));
        Card bears = addCard("Grizzly Bears", opp);
        g.getAction().checkStateEffects(true);
        SpellAbility push2 = spellOf(pushCard);
        push2.resetTargets();
        push2.getTargets().add(bears);
        AssertJUnit.assertEquals("mana value 2", DudCast.Verdict.EFFECT, check(g, push2));
        // revolt: a permanent we controlled left the battlefield this turn
        Card swamp = null;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Swamp") && !c.isTapped()) {
                swamp = c;
            }
        }
        g.getAction().moveToGraveyard(swamp, null);
        AssertJUnit.assertTrue("revolt", me.hasRevolt());
        g.getAction().checkStateEffects(true);
        SpellAbility push3 = spellOf(pushCard);
        push3.resetTargets();
        push3.getTargets().add(seeker);
        // the game copy does not carry revolt, so the check cannot tell: never a dud
        AssertJUnit.assertEquals("mana value 3 with revolt", DudCast.Verdict.UNKNOWN, check(g, push3));
    }

    /** The live answer handed to the seat's guard: ON vetoes Fatal Push on Spellseeker and asks Forge AI again. */
    @Test
    public void fatalPushAtTheSeat() {
        PlayerControllerLookahead c = seatAt(AiFixes.Mode.ON, PhaseType.DRAW, true, new String[] {"Swamp", "Swamp"},
                new String[] {"Fatal Push"}, new String[] {});
        Card seeker = addCard("Spellseeker", c.getGame().getPlayers().get(1));
        c.getGame().getAction().checkStateEffects(true);
        Card pushCard = c.getPlayer().getCardsIn(ZoneType.Hand).get(0);
        SpellAbility push = spellOf(pushCard);
        push.setActivatingPlayer(c.getPlayer());
        push.getTargets().add(seeker);
        List<SpellAbility> live = new ArrayList<>();
        live.add(push);
        List<SpellAbility> a = DudCast.guard(searchOf(c), c, live);
        System.err.println("[dud-test] fatal push at the seat: " + label(a) + " " + searchOf(c).getStats().toJson().get("dudCast"));
        AssertJUnit.assertEquals(1, searchOf(c).getStats().dudVetoed);
        AssertJUnit.assertEquals(1L, (long) searchOf(c).getStats().dudCards.get("Fatal Push"));
        AssertJUnit.assertFalse("not Fatal Push on Spellseeker: " + label(a), a != null && !a.isEmpty()
                && a.get(0).getHostCard() == pushCard && a.get(0).getTargets().contains(seeker));
        searchOf(c).shutdown();
    }
}
