package forge.ai.simulation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Position;

/**
 * combo-assembly-1021 B2: route progress of one seat's zones, shared by the attack (tutor candidates and beams) and the
 * defense (deck-guess increment 4: "which card lowers his progress most"). Agreed interface:
 * runs/2026-10-21-deck-guess/interface-route-progress.md (with combo-assembly's amendments a-d).
 *
 * <p><b>Pure and engine-free.</b> It reads a {@link Position} (the seat's hand, battlefield, graveyard, library sample,
 * known top, mana pool) and never touches a game. It is deterministic for a given seed: the same Position and seed give
 * the same {@link Est}.</p>
 *
 * <p><b>Role-based routes, generic over the role table.</b> The jar resource {@code route-roles-1021.json} (generated
 * from the card scripts plus Scryfall Tagger tags) gives each card name its roles, costs, mana and tutor filter, and the
 * routes: a family is an ordered list of role slots with zone requirements. There is no per-card logic here. An unknown
 * name has no roles: it fills nothing (an unknown land still makes one mana of any colour).</p>
 *
 * <p><b>"Completes"</b> (amendment b) means every slot is filled by a distinct card in an accessible zone, with the mana
 * for the enabler chain by that turn under a cheap mana model: battlefield sources, one land drop per turn from the
 * hand, rocks and dorks from hand (from the next turn; a 0-cost rock at once), and one-shot mana (rituals, LED, Petal,
 * Lotus) usable on one turn. At most one tutor in reach may stand in for one slot (to hand, top: a turn later,
 * graveyard or battlefield, by its filter). Cantrips and draw spells in reach dig their cards for free. It is NOT a win
 * claim: roles prune and score, the solver stays the arbiter.</p>
 *
 * <p><b>Turns</b> are the seat's own turns. {@code turns = 1} is this turn when the seat is active (its main phase or
 * earlier: {@code sorcerySpeed || landDrop}, the defense's convention), else its next turn (which draws a card first).</p>
 *
 * <p><b>Library</b> (amendment c): the Position's known top stays fixed in every sample; the rest of the library (as given:
 * the decklist minus seen for the attack, the belief world's library for the defense) is shuffled per sample, padded with
 * unknown cards up to {@code librarySize}.</p>
 */
public final class RouteProgress {
    private RouteProgress() {
    }

    public static final String RESOURCE = "/forge/ai/simulation/route-roles-1021.json";

