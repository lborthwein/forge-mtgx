package forge.bench.rl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import forge.bench.StateEncoder;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

/**
 * One bridge ask → its candidate arrays, decision mode and answer rule (interfaces.md §2.3, §2.4, §3.5; lane
 * rl-r0-b1-1005; families 8-24 per Appendix B.1, PERMUTE and the SCRY/SURVEIL two-frame asks: lane rl-r0-b4-1006). Built in two steps so that trivial asks never pay for an observation: {@link #build} reads the ask
 * (structure, legality, answer fragments) and {@link Menu#bind} fills the token pointers from an observation.
 *
 * <p>Pointer conventions (documented in the golden {@code .json}): {@code cand_tok} is the candidate's own card
 * token (the host card of a priority entry; the target card or stack item of a TARGETS entry; the attacker / blocker
 * of every entry in its ATTACK / BLOCK slot), {@code cand_card} that card's index; {@code cand_tgt[0]} is what the
 * candidate points at: −2 the seat's own player, −3 the opponent (a TARGETS player candidate, an ATTACK on the
 * opponent), or a token (the attacked planeswalker / battle, the blocked attacker).
 */
public final class RlCandidates {
    private RlCandidates() {
    }

    public static final int SHAPE_SINGLE = 1, SHAPE_CHOICES = 2, SHAPE_PAIRS = 3;
    /** Phase B (lane rl-r0-b4-1006): a full permutation → {@code {"choices": [frag…]}} (ORDER). */
    public static final int SHAPE_PERMUTE = 4;
    /** Phase B: SCRY / SURVEIL, whose answer is composed from two frames ({@link Menu#answerTwoFrame}). */
    public static final int SHAPE_TWO_FRAME = 5;

    /** One candidate before pointer binding. */
    static final class Cand {
        int kind;
        Card host;                 // → cand_tok / cand_card
        SpellAbilityStackInstance hostStack; // → cand_tok (stack) / cand_card (its source)
        Object tgt0;               // Player / Card / null → cand_tgt[0]
        int slot = -1;
        int num = -1;
        int ability;
        int flags;
        JsonElement frag;          // answer fragment; null = NONE in an ASSIGN slot
        String key;                // echo-matching key
        Object tgt1;               // Player / Card / null → cand_tgt[1] when the menu has no source pointer (PILE)
        String name;               // NAME: the card name → cand_card (and cand_tok, a token of that name if any)
        boolean hidden;            // the chooser cannot see this card's face (a face-down pile): cand_card = <unk>
    }

    /** The decision an ask poses. */
    public static final class Menu {
        public int family;
        public int mode;
        public int minPick;
        public int maxPick;
        public int shape;
        public String method;
        public String kind;
        final List<Cand> cands = new ArrayList<>();
        final List<Card> slotCards = new ArrayList<>();
        /** Number of distinct legal answers is exactly one. */
        public boolean trivial;
        /** The unique answer's steps when {@link #trivial}. */
        public short[] trivialSteps;
        /** Mulligan-bottom count, for the {@code mullK} scalar. */
        public int mullK;
        /** Set when the ask cannot be posed (no legal answer, unsupported shape): delegate. */
        public String unposable;
        /** TARGETS (clarification C2): the card whose spell or ability is choosing targets → every cand_tgt[1]. */
        public Card source;
        /** TARGETS: true when the bound source pointer is −1 (Forge exposes no source, or it is not a token). */
        public boolean sourceMissing;
        /** TARGETS: whether the targeting ability belongs to a spell (else a permanent's / card's ability). */
        public boolean sourceIsSpell;
        /** TARGETS: where the targeting comes from (the bridge's "origin": cast, trigger, playFromEffect, noStack). */
        public String origin = "cast";
        /**
         * Record-mode TARGETS synthesis (lane rl-r0-b4b-1006): set when Forge's own targets break the ability's own
         * target count ({@code !isTargetNumberValid()}, the test Forge's stack applies before it accepts the
         * activation). Forge then refuses the whole activation ("Couldn't add to stack, failed to target"), so the
         * answer is outside the legal action space: no label exists, and it is not a mapping failure.
         */
        public String forgeIllegal;
        /**
         * Record-mode TARGETS synthesis: the ability takes no targets on this cast (its target count evaluates to 0,
         * e.g. a kicker-only target of an unkicked spell) but Forge's AI left targets on it. The seat's ask is trivial
         * (min = max = 0), so the row is the trivial empty answer, not a mapping failure.
         */
        public boolean offTargets;
        /** SHAPE_CHOICES: the answer's list key ("choices"; "colors" for chooseColors). */
        String listKey = "choices";
        /** PERMUTE (ORDER): the steps are the final top-first order and the answer is Forge's move order reversed. */
        boolean reverse;
        /** Two-frame families (SCRY, SURVEIL): the key of the cards not kept on top ("bottom" / "graveyard"). */
        String restKey;
        /** Two-frame families: the looked-at cards in Forge's order (the ASSIGN slots). */
        final List<Card> looked = new ArrayList<>();
        /** Two-frame families, second frame: the cards kept on top, in the candidates' order. */
        final List<Card> kept = new ArrayList<>();
        /** PILE (obs-v2 pile-membership facts): the two piles' cards, and whether each is face down to the chooser. */
        public final List<List<Card>> piles = new ArrayList<>();
        public final List<Boolean> pileHidden = new ArrayList<>();

        // bound arrays
        public byte[] kindA;
        public short[] tok;
        public int[] card;
        public short[] tgt;
        public short[] slot;
        public short[] num;
        public byte[] ability;
        public byte[] flagsA;
        public short[] slotTok;

        public int C() {
            return cands.size();
        }

        public int S() {
            return slotCards.size();
        }

        /** Fill the pointer arrays from an observation of the asking seat. */
        public void bind(final RlFeaturizer.Obs o, final RlFeaturizer f, final Player seat) {
            final int c = C();
            final boolean srcFamily = family == RlSchema.F_TARGETS || family == RlSchema.F_ENTITY;
            final int src = srcFamily ? pointer(source, o, seat) : -1;
            sourceMissing = family == RlSchema.F_TARGETS && src < 0;
            kindA = new byte[c];
            tok = new short[c];
            card = new int[c];
            tgt = new short[2 * c];
            slot = new short[c];
            num = new short[c];
            ability = new byte[c];
            flagsA = new byte[c];
            for (int i = 0; i < c; i++) {
                final Cand x = cands.get(i);
                kindA[i] = (byte) x.kind;
                if (x.name != null) {
                    card[i] = nameIndex(f.index(), x.name);
                    tok[i] = (short) tokenOfCard(o, card[i]);
                } else if (x.host != null) {
                    tok[i] = (short) o.pos(x.host);
                    card[i] = x.hidden ? CardIndex.UNK : f.cardIndexOf(x.host);
                } else if (x.hidden) {
                    tok[i] = -1;
                    card[i] = CardIndex.UNK;
                } else if (x.hostStack != null) {
                    final Integer p = o.posByStackId.get(x.hostStack.getId());
                    tok[i] = (short) (p == null ? -1 : p);
                    card[i] = x.hostStack.getSourceCard() == null ? 0 : f.cardIndexOf(x.hostStack.getSourceCard());
                } else {
                    tok[i] = -1;
                    card[i] = 0;
                }
                tgt[2 * i] = (short) pointer(x.tgt0, o, seat);
                tgt[2 * i + 1] = (short) (srcFamily ? src : pointer(x.tgt1, o, seat));
                slot[i] = (short) x.slot;
                num[i] = (short) x.num;
                ability[i] = (byte) Math.max(0, Math.min(15, x.ability));
                if (f.version() == 2 && family == RlSchema.F_PILE) {
                    ability[i] = (byte) i; // obs-v2: the pile index, matching its members' PILE:<i> facts
                }
                flagsA[i] = (byte) x.flags;
            }
            slotTok = new short[S()];
            for (int s = 0; s < S(); s++) {
                slotTok[s] = (short) o.pos(slotCards.get(s));
            }
        }

