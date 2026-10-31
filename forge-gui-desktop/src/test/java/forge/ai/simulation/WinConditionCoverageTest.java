package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.player.Player;
import forge.util.IdScope;

/**
 * solver-cap12-1030 round 2 (PREDECLARATION-solver-cap12-APPENDIX-round2 R2-5): the win-condition coverage suite. One
 * puzzle per way to win (the queued lane's list, every combo of COMBO-EDGE-CASES-1030 and the cube's "win the game" /
 * "loses the game" cards), each labelled by the honest label solver (HonestLabel.Seat on the step-1 counter kill spec,
 * K = 8 belief worlds, hideOwnLibrary) at the combo seat's main phase 1 priority, with an expected verdict:
 * <ul>
 * <li>PROVEN: the label is "win" (a forced line this turn or next in every world);</li>
 * <li>NOT_PROVEN: an advantage, lock or engine with no same-turn or next-turn kill, or a negative control: the label is
 * not "win" (and the label finishes: a loop with no gain ceiling must stop).</li>
 * </ul>
 * The puzzle text names cards (it is a position); nothing in the solver does. Rows print as "COV name expected got ..."
 * for the pass/fail table; -Dsc12.cov=name1,name2 runs a subset.
 */
public class WinConditionCoverageTest extends forge.ai.AITest {

    /** The step-1 counter kill spec (CP-1029 kill check, repro/kill-spec.json). */
    static final String KILL_SPEC = "{\"lossCfg\":{\"budget\":\"counter\",\"budgetMs\":\"2000\",\"cycles\":\"true\","
            + "\"engineCards\":\"12500\",\"engineMs\":\"750\",\"enginePathCards\":\"20000\",\"engineSteps\":\"500\","
            + "\"evalReserveMs\":\"500\",\"evalSteps\":\"500\",\"hideOwnLibrary\":\"true\",\"wallGraceMs\":\"500\"},"
            + "\"lossWallMs\":0,\"redraw\":\"belief\",\"worlds\":8,\"sequentialAi\":true,\"aiTimeoutSec\":600,"
            + "\"solverCfg\":{\"budget\":\"counter\",\"budgetMs\":\"4000\",\"cycles\":\"true\",\"engineCards\":\"25000\","
            + "\"engineMs\":\"2000\",\"enginePathCards\":\"20000\",\"engineSteps\":\"1000\",\"evalReserveMs\":\"1000\","
            + "\"evalSteps\":\"1250\",\"hideOwnLibrary\":\"true\",\"wallGraceMs\":\"1000\"}}";

    enum V { PROVEN, NOT_PROVEN }

    /** A puzzle: the combo seat is "human" (seat 0), active in its main phase 1; the opponent "ai" has no hand. */
    static final class P {
        final String name;
        final V expect;
        final String why;
        final Map<String, String> kv = new LinkedHashMap<>();

        P(String name, V expect, String why) {
            this.name = name;
            this.expect = expect;
            this.why = why;
            kv.put("turn", "7");
            kv.put("activeplayer", "human");
            kv.put("activephase", "MAIN1");
            kv.put("humanlife", "20");
            kv.put("ailife", "20");
            kv.put("humanlandsplayed", "0");
            kv.put("ailandsplayed", "0");
            kv.put("humanhand", "");
            kv.put("humanbattlefield", "");
            kv.put("humangraveyard", "");
            kv.put("humanexile", "");
            kv.put("humanlibrary", n("Island", 10));
            kv.put("aihand", "");
            kv.put("aibattlefield", "");
            kv.put("aigraveyard", "");
            kv.put("aiexile", "");
            kv.put("ailibrary", n("Forest", 20));
        }

        P k(String key, String value) {
            kv.put(key, value);
            return this;
        }

