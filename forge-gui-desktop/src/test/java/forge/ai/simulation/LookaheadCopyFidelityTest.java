package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Copy fidelity (GameCopier#setCopyFidelity, LookaheadSearch.Config.copyFidelity; lane copy-fidelity-1010). The S-t
 * smoke of r3-distill-1010 failed every play-out of Usher of the Fallen's boast (19 of 19 searches): the live Usher had
 * attacked, so boast was on the live menu, but the copy did not carry "attacked this turn", so boast was never on the
 * copy's menu. Level 1 carries the per-turn and history state; level 0 (the default) copies as before.
 */
public class LookaheadCopyFidelityTest extends SimulationTest {

    private static Card inZone(Game g, String name, ZoneType z) {
        for (Card c : g.getCardsIn(z)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private static boolean playable(Card host, String prefix, Player p) {
        for (SpellAbility sa : host.getSpellAbilities()) {
            if (sa.getDescription().startsWith(prefix) || sa.toString().contains(prefix)) {
                sa.setActivatingPlayer(p);
                return sa.canPlay();
            }
        }
        throw new AssertionError("no ability '" + prefix + "' on " + host);
    }

    private static Game copy(Game live, int level) {
        GameCopier c = new GameCopier(live, true);
        c.setCopyFidelity(level);
        return c.makeCopy();
    }

    /** p's Usher of the Fallen has attacked this turn (as CombatUtil records an attack), main 2, mana for its boast. */
    private Game usherAttacked() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p);
        addCard("Plains", p);
        addCard("Plains", p);
        Card usher = addCard("Usher of the Fallen", p);
        usher.setSickness(false);
        game.getAction().checkStateEffects(true);
        usher.getDamageHistory().setCreatureAttackedThisCombat(opp, 0);
        p.addCreaturesAttackedThisTurn(CardCopyService.getLKICopy(usher), opp);
        return game;
    }

    @Test
    public void offByDefaultAndSilent() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        AssertJUnit.assertEquals(0, c.copyFidelity);
        AssertJUnit.assertFalse(c.toJson().has("copyFidelity"));
        c.copyFidelity = 1;
        AssertJUnit.assertEquals(1, c.toJson().get("copyFidelity").getAsInt());
        try {
            LookaheadSearch.Config.copyFidelityLevel(2);
            AssertJUnit.fail("level 2 accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void specKeysParseOffByDefault() {
        final String base = "{\"server\":\"127.0.0.1:1\"";
        forge.bench.rl.RlSearch.Config off = forge.bench.rl.RlSearch.Config.parse(
                com.google.gson.JsonParser.parseString(base + "}").getAsJsonObject());
        AssertJUnit.assertEquals(0, off.copyFidelity);
        AssertJUnit.assertFalse("an S1 spec's JSON is unchanged", off.toJson().has("copyFidelity"));
        forge.bench.rl.RlSearch.Config on = forge.bench.rl.RlSearch.Config.parse(
                com.google.gson.JsonParser.parseString(base + ",\"copyFidelity\":1}").getAsJsonObject());
        AssertJUnit.assertEquals(1, on.copyFidelity);
        AssertJUnit.assertEquals(1, on.toJson().get("copyFidelity").getAsInt());
        AssertJUnit.assertEquals(2, forge.bench.rl.RlSearch.Config.parse(com.google.gson.JsonParser.parseString(
                base + ",\"copyFidelity\":2}").getAsJsonObject()).copyFidelity);
        try {
            forge.bench.rl.RlSearch.Config.parse(com.google.gson.JsonParser.parseString(base + ",\"copyFidelity\":3}")
                    .getAsJsonObject());
            AssertJUnit.fail("copyFidelity 3 accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        final String live = "server=127.0.0.1:1,cardIndex=/x,policySha=" + "0".repeat(64);
        AssertJUnit.assertEquals(0, forge.bench.rl.RlLiveSeat.Spec.parse(live).search.copyFidelity);
        AssertJUnit.assertEquals(1, forge.bench.rl.RlLiveSeat.Spec.parse(live + ",copyFidelity=1").search.copyFidelity);
    }

    @Test
    public void boastIsOnTheCopysMenuOnlyWhenCarried() {
        Game live = usherAttacked();
        Player p = live.getPlayers().get(1);
        AssertJUnit.assertTrue("live: boast after attacking", playable(findCardWithName(live, "Usher of the Fallen"), "Boast", p));

        Game off = copy(live, 0);
        AssertJUnit.assertFalse("level 0: the copy forgot the attack",
                playable(findCardWithName(off, "Usher of the Fallen"), "Boast", off.getPlayers().get(1)));

        Game on = copy(live, 1);
        Card u = findCardWithName(on, "Usher of the Fallen");
        AssertJUnit.assertEquals(1, u.getDamageHistory().getCreatureAttacksThisTurn());
        AssertJUnit.assertEquals(1, on.getPlayers().get(1).getCreaturesAttackedThisTurn().size());
        AssertJUnit.assertTrue("level 1: boast on the copy's menu", playable(u, "Boast", on.getPlayers().get(1)));
    }

    @Test
    public void onceEachTurnCountsCarry() {
        Game live = usherAttacked();
        Player p = live.getPlayers().get(1);
        Card usher = findCardWithName(live, "Usher of the Fallen");
        SpellAbility boast = null;
        for (SpellAbility sa : usher.getSpellAbilities()) {
            if (sa.isBoast()) {
                boast = sa;
            }
        }
        AssertJUnit.assertNotNull(boast);
        boast.setActivatingPlayer(p);
        usher.addAbilityActivated(boast);
        AssertJUnit.assertFalse("live: boast only once each turn", playable(usher, "Boast", p));
        Game on = copy(live, 1);
        Card u = findCardWithName(on, "Usher of the Fallen");
        AssertJUnit.assertFalse("level 1: the activation this turn carries", playable(u, "Boast", on.getPlayers().get(1)));
        for (SpellAbility sa : u.getSpellAbilities()) {
            if (sa.isBoast()) {
                AssertJUnit.assertEquals(1, sa.getActivationsThisTurn());
                AssertJUnit.assertEquals(1, sa.getActivationsThisGame());
            }
        }
    }

    @Test
    public void zoneEntryTurnMulligansAndSpellsCastCarry() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p, 5);
        addCard("Mountain", p);
        addCard("Grizzly Bears", p);
        Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        // cast Lightning Bolt at the opponent in a simulator copy, which is then "live"
        SpellAbility sa = bolt.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        sa.getTargets().add(opp);
        GameSimulator sim = createSimulator(p);
        sim.simulateSpellAbility(sa);
        Game live = sim.getSimulatedGameState();
        Player lp = live.getPlayers().get(1);
        findCardWithName(live, "Grizzly Bears").setTurnInZone(2);
        lp.getStats().notifyHasMulliganed();
        lp.getStats().notifyHasMulliganed();
        live.getPlayers().get(0).getStats().notifyHasMulliganed();
        AssertJUnit.assertEquals(1, live.getStack().getSpellsCastThisTurn().size());
        AssertJUnit.assertEquals(1, lp.getSpellsCastThisTurn());
        final int liveBearTurn = findCardWithName(live, "Grizzly Bears").getTurnInZone();
        AssertJUnit.assertEquals(2, lp.getStats().getMulliganCount());

        Game off = copy(live, 0);
        AssertJUnit.assertEquals("level 0: storm count lost", 0, off.getStack().getSpellsCastThisTurn().size());
        AssertJUnit.assertEquals(0, off.getPlayers().get(1).getStats().getMulliganCount());
        AssertJUnit.assertEquals("level 0: every card entered this turn", off.getPhaseHandler().getTurn(),
                findCardWithName(off, "Grizzly Bears").getTurnInZone());

        Game on = copy(live, 1);
        Player op = on.getPlayers().get(1);
        AssertJUnit.assertEquals(1, on.getStack().getSpellsCastThisTurn().size());
        AssertJUnit.assertEquals(1, op.getSpellsCastThisTurn());
        AssertJUnit.assertEquals(0, on.getPlayers().get(0).getSpellsCastThisTurn());
        AssertJUnit.assertSame(op, on.getStack().getSpellsCastThisTurn().get(0).getActivatingPlayer());
        AssertJUnit.assertSame(on, on.getStack().getSpellsCastThisTurn().get(0).getHostCard().getGame());
        AssertJUnit.assertEquals(2, op.getStats().getMulliganCount());
        AssertJUnit.assertEquals(1, on.getPlayers().get(0).getStats().getMulliganCount());
        AssertJUnit.assertEquals(liveBearTurn, findCardWithName(on, "Grizzly Bears").getTurnInZone());
        Card gy = inZone(on, "Lightning Bolt", ZoneType.Graveyard);
        AssertJUnit.assertNotNull(gy);
        AssertJUnit.assertEquals(inZone(live, "Lightning Bolt", ZoneType.Graveyard).getTurnInZone(), gy.getTurnInZone());
        AssertJUnit.assertEquals(lp.getLastTurnNr(), op.getLastTurnNr());
        // the copy's card put into the graveyard this turn is recorded, as in the live game
        AssertJUnit.assertEquals(live.getPlayers().get(1).getZone(ZoneType.Graveyard).getCardsAddedThisTurn(null).size(),
                op.getZone(ZoneType.Graveyard).getCardsAddedThisTurn(null).size());
        for (Card c : op.getZone(ZoneType.Graveyard).getCardsAddedThisTurn(null)) {
            AssertJUnit.assertSame(on, c.getGame());
        }
    }

    /** The description of a card's spell that a may-play effect grants (as the search's candidate names it), or null. */
    private static String mayPlayDesc(Card c, Player p) {
        for (SpellAbility sa : forge.ai.ComputerUtilAbility.getOriginalAndAltCostAbilities(
                forge.ai.ComputerUtilAbility.getSpellAbilities(new forge.game.card.CardCollection(c), p), p)) {
            if (sa.isSpell() && sa.getMayPlay() != null) {
                return sa.getDescription();
            }
        }
        return null;
    }

    /**
     * r3-distill-1010's Goblin Rabblemaster (4 of 4 searches failed, "candidate not in the copy"): the card was in exile
     * under Laelia's "you may play that card this turn" effect. A may-play spell's description names the effect's source
     * ("... by Laelia, the Blade Reforged"); the copier did not carry an effect card's source, so the copy's spell read
     * "... by Laelia, the Blade Reforged's Effect" and the search could not find the candidate in its copies.
     */
    @Test
    public void aMayPlaySpellKeepsItsDescriptionInTheCopy() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        for (int i = 0; i < 3; i++) {
            addCard("Mountain", p);
        }
        addCardToZone("Lightning Bolt", p, ZoneType.Library);
        addCardToZone("Lightning Bolt", p, ZoneType.Library);
        Card lus = addCardToZone("Light Up the Stage", p, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        SpellAbility sa = lus.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        GameSimulator sim = createSimulator(p);
        sim.simulateSpellAbility(sa);
        Game live = sim.getSimulatedGameState();
        Card bolt = inZone(live, "Lightning Bolt", ZoneType.Exile);
        AssertJUnit.assertNotNull("Light Up the Stage exiled a Bolt", bolt);
        String liveDesc = mayPlayDesc(bolt, live.getPlayers().get(1));
        AssertJUnit.assertNotNull("live: the exiled Bolt may be cast", liveDesc);
        Game off = copy(live, 0);
        String offDesc = mayPlayDesc(off.findById(bolt.getId()), off.getPlayers().get(1));
        AssertJUnit.assertNotNull(offDesc);
        AssertJUnit.assertFalse("level 0: another description (" + offDesc + " vs " + liveDesc + ")", liveDesc.equals(offDesc));
        Game on = copy(live, 1);
        AssertJUnit.assertEquals(liveDesc, mayPlayDesc(on.findById(bolt.getId()), on.getPlayers().get(1)));
    }

    /**
     * The census's level-1 residual (Brazen Borrower, Bonecrusher Giant, Virtue of Persistence): an adventurer cast from
     * exile after its adventure. In exile the card is still its adventure (state Secondary: "Stomp"), and the may-play
     * spell's description names it ("... by Stomp"); the copier set a card's state on the battlefield only, so the
     * copy's card was "Bonecrusher Giant" and the description differed.
     */
    @Test
    public void anExiledAdventurerKeepsItsState() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        Card giant = addCardToZone("Bonecrusher Giant", p, ZoneType.Exile);
        giant.setState(forge.card.CardStateName.Secondary, true);
        giant.setExiledWith(giant);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("Stomp", giant.getName());
        Card off = copy(game, 0).findById(giant.getId());
        AssertJUnit.assertEquals(forge.card.CardStateName.Original, off.getCurrentStateName());
        Card on = copy(game, 1).findById(giant.getId());
        AssertJUnit.assertEquals(forge.card.CardStateName.Secondary, on.getCurrentStateName());
        AssertJUnit.assertEquals("Stomp", on.getName());
        AssertJUnit.assertEquals(giant.isOnAdventure(), on.isOnAdventure());
    }

    @Test
    public void imprintedCardsCarry() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        Card mox = addCard("Chrome Mox", p);
        Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Exile);
        mox.addImprintedCard(bolt);
        bolt.setExiledWith(mox);
        game.getAction().checkStateEffects(true);
        Game off = copy(game, 0);
        AssertJUnit.assertTrue(findCardWithName(off, "Chrome Mox").getImprintedCards().isEmpty());
        Game on = copy(game, 1);
        Card m = findCardWithName(on, "Chrome Mox");
        AssertJUnit.assertEquals(1, m.getImprintedCards().size());
        AssertJUnit.assertSame(on.findById(bolt.getId()), m.getImprintedCards().get(0));
        AssertJUnit.assertSame(m, on.findById(bolt.getId()).getExiledWith());
    }

    /** The copier rebuilds a command-zone effect without its emblem flag (the observation's command tokens list emblems). */
    @Test
    public void emblemsStayEmblems() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        Card e = new Card(game.nextCardId(), game);
        e.setGamePieceType(forge.card.GamePieceType.EFFECT);
        e.setName("Emblem - Test");
        e.setOwner(p);
        e.setEmblem(true);
        p.getZone(ZoneType.Command).add(e);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertFalse(copy(game, 0).findById(e.getId()).isEmblem());
        AssertJUnit.assertTrue(copy(game, 1).findById(e.getId()).isEmblem());
    }

    @Test
    public void aCopyWithFidelityPlaysOnAndLeavesTheLiveGameAlone() {
        Game live = usherAttacked();
        Player p = live.getPlayers().get(1);
        final int attacks = findCardWithName(live, "Usher of the Fallen").getDamageHistory().getCreatureAttacksThisTurn();
        final int liveAttacked = p.getCreaturesAttackedThisTurn().size();
        Game on = copy(live, 1);
        // play the copy into the next turn: the carried per-turn state resets there as in a live game
        playUntilNextTurn(on);
        AssertJUnit.assertEquals(0, findCardWithName(on, "Usher of the Fallen").getDamageHistory().getCreatureAttacksThisTurn());
        AssertJUnit.assertEquals(0, on.getPlayers().get(1).getCreaturesAttackedThisTurn().size());
        // the live game is untouched
        AssertJUnit.assertEquals(attacks, findCardWithName(live, "Usher of the Fallen").getDamageHistory().getCreatureAttacksThisTurn());
        AssertJUnit.assertEquals(liveAttacked, p.getCreaturesAttackedThisTurn().size());
        AssertJUnit.assertTrue(playable(findCardWithName(live, "Usher of the Fallen"), "Boast", p));
    }

    @Test
    public void searchCopiersFollowTheOption() {
        Game live = usherAttacked();
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.copyFidelity = 1;
        LookaheadSearch on = new LookaheadSearch(c);
        LookaheadSearch off = new LookaheadSearch(new LookaheadSearch.Config());
        try {
            Game a = on.copierOf(live).makeCopy();
            AssertJUnit.assertEquals(1, findCardWithName(a, "Usher of the Fallen").getDamageHistory().getCreatureAttacksThisTurn());
            Game b = off.copierOf(live).makeCopy();
            AssertJUnit.assertEquals(0, findCardWithName(b, "Usher of the Fallen").getDamageHistory().getCreatureAttacksThisTurn());
        } finally {
            on.shutdown();
            off.shutdown();
        }
    }

    @Test
    public void playoutFailuresAreAlwaysReportedByClass() {
        LookaheadSearch s = new LookaheadSearch(new LookaheadSearch.Config());
        try {
            JsonObject pf = s.getStats().toJson().getAsJsonObject("playoutFailures");
            AssertJUnit.assertNotNull(pf);
            for (String k : LookaheadSearch.Stats.PLAYOUT_FAILURE_CLASSES) {
                AssertJUnit.assertEquals(k, 0, pf.get(k).getAsLong());
            }
            AssertJUnit.assertFalse(s.getStats().toJson().has("playoutFailureCands"));
        } finally {
            s.shutdown();
        }
        LookaheadSearch.Rollout r = new LookaheadSearch.Rollout();
        r.ok = false;
        r.seatFailure = "play-out first action: the searched candidate is not in the play-out's menu";
        AssertJUnit.assertEquals("candidate_not_in_menu", LookaheadSearch.failureClass(r));
        r.seatFailure = "max_decisions";
        AssertJUnit.assertEquals("max_decisions", LookaheadSearch.failureClass(r));
        r.seatFailure = "cpu_cap";
        AssertJUnit.assertEquals("budget", LookaheadSearch.failureClass(r));
        r.seatFailure = "server: boom";
        AssertJUnit.assertEquals("other", LookaheadSearch.failureClass(r));
        r.failClass = "candidate_not_in_copy";
        AssertJUnit.assertEquals("candidate_not_in_copy", LookaheadSearch.failureClass(r));
        r.aborted = true;
        AssertJUnit.assertEquals("budget", LookaheadSearch.failureClass(r));
        LookaheadSearch.Stats st = new LookaheadSearch.Stats();
        st.countPlayoutFailure("candidate_not_in_menu", "activate Usher of the Fallen");
        st.countPlayoutFailure("candidate_not_in_menu", "activate Usher of the Fallen");
        st.countPlayoutFailure("nonsense", null);
        JsonObject j = st.toJson();
        AssertJUnit.assertEquals(2, j.getAsJsonObject("playoutFailures").get("candidate_not_in_menu").getAsLong());
        AssertJUnit.assertEquals(1, j.getAsJsonObject("playoutFailures").get("other").getAsLong());
        AssertJUnit.assertEquals(2, j.getAsJsonObject("playoutFailureCands")
                .get("candidate_not_in_menu activate Usher of the Fallen").getAsLong());
    }
}
