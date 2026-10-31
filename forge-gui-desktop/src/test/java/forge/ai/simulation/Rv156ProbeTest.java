package forge.ai.simulation;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

import forge.GameCommand;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** PR #156 review probes (throwaway; prints, never fails on a finding). */
public class Rv156ProbeTest extends forge.ai.AITest {

    static void out(String s) {
        System.out.println("RV " + s);
    }

    static Card find(Player p, ZoneType z, String name) {
        return PendingCopyTest.find(p, z, name);
    }

    static int count(Player p, ZoneType z, String name) {
        int n = 0;
        for (Card c : p.getCardsIn(z)) {
            if (c.getName().equals(name)) {
                n++;
            }
        }
        return n;
    }

    /** identity fingerprint of everything pending in g (to show the original is not mutated). */
    static String fp(Game g) {
        final StringBuilder sb = new StringBuilder();
        final IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
        for (Trigger t : g.getTriggerHandler().getDelayedTriggersView()) {
            sb.append("T").append(System.identityHashCode(t)).append(" h=").append(System.identityHashCode(t.getHostCard()))
                    .append(t.getHostCard().getGame() == g ? "" : "!FOREIGN-HOST");
            sb.append(" rem=[");
            for (Object o : t.getTriggerRemembered()) {
                sb.append(System.identityHashCode(o));
                if (o instanceof Card && ((Card) o).getGame() != g) sb.append("!FOREIGN");
                sb.append(',');
            }
            sb.append("]");
            if (t.getSpawningAbility() != null) {
                sa(sb, t.getSpawningAbility(), g, "sp");
            }
            if (t.getOverridingAbility() != null) {
                sa(sb, t.getOverridingAbility(), g, "ov");
            }
            sb.append("; ");
        }
        for (forge.game.phase.Phase ph : new forge.game.phase.Phase[] {g.getUntap(), g.getUpkeep(), g.getBeginOfCombat(),
                g.getEndOfCombat(), g.getEndOfTurn(), g.getCleanup()}) {
            sb.append("P").append(ph.getAtCommands().size()).append('/').append(ph.getUntilCommands().size()).append('/')
                    .append(ph.getUntilMapCommands().size()).append('/').append(ph.getUntilEndMapCommands().size()).append('/')
                    .append(ph.getRegisterMapCommands().size());
            for (GameCommand c : ph.getUntilCommands()) sb.append(" u").append(System.identityHashCode(c));
            for (GameCommand c : ph.getAtCommands()) sb.append(" a").append(System.identityHashCode(c));
            sb.append(' ');
        }
        for (Card c : g.getCardsInGame()) {
            int n = 0;
            for (List<GameCommand> l : c.getCommandListsForCopy()) n += l.size();
            if (n > 0) sb.append(" C").append(c.getId()).append("=").append(n);
        }
        return sb.toString();
    }

    static void sa(StringBuilder sb, SpellAbility s, Game g, String tag) {
        for (SpellAbility a = s; a != null; a = a.getSubAbility()) {
            sb.append(' ').append(tag).append(System.identityHashCode(a)).append(":act=")
                    .append(a.getActivatingPlayer() == null ? "-" : System.identityHashCode(a.getActivatingPlayer()))
                    .append(a.getActivatingPlayer() != null && a.getActivatingPlayer().getGame() != g ? "!FOREIGN" : "")
                    .append(" host=").append(a.getHostCard() == null ? "-" : a.getHostCard().getGame() == g ? "ok" : "!FOREIGN")
                    .append(" trig=").append(a.getTrigger() == null ? "-" : System.identityHashCode(a.getTrigger()));
            if (a.usesTargeting()) {
                for (GameObject o : a.getTargets()) {
                    sb.append(" tg").append(System.identityHashCode(o));
                    if (o instanceof Card && ((Card) o).getGame() != g) sb.append("!FOREIGN");
                }
            }
            for (Map.Entry<forge.game.ability.AbilityKey, Object> e : a.getTriggeringObjects().entrySet()) {
                if (e.getValue() instanceof Card && ((Card) e.getValue()).getGame() != g) sb.append(" trobj!FOREIGN");
            }
            if (!a.getReplacingObjects().isEmpty()) sb.append(" repl=").append(System.identityHashCode(a.getReplacingObjects()));
        }
    }

