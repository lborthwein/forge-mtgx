package forge.bench;

import com.google.gson.*;
import forge.game.GameRules;
import java.util.Set;

/** No game allocation: strict native per-seat configuration contract. */
public final class CubeComboConfigSmoke {
    private static Set<Integer> parse(String json, boolean simulation, String profile, GameRules.AiInformationPolicy policy) {
        return BenchMain.cubeComboSeats(JsonParser.parseString(json).getAsJsonObject(), 2, simulation, profile, policy);
    }
    public static void main(String[] args) {
        var policy = GameRules.AiInformationPolicy.CLOSED_REPAIR;
        if (!parse("{}", false, "Default", policy).isEmpty()) throw new AssertionError("Default must be opt-out");
        for (int seat = 0; seat < 2; seat++) {
            if (!parse("{cubeComboSeats:[" + seat + "],seats:{0:forge,1:forge}}", false, "Default", policy)
                    .equals(Set.of(seat))) throw new AssertionError("Exact per-seat selection");
        }
        int rejected = 0;
        for (String json : new String[]{"{cubeComboSeats:null}", "{cubeComboSeats:0}", "{cubeComboSeats:[0,0]}",
                "{cubeComboSeats:[2]}", "{cubeComboSeats:[-1]}", "{cubeComboSeats:[0.5]}", "{cubeComboSeats:[true]}",
                "{cubeComboSeats:['0']}", "{cubeComboSeats:[0],seats:{0:bridge}}", "{cubeComboSeats:[0],simSeats:[0]}"}) {
            try { parse(json, false, "Default", policy); throw new AssertionError("Accepted invalid config: " + json); }
            catch (IllegalArgumentException expected) { rejected++; }
        }
        for (int variant = 0; variant < 3; variant++) {
            try {
                parse("{cubeComboSeats:[0],seats:{0:forge,1:forge}}", variant == 0,
                        variant == 1 ? "Aggro" : "Default", variant == 2 ? GameRules.AiInformationPolicy.STOCK : policy);
                throw new AssertionError("Accepted incompatible policy");
            } catch (IllegalArgumentException expected) { rejected++; }
        }
        System.out.println("PASS per-seat selection, default opt-out, " + rejected + " invalid configs rejected");
    }
}
