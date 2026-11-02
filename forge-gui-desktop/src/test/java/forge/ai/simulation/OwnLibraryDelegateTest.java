package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * order-memory-1102 (interim, an R3 blocker; LEAKS.md): with the R3 spec's {@code knownLib} on, {@link OwnLibraryMemory}
 * (the R3 rows, its worlds, choose-a-card) delegates to {@link KnownOwnLibrary}. The legacy memory knows every card that
 * enters our library, at its true index, whoever ordered it: a cascade's random bottom, a hideaway or dig's random rest,
 * Endurance, and the opponent's Portent of our top. Each case is run on both memories side by side: the legacy one shows
 * the leak (so the case is not vacuous), the delegating one does not. Controls: Brainstorm, Doomsday and a tutor's top
 * stay known; a copy's memory seeded from the root keeps its pins until a shuffle. The key is off by default and is in
 * the spec's identity only when on.
 */
public class OwnLibraryDelegateTest extends forge.ai.AITest {

    static Player me(final Game g) {
        return KnownLibTest.me(g);
    }

    /** The legacy and the delegating memory, both watching {@code g} from now. */
    static OwnLibraryMemory[] both(final Game g) {
        final OwnLibraryMemory legacy = new OwnLibraryMemory(me(g)), kl = new OwnLibraryMemory(me(g), true);
        legacy.watch(g);
        kl.watch(g);
        Assert.assertFalse(legacy.delegating());
        Assert.assertTrue(kl.delegating());
        return new OwnLibraryMemory[] {legacy, kl};
    }

    static List<String> names(final Game g, final Set<Integer> ids) {
        return KnownLibTest.names(g, ids);
    }

    static void log(final String tag, final Game g, final OwnLibraryMemory[] m) {
        System.out.println("OLMD " + tag + " library=" + KnownLibTest.top(g, 20) + " legacy=" + names(g, m[0].known(me(g)))
                + " knownLib=" + names(g, m[1].known(me(g))));
    }

    // ------------------------------------------------------------------ the spec key

    @Test
    public void theKeyIsOffByDefaultAndInTheIdentityOnlyWhenOn() {
        final R3LabelSearch.Spec off = R3LabelSearch.Spec.parse(new JsonObject());
        Assert.assertFalse(off.knownLib);
        Assert.assertFalse(off.identity().has("knownLib"));
        Assert.assertFalse(off.cacConfig("shadow").knownLib);
        final JsonObject f = new JsonObject();
        f.addProperty("knownLib", false);
        Assert.assertEquals(R3LabelSearch.Spec.parse(f).sha(), off.sha(), "explicitly false: the same sha");
        final JsonObject o = new JsonObject();
        o.addProperty("knownLib", true);
        final R3LabelSearch.Spec on = R3LabelSearch.Spec.parse(o);
        Assert.assertTrue(on.knownLib);
        Assert.assertTrue(on.identity().get("knownLib").getAsBoolean());
        Assert.assertNotEquals(on.sha(), off.sha());
        Assert.assertTrue(on.cacConfig("on").knownLib, "choose-a-card's memory follows the key");
    }

    @Test
    public void chooseACardBindsTheMemoryTheKeyNames() {
        for (boolean kl : new boolean[] {false, true}) {
            final Game g = KnownLibTest.game("", "Island", "Island;Swamp;Island", 20);
            final ChooseACard.Config cfg = new ChooseACard.Config();
            cfg.knownLib = kl;
            final ChooseACard c = new ChooseACard(cfg, StaticValuer.INSTANCE, null);
            c.bind(g, me(g));
            Assert.assertEquals(c.memory().delegating(), kl);
        }
    }

    // ------------------------------------------------------------------ the leaks (LEAKS.md): unknown with the key on