        /** The steps (§2.4) → the bridge's answer JSON. Steps must have passed {@link #validate}. */
        public JsonObject answer(final short[] steps) {
            final JsonObject o = new JsonObject();
            switch (shape) {
                case SHAPE_SINGLE: {
                    final JsonElement f = cands.get(steps[0]).frag;
                    return f.deepCopy().getAsJsonObject();
                }
                case SHAPE_CHOICES: {
                    final JsonArray a = new JsonArray();
                    for (short s : steps) {
                        a.add(cands.get(s).frag.deepCopy());
                    }
                    o.add(listKey, a);
                    return o;
                }
                case SHAPE_PERMUTE: {
                    final JsonArray a = new JsonArray();
                    for (int k = 0; k < steps.length; k++) {
                        a.add(cands.get(steps[reverse ? steps.length - 1 - k : k]).frag.deepCopy());
                    }
                    o.add("choices", a);
                    return o;
                }
                case SHAPE_PAIRS: {
                    final JsonArray a = new JsonArray();
                    for (short s : steps) {
                        final JsonElement f = cands.get(s).frag;
                        if (f != null) {
                            a.add(f.deepCopy());
                        }
                    }
                    o.add("pairs", a);
                    return o;
                }
                case SHAPE_TWO_FRAME:
                    return answerTwoFrame(steps, null, null); // the first frame alone: kept cards in slot order
                default:
                    throw new IllegalStateException("no answer shape");
            }
        }

        /** Null when the steps are a legal decision of this menu (§2.4, §5.3), else the reason. */
        public String validate(final short[] steps) {
            final int c = C();
            switch (mode) {
                case RlSchema.M_SINGLE:
                    if (steps.length != 1) {
                        return "SINGLE needs 1 step, got " + steps.length;
                    }
                    if (steps[0] < 0 || steps[0] >= c || cands.get(steps[0]).kind <= 0) {
                        return "SINGLE step out of range: " + steps[0];
                    }
                    return null;
                case RlSchema.M_SUBSET: {
                    if (steps.length < minPick || steps.length > maxPick) {
                        return "SUBSET picked " + steps.length + " outside [" + minPick + "," + maxPick + "]";
                    }
                    final boolean[] seen = new boolean[c];
                    for (short s : steps) {
                        if (s < 0 || s >= c || cands.get(s).kind <= 0) {
                            return "SUBSET step out of range: " + s;
                        }
                        if (seen[s]) {
                            return "SUBSET repeats " + s;
                        }
                        seen[s] = true;
                    }
                    return null;
                }
                case RlSchema.M_ASSIGN: {
                    if (steps.length != S()) {
                        return "ASSIGN needs " + S() + " steps, got " + steps.length;
                    }
                    for (int s = 0; s < steps.length; s++) {
                        final int k = steps[s];
                        if (k < 0 || k >= c || cands.get(k).slot != s || cands.get(k).kind <= 0) {
                            return "ASSIGN slot " + s + " got candidate " + k;
                        }
                    }
                    return null;
                }
                case RlSchema.M_PERMUTE: {
                    if (steps.length != c) {
                        return "PERMUTE needs " + c + " steps, got " + steps.length;
                    }
                    final boolean[] seen = new boolean[c];
                    for (short s : steps) {
                        if (s < 0 || s >= c || cands.get(s).kind <= 0 || seen[s]) {
                            return "PERMUTE step out of range or repeated: " + s;
                        }
                        seen[s] = true;
                    }
                    return null;
                }
                default:
                    return "unsupported mode " + mode;
            }
        }

        /** SHAPE_CHOICES' list key (goldens). */
        public String listKey() {
            return listKey;
        }

        /** Two-frame families: the answer key of the cards not kept on top (goldens). */
        public String restKey() {
            return restKey;
        }

        /** SHAPE_PERMUTE: the answer is the steps reversed (goldens). */
        public boolean reversed() {
            return reverse;
        }

        /** Candidate i's answer fragment (goldens). */
        public JsonElement fragment(final int i) {
            final JsonElement f = cands.get(i).frag;
            return f == null ? com.google.gson.JsonNull.INSTANCE : f.deepCopy();
        }

        /**
         * Forge's echoed answer (record mode) → steps in this menu, or null when the menu cannot name it.
         */
        public short[] fromEcho(final JsonObject echo) {
            if (echo == null) {
                return null;
            }
            switch (family) {
                case RlSchema.F_PRIORITY: {
                    if (!echo.has("choice") || echo.get("choice").isJsonNull()) {
                        return null;
                    }
                    final int k = echo.get("choice").getAsInt();
                    final int x = echo.has("x") && !echo.get("x").isJsonNull() ? echo.get("x").getAsInt() : 0;
                    for (int i = 0; i < C(); i++) {
                        final JsonObject f = cands.get(i).frag.getAsJsonObject();
                        if (f.get("choice").getAsInt() != k) {
                            continue;
                        }
                        if (!f.has("x") || f.get("x").getAsInt() == x) {
                            return new short[] {(short) i};
                        }
                    }
                    return null;
                }
                case RlSchema.F_MULLIGAN:
                    return echo.has("keep") ? new short[] {(short) (echo.get("keep").getAsBoolean() ? 0 : 1)} : null;
                case RlSchema.F_START_PLAYER:
                    return echo.has("play") ? new short[] {(short) (echo.get("play").getAsBoolean() ? 0 : 1)} : null;
                case RlSchema.F_MULLIGAN_BOTTOM: {
                    if (!echo.has("choices")) {
                        return null;
                    }
                    final JsonArray a = echo.getAsJsonArray("choices");
                    final short[] out = new short[a.size()];
                    for (int j = 0; j < a.size(); j++) {
                        final int i = indexOfKey("card:" + a.get(j).getAsInt());
                        if (i < 0) {
                            return null;
                        }
                        out[j] = (short) i;
                    }
                    return validate(out) == null ? out : null;
                }
                case RlSchema.F_TARGETS: {
                    if (!echo.has("choices")) {
                        return null;
                    }
                    final JsonArray a = echo.getAsJsonArray("choices");
                    final short[] out = new short[a.size()];
                    for (int j = 0; j < a.size(); j++) {
                        final JsonObject r = a.get(j).getAsJsonObject();
                        final int i = indexOfKey(r.get("kind").getAsString() + ":" + r.get("id").getAsInt());
                        if (i < 0) {
                            return null;
                        }
                        out[j] = (short) i;
                    }
                    return validate(out) == null ? out : null;
                }
                case RlSchema.F_ATTACK:
                case RlSchema.F_BLOCK: {
                    if (!echo.has("pairs")) {
                        return null;
                    }
                    final short[] out = new short[S()];
                    final boolean[] set = new boolean[S()];
                    for (int s = 0; s < S(); s++) {
                        out[s] = (short) noneOf(s);
                    }
                    for (JsonElement pe : echo.getAsJsonArray("pairs")) {
                        final JsonArray p = pe.getAsJsonArray();
                        final int who = p.get(0).getAsInt();
                        int s = -1;
                        for (int k = 0; k < S(); k++) {
                            if (slotCards.get(k).getId() == who) {
                                s = k;
                                break;
                            }
                        }
                        if (s < 0 || set[s]) {
                            return null; // a creature off the menu, or one blocker on two attackers
                        }
                        final String what = family == RlSchema.F_ATTACK ? refKey(p.get(1)) : "card:" + p.get(1).getAsInt();
                        int found = -1;
                        for (int i = 0; i < C(); i++) {
                            final Cand x = cands.get(i);
                            if (x.slot == s && x.key != null && x.key.equals(what)) {
                                found = i;
                                break;
                            }
                        }
                        if (found < 0) {
                            return null;
                        }
                        out[s] = (short) found;
                        set[s] = true;
                    }
                    return out;
                }
                // ---- Phase B (lane rl-r0-b4-1006)
                case RlSchema.F_ENTITY: {
                    if (mode == RlSchema.M_SINGLE) {
                        if (echo.has("none") && !echo.get("none").isJsonNull() && echo.get("none").getAsBoolean()) {
                            return single(indexOfKey("none"));
                        }
                        if (!echo.has("choice") || echo.get("choice").isJsonNull()) {
                            return null;
                        }
                        return single(indexOfKey("i:" + echo.get("choice").getAsInt()));
                    }
                    return listByKey(echo, "choices", "i:");
                }
                case RlSchema.F_CARDS:
                case RlSchema.F_DISCARD_FROM:
                case RlSchema.F_COST_CARDS:
                    return listByKey(echo, "choices", "card:");
                case RlSchema.F_MODE:
                case RlSchema.F_OPTIONAL_COSTS:
                    return listByKey(echo, "choices", "i:");
                case RlSchema.F_CONFIRM:
                case RlSchema.F_PUT_ON_TOP:
                case RlSchema.F_OPTIONAL_TRIGGER:
                case RlSchema.F_PAY_TO_PREVENT:
                    return echo.has("yes") && !echo.get("yes").isJsonNull()
                            ? single(indexOfKey(echo.get("yes").getAsBoolean() ? "yes" : "no")) : null;
                case RlSchema.F_NUMBER:
                    return echo.has("value") && !echo.get("value").isJsonNull()
                            ? single(indexOfKey("v:" + echo.get("value").getAsInt())) : null;
                case RlSchema.F_PILE:
                    return echo.has("pile") && !echo.get("pile").isJsonNull()
                            ? single(indexOfKey("pile:" + echo.get("pile").getAsInt())) : null;
                case RlSchema.F_NAME: {
                    if (!echo.has("name") || echo.get("name").isJsonNull()) {
                        return null;
                    }
                    int i = indexOfKey("name:" + echo.get("name").getAsString());
                    if (i < 0) {
                        i = indexOfKey("forge"); // a name off this menu is what "Forge's choice" means
                    }
                    return single(i);
                }
                case RlSchema.F_COLOR:
                    if (mode == RlSchema.M_SINGLE) {
                        return echo.has("color") && !echo.get("color").isJsonNull()
                                ? single(indexOfKey("c:" + echo.get("color").getAsInt())) : null;
                    }
                    return listByKey(echo, "colors", "c:");
                case RlSchema.F_ORDER: {
                    final short[] mv = keysOf(echo, "choices", "card:");
                    if (mv == null || mv.length != C()) {
                        return null;
                    }
                    final short[] out = new short[mv.length];
                    for (int k = 0; k < mv.length; k++) {
                        out[k] = mv[reverse ? mv.length - 1 - k : k];
                    }
                    return validate(out) == null ? out : null;
                }
                default:
                    return null;
            }
        }

