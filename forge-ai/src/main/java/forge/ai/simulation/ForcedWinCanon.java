package forge.ai.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiCache;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.card.CardRules;
import forge.card.CardType;
import forge.card.ICardFace;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameState;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.MyRandom;

/**
 * Lane searchspeed-1113 (solver key {@code cardTable}; absent = off, and then nothing here runs): card models learned
 * once, in one standard sandbox, and saved.
 *
 * <p>A card model ({@link ACard}) is learned by playing the card in a sandbox. Without the key the sandbox is a copy of
 * the position the card is first seen in, so a spell with nothing to target there, or a search with nothing to find, is
 * learned as "not played", and which position that is depends on timing. With the key every model is learned in the
 * <b>canonical sandbox</b>: one made-up two-player position per card pool, built by rules that name no card (the pool's
 * cards are ranked by how little their scripts do; each player gets the plainest permanent of each card type in play, the
 * next plainest cards of each type in the graveyard and in hand, five basic lands, and our seat's library holds one copy
 * of every pool card, so a search finds a card of each kind the pool has). It never reads a real game: a card is looked
 * up by name only.</p>
 *
 * <p>The <b>table</b> is a file of such models, written by the generator ({@code forge.bench.CardTableGen}) with the jar
 * that will read it. It is stamped with the build id of that jar (the sha256 of the jar file the classes were loaded
 * from) and a digest of the JVM's {@code forge.*} flags; a table whose stamp is not the running JVM's is refused with one
 * log line and the solver learns as without the key. Each entry carries a hash of the card's script: an entry whose card
 * script has changed is not used. A card the table does not hold is learned canonically at run time, kept for every
 * reader of the JVM, and appended once to {@code <table>.add.jsonl} (stamp and script hash on every line), which the
 * next JVM start loads read-only. Loop probes (planLoops) are saved per card pair the same way.</p>
 *
 * <p>Also here, measurement only: {@code -Dsdcc.readerStats} (why a model is unmodeled, how often it is read) and the
 * {@code -Dsdcc.readerDump} registry of every reader a JVM made.</p>
 */
public final class ForcedWinCanon {
    private ForcedWinCanon() {
    }

    /** -Dsdcc.readerStats (measurement; false = nothing is counted): unmodeled reasons and per-template read counts. */
    public static final boolean STATS = Boolean.getBoolean("sdcc.readerStats");
    /** -Dsdcc.readerDump=DIR (measurement; null = off): every reader of the JVM is written there at game end and JVM exit. */
    public static final String DUMP = System.getProperty("sdcc.readerDump");
    /** The decision in progress (bumped by the search's decide wrappers; only with {@link #STATS}). */
    public static final AtomicLong DECISION = new AtomicLong();

    static final long SEED = 0x43414E4F4E31L;
    /** Canonical learns and probes run one at a time: they read the one base game, and what they find must not depend on order. */
    private static final Object LOCK = new Object();
    private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();

    // ------------------------------------------------------------------ measurement: the registry and the dump

    private static final List<Object[]> READERS = new ArrayList<>();
    private static boolean hooked;
    /** The registry holds its readers for the JVM's life (measurement runs only): no more than this many. */
    private static final int MAX_READERS = 20_000;
    private static int dropped;

    /** Called by every new reader when {@link #DUMP} is set. */
    static void register(final ForcedWinModel.Reader r) {
        String role = "?";
        final StackTraceElement[] st = new Throwable().getStackTrace();
        for (StackTraceElement e : st) {
            final String cn = e.getClassName();
            if (!cn.startsWith("forge.ai.simulation.ForcedWinModel") && !cn.equals(ForcedWinCanon.class.getName())) {
                role = cn.substring(cn.lastIndexOf('.') + 1) + "." + e.getMethodName() + ":" + e.getLineNumber();
                if (!e.getMethodName().equals("newSharedReader") && !e.getMethodName().equals("load")
                        && !e.getMethodName().equals("<init>")) {
                    break;
                }
            }
        }
        synchronized (READERS) {
            if (READERS.size() >= MAX_READERS) {
                dropped++;
                return;
            }
            READERS.add(new Object[] {r, role});
            if (!hooked) {
                hooked = true;
                Runtime.getRuntime().addShutdownHook(new Thread(ForcedWinCanon::dumpAll, "reader-dump"));
            }
        }
    }

