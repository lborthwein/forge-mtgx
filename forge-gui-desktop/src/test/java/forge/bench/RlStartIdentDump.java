package forge.bench;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.bench.rl.CardIndex;
import forge.bench.rl.RlWire;

/**
 * Identity dump (lane sdcc-startfix-1113): plays a fixed set of games of {@link RlSeedingTest}'s fixtures (Forge
 * seats): plain games and position starts of every shape WITHOUT the key {@code cast}, and prints per game the digest,
 * the sha of the tape (timing fields removed) and what was installed. Run once on the classpath of each of two jars:
 * equal output = the same games. usage: RlStartIdentDump N (cwd = a directory with res/, -Drl.testres as the suites).
 */
public final class RlStartIdentDump {
    private RlStartIdentDump() {
    }

    static JsonArray range(final int lo, final int hi) {
        return RlStartStateTest.arr(lo, hi);
    }

    public static void main(final String[] args) throws Exception {
        final int n = Integer.parseInt(args[0]);
        RlSeedingTest.setUp();
        for (int i = 0; i < n; i++) {
            final int seat = (i / 5) % 2;
            JsonObject st = null;
            final List<String> pc = new ArrayList<>(RlSeedingTest.pieces(i, seat, 3));
            switch (i % 5) {
            case 1: // a classic start: placed pieces, the ranges of the training positions, either seat to move
                st = RlStartStateTest.spec(seat, pc, Arrays.asList("auto", "battlefield", "graveyard").subList(0, pc.size()),
                        new int[] {0, 0, 0, 1});
                st.addProperty("active", 0.8);
                break;
            case 2: // an assembly start's shape: a card in hand first, the rest as placed, nothing on top
                st = RlStartStateTest.spec(seat, pc, Arrays.asList("hand", "auto", "auto").subList(0, pc.size()), new int[] {0});
                st.add("gy", range(0, 2));
                st.add("hand", range(3, 5));
                break;
            case 3: // one or two pieces on top of the library
                st = RlStartStateTest.spec(seat, pc, Arrays.asList("auto", "auto", "auto").subList(0, pc.size()), new int[] {1, 2});
                break;
            case 4: // a piece the deck lacks: not applied
                st = RlStartStateTest.spec(seat, Arrays.asList(pc.get(0), "No Such Card"), Arrays.asList("auto", "auto"),
                        new int[] {0});
                break;
            default: // a plain game
                break;
            }
            if (st != null && i % 5 != 4) {
                st.add("lands", range(4, 7));
                st.add("opp_lands", range(3, 6));
                st.add("opp_hand", range(2, 5));
                st.add("opp_board", range(0, 2));
                st.add("opp_gy", range(0, 4));
                st.add("opp_life", range(6, 20));
                st.add("life", range(8, 20));
            }
            final RlSeedingTest.Run r = RlSeedingTest.play("train", RlStartStateTest.game(i, st));
            final String tape = CardIndex.sha256(RlWire.canonicalString(RlSeedingTest.strip(r.p.tape))
                    .getBytes(StandardCharsets.UTF_8)).substring(0, 16);
            final JsonObject stripped = RlSeedingTest.strip(r.p.tape);
            final StringBuilder keys = new StringBuilder();
            for (String k : new java.util.TreeSet<>(stripped.keySet())) {
                keys.append(' ').append(k).append('=').append(CardIndex.sha256(RlWire.canonicalString(stripped.get(k))
                        .getBytes(StandardCharsets.UTF_8)).substring(0, 10));
            }
            System.out.println("KEYS " + i + keys);
            System.out.println("IDENT " + i + " kind " + (i % 5) + " guard " + r.p.guardError + " void " + r.p.end.get("void")
                    + " digest " + r.p.end.get("digest").getAsString() + " turns " + r.p.end.get("turns") + " tape " + tape
                    + " started " + (r.p.end.has("started") ? r.p.end.get("started") : "-"));
        }
        System.out.flush();
        System.exit(0);
    }
}