        private short[] single(final int i) {
            if (i < 0) {
                return null;
            }
            final short[] out = {(short) i};
            return validate(out) == null ? out : null;
        }

        /** The candidates whose keys are {@code prefix + element} for each element of {@code echo[key]}, in order. */
        private short[] keysOf(final JsonObject echo, final String key, final String prefix) {
            if (!echo.has(key) || !echo.get(key).isJsonArray()) {
                return null;
            }
            final JsonArray a = echo.getAsJsonArray(key);
            final short[] out = new short[a.size()];
            for (int j = 0; j < a.size(); j++) {
                final JsonElement e = a.get(j);
                final int i = indexOfKey(prefix + (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                        ? String.valueOf(e.getAsInt()) : e.getAsString()));
                if (i < 0) {
                    return null;
                }
                out[j] = (short) i;
            }
            return out;
        }

        private short[] listByKey(final JsonObject echo, final String key, final String prefix) {
            final short[] out = keysOf(echo, key, prefix);
            return out != null && validate(out) == null ? out : null;
        }

        // ---- two-frame families (SCRY, SURVEIL)

        /**
         * The second frame of a two-frame ask: PERMUTE over the cards the first frame's steps keep on top (in the
         * looked-at order), or null when fewer than two are kept (no second frame).
         */
        public Menu secondFrame(final short[] steps1) {
            final List<Card> keep = keptBy(steps1);
            if (keep.size() < 2) {
                return null;
            }
            final Menu p = new Menu();
            p.family = family;
            p.method = method;
            p.kind = kind;
            p.mode = RlSchema.M_PERMUTE;
            p.shape = SHAPE_PERMUTE;
            p.restKey = restKey;
            for (Card c : keep) {
                final Cand x = new Cand();
                x.kind = RlSchema.K_CARD;
                x.host = c;
                x.frag = new JsonPrimitive(c.getId());
                x.key = "card:" + c.getId();
                p.cands.add(x);
                p.kept.add(c);
            }
            finish(p);
            return p;
        }

        private List<Card> keptBy(final short[] steps1) {
            final List<Card> keep = new ArrayList<>();
            for (int s = 0; s < looked.size() && s < steps1.length; s++) {
                if (cands.get(steps1[s]).kind == RlSchema.K_YES) {
                    keep.add(looked.get(s));
                }
            }
            return keep;
        }

        /** The answer of a two-frame ask: {@code {top: [fid…] (top first), <rest>: [fid…]}}. */
        public JsonObject answerTwoFrame(final short[] steps1, final Menu second, final short[] steps2) {
            final JsonObject o = new JsonObject();
            final JsonArray top = new JsonArray();
            final JsonArray rest = new JsonArray();
            if (second == null) {
                for (Card c : keptBy(steps1)) {
                    top.add(c.getId());
                }
            } else {
                for (short k : steps2) {
                    top.add(second.kept.get(k).getId());
                }
            }
            for (int s = 0; s < looked.size(); s++) {
                if (cands.get(steps1[s]).kind != RlSchema.K_YES) {
                    rest.add(looked.get(s).getId());
                }
            }
            o.add("top", top);
            o.add(restKey, rest);
            return o;
        }

        /**
         * Record mode: Forge's {@code {top, <rest>}} → {steps1, second frame or null, steps2 or null}, or null when the
         * answer does not partition the looked-at cards.
         */
        public Object[] fromEchoTwoFrame(final JsonObject echo) {
            if (echo == null || !echo.has("top") || !echo.has(restKey)) {
                return null;
            }
            final List<Integer> top = new ArrayList<>();
            for (JsonElement e : echo.getAsJsonArray("top")) {
                top.add(e.getAsInt());
            }
            int restN = echo.getAsJsonArray(restKey).size();
            if (top.size() + restN != looked.size()) {
                return null;
            }
            final short[] steps1 = new short[looked.size()];
            for (int s = 0; s < looked.size(); s++) {
                final boolean onTop = top.contains(looked.get(s).getId());
                int found = -1;
                for (int i = 0; i < C(); i++) {
                    final Cand x = cands.get(i);
                    if (x.slot == s && x.kind == (onTop ? RlSchema.K_YES : RlSchema.K_NO)) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) {
                    return null;
                }
                steps1[s] = (short) found;
            }
            if (validate(steps1) != null || keptBy(steps1).size() != top.size()) {
                return null;
            }
            final Menu second = secondFrame(steps1);
            short[] steps2 = null;
            if (second != null) {
                steps2 = new short[top.size()];
                for (int k = 0; k < top.size(); k++) {
                    int at = -1;
                    for (int i = 0; i < second.kept.size(); i++) {
                        if (second.kept.get(i).getId() == top.get(k)) {
                            at = i;
                        }
                    }
                    if (at < 0) {
                        return null;
                    }
                    steps2[k] = (short) at;
                }
                if (second.validate(steps2) != null) {
                    return null;
                }
            }
            return new Object[] {steps1, second, steps2};
        }

        private int indexOfKey(final String key) {
            for (int i = 0; i < C(); i++) {
                if (key.equals(cands.get(i).key)) {
                    return i;
                }
            }
            return -1;
        }

        private int noneOf(final int s) {
            for (int i = 0; i < C(); i++) {
                if (cands.get(i).slot == s && cands.get(i).kind == RlSchema.K_NONE) {
                    return i;
                }
            }
            return -1;
        }
    }

    static String refKey(final JsonElement ref) {
        if (ref.isJsonObject()) {
            final JsonObject r = ref.getAsJsonObject();
            return r.get("kind").getAsString() + ":" + r.get("id").getAsInt();
        }
        return "card:" + ref.getAsInt();
    }

