package forge.player;

import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCostShard;
import java.util.List;

public final class HumanManaXSmoke {
    private static void check(int expected, List<ManaCostShard> shards, List<HumanManaX.Token> tokens) {
        int actual = HumanManaX.minimumLife(shards, tokens);
        if (actual != expected) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        var w = new HumanManaX.Token(ManaAtom.WHITE, 0);
        var b = new HumanManaX.Token(ManaAtom.BLACK, 0);
        var paid = new HumanManaX.Token(ManaAtom.WHITE | ManaAtom.BLACK, 1);
        check(0, List.of(), List.of());
        check(0, List.of(ManaCostShard.WHITE), List.of(w, paid));
        check(1, List.of(ManaCostShard.WHITE, ManaCostShard.WHITE), List.of(w, b, paid));
        check(1, List.of(ManaCostShard.WHITE, ManaCostShard.BLACK), List.of(paid, w));
        check(1_000_000, List.of(ManaCostShard.WHITE, ManaCostShard.WHITE), List.of(w, b));
        check(1_000_000, List.of(ManaCostShard.GENERIC, ManaCostShard.GENERIC), List.of(paid));
        check(0, List.of(ManaCostShard.GENERIC, ManaCostShard.WHITE), List.of(paid, b, w));
        System.out.println("PASS 7 exact X payment matching checks");
    }
}