    static GameCopier cp(Game g) {
        return PendingCopyTest.copier(g, true);
    }

    /** play g on until the turn is past {@code turn} (or the game ends). */
    static void through(Game g, int turn, PhaseType until) {
        int steps = 0;
        while (!g.isGameOver() && steps++ < 8000) {
            final int t = g.getPhaseHandler().getTurn();
            if (t > turn && (until == null || g.getPhaseHandler().getPhase() == until || g.getPhaseHandler().getPhase().isAfter(until))) {
                break;
            }
            g.getPhaseHandler().mainLoopStep();
        }
    }

    /** the common check: copy (pending on), play copy and a control copy (pending off) and the original separately. */
    static void probe(String tag, Game g, java.util.function.Function<Game[], String> state, int turns, PhaseType until) {
        final Player me = g.getPlayers().get(0);
        out(tag + " hasPending=" + GameCopier.hasPending(g) + " unsupported=" + GameCopier.unsupportedPending(g));
        final String before = fp(g);
        final GameCopier c = cp(g);
        final Game cg = c.makeCopy();
        out(tag + " copy gaps=" + c.getPendingGaps() + " copied=" + c.getPendingCopied());
        out(tag + " orig fp=" + before);
        out(tag + " copy fp=" + fp(cg));
        final String afterCopy = fp(g);
        out(tag + " orig unchanged by copy: " + before.equals(afterCopy));
        final GameCopier c0 = PendingCopyTest.copier(g, false);
        final Game cg0 = c0.makeCopy();
        final int t0 = g.getPhaseHandler().getTurn();
        through(cg, t0 + turns - 1, until);
        out(tag + " orig unchanged after copy played: " + before.equals(fp(g)) + (before.equals(fp(g)) ? "" : " NOW=" + fp(g)));
        through(cg0, t0 + turns - 1, until);
        through(g, t0 + turns - 1, until);
        out(tag + " REAL: " + state.apply(new Game[] {g}) + " turn=" + g.getPhaseHandler().getTurn() + " " + g.getPhaseHandler().getPhase());
        out(tag + " COPY(pending): " + state.apply(new Game[] {cg}) + " turn=" + cg.getPhaseHandler().getTurn() + " " + cg.getPhaseHandler().getPhase());
        out(tag + " COPY(off): " + state.apply(new Game[] {cg0}) + " turn=" + cg0.getPhaseHandler().getTurn());
    }

    static Player me(Game g) {
        return g.getPlayers().get(0);
    }

    static Player opp(Game g) {
        return g.getPlayers().get(1);
    }

    // ---------------------------------------------------------------- Sneak Attack

    @Test(timeOut = 900000)
    public void sneakAttack() {
        final Game g = PendingCopyTest.base("Sneak Attack;Mountain", "Craw Wurm", WinConditionCoverageTest.n("Island", 10), 20);
        final Player me = me(g);
        final Card sn = find(me, ZoneType.Battlefield, "Sneak Attack");
        final SpellAbility ab = sn.getFirstSpellAbility();
        ab.setActivatingPlayer(me);
        g.getStack().add(ab);
        PendingCopyTest.settle(g, me);
        out("sneak wurm on bf=" + count(me, ZoneType.Battlefield, "Craw Wurm") + " delayed=" + g.getTriggerHandler().getDelayedTriggersView().size());
        probe("sneak", g, a -> "wurmBF=" + count(me(a[0]), ZoneType.Battlefield, "Craw Wurm") + " wurmGY="
                + count(me(a[0]), ZoneType.Graveyard, "Craw Wurm"), 1, null);
    }

    // ---------------------------------------------------------------- Flickerwisp (two same-name Bears on their side)