    private static int pointer(final Object t, final RlFeaturizer.Obs o, final Player seat) {
        if (t == null) {
            return -1;
        }
        if (t instanceof Player) {
            return t == seat ? -2 : -3;
        }
        if (t instanceof Card) {
            return o.pos((Card) t);
        }
        if (t instanceof SpellAbilityStackInstance) {
            final Integer p = o.posByStackId.get(((SpellAbilityStackInstance) t).getId());
            return p == null ? -1 : p;
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------------ build

    /**
     * The menu of one ask (the 7-argument form without a seen-cards set).
     */
    public static Menu build(final Game game, final Player seat, final String method, final String kind,
            final JsonObject body, final Object objs) {
        return build(game, seat, method, kind, body, objs, null);
    }

    /**
     * The menu of one ask of any family 1–24 ({@code seen}: the opponent cards the seat has seen, by name, in
     * first-seen order, for NAME; may be null), or null for a kind no family names.
     */
    public static Menu build(final Game game, final Player seat, final String method, final String kind,
            final JsonObject body, final Object objs, final List<String> seen) {
        final int family = RlSchema.familyOf(kind, method);
        if (!RlSchema.isLearned(family)) {
            return null;
        }
        final Menu m = new Menu();
        m.family = family;
        m.method = method;
        m.kind = kind;
        try {
            switch (family) {
                case RlSchema.F_PRIORITY:
                    priority(m, body, objs);
                    break;
                case RlSchema.F_TARGETS:
                    targets(m, game, body, objs);
                    break;
                case RlSchema.F_ATTACK:
                    attack(m, body, objs);
                    break;
                case RlSchema.F_BLOCK:
                    block(m, body, objs);
                    break;
                case RlSchema.F_MULLIGAN:
                    binary(m, RlSchema.K_KEEP, RlSchema.K_MULLIGAN, "keep");
                    break;
                case RlSchema.F_START_PLAYER:
                    binary(m, RlSchema.K_PLAY_FIRST, RlSchema.K_DRAW_FIRST, "play");
                    break;
                case RlSchema.F_MULLIGAN_BOTTOM:
                    bottom(m, body, objs);
                    break;
                // ---- Phase B (lane rl-r0-b4-1006; interfaces Appendix B.1)
                case RlSchema.F_ENTITY:
                    entity(m, body, objs);
                    break;
                case RlSchema.F_CARDS:
                case RlSchema.F_DISCARD_FROM:
                case RlSchema.F_COST_CARDS:
                    cards(m, body, objs);
                    break;
                case RlSchema.F_MODE:
                    if ("chooseModeForAbility".equals(m.method)) {
                        modes(m, body, objs);
                    } else {
                        options(m, objs);
                    }
                    break;
                case RlSchema.F_CONFIRM: {
                    final Object[] o = arr(objs, 2);
                    final SpellAbility sa = (SpellAbility) o[0];
                    final Card shown = (Card) o[1];
                    yesNo(m, shown != null ? shown : sa == null ? null : sa.getHostCard(), true, true, -1);
                    break;
                }
                case RlSchema.F_NUMBER:
                    number(m, body, objs);
                    break;
                case RlSchema.F_OPTIONAL_COSTS:
                    optionalCosts(m, objs);
                    break;
                case RlSchema.F_SCRY:
                case RlSchema.F_SURVEIL:
                    look(m, objs);
                    break;
                case RlSchema.F_ORDER:
                    order(m, objs);
                    break;
                case RlSchema.F_PILE:
                    pile(m, objs);
                    break;
                case RlSchema.F_PUT_ON_TOP:
                    yesNo(m, (Card) objs, true, true, -1);
                    break;
                case RlSchema.F_OPTIONAL_TRIGGER: {
                    final Object[] o = arr(objs, 2);
                    final SpellAbility w = (SpellAbility) o[0];
                    yesNo(m, w == null ? null : w.getHostCard(), true, !Boolean.TRUE.equals(o[1]), -1);
                    break;
                }
                case RlSchema.F_PAY_TO_PREVENT: {
                    final Object[] o = arr(objs, 4);
                    final SpellAbility sa = (SpellAbility) o[1];
                    yesNo(m, sa == null ? null : sa.getHostCard(), Boolean.TRUE.equals(o[2]), true,
                            o[3] instanceof Integer ? (Integer) o[3] : -1);
                    break;
                }
                case RlSchema.F_NAME:
                    name(m, seat, objs, seen);
                    break;
                case RlSchema.F_COLOR:
                    color(m, objs);
                    break;
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            m.unposable = "menu build failed: " + e;
        }
        if (m.unposable == null) {
            finish(m);
        }
        return m;
    }

    private static void finish(final Menu m) {
        final int c = m.C();
        switch (m.mode) {
            case RlSchema.M_SINGLE: {
                int legal = 0, only = -1;
                for (int i = 0; i < c; i++) {
                    if (m.cands.get(i).kind > 0) {
                        legal++;
                        only = i;
                    }
                }
                if (legal == 0) {
                    m.unposable = "no legal candidate";
                } else if (legal == 1) {
                    m.trivial = true;
                    m.trivialSteps = new short[] {(short) only};
                }
                break;
            }
            case RlSchema.M_SUBSET: {
                if (m.minPick > c || m.minPick > m.maxPick) {
                    m.unposable = "SUBSET min " + m.minPick + " exceeds " + c + " candidates / max " + m.maxPick;
                } else if (m.maxPick == 0) {
                    m.trivial = true;
                    m.trivialSteps = new short[0];
                } else if (m.minPick == c) {
                    m.trivial = true;
                    m.trivialSteps = new short[c];
                    for (int i = 0; i < c; i++) {
                        m.trivialSteps[i] = (short) i;
                    }
                }
                break;
            }
            case RlSchema.M_ASSIGN: {
                boolean forced = true;
                final short[] st = new short[m.S()];
                for (int s = 0; s < m.S(); s++) {
                    int n = 0;
                    for (int i = 0; i < c; i++) {
                        if (m.cands.get(i).slot == s) {
                            n++;
                            st[s] = (short) i;
                        }
                    }
                    if (n != 1) {
                        forced = false;
                    }
                }
                if (forced) {
                    m.trivial = true;
                    m.trivialSteps = st;
                }
                m.minPick = m.S();
                m.maxPick = m.S();
                break;
            }
            case RlSchema.M_PERMUTE: {
                if (c == 0) {
                    m.unposable = "PERMUTE over nothing";
                } else if (c == 1) {
                    m.trivial = true;
                    m.trivialSteps = new short[] {0};
                }
                m.minPick = c;
                m.maxPick = c;
                break;
            }
            default:
                m.unposable = "mode " + m.mode;
        }
    }

    private static void binary(final Menu m, final int k0, final int k1, final String key) {
        m.mode = RlSchema.M_SINGLE;
        m.shape = SHAPE_SINGLE;
        m.minPick = 1;
        m.maxPick = 1;
        final Cand a = new Cand();
        a.kind = k0;
        a.frag = flag(key, true);
        final Cand b = new Cand();
        b.kind = k1;
        b.frag = flag(key, false);
        m.cands.add(a);
        m.cands.add(b);
    }

    private static JsonObject flag(final String key, final boolean v) {
        final JsonObject o = new JsonObject();
        o.addProperty(key, v);
        return o;
    }

    @SuppressWarnings("unchecked")
    private static void priority(final Menu m, final JsonObject body, final Object objs) {
        m.mode = RlSchema.M_SINGLE;
        m.shape = SHAPE_SINGLE;
        m.minPick = 1;
        m.maxPick = 1;
        if (!(objs instanceof List)) {
            m.unposable = "priority ask without its menu objects";
            return;
        }
        final List<SpellAbility> menu = (List<SpellAbility>) objs;
        final JsonArray items = body.getAsJsonArray("menu");
        final Cand pass = new Cand();
        pass.kind = RlSchema.K_PASS;
        pass.frag = choice(0, -1);
        m.cands.add(pass);
        for (int i = 0; i < menu.size(); i++) {
            final SpellAbility sa = menu.get(i);
            final JsonObject item = items != null && items.size() > i + 1 ? items.get(i + 1).getAsJsonObject() : null;
            final Card host = sa.getHostCard();
            final int kind = sa.isLandAbility() ? RlSchema.K_LAND : sa.isSpell() ? RlSchema.K_CAST : RlSchema.K_ACTIVATE;
            int flags = 0;
            try {
                if (sa.getAlternativeCost() != null) {
                    flags |= 1;
                }
                if (sa.getOptionalCosts().iterator().hasNext()) {
                    flags |= 2;
                }
            } catch (RuntimeException e) {
                // flags are hints
            }
            boolean hasX = false;
            int maxX = 0;
            if (item != null && item.has("x") && item.get("x").isJsonObject()) {
                final JsonObject x = item.getAsJsonObject("x");
                hasX = x.has("has") && x.get("has").getAsBoolean();
                if (hasX) {
                    maxX = x.has("maxAnnounce") ? Math.max(0, x.get("maxAnnounce").getAsInt()) : 0;
                }
            }
            if (hasX) {
                flags |= 4;
            }
            if (host != null && host.getZone() != null) {
                final ZoneType z = host.getZone().getZoneType();
                if (z == ZoneType.Graveyard || z == ZoneType.Exile || z == ZoneType.Library || z == ZoneType.Command) {
                    flags |= 8;
                }
            }
            final int ability = abilityIndex(host, sa);
            if (hasX) {
                final int top = Math.min(maxX, RlSchema.X_MAX);
                for (int v = 0; v <= top; v++) {
                    final Cand c = new Cand();
                    c.kind = kind;
                    c.host = host;
                    c.num = v;
                    c.ability = ability;
                    c.flags = flags;
                    c.frag = choice(i + 1, v);
                    m.cands.add(c);
                }
            } else {
                final Cand c = new Cand();
                c.kind = kind;
                c.host = host;
                c.ability = ability;
                c.flags = flags;
                c.frag = choice(i + 1, -1);
                m.cands.add(c);
            }
        }
    }

    private static JsonObject choice(final int k, final int x) {
        final JsonObject o = new JsonObject();
        o.addProperty("choice", k);
        if (x >= 0) {
            o.addProperty("x", x);
        }
        return o;
    }

    /** The index of the ability among its host's abilities (identity, then description); 0 if unknown. */
    static int abilityIndex(final Card host, final SpellAbility sa) {
        if (host == null || sa == null) {
            return 0;
        }
        try {
            int i = 0;
            String desc = null;
            int byDesc = -1;
            for (SpellAbility h : host.getSpellAbilities()) {
                if (h == sa) {
                    return i;
                }
                if (byDesc < 0) {
                    if (desc == null) {
                        desc = String.valueOf(sa.getDescription());
                    }
                    if (desc.equals(String.valueOf(h.getDescription()))) {
                        byDesc = i;
                    }
                }
                i++;
            }
            return Math.max(0, byDesc);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static void targets(final Menu m, final Game game, final JsonObject body, final Object objs) {
        m.mode = RlSchema.M_SUBSET;
        m.shape = SHAPE_CHOICES;
        if (!(objs instanceof Object[]) || ((Object[]) objs).length < 3) {
            m.unposable = "targets ask without its menu objects";
            return;
        }
        final Object[] o = (Object[]) objs;
        final List<GameEntity> ents = (List<GameEntity>) o[1];
        final List<SpellAbilityStackInstance> stack = (List<SpellAbilityStackInstance>) o[2];
        m.source = o[0] instanceof SpellAbility ? ((SpellAbility) o[0]).getHostCard() : null;
        m.sourceIsSpell = o[0] instanceof SpellAbility && ((SpellAbility) o[0]).getRootAbility().isSpell();
        if (body.has("origin") && !body.get("origin").isJsonNull()) {
            m.origin = body.get("origin").getAsString();
        }
        addTargets(m, ents, stack);
        final int min = body.has("min") ? body.get("min").getAsInt() : 1;
        final int max = body.has("max") ? body.get("max").getAsInt() : 1;
        m.minPick = Math.max(0, min);
        m.maxPick = Math.min(Math.max(0, max), m.C());
    }

    private static void addTargets(final Menu m, final List<? extends GameEntity> ents,
            final List<SpellAbilityStackInstance> stack) {
        for (GameEntity ge : ents) {
            final Cand c = new Cand();
            if (ge instanceof Player) {
                c.kind = RlSchema.K_PLAYER;
                c.tgt0 = ge;
                c.frag = ref("player", ge.getId());
                c.key = "player:" + ge.getId();
            } else if (ge instanceof Card) {
                c.kind = RlSchema.K_CARD;
                c.host = (Card) ge;
                c.frag = ref("card", ge.getId());
                c.key = "card:" + ge.getId();
            } else {
                continue;
            }
            m.cands.add(c);
        }
        if (stack != null) {
            for (SpellAbilityStackInstance si : stack) {
                final Cand c = new Cand();
                c.kind = RlSchema.K_STACK;
                c.hostStack = si;
                final int id = StateEncoder.SPELL_TARGET_ID_BASE + si.getId();
                c.frag = ref("spell", id);
                c.key = "spell:" + id;
                m.cands.add(c);
            }
        }
    }

    private static JsonObject ref(final String kind, final int id) {
        final JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("id", id);
        return o;
    }

    /** Canonical slot order: power descending, toughness descending, Forge id ascending (ids sort only). */
    static final Comparator<Card> CANONICAL = (a, b) -> {
        if (a.getNetPower() != b.getNetPower()) {
            return Integer.compare(b.getNetPower(), a.getNetPower());
        }
        if (a.getNetToughness() != b.getNetToughness()) {
            return Integer.compare(b.getNetToughness(), a.getNetToughness());
        }
        return Integer.compare(a.getId(), b.getId());
    };

    @SuppressWarnings("unchecked")
    private static void attack(final Menu m, final JsonObject body, final Object objs) {
        m.mode = RlSchema.M_ASSIGN;
        m.shape = SHAPE_PAIRS;
        if (!(objs instanceof Object[]) || ((Object[]) objs).length < 2) {
            m.unposable = "attackers ask without its menu objects";
            return;
        }
        final Object[] o = (Object[]) objs;
        final List<Card> possible = new ArrayList<>((List<Card>) o[0]);
        final List<GameEntity> defenders = (List<GameEntity>) o[1];
        possible.sort(CANONICAL);
        final JsonObject legal = body.getAsJsonObject("legalPairsTyped");
        final Map<String, GameEntity> byKey = new HashMap<>();
        for (GameEntity d : defenders) {
            byKey.put((d instanceof Player ? "player:" : "card:") + d.getId(), d);
        }
        for (int s = 0; s < possible.size(); s++) {
            final Card a = possible.get(s);
            m.slotCards.add(a);
            final Cand none = new Cand();
            none.kind = RlSchema.K_NONE;
            none.host = a;
            none.slot = s;
            none.key = "none";
            m.cands.add(none);
            final JsonArray defs = legal == null ? null : legal.getAsJsonArray(String.valueOf(a.getId()));
            if (defs == null) {
                continue;
            }
            for (JsonElement de : defs) {
                final String key = refKey(de);
                final GameEntity d = byKey.get(key);
                if (d == null) {
                    continue;
                }
                final Cand c = new Cand();
                c.kind = RlSchema.K_DEFENDER;
                c.host = a;
                c.tgt0 = d;
                c.slot = s;
                final JsonArray pr = new JsonArray();
                pr.add(a.getId());
                pr.add(StateEncoder.entityRef(d));
                c.frag = pr;
                c.key = key;
                m.cands.add(c);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void block(final Menu m, final JsonObject body, final Object objs) {
        m.mode = RlSchema.M_ASSIGN;
        m.shape = SHAPE_PAIRS;
        if (!(objs instanceof Object[]) || ((Object[]) objs).length < 2) {
            m.unposable = "blockers ask without its menu objects";
            return;
        }
        final Object[] o = (Object[]) objs;
        final List<Card> possible = new ArrayList<>((List<Card>) o[0]);
        final List<Card> attackers = (List<Card>) o[1];
        possible.sort(CANONICAL);
        final JsonObject legal = body.getAsJsonObject("legalPairs");
        final Map<Integer, Card> byId = new HashMap<>();
        for (Card a : attackers) {
            byId.put(a.getId(), a);
        }
        for (int s = 0; s < possible.size(); s++) {
            final Card b = possible.get(s);
            m.slotCards.add(b);
            final Cand none = new Cand();
            none.kind = RlSchema.K_NONE;
            none.host = b;
            none.slot = s;
            none.key = "none";
            m.cands.add(none);
            final JsonArray atk = legal == null ? null : legal.getAsJsonArray(String.valueOf(b.getId()));
            if (atk == null) {
                continue;
            }
            for (JsonElement ae : atk) {
                final Card a = byId.get(ae.getAsInt());
                if (a == null) {
                    continue;
                }
                final Cand c = new Cand();
                c.kind = RlSchema.K_ATTACKER;
                c.host = b;
                c.tgt0 = a;
                c.slot = s;
                final JsonArray pr = new JsonArray();
                pr.add(b.getId());
                pr.add(a.getId());
                c.frag = pr;
                c.key = "card:" + a.getId();
                m.cands.add(c);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void bottom(final Menu m, final JsonObject body, final Object objs) {
        m.mode = RlSchema.M_SUBSET;
        m.shape = SHAPE_CHOICES;
        if (!(objs instanceof Iterable)) {
            m.unposable = "cardsChoice ask without its pool";
            return;
        }
        for (Card c : (Iterable<Card>) objs) {
            final Cand x = new Cand();
            x.kind = RlSchema.K_CARD;
            x.host = c;
            x.frag = new JsonPrimitive(c.getId());
            x.key = "card:" + c.getId();
            m.cands.add(x);
        }
        final int min = body.has("min") ? body.get("min").getAsInt() : 0;
        final int max = body.has("max") ? body.get("max").getAsInt() : min;
        m.minPick = Math.max(0, min);
        m.maxPick = Math.min(Math.max(0, max), m.C());
        m.mullK = m.minPick;
    }

    // ------------------------------------------------------------------------------------------------ Phase B builders
    // lane rl-r0-b4-1006; interfaces.md Appendix B.1. Keys (echo matching): "i:<index>" (index-answered menus),
    // "card:<fid>", "v:<value>", "pile:<p>", "name:<name>", "c:<colour>", "yes"/"no", "none", "forge".

    private static Object[] arr(final Object objs, final int n) {
        if (!(objs instanceof Object[]) || ((Object[]) objs).length < n) {
            throw new IllegalArgumentException("ask without its menu objects");
        }
        return (Object[]) objs;
    }

    private static JsonObject kv(final String key, final JsonElement v) {
        final JsonObject o = new JsonObject();
        o.add(key, v);
        return o;
    }

    /**
     * A NAME candidate's token: a zone 16-18 token showing card index {@code idx} (a hidden card the seat knows: the
     * opponent's known hand, known library positions; observation v1), else the first token showing it, else -1.
     */
    static int tokenOfCard(final RlFeaturizer.Obs o, final int idx) {
        if (idx <= CardIndex.UNK) {
            return -1;
        }
        int first = -1;
        for (int i = 0; i < o.L; i++) {
            if (o.tokCard[i] != idx) {
                continue;
            }
            final int z = o.tokZone[i] & 0xff;
            if (z >= RlSchema.Z_O_HAND_KNOWN && z <= RlSchema.Z_O_LIB_KNOWN) {
                return i;
            }
            if (first < 0) {
                first = i;
            }
        }
        return first;
    }

    /**
     * A NAME candidate's card index (clarification C3): the name's own entry, else the full card's when the name is one
     * face of a multi-face card (Forge's card database maps every face name to its full card), else {@code <unk>}.
     */
    public static int nameIndex(final CardIndex index, final String name) {
        final int r = index.resolve(name);
        if (r != CardIndex.UNK) {
            return r;
        }
        try {
            final forge.item.PaperCard pc = forge.StaticData.instance().getCommonCards().getCard(name);
            if (pc != null && !name.equals(pc.getName())) {
                return index.resolve(pc.getName());
            }
        } catch (RuntimeException e) {
            // no card database (unit tests): the name's own entry stands
        }
        return CardIndex.UNK;
    }

    /** SINGLE over [YES, NO] (either may be illegal: absent), {@code host} the card the question is about. */
    private static void yesNo(final Menu m, final Card host, final boolean yes, final boolean no, final int num) {
        m.mode = RlSchema.M_SINGLE;
        m.shape = SHAPE_SINGLE;
        m.minPick = 1;
        m.maxPick = 1;
        if (yes) {
            final Cand y = new Cand();
            y.kind = RlSchema.K_YES;
            y.host = host;
            y.num = num;
            y.frag = flag("yes", true);
            y.key = "yes";
            m.cands.add(y);
        }
        if (no) {
            final Cand n = new Cand();
            n.kind = RlSchema.K_NO;
            n.host = host;
            n.frag = flag("yes", false);
            n.key = "no";
            m.cands.add(n);
        }
    }

    /** 8 ENTITY: chooseSingleEntityForEffect (SINGLE, NONE when optional) / chooseEntitiesForEffect (SUBSET). */
    @SuppressWarnings("unchecked")
    private static void entity(final Menu m, final JsonObject body, final Object objs) {
        final Object[] o = arr(objs, 3);
        final List<? extends GameEntity> options = (List<? extends GameEntity>) o[0];
        final SpellAbility sa = (SpellAbility) o[1];
        final boolean optional = Boolean.TRUE.equals(o[2]);
        final boolean single = "chooseSingleEntityForEffect".equals(m.method);
        m.source = sa == null ? null : sa.getHostCard();
        for (int i = 0; i < options.size(); i++) {
            final GameEntity ge = options.get(i);
            final Cand c = new Cand();
            if (ge instanceof Player) {
                c.kind = RlSchema.K_PLAYER;
                c.tgt0 = ge;
            } else {
                c.kind = RlSchema.K_CARD;
                c.host = ge instanceof Card ? (Card) ge : null;
            }
            c.frag = single ? kv("choice", new JsonPrimitive(i)) : new JsonPrimitive(i);
            c.key = "i:" + i;
            m.cands.add(c);
        }
        if (single) {
            if (optional) {
                final Cand none = new Cand();
                none.kind = RlSchema.K_NONE;
                none.frag = flag("none", true);
                none.key = "none";
                m.cands.add(none);
            }
            m.mode = RlSchema.M_SINGLE;
            m.shape = SHAPE_SINGLE;
            m.minPick = 1;
            m.maxPick = 1;
        } else {
            m.mode = RlSchema.M_SUBSET;
            m.shape = SHAPE_CHOICES;
            final int min = body.has("min") ? body.get("min").getAsInt() : 0;
            final int max = body.has("max") ? body.get("max").getAsInt() : options.size();
            m.minPick = Math.max(0, min);
            m.maxPick = Math.min(Math.max(0, max), m.C());
        }
    }

    /** 9 CARDS, 16 DISCARD_FROM, 17 COST_CARDS: SUBSET over a card pool, answered by fids. */
    @SuppressWarnings("unchecked")
    private static void cards(final Menu m, final JsonObject body, final Object objs) {
        final Object pool = objs instanceof Object[] ? ((Object[]) objs)[0] : objs;
        if (!(pool instanceof Iterable)) {
            m.unposable = m.kind + " ask without its pool";
            return;
        }
        m.mode = RlSchema.M_SUBSET;
        m.shape = SHAPE_CHOICES;
        for (Card c : (Iterable<Card>) pool) {
            final Cand x = new Cand();
            x.kind = RlSchema.K_CARD;
            x.host = c;
            x.frag = new JsonPrimitive(c.getId());
            x.key = "card:" + c.getId();
            m.cands.add(x);
        }
        final int min = body.has("min") ? body.get("min").getAsInt() : 0;
        final int max = body.has("max") ? body.get("max").getAsInt() : min;
        m.minPick = Math.max(0, min);
        m.maxPick = Math.min(Math.max(0, max), m.C());
    }

    /** 10 MODE: SUBSET (min, num) over the modes; cand_num = cand_ability = the mode index, the host card. */
    @SuppressWarnings("unchecked")
    private static void modes(final Menu m, final JsonObject body, final Object objs) {
        final Object[] o = arr(objs, 2);
        final SpellAbility sa = (SpellAbility) o[0];
        final List<? extends SpellAbility> possible = (List<? extends SpellAbility>) o[1];
        m.mode = RlSchema.M_SUBSET;
        m.shape = SHAPE_CHOICES;
        final Card host = sa == null ? null : sa.getHostCard();
        for (int i = 0; i < possible.size(); i++) {
            final Cand c = new Cand();
            c.kind = RlSchema.K_MODE;
            c.host = host;
            c.num = i;
            c.ability = i;
            c.frag = new JsonPrimitive(i);
            c.key = "i:" + i;
            m.cands.add(c);
        }
        final int min = body.has("min") ? body.get("min").getAsInt() : 1;
        final int num = body.has("num") ? body.get("num").getAsInt() : 1;
        m.minPick = Math.max(0, min);
        m.maxPick = Math.min(Math.max(0, num), m.C());
    }

    /**
     * 10 MODE for the other strategic option asks (ICR B4-strategic-leftovers, ruled 10-05): protection type, pump
     * keyword, a spell for an effect, a card face, the ability to cast from an effect, generic spell-ability choices.
     * SUBSET (min, max from the ask; min = max = 1 for single choices); cand_num = cand_ability = the option index;
     * cand_tok / cand_card = the option's host card (a spell ability), its name (a card face), else the source's host.
     */
    @SuppressWarnings("unchecked")
    private static void options(final Menu m, final Object objs) {
        final Object[] o = arr(objs, 4);
        final SpellAbility sa = (SpellAbility) o[0];
        final List<?> opts = (List<?>) o[1];
        m.mode = RlSchema.M_SUBSET;
        m.shape = SHAPE_CHOICES;
        final Card src = sa == null ? null : sa.getHostCard();
        for (int i = 0; i < opts.size(); i++) {
            final Object x = opts.get(i);
            final Cand c = new Cand();
            c.kind = RlSchema.K_MODE;
            if (x instanceof SpellAbility) {
                c.host = ((SpellAbility) x).getHostCard();
            } else if (x instanceof forge.card.ICardFace) {
                c.name = ((forge.card.ICardFace) x).getName();
            } else {
                c.host = src;
            }
            c.num = i;
            c.ability = i;
            c.frag = new JsonPrimitive(i);
            c.key = "i:" + i;
            m.cands.add(c);
        }
        m.minPick = Math.max(0, (Integer) o[2]);
        m.maxPick = Math.min(Math.max(0, (Integer) o[3]), m.C());
    }

    /**
     * 12 NUMBER: SINGLE over the allowed values ascending (cand_num = the value). Over C_MAX values: C_MAX evenly
     * spaced, min and max included.
     */
    private static void number(final Menu m, final JsonObject body, final Object objs) {
        m.mode = RlSchema.M_SINGLE;
        m.shape = SHAPE_SINGLE;
        m.minPick = 1;
        m.maxPick = 1;
        final Card host = objs instanceof SpellAbility ? ((SpellAbility) objs).getHostCard() : null;
        final java.util.TreeSet<Integer> vals = new java.util.TreeSet<>();
        if (body.has("values") && body.get("values").isJsonArray()) {
            final List<Integer> all = new ArrayList<>();
            for (JsonElement e : body.getAsJsonArray("values")) {
                all.add(e.getAsInt());
            }
            final List<Integer> distinct = new ArrayList<>(new java.util.TreeSet<>(all));
            final int n = distinct.size();
            if (n <= RlSchema.C_MAX) {
                vals.addAll(distinct);
            } else {
                for (int k = 0; k < RlSchema.C_MAX; k++) {
                    vals.add(distinct.get((int) Math.round(k * (n - 1) / (double) (RlSchema.C_MAX - 1))));
                }
            }
        } else {
            final long lo = body.has("min") ? body.get("min").getAsLong() : 0;
            final long hi = body.has("max") ? body.get("max").getAsLong() : lo;
            if (hi < lo) {
                m.unposable = "number range [" + lo + "," + hi + "]";
                return;
            }
            if (hi - lo + 1 <= RlSchema.C_MAX) {
                for (long v = lo; v <= hi; v++) {
                    vals.add((int) v);
                }
            } else {
                for (int k = 0; k < RlSchema.C_MAX; k++) {
                    vals.add((int) (lo + Math.round(k * (hi - lo) / (double) (RlSchema.C_MAX - 1))));
                }
            }
        }
        for (int v : vals) {
            final Cand c = new Cand();
            c.kind = RlSchema.K_NUMBER;
            c.host = host;
            c.num = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
            c.frag = kv("value", new JsonPrimitive(v));
            c.key = "v:" + v;
            m.cands.add(c);
        }
    }

    /** 13 OPTIONAL_COSTS: SUBSET 0..n; cand_num = the cost index, cand_flags bit1 = a kicker. */
    @SuppressWarnings("unchecked")
    private static void optionalCosts(final Menu m, final Object objs) {
        final Object[] o = arr(objs, 2);
        final SpellAbility sa = (SpellAbility) o[0];
        final List<forge.game.spellability.OptionalCostValue> costs =
                (List<forge.game.spellability.OptionalCostValue>) o[1];
        m.mode = RlSchema.M_SUBSET;
        m.shape = SHAPE_CHOICES;
        final Card host = sa == null ? null : sa.getHostCard();
        for (int i = 0; i < costs.size(); i++) {
            final Cand c = new Cand();
            c.kind = RlSchema.K_MODE;
            c.host = host;
            c.num = i;
            final forge.game.spellability.OptionalCost t = costs.get(i).getType();
            if (t == forge.game.spellability.OptionalCost.Kicker1 || t == forge.game.spellability.OptionalCost.Kicker2) {
                c.flags = 2;
            }
            c.frag = new JsonPrimitive(i);
            c.key = "i:" + i;
            m.cands.add(c);
        }
        m.minPick = 0;
        m.maxPick = m.C();
    }

    /**
     * 14 SCRY, 19 SURVEIL, first frame: ASSIGN with one slot per looked-at card (Forge's order, top first) and two
     * candidates each: YES = keep on top, NO = bottom (scry) / graveyard (surveil). The second frame is
     * {@link Menu#secondFrame}.
     */
    @SuppressWarnings("unchecked")
    private static void look(final Menu m, final Object objs) {
        if (!(objs instanceof Iterable)) {
            m.unposable = m.kind + " ask without its cards";
            return;
        }
        m.mode = RlSchema.M_ASSIGN;
        m.shape = SHAPE_TWO_FRAME;
        m.restKey = m.family == RlSchema.F_SCRY ? "bottom" : "graveyard";
        int s = 0;
        for (Card c : (Iterable<Card>) objs) {
            m.slotCards.add(c);
            m.looked.add(c);
            final Cand y = new Cand();
            y.kind = RlSchema.K_YES;
            y.host = c;
            y.slot = s;
            y.frag = new JsonPrimitive(c.getId());
            y.key = "top:" + c.getId();
            m.cands.add(y);
            final Cand n = new Cand();
            n.kind = RlSchema.K_NO;
            n.host = c;
            n.slot = s;
            n.frag = new JsonPrimitive(c.getId());
            n.key = "rest:" + c.getId();
            m.cands.add(n);
            s++;
        }
    }

    /**
     * 15 ORDER (orderMoveToZoneList): PERMUTE over the cards. Steps = the final top-first order (ruling 10-05): for a
     * move onto the top of a library ({@code topFirst}) the answer, Forge's move order, is the steps reversed;
     * otherwise the answer is the steps.
     */
    @SuppressWarnings("unchecked")
    private static void order(final Menu m, final Object objs) {
        final Object[] o = arr(objs, 3);
        m.mode = RlSchema.M_PERMUTE;
        m.shape = SHAPE_PERMUTE;
        m.reverse = Boolean.TRUE.equals(o[2]);
        for (Card c : (Iterable<Card>) o[0]) {
            final Cand x = new Cand();
            x.kind = RlSchema.K_CARD;
            x.host = c;
            x.frag = new JsonPrimitive(c.getId());
            x.key = "card:" + c.getId();
            m.cands.add(x);
        }
    }

    /**
     * 18 PILE: SINGLE over [pile A, pile B] (kind MODE); cand_num = the pile's size, cand_card = its highest-MV card,
     * cand_tgt = tokens of its two highest-MV members (lossy). A face-down pile shows only its size.
     */
    private static void pile(final Menu m, final Object objs) {
        final Object[] o = arr(objs, 4);
        // TwoPilesEffect passes its FaceDown parameter (PlayerController names it faceUp): "False" = both piles face
        // up, "True" = both face down, "One" = pile 1 (the separator's pick) face down
        final String faceDown = String.valueOf(o[3]);
        m.mode = RlSchema.M_SINGLE;
        m.shape = SHAPE_SINGLE;
        m.minPick = 1;
        m.maxPick = 1;
        for (int p = 0; p < 2; p++) {
            @SuppressWarnings("unchecked")
            final List<Card> pile = new ArrayList<>((java.util.Collection<Card>) (p == 0 ? o[1] : o[2]));
            final boolean hidden = "True".equals(faceDown) || ("One".equals(faceDown) && p == 0);
            m.piles.add(new ArrayList<>(pile));
            m.pileHidden.add(hidden);
            pile.sort((a, b) -> a.getCMC() != b.getCMC() ? Integer.compare(b.getCMC(), a.getCMC())
                    : Integer.compare(a.getId(), b.getId()));
            final Cand c = new Cand();
            c.kind = RlSchema.K_MODE;
            c.num = pile.size();
            if (hidden) {
                c.hidden = true;
            } else {
                c.host = pile.isEmpty() ? null : pile.get(0);
                c.tgt0 = pile.isEmpty() ? null : pile.get(0);
                c.tgt1 = pile.size() < 2 ? null : pile.get(1);
            }
            c.frag = kv("pile", new JsonPrimitive(p));
            c.key = "pile:" + p;
            m.cands.add(c);
        }
    }

    /**
     * 23 NAME: SINGLE over the legal names among the opponent cards the seat has seen (first-seen order) and its own
     * deck (deck order), or over the ask's own faces; then "Forge's choice" (kind MODE, cand_num -1; delegated).
     */
    @SuppressWarnings("unchecked")
    private static void name(final Menu m, final Player seat, final Object objs, final List<String> seen) {
        final Object[] o = arr(objs, 3);
        final java.util.function.Predicate<forge.card.ICardFace> cpp =
                (java.util.function.Predicate<forge.card.ICardFace>) o[1];
        final List<forge.card.ICardFace> faces = (List<forge.card.ICardFace>) o[2];
        m.mode = RlSchema.M_SINGLE;
        m.shape = SHAPE_SINGLE;
        m.minPick = 1;
        m.maxPick = 1;
        final java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (faces != null) {
            for (forge.card.ICardFace f : faces) {
                if (f != null) {
                    names.add(f.getName());
                }
            }
        } else {
            final List<String> pool = new ArrayList<>();
            if (seen != null) {
                pool.addAll(seen);
            }
            final forge.game.player.RegisteredPlayer rp = seat.getRegisteredPlayer();
            if (rp != null && rp.getDeck() != null && rp.getDeck().getMain() != null) {
                for (Map.Entry<forge.item.PaperCard, Integer> e : rp.getDeck().getMain()) {
                    pool.add(e.getKey().getName());
                }
            }
            for (String n : pool) {
                if (!names.contains(n) && forge.bench.PlayerControllerBridge.nameLegal(n, cpp, null)) {
                    names.add(n);
                }
            }
        }
        for (String n : names) {
            if (m.C() >= RlSchema.C_MAX - 1) {
                break;
            }
            final Cand c = new Cand();
            c.kind = RlSchema.K_CARD;
            c.name = n;
            c.frag = kv("name", new JsonPrimitive(n));
            c.key = "name:" + n;
            m.cands.add(c);
        }
        final Cand forge = new Cand();
        forge.kind = RlSchema.K_MODE;
        forge.num = -1;
        forge.frag = flag("delegate", true);
        forge.key = "forge";
        m.cands.add(forge);
    }

    /** 24 COLOR: chooseColor SINGLE, chooseColors SUBSET; MODE candidates, cand_num 0-4 = W, U, B, R, G. */
    private static void color(final Menu m, final Object objs) {
        final Object[] o = arr(objs, 4);
        final SpellAbility sa = (SpellAbility) o[0];
        final forge.card.ColorSet cs = (forge.card.ColorSet) o[1];
        final boolean single = "chooseColor".equals(m.method);
        final Card host = sa == null ? null : sa.getHostCard();
        for (int i = 0; i < forge.card.MagicColor.WUBRG.length; i++) {
            if ((cs.getColor() & forge.card.MagicColor.WUBRG[i]) == 0) {
                continue;
            }
            final Cand c = new Cand();
            c.kind = RlSchema.K_MODE;
            c.host = host;
            c.num = i;
            c.frag = single ? kv("color", new JsonPrimitive(i)) : new JsonPrimitive(i);
            c.key = "c:" + i;
            m.cands.add(c);
        }
        if (single) {
            m.mode = RlSchema.M_SINGLE;
            m.shape = SHAPE_SINGLE;
            m.minPick = 1;
            m.maxPick = 1;
        } else {
            m.mode = RlSchema.M_SUBSET;
            m.shape = SHAPE_CHOICES;
            m.listKey = "colors";
            m.minPick = Math.max(0, (Integer) o[2]);
            m.maxPick = Math.min(Math.max(0, (Integer) o[3]), m.C());
        }
    }

    // ------------------------------------------------------------------------------------------------ record-mode synthesis

    /**
     * Whether Forge's stack accepts {@code root} with the targets it carries now: the test {@code MagicStack.add}
     * applies before it puts an activation on the stack (every ability in the chain has a valid target count).
     */
    public static boolean stackAccepts(final Game game, final SpellAbility root) {
        try {
            return game.getStack().hasLegalTargeting(root);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Record mode: the TARGETS decisions implied by a spell Forge's AI chose at priority. Forge's AI targets inside
     * {@code canPlayAI} and never calls {@code chooseTargetsFor} for its own casts, so the bridge never asks; the
     * RL seat, which casts through the bridge, is asked once per targeting (sub-)ability in chain order, over
     * {@code getAllCandidates} plus the stack candidates. This rebuilds that menu (the chosen targets included even
     * when a uniqueness rule would now exclude them) and returns one (menu, teacher steps) pair per targeting
     * ability, or a menu with {@code unposable} set when Forge's targets cannot be named. Two cases are not mapping
     * failures (lane rl-r0-b4b-1006): an ability whose target count is 0 on this cast is the seat's trivial empty ask
     * ({@code offTargets} when Forge left stray targets on it), and Forge's targets that break the ability's own
     * target count are Forge-illegal ({@code forgeIllegal}: Forge's stack refuses the activation). Reads only.
     */
    public static List<Object[]> targetsFromChosen(final Game game, final SpellAbility root) {
        final List<Object[]> out = new ArrayList<>();
        SpellAbility cur = root;
        while (cur != null) {
            if (cur.usesTargeting()) {
                final Menu m = new Menu();
                m.family = RlSchema.F_TARGETS;
                m.method = "chooseTargetsFor";
                m.kind = "targets";
                m.mode = RlSchema.M_SUBSET;
                m.shape = SHAPE_CHOICES;
                m.source = cur.getHostCard();
                m.sourceIsSpell = cur.getRootAbility().isSpell();
                short[] steps = null;
                try {
                    final List<GameObject> chosen = new ArrayList<>();
                    for (GameObject go : cur.getTargets()) {
                        chosen.add(go);
                    }
                    final TargetRestrictions tr = cur.getTargetRestrictions();
                    final List<GameEntity> ents = new ArrayList<>();
                    for (Player p : game.getPlayers()) {
                        if (chosen.contains(p) || cur.canTarget(p)) {
                            ents.add(p);
                        }
                    }
                    for (Card c : game.getCardsIn(tr.getZone())) {
                        if (chosen.contains(c) || cur.canTarget(c)) {
                            ents.add(c);
                        }
                    }
                    final List<SpellAbilityStackInstance> stack = new ArrayList<>();
                    if (tr.getZone() != null && tr.getZone().contains(ZoneType.Stack)) {
                        for (SpellAbilityStackInstance si : game.getStack()) {
                            final SpellAbility t = si.getSpellAbility();
                            if (t != null && (chosen.contains(t) || cur.canTargetSpellAbility(t))) {
                                stack.add(si);
                            }
                        }
                    }
                    addTargets(m, ents, stack);
                    m.minPick = Math.max(0, cur.getMinTargets());
                    m.maxPick = Math.min(Math.max(0, cur.getMaxTargets()), m.C());
                    steps = new short[chosen.size()];
                    for (int j = 0; j < chosen.size(); j++) {
                        final GameObject go = chosen.get(j);
                        int at = -1;
                        for (int i = 0; i < m.C() && at < 0; i++) {
                            final Cand x = m.cands.get(i);
                            if ((x.host != null && x.host == go) || (x.tgt0 != null && x.tgt0 == go)
                                    || (x.hostStack != null && x.hostStack.getSpellAbility() == go)) {
                                at = i;
                            }
                        }
                        if (at < 0) {
                            steps = null;
                            break;
                        }
                        steps[j] = (short) at;
                    }
                    finish(m);
                    final int maxT = cur.getMaxTargets();
                    if (m.unposable == null && m.trivial && maxT == 0) {
                        // no targets on this cast: the seat's ask is trivial (the empty answer), as in the seat path;
                        // stray targets Forge's AI left on it are not a decision (they make Forge refuse the cast)
                        m.offTargets = !chosen.isEmpty();
                        steps = m.trivialSteps;
                    } else if (!cur.isTargetNumberValid()) {
                        // Forge's own answer breaks the ability's own target count (too few targets, typically: the
                        // AI activates a +loyalty ability "for the cost" without targeting), so Forge refuses the
                        // activation: outside the legal action space, whatever the menu could name
                        m.forgeIllegal = chosen.size() + " targets outside [" + cur.getMinTargets() + "," + maxT + "]";
                        steps = null;
                    } else if (steps != null && m.unposable == null) {
                        final String bad = m.validate(steps);
                        if (bad != null) {
                            m.unposable = "Forge's targets break the menu's rules: " + bad;
                            steps = null;
                        }
                    }
                } catch (RuntimeException e) {
                    m.unposable = "targets synthesis failed: " + e;
                }
                if (steps == null && m.unposable == null && m.forgeIllegal == null) {
                    m.unposable = "Forge's targets are not on the menu";
                }
                out.add(new Object[] {m, steps});
            }
            cur = cur.getSubAbility();
        }
        return out;
    }
}