    /** The estimate (amendment a: {@code turn} and {@code top}). */
    public static final class Est {
        /** P(some route completes within {@code turns} of this seat's turns), 0..1. */
        public double p;
        /** The best route's id (the role table's route id), null if none. */
        public String route;
        /** doomsday | breach | reanimator | sneak-show | natural-order | tinker | twin; null if none. */
        public String family;
        /** The best route's slots that the seat's current zones do not fill (a multi-role slot is "A+B"). */
        public List<String> missing = new ArrayList<>();
        /** Every HAND card id that fills a role of any route the seat is building (a tutor it used included) -> role. */
        public Map<Integer, String> fillers = new LinkedHashMap<>();
        /** The median own turn the best route completes on (among the samples where it does), -1 if none. */
        public int turn = -1;
        /** The best (at most 3) routes with their completion probability, best first. */
        public Map<String, Double> top = new LinkedHashMap<>();

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "p=%.4f route=%s family=%s turn=%d missing=%s fillers=%s top=%s",
                    p, route, family, turn, missing, fillers, top);
        }
    }

    // ------------------------------------------------------------------------------------------------ the role table

    private static final int H = 1, BF = 2, GY = 4, LIB = 8;
    private static final int PAY_CAST = 1, PAY_USE = 2;
    private static final int INF = 99;
    private static final String COLORS = "WUBRGC";
    private static final int ANY_COLOR = 0b111111;

    static final class Cost {
        final int[] pips = new int[6];
        int generic;

        boolean zero() {
            if (generic != 0) {
                return false;
            }
            for (int x : pips) {
                if (x != 0) {
                    return false;
                }
            }
            return true;
        }

        static Cost parse(String s) {
            final Cost c = new Cost();
            if (s == null) {
                return c;
            }
            int num = 0;
            boolean digits = false;
            for (int i = 0; i < s.length(); i++) {
                final char ch = s.charAt(i);
                if (Character.isDigit(ch)) {
                    num = num * 10 + (ch - '0');
                    digits = true;
                } else {
                    final int k = COLORS.indexOf(ch);
                    if (k >= 0) {
                        c.pips[k]++;
                    }
                }
            }
            if (digits) {
                c.generic = num;
            }
            return c;
        }

        void addTo(Cost into) {
            into.generic += generic;
            for (int i = 0; i < 6; i++) {
                into.pips[i] += pips[i];
            }
        }
    }

    static final class Filter {
        String to; // hand | top | graveyard | battlefield (tutors)
        long types; // 0 = any
        int maxmv = -1;
        int colors; // 0 = any
    }

    static final class Mana {
        String kind; // land | rock | dork | ritual | oneshot | oneshot-hand
        int colors;
        int amount;
        int net;
    }

    static final class CardRole {
        String name;
        long roles;
        final List<String> roleNames = new ArrayList<>();
        long types;
        int colors;
        int mv;
        Cost cost = new Cost();
        final Map<String, Cost> use = new HashMap<>();
        Filter tutor;
        Filter finds;
        Mana mana;
        int dig;
    }

    static final class Slot {
        long roles;
        String label;
        String useRole;
        int zones;
        int pay;
        int filterBy = -1;
    }

    static final class Route {
        String id;
        String family;
        Slot[] slots;
    }

    static final class Table {
        final Map<String, Integer> roleBit = new HashMap<>();
        final Map<String, Integer> typeBit = new HashMap<>();
        final Map<String, CardRole> cards = new HashMap<>();
        final List<Route> routes = new ArrayList<>();
        String version;
        String sha256;
        long tutorRoles;

        long bit(String role) {
            Integer b = roleBit.get(role);
            if (b == null) {
                b = roleBit.size();
                if (b >= 63) {
                    throw new IllegalStateException("route-roles: more than 63 roles");
                }
                roleBit.put(role, b);
            }
            return 1L << b;
        }

        long typeBits(JsonArray a) {
            long m = 0;
            if (a == null) {
                return 0;
            }
            for (JsonElement e : a) {
                Integer b = typeBit.get(e.getAsString());
                if (b == null) {
                    b = typeBit.size();
                    typeBit.put(e.getAsString(), b);
                }
                m |= 1L << Math.min(62, b);
            }
            return m;
        }
    }

    private static volatile Table table;

    static Table table() {
        Table t = table;
        if (t == null) {
            synchronized (RouteProgress.class) {
                t = table;
                if (t == null) {
                    t = load();
                    table = t;
                }
            }
        }
        return t;
    }

    private static int colorMask(String s) {
        int m = 0;
        if (s == null) {
            return 0;
        }
        for (int i = 0; i < s.length(); i++) {
            final int k = COLORS.indexOf(s.charAt(i));
            if (k >= 0) {
                m |= 1 << k;
            }
        }
        return m;
    }

    private static Filter filter(Table t, JsonObject o) {
        if (o == null) {
            return null;
        }
        final Filter f = new Filter();
        f.to = o.has("to") ? o.get("to").getAsString() : null;
        f.types = t.typeBits(o.has("types") ? o.getAsJsonArray("types") : null);
        f.maxmv = o.has("maxmv") ? o.get("maxmv").getAsInt() : -1;
        f.colors = colorMask(o.has("colors") ? o.get("colors").getAsString() : null);
        return f;
    }

    private static Table load() {
        final byte[] bytes;
        try (InputStream in = RouteProgress.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("route-roles resource missing: " + RESOURCE);
            }
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            bytes = bo.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("route-roles resource unreadable: " + e, e);
        }
        final Table t = new Table();
        try {
            final StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            t.sha256 = sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            t.sha256 = "?";
        }
        final JsonObject o = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        t.version = o.get("version").getAsString();
        for (JsonElement re : o.getAsJsonArray("routes")) {
            final JsonObject ro = re.getAsJsonObject();
            final Route r = new Route();
            r.id = ro.get("id").getAsString();
            r.family = ro.get("family").getAsString();
            final JsonArray sl = ro.getAsJsonArray("slots");
            if (sl.size() > 4) {
                throw new IllegalStateException("route-roles: route " + r.id + " has more than 4 slots");
            }
            r.slots = new Slot[sl.size()];
            for (int i = 0; i < sl.size(); i++) {
                final JsonObject so = sl.get(i).getAsJsonObject();
                final Slot s = new Slot();
                final StringBuilder label = new StringBuilder();
                for (JsonElement x : so.getAsJsonArray("roles")) {
                    s.roles |= t.bit(x.getAsString());
                    if (s.useRole == null) {
                        s.useRole = x.getAsString();
                    }
                    label.append(label.length() == 0 ? "" : "+").append(x.getAsString());
                }
                s.label = label.toString();
                for (JsonElement x : so.getAsJsonArray("zones")) {
                    switch (x.getAsString()) {
                        case "hand": s.zones |= H; break;
                        case "battlefield": s.zones |= BF; break;
                        case "graveyard": s.zones |= GY; break;
                        case "library": s.zones |= LIB; break;
                        default: throw new IllegalStateException("route-roles: zone " + x);
                    }
                }
                final String pay = so.get("pay").getAsString();
                s.pay = (pay.contains("cast") ? PAY_CAST : 0) | (pay.contains("use") ? PAY_USE : 0);
                s.filterBy = so.has("filter_by") ? so.get("filter_by").getAsInt() : -1;
                r.slots[i] = s;
            }
            t.routes.add(r);
        }
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("cards").entrySet()) {
            final JsonObject co = e.getValue().getAsJsonObject();
            final CardRole c = new CardRole();
            c.name = e.getKey();
            for (JsonElement x : co.getAsJsonArray("roles")) {
                c.roleNames.add(x.getAsString());
                c.roles |= t.bit(x.getAsString());
            }
            c.types = t.typeBits(co.getAsJsonArray("types"));
            c.mv = co.has("mv") ? co.get("mv").getAsInt() : 0;
            c.colors = colorMask(co.has("colors") ? co.get("colors").getAsString() : null);
            c.cost = Cost.parse(co.has("cost") ? co.get("cost").getAsString() : null);
            if (co.has("costs")) {
                for (Map.Entry<String, JsonElement> u : co.getAsJsonObject("costs").entrySet()) {
                    c.use.put(u.getKey(), Cost.parse(u.getValue().getAsString()));
                }
            }
            c.tutor = filter(t, co.has("tutor") ? co.getAsJsonObject("tutor") : null);
            c.finds = filter(t, co.has("finds") ? co.getAsJsonObject("finds") : null);
            if (co.has("mana")) {
                final JsonObject mo = co.getAsJsonObject("mana");
                final Mana m = new Mana();
                m.kind = mo.get("kind").getAsString();
                m.colors = colorMask(mo.get("colors").getAsString());
                m.amount = mo.get("amount").getAsInt();
                m.net = mo.has("net") ? mo.get("net").getAsInt() : m.amount;
                c.mana = m;
            }
            c.dig = co.has("dig") ? co.get("dig").getAsInt() : 0;
            t.cards.put(c.name, c);
        }
        for (String r : new String[] {"TUTOR_ANY", "TUTOR_HAND", "TUTOR_TOP", "TUTOR_GY", "TUTOR_BF"}) {
            t.tutorRoles |= t.bit(r);
        }
        return t;
    }

    /** The role table's roles for a card name (empty for an unknown name). */
    public static List<String> roles(String name) {
        final CardRole c = name == null ? null : table().cards.get(name);
        return c == null ? Collections.emptyList() : Collections.unmodifiableList(c.roleNames);
    }

    /** The role table's version and the sha256 of its bytes, for receipts. */
    public static String tableVersion() {
        return table().version;
    }

    public static String tableSha256() {
        return table().sha256;
    }

    // ------------------------------------------------------------------------------------------------ the estimator

    public static Est estimate(Position pos, int turns, int draws, long seed) {
        return estimateWithout(pos, Collections.emptySet(), turns, draws, seed);
    }

    /** As {@link #estimate}, with these card ids taken out of every zone (a discard candidate, a countered spell). */
    public static Est estimateWithout(Position pos, Collection<Integer> cardIds, int turns, int draws, long seed) {
        return new Run(table(), pos, cardIds == null ? Collections.emptySet() : new HashSet<>(cardIds),
                Math.max(1, Math.min(turns, 12)), Math.max(1, draws), seed).run();
    }

    /** One call's state: the cards of the position, the per-sample arrivals and mana budgets. */
    private static final class Run {
        final Table t;
        final Position pos;
        final int T;
        final int samples;
        final long seed;
        final boolean active;

        // cards: index -> data
        int n;
        int[] id;
        CardRole[] cr;
        ACard[] ac;
        int[] origin;
        boolean[] tapped;
        int fixedTop; // number of known-top cards (library cards 0..fixedTop-1 in order)
        int[] libCards; // library card indices: the known top first (in order), then the rest
        int blanks; // unknown library cards
        int[][] slotCands; // per route, per slot: candidate card indices (origin-compatible, roles)
        int[][] slotLibCands; // per route, per slot: library cards with the slot's roles (a tutor's targets)
        int[] tutors; // card indices with a tutor filter (in hand or library)
        int[] cantrips;
        int[] landsH; // lands/rocks/dorks in hand or library (mana permanents to play), by index
        int[] oneshots;

        // per sample
        final int[] arrival;
        final int[] drawsBy;
        final int[][] baseSrc; // per turn: sources (mask << 8 | amount)
        final int[] baseLen;
        final int[][] oneSrc;
        final int[] oneLen;

        Run(Table t, Position pos, Set<Integer> without, int turns, int draws, long seed) {
            this.t = t;
            this.pos = pos;
            this.T = turns;
            this.seed = seed;
            this.active = pos.sorcerySpeed || pos.landDrop;
            collect(without);
            final int unknownOrder = libCards.length - fixedTop + blanks;
            this.samples = unknownOrder <= 1 ? 1 : draws;
            arrival = new int[n];
            drawsBy = new int[T + 1];
            for (int k = 1; k <= T; k++) {
                drawsBy[k] = !pos.canDraw ? 0 : active ? k - 1 : k;
            }
            baseSrc = new int[T + 1][];
            baseLen = new int[T + 1];
            oneSrc = new int[T + 1][];
            oneLen = new int[T + 1];
            for (int k = 1; k <= T; k++) {
                baseSrc[k] = new int[n + 8];
                oneSrc[k] = new int[n + 1];
            }
        }

        private void add(List<Object[]> into, ACard a, int org, boolean tap) {
            into.add(new Object[] {a, org, tap});
        }

        private void collect(Set<Integer> without) {
            final List<Object[]> all = new ArrayList<>();
            for (ACard a : pos.hand) {
                if (!without.contains(a.id)) {
                    add(all, a, H, false);
                }
            }
            for (int i = 0; i < pos.battlefield.size(); i++) {
                final ACard a = pos.battlefield.get(i);
                if (!without.contains(a.id)) {
                    add(all, a, BF, i < pos.tapped.size() && Boolean.TRUE.equals(pos.tapped.get(i)));
                }
            }
            for (ACard a : pos.graveyard) {
                if (!without.contains(a.id)) {
                    add(all, a, GY, false);
                }
            }
            int top = 0;
            for (ACard a : pos.knownTop) {
                if (!without.contains(a.id)) {
                    add(all, a, LIB, false);
                    top++;
                }
            }
            for (ACard a : pos.library) {
                if (!without.contains(a.id)) {
                    add(all, a, LIB, false);
                }
            }
            fixedTop = top;
            n = all.size();
            id = new int[n];
            cr = new CardRole[n];
            ac = new ACard[n];
            origin = new int[n];
            tapped = new boolean[n];
            final List<Integer> lib = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final Object[] o = all.get(i);
                ac[i] = (ACard) o[0];
                id[i] = ac[i].id;
                cr[i] = ac[i].name == null ? null : t.cards.get(ac[i].name);
                origin[i] = (Integer) o[1];
                tapped[i] = (Boolean) o[2];
                if (origin[i] == LIB) {
                    lib.add(i);
                }
            }
            libCards = lib.stream().mapToInt(Integer::intValue).toArray();
            blanks = Math.max(0, pos.librarySize - pos.knownTop.size() - pos.library.size()); // a removed card leaves no blank

            // slot candidates (roles + origin compatible with the slot's zones)
            slotCands = new int[t.routes.size() * 4][];
            slotLibCands = new int[t.routes.size() * 4][];
            for (int r = 0; r < t.routes.size(); r++) {
                final Route route = t.routes.get(r);
                for (int s = 0; s < route.slots.length; s++) {
                    final Slot slot = route.slots[s];
                    final List<Integer> c = new ArrayList<>(), lc = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        if (cr[i] == null || (cr[i].roles & slot.roles) != slot.roles) {
                            continue;
                        }
                        if (origin[i] == LIB) {
                            lc.add(i);
                        }
                        final int z = origin[i] == LIB ? (H | LIB) : origin[i];
                        if ((z & slot.zones) != 0) {
                            c.add(i);
                        }
                    }
                    slotCands[r * 4 + s] = c.stream().mapToInt(Integer::intValue).toArray();
                    slotLibCands[r * 4 + s] = lc.stream().mapToInt(Integer::intValue).toArray();
                }
            }
            final List<Integer> tu = new ArrayList<>(), ca = new ArrayList<>(), la = new ArrayList<>(), os = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final CardRole c = cr[i];
                if (c != null && c.tutor != null && (origin[i] == H || origin[i] == LIB || origin[i] == BF)) {
                    tu.add(i);
                }
                if (c != null && c.dig > 0 && (origin[i] == H || origin[i] == LIB)) {
                    ca.add(i);
                }
                if (origin[i] == H || origin[i] == LIB) {
                    final boolean manaPerm = c != null && c.mana != null
                            && ("land".equals(c.mana.kind) || "rock".equals(c.mana.kind) || "dork".equals(c.mana.kind));
                    if (manaPerm || c == null && ac[i].land) {
                        la.add(i);
                    }
                }
                if (c != null && c.mana != null && c.mana.net > 0 && (c.mana.kind.startsWith("oneshot") || "ritual".equals(c.mana.kind))
                        && origin[i] != GY) {
                    os.add(i);
                }
            }
            tutors = tu.stream().mapToInt(Integer::intValue).toArray();
            cantrips = ca.stream().mapToInt(Integer::intValue).toArray();
            landsH = la.stream().mapToInt(Integer::intValue).toArray();
            oneshots = os.stream().mapToInt(Integer::intValue).toArray();
            taken = new boolean[n];
            scratch = new int[2 * n + 16];
        }

        // ---------------------------------------------------------------- per sample: library order, arrivals, mana

        private void sampleArrivals(int[] order) {
            // order: library card indices (or -1 = unknown) top first; the known top is fixed
            final int depth = order.length;
            final int[] depthOf = new int[n];
            Arrays.fill(depthOf, -1);
            for (int d = 0; d < depth; d++) {
                if (order[d] >= 0) {
                    depthOf[order[d]] = d;
                }
            }
            for (int i = 0; i < n; i++) {
                arrival[i] = origin[i] == LIB ? INF : 0;
            }
            final boolean[] used = new boolean[cantrips.length];
            int extra = 0;
            for (int k = 1; k <= T; k++) {
                int seen = drawsBy[k] + extra;
                boolean more = true;
                while (more) {
                    more = false;
                    for (int c = 0; c < cantrips.length; c++) {
                        final int i = cantrips[c];
                        if (used[c]) {
                            continue;
                        }
                        final boolean inHand = origin[i] == H || depthOf[i] >= 0 && depthOf[i] < seen;
                        if (inHand) {
                            used[c] = true;
                            extra += cr[i].dig;
                            seen += cr[i].dig;
                            more = true;
                        }
                    }
                }
                for (int d = 0; d < Math.min(seen, depth); d++) {
                    final int i = order[d];
                    if (i >= 0 && arrival[i] == INF) {
                        arrival[i] = k;
                    }
                }
            }
        }

        private static int pack(int mask, int amount) {
            return mask << 8 | Math.min(255, amount);
        }

        private boolean inHandBy(int i, int k) {
            return origin[i] == H || origin[i] == LIB && arrival[i] <= k;
        }

        private void sampleMana() {
            int played = 0;
            final boolean[] playedLand = new boolean[n];
            for (int k = 1; k <= T; k++) {
                int len = 0;
                final int[] src = baseSrc[k];
                // battlefield sources
                for (int i = 0; i < n; i++) {
                    if (origin[i] != BF) {
                        continue;
                    }
                    final boolean now = k == 1 && active;
                    if (now && (tapped[i] || ac[i].sourceSick)) {
                        continue;
                    }
                    final Mana m = cr[i] == null ? null : cr[i].mana;
                    if (m != null && !("land".equals(m.kind) || "rock".equals(m.kind) || "dork".equals(m.kind))) {
                        continue; // one-shot mana on the battlefield (LED, Petal, Lotus) is in the one-shot pool
                    }
                    if (m != null) {
                        src[len++] = pack(m.colors, m.amount);
                    } else if (ac[i].sourceColors != 0) {
                        src[len++] = pack(ac[i].sourceColors, Math.max(1, ac[i].sourceAmount));
                    } else if (cr[i] == null && ac[i].land) {
                        src[len++] = pack(ANY_COLOR, 1);
                    }
                }
                if (k == 1 && active) {
                    for (int c = 0; c < 6; c++) {
                        if (pos.pool[c] > 0) {
                            src[len++] = pack(1 << c, pos.pool[c]);
                        }
                    }
                }
                // land drops: one per own turn (this turn only if the drop is still there)
                final boolean drop = k > 1 || !active || pos.landDrop;
                if (drop) {
                    for (int i : landsH) {
                        if (!playedLand[i] && inHandBy(i, k) && isLand(i)) {
                            playedLand[i] = true;
                            played++;
                            break;
                        }
                    }
                }
                // played lands, and rocks / dorks from hand (from the turn after they arrive; a free rock at once)
                for (int i : landsH) {
                    if (isLand(i)) {
                        if (playedLand[i]) {
                            src[len++] = landSource(i);
                        }
                    } else if (inHandBy(i, k)) {
                        final int arr = origin[i] == H ? 1 : arrival[i];
                        final Mana m = cr[i].mana;
                        if (k > arr || k == arr && "rock".equals(m.kind) && cr[i].cost.zero()) {
                            src[len++] = pack(m.colors, m.amount);
                        }
                    }
                }
                baseLen[k] = len;
                int ol = 0;
                for (int i : oneshots) {
                    if (origin[i] == BF || inHandBy(i, k)) {
                        oneSrc[k][ol++] = pack(cr[i].mana.colors, cr[i].mana.net);
                    }
                }
                oneLen[k] = ol;
            }
        }

        private boolean isLand(int i) {
            return cr[i] == null ? ac[i].land : cr[i].mana != null && "land".equals(cr[i].mana.kind);
        }

        private int landSource(int i) {
            return cr[i] == null || cr[i].mana == null ? pack(ANY_COLOR, 1) : pack(cr[i].mana.colors, cr[i].mana.amount);
        }

        // ---------------------------------------------------------------- matching one route at one turn

        /** Per attempt scratch. */
        int[] assign = new int[4];
        int tutorIdx, tutorSlot;
        boolean tutorTop;
        boolean[] taken;
        int[] scratch;
        int[] schedTurn = new int[4];
        int[][] rem;
        int[] remLen;
        int nodes;

        private boolean eligible(int i, Slot s, int k) {
            switch (origin[i]) {
                case H: return (s.zones & H) != 0;
                case BF: return (s.zones & BF) != 0;
                case GY: return (s.zones & GY) != 0;
                default: return (s.zones & H) != 0 && arrival[i] <= k || (s.zones & LIB) != 0 && arrival[i] > k;
            }
        }

        private boolean passes(Filter f, int i) {
            if (f == null) {
                return true;
            }
            final CardRole c = cr[i];
            if (c == null) {
                return false;
            }
            if (f.types != 0 && (c.types & f.types) == 0) {
                return false;
            }
            if (f.maxmv >= 0 && c.mv > f.maxmv) {
                return false;
            }
            return f.colors == 0 || (c.colors & f.colors) != 0;
        }

        /** Does route r complete by own turn k? Fills {@link #assign} and the tutor fields on success. */
        boolean complete(Route route, int r, int k) {
            nodes = 0;
            tutorIdx = -1;
            tutorSlot = -1;
            return fill(route, r, 0, k); // fill() clears 'taken' as it backtracks
        }

        private boolean fill(Route route, int r, int s, int k) {
            if (++nodes > 400) {
                return false;
            }
            if (s == route.slots.length) {
                return schedule(route, k);
            }
            final Slot slot = route.slots[s];
            final Filter by = slot.filterBy >= 0 && slot.filterBy < s ? finds(assign[slot.filterBy]) : null;
            for (int i : slotCands[r * 4 + s]) {
                if (taken[i] || !eligible(i, slot, k) || slot.filterBy >= 0 && !passes(by, i)) {
                    continue;
                }
                taken[i] = true;
                assign[s] = i;
                final boolean ok = fill(route, r, s + 1, k);
                taken[i] = false;
                if (ok) {
                    return true;
                }
            }
            // one tutor in reach may stand in for this slot
            if (tutorIdx < 0 && (slot.zones & LIB) == 0) {
                for (int ti : tutors) {
                    if (taken[ti]) {
                        continue;
                    }
                    final Filter tf = cr[ti].tutor;
                    final int dest = "graveyard".equals(tf.to) ? GY : "battlefield".equals(tf.to) ? BF : H;
                    if ((slot.zones & dest) == 0 || !(origin[ti] == BF || inHandBy(ti, k))) {
                        continue;
                    }
                    final int tArr = origin[ti] == LIB ? arrival[ti] : 1;
                    for (int i : slotLibCands[r * 4 + s]) {
                        if (taken[i] || arrival[i] <= tArr || !passes(tf, i)
                                || slot.filterBy >= 0 && !passes(by, i)) {
                            continue;
                        }
                        taken[ti] = true;
                        taken[i] = true;
                        assign[s] = i;
                        tutorIdx = ti;
                        tutorSlot = s;
                        tutorTop = "top".equals(tf.to);
                        final boolean ok = fill(route, r, s + 1, k);
                        taken[ti] = false;
                        taken[i] = false;
                        if (ok) {
                            return true;
                        }
                        tutorIdx = -1;
                        tutorSlot = -1;
                    }
                }
            }
            return false;
        }

        private Filter finds(int i) {
            return i >= 0 && cr[i] != null ? cr[i].finds : null;
        }

        /** Greedy mana schedule of the chain in slot order (a tutor first), all by turn k. */
        private boolean schedule(Route route, int k) {
            if (rem == null) {
                rem = new int[T + 1][];
                remLen = new int[T + 1];
                for (int j = 1; j <= T; j++) {
                    rem[j] = new int[2 * n + 16];
                }
            }
            for (int j = 1; j <= k; j++) {
                System.arraycopy(baseSrc[j], 0, rem[j], 0, baseLen[j]);
                remLen[j] = baseLen[j];
            }
            int oneTurn = -1;
            int cur = 1;
            int tutorTurn = -1;
            if (tutorIdx >= 0) {
                final int ti = tutorIdx;
                final Cost c = origin[ti] == BF ? useCost(ti, null) : cr[ti].cost;
                final int arr = origin[ti] == LIB ? arrival[ti] : 1;
                final int[] got = place(id[ti] == pos.pendingId ? new Cost() : c, Math.max(cur, arr), k, oneTurn);
                if (got == null) {
                    return false;
                }
                cur = got[0];
                oneTurn = got[1];
                tutorTurn = cur;
            }
            for (int s = 0; s < route.slots.length; s++) {
                final Slot slot = route.slots[s];
                final int i = assign[s];
                int arr;
                boolean inHandNow;
                if (s == tutorSlot) {
                    final Filter tf = cr[tutorIdx].tutor;
                    arr = tutorTurn + (tutorTop ? 1 : 0);
                    inHandNow = "hand".equals(tf.to) || "top".equals(tf.to);
                } else {
                    arr = origin[i] == LIB ? ((slot.zones & LIB) != 0 && arrival[i] > k ? 1 : arrival[i]) : 1;
                    inHandNow = origin[i] == H || origin[i] == LIB && arrival[i] <= k;
                }
                if (arr > k) {
                    return false;
                }
                final Cost c = new Cost();
                final boolean libSlot = origin[i] == LIB && arrival[i] > k && s != tutorSlot;
                // the pending spell (cast and paid) is free
                if ((slot.pay & PAY_CAST) != 0 && !libSlot && (inHandNow || origin[i] == GY) && id[i] != pos.pendingId) {
                    cr[i].cost.addTo(c);
                }
                if ((slot.pay & PAY_USE) != 0) {
                    useCost(i, slot.useRole).addTo(c);
                }
                final int[] got = place(c, Math.max(cur, arr), k, oneTurn);
                if (got == null) {
                    return false;
                }
                cur = got[0];
                oneTurn = got[1];
            }
            return cur <= k;
        }

        private Cost useCost(int i, String role) {
            final Cost c = role == null ? null : cr[i].use.get(role);
            return c == null ? new Cost() : c;
        }

        /** The first turn in [from, k] whose remaining mana pays c (one-shot mana joins one turn only); null if none. */
        private int[] place(Cost c, int from, int k, int oneTurn) {
            if (c.zero()) {
                return from <= k ? new int[] {from, oneTurn} : null;
            }
            for (int j = from; j <= k; j++) {
                if (pay(c, rem[j], remLen[j], scratch, true)) {
                    pay(c, rem[j], remLen[j], scratch, false);
                    return new int[] {j, oneTurn};
                }
                if (oneTurn == -1 && oneLen[j] > 0) {
                    final int save = remLen[j];
                    System.arraycopy(oneSrc[j], 0, rem[j], save, oneLen[j]);
                    remLen[j] = save + oneLen[j];
                    if (pay(c, rem[j], remLen[j], scratch, true)) {
                        pay(c, rem[j], remLen[j], scratch, false);
                        return new int[] {j, j};
                    }
                    remLen[j] = save;
                }
            }
            return null;
        }

        /** Pays coloured pips from the sources with the fewest colours first, then generic; dry = only test. */
        private static boolean pay(Cost c, int[] src, int len, int[] amt, boolean dry) {
            for (int x = 0; x < len; x++) {
                amt[x] = src[x] & 0xff;
            }
            for (int col = 0; col < 5; col++) {
                int need = c.pips[col];
                while (need > 0) {
                    int best = -1, bestPop = 99;
                    for (int x = 0; x < len; x++) {
                        if (amt[x] > 0 && (src[x] >> 8 & 1 << col) != 0) {
                            final int pop = Integer.bitCount(src[x] >> 8);
                            if (pop < bestPop) {
                                bestPop = pop;
                                best = x;
                            }
                        }
                    }
                    if (best < 0) {
                        return false;
                    }
                    final int take = Math.min(need, amt[best]);
                    amt[best] -= take;
                    need -= take;
                }
            }
            int need = c.generic + c.pips[5];
            for (int x = 0; x < len && need > 0; x++) {
                final int take = Math.min(need, amt[x]);
                amt[x] -= take;
                need -= take;
            }
            if (need > 0) {
                return false;
            }
            if (!dry) {
                for (int x = 0; x < len; x++) {
                    src[x] = (src[x] & ~0xff) | amt[x];
                }
            }
            return true;
        }

        // ---------------------------------------------------------------- the run

        Est run() {
            final int R = t.routes.size();
            final int[] completes = new int[R];
            final List<List<Integer>> turnsOf = new ArrayList<>();
            for (int r = 0; r < R; r++) {
                turnsOf.add(new ArrayList<>());
            }
            final Set<Integer> usedTutors = new HashSet<>();
            int any = 0;
            final Random rng = new Random(seed);
            final int restN = libCards.length - fixedTop;
            final int[] rest = new int[restN + blanks];
            final int[] order = new int[fixedTop + restN + blanks];
            for (int sIdx = 0; sIdx < samples; sIdx++) {
                for (int x = 0; x < restN; x++) {
                    rest[x] = libCards[fixedTop + x];
                }
                for (int x = 0; x < blanks; x++) {
                    rest[restN + x] = -1;
                }
                for (int x = rest.length - 1; x > 0; x--) {
                    final int y = rng.nextInt(x + 1);
                    final int tmp = rest[x];
                    rest[x] = rest[y];
                    rest[y] = tmp;
                }
                for (int x = 0; x < fixedTop; x++) {
                    order[x] = libCards[x];
                }
                System.arraycopy(rest, 0, order, fixedTop, rest.length);
                sampleArrivals(order);
                sampleMana();
                boolean hit = false;
                for (int r = 0; r < R; r++) {
                    final Route route = t.routes.get(r);
                    if (!possible(route, r)) {
                        continue;
                    }
                    for (int k = 1; k <= T; k++) {
                        if (complete(route, r, k)) {
                            completes[r]++;
                            turnsOf.get(r).add(k);
                            hit = true;
                            if (tutorIdx >= 0 && origin[tutorIdx] == H) {
                                usedTutors.add(tutorIdx);
                            }
                            break;
                        }
                    }
                }
                if (hit) {
                    any++;
                }
            }
            final Est e = new Est();
            e.p = (double) any / samples;
            // routes by completion count, then median turn, then table order
            final Integer[] idx = new Integer[R];
            final int[] med = new int[R];
            for (int r = 0; r < R; r++) {
                idx[r] = r;
                final List<Integer> ts = turnsOf.get(r);
                Collections.sort(ts);
                med[r] = ts.isEmpty() ? INF : ts.get((ts.size() - 1) / 2);
            }
            Arrays.sort(idx, (a, b) -> completes[a] != completes[b] ? Integer.compare(completes[b], completes[a])
                    : med[a] != med[b] ? Integer.compare(med[a], med[b]) : Integer.compare(a, b));
            for (int x = 0; x < Math.min(3, R); x++) {
                final int r = idx[x];
                if (completes[r] > 0) {
                    e.top.put(t.routes.get(r).id, (double) completes[r] / samples);
                }
            }
            int best = completes[idx[0]] > 0 ? idx[0] : -1;
            final boolean[] feasible = new boolean[R];
            int bestFill = -1;
            for (int r = 0; r < R; r++) {
                feasible[r] = completes[r] > 0 || relaxedFeasible(t.routes.get(r), r);
                if (best < 0 && feasible[r]) {
                    final int f = filledNow(t.routes.get(r), r, null);
                    if (f > bestFill) {
                        bestFill = f;
                        bestRelaxed = r;
                    }
                }
            }
            if (best < 0 && bestFill >= 0) {
                best = bestRelaxed;
            }
            if (best >= 0) {
                final Route route = t.routes.get(best);
                e.route = route.id;
                e.family = route.family;
                e.turn = med[best] == INF ? -1 : med[best];
                final boolean[] filled = new boolean[route.slots.length];
                filledNow(route, best, filled);
                for (int s = 0; s < route.slots.length; s++) {
                    if (!filled[s]) {
                        e.missing.add(route.slots[s].label);
                    }
                }
            }
            // fillers: hand cards of every route the seat is building (best routes first), then the tutors it used
            for (int x = 0; x < R; x++) {
                final int r = idx[x];
                if (!feasible[r]) {
                    continue;
                }
                final Route route = t.routes.get(r);
                for (int s = 0; s < route.slots.length; s++) {
                    for (int i : slotCands[r * 4 + s]) {
                        if (origin[i] == H) {
                            e.fillers.putIfAbsent(id[i], route.slots[s].label);
                        }
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                if (usedTutors.contains(i)) {
                    e.fillers.putIfAbsent(id[i], tutorRole(i));
                }
            }
            return e;
        }

        int bestRelaxed = -1;

        private String tutorRole(int i) {
            for (String r : cr[i].roleNames) {
                if (r.startsWith("TUTOR_")) {
                    return r;
                }
            }
            return "TUTOR";
        }

        /** Every slot has some candidate (or a library card a tutor could find): a cheap pre-check. */
        private boolean possible(Route route, int r) {
            for (int s = 0; s < route.slots.length; s++) {
                if (slotCands[r * 4 + s].length == 0 && (tutors.length == 0 || slotLibCands[r * 4 + s].length == 0)) {
                    return false;
                }
            }
            return true;
        }

        /** A distinct-card matching over all the seat's cards (zones relaxed: hand slots take library cards too). */
        private boolean relaxedFeasible(Route route, int r) {
            return possible(route, r) && relaxedFill(route, r, 0, new boolean[n]);
        }

        private boolean relaxedFill(Route route, int r, int s, boolean[] used) {
            if (s == route.slots.length) {
                return true;
            }
            final int[] lib = tutors.length == 0 ? new int[0] : slotLibCands[r * 4 + s];
            for (int x = 0; x < slotCands[r * 4 + s].length + lib.length; x++) {
                final int i = x < slotCands[r * 4 + s].length ? slotCands[r * 4 + s][x] : lib[x - slotCands[r * 4 + s].length];
                if (!used[i]) {
                    used[i] = true;
                    final boolean ok = relaxedFill(route, r, s + 1, used);
                    used[i] = false;
                    if (ok) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** The most slots the current zones fill (hand, battlefield, graveyard; library slots from the library). */
        private int filledNow(Route route, int r, boolean[] out) {
            final int S = route.slots.length;
            int bestCount = -1, bestMask = 0;
            for (int mask = 0; mask < 1 << S; mask++) {
                if (Integer.bitCount(mask) <= bestCount) {
                    continue;
                }
                if (fillMask(route, r, 0, mask, new boolean[n])) {
                    bestCount = Integer.bitCount(mask);
                    bestMask = mask;
                }
            }
            if (out != null) {
                for (int s = 0; s < S; s++) {
                    out[s] = (bestMask >> s & 1) != 0;
                }
            }
            return bestCount;
        }

        private boolean fillMask(Route route, int r, int s, int mask, boolean[] used) {
            if (s == route.slots.length) {
                return true;
            }
            if ((mask >> s & 1) == 0) {
                return fillMask(route, r, s + 1, mask, used);
            }
            final Slot slot = route.slots[s];
            for (int i : slotCands[r * 4 + s]) {
                final boolean now = origin[i] == LIB ? (slot.zones & LIB) != 0 : true;
                if (!used[i] && now) {
                    used[i] = true;
                    final boolean ok = fillMask(route, r, s + 1, mask, used);
                    used[i] = false;
                    if (ok) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