        String frame() {
            final StringBuilder sb = new StringBuilder("[state]\n");
            for (Map.Entry<String, String> e : kv.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static String n(String card, int count) {
        return String.join(";", Collections.nCopies(count, card));
    }

    static String j(String... parts) {
        final List<String> xs = new ArrayList<>();
        for (String p : parts) {
            if (!p.isEmpty()) {
                xs.add(p);
            }
        }
        return String.join(";", xs);
    }

    static List<P> puzzles() {
        final List<P> ps = new ArrayList<>();
        // --- alt wins from an empty or tiny library (the cube's "win the game" cards: Thassa's Oracle, Jace)
        ps.add(new P("oracle-library1", V.PROVEN, "Oracle, devotion 2 >= library 1")
                .k("humanhand", "Thassa's Oracle").k("humanbattlefield", n("Island", 2)).k("humanlibrary", "Island"));
        ps.add(new P("oracle-library10-control", V.NOT_PROVEN, "negative control: devotion 2 < library 10")
                .k("humanhand", "Thassa's Oracle").k("humanbattlefield", n("Island", 2)));
        ps.add(new P("jace-empty-plus1", V.PROVEN, "Jace, +1 draws from an empty library")
                .k("humanhand", "Jace, Wielder of Mysteries").k("humanbattlefield", n("Island", 4)).k("humanlibrary", ""));
        ps.add(new P("brainfreeze-self-oracle", V.PROVEN, "Brain Freeze on ourselves (3 cards), then Oracle")
                .k("humanhand", "Brain Freeze;Thassa's Oracle").k("humanbattlefield", n("Island", 4))
                .k("humanlibrary", n("Island", 3)));
        ps.add(new P("brainfreeze-self-jace", V.PROVEN, "Jace, then Brain Freeze ourselves, then Jace +1 from empty")
                .k("humanhand", "Brain Freeze;Jace, Wielder of Mysteries").k("humanbattlefield", n("Island", 6))
                .k("humanlibrary", n("Island", 3)));
        ps.add(new P("breach-brainfreeze-self-oracle", V.PROVEN,
                "Breach, escape Brain Freeze (storm 2) on ourselves (6 cards), Oracle")
                .k("humanhand", "Underworld Breach;Thassa's Oracle").k("humanbattlefield", n("Volcanic Island", 6))
                .k("humangraveyard", j("Brain Freeze", n("Mountain", 6))).k("humanlibrary", n("Island", 6)));
        ps.add(new P("doomsday-recall-oracle", V.PROVEN, "Doomsday (5), Ancestral Recall ourselves (2), Oracle")
                .k("humanhand", "Doomsday;Ancestral Recall;Thassa's Oracle")
                .k("humanbattlefield", n("Underground Sea", 6)).k("humanlibrary", n("Island", 15)));
        ps.add(new P("doomsday-jace-recall", V.PROVEN, "Doomsday, Jace, two Recalls: the last draw is from empty")
                .k("humanhand", "Doomsday;Jace, Wielder of Mysteries;Ancestral Recall;Ancestral Recall")
                .k("humanbattlefield", n("Underground Sea", 9)).k("humanlibrary", n("Island", 15)));
        // --- storm on the opponent
        ps.add(new P("brainfreeze-opp-mill", V.PROVEN, "Ritual, Brain Freeze (storm 1) mills the opponent's 6")
                .k("humanhand", "Dark Ritual;Brain Freeze").k("humanbattlefield", n("Underground Sea", 3))
                .k("ailibrary", n("Forest", 6)));
        ps.add(new P("tendrils-storm3", V.PROVEN, "Petal, Petal, Ritual, Tendrils storm 3 = 8")
                .k("humanhand", "Lotus Petal;Lotus Petal;Dark Ritual;Tendrils of Agony")
                .k("humanbattlefield", n("Swamp", 2)).k("ailife", "8"));
        ps.add(new P("grapeshot-storm3", V.PROVEN, "three Petals, Grapeshot storm 3 = 4")
                .k("humanhand", "Lotus Petal;Lotus Petal;Lotus Petal;Grapeshot").k("humanbattlefield", n("Mountain", 2))
                .k("ailife", "4"));
        // --- poison, Aetherflux (life as a resource, edge item 11)
        ps.add(new P("blightsteel-poison", V.PROVEN, "an unblocked infect 11 attacker")
                .k("humanbattlefield", "Blightsteel Colossus;Island"));
        ps.add(new P("aetherflux-pay50", V.PROVEN, "Reservoir at 60 life: pay 50")
                .k("humanbattlefield", "Aetherflux Reservoir;Island").k("humanlife", "60"));
        ps.add(new P("aetherflux-storm-lifegain", V.PROVEN, "Reservoir at 45: five Petals gain 15, pay 50")
                .k("humanhand", n("Lotus Petal", 5)).k("humanbattlefield", "Aetherflux Reservoir").k("humanlife", "45"));
        // --- loops (edge items 1, 2, 3, 12)
        ps.add(new P("kiki-pestermite", V.PROVEN, "Kiki + Pestermite: hasty tokens")
                .k("humanhand", "Pestermite").k("humanbattlefield", ForcedWinSolverTest.KIKI_BF).k("ailife", "6"));
        ps.add(new P("kiki-pestermite-20", V.PROVEN, "Kiki + Pestermite at 20 life: the loop needs 20 iterations")
                .k("humanhand", "Pestermite").k("humanbattlefield", ForcedWinSolverTest.KIKI_BF));
        ps.add(new P("twin-exarch", V.PROVEN, "Splinter Twin on Deceiver Exarch at 20")
                .k("humanhand", "Splinter Twin").k("humanbattlefield", j("Deceiver Exarch", n("Mountain", 4))));
        ps.add(new P("fastbond-zuran-crucible-aetherflux", V.PROVEN,
                "the land loop gains 1 life an iteration; Reservoir is the outlet (51+)")
                .k("humanbattlefield", "Fastbond;Zuran Orb;Crucible of Worlds;Aetherflux Reservoir;Forest")
                .k("humangraveyard", "Forest").k("humanlandsplayed", "1"));
        ps.add(new P("fastbond-zuran-crucible-no-outlet", V.NOT_PROVEN,
                "infinite life with no outlet: an advantage, and the loop must stop (item 12)")
                .k("humanbattlefield", "Fastbond;Zuran Orb;Crucible of Worlds;Forest").k("humangraveyard", "Forest")
                .k("humanlandsplayed", "1"));
        ps.add(new P("basalt-kinnan-ballista", V.PROVEN, "Kinnan + Basalt: unbounded colorless mana; Ballista X is the outlet")
                .k("humanhand", "Walking Ballista").k("humanbattlefield", "Basalt Monolith;Kinnan, Bonder Prodigy;Forest"));
        ps.add(new P("basalt-kinnan-no-outlet", V.NOT_PROVEN, "unbounded mana, nothing to spend it on")
                .k("humanbattlefield", "Basalt Monolith;Kinnan, Bonder Prodigy;Forest"));
        ps.add(new P("urza-thopter-sword", V.PROVEN, "Urza + Foundry + Sword: unbounded thopters, attack next turn")
                .k("humanbattlefield", "Urza, Lord High Artificer;Thopter Foundry;Sword of the Meek;Island"));
        // --- instant-speed windows (R2-4 c): the opponent's end step
        ps.add(new P("kiki-pestermite-their-end-step", V.PROVEN,
                "flash Pestermite at their end step, Kiki loop, attack on our turn (a window outside main phase 1)")
                .k("activeplayer", "ai").k("activephase", "END_OF_TURN").k("humanhand", "Pestermite")
                .k("humanbattlefield", ForcedWinSolverTest.KIKI_BF).k("ailife", "6"));
        // --- advantage, lock and extra-turn families (edge item 6): no same-turn or next-turn proof
        ps.add(new P("witness-ephemerate-timewarp", V.NOT_PROVEN, "an extra-turn chain: a lock, value territory")
                .k("humanhand", "Ephemerate;Time Warp").k("humanbattlefield", j("Eternal Witness", n("Island", 3), n("Plains", 3))));
        ps.add(new P("show-and-tell-griselbrand", V.NOT_PROVEN, "Show and Tell into Griselbrand: an advantage")
                .k("humanhand", "Show and Tell;Griselbrand").k("humanbattlefield", n("Island", 3)));
        ps.add(new P("sneak-emrakul-lethal", V.PROVEN, "Sneak Attack Emrakul for 15 into 15")
                .k("humanhand", "Emrakul, the Aeons Torn").k("humanbattlefield", j("Sneak Attack", n("Mountain", 2)))
                .k("ailife", "15"));
        ps.add(new P("reanimate-griselbrand", V.NOT_PROVEN, "Reanimate Griselbrand: an advantage")
                .k("humanhand", "Reanimate").k("humanbattlefield", "Swamp").k("humangraveyard", "Griselbrand"));
        return ps;
    }

    @DataProvider(name = "cov")
    public static Object[][] cov() {
        final String only = System.getProperty("sc12.cov");
        final List<Object[]> out = new ArrayList<>();
        for (P p : puzzles()) {
            if (only == null || java.util.Arrays.asList(only.split(",")).contains(p.name)) {
                out.add(new Object[] {p});
            }
        }
        return out.toArray(new Object[0][]);
    }

    /** The kill spec, plus -Dsc12.keys=k=v,... in both solverCfg and lossCfg (an arm of the suite). */
    static JsonObject spec() {
        final JsonObject o = JsonParser.parseString(KILL_SPEC).getAsJsonObject();
        final String keys = System.getProperty("sc12.keys");
        if (keys != null && !keys.isBlank()) {
            for (String kv : keys.split(",")) {
                final String[] p = kv.split("=", 2);
                for (String c : new String[] {"solverCfg", "lossCfg"}) {
                    o.getAsJsonObject(c).addProperty(p[0].trim(), p[1].trim());
                }
            }
        }
        return o;
    }

    static JsonObject label(final Game g, final Player me, final long key) {
        final HonestLabel.Seat seat = new HonestLabel.Seat(HonestLabel.Spec.parse(spec()));
        return PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> seat.label(g, me, key))).toJson();
    }

    static String str(final JsonObject r, final String k) {
        return r.has(k) && !r.get(k).isJsonNull() ? r.get(k).getAsString() : "-";
    }

    @Test(dataProvider = "cov", timeOut = 600000)
    public void coverage(final P p) {
        final Game g = ForcedWinSolverTest.game("Default", "Default", p.frame());
        final Player me = g.getPlayers().get(0);
        final long t0 = System.nanoTime();
        final JsonObject r = label(g, me, 42L);
        final String got = str(r, "label");
        final boolean pass = (p.expect == V.PROVEN) == "win".equals(got);
        System.out.println("COV " + p.name + " expect=" + p.expect + " got=" + got + " pass=" + pass + " how="
                + str(r, "how") + " tte=" + str(r, "turns_to_end") + " ms=" + (System.nanoTime() - t0) / 1_000_000
                + " row=" + r);
        Assert.assertTrue(pass, p.name + " (" + p.why + "): expected " + p.expect + ", label " + r);
    }
}
