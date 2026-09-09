package forge.player;

import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import java.util.ArrayList;
import java.util.List;

/** Standalone deterministic smoke: no game runtime or AI controller required. */
public final class HumanManaAffordabilitySmoke {
    private static int checks;
    private static void check(String text, boolean expected, List<Integer> mana) {
        final ManaCost cost = new ManaCost(text);
        final List<ManaCostShard> shards = new ArrayList<>();
        for (ManaCostShard shard : cost) shards.add(shard);
        for (int i = 0; i < cost.getGenericCost(); i++) shards.add(ManaCostShard.GENERIC);
        final List<ManaCostShard> before = List.copyOf(shards);
        final List<Integer> manaBefore = List.copyOf(mana);
        boolean actual = HumanManaAffordability.canMatch(shards, mana);
        if (actual != expected) throw new AssertionError(text + " vs " + mana + ": " + actual);
        if (!shards.equals(before) || !mana.equals(manaBefore)) throw new AssertionError("query mutated inputs");
        checks++;
    }
    public static void main(String[] args) {
        int w = ManaAtom.WHITE, b = ManaAtom.BLACK, r = ManaAtom.RED, c = ManaAtom.COLORLESS;
        for (int n : new int[]{1, 3, 6}) {
            List<Integer> whites = new ArrayList<>();
            for (int i = 0; i < n; i++) whites.add(w);
            check("W/R", true, whites);
            check("W/R W/R W/R", n >= 3, whites);
            check("W/R W/R W/R W/R W/R W/R", n >= 6, whites);
        }
        check("W/R W/R W/R", false, List.of(w, b, b));
        check("W/R W/R W/R", true, List.of(w, r, w));
        check("W/R W/R W/R", false, List.of(w, r));
        check("2 W", true, List.of(w, b, c));
        check("2 W", false, List.of(b, b, c));
        check("W B", true, List.of(w | b, w)); // augmenting path must reassign the flexible token
        check("C", false, List.of(w));
        check("C", true, List.of(c));
        check("0", true, List.of());
        check("W W", false, List.of(w | r)); // alternatives are not extra mana
        if (!HumanManaAffordability.mayAfford(null, null)) throw new AssertionError("unknown hidden");
        System.out.println("HumanManaAffordabilitySmoke: " + checks + " matching checks passed; inputs unchanged");
    }
}
