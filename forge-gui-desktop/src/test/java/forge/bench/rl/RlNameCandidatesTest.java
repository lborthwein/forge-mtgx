package forge.bench.rl;

import java.nio.charset.StandardCharsets;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * NAME candidates' pointers (lane rl-r0-b4-1006, after observation v1): a name the seat knows from a hidden zone points
 * at that zone 16-18 token (the opponent's known hand, known library positions) before any other token showing the
 * same card; otherwise at the first token showing it; otherwise -1. No Forge boot needed.
 */
public class RlNameCandidatesTest {

    private static RlFeaturizer.Obs obs(final int[] cards, final int[] zones) {
        final RlFeaturizer.Obs o = new RlFeaturizer.Obs();
        o.L = cards.length;
        o.tokCard = cards.clone();
        o.tokZone = new byte[cards.length];
        for (int i = 0; i < zones.length; i++) {
            o.tokZone[i] = (byte) zones[i];
        }
        return o;
    }

    @Test
    public void knownZoneTokenWins() {
        // own hand, opponent creature, known opponent hand (16): the zone-16 token
        Assert.assertEquals(RlCandidates.tokenOfCard(obs(new int[] {7, 7, 7}, new int[] {1, 6, 16}), 7), 2);
        // known own library (17) after an opponent graveyard copy
        Assert.assertEquals(RlCandidates.tokenOfCard(obs(new int[] {9, 7, 7}, new int[] {1, 9, 17}), 7), 2);
        // known opponent library (18)
        Assert.assertEquals(RlCandidates.tokenOfCard(obs(new int[] {7, 7}, new int[] {7, 18}), 7), 1);
        // the first of several known tokens
        Assert.assertEquals(RlCandidates.tokenOfCard(obs(new int[] {7, 7, 7}, new int[] {2, 18, 16}), 7), 1);
    }

    @Test
    public void otherwiseFirstTokenOrNone() {
        Assert.assertEquals(RlCandidates.tokenOfCard(obs(new int[] {4, 7, 7}, new int[] {1, 6, 9}), 7), 1);
        Assert.assertEquals(RlCandidates.tokenOfCard(obs(new int[] {4, 5}, new int[] {1, 6}), 7), -1);
        // <pad> / <unk> never point anywhere, even when an <unk> token exists
        Assert.assertEquals(RlCandidates.tokenOfCard(obs(new int[] {1, 1}, new int[] {16, 6}), 1), -1);
        Assert.assertEquals(RlCandidates.tokenOfCard(obs(new int[] {0}, new int[] {16}), 0), -1);
    }

    @Test
    public void bindPointsNameCandidatesAtKnownTokens() {
        final CardIndex idx = CardIndex.of(("<pad>\t0\n<unk>\t1\nCabal Therapy\t2\nOko, Thief of Crowns\t3\n"
                + "Brainstorm\t4\n").getBytes(StandardCharsets.UTF_8));
        final RlFeaturizer f = new RlFeaturizer(idx);
        final RlCandidates.Menu m = new RlCandidates.Menu();
        m.family = RlSchema.F_NAME;
        m.mode = RlSchema.M_SINGLE;
        m.shape = RlCandidates.SHAPE_SINGLE;
        for (String n : new String[] {"Oko, Thief of Crowns", "Brainstorm", "Not A Card"}) {
            final RlCandidates.Cand c = new RlCandidates.Cand();
            c.kind = RlSchema.K_CARD;
            c.name = n;
            m.cands.add(c);
        }
        // tokens: own hand Brainstorm, opponent battlefield Oko, known opponent hand Oko (16)
        final RlFeaturizer.Obs o = obs(new int[] {4, 3, 3}, new int[] {1, 6, 16});
        o.tokAttr = new float[o.L * RlSchema.N_ATTR];
        m.bind(o, f, null);
        Assert.assertEquals(m.card[0], 3);
        Assert.assertEquals(m.tok[0], 2, "Oko points at the known-hand token");
        Assert.assertEquals(m.card[1], 4);
        Assert.assertEquals(m.tok[1], 0, "Brainstorm: no known token, the first token showing it");
        Assert.assertEquals(m.card[2], CardIndex.UNK);
        Assert.assertEquals(m.tok[2], -1);
    }
}
