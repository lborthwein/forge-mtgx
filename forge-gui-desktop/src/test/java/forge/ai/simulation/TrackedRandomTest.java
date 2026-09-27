package forge.ai.simulation;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;

/** The play-out stream with a readable state is java.util.Random's stream, and a snapshot continues it exactly. */
public class TrackedRandomTest {

    private static List<Object> draws(Random r) {
        final List<Object> out = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            out.add(r.nextInt());
            out.add(r.nextInt(7 + i));
            out.add(r.nextLong());
            out.add(r.nextDouble());
            out.add(r.nextBoolean());
            out.add(r.nextFloat());
            out.add(r.nextGaussian());
        }
        final List<Integer> l = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            l.add(i);
        }
        Collections.shuffle(l, r);
        out.add(l);
        out.add(r.nextGaussian());
        return out;
    }

    @Test
    public void streamEqualsJavaUtilRandom() {
        for (long seed : new long[] {0L, 1L, -7L, 0x5eedL, Long.MAX_VALUE, 719212172L * 31}) {
            assertEquals(draws(new PlayoutKeys.TrackedRandom(seed)), draws(new Random(seed)), "seed " + seed);
        }
    }

    @Test
    public void snapshotContinuesTheStream() {
        final PlayoutKeys.TrackedRandom a = new PlayoutKeys.TrackedRandom(42L);
        a.nextInt();
        a.nextGaussian(); // leaves a cached second Gaussian in the state
        final long[] snap = a.snapshot();
        final String state = a.state();
        final PlayoutKeys.TrackedRandom b = PlayoutKeys.TrackedRandom.ofSnapshot(snap);
        assertEquals(b.state(), state);
        assertEquals(draws(b), draws(a));
        assertNotEquals(a.state(), state);
    }
}
