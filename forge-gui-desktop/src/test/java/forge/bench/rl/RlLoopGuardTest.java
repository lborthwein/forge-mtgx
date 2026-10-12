package forge.bench.rl;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.ai.AiFixes;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.Localizer;

/**
 * Lane forge-live-fixes-1012: the live policy seat's "Cancel Search?" loop guard ({@link RlLoopGuard}, spec
 * {@code loopGuard=K}, off by default). Show and Tell resolves for the policy seat (seat 1) against a scripted policy
 * service: the card ask is answered with no card or a card, the confirm "Cancel Search? Up to 1 more card(s) can be
 * selected." with yes or no.
 * <ul>
 *   <li>(a) no card / no forever: with the guard off the seat repeats the pair until {@code maxDecisions} and degrades
 *       (Forge AI then chooses); with K = 2 (and 1, 3) the policy answers K no's and the seat answers the next confirm yes
 *       itself: nothing is put, the seat never degrades;</li>
 *   <li>(b) every sequence that ends in a card or a yes is answered the same with K = 2 as without the guard;</li>
 *   <li>(c) {@link RlLiveSeat.Spec#parse} takes {@code loopGuard} (default 0, refuses a negative);</li>
 *   <li>the trigger is exact: another ask between, another card ask, another ability or another confirm prompt resets
 *       or never counts.</li>
 * </ul>
 * Plays no whole game (Show and Tell resolves straight from a set-up board), so it needs no broker lease of its own.
 */
public class RlLoopGuardTest extends AITest {
    static final String SHA = "7f7d160785e58bd1da37435f8ed71e145d761fb34f9d45b40809880982388465";
    static final String[] HAND = {"Grizzly Bears", "Hill Giant", "Llanowar Elves"};
    static Path indexFile;

