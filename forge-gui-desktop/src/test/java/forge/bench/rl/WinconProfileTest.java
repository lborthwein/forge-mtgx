package forge.bench.rl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonParser;

/**
 * sdcc-wincon-1117: choiceScorer.winconProfile is off unless set and then absent from the spec JSON; the deckKey recipe;
 * and the fallback's selection (every scored option at goldfish 0 and reach 0; the highest profile score among eligible
 * options; ties by v). Names here are synthetic test data.
 */
public class WinconProfileTest {
    private static final double NAN = Double.NaN;

    private static ChoiceScorer.Config parse(final String s) {
        return ChoiceScorer.Config.parse(JsonParser.parseString(s).getAsJsonObject());
    }

    private static Path profile(final String json) throws Exception {
        final Path p = Files.createTempFile("wincon", ".json");
        Files.write(p, json.getBytes(StandardCharsets.UTF_8));
        p.toFile().deleteOnExit();
        return p;
    }

    @Test
    public void offByDefaultAndAbsentFromTheSpec() {
        final ChoiceScorer.Config c = parse("{}");
        Assert.assertEquals(c.winconProfile, "");
        Assert.assertFalse(c.toJson().has("winconProfile"));
    }

    @Test
    public void keptWhenOnAndTheFileIsRead() throws Exception {
        final Path p = profile("{\"decks\":{\"0d570a0e72b3258d\":{\"Beta Card\":0.4}}}");
        final ChoiceScorer.Config c = parse("{\"winconProfile\":\"" + p + "\"}");
        Assert.assertEquals(c.winconProfile, p.toString());
        Assert.assertEquals(c.toJson().get("winconProfile").getAsString(), p.toString());
        Assert.assertEquals(WinconProfile.load(p.toString()).get("0d570a0e72b3258d").get("Beta Card"), 0.4);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void missingFileIsRefusedAtParse() {
        parse("{\"winconProfile\":\"/nonexistent/wincon-profile.json\"}");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void badKeyIsRefused() throws Exception {
        WinconProfile.load(profile("{\"decks\":{},\"x\":1}").toString());
    }

    @Test
    public void deckKeyRecipe() {
        // "2 Alpha\n1 Beta Card" sorted by name, sha256 hex first 16
        final Map<String, Integer> m = new TreeMap<>();
        m.put("Beta Card", 1);
        m.put("Alpha", 2);
        Assert.assertEquals(WinconProfile.deckKey(m), "0d570a0e72b3258d");
        // a .dck's [Main]: printings merged, other sections ignored
        final Map<String, Integer> d = WinconProfile.dckMain(Arrays.asList("[metadata]", "Name=x", "[Main]",
                "1 Alpha|SET1", "1 Alpha|SET2|1", "1 Beta Card|SET3", "[Sideboard]", "1 Gamma|SET4"));
        Assert.assertEquals(d, m);
        Assert.assertEquals(WinconProfile.deckKey(d), "0d570a0e72b3258d");
    }

    @Test
    public void selectionHelper() {
        final double[] v = {0.30, 0.31, 0.29, 0.31};
        final boolean[] all = {true, true, true, true};
        // not all zero: nothing changes
        Assert.assertEquals(WinconProfile.select(new double[] {0, 0.5, 0.2, 0}, all, v, false), -1);
        // the highest score
        Assert.assertEquals(WinconProfile.select(new double[] {0, 0.5, 0.2, 0}, all, v, true), 1);
        // the best is ineligible (held in hand): the next
        Assert.assertEquals(WinconProfile.select(new double[] {0, 0.5, 0.2, 0}, new boolean[] {true, false, true, true}, v, true), 2);
        // no score > 0: nothing changes
        Assert.assertEquals(WinconProfile.select(new double[] {0, 0, 0, 0}, all, v, true), -1);
        // ties: the larger v, then the options' order
        Assert.assertEquals(WinconProfile.select(new double[] {0.2, 0.2, 0.2, 0}, all, v, true), 1);
        Assert.assertEquals(WinconProfile.select(new double[] {0, 0.2, 0, 0.2}, all, v, true), 1);
        Assert.assertEquals(WinconProfile.select(new double[] {0.2, 0, 0.2, 0}, all, new double[] {NAN, 0, 0.1, 0}, true), 2);
    }

    private static ChoiceScorer.Option opt(final String label, final double v, final double gf, final double reach,
            final int lines, final double score, final boolean eligible, final boolean policy) {
        final ChoiceScorer.Option o = new ChoiceScorer.Option();
        o.label = label;
        o.steps = new short[] {0};
        o.value = v;
        o.policy = policy;
        o.screen = new forge.ai.simulation.ForcedWinSolver.Screen();
        o.screen.gf = gf;
        o.screen.reach = reach;
        o.screen.lines = lines;
        o.wcScore = score;
        o.wcEligible = eligible;
        return o;
    }

    @Test
    public void ruleOnScoredOptions() {
        final ChoiceScorer.Scored s = new ChoiceScorer.Scored();
        s.options.add(opt("a", 0.40, 0, 0, 0, 0.0, true, true));
        s.options.add(opt("b", 0.35, 0, NAN, 0, 0.6, true, false));
        s.options.add(opt("c", 0.38, NAN, 0, 0, 0.9, false, false)); // held in hand: ineligible
        Assert.assertSame(ChoiceScorer.wincon(s), s.options.get(1));
        Assert.assertEquals(s.wincon, "b 0.6 (1 scored)");
        // one option shows a goldfish line: nothing changes
        final ChoiceScorer.Scored t = new ChoiceScorer.Scored();
        t.options.add(opt("a", 0.40, 0.64, 0, 0, 0.0, true, true));
        t.options.add(opt("b", 0.35, 0, 0, 0, 0.6, true, false));
        Assert.assertNull(ChoiceScorer.wincon(t));
        Assert.assertNull(t.wincon);
        // one scored option without a screen: not known to be zero, nothing changes
        final ChoiceScorer.Scored u = new ChoiceScorer.Scored();
        u.options.add(opt("a", 0.40, 0, 0, 0, 0.0, true, true));
        u.options.add(opt("b", 0.35, 0, 0, 0, 0.6, true, false));
        u.options.get(0).screen = null;
        Assert.assertNull(ChoiceScorer.wincon(u));
    }
}
