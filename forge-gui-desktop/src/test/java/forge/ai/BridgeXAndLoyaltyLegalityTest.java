package forge.ai;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.gson.JsonObject;

import forge.bench.BenchSession;
import forge.bench.JsonRpcChannel;
import forge.bench.LobbyPlayerBridge;
import forge.bench.PlayerControllerBridge;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * mtgx lane forge-fix-p1p2-1028 (anomaly audit 1028 P1/P3): a bridged seat whose answers are played (an RL or policy
 * seat) must not have its choice refused, and the priority handed to Forge AI, by Forge AI's own preferences.
 * <ul>
 *   <li>X spells: the announced X is stepped down to the largest payable X (not refused) when the mana estimate over-counts
 *       (a Talisman's "Combo G U" counted as three mana, a colour-restricted source such as Mishra's Workshop) or misses a
 *       cost increase (Thalia, Guardian of Thraben); Walking Ballista at X = 0 is accepted;</li>
 *   <li>planeswalkers: a loyalty ability that costs all the walker's loyalty (The Wandering Emperor's -1 at loyalty 1) is
 *       offered and accepted, with and without opposing creatures, on every random seed (no "try not to lose the
 *       planeswalker" coin, no random draw).</li>
 * </ul>
 * Seat 1 is bridged ({@code Mode.BRIDGE}) with an in-process answerer that picks one menu entry and announces an X; seat
 * 0 is a plain Forge seat. The answer is driven through {@link PlayerControllerBridge#chooseSpellAbilityToPlay}, then
 * played and resolved as the game would.
 */
public class BridgeXAndLoyaltyLegalityTest extends AITest {

    private static final class Seat {
        Game game;
        Player opp;
        Player me;
        PlayerControllerBridge bridge;
        final List<String> refusals = new ArrayList<>();
        Predicate<SpellAbility> pick = sa -> false;
        Integer x;
        int priorityAsks;
        boolean offered;
    }

    @AfterMethod
    public void clearRandom() {
        MyRandom.setThreadRandom(null);
    }

    private Seat seat() {
        final Seat s = new Seat();
        final BenchSession session = new BenchSession(new JsonRpcChannel(InputStream.nullInputStream(),
                OutputStream.nullOutputStream()));
        final List<RegisteredPlayer> players = Lists.newArrayList();
        players.add(new RegisteredPlayer(new Deck()).setPlayer(
                new LobbyPlayerBridge("Seat0", null, session, BenchSession.Mode.NULL, 0)));
        players.add(new RegisteredPlayer(new Deck()).setPlayer(
                new LobbyPlayerBridge("Seat1", null, session, BenchSession.Mode.BRIDGE, 1)));
        final GameRules rules = new GameRules(GameType.Constructed);
        final Game game = new Game(players, rules, new Match(rules, players, "bridge-x-pw"));
        game.setAge(GameStage.Play);
        s.game = game;
        s.opp = game.getPlayers().get(0);
        s.me = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, s.me);
        game.getPhaseHandler().onStackResolved();
        for (Player p : new Player[] {s.opp, s.me}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Forest" : "Island", p, ZoneType.Library);
            }
        }
        session.setLiveGame(game);
        session.setLocalAnswerer(new BenchSession.LocalAnswerer() {
            @Override
            public JsonObject answer(final Game g, final Player p, final String kind, final JsonObject body) {
                return null;
            }

            @Override
            public JsonObject answer(final Game g, final Player p, final String method, final String kind,
                    final JsonObject body, final Object menuObjects) {
                if (p != s.me || !"priority".equals(kind) || !(menuObjects instanceof List)) {
                    return null; // delegate: Forge decides (targets, the opponent, ...)
                }
                s.priorityAsks++;
                final List<?> menu = (List<?>) menuObjects;
                for (int i = 0; i < menu.size(); i++) {
                    if (s.pick.test((SpellAbility) menu.get(i))) {
                        s.offered = true;
                        final JsonObject o = new JsonObject();
                        o.addProperty("choice", i + 1);
                        if (s.x != null) {
                            o.addProperty("x", s.x);
                        }
                        return o;
                    }
                }
                final JsonObject pass = new JsonObject();
                pass.addProperty("choice", 0);
                return pass;
            }

            @Override
            public void onRefused(final Game g, final Player p, final String method, final String why) {
                s.refusals.add(why);
            }
        });
        s.bridge = (PlayerControllerBridge) s.me.getController();
        return s;
    }

    private static void ready(final Seat s) {
        s.game.getAction().checkStateEffects(true);
        s.game.getPhaseHandler().setPriority(s.me);
    }

    /** The bridge's priority answer; asserts it is the answerer's pick (not refused to Forge AI), and returns it. */
    private static SpellAbility choose(final Seat s) {
        ready(s);
        final List<SpellAbility> got = s.bridge.chooseSpellAbilityToPlay();
        AssertJUnit.assertTrue("the pick was not in the priority menu", s.offered);
        AssertJUnit.assertEquals("refused: " + s.refusals, 0, s.refusals.size());
        AssertJUnit.assertNotNull(got);
        AssertJUnit.assertEquals(1, got.size());
        AssertJUnit.assertTrue("the bridge returned something else: " + got.get(0), s.pick.test(got.get(0)));
        return got.get(0);
    }

    /** Plays the chosen ability as the game would (pays its cost) and resolves the stack. */
    private static void playAndResolve(final Seat s, final SpellAbility sa) {
        AssertJUnit.assertTrue("could not play " + sa, s.bridge.playChosenSpellAbility(sa));
        int guard = 0;
        while (!s.game.getStack().isEmpty() && guard++ < 20) {
            s.game.getStack().resolveStack();
        }
        AssertJUnit.assertTrue(s.game.getStack().isEmpty());
    }

    private static Predicate<SpellAbility> spellOf(final Card c) {
        return sa -> sa.getHostCard() == c && sa.isSpell();
    }

    private static int untapped(final Player p) {
        int n = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isTapped() && !c.getManaAbilities().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------ X spells

    /**
     * Green Sun's Zenith with Forest, Forest and Talisman of Curiosity: three mana, X = 2 at most. The estimate counts the
     * Talisman's "Combo G U" as three (five mana, X ceiling 4). The answer X = 4 is stepped down to 2 and played.
     */
    @Test
    public void gszTalismanOvercountStepsDownToThePayableX() {
        final Seat s = seat();
        addCard("Forest", s.me);
        addCard("Forest", s.me);
        addCard("Talisman of Curiosity", s.me);
        addCardToZone("Grizzly Bears", s.me, ZoneType.Library);
        final Card gsz = addCardToZone("Green Sun's Zenith", s.me, ZoneType.Hand);
        s.pick = spellOf(gsz);
        s.x = 4;
        final SpellAbility sa = choose(s);
        AssertJUnit.assertEquals(Integer.valueOf(2), sa.getXManaCostPaid());
        playAndResolve(s, sa);
        AssertJUnit.assertEquals("all three sources paid", 0, untapped(s.me));
        AssertJUnit.assertEquals("Grizzly Bears (mana value 2) fetched", 1,
                countCardsWithName(s.game, "Grizzly Bears", ZoneType.Battlefield));
    }

    /**
     * Finale of Devastation ({X}{G}{G}) with Forest, Forest and Mishra's Workshop (three colourless, artifact spells only):
     * the estimate counts five mana (X ceiling 3), only X = 0 is payable. The answer X = 3 is stepped down to 0 and played.
     */
    @Test
    public void finaleColourRestrictedSourceStepsDownToZero() {
        final Seat s = seat();
        addCard("Forest", s.me);
        addCard("Forest", s.me);
        addCard("Mishra's Workshop", s.me);
        addCardToZone("Dryad Arbor", s.me, ZoneType.Library);
        final Card finale = addCardToZone("Finale of Devastation", s.me, ZoneType.Hand);
        s.pick = spellOf(finale);
        s.x = 3;
        final SpellAbility sa = choose(s);
        AssertJUnit.assertEquals(Integer.valueOf(0), sa.getXManaCostPaid());
        playAndResolve(s, sa);
        AssertJUnit.assertTrue("Finale resolved", s.game.getCardState(finale).isInZone(ZoneType.Graveyard));
    }

    /**
     * Green Sun's Zenith with three Forests under the opponent's Thalia, Guardian of Thraben (noncreature spells cost {1}
     * more): the estimate's X ceiling is 2, the taxed spell pays X = 1 at most. X = 2 is stepped down to 1 and played.
     */
    @Test
    public void gszUnderThaliaStepsDownToThePayableX() {
        final Seat s = seat();
        addCard("Thalia, Guardian of Thraben", s.opp);
        for (int i = 0; i < 3; i++) {
            addCard("Forest", s.me);
        }
        addCardToZone("Grizzly Bears", s.me, ZoneType.Library);
        final Card gsz = addCardToZone("Green Sun's Zenith", s.me, ZoneType.Hand);
        s.pick = spellOf(gsz);
        s.x = 2;
        final SpellAbility sa = choose(s);
        AssertJUnit.assertEquals(Integer.valueOf(1), sa.getXManaCostPaid());
        playAndResolve(s, sa);
        AssertJUnit.assertEquals(0, untapped(s.me));
    }

    /** An affordable X is announced as asked (no step-down). */
    @Test
    public void payableXIsAnnouncedAsAsked() {
        final Seat s = seat();
        for (int i = 0; i < 4; i++) {
            addCard("Forest", s.me);
        }
        addCardToZone("Grizzly Bears", s.me, ZoneType.Library);
        final Card gsz = addCardToZone("Green Sun's Zenith", s.me, ZoneType.Hand);
        s.pick = spellOf(gsz);
        s.x = 3;
        final SpellAbility sa = choose(s);
        AssertJUnit.assertEquals(Integer.valueOf(3), sa.getXManaCostPaid());
    }

    /** Walking Ballista at X = 0 (no mana at all) is offered and accepted; it is cast as a 0/0 and dies. */
    @Test
    public void ballistaAtXZeroIsAccepted() {
        final Seat s = seat();
        final Card ballista = addCardToZone("Walking Ballista", s.me, ZoneType.Hand);
        s.pick = spellOf(ballista);
        s.x = 0;
        final SpellAbility sa = choose(s);
        AssertJUnit.assertEquals(Integer.valueOf(0), sa.getXManaCostPaid());
        playAndResolve(s, sa);
        s.game.getAction().checkStateEffects(true);
        AssertJUnit.assertTrue(s.game.getCardState(ballista).isInZone(ZoneType.Graveyard));
    }

    /** Walking Ballista asked X = 3 on two Forests and a Talisman (three mana: X = 1) is stepped down to 1. */
    @Test
    public void ballistaOvercountStepsDown() {
        final Seat s = seat();
        addCard("Forest", s.me);
        addCard("Forest", s.me);
        addCard("Talisman of Curiosity", s.me);
        final Card ballista = addCardToZone("Walking Ballista", s.me, ZoneType.Hand);
        s.pick = spellOf(ballista);
        s.x = 3;
        final SpellAbility sa = choose(s);
        AssertJUnit.assertEquals(Integer.valueOf(1), sa.getXManaCostPaid());
    }

    // ------------------------------------------------------------------------------------------------ planeswalkers

    private Card emperorAtLoyaltyOne(final Seat s) {
        final Card emp = addCard("The Wandering Emperor", s.me);
        final int cur = emp.getCurrentLoyalty();
        if (cur < 1) {
            emp.addCounterInternal(CounterEnumType.LOYALTY, 1 - cur, s.me, false, null, null);
        } else if (cur > 1) {
            emp.subtractCounter(CounterEnumType.LOYALTY, cur - 1, s.me);
        }
        AssertJUnit.assertEquals(1, emp.getCurrentLoyalty());
        return emp;
    }

    private static Predicate<SpellAbility> minusOne(final Card emp) {
        return sa -> sa.getHostCard() == emp && sa.isPwAbility() && sa.getApi() == ApiType.Token;
    }

    /** The opponent has no creatures: Forge AI always refused the -1 at loyalty 1. It is offered, accepted and resolves. */
    @Test
    public void emperorMinusOneAtLoyaltyOneNoOpposingCreatures() {
        final Seat s = seat();
        final Card emp = emperorAtLoyaltyOne(s);
        s.pick = minusOne(emp);
        final SpellAbility sa = choose(s);
        playAndResolve(s, sa);
        s.game.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("a Samurai token", 1, countCardsWithName(s.game, "Samurai Token")
                + countCardsWithName(s.game, "Samurai"));
        AssertJUnit.assertTrue("the Emperor went to the graveyard at 0 loyalty",
                s.game.getCardState(emp).isInZone(ZoneType.Graveyard));
    }

    /**
     * The opponent has a creature: Forge AI refused on a 50 % coin. Offered and accepted on every one of 16 random
     * streams, and the menu build and re-check take no draw from the stream.
     */
    @Test
    public void emperorMinusOneAtLoyaltyOneEverySeedNoDraw() {
        for (long seed = 0; seed < 16; seed++) {
            final Seat s = seat();
            addCard("Grizzly Bears", s.opp);
            final Card emp = emperorAtLoyaltyOne(s);
            s.pick = minusOne(emp);
            final CountingRandom r = new CountingRandom(seed);
            MyRandom.setThreadRandom(r);
            final SpellAbility sa = choose(s);
            AssertJUnit.assertEquals("seed " + seed + ": " + r.floats + " float draws", 0, r.floats);
            AssertJUnit.assertTrue(s.pick.test(sa));
            MyRandom.setThreadRandom(null);
        }
    }

    /** A recorder (observe-only) keeps Forge's own test: its menu still drops the -1 when the opponent has no creatures. */
    @Test
    public void recorderMenuIsForgesOwn() throws Exception {
        final Seat s = seat();
        final Card emp = emperorAtLoyaltyOne(s);
        final BenchSession session = sessionOf(s);
        final BenchSession.LocalAnswerer played = session.getLocalAnswerer();
        session.setLocalAnswerer(new BenchSession.LocalAnswerer() {
            @Override
            public JsonObject answer(final Game g, final Player p, final String kind, final JsonObject body) {
                return null;
            }

            @Override
            public boolean observeOnly() {
                return true;
            }
        });
        ready(s);
        final Method legal = PlayerControllerBridge.class.getDeclaredMethod("legalSpellAbilities");
        legal.setAccessible(true);
        @SuppressWarnings("unchecked")
        final List<SpellAbility> rec = (List<SpellAbility>) legal.invoke(s.bridge);
        AssertJUnit.assertFalse(rec.stream().anyMatch(minusOne(emp)));
        session.setLocalAnswerer(played);
        @SuppressWarnings("unchecked")
        final List<SpellAbility> seat = (List<SpellAbility>) legal.invoke(s.bridge);
        AssertJUnit.assertTrue(seat.stream().anyMatch(minusOne(emp)));
    }

    private static BenchSession sessionOf(final Seat s) throws Exception {
        final java.lang.reflect.Field f = PlayerControllerBridge.class.getDeclaredField("session");
        f.setAccessible(true);
        return (BenchSession) f.get(s.bridge);
    }

    /** A java.util.Random that counts nextFloat draws (the planeswalker coin's draw). */
    static final class CountingRandom extends java.util.Random {
        int floats;

        CountingRandom(final long seed) {
            super(seed);
        }

        @Override
        public float nextFloat() {
            floats++;
            return super.nextFloat();
        }
    }
}
