package forge.bench;

import forge.card.mana.ManaAtom;

/** Pure encoder witness: no game, prompt, or AI action is started. */
public final class StateEncoderManaPoolSmoke {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        final var encoded = StateEncoder.encodeManaPoolAmounts(
                type -> type == ManaAtom.RED ? 1 : type == ManaAtom.COLORLESS ? 3 : 0, 4);
        check(encoded.get("R").getAsInt() == 1, "red amount survives");
        check(encoded.get("C").getAsInt() == 3, "actual colorless mana is encoded");
        check("mana-atom/1".equals(encoded.get("colorlessEncoding").getAsString()), "corrected encoding declared");
        check(encoded.get("total").getAsInt() == 4, "colored plus colorless total agrees");
        for (String color : new String[] {"W", "U", "B", "G"}) {
            check(encoded.get(color).getAsInt() == 0, "other colors remain zero: " + color);
        }
        System.out.println("PASS StateEncoder mana pool WUBRGC and total");
    }
}
