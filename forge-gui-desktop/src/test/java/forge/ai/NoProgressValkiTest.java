package forge.ai;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.LobbyPlayer;
import forge.ai.simulation.SpellAbilityPicker;
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
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.NoProgressGuard;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane jar-glow-valki-1027: the owner's ranked game forge-mvr55yi8 (2026-10-27, reports 20-35-21, 20-39-40, 20-40-14,
 * 20-40-59). On the owner's turn 8, main 1, with Nissa, Ascended Animist on the stack, the AI seat answered every priority
 * with Valki, God of Lies' "{X}: Choose a creature card exiled with Valki with mana value X. Valki becomes a copy of that
 * card." at X = 0, with no card exiled with Valki: the ability did nothing, the owner passed, the AI was back where it
 * started and did it again; Nissa never resolved. Owner: "fix it for the generalized case".
 *
 * <p>The board is the report's (20-39-40): the owner's Library of Alexandria, Wall of Roots with one -0/-1 counter,
 * three tapped Forests and a tapped Basalt Monolith; the AI's tapped Swamp, Plains, Silent Clearing, Valki and Deathrite
 * Shaman, a summoning-sick Recruiter of the Guard; nothing in exile; Nissa on the stack. The checks:
 * <ul>
 *   <li>no AI chooser is offered the empty-choice ability: Forge AI ({@code canPlaySa}), the look-ahead's candidates
 *       ({@link SpellAbilityPicker}), the bridge's priority menu (the policy seat and every bench host);</li>
 *   <li>whatever chooses it anyway (here a scripted chooser that always picks it, as the live seat did), the no-progress
 *       guard ({@link NoProgressGuard}) stops the second activation in the same state and Nissa resolves; the same
 *       for a synthetic repeatable no-op ("{0}: Untap this", on an untapped creature); a repeat that changes the state
 *       is never stopped;</li>
 *   <li>the ability stays legal and resolves to nothing, and its stack text no longer says Valki copies itself.</li>
 * </ul>
 */
public class NoProgressValkiTest extends AITest {

    /** The report's board; the owner (player 0) has priority with Nissa on the stack. */
    private static final class Board {
        Game game;
        Player owner;
        Player ai;
        Card valki;
        Card nissa;
    }

