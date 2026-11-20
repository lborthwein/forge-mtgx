package forge.bench;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.simulation.SimulationTest;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.trigger.Trigger;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

/**
 * target-picks-1119, review 1 of b8b01a0ed3e: the pick guard ({@code ownHarmPicks}) fails open (S1 1), forced and
 * retargeted target sets are legal as Forge's own targeting, MustTarget included (S1 2), and divided-amount abilities are
 * never forced or retargeted (S1 3). The reviewer's repros (review/r1/ReviewScratchTargetPicksTest.java), on the live
 * controller's guard directly: seat 0 is a bridged seat whose local answerer says yes to every ask (a targets ask gets
 * no choices: Forge AI answers it), seat 1 a null seat.
 */
public class TargetPicksGuardTest extends SimulationTest {
    private Game game() {
        final BenchSession session = new BenchSession(new JsonRpcChannel(InputStream.nullInputStream(), OutputStream.nullOutputStream()));
        session.setLocalAnswerer((g, p, k, b) -> {
            final JsonObject a = new JsonObject();
            a.addProperty("yes", true);
            return a;
        });
        final List<RegisteredPlayer> ps = Arrays.asList(
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerBridge("Seat0", null, session, BenchSession.Mode.BRIDGE, 0)),
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerBridge("Seat1", null, session, BenchSession.Mode.NULL, 1)));
        final GameRules rules = new GameRules(GameType.Constructed);
        final Game g = new Game(ps, rules, new Match(rules, ps, "target-picks-guard"));
        g.setAge(GameStage.Play);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, g.getPlayers().get(0), 10);
        session.setLiveGame(g);
        return g;
    }

    private static PlayerControllerBridge pc(final Player p) {
        return (PlayerControllerBridge) p.getController();
    }

    private static SpellAbility spell(final Card c, final Player p) {
        final SpellAbility s = c.getFirstSpellAbility();
        s.setActivatingPlayer(p);
        return s;
    }

    private static SpellAbility script(final Card c, final String key, final Player p) {
        final SpellAbility s = AbilityFactory.getAbility(c.getSVar(key), c);
        s.setActivatingPlayer(p);
        return s;
    }

    /** The part's legal targets with nothing chosen (its choice kept). */
    private static List<GameEntity> legal(final SpellAbility s) {
        final forge.game.spellability.TargetChoices before = s.getTargets();
        s.resetTargets();
        final List<GameEntity> l = s.getTargetRestrictions().getAllCandidates(s);
        s.setTargets(before);
        return l;
    }

    /** The guard (key on) on a cast's pick; its action. */
    private static String guard(final Player p, final SpellAbility s) {
        pc(p).ownHarmPicks = true;
        final String g = pc(p).guardPick(s, s, "cast", legal(s));
        System.out.println("[target-picks-guard] " + s.getHostCard().getName() + " => " + g + " " + s.getTargets());
        return g;
    }

    private static boolean force(final Player p, final SpellAbility s, final String ref) {
        pc(p).armCastTarget(s, ref, null);
        s.clearTargets();
        s.setTargetingPlayer(p);
        return pc(p).forcedCastTarget(s, s);
    }

    // ------------------------------------------------------------------ S1 1: the guard fails open

    /** Reclamation Sage at our Banishing Light, which holds our own Grizzly Bears: the seat's yes stands, key off and on. */
    @Test
    public void sageAtOurBanishingLightHoldingOurCreatureIsKept() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        final Card bear = addCard("Grizzly Bears", p);
        bear.setController(q, g.getNextTimestamp());
        final Card light = addCard("Banishing Light", p);
        final SpellAbility exile = script(light, "TrigExile", p);
        exile.getTargets().add(bear);
        AbilityUtils.resolve(exile);
        Assert.assertTrue(bear.isInZone(ZoneType.Exile));
        final Card sage = addCard("Reclamation Sage", p);
        final Trigger tr = sage.getTriggers().get(0);
        final SpellAbility sa = tr.ensureAbility().copy(p);
        sa.setOptionalTrigger(true);
        sa.getTargets().add(light);
        final WrappedAbility w = new WrappedAbility(tr, sa, p);
        w.setActivatingPlayer(p);
        Assert.assertTrue(pc(p).confirmTrigger(w), "key off: the seat's yes");
        pc(p).ownHarmPicks = true;
        Assert.assertTrue(pc(p).confirmTrigger(w), "key on: kept (Banishing Light's exile ends when it leaves)");
        Assert.assertEquals(pc(p).guardPick(w, sa, "trigger", legal(sa)), "kept-unsure");
        Assert.assertTrue(sa.getTargets().contains(light));
    }

    /** Tear Asunder at our Out of Time (our Bears phased out until it leaves), their Mind Stone legal: kept. */
    @Test
    public void tearAsunderAtOurOutOfTimeIsKept() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        final Card bear = addCard("Grizzly Bears", p), out = addCard("Out of Time", p);
        AbilityUtils.resolve(script(out, "TrigUntap", p));
        Assert.assertTrue(bear.isPhasedOut());
        addCard("Mind Stone", q);
        final SpellAbility s = spell(addCardToZone("Tear Asunder", p, ZoneType.Hand), p);
        s.getTargets().add(out);
        Assert.assertEquals(guard(p, s), "kept-unsure");
        Assert.assertTrue(s.getTargets().contains(out), "the phase-in payoff kept");
    }

    /** Garruk Relentless at 3 loyalty, his 0 at our Grizzly Bears (it deals 2 back: he transforms), their Birds legal: kept. */
    @Test
    public void garrukAtOurBearsIsKept() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        final Card gar = addCard("Garruk Relentless", p);
        gar.setCounters(CounterEnumType.LOYALTY, 3);
        final Card bear = addCard("Grizzly Bears", p);
        addCard("Birds of Paradise", q);
        final SpellAbility s = gar.getSpellAbilities().stream().filter(a -> a.getApi() == ApiType.DealDamage).findFirst().orElseThrow();
        s.setActivatingPlayer(p);
        s.getTargets().add(bear);
        Assert.assertEquals(guard(p, s), "kept-unsure");
        Assert.assertTrue(s.getTargets().contains(bear));
    }

    /** Doom Blade at our Bears, which Adarkar Valkyrie's delayed trigger returns when it dies: kept. */
    @Test
    public void doomBladeAtOurValkyrieMarkedCreatureIsKept() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        final Card valk = addCard("Adarkar Valkyrie", p), bear = addCard("Grizzly Bears", p);
        final SpellAbility delay = valk.getSpellAbilities().stream().filter(a -> a.getApi() == ApiType.DelayedTrigger).findFirst().orElseThrow();
        delay.setActivatingPlayer(p);
        delay.getTargets().add(bear);
        AbilityUtils.resolve(delay);
        addCard("Runeclaw Bear", q);
        final SpellAbility s = spell(addCardToZone("Doom Blade", p, ZoneType.Hand), p);
        s.getTargets().add(bear);
        Assert.assertEquals(guard(p, s), "kept-unsure");
        Assert.assertTrue(s.getTargets().contains(bear));
    }

    /** Disfigure at our 2/2 Bears with Shirei, Shizo's Caretaker (it returns a dying creature of power 1 or less): kept. */
    @Test
    public void disfigureAtOurBearsWithShireiIsKept() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        addCard("Shirei, Shizo's Caretaker", p);
        final Card bear = addCard("Grizzly Bears", p);
        addCard("Runeclaw Bear", q);
        final SpellAbility s = spell(addCardToZone("Disfigure", p, ZoneType.Hand), p);
        s.getTargets().add(bear);
        Assert.assertEquals(guard(p, s), "kept-unsure");
        Assert.assertTrue(s.getTargets().contains(bear));
    }

    /** Control: Doom Blade at our Bears with no payoff, their Runeclaw Bear legal: still retargeted. */
    @Test
    public void doomBladeAtOurBearsWithoutPayoffIsStillRetargeted() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        final Card bear = addCard("Grizzly Bears", p);
        final Card theirs = addCard("Runeclaw Bear", q);
        final SpellAbility s = spell(addCardToZone("Doom Blade", p, ZoneType.Hand), p);
        s.getTargets().add(bear);
        Assert.assertEquals(guard(p, s), "retarget");
        Assert.assertEquals(new ArrayList<>(s.getTargets()), List.of(theirs));
    }

    /** Control: Reclamation Sage at our only artifact (Sol Ring), nothing of theirs: still declined. */
    @Test
    public void sageAtOurOnlySolRingIsStillDeclined() {
        final Game g = game();
        final Player p = g.getPlayers().get(0);
        final Card ring = addCard("Sol Ring", p);
        final Card sage = addCard("Reclamation Sage", p);
        final Trigger tr = sage.getTriggers().get(0);
        final SpellAbility sa = tr.ensureAbility().copy(p);
        sa.setOptionalTrigger(true);
        sa.getTargets().add(ring);
        final WrappedAbility w = new WrappedAbility(tr, sa, p);
        w.setActivatingPlayer(p);
        pc(p).ownHarmPicks = true;
        Assert.assertEquals(pc(p).guardPick(w, sa, "trigger", legal(sa)), "decline");
        Assert.assertFalse(pc(p).confirmTrigger(w), "declined at resolution");
    }

    /** A search variant's own pick (Doom Blade at our Bears, theirs legal) is never overridden: kept-variant. */
    @Test
    public void aVariantsPickIsNeverOverridden() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        final Card bear = addCard("Grizzly Bears", p);
        addCard("Runeclaw Bear", q);
        final SpellAbility s = spell(addCardToZone("Doom Blade", p, ZoneType.Hand), p);
        s.getTargets().add(bear);
        pc(p).ownHarmPicks = true;
        pc(p).targetLog = new TargetPickLog();
        pc(p).picked(s, s, "cast", "variant", legal(s));
        Assert.assertEquals(new ArrayList<>(s.getTargets()), List.of(bear));
        final JsonArray rec = pc(p).targetLog.drain();
        Assert.assertNotNull(rec);
        Assert.assertEquals(rec.get(0).getAsJsonObject().get("g").getAsString(), "kept-variant", rec.toString());
        Assert.assertEquals(rec.get(0).getAsJsonObject().get("by").getAsString(), "variant", rec.toString());
    }

    // ------------------------------------------------------------------ S1 2: MustTarget

    /** Their Standard Bearer: Lightning Bolt's P1 (face) variant is not forced (MustTarget); the targets are cleared. */
    @Test
    public void boltFaceVariantPastStandardBearerIsNotForced() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        addCard("Standard Bearer", q);
        final SpellAbility s = spell(addCardToZone("Lightning Bolt", p, ZoneType.Hand), p);
        Assert.assertFalse(force(p, s, "P1"), "not forced");
        Assert.assertTrue(s.getTargets().isEmpty(), "cleared for the seat's normal choice: " + s.getTargets());
    }

    /** Control: with no Flagbearer, the same face variant is forced. */
    @Test
    public void boltFaceVariantIsForcedWithoutAFlagbearer() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        addCard("Grizzly Bears", q);
        final SpellAbility s = spell(addCardToZone("Lightning Bolt", p, ZoneType.Hand), p);
        Assert.assertTrue(force(p, s, "P1"));
        Assert.assertEquals(new ArrayList<>(s.getTargets()), List.of(q));
    }

    /** The guard's retarget of a Bolt at our Bears, with their Standard Bearer and Runeclaw Bear, meets MustTarget. */
    @Test
    public void aRetargetMeetsMustTarget() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        final Card bear = addCard("Grizzly Bears", p);
        final Card flag = addCard("Standard Bearer", q);
        addCard("Runeclaw Bear", q);
        final SpellAbility s = spell(addCardToZone("Lightning Bolt", p, ZoneType.Hand), p);
        s.getTargets().add(bear);
        Assert.assertEquals(guard(p, s), "retarget");
        Assert.assertEquals(new ArrayList<>(s.getTargets()), List.of(flag));
        Assert.assertTrue(StaticAbilityMustTarget.meetsMustTargetRestriction(s));
    }

    // ------------------------------------------------------------------ S1 3: divided amounts

    /**
     * Volcanic Wind (divided damage), one creature: its card variant is not forced (the reviewer's repro: a forced target
     * with no allocation, an NPE at resolution), the targets are left empty for the normal targets ask, and that ask's
     * choice resolves with no error (here the yes-only answerer chooses no target; any target it chooses carries its
     * allocation).
     */
    @Test
    public void volcanicWindIsNeverForcedAndResolves() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        final Card bear = addCard("Grizzly Bears", q);
        final SpellAbility s = spell(addCardToZone("Volcanic Wind", p, ZoneType.Hand), p);
        Assert.assertTrue(s.isDividedAsYouChoose());
        Assert.assertFalse(force(p, s, "C" + bear.getId()), "a divided part is never forced");
        Assert.assertTrue(s.getTargets().isEmpty(), "no forced target: " + s.getTargets());
        s.setTargetingPlayer(p);
        pc(p).chooseTargetsFor(s); // the normal path (Forge AI here), with its allocation
        if (!s.getTargets().isEmpty()) {
            Assert.assertNotNull(s.getDividedValue(bear), "an allocation for each target");
        }
        AbilityUtils.resolve(s);
        System.out.println("[target-picks-guard] Volcanic Wind targets " + s.getTargets() + ", Bears damage " + bear.getDamage());
    }

    /** Volcanic Wind at our Bears, their Grizzly Bears legal: the guard does not retarget a divided part (kept-unsure). */
    @Test
    public void volcanicWindAtOurBearsIsNotRetargeted() {
        final Game g = game();
        final Player p = g.getPlayers().get(0), q = g.getPlayers().get(1);
        final Card ours = addCard("Runeclaw Bear", p);
        addCard("Grizzly Bears", q);
        final SpellAbility s = spell(addCardToZone("Volcanic Wind", p, ZoneType.Hand), p);
        s.getTargets().add(ours);
        Assert.assertEquals(guard(p, s), "kept-unsure");
        Assert.assertEquals(new ArrayList<>(s.getTargets()), List.of(ours));
    }
}
