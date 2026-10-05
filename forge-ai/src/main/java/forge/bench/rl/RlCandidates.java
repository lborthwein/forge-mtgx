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
 * rl-r0-b1-1005). Built in two steps so that trivial asks never pay for an observation: {@link #build} reads the ask
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
            final int src = family == RlSchema.F_TARGETS ? pointer(source, o, seat) : -1;
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
                if (x.host != null) {
                    tok[i] = (short) o.pos(x.host);
                    card[i] = f.cardIndexOf(x.host);
                } else if (x.hostStack != null) {
                    final Integer p = o.posByStackId.get(x.hostStack.getId());
                    tok[i] = (short) (p == null ? -1 : p);
                    card[i] = x.hostStack.getSourceCard() == null ? 0 : f.cardIndexOf(x.hostStack.getSourceCard());
                } else {
                    tok[i] = -1;
                    card[i] = 0;
                }
                tgt[2 * i] = (short) pointer(x.tgt0, o, seat);
                tgt[2 * i + 1] = (short) src;
                slot[i] = (short) x.slot;
                num[i] = (short) x.num;
                ability[i] = (byte) Math.max(0, Math.min(15, x.ability));
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
                default:
                    return "unsupported mode " + mode;
            }
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
                default:
                    return null;
            }
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
     * The menu of one Phase A ask, or null for a family this builder does not pose (Phase B, or an unknown kind).
     */
    public static Menu build(final Game game, final Player seat, final String method, final String kind,
            final JsonObject body, final Object objs) {
        final int family = RlSchema.familyOf(kind, method);
        if (!RlSchema.isPhaseA(family)) {
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

    // ------------------------------------------------------------------------------------------------ record-mode synthesis

    /**
     * Record mode: the TARGETS decisions implied by a spell Forge's AI chose at priority. Forge's AI targets inside
     * {@code canPlayAI} and never calls {@code chooseTargetsFor} for its own casts, so the bridge never asks; the
     * RL seat, which casts through the bridge, is asked once per targeting (sub-)ability in chain order, over
     * {@code getAllCandidates} plus the stack candidates. This rebuilds that menu (the chosen targets included even
     * when a uniqueness rule would now exclude them) and returns one (menu, teacher steps) pair per targeting
     * ability, or a menu with {@code unposable} set when Forge's targets cannot be named. Reads only.
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
                    if (steps != null && m.unposable == null && m.validate(steps) != null) {
                        steps = null;
                    }
                } catch (RuntimeException e) {
                    m.unposable = "targets synthesis failed: " + e;
                }
                if (steps == null && m.unposable == null) {
                    m.unposable = "Forge's targets are not on the menu";
                }
                out.add(new Object[] {m, steps});
            }
            cur = cur.getSubAbility();
        }
        return out;
    }
}