    private Board board(final Game game) {
        final Board b = new Board();
        b.game = game;
        b.owner = game.getPlayers().get(0);
        b.ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, b.owner);
        for (Player p : new Player[] {b.owner, b.ai}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Forest" : "Grizzly Bears", p, ZoneType.Library);
            }
        }
        addCard("Library of Alexandria", b.owner);
        for (String n : new String[] {"Forest", "Forest", "Forest", "Basalt Monolith"}) {
            addCard(n, b.owner).setTapped(true);
        }
        final Card wall = addCard("Wall of Roots", b.owner);
        wall.addCounterInternal(CounterEnumType.M0M1, 1, b.owner, false, null, null);
        addCardToZone("Questing Beast", b.owner, ZoneType.Hand);
        addCardToZone("Mountain", b.owner, ZoneType.Hand);
        for (String n : new String[] {"Swamp", "Plains", "Silent Clearing", "Deathrite Shaman"}) {
            final Card c = addCard(n, b.ai);
            c.setTapped(true);
            c.setSickness(false);
        }
        b.valki = addCard("Valki, God of Lies", b.ai);
        b.valki.setTapped(true);
        b.valki.setSickness(false);
        addCard("Recruiter of the Guard", b.ai);
        addCardToZone("Elite Spellbinder", b.ai, ZoneType.Hand);
        b.nissa = addCardToZone("Nissa, Ascended Animist", b.owner, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        final SpellAbility cast = b.nissa.getFirstSpellAbility();
        cast.setActivatingPlayer(b.owner);
        game.getStack().addAndUnfreeze(cast);
        game.getPhaseHandler().setPriority(b.owner);
        return b;
    }

    private Board board() {
        return board(initAndCreateGame());
    }

    /** One of the owner's cards exiled with Valki (as Valki's enter trigger does: until it leaves the battlefield). */
    private Card exileWith(final Board b, final String name) {
        final Card c = addCardToZone(name, b.owner, ZoneType.Hand);
        final Card moved = b.game.getAction().exile(c, null, null);
        moved.setExiledWith(b.valki);
        moved.setExiledBy(b.ai);
        b.valki.addExiledCard(moved);
        return moved;
    }

    private static SpellAbility copyAbility(final Card valki, final Player ai) {
        for (SpellAbility sa : valki.getSpellAbilities()) {
            if (sa.getApi() == ApiType.ChooseCard) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        throw new AssertionError("Valki has no ChooseCard ability");
    }

    private static boolean names(final List<SpellAbility> l, final Card host, final ApiType api) {
        for (SpellAbility sa : l) {
            if (sa.getHostCard() == host && sa.getApi() == api) {
                return true;
            }
        }
        return false;
    }

    /** Always passes. */
    static final class Passer extends PlayerControllerAi {
        Passer(final Game g, final Player p, final LobbyPlayer lp) {
            super(g, p, lp);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            return null;
        }
    }

    /**
     * The live seat's choice, scripted: the given ability whenever it can be played (X = 0), else pass. Counts the
     * plays. Every other ask is Forge AI's.
     */
    static final class Repeater extends PlayerControllerAi {
        final SpellAbility pick;
        int played;
        int max = Integer.MAX_VALUE;

        Repeater(final Game g, final Player p, final LobbyPlayer lp, final SpellAbility pick) {
            super(g, p, lp);
            this.pick = pick;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            pick.setActivatingPlayer(getPlayer());
            if (played >= max || !pick.canPlay()) {
                return null;
            }
            pick.setXManaCostPaid(0);
            return Lists.newArrayList(pick);
        }

        @Override
        public boolean playChosenSpellAbility(final SpellAbility sa) {
            final boolean ok = super.playChosenSpellAbility(sa);
            if (ok) {
                played++;
            }
            return ok;
        }
    }

    private static Repeater repeat(final Board b, final SpellAbility pick) {
        b.owner.dangerouslySetController(new Passer(b.game, b.owner, b.owner.getLobbyPlayer()));
        final Repeater r = new Repeater(b.game, b.ai, b.ai.getLobbyPlayer(), pick);
        b.ai.dangerouslySetController(r);
        return r;
    }

    /** Main-loop steps until Nissa has left the stack, at most {@code cap}; returns the steps taken. */
    private static int untilNissaResolves(final Board b, final int cap) {
        int steps = 0;
        while (steps < cap && !b.game.isGameOver() && b.nissa.isInZone(ZoneType.Stack)) {
            b.game.getPhaseHandler().mainLoopStep();
            steps++;
        }
        return steps;
    }

    // ------------------------------------------------------------------------------------------- the choosers

    @Test
    public void forgeAiDoesNotChooseIt() {
        final Board b = board();
        final SpellAbility copy = copyAbility(b.valki, b.ai);
        final PlayerControllerAi ctrl = (PlayerControllerAi) b.ai.getController();
        AssertJUnit.assertTrue("legal by the rules", copy.canPlay());
        AssertJUnit.assertFalse(ctrl.getAi().canPlaySa(copy) == AiPlayDecision.WillPlay);
        b.game.getPhaseHandler().setPriority(b.ai);
        final List<SpellAbility> got = ctrl.chooseSpellAbilityToPlay();
        AssertJUnit.assertFalse(got != null && names(got, b.valki, ApiType.ChooseCard));
    }

    @Test
    public void emptyChoiceDetector() {
        final Board b = board();
        final SpellAbility copy = copyAbility(b.valki, b.ai);
        AssertJUnit.assertTrue("nothing exiled with Valki", AiEmptyChoice.emptyForEveryX(copy, b.ai));
        AssertJUnit.assertTrue(AiEmptyChoice.emptyAt(copy, b.ai, 0));
        // a creature card of mana value 4 exiled with Valki: X = 4 chooses it, every other X still chooses nothing
        final Card exiled = exileWith(b, "Questing Beast");
        AssertJUnit.assertTrue(exiled.isInZone(ZoneType.Exile));
        AssertJUnit.assertFalse(AiEmptyChoice.emptyForEveryX(copy, b.ai));
        AssertJUnit.assertFalse(AiEmptyChoice.emptyAt(copy, b.ai, 4));
        AssertJUnit.assertTrue(AiEmptyChoice.emptyAt(copy, b.ai, 0));
        AssertJUnit.assertTrue(AiEmptyChoice.emptyAt(copy, b.ai, 3));
        AssertJUnit.assertNull("X restored", copy.getXManaCostPaid());
        // a trigger that watches activations makes the activation itself do something: not flagged
        final Board r = board();
        addCard("Rings of Brighthearth", r.ai);
        AssertJUnit.assertFalse(AiEmptyChoice.emptyForEveryX(copyAbility(r.valki, r.ai), r.ai));
        // other shapes are never flagged
        AssertJUnit.assertFalse(AiEmptyChoice.emptyForEveryX(b.nissa.getFirstSpellAbility(), b.owner));
        AssertJUnit.assertFalse(AiEmptyChoice.emptyForEveryX(null, b.ai));
    }

    @Test
    public void lookaheadCandidatesOmitIt() {
        final Board b = board();
        b.game.getPhaseHandler().setPriority(b.ai);
        final List<SpellAbility> cands = new SpellAbilityPicker(b.ai).getCandidateSpellsAndAbilities();
        AssertJUnit.assertFalse(names(cands, b.valki, ApiType.ChooseCard));
    }

    @Test
    public void bridgeMenuOmitsIt() throws Exception {
        final BenchSession session = new BenchSession(new JsonRpcChannel(InputStream.nullInputStream(),
                OutputStream.nullOutputStream()));
        final List<RegisteredPlayer> players = Lists.newArrayList();
        for (int s = 0; s < 2; s++) {
            players.add(new RegisteredPlayer(new Deck()).setPlayer(
                    new LobbyPlayerBridge("Seat" + s, null, session, BenchSession.Mode.NULL, s)));
        }
        final GameRules rules = new GameRules(GameType.Constructed);
        final Game game = new Game(players, rules, new Match(rules, players, "valki-menu"));
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        game.getPhaseHandler().onStackResolved();
        session.setLiveGame(game);
        final Board b = board(game);
        b.game.getPhaseHandler().setPriority(b.ai);
        final PlayerControllerBridge bridge = (PlayerControllerBridge) b.ai.getController();
        final Method legal = PlayerControllerBridge.class.getDeclaredMethod("legalSpellAbilities");
        legal.setAccessible(true);
        @SuppressWarnings("unchecked")
        final List<SpellAbility> menu = (List<SpellAbility>) legal.invoke(bridge);
        AssertJUnit.assertFalse("the policy seat's menu offered Valki's empty copy", names(menu, b.valki, ApiType.ChooseCard));
        // with a creature card of mana value 0 exiled with Valki (X = 0 chooses it) it is offered
        exileWith(b, "Memnite");
        @SuppressWarnings("unchecked")
        final List<SpellAbility> menu2 = (List<SpellAbility>) legal.invoke(bridge);
        AssertJUnit.assertTrue(names(menu2, b.valki, ApiType.ChooseCard));
    }

    // ------------------------------------------------------------------------------------------- the guard

    @Test
    public void reportStateLoopStopsAndNissaResolves() {
        final Board b = board();
        final Repeater r = repeat(b, copyAbility(b.valki, b.ai));
        final int steps = untilNissaResolves(b, 400);
        AssertJUnit.assertFalse("Nissa never resolved: Valki activated " + r.played + " times in " + steps + " steps",
                b.nissa.isInZone(ZoneType.Stack));
        AssertJUnit.assertEquals("one activation, then the guard passes", 1, r.played);
        AssertJUnit.assertEquals(1, b.game.getPhaseHandler().noProgressGuard().stops());
        AssertJUnit.assertTrue(b.nissa.isInZone(ZoneType.Battlefield));
        AssertJUnit.assertFalse("Valki is no copy of anything", b.valki.isCloned());
    }

    @Test
    public void guardOffReproducesTheLoop() {
        final Board b = board();
        final Repeater r = repeat(b, copyAbility(b.valki, b.ai));
        NoProgressGuard.setEnabled(false);
        try {
            untilNissaResolves(b, 200);
        } finally {
            NoProgressGuard.setEnabled(true);
        }
        AssertJUnit.assertTrue(b.nissa.isInZone(ZoneType.Stack));
        AssertJUnit.assertTrue(r.played > 20);
    }

    @Test
    public void syntheticRepeatableNoopStops() {
        final Board b = board();
        final Card bear = addCard("Grizzly Bears", b.ai);
        bear.setSickness(false);
        final SpellAbility untap = AbilityFactory.getAbility(
                "AB$ Untap | Cost$ 0 | Defined$ Self | SpellDescription$ Untap CARDNAME.", bear);
        bear.addSpellAbility(untap);
        final Repeater r = repeat(b, untap);
        untilNissaResolves(b, 400);
        AssertJUnit.assertFalse(b.nissa.isInZone(ZoneType.Stack));
        AssertJUnit.assertEquals(1, r.played);
    }

    @Test
    public void aRepeatThatChangesTheStateIsNeverStopped() {
        final Board b = board();
        final Card bear = addCard("Grizzly Bears", b.ai);
        bear.setSickness(false);
        final SpellAbility pump = AbilityFactory.getAbility(
                "AB$ Pump | Cost$ 0 | Defined$ Self | NumAtt$ +1 | SpellDescription$ CARDNAME gets +1/+0 until end of turn.", bear);
        bear.addSpellAbility(pump);
        final Repeater r = repeat(b, pump);
        r.max = 6;
        untilNissaResolves(b, 400);
        AssertJUnit.assertEquals("every pump changed the board: none stopped", 6, r.played);
        AssertJUnit.assertEquals(8, bear.getNetPower());
        AssertJUnit.assertFalse(b.nissa.isInZone(ZoneType.Stack));
    }

    // ------------------------------------------------------------------------------------------- the effect

    @Test
    public void stackTextNamesNoCopy() {
        final Board b = board();
        final SpellAbility copy = copyAbility(b.valki, b.ai);
        AssertJUnit.assertTrue("legal by the rules", copy.canPlay());
        copy.setXManaCostPaid(0);
        b.game.getStack().addAndUnfreeze(copy);
        final String text = b.game.getStack().peek().getStackDescription();
        AssertJUnit.assertFalse("stack text: " + text, text.contains("becomes a copy of Valki"));
        AssertJUnit.assertTrue("stack text: " + text, text.contains("becomes a copy of that card"));
    }

    @Test
    public void itResolvesToNothing() {
        final Board b = board();
        final SpellAbility copy = copyAbility(b.valki, b.ai);
        final String before = forge.ai.simulation.LookaheadSearch.fingerprint(b.game);
        final int stack = b.game.getStack().size();
        copy.setXManaCostPaid(0);
        b.game.getStack().addAndUnfreeze(copy);
        AssertJUnit.assertEquals(stack + 1, b.game.getStack().size());
        b.game.getStack().resolveStack();
        AssertJUnit.assertEquals("only Nissa left", stack, b.game.getStack().size());
        AssertJUnit.assertFalse(b.valki.isCloned());
        AssertJUnit.assertEquals("Valki, God of Lies", b.valki.getName());
        AssertJUnit.assertTrue(b.game.getCardsIn(ZoneType.Exile).isEmpty());
        AssertJUnit.assertEquals(before, forge.ai.simulation.LookaheadSearch.fingerprint(b.game));
    }

    @Test
    public void guardFingerprintIsUnchangedByTheNoop() {
        final Board b = board();
        b.game.getPhaseHandler().setPriority(b.ai);
        final String before = NoProgressGuard.fingerprint(b.game);
        final SpellAbility copy = copyAbility(b.valki, b.ai);
        copy.setXManaCostPaid(0);
        b.game.getStack().addAndUnfreeze(copy);
        AssertJUnit.assertFalse(before.equals(NoProgressGuard.fingerprint(b.game)));
        b.game.getStack().resolveStack();
        b.game.getPhaseHandler().setPriority(b.ai);
        AssertJUnit.assertEquals(before, NoProgressGuard.fingerprint(b.game));
    }
}
