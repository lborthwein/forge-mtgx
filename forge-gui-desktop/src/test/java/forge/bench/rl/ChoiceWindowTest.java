package forge.bench.rl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Lane cm-choice-search-1009: {@link ChoiceWindow}'s bookkeeping on menus without game objects (MODE / CONFIRM style
 * candidates, whose identity is their key): ordinals per ask key, schedule hits, misses, answers the policy already
 * gave, asks never posed, entries never reached, the window's end, and the recorder's prior.
 */
public class ChoiceWindowTest {

    static RlCandidates.Menu menu(final int mode, final String... keys) {
        final RlCandidates.Menu m = new RlCandidates.Menu();
        m.family = RlSchema.F_MODE;
        m.mode = mode;
        m.minPick = 1;
        m.maxPick = 1;
        for (String k : keys) {
            final RlCandidates.Cand c = new RlCandidates.Cand();
            c.kind = RlSchema.K_MODE;
            c.key = k;
            m.cands.add(c);
        }
        return m;
    }

    static final String ASK = ChoiceWindow.askKey(RlSchema.F_MODE, "chooseModeForAbility", menu(RlSchema.M_SINGLE), null);

    @Test
    public void keysOfKeyOnlyCandidates() {
        final RlCandidates.Menu m = menu(RlSchema.M_SINGLE, "i:0", "i:1");
        Assert.assertEquals(ASK, "MODE|chooseModeForAbility|-");
        Assert.assertEquals(ChoiceWindow.looseKey(m, 1, null), RlSchema.K_MODE + "|k=i:1");
        Assert.assertEquals(ChoiceWindow.exactKey(m, 1), RlSchema.K_MODE + "|i:1");
        Assert.assertTrue(ChoiceWindow.singlePick(m));
        final RlCandidates.Menu sub = menu(RlSchema.M_SUBSET, "i:0", "i:1");
        sub.maxPick = 2;
        Assert.assertFalse(ChoiceWindow.singlePick(sub));
        sub.maxPick = 1;
        Assert.assertTrue(ChoiceWindow.singlePick(sub));
    }

    @Test
    public void scheduleAnswersItsAskAtItsOrdinalAndCountsTheRest() {
        final RlCandidates.Menu m = menu(RlSchema.M_SINGLE, "i:0", "i:1", "i:2");
        final ChoiceWindow.Counters c = new ChoiceWindow.Counters();
        final List<ChoiceWindow.Entry> sched = Arrays.asList(
                new ChoiceWindow.Entry(ASK, 1, ChoiceWindow.exactKey(m, 2), ChoiceWindow.looseKey(m, 2, null)),
                new ChoiceWindow.Entry(ASK, 2, "nope", "nope"),
                new ChoiceWindow.Entry(ASK, 3, ChoiceWindow.exactKey(m, 0), ChoiceWindow.looseKey(m, 0, null)),
                new ChoiceWindow.Entry(ASK, 4, ChoiceWindow.exactKey(m, 1), ChoiceWindow.looseKey(m, 1, null)),
                new ChoiceWindow.Entry(ASK, 9, ChoiceWindow.exactKey(m, 1), ChoiceWindow.looseKey(m, 1, null)));
        final ChoiceWindow w = ChoiceWindow.scheduled(sched, c);
        Assert.assertFalse(w.isOpen(), "armed, not open, until the action is answered");
        w.open(3);
        Assert.assertTrue(w.isOpen());
        // ordinal 0: not scheduled
        Assert.assertEquals(w.nextOrdinal(ASK), 0);
        Assert.assertEquals(w.scheduledAnswer(ASK, 0, m, null, 0), -1);
        // ordinal 1: scheduled to candidate 2 (the policy answered 0)
        Assert.assertEquals(w.nextOrdinal(ASK), 1);
        Assert.assertEquals(w.scheduledAnswer(ASK, 1, m, null, 0), 2);
        // ordinal 2: its answer is not a candidate: a miss, the policy answers
        Assert.assertEquals(w.nextOrdinal(ASK), 2);
        Assert.assertEquals(w.scheduledAnswer(ASK, 2, m, null, 1), -1);
        // ordinal 3: the schedule agrees with the policy (a hit, not a change)
        Assert.assertEquals(w.nextOrdinal(ASK), 3);
        Assert.assertEquals(w.scheduledAnswer(ASK, 3, m, null, 0), 0);
        // ordinal 4: a single legal answer, never posed
        Assert.assertEquals(w.nextOrdinal(ASK), 4);
        w.notPosed(ASK, 4, true);
        // another ask key has its own ordinals
        Assert.assertEquals(w.nextOrdinal("CONFIRM|confirmAction|X"), 0);
        // an entry is used once
        Assert.assertEquals(w.scheduledAnswer(ASK, 1, m, null, 0), -1);
        w.close();
        w.close();
        Assert.assertFalse(w.isOpen());
        Assert.assertTrue(w.isClosed());
        Assert.assertEquals(c.hitExact.get(), 2);
        Assert.assertEquals(c.miss.get(), 1);
        Assert.assertEquals(c.changed.get(), 1);
        Assert.assertEquals(c.trivial.get(), 1);
        Assert.assertEquals(c.unused.get(), 1, "ordinal 9 was never reached");
        final ChoiceWindow.Counters sum = new ChoiceWindow.Counters();
        c.addTo(sum);
        c.addTo(sum);
        Assert.assertEquals(sum.hits(), 4);
    }

