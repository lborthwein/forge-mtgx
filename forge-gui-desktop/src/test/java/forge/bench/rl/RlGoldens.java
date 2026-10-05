package forge.bench.rl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.player.Player;

/**
 * Golden DECIDE frames (interfaces.md §2.6; lane rl-r0-b1-1005): a frame listener that keeps the first frame of each
 * wanted kind and writes {@code decide-<name>.bin} (the whole wire frame: u32 len, u16 type, u16 flags, payload) plus
 * {@code decide-<name>.json}, the expected decode field by field, with each candidate's answer fragment, the steps
 * the fake server chose and the bridge answer JSON those steps produce.
 */
public final class RlGoldens implements RlSeat.FrameListener {

    public final Map<String, Object[]> kept = new LinkedHashMap<>();

    static final String[] WANTED = {"priority", "priority-x", "targets-spell", "targets-ability", "attack", "block",
            "mulligan", "mulligan-bottom", "start-player"};

    public boolean complete() {
        for (String w : WANTED) {
            if (!kept.containsKey(w)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized void onFrame(final Game game, final Player seat, final RlWire.Decide f,
            final RlCandidates.Menu m, final RlFeaturizer.Obs obs, final short[] steps, final JsonObject answer) {
        String name = null;
        switch (f.family) {
            case RlSchema.F_PRIORITY: {
                boolean x = false;
                for (int i = 0; i < f.C; i++) x |= f.candNum[i] >= 0;
                if (x) {
                    name = "priority-x";
                } else if (f.C >= 3) {
                    name = "priority";
                }
                break;
            }
            case RlSchema.F_TARGETS:
                if (f.C >= 2 && f.candTgt[1] >= 0) {
                    name = m.sourceIsSpell ? "targets-spell" : "targets-ability";
                }
                break;
            case RlSchema.F_ATTACK:
                name = f.S >= 2 ? "attack" : null;
                break;
            case RlSchema.F_BLOCK:
                name = "block";
                break;
            case RlSchema.F_MULLIGAN:
                name = "mulligan";
                break;
            case RlSchema.F_MULLIGAN_BOTTOM:
                name = "mulligan-bottom";
                break;
            case RlSchema.F_START_PLAYER:
                name = "start-player";
                break;
            default:
                break;
        }
        if (name == null || kept.containsKey(name)) {
            return;
        }
        final JsonObject meta = new JsonObject();
        meta.addProperty("family_name", RlSchema.familyName(f.family));
        meta.addProperty("source_is_spell", m.family == RlSchema.F_TARGETS ? m.sourceIsSpell : null);
        meta.addProperty("source_card", m.source == null ? null : m.source.getName());
        meta.addProperty("seat_name", seat.getName());
        final byte[] frame = RlWire.frameBytes(RlWire.T_DECIDE, 0, RlWire.encodeDecide(f));
        kept.put(name, new Object[] {frame, describe(f, m, steps, answer, meta)});
    }

    /** The field-by-field decode of a DECIDE frame plus the answer contract. */
    public static JsonObject describe(final RlWire.Decide f, final RlCandidates.Menu m, final short[] steps,
            final JsonObject answer, final JsonObject meta) {
        final JsonObject o = new JsonObject();
        o.addProperty("type", "DECIDE");
        final JsonObject h = new JsonObject();
        h.addProperty("game_uid", Long.toUnsignedString(f.gameUid));
        h.addProperty("dec_idx", f.decIdx);
        h.addProperty("seat", f.seat);
        h.addProperty("family", f.family);
        h.addProperty("mode", f.mode);
        h.addProperty("flags", f.flags);
        h.addProperty("L", f.L);
        h.addProperty("D", f.D);
        h.addProperty("C", f.C);
        h.addProperty("S", f.S);
        h.addProperty("P", f.hasPriv() ? f.P : 0);
        h.addProperty("min_pick", f.minPick);
        h.addProperty("max_pick", f.maxPick);
        h.addProperty("turn", f.turn);
        h.addProperty("n_teacher", f.teacher.length);
        o.add("header", h);
        final JsonObject a = new JsonObject();
        a.add("tok_card", ints(f.tokCard, f.L));
        a.add("tok_zone", bytes(f.tokZone, f.L));
        a.add("tok_attr", floats(f.tokAttr, f.L * RlSchema.N_ATTR));
        a.add("deck_card", ints(f.deckCard, f.D));
        a.add("deck_cnt", bytes(f.deckCnt, f.D));
        a.add("scal", floats(f.scal, RlSchema.N_SCAL));
        a.add("ctx", floats(f.ctx, RlSchema.N_CTX));
        a.add("cand_kind", bytes(f.candKind, f.C));
        a.add("cand_tok", shorts(f.candTok, f.C));
        a.add("cand_card", ints(f.candCard, f.C));
        a.add("cand_tgt", shorts(f.candTgt, 2 * f.C));
        a.add("cand_slot", shorts(f.candSlot, f.C));
        a.add("cand_num", shorts(f.candNum, f.C));
        a.add("cand_ability", bytes(f.candAbility, f.C));
        a.add("cand_flags", bytes(f.candFlags, f.C));
        a.add("slot_tok", shorts(f.slotTok, f.S));
        if (f.hasPriv()) {
            a.add("priv_card", ints(f.privCard, f.P));
            a.add("priv_zone", bytes(f.privZone, f.P));
            a.add("priv_cnt", bytes(f.privCnt, f.P));
        }
        a.add("teacher", shorts(f.teacher, f.teacher.length));
        o.add("arrays", a);
        if (m != null) {
            final JsonObject ans = new JsonObject();
            ans.addProperty("shape", m.shape == RlCandidates.SHAPE_SINGLE ? "single"
                    : m.shape == RlCandidates.SHAPE_CHOICES ? "choices" : "pairs");
            final JsonArray fr = new JsonArray();
            for (int i = 0; i < m.C(); i++) fr.add(m.fragment(i));
            ans.add("fragments", fr);
            ans.add("rule", new com.google.gson.JsonPrimitive("single: fragments[steps[0]]; choices: {\"choices\": "
                    + "[fragments[s] for s in steps]}; pairs: {\"pairs\": [fragments[s] for s in steps if not null]}"));
            o.add("answer_contract", ans);
        }
        if (steps != null) {
            o.add("example_steps", shorts(steps, steps.length));
            o.add("example_answer", answer);
        }
        if (meta != null) {
            o.add("meta", meta);
        }
        return o;
    }

    /** Apply steps to a golden's answer contract (the same rule as RlCandidates.Menu.answer). */
    public static JsonObject applyContract(final JsonObject golden, final short[] steps) {
        final JsonObject c = golden.getAsJsonObject("answer_contract");
        final JsonArray fr = c.getAsJsonArray("fragments");
        final String shape = c.get("shape").getAsString();
        final JsonObject o = new JsonObject();
        switch (shape) {
            case "single":
                return fr.get(steps[0]).deepCopy().getAsJsonObject();
            case "choices": {
                final JsonArray a = new JsonArray();
                for (short s : steps) a.add(fr.get(s).deepCopy());
                o.add("choices", a);
                return o;
            }
            default: {
                final JsonArray a = new JsonArray();
                for (short s : steps) {
                    final JsonElement e = fr.get(s);
                    if (!e.isJsonNull()) {
                        a.add(e.deepCopy());
                    }
                }
                o.add("pairs", a);
                return o;
            }
        }
    }

    public void write(final Path dir) throws IOException {
        Files.createDirectories(dir);
        for (Map.Entry<String, Object[]> e : kept.entrySet()) {
            Files.write(dir.resolve("decide-" + e.getKey() + ".bin"), (byte[]) e.getValue()[0]);
            Files.write(dir.resolve("decide-" + e.getKey() + ".json"),
                    (pretty((JsonObject) e.getValue()[1]) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    static String pretty(final JsonObject o) {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls()
                .create().toJson(o);
    }

    static JsonArray ints(final int[] a, final int n) {
        final JsonArray j = new JsonArray();
        for (int i = 0; i < n; i++) j.add(a[i]);
        return j;
    }

    static JsonArray shorts(final short[] a, final int n) {
        final JsonArray j = new JsonArray();
        for (int i = 0; i < n; i++) j.add(a[i]);
        return j;
    }

    static JsonArray bytes(final byte[] a, final int n) {
        final JsonArray j = new JsonArray();
        for (int i = 0; i < n; i++) j.add(a[i] & 0xff);
        return j;
    }

    static JsonArray floats(final float[] a, final int n) {
        final JsonArray j = new JsonArray();
        for (int i = 0; i < n; i++) j.add((double) a[i]);
        return j;
    }
}
