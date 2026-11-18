package forge.bench.rl;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.ai.simulation.GameCopier;
import forge.ai.simulation.InfoStates;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * knowledge-same-name-1118 (Astra, review of fork #248 round 2: evidence ReviewScratchTrackerTest): the policy seat's
 * live tracker ({@link RlKnowledge}) kept a publicly returned Serra Angel known after the opponent discarded another,
 * unknown Serra Angel, because the physical card that left was the unknown one. The seat saw only "a Serra Angel" leave.
 * With {@link RlKnowledge#sameName} on, a seen exit of a card the seat did not know forgets one known card of that hand
 * with the same name; with it off, every scenario is today's. Each key-on scenario runs key off first (today's tracker,
 * which a base jar also runs), then key on.
 */
public class RlKnowledgeSameNameTest extends SimulationTest {

    static final String ANGEL = "Serra Angel";
    static final String DRAGON = KnownHandWorldsTest.DRAGON;
    static final String SAME = "an unknown card with its name left that hand";

    /** A game, its two players, and a tracker of seat 0's knowledge with a forget log. */
    final class Pos {
        final Game g;
        final Player me, opp;
        final RlKnowledge k;
        final InfoStates is;
        final List<String> forgot = new ArrayList<>();

        Pos(final boolean on) {
            g = initAndCreateGame();
            me = g.getPlayers().get(0);
            opp = g.getPlayers().get(1);
            g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
            fillLibrary(opp, 30);
            fillLibrary(me, 30);
            k = new RlKnowledge(g);
            k.v2 = true;
            if (on) {
                k.sameName = true; // the key (never touched while off: a base jar runs the key-off half)
            }
            k.log = new RlKnowledge.Log() {
                @Override
                public void learned(final int seat, final Card card, final String how) {
                }

                @Override
                public void forgot(final int seat, final int cardId, final String why) {
                    forgot.add(seat + ":" + cardId + ":" + why);
                }
            };
            k.attach();
            is = InfoStates.attach(g).trackHands();
        }

        /** A card the seat did not see enter the opponent's hand (added straight to the zone: no event). */
        Card unknown(final String name) {
            return addCardToZone(name, opp, ZoneType.Hand);
        }

        /** A card returned publicly from the opponent's battlefield to their hand: the seat knows it. */
        Card returned(final String name) {
            final Card c = g.getAction().moveToHand(addCard(name, opp), null);
            assertTrue(knows(c), name + " seen entering the hand");
            return c;
        }

        boolean knows(final Card c) {
            return k.knows(k.seatOf(me), c);
        }

        /** The forget log with card ids named by role ({@code roles}: card, name, ...), comparable across games. */
        String log(final Object... roles) {
            final List<String> out = new ArrayList<>();
            for (String x : forgot) {
                final String[] f = x.split(":", 3);
                String who = f[1];
                for (int i = 0; i + 1 < roles.length; i += 2) {
                    if (((Card) roles[i]).getId() == Integer.parseInt(f[1])) {
                        who = (String) roles[i + 1];
                    }
                }
                out.add(f[0] + ":" + who + ":" + f[2]);
            }
            return out.toString();
        }

        /** The forget log entries with the same-name reason, for card {@code id} (or any card if -1). */
        long sameNameForgets(final int id) {
            return forgot.stream().filter(x -> x.endsWith(":" + SAME) && (id < 0 || x.startsWith("0:" + id + ":"))).count();
        }
    }

    /**
     * The round-2 repro: Serra Angel B returns publicly to a hand that holds an unknown Serra Angel A, and A is
     * discarded. Key off: the tracker still knows B (today's leak). Key on: it forgets B, as InfoStates does. Kolaghan,
     * known too, stays known either way.
     */
    @Test
    public void theReproForgetsTheKnownCopyWithTheKeyOn() {
        for (boolean on : new boolean[] {false, true}) {
            final Pos p = new Pos(on);
            final Card a = p.unknown(ANGEL);
            final Card b = p.returned(ANGEL);
            final Card dragon = p.returned(DRAGON);
            p.g.getAction().moveToGraveyard(a, null); // a public discard of the unknown Angel
            assertTrue(b.isInZone(ZoneType.Hand), "B is still in the hand");
            assertFalse(p.is.knowsInHand(p.me, b), "InfoStates forgets B (it cannot tell which Angel left)");
            System.err.println("[same-name] repro key " + (on ? "on" : "off") + ": RlKnowledge knows B=" + p.knows(b)
                    + " InfoStates=" + p.is.knowsInHand(p.me, b) + " forgot=" + p.forgot);
            if (on) {
                assertFalse(p.knows(b), "key on: an Angel left unseen-which, so B is not known");
                assertEquals(p.sameNameForgets(b.getId()), 1, "forgotten for its name: " + p.forgot);
            } else {
                assertTrue(p.knows(b), "key off: today's tracker keeps B (the leak)");
                assertEquals(p.sameNameForgets(-1), 0);
            }
            assertTrue(p.knows(dragon), "Kolaghan stays known");
        }
    }

    /**
     * Both copies known. (1) Both entered publicly and one of them leaves: the other stays known, key on or off. (2) With
     * an unknown copy also in the hand, the unknown one leaves: key on, exactly one known copy is forgotten (the seat knew
     * two Angels and saw one leave, so it still knows one); key off, both stay known (today).
     */
    @Test
    public void knowingTwoAndSeeingOneLeaveStillKnowsOne() {
        for (boolean on : new boolean[] {false, true}) {
            final Pos p = new Pos(on);
            final Card b = p.returned(ANGEL);
            final Card c = p.returned(ANGEL);
            p.g.getAction().moveToGraveyard(b, null); // the known B itself leaves
            assertFalse(p.knows(b));
            assertTrue(p.knows(c), "key " + on + ": the other known Angel stays known");
            assertEquals(p.sameNameForgets(-1), 0, "a known card's exit forgets only that card");

            final Pos q = new Pos(on);
            final Card a = q.unknown(ANGEL);
            final Card b2 = q.returned(ANGEL);
            final Card c2 = q.returned(ANGEL);
            q.g.getAction().moveToGraveyard(a, null); // the unknown A leaves
            final int still = (q.knows(b2) ? 1 : 0) + (q.knows(c2) ? 1 : 0);
            System.err.println("[same-name] two known, unknown leaves, key " + (on ? "on" : "off") + ": still known " + still);
            if (on) {
                assertEquals(still, 1, "key on: knew two Angels, saw one leave: one stays known");
                assertFalse(q.knows(b2.getId() < c2.getId() ? b2 : c2), "the lowest id is the one forgotten");
                assertEquals(q.sameNameForgets(-1), 1);
            } else {
                assertEquals(still, 2, "key off: today's tracker keeps both");
            }
        }
    }

    /**
     * The known copy itself leaves: it is forgotten, as today, and nothing else is (the unknown copy stays unknown, the
     * known Kolaghan stays known). A later exit of the remaining unknown Angel finds no known Angel to forget.
     */
    @Test
    public void theKnownCopyLeavingIsForgottenAsToday() {
        final List<String> logs = new ArrayList<>();
        for (boolean on : new boolean[] {false, true}) {
            final Pos p = new Pos(on);
            final Card a = p.unknown(ANGEL);
            final Card b = p.returned(ANGEL);
            final Card dragon = p.returned(DRAGON);
            p.g.getAction().moveToGraveyard(b, null);
            assertFalse(p.knows(b), "the known Angel that left is forgotten");
            assertFalse(p.knows(a), "the unknown Angel stays unknown");
            assertTrue(p.knows(dragon), "Kolaghan stays known");
            p.g.getAction().moveToStack(a, null); // then the unknown Angel is cast
            assertTrue(p.knows(dragon), "Kolaghan stays known");
            assertEquals(p.k.knownCount(0), 1, "only Kolaghan is known");
            assertEquals(p.sameNameForgets(-1), 0, "no known Angel was left to forget for its name");
            logs.add(p.log(a, "A", b, "B", dragon, "dragon"));
        }
        assertEquals(logs.get(1), logs.get(0), "key on and off forget the same cards for the same reasons");
    }

    /**
     * Seen exits that settle later (a zone where Forge may still turn the card face down): cast (stack), played
     * (battlefield) and exiled face up (a pitch cost). Key on: B is forgotten once the move settles as seen (the next
     * query). An Angel C that returns publicly after the exit but before the settle was not in the hand when the Angel
     * left, so it stays known. Key off: B stays known (today).
     */
    @Test
    public void exitsSettledLaterCountTheCardsKnownBeforeTheMove() {
        for (String how : new String[] {"cast", "play", "exile"}) {
            for (boolean on : new boolean[] {false, true}) {
                final Pos p = new Pos(on);
                final Card a = p.unknown(ANGEL);
                final Card b = p.returned(ANGEL);
                switch (how) {
                    case "cast": p.g.getAction().moveToStack(a, null); break;
                    case "play": p.g.getAction().moveToPlay(a, null, null); break;
                    default: p.g.getAction().exile(a, null, null); break;
                }
                // no query before C enters: the exit is still pending
                final Card c = p.g.getAction().moveToHand(addCard(ANGEL, p.opp), null);
                final boolean kb = p.knows(b), kc = p.knows(c);
                System.err.println("[same-name] " + how + " key " + (on ? "on" : "off") + ": B=" + kb + " C=" + kc);
                assertTrue(kc, how + ": C entered after the exit and stays known");
                assertEquals(kb, !on, how + ": B known only with the key off");
                assertEquals(p.sameNameForgets(b.getId()), on ? 1 : 0, how + ": " + p.forgot);
            }
        }
    }

    /**
     * An exit the seat does not see (to the library) is the F1 blur, key on as off: every card of that hand the seat knew
     * is forgotten, for that reason, and none for a name.
     */
    @Test
    public void anUnseenExitIsStillTheBlur() {
        final List<String> logs = new ArrayList<>();
        for (boolean on : new boolean[] {false, true}) {
            final Pos p = new Pos(on);
            final Card a = p.unknown(ANGEL);
            final Card b = p.returned(ANGEL);
            final Card dragon = p.returned(DRAGON);
            p.g.getAction().moveToLibrary(a, 0, null); // tucked from the hand: nobody saw which card
            assertFalse(p.knows(b));
            assertFalse(p.knows(dragon), "the blur forgets every known card of that hand");
            assertEquals(p.sameNameForgets(-1), 0, "no name-based forgetting: " + p.forgot);
            assertTrue(p.forgot.stream().allMatch(x -> x.endsWith("an unseen card left that hand")), p.forgot.toString());
            logs.add(p.log(a, "A", b, "B", dragon, "dragon"));
        }
        assertEquals(logs.get(1), logs.get(0), "key on and off forget the same cards for the same reasons");
    }

    /**
     * Exits whose name is not public: to the library and exiled face down (Forge exiles it face up, then turns it face
     * down). Key on, the tracker forgets exactly what it forgets key off (the blur, at once or at the settle), never a card
     * for its name; an Angel C learned after the face-down exile, before the settle, stays known in both.
     */
    @Test
    public void exitsThatAreNotPublicForgetNothingMoreThanToday() {
        for (String how : new String[] {"library", "faceDownExile"}) {
            final List<String> states = new ArrayList<>();
            for (boolean on : new boolean[] {false, true}) {
                final Pos p = new Pos(on);
                final Card a = p.unknown(ANGEL);
                final Card b = p.returned(ANGEL);
                final Card dragon = p.returned(DRAGON);
                Card c = null;
                if ("library".equals(how)) {
                    p.g.getAction().moveToLibrary(a, -1, null);
                } else {
                    final Card ex = p.g.getAction().exile(a, null, null);
                    ex.turnFaceDown(true);
                    c = p.g.getAction().moveToHand(addCard(ANGEL, p.opp), null);
                }
                final String state = how + " B=" + p.knows(b) + " dragon=" + p.knows(dragon)
                        + (c == null ? "" : " C=" + p.knows(c)) + " known=" + p.k.knownCount(0);
                System.err.println("[same-name] not public, key " + (on ? "on" : "off") + ": " + state);
                assertEquals(p.sameNameForgets(-1), 0, how + ": no name-based forgetting: " + p.forgot);
                assertFalse(p.knows(b), how + ": the blur forgets B");
                assertFalse(p.knows(dragon), how + ": and Kolaghan");
                if (c != null) {
                    assertTrue(p.knows(c), how + ": C entered after the exit and stays known");
                }
                states.add(state);
            }
            assertEquals(states.get(1), states.get(0), how + ": key on knows exactly what key off knows");
        }
    }

    /** No card of the snapshot JSON tree has a key named {@code key}. */
    static boolean hasKey(final JsonElement e, final String key) {
        if (e.isJsonObject()) {
            for (java.util.Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
                if (x.getKey().equals(key) || hasKey(x.getValue(), key)) {
                    return true;
                }
            }
        } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (hasKey(x, key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A forked tracker ({@link RlKnowledge#forkFor}) and a restored one ({@link RlKnowledge#snapshot} /
     * {@link RlKnowledge#restore}) carry the key and a pending same-name exit: an unknown Angel is played (pending), the
     * tracker is forked and snapshotted before the move settles, and each copy then forgets B as the live tracker does.
     * Key off: no new JSON key anywhere, and every copy keeps B (today).
     */
    @Test
    public void aForkedOrRestoredTrackerCarriesTheKeyAndItsPendingExit() {
        for (boolean on : new boolean[] {false, true}) {
            final Pos p = new Pos(on);
            final Card a = p.unknown(ANGEL);
            final Card b = p.returned(ANGEL);
            p.g.getAction().moveToPlay(a, null, null); // seen once it settles; nothing has queried yet
            final GameCopier copier = new GameCopier(p.g, true);
            final Game copy = copier.makeCopy();
            final RlKnowledge fork = p.k.forkFor(copy);
            final JsonObject snap = p.k.snapshot(id -> Integer.toString(id));
            final RlKnowledge restored = RlKnowledge.restore(p.g, snap, Integer::parseInt);
            System.err.println("[same-name] snapshot key " + (on ? "on" : "off") + ": " + snap);
            final Card bCopy = copy.findById(b.getId());
            assertNotNull(bCopy);
            assertTrue(bCopy.isInZone(ZoneType.Hand));
            final boolean kf = fork.knows(fork.seatOf((Player) copier.find(p.me)), bCopy);
            final boolean kr = restored.knows(restored.seatOf(p.me), b);
            final boolean kl = p.knows(b);
            assertEquals(kf, !on, "the fork");
            assertEquals(kr, !on, "the restored tracker");
            assertEquals(kl, !on, "the live tracker");
            if (on) {
                assertTrue(snap.get("same_name").getAsBoolean(), "the snapshot carries the key");
                assertEquals(snap.getAsJsonArray("pending").get(0).getAsJsonObject().get("exit_name").getAsString(), ANGEL,
                        "and the pending exit's name");
                assertTrue(RlKnowledge.restore(p.g, restored.snapshot(id -> Integer.toString(id)), Integer::parseInt)
                        .snapshot(id -> Integer.toString(id)).get("same_name").getAsBoolean(), "a restored tracker snapshots it again");
            } else {
                assertFalse(hasKey(snap, "same_name"), "key off: no same_name in the snapshot");
                assertFalse(hasKey(snap, "exit_name"), "key off: no exit_name in the snapshot");
            }
        }
    }
}
