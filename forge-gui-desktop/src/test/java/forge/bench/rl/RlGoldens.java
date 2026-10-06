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

    /** Phase B (lane rl-r0-b4-1006): one frame per family 8-24; the two-frame families keep both frames of one ask. */
    public static final String[] WANTED_B = {"entity", "cards", "mode", "confirm", "number", "optional-costs", "scry-assign",
            "scry-permute", "order", "discard-from", "cost-cards", "pile", "surveil-assign", "surveil-permute",
            "put-on-top", "optional-trigger", "pay-to-prevent", "name", "color", "targets-trigger"};

    /** The kinds {@link #complete} waits for (default: Phase A's). */
    public String[] wanted = WANTED;

    /**
     * Also keep RECORD frames (record mode; lane rl-r0-b4-1006), for families an RL seat is rarely asked: the frame is
     * written as the DECIDE frame of the same ask (teacher stripped, n_teacher 0) with Forge's answer as
     * {@code example_steps}, and {@code meta.source = "record"}.
     */
    public boolean acceptRecord = false;

    /** The last ASSIGN frame of a two-frame family (per family), waiting for its PERMUTE partner. */
    private final Map<Integer, Object[]> pendingAssign = new java.util.HashMap<>();

    public boolean complete() {
        for (String w : wanted) {
            if (!kept.containsKey(w)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized void onFrame(final Game game, final Player seat, final RlWire.Decide f,
            final RlCandidates.Menu m, final RlFeaturizer.Obs obs, final short[] steps, final JsonObject answer) {
        if (f.teacher.length == 0) {
            keep(game, seat, f, m, obs, steps, answer, false);
            return;
        }
        if (!acceptRecord) {
            return;
        }
        final short[] t = f.teacher;
        f.teacher = new short[0]; // the ask's DECIDE layout; restored below (the RECORD frame was already sent)
        try {
            keep(game, seat, f, m, obs, t, answer, true);
        } finally {
            f.teacher = t;
        }
    }

    private void keep(final Game game, final Player seat, final RlWire.Decide f, final RlCandidates.Menu m,
            final RlFeaturizer.Obs obs, final short[] steps, final JsonObject answer, final boolean fromRecord) {
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
            case RlSchema.F_ENTITY: name = "entity"; break;
            case RlSchema.F_CARDS: name = "cards"; break;
            case RlSchema.F_MODE: name = "mode"; break;
            case RlSchema.F_CONFIRM: name = "confirm"; break;
            case RlSchema.F_NUMBER: name = "number"; break;
            case RlSchema.F_OPTIONAL_COSTS: name = "optional-costs"; break;
            case RlSchema.F_ORDER: name = f.C >= 2 ? "order" : null; break;
            case RlSchema.F_DISCARD_FROM: name = "discard-from"; break;
            case RlSchema.F_COST_CARDS: name = "cost-cards"; break;
            case RlSchema.F_PILE: name = "pile"; break;
            case RlSchema.F_PUT_ON_TOP: name = "put-on-top"; break;
            case RlSchema.F_OPTIONAL_TRIGGER: name = "optional-trigger"; break;
            case RlSchema.F_PAY_TO_PREVENT: name = "pay-to-prevent"; break;
            case RlSchema.F_NAME: name = "name"; break;
            case RlSchema.F_COLOR: name = "color"; break;
            case RlSchema.F_SCRY:
            case RlSchema.F_SURVEIL: {
                final String fam = f.family == RlSchema.F_SCRY ? "scry" : "surveil";
                if (f.mode == RlSchema.M_ASSIGN) {
                    if (!kept.containsKey(fam + "-permute")) {
                        pendingAssign.put(f.family, new Object[] {f.gameUid, f.decIdx,
                                RlWire.frameBytes(RlWire.T_DECIDE, 0, RlWire.encodeDecide(f)),
                                describe(f, m, steps, answer, sourced(meta(f, m, seat), fromRecord))});
                    }
                    if (!kept.containsKey(fam + "-assign")) {
                        name = fam + "-assign";
                    }
                } else if (!kept.containsKey(fam + "-permute")) {
                    final Object[] a = pendingAssign.get(f.family);
                    if (a != null && (Long) a[0] == f.gameUid && (Integer) a[1] == f.decIdx - 1) {
                        // both frames of one ask: the pair replaces any lone first frame kept earlier
                        kept.put(fam + "-assign", new Object[] {a[2], a[3]});
                        name = fam + "-permute";
                    }
                }
                break;
            }
            default:
                break;
        }
        if (f.family == RlSchema.F_TARGETS && m != null && "trigger".equals(m.origin) && f.C >= 2) {
            name = kept.containsKey("targets-trigger") ? null : "targets-trigger";
        }
        if (name == null || kept.containsKey(name)) {
            return;
        }
        final byte[] frame = RlWire.frameBytes(RlWire.T_DECIDE, 0, RlWire.encodeDecide(f));
        kept.put(name, new Object[] {frame, describe(f, m, steps, answer, sourced(meta(f, m, seat), fromRecord))});
    }

    static JsonObject sourced(final JsonObject meta, final boolean fromRecord) {
        meta.addProperty("source", fromRecord ? "record (example_steps = Forge's answer)" : "train (fake server)");
        return meta;
    }

    static JsonObject meta(final RlWire.Decide f, final RlCandidates.Menu m, final Player seat) {
        final JsonObject meta = new JsonObject();
        meta.addProperty("family_name", RlSchema.familyName(f.family));
        meta.addProperty("source_is_spell", m.family == RlSchema.F_TARGETS ? m.sourceIsSpell : null);
        meta.addProperty("source_card", m.source == null ? null : m.source.getName());
        meta.addProperty("seat_name", seat.getName());
        meta.addProperty("method", m.method);
        meta.addProperty("ask_kind", m.kind);
        if (m.family == RlSchema.F_TARGETS) {
            meta.addProperty("origin", m.origin);
        }
        return meta;
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
                    : m.shape == RlCandidates.SHAPE_CHOICES ? "choices" : m.shape == RlCandidates.SHAPE_PAIRS ? "pairs"
                    : m.shape == RlCandidates.SHAPE_PERMUTE ? "permute" : "two_frame");
            final JsonArray fr = new JsonArray();
            for (int i = 0; i < m.C(); i++) fr.add(m.fragment(i));
            ans.add("fragments", fr);
            if (m.shape == RlCandidates.SHAPE_CHOICES) {
                ans.addProperty("list_key", m.listKey());
            }
            if (m.shape == RlCandidates.SHAPE_PERMUTE) {
                ans.addProperty("reverse", m.reversed());
            }
            if (m.shape == RlCandidates.SHAPE_TWO_FRAME) {
                ans.addProperty("rest_key", m.restKey());
            }
            ans.add("rule", new com.google.gson.JsonPrimitive("single: fragments[steps[0]]; choices: {list_key: "
                    + "[fragments[s] for s in steps]}; pairs: {\"pairs\": [fragments[s] for s in steps if not null]}; "
                    + "permute (steps = the final top-first order): {\"choices\": [fragments[s] for s in "
                    + "(reversed(steps) if reverse else steps)]}; two_frame (SCRY/SURVEIL first frame, ASSIGN: per slot "
                    + "YES = keep on top, NO = bottom/graveyard; fragments = the slot card's fid): with the second "
                    + "frame's steps p (PERMUTE over the kept cards in slot order; absent when fewer than 2 are kept), "
                    + "{\"top\": [kept[s] for s in p] (else the kept card), \"bottom\"|\"graveyard\": [fid of each NO "
                    + "slot, in slot order]}"));
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
                o.add(c.has("list_key") ? c.get("list_key").getAsString() : "choices", a);
                return o;
            }
            case "two_frame": {
                // this frame alone: the YES slots' cards (top, in slot order) and the NO slots' (rest)
                final JsonArray kinds = golden.getAsJsonObject("arrays").getAsJsonArray("cand_kind");
                final JsonArray top = new JsonArray();
                final JsonArray rest = new JsonArray();
                for (short s : steps) {
                    (kinds.get(s).getAsInt() == RlSchema.K_YES ? top : rest).add(fr.get(s).deepCopy());
                }
                o.add("top", top);
                o.add(c.get("rest_key").getAsString(), rest);
                return o;
            }
            case "permute": {
                final boolean rev = c.has("reverse") && c.get("reverse").getAsBoolean();
                final JsonArray a = new JsonArray();
                for (int k = 0; k < steps.length; k++) a.add(fr.get(steps[rev ? steps.length - 1 - k : k]).deepCopy());
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