    /** Cascade: the exiled cards go to the bottom in a random order. */
    @Test
    public void aCascadeBottomIsNotKnown() {
        final Game g = KnownLibTest.game("Shardless Agent", "Tropical Island;Island;Forest",
                "Island;Swamp;Mountain;Lightning Bolt;Island;Swamp;Island;Swamp;Island;Swamp", 20);
        me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), null, false));
        final OwnLibraryMemory[] m = both(g);
        KnownLibTest.playAndResolve(g, me(g), ChooseACardTest.find(me(g), ZoneType.Hand, "Shardless Agent").getFirstSpellAbility(), null);
        log("cascade", g, m);
        Assert.assertFalse(m[0].known(me(g)).isEmpty(), "the legacy memory reads the random bottom (the leak)");
        Assert.assertTrue(m[1].known(me(g)).isEmpty(), names(g, m[1].known(me(g))).toString());
    }

    /** Endurance on our graveyard (ours or theirs): the bottom in a random order. */
    @Test
    public void anEnduranceBottomIsNotKnown() {
        for (boolean theirs : new boolean[] {true, false}) {
            final String frame = ForcedWinSolverTest.frame(theirs ? "" : "Endurance", theirs ? "Island" : "Forest;Forest;Forest",
                    20, theirs ? "Endurance" : "", theirs ? "Forest;Forest;Forest" : "", 20, "human")
                    .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Swamp;Island;Swamp;Island")
                    .replace("humangraveyard=", "humangraveyard=Lightning Bolt;Shock;Giant Growth");
            final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
            final OwnLibraryMemory[] m = both(g);
            final Player caster = theirs ? KnownLibTest.opp(g) : me(g);
            KnownLibTest.playAndResolve(g, caster, ChooseACardTest.find(caster, ZoneType.Hand, "Endurance").getFirstSpellAbility(), sa -> {
                if (sa != null && sa.isTrigger() && sa.usesTargeting()) {
                    sa.resetTargets();
                    sa.getTargets().add(me(g));
                }
            });
            log("endurance theirs=" + theirs, g, m);
            Assert.assertTrue(me(g).getCardsIn(ZoneType.Graveyard).isEmpty(), "our graveyard went to the bottom");
            Assert.assertEquals(m[0].known(me(g)).size(), 3, "the legacy memory reads the random order (the leak)");
            Assert.assertTrue(m[1].known(me(g)).isEmpty(), "theirs=" + theirs + ": " + names(g, m[1].known(me(g))));
        }
    }

    /** A dig whose rest goes to the bottom in a random order (Abundant Harvest's DigUntil RevealRandomOrder). */
    @Test
    public void aRandomRestIsNotKnown() {
        final Game g = KnownLibTest.game("Abundant Harvest", "Forest", "Island;Swamp;Mountain;Lightning Bolt;Island;Swamp;Island;Swamp", 20);
        final KnownLibTest.Ordered c = new KnownLibTest.Ordered(g, me(g), null, false);
        c.pick = "Nonland";
        me(g).dangerouslySetController(c);
        final OwnLibraryMemory[] m = both(g);
        KnownLibTest.castAndResolve(g, "Abundant Harvest");
        log("random-rest", g, m);
        Assert.assertFalse(m[0].known(me(g)).isEmpty(), "the legacy memory reads the random rest (the leak)");
        Assert.assertTrue(m[1].known(me(g)).isEmpty(), names(g, m[1].known(me(g))).toString());
    }

    /** Their Portent reorders our top three, put-backs included: the order is theirs, so nothing there is known. */
    @Test
    public void theirReorderOfOurTopIsNotKnown() {
        final String frame = ForcedWinSolverTest.frame("Lightning Bolt;Shock", "Island", 20, "Portent", "Island", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Swamp;Island;Swamp;Island;Swamp");
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
        final OwnLibraryMemory[] m = both(g);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Lightning Bolt"), 0, null);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Shock"), 0, null);
        Assert.assertEquals(m[1].known(me(g)).size(), 2, "our two put-backs, before their Portent");
        final SpellAbility portent = ChooseACardTest.find(KnownLibTest.opp(g), ZoneType.Hand, "Portent").getFirstSpellAbility();
        portent.setActivatingPlayer(KnownLibTest.opp(g));
        portent.resetTargets();
        portent.getTargets().add(me(g));
        KnownLibTest.playAndResolve(g, KnownLibTest.opp(g), portent, null);
        log("portent", g, m);
        Assert.assertTrue(m[0].known(me(g)).size() >= 3, "the legacy memory reads their order (the leak)");
        Assert.assertTrue(m[1].known(me(g)).isEmpty(), names(g, m[1].known(me(g))).toString());
    }

    // ------------------------------------------------------------------ controls: still known with the key on

    @Test
    public void ourBrainstormPutBackIsStillKnown() {
        final Game g = KnownLibTest.game("Brainstorm;Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island;Swamp", 20);
        me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), null, false));
        final OwnLibraryMemory[] m = both(g);
        KnownLibTest.playAndResolve(g, me(g), ChooseACardTest.find(me(g), ZoneType.Hand, "Brainstorm").getFirstSpellAbility(), null);
        log("brainstorm", g, m);
        final List<Card> lib = new ArrayList<>(me(g).getCardsIn(ZoneType.Library));
        Assert.assertEquals(m[1].known(me(g)), Set.of(lib.get(0).getId(), lib.get(1).getId()), "the two put-backs on top");
        me(g).drawCards(1);
        Assert.assertEquals(m[1].known(me(g)), Set.of(me(g).getCardsIn(ZoneType.Library).get(0).getId()), "next turn: one left");
    }

    @Test
    public void aDoomsdayPileIsStillKnown() {
        final Game g = KnownLibTest.game("Doomsday", "Swamp;Swamp;Swamp", "Island;Thassa's Oracle;Island", 20);
        me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), List.of("Thassa's Oracle", "Island"), false));
        final OwnLibraryMemory[] m = both(g);
        KnownLibTest.castAndResolve(g, "Doomsday");
        log("doomsday", g, m);
        Assert.assertEquals(m[1].known(me(g)).size(), 3, "the whole pile");
        me(g).shuffle(null);
        Assert.assertTrue(m[1].known(me(g)).isEmpty(), "a shuffle forgets it");
    }

    @Test
    public void aTutorTopIsStillKnown() {
        final Game g = KnownLibTest.game("Vampiric Tutor", "Swamp", "Island;Swamp;Island;Lightning Bolt;Island;Swamp", 20);
        me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), null, false));
        final OwnLibraryMemory[] m = both(g);
        KnownLibTest.castAndResolve(g, "Vampiric Tutor");
        log("tutor", g, m);
        Assert.assertEquals(m[1].known(me(g)), Set.of(me(g).getCardsIn(ZoneType.Library).get(0).getId()), "the tutored top");
    }

    /** A copy's memory (an R3 world) seeded with the root's known cards keeps them through draws; a shuffle forgets. */
    @Test
    public void aSeededCopyKeepsItsPinsUntilAShuffle() {
        final Game g = KnownLibTest.game("", "Island", "Island;Swamp;Lightning Bolt;Swamp;Island;Swamp", 20);
        final List<Card> lib = new ArrayList<>(me(g).getCardsIn(ZoneType.Library));
        final OwnLibraryMemory kl = new OwnLibraryMemory(me(g), true);
        kl.seedKnown(List.of(lib.get(0), lib.get(2)));
        kl.watch(g);
        Assert.assertEquals(kl.known(me(g)), Set.of(lib.get(0).getId(), lib.get(2).getId()), "non-contiguous pins");
        me(g).drawCards(1);
        Assert.assertEquals(kl.known(me(g)), Set.of(lib.get(2).getId()), "a draw takes the first; the second stays pinned");
        me(g).shuffle(null);
        Assert.assertTrue(kl.known(me(g)).isEmpty());
        // legacy seeding is noteLooked, unchanged
        final OwnLibraryMemory legacy = new OwnLibraryMemory(me(g));
        final Card top = me(g).getCardsIn(ZoneType.Library).get(0);
        legacy.seedKnown(List.of(top));
        Assert.assertEquals(legacy.known(me(g)), Set.of(top.getId()));
        Assert.assertTrue(((PlayerControllerAi) me(g).getController()) != null);
    }
    // ------------------------------------------------------------------ review #168 S2 / S3

    /**
     * S2: a choose-a-card query on a copy whose library order differs from the live one (an applied answer, a shuffled
     * world) must not wipe the live tracker: the Brainstorm put-backs are still known at the live seat's next row.
     */
    @Test
    public void aCopyQueryDoesNotWipeTheLiveTracker() {
        final Game g = KnownLibTest.game("Brainstorm;Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island;Swamp", 20);
        me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), null, false));
        final OwnLibraryMemory kl = new OwnLibraryMemory(me(g), true);
        kl.watch(g);
        KnownLibTest.playAndResolve(g, me(g), ChooseACardTest.find(me(g), ZoneType.Hand, "Brainstorm").getFirstSpellAbility(), null);
        final Set<Integer> live = kl.known(me(g));
        Assert.assertEquals(live.size(), 2, "the two put-backs");
        final GameCopier copier = new GameCopier(g, true);
        final Game copy = copier.makeCopy();
        final Player cm = (Player) copier.find(me(g));
        final List<Card> order = new ArrayList<>(cm.getCardsIn(ZoneType.Library));
        java.util.Collections.reverse(order);
        cm.getZone(ZoneType.Library).setCards(order); // the copy's order differs from the live one
        Assert.assertEquals(kl.known(cm), live, "the copy query reads the live known ids (they survive the copy)");
        Assert.assertEquals(kl.known(me(g)), live, "and the live tracker still knows them at the next row");
        Assert.assertNotNull(copy);
    }

    /** S3: an R3 world's stratify reorders the watched copy's unknown slots (setCards): the pins survive a resync. */
    @Test
    public void pinsSurviveAPinnedReorderAfterAResync() {
        final Game g = KnownLibTest.game("", "Island", "Island;Swamp;Lightning Bolt;Swamp;Island;Swamp;Island;Swamp", 20);
        final List<Card> lib = new ArrayList<>(me(g).getCardsIn(ZoneType.Library));
        final OwnLibraryMemory kl = new OwnLibraryMemory(me(g), true);
        kl.seedKnown(List.of(lib.get(0), lib.get(2)));
        kl.watch(g);
        final Set<Integer> pins = Set.of(lib.get(0).getId(), lib.get(2).getId());
        R3LabelSearch.stratify(me(g), kl.known(me(g)), 1, 4, new java.util.Random(7));
        kl.resync();
        Assert.assertEquals(kl.known(me(g)), pins, "the pins stay at their indices and stay known");
        Assert.assertEquals(me(g).getCardsIn(ZoneType.Library).get(2).getId(), lib.get(2).getId());
        // without the resync, a silent reorder forgets (fail closed)
        final List<Card> before = new ArrayList<>(me(g).getCardsIn(ZoneType.Library));
        OwnLibraryMemory.shuffleUnknown(me(g), pins, new java.util.Random(9));
        if (!new ArrayList<>(me(g).getCardsIn(ZoneType.Library)).equals(before)) {
            Assert.assertTrue(kl.known(me(g)).isEmpty(), "an unexplained reorder still forgets");
        }
    }
    // ------------------------------------------------------------------ ruling 2026-11-02 (FLAG-R3-3)

    /** A set that fails on any read: a world that reads it throws. */
    static final class Poisoned extends java.util.AbstractSet<Integer> {
        @Override
        public java.util.Iterator<Integer> iterator() {
            throw new IllegalStateException("read the opponent's memory");
        }

        @Override
        public int size() {
            throw new IllegalStateException("read the opponent's memory");
        }

        @Override
        public boolean contains(final Object o) {
            throw new IllegalStateException("read the opponent's memory");
        }
    }

    /**
     * The belief (selection) worlds never read the opponent's own known positions; only the T (truth) target worlds do
     * (FLAG-R3-3: their known positions are real facts of the target's truth). A later change that moved the opponent's
     * memory into selection fails here.
     */
    @Test
    public void beliefWorldsNeverReadTheOpponentsMemory() {
        final Game g = KnownLibTest.game("", "Island", "Island;Swamp;Island;Swamp;Island;Swamp", 20);
        final R3LabelSearch r3 = new R3LabelSearch(R3LabelSearchTest.spec(R3LabelSearchTest.base()),
                R3LabelSearchTest.env(StaticValuer.INSTANCE, null));
        for (boolean truth : new boolean[] {false, true}) {
            final GameCopier copier = new GameCopier(g, true);
            final Game copy = copier.makeCopy();
            final Player cm = (Player) copier.find(me(g));
            final R3LabelSearch.World w = r3.new World(truth, 7L, -1, 0, null, null, new java.util.HashSet<>(), new Poisoned());
            boolean read = false;
            try {
                w.make(g, me(g), copy, cm);
            } catch (IllegalStateException e) {
                read = String.valueOf(e.getMessage()).contains("opponent's memory");
            }
            Assert.assertEquals(read, truth, truth ? "the T world pins the opponent's own positions (the test reaches it)"
                    : "a belief world read the opponent's memory");
        }
    }
}
