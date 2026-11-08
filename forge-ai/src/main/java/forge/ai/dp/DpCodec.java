package forge.ai.dp;

import java.util.Collection;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import forge.ai.AiTapeCodec;
import forge.game.Game;

/**
 * decisionpoint-1102 (CENSUS.md §5.1): the canonical encoding of options and answers, on the AI tape's identities
 * ({@link AiTapeCodec#ref}: {@code c<id>} a card, {@code p<seat>} a player, {@code s<i>/<host>} a stack item,
 * {@code a<host>:<hash>} an ability by host card and description hash). Collections keep their order (an ARRANGE
 * answer's order is the answer); a pair is {@code [left, right]} (scry/surveil: top, other); a map is an object keyed by
 * the key's reference (combat damage, divided amounts). Never card names. Pure: reads ids and descriptions only.
 */
public final class DpCodec {
    private DpCodec() {
    }

    public static JsonElement encode(final Game g, final Object o) {
        if (o == null) {
            return JsonNull.INSTANCE;
        }
        if (o instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (o instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (o instanceof String s) {
            return new JsonPrimitive(s);
        }
        if (o instanceof Enum<?> e) {
            return new JsonPrimitive(e.name());
        }
        if (o instanceof Pair<?, ?> p) {
            final JsonArray a = new JsonArray();
            a.add(encode(g, p.getLeft()));
            a.add(encode(g, p.getRight()));
            return a;
        }
        if (o instanceof Collection<?> c) {
            final JsonArray a = new JsonArray();
            for (Object x : c) {
                a.add(encode(g, x));
            }
            return a;
        }
        if (o instanceof Map<?, ?> m) {
            final JsonObject j = new JsonObject();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                final JsonElement k = encode(g, e.getKey());
                j.add(k.isJsonPrimitive() ? k.getAsString() : k.toString(), encode(g, e.getValue()));
            }
            return j;
        }
        final String ref = AiTapeCodec.ref(g, o);
        return ref == null ? new JsonPrimitive(o.getClass().getSimpleName()) : new JsonPrimitive(ref);
    }

    /** The point's options, encoded (null when the call carried none). */
    public static JsonElement options(final DecisionPoint dp) {
        return dp.options() == null ? JsonNull.INSTANCE
                : encode(dp.seat() == null ? null : dp.seat().getGame(), dp.options());
    }
}