    @BeforeClass
    public static void index() throws Exception {
        final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
        int i = 2;
        for (String n : new String[] {"Show and Tell", "Grizzly Bears", "Hill Giant", "Llanowar Elves", "Island"}) {
            sb.append(n).append('\t').append(i++).append('\n');
        }
        indexFile = Files.createTempFile("loop-guard-index-", ".tsv");
        Files.write(indexFile, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String spec(final String server, final String extra) {
        return "server=" + server + ",policySha=" + SHA + ",cardIndex=" + indexFile + ",log=0"
                + (extra.isEmpty() ? "" : "," + extra);
    }

    /**
     * A scripted policy: the policy seat's n-th card ask (from 0) is answered {@code cards[min(n, last)]} (-1 = no card,
     * else that candidate), its n-th confirm {@code yes[min(n, last)]}.
     */
    static final class Script implements java.util.function.Function<RlWire.Decide, short[]> {
        final int[] cards;
        final boolean[] yes;
        final AtomicInteger cardAsks = new AtomicInteger(), confirms = new AtomicInteger(), other = new AtomicInteger();

        Script(final int[] cards, final boolean[] yes) {
            this.cards = cards;
            this.yes = yes;
        }

        @Override
        public short[] apply(final RlWire.Decide d) {
            if (d.family == RlSchema.F_CARDS) {
                final int n = cardAsks.getAndIncrement();
                final int c = cards[Math.min(n, cards.length - 1)];
                return c < 0 ? new short[0] : new short[] {(short) c};
            }
            if (d.family == RlSchema.F_CONFIRM) {
                final int n = confirms.getAndIncrement();
                return new short[] {(short) (yes[Math.min(n, yes.length - 1)] ? 0 : 1)}; // [yes, no]
            }
            other.incrementAndGet();
            return null;
        }
    }

    /** One resolution of Show and Tell (cast by seat 0, Forge AI) with the policy seat (seat 1) holding {@link #HAND}. */
    static final class Run {
        int decides;
        boolean degraded;
        List<String> putBySeat = new ArrayList<>();
        int handAfter;
        int cardAsks, confirms, otherAsks;
        int hits;
        String err;
    }

    private Run resolve(final String extra, final Script script) throws Exception {
        final Run r = new Run();
        final PrintStream was = System.err;
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try (FakeSearchService svc = new FakeSearchService(SHA)) {
            svc.script = script;
            final RlLiveSeat live = RlLiveSeat.connect(spec(svc.address(), extra), "loop-guard-test");
            Assert.assertNotNull(live, "the seat binds the fake service");
            final LobbyPlayerAi ai = new LobbyPlayerAi("Seat0", null);
            ai.setAiProfile("Default");
            final List<RegisteredPlayer> players = Arrays.asList(new RegisteredPlayer(new Deck()).setPlayer(ai),
                    new RegisteredPlayer(new Deck()).setPlayer(live.lobby("Seat1", 1, "Default")));
            final GameRules rules = new GameRules(GameType.Constructed);
            final Match match = new Match(rules, players, "loop-guard");
            final Game game = new Game(players, rules, match);
            final Player p0 = game.getRegisteredPlayers().get(0);
            final Player p1 = game.getRegisteredPlayers().get(1);
            game.setAge(GameStage.Play);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p0);
            game.getPhaseHandler().onStackResolved();
            for (int i = 0; i < 5; i++) {
                addCardToZone("Island", p0, ZoneType.Library);
                addCardToZone("Island", p1, ZoneType.Library);
            }
            for (String n : HAND) {
                addCardToZone(n, p1, ZoneType.Hand);
            }
            final Card st = addCardToZone("Show and Tell", p0, ZoneType.Hand);
            live.bind(game, 1, 722160001L, null, AiFixes.Mode.OFF);
            final SpellAbility sa = st.getFirstSpellAbility();
            sa.setActivatingPlayer(p0);
            try {
                AbilityUtils.resolve(sa);
            } finally {
                live.finish();
            }
            System.err.flush();
            r.err = buf.toString(StandardCharsets.UTF_8);
            r.decides = (int) svc.decides.get();
            r.degraded = live.degraded();
            for (Card c : p1.getCardsIn(ZoneType.Battlefield)) {
                r.putBySeat.add(c.getName());
            }
            r.handAfter = p1.getCardsIn(ZoneType.Hand).size();
            r.cardAsks = script.cardAsks.get();
            r.confirms = script.confirms.get();
            r.otherAsks = script.other.get();
            r.hits = (int) r.err.lines().filter(l -> l.startsWith("[loop-guard] ")).count();
        } finally {
            System.setErr(was);
            was.print(buf.toString(StandardCharsets.UTF_8));
        }
        return r;
    }

    static Script never() {
        return new Script(new int[] {-1}, new boolean[] {false});
    }

    // ------------------------------------------------------------------------------------------------ (a) the loop

    @Test
    public void withTheGuardOffNoCardAndNoLoopToTheCapAndDegrade() throws Exception {
        final Run r = resolve("maxDecisions=40", never());
        Assert.assertEquals(r.decides, 40, "the pair repeats until maxDecisions");
        Assert.assertEquals(r.cardAsks, 20);
        Assert.assertEquals(r.confirms, 20);
        Assert.assertTrue(r.degraded, "the cap degrades the live seat");
        Assert.assertTrue(r.err.contains("\"degraded\":true,\"reason\":\"max_decisions\""), r.err);
        Assert.assertEquals(r.hits, 0, "no guard without loopGuard");
        // the seat's lines are unchanged without the guard (the K8 search summary's own "loopGuard" stat is another thing)
        Assert.assertFalse(r.err.contains("\"loopGuardHits\""), "no hit count in the end line");
        Assert.assertFalse(r.err.contains("\"budgetMs\":8000,\"loopGuard\""), "no guard in the bound line");
        // after the degrade Forge AI answers the card ask (and any confirm: yes), so the resolution ends
        Assert.assertTrue(r.putBySeat.size() <= 1, "Forge AI's choice after the degrade: " + r.putBySeat);
        Assert.assertEquals(r.putBySeat.size() + r.handAfter, HAND.length);
    }

    @Test
    public void withKTwoThePolicyAnswersTwoNoesAndTheSeatAnswersTheThirdConfirmYes() throws Exception {
        final Run r = resolve("maxDecisions=40,loopGuard=2", never());
        Assert.assertEquals(r.cardAsks, 3, "the card ask still goes to the policy every time");
        Assert.assertEquals(r.confirms, 2, "K = 2 no-answers by the policy");
        Assert.assertEquals(r.decides, 5);
        Assert.assertEquals(r.hits, 1, "one [loop-guard] line");
        Assert.assertFalse(r.degraded, "the guard ends the loop: no cap, no degrade");
        Assert.assertTrue(r.putBySeat.isEmpty(), "yes = put nothing: " + r.putBySeat);
        Assert.assertEquals(r.handAfter, HAND.length);
        final String line = r.err.lines().filter(l -> l.startsWith("[loop-guard] ")).findFirst().orElse("");
        final JsonObject o = com.google.gson.JsonParser.parseString(line.substring("[loop-guard] ".length())).getAsJsonObject();
        Assert.assertEquals(o.get("cycles").getAsInt(), 2);
        Assert.assertEquals(o.get("source").getAsString(), "Show and Tell");
        Assert.assertEquals(o.get("cards").getAsInt(), 3);
        Assert.assertEquals(o.get("prompt").getAsString(),
                Localizer.getInstance().getMessage(RlLoopGuard.PROMPT_KEY, "1"));
        Assert.assertTrue(r.err.contains("\"loopGuardHits\":1"), "the end line counts the hit");
        Assert.assertTrue(r.err.contains("\"loopGuard\":2"), "the bound line names the guard");
    }

    @Test
    public void kIsTheNumberOfNoAnswersThePolicyGives() throws Exception {
        for (int k : new int[] {1, 3}) {
            final Run r = resolve("maxDecisions=40,loopGuard=" + k, never());
            Assert.assertEquals(r.confirms, k, "K = " + k);
            Assert.assertEquals(r.cardAsks, k + 1, "K = " + k);
            Assert.assertEquals(r.decides, 2 * k + 1, "K = " + k);
            Assert.assertEquals(r.hits, 1, "K = " + k);
            Assert.assertFalse(r.degraded);
            Assert.assertTrue(r.putBySeat.isEmpty());
        }
    }

    // ------------------------------------------------------------------------------------------------ (b) unchanged

    /** Every sequence that leaves the pair on its own: the same asks, answers and board with K = 2 as without. */
    @Test
    public void choicesThatEndOnTheirOwnAreUnchangedWithKTwo() throws Exception {
        final Object[][] cases = {
            // a card at once
            {new int[] {1}, new boolean[] {false}, 1, 0, "Hill Giant"},
            // no card, no, then a card
            {new int[] {-1, 0}, new boolean[] {false}, 2, 1, "Grizzly Bears"},
            // no card, no, no card, no, then a card: K = 2 no-answers, but the third confirm never comes
            {new int[] {-1, -1, 2}, new boolean[] {false}, 3, 2, "Llanowar Elves"},
            // no card, then yes (the policy's own way out)
            {new int[] {-1}, new boolean[] {true}, 1, 1, null},
            // no card, no, no card, yes
            {new int[] {-1}, new boolean[] {false, true}, 2, 2, null},
        };
        for (Object[] c : cases) {
            final String what = Arrays.toString((int[]) c[0]) + " " + Arrays.toString((boolean[]) c[1]);
            final Run off = resolve("maxDecisions=40", new Script((int[]) c[0], (boolean[]) c[1]));
            final Run on = resolve("maxDecisions=40,loopGuard=2", new Script((int[]) c[0], (boolean[]) c[1]));
            for (Run r : new Run[] {off, on}) {
                Assert.assertEquals(r.cardAsks, (int) c[2], what);
                Assert.assertEquals(r.confirms, (int) c[3], what);
                Assert.assertEquals(r.hits, 0, what);
                Assert.assertFalse(r.degraded, what);
                Assert.assertEquals(r.putBySeat, c[4] == null ? List.of() : List.of((String) c[4]), what);
            }
            Assert.assertEquals(on.decides, off.decides, what);
            Assert.assertEquals(on.otherAsks, off.otherAsks, what);
        }
    }

    // ------------------------------------------------------------------------------------------------ (c) the spec

    @Test
    public void specTakesLoopGuardOffByDefault() {
        Assert.assertEquals(RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "")).loopGuard, 0, "off by default");
        Assert.assertEquals(RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "loopGuard=2")).loopGuard, 2);
        Assert.assertEquals(RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "maxDecisions=1000,loopGuard=0")).loopGuard, 0);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "loopGuard=-1")));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "loopGuard=x")));
        Assert.assertThrows(IllegalArgumentException.class, () -> new RlLoopGuard(0));
    }

    // ------------------------------------------------------------------------------------------------ exact trigger

    static JsonObject ability(final int fid) {
        final JsonObject a = new JsonObject();
        a.addProperty("fid", fid);
        a.addProperty("source", "Show and Tell");
        a.addProperty("api", "ChangeZone");
        return a;
    }

    static JsonObject cardAsk(final int fid, final int... ids) {
        final JsonObject b = new JsonObject();
        b.addProperty("title", "Select a card from your hand");
        b.addProperty("min", 0);
        b.addProperty("max", 1);
        b.addProperty("destination", "Battlefield");
        final JsonArray o = new JsonArray();
        o.add("Hand");
        b.add("origin", o);
        b.addProperty("changeNum", 1);
        b.addProperty("chosen", 0);
        b.addProperty("optional", true);
        b.addProperty("single", true);
        final JsonArray m = new JsonArray();
        for (int id : ids) {
            final JsonObject c = new JsonObject();
            c.addProperty("id", id);
            m.add(c);
        }
        b.add("menu", m);
        b.add("ability", ability(fid));
        return b;
    }

    static JsonObject confirm(final int fid, final String message) {
        final JsonObject b = new JsonObject();
        b.addProperty("mode", RlLoopGuard.CONFIRM_MODE);
        b.addProperty("message", message);
        b.add("ability", ability(fid));
        return b;
    }

    static JsonObject none() {
        final JsonObject a = new JsonObject();
        a.add("choices", new JsonArray());
        return a;
    }

    static JsonObject yesNo(final boolean y) {
        final JsonObject a = new JsonObject();
        a.addProperty("yes", y);
        return a;
    }

    static String cancel(final int n) {
        return Localizer.getInstance().getMessage(RlLoopGuard.PROMPT_KEY, String.valueOf(n));
    }

    /** One no-card / no cycle through the guard; returns whether the guard answered the confirm. */
    static boolean cycle(final RlLoopGuard g, final JsonObject ask, final JsonObject conf) {
        Assert.assertFalse(g.fires(RlLoopGuard.CARDS_METHOD, "zoneChange", ask));
        g.saw(true, RlLoopGuard.CARDS_METHOD, "zoneChange", ask, null, none());
        if (g.fires(RlLoopGuard.CONFIRM_METHOD, "confirm", conf)) {
            return true;
        }
        g.saw(true, RlLoopGuard.CONFIRM_METHOD, "confirm", conf, null, yesNo(false));
        return false;
    }

    @Test
    public void theTriggerIsExact() {
        final JsonObject ask = cardAsk(7, 11, 12, 13);
        final JsonObject conf = confirm(7, cancel(1));
        // the plain loop: fires on the third confirm with K = 2
        RlLoopGuard g = new RlLoopGuard(2);
        Assert.assertFalse(cycle(g, ask, conf));
        Assert.assertFalse(cycle(g, ask, conf));
        Assert.assertTrue(cycle(g, ask, conf));
        Assert.assertEquals(g.hits, 1);
        // after a hit the count starts again
        Assert.assertFalse(cycle(g, ask, conf));
        Assert.assertFalse(cycle(g, ask, conf));
        Assert.assertTrue(cycle(g, ask, conf));

        // another ask between two cycles resets the count
        g = new RlLoopGuard(2);
        Assert.assertFalse(cycle(g, ask, conf));
        Assert.assertFalse(cycle(g, ask, conf));
        Assert.assertFalse(g.fires("chooseSpellAbilityToPlay", "priority", new JsonObject()));
        g.saw(true, "chooseSpellAbilityToPlay", "priority", new JsonObject(), null, new JsonObject());
        Assert.assertFalse(cycle(g, ask, conf));
        Assert.assertFalse(cycle(g, ask, conf));
        Assert.assertTrue(cycle(g, ask, conf));

        // an ask of another seat (or after the seat ended) resets it too
        g = new RlLoopGuard(2);
        Assert.assertFalse(cycle(g, ask, conf));
        Assert.assertFalse(cycle(g, ask, conf));
        g.saw(false, RlLoopGuard.CARDS_METHOD, "zoneChange", ask, null, none());
        Assert.assertFalse(cycle(g, ask, conf));

        // a different card ask (another candidate set) starts a new count
        g = new RlLoopGuard(2);
        Assert.assertFalse(cycle(g, ask, conf));
        Assert.assertFalse(cycle(g, ask, conf));
        Assert.assertFalse(cycle(g, cardAsk(7, 11, 12), conf));
        Assert.assertFalse(cycle(g, cardAsk(7, 11, 12), conf));
        Assert.assertTrue(cycle(g, cardAsk(7, 11, 12), conf));

        // a confirm of another ability, another mode or another prompt never counts
        for (JsonObject other : new JsonObject[] {confirm(8, cancel(1)), confirm(7, "Search your library?"),
                confirm(7, cancel(4))}) {
            g = new RlLoopGuard(1);
            for (int i = 0; i < 5; i++) {
                Assert.assertFalse(cycle(g, ask, other), String.valueOf(other));
            }
        }
        final JsonObject otherMode = confirm(7, cancel(1));
        otherMode.addProperty("mode", "ChangeZoneFromAltSource");
        g = new RlLoopGuard(1);
        for (int i = 0; i < 5; i++) {
            Assert.assertFalse(cycle(g, ask, otherMode));
        }

        // a card ask that is mandatory, or answered with a card, never arms the guard
        final JsonObject mandatory = cardAsk(7, 11, 12, 13);
        mandatory.addProperty("optional", false);
        Assert.assertNull(RlLoopGuard.cardsKey(RlLoopGuard.CARDS_METHOD, "zoneChange", mandatory, null));
        g = new RlLoopGuard(1);
        for (int i = 0; i < 3; i++) {
            Assert.assertFalse(g.fires(RlLoopGuard.CARDS_METHOD, "zoneChange", ask));
            final JsonObject one = new JsonObject();
            final JsonArray a = new JsonArray();
            a.add(11);
            one.add("choices", a);
            g.saw(true, RlLoopGuard.CARDS_METHOD, "zoneChange", ask, null, one);
            Assert.assertFalse(g.fires(RlLoopGuard.CONFIRM_METHOD, "confirm", conf));
            g.saw(true, RlLoopGuard.CONFIRM_METHOD, "confirm", conf, null, yesNo(false));
        }
        Assert.assertEquals(g.hits, 0);
    }
}