    @Test(timeOut = 900000)
    public void flickerwisp() {
        final WinConditionCoverageTest.P p = new WinConditionCoverageTest.P("rv", WinConditionCoverageTest.V.NOT_PROVEN, "rv")
                .k("humanhand", "Flickerwisp").k("humanbattlefield", "Plains").k("aibattlefield", "Grizzly Bears;Grizzly Bears")
                .k("humanlibrary", WinConditionCoverageTest.n("Island", 10)).k("ailibrary", WinConditionCoverageTest.n("Island", 10));
        final Game g = ForcedWinSolverTest.game("Default", "Default", p.frame());
        final Player me = me(g);
        final Card fw = find(me, ZoneType.Hand, "Flickerwisp");
        final SpellAbility sa = fw.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        PendingCopyTest.cast(g, fw, sa);
        PendingCopyTest.settle(g, me);
        final List<Integer> ex = new ArrayList<>();
        for (Card c : g.getCardsIn(ZoneType.Exile)) ex.add(c.getId());
        out("fw exiled ids=" + ex + " delayed=" + g.getTriggerHandler().getDelayedTriggersView().size());
        probe("flickerwisp", g, a -> {
            final List<String> s = new ArrayList<>();
            for (Card c : opp(a[0]).getCardsIn(ZoneType.Battlefield)) s.add(c.getName() + "#" + c.getId());
            for (Card c : me(a[0]).getCardsIn(ZoneType.Battlefield)) s.add("me:" + c.getName() + "#" + c.getId());
            s.add("exile=" + a[0].getCardsIn(ZoneType.Exile).size());
            return s.toString();
        }, 1, null);
    }

    // ---------------------------------------------------------------- Threaten

    @Test(timeOut = 900000)
    public void threaten() {
        final WinConditionCoverageTest.P p = new WinConditionCoverageTest.P("rv", WinConditionCoverageTest.V.NOT_PROVEN, "rv")
                .k("humanhand", "Threaten").k("humanbattlefield", "Mountain").k("aibattlefield", "Craw Wurm")
                .k("humanlibrary", WinConditionCoverageTest.n("Island", 10)).k("ailibrary", WinConditionCoverageTest.n("Island", 10));
        final Game g = ForcedWinSolverTest.game("Default", "Default", p.frame());
        final Player me = me(g);
        final Card th = find(me, ZoneType.Hand, "Threaten");
        final SpellAbility sa = th.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        sa.getTargets().add(find(opp(g), ZoneType.Battlefield, "Craw Wurm"));
        PendingCopyTest.cast(g, th, sa);
        PendingCopyTest.settle(g, me);
        out("threaten me has wurm=" + count(me, ZoneType.Battlefield, "Craw Wurm"));
        probe("threaten", g, a -> "meWurm=" + count(me(a[0]), ZoneType.Battlefield, "Craw Wurm") + " oppWurm="
                + count(opp(a[0]), ZoneType.Battlefield, "Craw Wurm"), 1, null);
    }

    // ---------------------------------------------------------------- Ephemerate (rebound: a replacement effect)

    @Test(timeOut = 900000)
    public void ephemerate() {
        final Game g = PendingCopyTest.base("Grizzly Bears;Plains;Plains", "Ephemerate", WinConditionCoverageTest.n("Island", 10), 20);
        final Player me = me(g);
        final Card e = find(me, ZoneType.Hand, "Ephemerate");
        final SpellAbility sa = e.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        sa.getTargets().add(find(me, ZoneType.Battlefield, "Grizzly Bears"));
        PendingCopyTest.cast(g, e, sa);
        PendingCopyTest.settle(g, me);
        out("ephemerate exile=" + count(me, ZoneType.Exile, "Ephemerate") + " delayed=" + g.getTriggerHandler().getDelayedTriggersView().size()
                + " cmd=" + g.getCardsIn(ZoneType.Command).size());
        probe("ephemerate", g, a -> "ephExile=" + count(me(a[0]), ZoneType.Exile, "Ephemerate") + " ephGY="
                + count(me(a[0]), ZoneType.Graveyard, "Ephemerate") + " delayed=" + a[0].getTriggerHandler().getDelayedTriggersView().size(),
                2, PhaseType.MAIN1);
    }