    @Test
    public void aMultiPickAskIsNeverScheduled() {
        final RlCandidates.Menu m = menu(RlSchema.M_SUBSET, "i:0", "i:1");
        m.maxPick = 2;
        final ChoiceWindow.Counters c = new ChoiceWindow.Counters();
        final ChoiceWindow w = ChoiceWindow.scheduled(Arrays.asList(
                new ChoiceWindow.Entry(ASK, 0, ChoiceWindow.exactKey(m, 1), ChoiceWindow.looseKey(m, 1, null))), c);
        w.open(1);
        Assert.assertEquals(w.scheduledAnswer(ASK, w.nextOrdinal(ASK), m, null, 0), -1);
        Assert.assertEquals(c.miss.get(), 1);
    }

    @Test
    public void recorderKeepsOnePickAsksOfItsFamiliesWithThePrior() {
        final List<ChoiceWindow.Ask> into = new ArrayList<>();
        final ChoiceWindow w = ChoiceWindow.recorder(ChoiceWindow.parseFamilies("MODE,TARGETS"),
                p -> new double[] {0.25, 0.75, 0.5}, into);
        final RlCandidates.Menu m = menu(RlSchema.M_SINGLE, "i:0", "i:1", "i:2");
        m.cands.get(2).kind = 0; // illegal: prior 0
        w.open(5);
        Assert.assertTrue(w.recording());
        Assert.assertTrue(w.wants(RlSchema.F_MODE, m));
        Assert.assertFalse(w.wants(RlSchema.F_CARDS, m), "not a recorded family");
        w.record(ASK, w.nextOrdinal(ASK), RlSchema.F_MODE, m, null, new byte[0], 1);
        Assert.assertEquals(into.size(), 1);
        final ChoiceWindow.Ask a = into.get(0);
        Assert.assertEquals(a.ask, ASK);
        Assert.assertEquals(a.ordinal, 0);
        Assert.assertEquals(a.greedy, 1);
        Assert.assertEquals(a.prior[2], 0.0);
        Assert.assertEquals(a.maxPrior(), 0.75);
        Assert.assertEquals(a.loose[0], ChoiceWindow.looseKey(m, 0, null));
        // a scorer that fails records nothing
        final ChoiceWindow f = ChoiceWindow.recorder(ChoiceWindow.parseFamilies("MODE"), p -> {
            throw new java.io.IOException("down");
        }, into);
        f.open(5);
        f.record(ASK, f.nextOrdinal(ASK), RlSchema.F_MODE, m, null, new byte[0], 0);
        Assert.assertEquals(into.size(), 1);
        Assert.assertThrows(IllegalArgumentException.class, () -> ChoiceWindow.parseFamilies("PRIORITY"));
        Assert.assertThrows(IllegalArgumentException.class, () -> ChoiceWindow.parseFamilies(""));
    }
}