    /** Write every registered reader (templates once each, by content) to {@code DUMP/readers-<pid>.json}. */
    public static void dumpAll() {
        if (DUMP == null) {
            return;
        }
        final List<Object[]> all;
        synchronized (READERS) {
            all = new ArrayList<>(READERS);
        }
        try {
            final JsonObject out = new JsonObject();
            final JsonObject bodies = new JsonObject();
            final JsonArray readers = new JsonArray();
            int seq = 0;
            for (Object[] e : all) {
                final ForcedWinModel.Reader r = (ForcedWinModel.Reader) e[0];
                final JsonObject keys = new JsonObject();
                final Map<String, ACard> copy;
                synchronized (r.templates) {
                    copy = new TreeMap<>(r.templates);
                }
                for (Map.Entry<String, ACard> t : copy.entrySet()) {
                    if (r.stat != null) {
                        synchronized (r.stat) {
                            if (!r.stat.containsKey(t.getKey())) {
                                continue; // with read counts on: a template nobody read (a preloaded list) is left out
                            }
                        }
                    }
                    final String body;
                    synchronized (t.getValue()) {
                        body = GSON.toJson(t.getValue().instance(0));
                    }
                    final String h = hex(sha256(body.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
                    if (!bodies.has(h)) {
                        bodies.add(h, JsonParser.parseString(body));
                    }
                    final JsonObject k = new JsonObject();
                    k.addProperty("t", h);
                    if (r.stat != null) {
                        synchronized (r.stat) {
                            final long[] s = r.stat.get(t.getKey());
                            final java.util.BitSet ds = r.statDecisions.get(t.getKey());
                            if (s != null && ds != null) {
                                k.addProperty("reads", s[0]);
                                final JsonArray da = new JsonArray();
                                ds.stream().forEach(da::add);
                                k.add("decisions", da);
                            }
                        }
                    }
                    keys.add(t.getKey(), k);
                }
                final JsonObject o = new JsonObject();
                o.addProperty("seq", seq++);
                o.addProperty("role", (String) e[1]);
                o.addProperty("owner", r.statOwner);
                o.addProperty("table", r.table != null);
                o.add("keys", keys);
                if (keys.size() > 0) {
                    readers.add(o);
                }
            }
            out.addProperty("decisions", DECISION.get());
            out.addProperty("readersMade", all.size());
            out.addProperty("readersDropped", dropped);
            out.add("readers", readers);
            out.add("templates", bodies);
            Files.createDirectories(Paths.get(DUMP));
            final Path p = Paths.get(DUMP, "readers-" + ProcessHandle.current().pid() + ".json");
            final Path tmp = Paths.get(DUMP, "readers-" + ProcessHandle.current().pid() + ".json.tmp");
            Files.write(tmp, GSON.toJson(out).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            System.err.println("[reader-dump] failed: " + e);
        }
    }

    // ------------------------------------------------------------------ the canonical sandbox

    /** GameState's install, on the calling thread. */
    private static final class NowState extends GameState {
        void applyNow(final Game g) {
            applyGameOnThread(g);
        }
    }

    /** The canonical position of one card pool: the game, our seat in it, and our library's card of each name. */
    static final class Base {
        Game g;
        Player me;
        final Map<String, Card> byName = new HashMap<>();
        final List<String> pool = new ArrayList<>();
        String frame;
    }

    private static PaperCard paper(final String name) {
        return BeliefSampler.resolve(name);
    }

    /** How much a card's script does: the fewer lines, the plainer the object it makes in the sandbox. */
    private static int weight(final CardRules r) {
        int n = r.getOtherPart() != null ? 100 : 0;
        final ICardFace f = r.getMainPart();
        for (Iterable<String> it : List.of(f.getKeywords(), f.getAbilities(), f.getTriggers(), f.getStaticAbilities(), f.getReplacements())) {
            if (it != null) {
                for (String s : it) {
                    n += s == null ? 0 : 1;
                }
            }
        }
        return n;
    }

    /** The card-type bucket a pool card is ranked in (null = none: it only sits in our library). */
    private static String bucket(final CardType t) {
        if (t.isLand()) {
            return t.isBasicLand() ? null : "land";
        }
        if (t.isCreature()) {
            return "creature";
        }
        if (t.isPlaneswalker()) {
            return "planeswalker";
        }
        if (t.isArtifact()) {
            return t.hasSubtype("Equipment") || t.hasSubtype("Vehicle") ? null : "artifact";
        }
        if (t.isEnchantment()) {
            return t.hasSubtype("Aura") || t.hasSubtype("Saga") || t.hasSubtype("Class") ? null : "enchantment";
        }
        if (t.isInstant()) {
            return "instant";
        }
        if (t.isSorcery()) {
            return "sorcery";
        }
        return null;
    }

    private static final String[] PERMANENT = {"creature", "artifact", "enchantment", "planeswalker", "land"};
    private static final String[] ANY = {"creature", "artifact", "enchantment", "planeswalker", "land", "instant", "sorcery"};

    /**
     * The canonical position's frame for {@code pool} (GameState text; everything but our library, which is added card
     * by card). Per bucket the pool's cards are ordered by {@link #weight}, then name; pick k of a bucket is its k-th.
     * Battlefields: picks 0 (ours) and 1 (theirs) of each permanent type, and the five basic lands each. Graveyards: picks
     * 2 and 3 of every type. Hands: picks 4 and 5. A bucket with too few cards wraps around.
     */
    static String frame(final List<String> pool) {
        final Map<String, List<String>> by = new HashMap<>();
        final Map<String, Integer> w = new HashMap<>();
        final Map<String, String> loyalty = new HashMap<>();
        for (String name : pool) {
            final PaperCard pc = paper(name);
            if (pc == null || pc.getRules() == null) {
                continue;
            }
            final String b = bucket(pc.getRules().getType());
            if (b == null) {
                continue;
            }
            by.computeIfAbsent(b, x -> new ArrayList<>()).add(name);
            w.put(name, weight(pc.getRules()));
            if (b.equals("planeswalker")) {
                loyalty.put(name, pc.getRules().getMainPart().getInitialLoyalty());
            }
        }
        for (List<String> l : by.values()) {
            l.sort((x, y) -> w.get(x) != w.get(y).intValue() ? Integer.compare(w.get(x), w.get(y)) : x.compareTo(y));
        }
        final java.util.function.BiFunction<String, Integer, String> pick = (b, k) -> {
            final List<String> l = by.get(b);
            return l == null || l.isEmpty() ? null : l.get(k % l.size());
        };
        final java.util.function.BiFunction<String[], Integer, List<String>> picks = (types, k) -> {
            final List<String> out = new ArrayList<>();
            for (String b : types) {
                final String n = pick.apply(b, k);
                if (n != null) {
                    out.add(n);
                }
            }
            return out;
        };
        final java.util.function.Function<List<String>, String> field = names -> {
            final List<String> out = new ArrayList<>();
            for (String n : names) {
                final String l = loyalty.get(n);
                out.add(l != null && l.matches("\\d+") ? n + "|Counters:LOYALTY=" + l : n);
            }
            return String.join(";", out);
        };
        final List<String> basics = new ArrayList<>(List.of(BeliefSampler.basics()));
        final List<String> ourField = picks.apply(PERMANENT, 0), theirField = picks.apply(PERMANENT, 1);
        ourField.addAll(basics);
        theirField.addAll(basics);
        final List<String> theirLibrary = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            theirLibrary.addAll(basics);
        }
        return "[state]\nturn=5\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=20\nhumanlandsplayed=0\nailandsplayed=0\n"
                + "humanhand=" + String.join(";", picks.apply(ANY, 4)) + "\nhumanlibrary=\nhumangraveyard="
                + String.join(";", picks.apply(ANY, 2)) + "\nhumanexile=\nhumanbattlefield=" + field.apply(ourField)
                + "\naihand=" + String.join(";", picks.apply(ANY, 5)) + "\nailibrary=" + String.join(";", theirLibrary)
                + "\naigraveyard=" + String.join(";", picks.apply(ANY, 3)) + "\naiexile=\naibattlefield=" + field.apply(theirField)
                + "\n";
    }

    private interface Scoped<T> {
        T run();
    }

    /** {@code body} in its own id scope, AI cache scope and random stream (a function of the seed alone), HIDE off. */
    private static <T> T scoped(final long seed, final Scoped<T> body) {
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        final boolean prevHide = ForcedWinProbe.HIDE.get();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(seed));
        AiCache.openScope();
        forge.util.IdScope.open();
        ForcedWinProbe.HIDE.set(false); // nothing in a made-up position is hidden from anyone
        try {
            return body.run();
        } finally {
            ForcedWinProbe.HIDE.set(prevHide);
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
    }

    /** Build the canonical position of {@code pool} (names; unresolvable ones are left out). Never reads a real game. */
    static Base build(final List<String> pool) {
        return scoped(SEED, () -> {
            final Base b = new Base();
            final List<String> names = new ArrayList<>();
            for (String n : pool) {
                final PaperCard pc = paper(n);
                if (pc != null && !names.contains(pc.getName())) {
                    names.add(pc.getName());
                }
            }
            for (String n : BeliefSampler.basics()) {
                if (!names.contains(n)) {
                    names.add(n);
                }
            }
            java.util.Collections.sort(names);
            b.pool.addAll(names);
            final List<RegisteredPlayer> players = new ArrayList<>();
            final LobbyPlayerAi a = new LobbyPlayerAi("Canon0", null);
            a.setAiProfile("Default");
            final LobbyPlayerAi o = new LobbyPlayerAi("Canon1", null);
            o.setAiProfile("Default");
            players.add(new RegisteredPlayer(new Deck()).setPlayer(a));
            players.add(new RegisteredPlayer(new Deck()).setPlayer(o));
            final GameRules rules = new GameRules(GameType.Constructed);
            final Match match = new Match(rules, players, "Canon");
            final Game game = new Game(players, rules, match);
            game.setAge(GameStage.Play);
            b.frame = frame(names);
            final NowState gs = new NowState();
            gs.parse(java.util.Arrays.asList(b.frame.split("\\R")));
            gs.applyNow(game);
            game.getPhaseHandler().onStackResolved();
            b.g = game;
            b.me = game.getPlayers().get(0);
            for (String n : names) {
                final Card c = Card.fromPaperCard(paper(n), b.me);
                b.me.getZone(ZoneType.Library).add(c);
                b.byName.put(n, c);
            }
            return b;
        });
    }

    private interface WithCard<T> {
        T use(Card canonCard);
    }

    /**
     * {@code use} with the base game's copy of the card named as {@code like} (our library's, or for a card outside the
     * pool one put there for the call and taken out again, so the base never changes). Null when the card has no paper
     * card (a token): the caller learns it as without the key.
     */
    private static <T> T withCard(final Base b, final Card like, final WithCard<T> use) {
        final Card have = b.byName.get(like.getName());
        if (have != null) {
            return use.use(have);
        }
        final forge.item.IPaperCard pc = like.isToken() ? null : like.getPaperCard();
        if (pc == null) {
            return null;
        }
        final Card extra = Card.fromPaperCard(pc, b.me);
        if (!extra.getName().equals(like.getName())) {
            return null; // another face or a copy of something else: not the card that was asked about
        }
        b.me.getZone(ZoneType.Library).add(extra);
        try {
            return use.use(extra);
        } finally {
            b.me.getZone(ZoneType.Library).remove(extra);
        }
    }

    // ------------------------------------------------------------------ the stamp and the script hash

    private static volatile String stampOverride;
    private static String stampCache;

    /** Tests: the build id this JVM claims (null = its own). */
    static void setStampForTest(final String s) {
        stampOverride = s;
    }

    /**
     * The running build's id: sha256 of the jar the solver was loaded from (classes in a directory: "dir"), then a digest
     * of the JVM's {@code forge.*} system properties that are not per game (everything but forge.interactive.* and
     * forge.sound): flags that change what a sandbox does.
     */
    public static synchronized String stamp() {
        if (stampOverride != null) {
            return stampOverride;
        }
        if (stampCache == null) {
            String jar = "dir";
            try {
                final java.security.CodeSource cs = ForcedWinSolver.class.getProtectionDomain().getCodeSource();
                final Path p = cs == null ? null : Paths.get(cs.getLocation().toURI());
                if (p != null && Files.isRegularFile(p)) {
                    final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                    try (java.io.InputStream in = Files.newInputStream(p)) {
                        final byte[] buf = new byte[1 << 16];
                        for (int n; (n = in.read(buf)) > 0;) {
                            md.update(buf, 0, n);
                        }
                    }
                    jar = hex(md.digest());
                }
            } catch (Exception e) {
                jar = "unknown";
            }
            stampCache = jar + ":" + flagsDigest();
        }
        return stampCache;
    }

    /** The flags part of {@link #stamp}, readable: sorted {@code key=value} lines. */
    public static String flags() {
        final TreeMap<String, String> m = new TreeMap<>();
        for (String k : System.getProperties().stringPropertyNames()) {
            if (k.startsWith("forge.") && !k.startsWith("forge.interactive.") && !k.equals("forge.sound")
                    && !k.startsWith("forge.workClock")) {
                m.put(k, System.getProperty(k));
            }
        }
        final StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sb.toString();
    }

    private static String flagsDigest() {
        return hex(sha256(flags().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    }

    static byte[] sha256(final byte[] b) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(b);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(final byte[] b) {
        final StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static void face(final StringBuilder sb, final ICardFace f) {
        if (f == null) {
            sb.append("-\n");
            return;
        }
        sb.append(f.getName()).append('|').append(f.getType()).append('|').append(f.getManaCost()).append('|')
                .append(f.getPower()).append('/').append(f.getToughness()).append('|').append(f.getInitialLoyalty()).append('\n');
        for (Iterable<String> it : List.of(f.getKeywords(), f.getAbilities(), f.getTriggers(), f.getStaticAbilities(), f.getReplacements())) {
            sb.append("#\n");
            if (it != null) {
                for (String s : it) {
                    sb.append(s).append('\n');
                }
            }
        }
        final TreeMap<String, String> vars = new TreeMap<>();
        if (f.getVariables() != null) {
            for (Map.Entry<String, String> e : f.getVariables()) {
                vars.put(e.getKey(), e.getValue());
            }
        }
        vars.forEach((k, v) -> sb.append(k).append(':').append(v).append('\n'));
    }

    private static final ConcurrentHashMap<String, String> SCRIPT = new ConcurrentHashMap<>();

    /** A hash of the card's script as Forge loaded it (both faces: type line, costs, every ability line, variables). */
    public static String scriptHash(final String name) {
        return SCRIPT.computeIfAbsent(name, n -> {
            final PaperCard pc = paper(n);
            if (pc == null || pc.getRules() == null) {
                return "none";
            }
            final StringBuilder sb = new StringBuilder();
            face(sb, pc.getRules().getMainPart());
            face(sb, pc.getRules().getOtherPart());
            return hex(sha256(sb.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        });
    }

    // ------------------------------------------------------------------ the table

    /** One saved model: the card script's hash when it was learned, and the template. */
    static final class Entry {
        final String hash;
        final ACard card;

        Entry(final String hash, final ACard card) {
            this.hash = hash;
            this.card = card;
        }
    }

    public static final class Table {
        final String path;
        final String stamp;
        final List<String> pool = new ArrayList<>();
        final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
        /** Loop probes: "card|fromHand|partner" (partner "" = alone) -> script hashes "h1+h2" and the answer. */
        final ConcurrentHashMap<String, String[]> loops = new ConcurrentHashMap<>();
        private Base base;
        /** Null = nothing is appended (a table made in memory, or the generator's). */
        Path additions;
        public final AtomicLong hits = new AtomicLong(), learned = new AtomicLong(), stale = new AtomicLong(), tokens = new AtomicLong();

        Table(final String path, final String stamp) {
            this.path = path;
            this.stamp = stamp;
        }

        Base base() {
            synchronized (LOCK) {
                if (base == null) {
                    base = build(pool);
                }
                return base;
            }
        }

        /** The saved model of {@code key} for the card {@code c}, or null (none, or its script is not the one it was learned from). */
        ACard get(final String key, final Card c) {
            final Entry e = entries.get(key);
            if (e == null) {
                return null;
            }
            if (!e.hash.equals(scriptHash(c.getName()))) {
                stale.incrementAndGet();
                return null;
            }
            hits.incrementAndGet();
            return e.card;
        }

        /**
         * Learn {@code c} in the canonical sandbox (the caller has set the reader's probe flags on this thread) and keep it
         * under {@code key}. Null for a card with no paper card (a token), which the caller learns as without the key.
         */
        ACard learn(final String key, final Card c, final ForcedWinProbe.Stats st) {
            synchronized (LOCK) {
                final Entry have = entries.get(key);
                final String h = scriptHash(c.getName());
                if (have != null && have.hash.equals(h)) {
                    return have.card;
                }
                final Base b = base();
                final ACard t = scoped(ForcedWinSolver.mix(SEED, key.hashCode()),
                        () -> withCard(b, c, cc -> ForcedWinProbe.learn(b.g, b.me, cc, SEED, st)));
                if (t == null) {
                    tokens.incrementAndGet();
                    return null;
                }
                learned.incrementAndGet();
                if (!forge.ai.AiWork.spent()) {
                    entries.put(key, new Entry(h, t));
                    final JsonObject o = new JsonObject();
                    o.addProperty("k", key);
                    o.addProperty("h", h);
                    o.add("t", GSON.toJsonTree(t));
                    append(o);
                }
                return t;
            }
        }

        private static String loopKey(final Card c, final Card partner, final boolean fromHand, final String tag) {
            return tag + "|" + c.getName() + "|" + fromHand + "|" + (partner == null ? "" : partner.getName());
        }

        /** A saved loop probe's answer, or null. */
        Boolean loop(final Card c, final Card partner, final boolean fromHand, final String tag) {
            final String[] e = loops.get(loopKey(c, partner, fromHand, tag));
            if (e == null || !e[0].equals(scriptHash(c.getName()) + "+" + (partner == null ? "" : scriptHash(partner.getName())))) {
                return null;
            }
            return Boolean.valueOf(e[1]);
        }

        /**
         * The loop probe of {@code c} with {@code partner} in the canonical sandbox, within {@code budgetNanos}; kept when
         * it is an answer (as the live probe: a search cut short of the full budget without a win is none, null).
         */
        Boolean probeLoop(final Card c, final Card partner, final boolean fromHand, final String tag, final ForcedWinEngine.Limits lim,
                final long budgetNanos, final long fullNanos) {
            synchronized (LOCK) {
                final Boolean have = loop(c, partner, fromHand, tag);
                if (have != null) {
                    return have;
                }
                final Base b = base();
                final long t0 = System.nanoTime();
                final long seed = ForcedWinSolver.mix(SEED, loopKey(c, partner, fromHand, tag).hashCode());
                final Boolean w = scoped(seed, () -> withCard(b, c, cc -> partner == null
                        ? Boolean.valueOf(ForcedWinProbe.sandboxWin(b.g, b.me, cc.getId(), -1, fromHand, lim, seed, budgetNanos))
                        : withCard(b, partner, pp -> Boolean.valueOf(
                                ForcedWinProbe.sandboxWin(b.g, b.me, cc.getId(), pp.getId(), fromHand, lim, seed, budgetNanos)))));
                if (w == null) {
                    return null; // a token: no canonical answer
                }
                if (!w && budgetNanos < fullNanos && System.nanoTime() - t0 >= budgetNanos - budgetNanos / 10) {
                    return null;
                }
                final String h = scriptHash(c.getName()) + "+" + (partner == null ? "" : scriptHash(partner.getName()));
                loops.put(loopKey(c, partner, fromHand, tag), new String[] {h, String.valueOf(w)});
                final JsonObject o = new JsonObject();
                o.addProperty("loop", loopKey(c, partner, fromHand, tag));
                o.addProperty("h", h);
                o.addProperty("w", w);
                append(o);
                return w;
            }
        }

        private void append(final JsonObject o) {
            if (additions == null) {
                return;
            }
            o.addProperty("stamp", stamp);
            final byte[] line = (GSON.toJson(o) + "\n").getBytes(StandardCharsets.UTF_8);
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(additions, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                    java.nio.channels.FileLock lk = ch.lock()) {
                ch.write(java.nio.ByteBuffer.wrap(line));
            } catch (IOException | RuntimeException e) {
                System.err.println("[card-table] addition not saved: " + e);
            }
        }

        /** The table as the generator writes it. */
        public String toJson(final String note) {
            final JsonObject o = new JsonObject();
            o.addProperty("format", "mtgx-card-table/1");
            o.addProperty("stamp", stamp);
            o.addProperty("flags", flags());
            o.addProperty("note", note);
            final JsonArray p = new JsonArray();
            pool.forEach(p::add);
            o.add("pool", p);
            o.addProperty("frame", base().frame);
            final JsonObject es = new JsonObject();
            new TreeMap<>(entries).forEach((k, e) -> {
                final JsonObject x = new JsonObject();
                x.addProperty("h", e.hash);
                x.add("t", GSON.toJsonTree(e.card));
                es.add(k, x);
            });
            o.add("entries", es);
            final JsonObject ls = new JsonObject();
            new TreeMap<>(loops).forEach((k, e) -> {
                final JsonObject x = new JsonObject();
                x.addProperty("h", e[0]);
                x.addProperty("w", Boolean.parseBoolean(e[1]));
                ls.add(k, x);
            });
            o.add("loops", ls);
            return GSON.toJson(o);
        }

        public String summary() {
            return "entries=" + entries.size() + " loops=" + loops.size() + " hits=" + hits.get() + " learned=" + learned.get()
                    + " staleScripts=" + stale.get() + " tokens=" + tokens.get();
        }
    }

    /** The part of a loop probe's key that says which engine limits it ran under (a digest of the limits' settings). */
    static String limTag(final ForcedWinEngine.Limits lim) {
        try {
            return hex(sha256(GSON.toJson(lim).getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (RuntimeException | StackOverflowError e) {
            return "nolim";
        }
    }

    /** A new empty table of this JVM's stamp over {@code pool} (the generator's, and tests'). */
    public static Table newTable(final List<String> pool) {
        final Table t = new Table(null, stamp());
        t.pool.addAll(pool);
        return t;
    }

    /** Generator: the names the table's canonical position holds (the resolvable pool and the basic lands, sorted). */
    public static List<String> poolOf(final Table tb) {
        return new ArrayList<>(tb.base().pool);
    }

    /** Generator: learn {@code name} under the reader flags of {@code prefix} (a template key's flags, as "sl|tc|settle|"). */
    public static boolean generate(final Table tb, final String prefix, final String name) {
        final Base b = tb.base();
        final Card c = b.byName.get(name);
        if (c == null) {
            return false;
        }
        final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
        r.sourceLeaves = prefix.contains("sl|");
        r.tutorCast = prefix.contains("tc|");
        r.planScry = prefix.contains("scry|");
        r.probeSettle = prefix.contains("settle|");
        r.table = tb;
        r.card(b.g, b.me, c);
        return tb.entries.containsKey(prefix + name);
    }

    /** Generator: the loop probes of {@code name} with every creature of the pool (planLoops; nothing for other cards). */
    public static int generateLoops(final Table tb, final ForcedWinSolver.Config cfg, final String prefix, final String name) {
        final Base b = tb.base();
        final Card c = b.byName.get(name);
        final Entry e = tb.entries.get(prefix + name);
        if (c == null || e == null) {
            return 0;
        }
        final int before = tb.loops.size();
        ForcedWinSolver.fillLoopsCanon(tb, cfg, c, e.card, new ArrayList<>(b.byName.values()));
        return tb.loops.size() - before;
    }

    private static final ConcurrentHashMap<String, Object> OPEN = new ConcurrentHashMap<>();
    private static final Object REFUSED = new Object();

    /**
     * The table at {@code path}, loaded once per JVM; null when it is refused (missing, unreadable, or stamped by another
     * build or other flags: one log line, and the caller learns as without the key).
     */
    public static Table open(final String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        final Object o = OPEN.computeIfAbsent(path, ForcedWinCanon::load);
        return o == REFUSED ? null : (Table) o;
    }

    /** Tests: forget the tables opened so far. */
    static void forgetForTest() {
        OPEN.clear();
        synchronized (ForcedWinCanon.class) {
            stampCache = null;
        }
    }

    private static Object load(final String path) {
        final String mine = stamp();
        try {
            final JsonObject o = JsonParser.parseString(Files.readString(Paths.get(path))).getAsJsonObject();
            final String theirs = o.has("stamp") ? o.get("stamp").getAsString() : "none";
            if (!"mtgx-card-table/1".equals(o.has("format") ? o.get("format").getAsString() : "") || !mine.equals(theirs)) {
                System.err.println("[card-table] REFUSED " + path + ": stamp " + theirs + " is not this build's " + mine
                        + " (regenerate the table with this jar and these flags); card models are learned as without the key");
                return REFUSED;
            }
            final Table t = new Table(path, mine);
            for (JsonElement e : o.getAsJsonArray("pool")) {
                t.pool.add(e.getAsString());
            }
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("entries").entrySet()) {
                final JsonObject x = e.getValue().getAsJsonObject();
                t.entries.put(e.getKey(), new Entry(x.get("h").getAsString(), GSON.fromJson(x.get("t"), ACard.class)));
            }
            if (o.has("loops")) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("loops").entrySet()) {
                    final JsonObject x = e.getValue().getAsJsonObject();
                    t.loops.put(e.getKey(), new String[] {x.get("h").getAsString(), String.valueOf(x.get("w").getAsBoolean())});
                }
            }
            final int n0 = t.entries.size(), l0 = t.loops.size();
            t.additions = Paths.get(path + ".add.jsonl");
            int skipped = 0;
            if (Files.isRegularFile(t.additions)) {
                for (String line : Files.readAllLines(t.additions, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        final JsonObject x = JsonParser.parseString(line).getAsJsonObject();
                        if (!mine.equals(x.get("stamp").getAsString())) {
                            skipped++;
                        } else if (x.has("loop")) {
                            t.loops.putIfAbsent(x.get("loop").getAsString(),
                                    new String[] {x.get("h").getAsString(), String.valueOf(x.get("w").getAsBoolean())});
                        } else {
                            t.entries.putIfAbsent(x.get("k").getAsString(),
                                    new Entry(x.get("h").getAsString(), GSON.fromJson(x.get("t"), ACard.class)));
                        }
                    } catch (RuntimeException e) {
                        skipped++; // a torn or foreign line
                    }
                }
            }
            System.err.println("[card-table] loaded " + path + " stamp " + mine + ": " + n0 + " models, " + l0 + " loop probes; additions "
                    + (t.entries.size() - n0) + " models, " + (t.loops.size() - l0) + " loop probes, " + skipped + " lines skipped");
            return t;
        } catch (IOException | RuntimeException e) {
            System.err.println("[card-table] REFUSED " + path + ": " + e + "; card models are learned as without the key");
            return REFUSED;
        }
    }

    /** Insertion-ordered view for reports. */
    public static Map<String, Integer> sizes(final Table t) {
        final Map<String, Integer> m = new LinkedHashMap<>();
        m.put("entries", t.entries.size());
        m.put("loops", t.loops.size());
        return m;
    }
}