    // ---------------------------------------------------------------- Pact cast at the opponent's end step (detector)

    @Test(timeOut = 900000)
    public void pactLabel() {
        final Game g = PendingCopyTest.pact("Grizzly Bears|SummonSick", true);
        probe("pact", g, a -> "meLost=" + me(a[0]).hasLost(), 2, PhaseType.MAIN1);
    }

    // ---------------------------------------------------------------- Flickerform: an Effect card's next end step

    @Test(timeOut = 900000)
    public void flickerform() {
        final Game g = PendingCopyTest.base("Grizzly Bears;Plains;Plains;Plains;Plains", "Flickerform", WinConditionCoverageTest.n("Island", 10), 20);
        final Player me = me(g);
        final Card ff = find(me, ZoneType.Hand, "Flickerform");
        final Card bears = find(me, ZoneType.Battlefield, "Grizzly Bears");
        final SpellAbility fsa = ff.getFirstSpellAbility();
        fsa.setActivatingPlayer(me);
        fsa.getTargets().add(bears);
        PendingCopyTest.cast(g, ff, fsa);
        PendingCopyTest.settle(g, me);
        final Card b2 = find(me, ZoneType.Battlefield, "Grizzly Bears");
        out("flickerform attached=" + find(me, ZoneType.Battlefield, "Flickerform"));
        SpellAbility ab = null;
        for (SpellAbility s : find(me, ZoneType.Battlefield, "Flickerform").getSpellAbilities()) {
            if (s.isActivatedAbility()) ab = s;
        }
        out("flickerform ability=" + ab);
        if (ab == null) return;
        ab.setActivatingPlayer(me);
        g.getStack().add(ab);
        PendingCopyTest.settle(g, me);
        out("flickerform cmd=" + g.getCardsIn(ZoneType.Command) + " exile=" + g.getCardsIn(ZoneType.Exile) + " delayed="
                + g.getTriggerHandler().getDelayedTriggersView().size());
        probe("flickerform", g, a -> "bearsBF=" + count(me(a[0]), ZoneType.Battlefield, "Grizzly Bears") + " ffBF="
                + count(me(a[0]), ZoneType.Battlefield, "Flickerform") + " exile=" + a[0].getCardsIn(ZoneType.Exile).size()
                + " cmd=" + a[0].getCardsIn(ZoneType.Command).size(), 1, null);
    }

    /** Threaten, then the label: the copy keeps the stolen Wurm past cleanup (temp controller re-keyed at 0). */
    @Test(timeOut = 900000)
    public void threatenLabel() {
        final WinConditionCoverageTest.P p = new WinConditionCoverageTest.P("rv", WinConditionCoverageTest.V.NOT_PROVEN, "rv")
                .k("humanhand", "Threaten").k("humanbattlefield", "Mountain").k("aibattlefield", "Craw Wurm").k("ailife", "12")
                .k("humanlibrary", WinConditionCoverageTest.n("Island", 10)).k("ailibrary", WinConditionCoverageTest.n("Island", 10));
        final Game g = ForcedWinSolverTest.game("Default", "Default", p.frame());
        final Player me = me(g);
        final Card th = find(me, ZoneType.Hand, "Threaten");
        final SpellAbility sa = th.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        sa.getTargets().add(find(opp(g), ZoneType.Battlefield, "Craw Wurm"));
        PendingCopyTest.cast(g, th, sa);
        PendingCopyTest.settle(g, me);
        final boolean[] real = new boolean[1];
        final com.google.gson.JsonObject r = PendingCopyTest.labelAndTruth("rvthreaten", g, me, real);
        out("threatenLabel label=" + r + " realWin=" + real[0]);
    }

    @Test(timeOut = 900000)
    public void sneakDebug() {
        final Game g = PendingCopyTest.base("Sneak Attack;Mountain", "Craw Wurm", WinConditionCoverageTest.n("Island", 10), 20);
        final Player me = me(g);
        final Card sn = find(me, ZoneType.Battlefield, "Sneak Attack");
        final SpellAbility ab = sn.getFirstSpellAbility();
        ab.setActivatingPlayer(me);
        g.getStack().add(ab);
        GameSimulator.resolveStack(g, opp(g));
        out("sneakDebug after resolve: bf=" + me.getCardsIn(ZoneType.Battlefield) + " hand=" + me.getCardsIn(ZoneType.Hand) + " gy="
                + me.getCardsIn(ZoneType.Graveyard) + " delayed=" + g.getTriggerHandler().getDelayedTriggersView().size() + " phase="
                + g.getPhaseHandler().getPhase());
    }

    // ---------------------------------------------------------------- lambdas and commands the mapper cannot know

    static final class SetHolder implements GameCommand {
        private static final long serialVersionUID = 1L;
        final java.util.Set<Card> s = new java.util.HashSet<>();
        public void run() {
        }
    }

    static final class Box implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        Card c;
    }

    @Test(timeOut = 900000)
    public void lambdas() {
        final Game g = PendingCopyTest.bearsOnly();
        final Player me = me(g);
        final Card bears = find(me, ZoneType.Battlefield, "Grizzly Bears");
        final Game other = PendingCopyTest.bearsOnly();
        final Player foreign = me(other);
        final java.util.Map<String, Card> m = new java.util.HashMap<>();
        m.put("x", bears);
        final Box box = new Box();
        box.c = bears;
        final java.util.concurrent.atomic.AtomicReference<Card> ref = new java.util.concurrent.atomic.AtomicReference<>(bears);
        final Runnable nonSer = () -> bears.setTapped(true);
        final Card loose = new Card(987654, g); // a card of the original in no zone
        loose.setName("Loose");
        final int[] counter = new int[1];
        final Object[][] cases = {
                {"map", (GameCommand) () -> m.get("x").tap(true, null, null)},
                {"box", (GameCommand) () -> box.c.tap(true, null, null)},
                {"atomicref", (GameCommand) () -> ref.get().tap(true, null, null)},
                {"nonSerRunnableRef", (GameCommand) nonSer::run},
                {"foreignPlayer", (GameCommand) () -> foreign.gainLife(1, null, null)},
                {"intArrayShared", (GameCommand) () -> counter[0]++},
                {"looseCard", (GameCommand) () -> loose.setTapped(true)},
                {"setHolder", new SetHolder()},
                {"nestedMap", (GameCommand) () -> ((GameCommand) () -> m.clear()).run()},
        };
        for (Object[] cs : cases) {
            final Game gg = g;
            gg.getEndOfCombat().getUntilCommands().clear();
            gg.getEndOfCombat().addUntil((GameCommand) cs[1]);
            final List<String> gaps = GameCopier.unsupportedPending(gg);
            final GameCopier c = cp(gg);
            final Game cg = c.makeCopy();
            final List<GameCommand> carried = new ArrayList<>(cg.getEndOfCombat().getUntilCommands());
            String run = "";
            if (!carried.isEmpty()) {
                final int before = counter[0];
                final boolean tb = bears.isTapped();
                try {
                    carried.get(0).run();
                    run = " ran: liveCounter " + before + "->" + counter[0] + " liveBearsTapped " + tb + "->" + bears.isTapped()
                            + " liveLooseTapped=" + loose.isTapped() + " foreignLife=" + foreign.getLife();
                } catch (RuntimeException e) {
                    run = " ran:" + e;
                }
                bears.setTapped(false);
            }
            out("lambda " + cs[0] + " detector=" + gaps + " copyGaps=" + c.getPendingGaps() + " carried=" + carried.size() + run);
        }
        g.getEndOfCombat().getUntilCommands().clear();
    }
}
