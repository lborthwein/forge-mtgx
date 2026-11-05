package forge.bench.rl;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.PriorityDecisionListener;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.IdScope;

/**
 * Lane outcome-arm-1104 (frame-dump replay mode; off unless the bench config has {@code frameDump}): the cheap volume
 * path. A replayed (forced) tape game emits the deciding seat's v(o) frame at every priority decision of either seat and
 * at every forced choice ask the R3 labeller hooks, with NO label search: no solver, no proof check, no copy of the
 * game. The frame is the labeller's exactly: the same featurizer over the same seat-knowledge tracker, {@link
 * RlSearch#leafFrame}, the same numbering (dec_idx = priority decisions so far, both seats; choice_ord = the seat's
 * forced-choice ordinal of {@link forge.ai.AiTapeController}), computed under the same bench isolation. So a label row
 * and the dump row at the same (game_uid, seat, dec_idx[, choice_ord]) carry byte-identical payloads.
 *
 * <p>Rows are {@code mtgx-rl-labelsink/1} records ({@code b"MXL1" | u32le hlen | header JSON | u32le plen | DECIDE
 * payload}) of kind {@code value} with no target (target null, target_source none) and the tape result of the frame's
 * seat, so {@code tools/ml/rl/labels.py read_sink} reads them as label files. Nothing is written during the game; {@link
 * #finish} writes the game's rows only when its replay is digest-equal to the tape (else none, loudly).
 *
 * <p><b>The observation schedule matters.</b> {@link RlKnowledge} settles a pending sighting (a card that may have
 * turned face down) when it is next queried, against the card's visibility at that moment. So a frame depends on which
 * earlier positions were observed: a dump over band [lo, hi) reproduces a labeller of the same band position for
 * position, while a whole-game dump (hi &lt;= 0) settles at every decision and its band frames can show more known
 * cards than the banded dump's. Each dump is deterministic for its own band. With the research key
 * -Dforge.bench.rl.knowledgeSettle=fixed ({@link RlKnowledge#FIXED_SETTLE}) the tracker settles at fixed game points
 * only, and a frame no longer depends on the schedule (rows then carry {@code knowledge_settle: "fixed"}).
 *
 * <p>Outputs ({@link Sink}, one of each per JVM): {@code frames-<pid>.mxl.gz} (one gzip member per game) and {@code
 * games-<pid>.jsonl} (one row per replayed game, with its row counts and frame time).
 *
 * <p><b>Menus</b> (dump-menus-1105; {@code frameDump.menus}, off by default: then nothing here runs and every output is
 * unchanged). Each forced choice ask of either seat that poses a menu (the zone-change picks with their fetch list:
 * tutors, fetches; the choose-a-card / dig asks; entity picks; TARGETS, both a choice's and the targets an SA is
 * configured with before it is paid; MODE; OPTIONAL_TRIGGER) writes one {@code kind: "menu"} MXL1 record to {@code
 * menus-<pid>.mxm.gz}, a file of its own (not *.mxl.gz, so no label reader takes it): the frames file keeps its
 * bytes, and a games row only gains the menu counts.
 * The record's header carries every option (its tape reference {@code ref}, Forge id, card-index id {@code card}, its
 * token {@code tok} in the record's frame or -1 / -2 own player / -3 opponent, zone, owner and controller seat, target
 * slot), the chosen options' indices, the record's answer {@code chosen_a} and, per single-pick option, the answer
 * {@code a} the tape would hold had it been chosen; who chose ({@code by}: policy or forge, from the seat's controller;
 * the tape has no per-decision provenance, so a forced-win solver's pick on a forge seat reads forge); and the entry's
 * locator ({@code entry_k}, {@code entry_m}, {@code entry_post}, {@code entry_dup}). So a forced replay whose entry
 * answer is replaced by option i's {@code a} reaches that alternative's post-pick position. The payload is the asking
 * seat's frame at the ask (the answer decoded, not yet applied), observed through a FORK of the seat-knowledge
 * tracker, so the menu's observation settles nothing the frames see.
 */
public final class RlFrameDump implements PriorityDecisionListener {
    public static final String SCHEMA = "mtgx-bvw-frames/1";

    /** Shared by the replay threads of one JVM. */
    public static final class Sink {
        private final Path frames, games;

        public Sink(final Path dir) throws IOException {
            Files.createDirectories(dir);
            final long pid = ProcessHandle.current().pid();
            this.frames = dir.resolve("frames-" + pid + ".mxl.gz");
            this.games = dir.resolve("games-" + pid + ".jsonl");
        }

        synchronized void write(final byte[] member, final JsonObject game) throws IOException {
            if (member != null) {
                try (OutputStream o = new FileOutputStream(frames.toFile(), true)) {
                    o.write(member);
                }
            }
            Files.write(games, (RlWire.canonicalString(game) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        public Path framesFile() {
            return frames;
        }

        public Path gamesFile() {
            return games;
        }

        /** dump-menus-1105: the menus file (created by the first game that writes a menu). */
        public Path menusFile() {
            // MXL1 records, but not *.mxl.gz: readers that glob a dump's *.mxl.gz (label readers) never take a menu row
            return frames.resolveSibling(frames.getFileName().toString().replace("frames-", "menus-")
                    .replace(".mxl.gz", ".mxm.gz"));
        }

        synchronized void writeMenus(final byte[] member) throws IOException {
            try (OutputStream o = new FileOutputStream(menusFile().toFile(), true)) {
                o.write(member);
            }
        }
    }

    public static final String MENU_SCHEMA = "mtgx-bvw-menus/1";

    /** dump-menus-1105: the asks whose menus are recorded, and their family. */
    static final Map<String, String> MENU_FAMILY;

    static {
        final Map<String, String> m = new TreeMap<>();
        m.put("chooseSingleCardForZoneChange", "ZONE_CHANGE");
        m.put("chooseCardsForZoneChange", "ZONE_CHANGE");
        m.put("chooseCardsForEffect", "CARDS");
        m.put("chooseSingleEntityForEffect", "ENTITY");
        m.put("chooseEntitiesForEffect", "ENTITY");
        m.put("chooseTargetsFor", "TARGETS");
        m.put("chooseTarget", "TARGETS");
        m.put("chooseModeForAbility", "MODE");
        m.put("chooseSingleSpellForEffect", "MODE");
        m.put("chooseSpellAbilitiesForEffect", "MODE");
        m.put("confirmTrigger", "OPTIONAL_TRIGGER");
        MENU_FAMILY = java.util.Collections.unmodifiableMap(m);
    }

    /** The probe methods whose forced answers are choice rows (the R3 labeller's choose-a-card hooks). */
    static final java.util.Set<String> CHOICE_METHODS = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(
            java.util.Arrays.asList("chooseSingleCardForZoneChange", "orderMoveToZoneList", "arrangeForScry",
                    "arrangeForSurveil", "chooseCardsToDiscardFrom", "tuckCardsViaMulligan")));

    private final int bandLo, bandHi;
    private final boolean choices;
    private final JsonObject tape;
    private final long uid;
    private final int tapeTurns;
    private final String jarSha;
    private final Game game;
    private final RlFeaturizer feat;
    /** knowledge-settle-1104: the seat-knowledge tracker settles on the fixed schedule (recorded in rows only then). */
    private final boolean fixedSettle;
    private final List<byte[]> records = new ArrayList<>();
    private int decisions = 0, priorityRows = 0, choiceRows = 0;
    private double frameMs = 0;
    private final Map<String, Integer> byMethod = new TreeMap<>();
    /** dump-menus-1105: menus on; the knowledge tracker menus fork; their featurizer; records; counts. */
    private final boolean menus;
    private final RlKnowledge knowledge;
    private final RlFeaturizer menuFeat;
    private final List<byte[]> menuRecords = new ArrayList<>();
    private final int[] menuOrd = new int[2];
    private final Map<String, Integer> menuByMethod = new TreeMap<>();
    private final Map<String, Integer> menuDup = new java.util.HashMap<>();
    private int menuOptions = 0, menuWide = 0, menuSkipped = 0;
    private double menuMs = 0;

    /**
     * @param bandLo, bandHi turns before the end in [lo, hi) (hi &lt;= 0: no upper bound, every turn)
     * @param choices also the forced choice asks (installs an {@link forge.ai.AiTapeController} probe that only reads)
     */
    public RlFrameDump(final int bandLo, final int bandHi, final boolean choices, final JsonObject tapeLine,
            final String jarSha, final Game game, final RlKnowledge knowledge, final CardIndex index, final int obs) {
        this(bandLo, bandHi, choices, false, tapeLine, jarSha, game, knowledge, index, obs);
    }

    /** @param menus dump-menus-1105: also every forced menu ask's options ({@code frameDump.menus}; class comment) */
    public RlFrameDump(final int bandLo, final int bandHi, final boolean choices, final boolean menus,
            final JsonObject tapeLine, final String jarSha, final Game game, final RlKnowledge knowledge,
            final CardIndex index, final int obs) {
        RlReplayLabeller.checkTrainTape(tapeLine);
        if (bandLo < 0 || (bandHi > 0 && bandHi <= bandLo)) {
            throw new IllegalArgumentException("frameDump band [lo, hi): 0 <= lo, hi > lo or hi <= 0 (no bound), not ["
                    + bandLo + ", " + bandHi + ")");
        }
        this.bandLo = bandLo;
        this.bandHi = bandHi;
        this.choices = choices;
        this.tape = tapeLine;
        this.uid = RlWire.parseUid(tapeLine.get("game_uid").getAsString());
        this.tapeTurns = tapeLine.get("turns").getAsInt();
        this.jarSha = jarSha;
        this.game = game;
        this.feat = game == null ? null : new RlFeaturizer(index);
        this.fixedSettle = knowledge != null && knowledge.fixedSettle();
        if (feat != null) {
            feat.setVersion(obs);
            feat.reset();
            feat.setKnowledge(knowledge);
        }
        this.menus = menus;
        this.knowledge = knowledge;
        this.menuFeat = game != null && menus ? new RlFeaturizer(index) : null;
        if (menuFeat != null) {
            menuFeat.setVersion(obs);
            menuFeat.reset();
            forge.ai.AiTapeController.MENU_PROBES.put(game, this::menuSeen);
        }
        if (game != null && choices) {
            forge.ai.AiTapeController.PROBES.put(game, new forge.ai.AiTapeController.Probe() {
                @Override
                public Object divert(final forge.ai.AiTapeController c, final String method, final Object[] args,
                        final int ordinal) {
                    return NONE;
                }

                @Override
                public void seen(final forge.ai.AiTapeController c, final String method, final Object[] args,
                        final int ordinal, final Object value) {
                    if (CHOICE_METHODS.contains(method)) {
                        choiceFrame(c.getPlayer(), ordinal, method);
                    }
                }

                @Override
                public Object scripted(final forge.ai.AiTapeController c, final String method, final Object[] args) {
                    return NONE;
                }
            });
        }
    }

    private boolean inBand(final int tbe) {
        return tbe >= bandLo && (bandHi <= 0 || tbe < bandHi);
    }

    @Override
    public void beforeDecision(final Player p) {
        final Game g = game;
        final int ord = decisions++;
        if (g == null || g.isGameOver()) {
            return;
        }
        final int seat = g.getRegisteredPlayers().indexOf(p);
        final int turn = g.getPhaseHandler().getTurn();
        final int tbe = tapeTurns - turn;
        if (seat < 0 || seat > 1 || !inBand(tbe)) {
            return;
        }
        final String phase = String.valueOf(g.getPhaseHandler().getPhase());
        final int stack = g.getStack().size();
        final String stateSha = TrajectoryRef.stateSha(g);
        final Object[] got = frame(g, p, seat, turn, ord);
        priorityRows++;
        row(seat, ord, turn, tbe, phase, stack, stateSha, (byte[]) got[0], (Integer) got[1], "priority", -1, null);
    }

    @Override
    public void afterDecision(final Player p, final List<SpellAbility> chosen) {
    }

    /** A forced choice ask of a taped seat (after its recorded answer, as the labeller's choice rows). */
    private void choiceFrame(final Player p, final int ordinal, final String method) {
        final Game g = game;
        if (g == null || g.isGameOver()) {
            return;
        }
        final int seat = g.getRegisteredPlayers().indexOf(p);
        final int turn = g.getPhaseHandler().getTurn();
        final int tbe = tapeTurns - turn;
        if (seat < 0 || seat > 1 || !inBand(tbe)) {
            return;
        }
        final String phase = String.valueOf(g.getPhaseHandler().getPhase());
        final int stack = g.getStack().size();
        final Object[] got = PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> {
            final String stateSha = TrajectoryRef.stateSha(g);
            final Object[] f = frameIn(g, p, seat, turn, decisions);
            return new Object[] {f[0], f[1], stateSha};
        }));
        choiceRows++;
        byMethod.merge(method, 1, Integer::sum);
        row(seat, decisions, turn, tbe, phase, stack, (String) got[2], (byte[]) got[0], (Integer) got[1], "choice",
                ordinal, method);
    }

    /** The seat's own v(o) frame, under the bench isolation (as the labeller's priority rows). */
    private Object[] frame(final Game g, final Player p, final int seat, final int turn, final int decIdx) {
        return PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> frameIn(g, p, seat, turn, decIdx)));
    }

    private Object[] frameIn(final Game g, final Player p, final int seat, final int turn, final int decIdx) {
        final long f0 = System.nanoTime();
        final RlFeaturizer.Obs o = feat.observe(g, p, 0, false, null);
        final RlWire.Decide f = RlSearch.leafFrame(o, uid, seat, turn);
        f.decIdx = decIdx;
        if (f.hasPriv()) {
            throw new IllegalStateException("frame dump: a value frame with the privileged block");
        }
        final byte[] payload = RlWire.encodeDecide(f);
        frameMs += (System.nanoTime() - f0) / 1e6;
        return new Object[] {payload, f.C};
    }

    private void row(final int seat, final int ord, final int turn, final int tbe, final String phase, final int stack,
            final String stateSha, final byte[] payload, final int C, final String rowKind, final int choiceOrd,
            final String method) {
        final JsonObject h = new JsonObject();
        h.addProperty("kind", "value");
        h.addProperty("schema", SCHEMA);
        h.addProperty("split", "TRAIN");
        h.addProperty("game_uid", Long.toUnsignedString(uid));
        h.addProperty("seed", tape.get("seed").getAsLong());
        h.addProperty("seat", seat);
        h.addProperty("dec_idx", ord);
        h.addProperty("turn", turn);
        h.addProperty("phase", phase);
        h.addProperty("stack", stack);
        h.addProperty("state_sha", stateSha);
        h.addProperty("row_kind", rowKind);
        if (choiceOrd >= 0) {
            h.addProperty("choice_ord", choiceOrd);
            h.addProperty("choice_method", method);
        }
        h.addProperty("family", RlSchema.F_PRIORITY);
        h.addProperty("C", C);
        h.addProperty("obs", feat.version());
        h.addProperty("turns_before_end", tbe);
        h.addProperty("tape_turns", tapeTurns);
        h.addProperty("tape_reason", tape.has("reason") && !tape.get("reason").isJsonNull()
                ? tape.get("reason").getAsString() : "none");
        h.addProperty("tape_result", tape.getAsJsonArray("result").get(seat).getAsInt());
        h.add("target", com.google.gson.JsonNull.INSTANCE);
        h.addProperty("target_source", "none");
        h.addProperty("jar_sha", jarSha);
        if (fixedSettle) {
            h.addProperty("knowledge_settle", "fixed"); // knowledge-settle-1104 (absent = the query schedule)
        }
        h.add("controllers", tape.get("controllers"));
        final JsonArray dp = new JsonArray(), ds = new JsonArray();
        for (com.google.gson.JsonElement d : tape.getAsJsonArray("decks")) {
            dp.add(d.getAsJsonObject().get("path"));
            ds.add(d.getAsJsonObject().get("sha"));
        }
        h.add("decks", dp);
        h.add("deck_sha", ds);
        records.add(RlReplayLabeller.record(h, payload));
    }

    /** The game is over and its digest checked: its rows only when {@code digestEqual}; the games row says which. */
    public int finish(final Sink sink, final boolean digestEqual, final JsonObject replayRow) throws IOException {
        if (game != null && choices) {
            forge.ai.AiTapeController.PROBES.remove(game);
        }
        if (game != null && menus) {
            forge.ai.AiTapeController.MENU_PROBES.remove(game);
        }
        byte[] member = null;
        if (digestEqual && !records.isEmpty()) {
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bo)) {
                for (byte[] r : records) {
                    gz.write(r);
                }
            }
            member = bo.toByteArray();
        }
        if (!digestEqual) {
            System.err.println("[frame-dump] NOT EQUAL game " + Long.toUnsignedString(uid) + ": tape " + tape.get("digest")
                    + ", replay " + replayRow.get("digest_replay") + " -- its " + records.size()
                    + " frames are not written");
        }
        final JsonObject gr = new JsonObject();
        gr.addProperty("schema", SCHEMA);
        gr.addProperty("game_uid", Long.toUnsignedString(uid));
        gr.add("seed", tape.get("seed"));
        gr.add("result", tape.get("result"));
        gr.add("reason", tape.get("reason"));
        gr.addProperty("turns", tapeTurns);
        gr.add("digest_tape", tape.get("digest"));
        gr.add("digest_replay", replayRow.get("digest_replay"));
        gr.addProperty("equal", digestEqual);
        gr.addProperty("decisions", decisions);
        gr.addProperty("rows", digestEqual ? records.size() : 0);
        gr.addProperty("rows_void", digestEqual ? 0 : records.size());
        gr.addProperty("priority_rows", priorityRows);
        gr.addProperty("choice_rows", choiceRows);
        final JsonObject bm = new JsonObject();
        for (Map.Entry<String, Integer> e : byMethod.entrySet()) {
            bm.addProperty(e.getKey(), e.getValue());
        }
        gr.add("choice_by_method", bm);
        gr.addProperty("frame_ms", Math.round(frameMs));
        if (replayRow.has("cpu_ms")) {
            gr.add("cpu_ms", replayRow.get("cpu_ms"));
        }
        final JsonArray band = new JsonArray();
        band.add(bandLo);
        band.add(bandHi);
        gr.add("band", band);
        gr.addProperty("choices", choices);
        if (fixedSettle) {
            gr.addProperty("knowledge_settle", "fixed"); // knowledge-settle-1104
        }
        gr.addProperty("jar_sha", jarSha);
        byte[] menuMember = null;
        if (menus) {
            // dump-menus-1105 (absent when off: games rows unchanged)
            gr.addProperty("menus", digestEqual ? menuRecords.size() : 0);
            gr.addProperty("menus_void", digestEqual ? 0 : menuRecords.size());
            gr.addProperty("menu_options", menuOptions);
            gr.addProperty("menus_ge10", menuWide);
            gr.addProperty("menus_skipped", menuSkipped);
            final JsonObject mm = new JsonObject();
            for (Map.Entry<String, Integer> e : menuByMethod.entrySet()) {
                mm.addProperty(e.getKey(), e.getValue());
            }
            gr.add("menu_by_method", mm);
            gr.addProperty("menu_ms", Math.round(menuMs));
            if (digestEqual && !menuRecords.isEmpty()) {
                final ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (GZIPOutputStream gz = new GZIPOutputStream(bo)) {
                    for (byte[] r : menuRecords) {
                        gz.write(r);
                    }
                }
                menuMember = bo.toByteArray();
            }
        }
        if (sink != null) {
            if (menuMember != null) {
                sink.writeMenus(menuMember);
            }
            sink.write(member, gr);
        }
        replayRow.addProperty("frame_rows", digestEqual ? records.size() : 0);
        replayRow.addProperty("frame_rows_void", digestEqual ? 0 : records.size());
        if (menus) {
            replayRow.addProperty("menu_rows", digestEqual ? menuRecords.size() : 0);
        }
        return digestEqual ? records.size() : 0;
    }

    // ------------------------------------------------------------------ menus (dump-menus-1105)

    /** One option of a menu: a Card, Player, SpellAbility, AbilitySub, Boolean, or null ("none"). */
    static final class Opt {
        final Object o;
        final int slot;

        Opt(final Object o, final int slot) {
            this.o = o;
            this.slot = slot;
        }
    }

    /** A forced ask's menu: its options, the chosen options' indices and the pick bounds. */
    static final class MenuAsk {
        final String method, family;
        final List<Opt> opts = new ArrayList<>();
        final List<Integer> chosen = new ArrayList<>();
        int min = 1, max = 1;
        boolean optional;
        /** The answer encoding of a single pick, per option ({@link #answerOf}); null = no per-option answer. */
        String answer;
        SpellAbility sa;
        /** TARGETS: per targeted chain link [link, min, max]. */
        final JsonArray slots = new JsonArray();

        MenuAsk(final String method, final String family) {
            this.method = method;
            this.family = family;
        }

        int options() {
            int n = 0;
            for (Opt x : opts) {
                n += x.o == null ? 0 : 1;
            }
            return n;
        }
    }

    private static void addAll(final MenuAsk k, final Iterable<?> os, final int slot) {
        if (os != null) {
            for (Object o : os) {
                k.opts.add(new Opt(o, slot));
            }
        }
    }

    private static int indexOf(final MenuAsk k, final Object v, final int slot, final boolean[] used) {
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < k.opts.size(); i++) {
                final Opt x = k.opts.get(i);
                if ((used != null && used[i]) || (slot >= 0 && x.slot != slot)) {
                    continue;
                }
                if (pass == 0 ? x.o == v : java.util.Objects.equals(x.o, v)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void chosenAll(final MenuAsk k, final Iterable<?> vs, final int slot) {
        if (vs == null) {
            return;
        }
        final boolean[] used = new boolean[k.opts.size()];
        for (Object v : vs) {
            final int i = indexOf(k, v, slot, used);
            if (i >= 0) {
                used[i] = true;
            }
            k.chosen.add(i);
        }
    }

    private static void chosenOne(final MenuAsk k, final Object v) {
        k.chosen.add(indexOf(k, v, -1, null));
    }

    /**
     * The menu of a forced ask ({@link forge.ai.AiTapeController.MenuProbe#seen}'s arguments), or null when the ask
     * poses none (an SA configured without targets of its seat's choosing).
     */
    @SuppressWarnings("unchecked")
    static MenuAsk ask(final Game g, final Player p, final String m, final Object[] a, final Object v) {
        final boolean cfg = m.startsWith("cfg:");
        final String fam = cfg ? "TARGETS" : MENU_FAMILY.get(m);
        if (fam == null) {
            return null;
        }
        final MenuAsk k = new MenuAsk(m, fam);
        switch (cfg ? "cfg" : m) {
            case "chooseSingleCardForZoneChange": // {destination, origin, sa, fetchList, isOptional, decider}
                k.sa = (SpellAbility) a[2];
                k.optional = Boolean.TRUE.equals(a[4]);
                addAll(k, (Iterable<?>) a[3], -1);
                if (k.optional) {
                    k.opts.add(new Opt(null, -1));
                }
                k.min = k.optional ? 0 : 1;
                k.answer = "ref";
                chosenOne(k, v);
                break;
            case "chooseCardsForZoneChange": // {destination, origin, sa, fetchList, min, max, decider}
                k.sa = (SpellAbility) a[2];
                addAll(k, (Iterable<?>) a[3], -1);
                k.min = (Integer) a[4];
                k.max = (Integer) a[5];
                k.answer = k.max == 1 ? "refs" : null;
                chosenAll(k, (Iterable<?>) v, -1);
                break;
            case "chooseCardsForEffect": // {sourceList, sa, title, min, max, isOptional}
                k.sa = (SpellAbility) a[1];
                addAll(k, (Iterable<?>) a[0], -1);
                k.min = (Integer) a[3];
                k.max = (Integer) a[4];
                k.optional = Boolean.TRUE.equals(a[5]);
                k.answer = k.max == 1 ? "refs" : null;
                chosenAll(k, (Iterable<?>) v, -1);
                break;
            case "chooseSingleEntityForEffect": // {options, sa, isOptional}
                k.sa = (SpellAbility) a[1];
                k.optional = Boolean.TRUE.equals(a[2]);
                addAll(k, (Iterable<?>) a[0], -1);
                if (k.optional) {
                    k.opts.add(new Opt(null, -1));
                }
                k.min = k.optional ? 0 : 1;
                k.answer = "ref";
                chosenOne(k, v);
                break;
            case "chooseEntitiesForEffect": // {options, sa, min, max}
                k.sa = (SpellAbility) a[1];
                addAll(k, (Iterable<?>) a[0], -1);
                k.min = (Integer) a[2];
                k.max = (Integer) a[3];
                k.answer = k.max == 1 ? "refs" : null;
                chosenAll(k, (Iterable<?>) v, -1);
                break;
            case "chooseTarget": { // {options (stack item, target) pairs, saSrc}: the new target is the right
                k.sa = (SpellAbility) a[1];
                final List<Object> ps = (List<Object>) a[0];
                for (Object o : ps) {
                    k.opts.add(new Opt(o, -1));
                }
                k.answer = "index";
                chosenOne(k, v);
                break;
            }
            case "chooseSingleSpellForEffect": // {options, sa}
                k.sa = (SpellAbility) a[1];
                addAll(k, (List<?>) a[0], -1);
                k.answer = "index";
                chosenOne(k, v);
                break;
            case "chooseSpellAbilitiesForEffect": // {options, sa, num}
                k.sa = (SpellAbility) a[1];
                addAll(k, (List<?>) a[0], -1);
                k.min = (Integer) a[2];
                k.max = (Integer) a[2];
                k.answer = k.max == 1 ? "index" : null;
                chosenAll(k, (Iterable<?>) v, -1);
                break;
            case "chooseModeForAbility": { // {offered, sa, min, num}
                k.sa = (SpellAbility) a[1];
                final List<Object> offered = (List<Object>) a[0];
                addAll(k, offered, -1);
                k.min = (Integer) a[2];
                k.max = (Integer) a[3];
                k.answer = k.min == 1 && k.max == 1 ? "index" : null;
                if (v != null) {
                    for (Object s : (List<Object>) v) {
                        int i = offered.indexOf(s);
                        k.chosen.add(i);
                    }
                }
                break;
            }
            case "confirmTrigger": // {wrapper}
                k.sa = (SpellAbility) a[0];
                k.opts.add(new Opt(Boolean.TRUE, -1));
                k.opts.add(new Opt(Boolean.FALSE, -1));
                k.answer = "bool";
                k.chosen.add(Boolean.TRUE.equals(v) ? 0 : 1);
                break;
            case "chooseTargetsFor": // {sa}: its targets are the record's now
            case "cfg": // {sa}
                k.sa = (SpellAbility) a[0];
                if (k.sa == null || !targets(k, g, p, k.sa)) {
                    return null;
                }
                break;
            default:
                return null;
        }
        return k;
    }

    /** TARGETS: every targeted chain link this seat chooses for: its legal targets, the record's marked. */
    private static boolean targets(final MenuAsk k, final Game g, final Player p, final SpellAbility sa) {
        int link = 0;
        for (SpellAbility s = sa; s != null; s = s.getSubAbility(), link++) {
            if (!s.usesTargeting() || (s.getTargetingPlayer() != null && s.getTargetingPlayer() != p)) {
                continue;
            }
            final forge.game.spellability.TargetRestrictions tr = s.getTargetRestrictions();
            final List<Object> cands = new ArrayList<>();
            if (tr != null && s.getActivatingPlayer() != null) {
                cands.addAll(tr.getAllCandidates(s));
                if (tr.getZone() != null && tr.getZone().contains(forge.game.zone.ZoneType.Stack)) {
                    for (forge.game.spellability.SpellAbilityStackInstance si : g.getStack()) {
                        if (s.canTargetSpellAbility(si.getSpellAbility())) {
                            cands.add(si.getSpellAbility());
                        }
                    }
                }
            }
            for (Object t : s.getTargets()) {
                if (!cands.contains(t)) {
                    cands.add(t); // the record's target, which the enumeration no longer offers once it is set
                }
            }
            final int first = k.opts.size();
            addAll(k, cands, link);
            final JsonArray sl = new JsonArray();
            sl.add(link);
            sl.add(s.getMinTargets());
            sl.add(s.getMaxTargets());
            sl.add(first);
            sl.add(k.opts.size() - first);
            k.slots.add(sl);
            chosenAll(k, s.getTargets(), link);
        }
        if (k.slots.size() == 1 && k.slots.get(0).getAsJsonArray().get(2).getAsInt() == 1) {
            k.answer = "cfg";
        }
        k.min = 0;
        k.max = k.opts.size();
        return k.slots.size() > 0;
    }

    /** Option {@code x}'s answer as the tape would record it had it been the one chosen (single picks only). */
    static JsonElement answerOf(final Game g, final MenuAsk k, final int i, final JsonElement recorded) {
        final Opt x = k.opts.get(i);
        switch (k.answer) {
            case "ref":
                return x.o == null ? com.google.gson.JsonNull.INSTANCE
                        : new com.google.gson.JsonPrimitive(forge.ai.AiTapeCodec.ref(g, x.o));
            case "refs": {
                final JsonArray r = new JsonArray();
                r.add(forge.ai.AiTapeCodec.ref(g, x.o));
                return r;
            }
            case "index": {
                final JsonObject o = new JsonObject();
                final JsonArray r = new JsonArray();
                r.add(i);
                o.add("i", r);
                o.addProperty("of", k.opts.size());
                return o;
            }
            case "bool":
                return new com.google.gson.JsonPrimitive((Boolean) x.o);
            case "cfg": {
                if (recorded == null || !recorded.isJsonObject()) {
                    return null;
                }
                final JsonObject a = recorded.deepCopy().getAsJsonObject();
                final JsonObject c = k.method.startsWith("cfg:") ? a : a.getAsJsonObject("cfg");
                final JsonArray r = new JsonArray();
                r.add(forge.ai.AiTapeCodec.ref(g, x.o));
                c.getAsJsonArray("t").set(x.slot, r);
                return a;
            }
            default:
                return null;
        }
    }

    private static int seatOf(final Game g, final Player p) {
        return p == null ? -1 : g.getRegisteredPlayers().indexOf(p);
    }

    /** One option's JSON: its reference, ids, card-index id and token in {@code o} (null: no observation). */
    static JsonObject optionJson(final Game g, final Player p, final MenuAsk k, final int i, final RlFeaturizer.Obs o,
            final CardIndex index, final JsonElement recorded) {
        final Opt x = k.opts.get(i);
        final JsonObject j = new JsonObject();
        j.addProperty("i", i);
        if (x.slot >= 0) {
            j.addProperty("slot", x.slot);
        }
        Object t = x.o;
        if (t instanceof org.apache.commons.lang3.tuple.Pair) {
            t = ((org.apache.commons.lang3.tuple.Pair<?, ?>) t).getRight();
        }
        if (t == null) {
            j.addProperty("kind", "none");
        } else if (t instanceof Boolean) {
            j.addProperty("kind", (Boolean) t ? "yes" : "no");
        } else if (t instanceof forge.game.card.Card) {
            final forge.game.card.Card c = (forge.game.card.Card) t;
            j.addProperty("kind", "card");
            j.addProperty("ref", forge.ai.AiTapeCodec.ref(g, c));
            j.addProperty("id", c.getId());
            final boolean shown = !c.isFaceDown() || c.getView().canBeShownTo(p.getView());
            j.addProperty("card", index == null ? -1 : shown ? RlFeaturizer.resolveCard(index, c) : CardIndex.UNK);
            j.addProperty("tok", o == null ? -1 : o.pos(c));
            j.addProperty("zone", c.getZone() == null ? "none" : String.valueOf(c.getZone().getZoneType()));
            j.addProperty("owner", seatOf(g, c.getOwner()));
            j.addProperty("ctrl", seatOf(g, c.getController()));
            j.addProperty("token", c.isToken());
        } else if (t instanceof Player) {
            j.addProperty("kind", "player");
            j.addProperty("ref", forge.ai.AiTapeCodec.ref(g, t));
            j.addProperty("id", seatOf(g, (Player) t));
            j.addProperty("tok", t == p ? -2 : -3);
        } else if (t instanceof SpellAbility) {
            final SpellAbility s = (SpellAbility) t;
            final forge.game.card.Card h = s.getHostCard();
            j.addProperty("kind", t instanceof forge.game.spellability.AbilitySub ? "mode" : "ability");
            int tok = -1;
            for (forge.game.spellability.SpellAbilityStackInstance si : g.getStack()) {
                if (si.getSpellAbility() == s || si.getSpellAbility() == s.getRootAbility()) {
                    j.addProperty("ref", forge.ai.AiTapeCodec.ref(g, s));
                    final Integer q = o == null ? null : o.posByStackId.get(si.getId());
                    tok = q == null ? -1 : q;
                    break;
                }
            }
            if (h != null) {
                j.addProperty("host", h.getId());
                j.addProperty("card", index == null ? -1 : RlFeaturizer.resolveCard(index, h));
                if (tok < 0 && o != null && !(t instanceof forge.game.spellability.AbilitySub)) {
                    tok = o.pos(h);
                }
            }
            j.addProperty("tok", tok);
            j.addProperty("api", s.getApi() == null ? "none" : s.getApi().name());
        } else {
            j.addProperty("kind", t.getClass().getSimpleName());
        }
        if (k.answer != null) {
            final JsonElement a = answerOf(g, k, i, recorded);
            if (a != null) {
                j.add("a", a);
            }
        }
        return j;
    }

    /** {@link forge.ai.AiTapeController.MenuProbe}: a forced ask's menu record (in band, digest permitting at finish). */
    private void menuSeen(final Player p, final String method, final Object[] args, final JsonObject entry,
            final int forcedOrd, final Object value) {
        final Game g = game;
        if (g == null || g.isGameOver()) {
            return;
        }
        final int seat = g.getRegisteredPlayers().indexOf(p);
        final int turn = g.getPhaseHandler().getTurn();
        final int tbe = tapeTurns - turn;
        if (seat < 0 || seat > 1 || !inBand(tbe)) {
            return;
        }
        final long t0 = System.nanoTime();
        try {
            final String phase = String.valueOf(g.getPhaseHandler().getPhase());
            final int stack = g.getStack().size();
            final Object[] got = PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> {
                final MenuAsk k = ask(g, p, method, args, value);
                if (k == null) {
                    return null;
                }
                final String stateSha = TrajectoryRef.stateSha(g);
                // a fork of the seat's tracker: this observation settles nothing the frames see
                menuFeat.setKnowledge(knowledge == null ? null : knowledge.forkFor(g));
                final RlFeaturizer.Obs o = menuFeat.observe(g, p, 0, false, null);
                final RlWire.Decide f = RlSearch.leafFrame(o, uid, seat, turn);
                f.decIdx = decisions;
                if (f.hasPriv()) {
                    throw new IllegalStateException("menu dump: a value frame with the privileged block");
                }
                final JsonArray opts = new JsonArray();
                final JsonElement recorded = entry == null ? null : entry.get("a");
                for (int i = 0; i < k.opts.size(); i++) {
                    opts.add(optionJson(g, p, k, i, o, menuFeat.index(), recorded));
                }
                return new Object[] {k, opts, stateSha, RlWire.encodeDecide(f), f.C};
            }));
            if (got == null) {
                return;
            }
            final MenuAsk k = (MenuAsk) got[0];
            final JsonObject h = new JsonObject();
            h.addProperty("kind", "menu");
            h.addProperty("schema", MENU_SCHEMA);
            h.addProperty("split", "TRAIN");
            h.addProperty("game_uid", Long.toUnsignedString(uid));
            h.addProperty("seed", tape.get("seed").getAsLong());
            h.addProperty("seat", seat);
            h.addProperty("dec_idx", decisions);
            h.addProperty("menu_ord", menuOrd[seat]++);
            h.addProperty("forced_ord", forcedOrd);
            h.addProperty("turn", turn);
            h.addProperty("phase", phase);
            h.addProperty("stack", stack);
            h.addProperty("state_sha", (String) got[2]);
            h.addProperty("method", method);
            h.addProperty("family", k.family);
            final String ctl = tape.getAsJsonArray("controllers").get(seat).getAsString();
            h.addProperty("by", ctl.startsWith("rl:") ? "policy" : "forge");
            h.addProperty("controller", ctl);
            h.addProperty("n_options", k.options());
            h.addProperty("min", k.min);
            h.addProperty("max", k.max);
            h.addProperty("optional", k.optional);
            if (k.slots.size() > 0) {
                h.add("slots", k.slots);
            }
            if (k.sa != null && k.sa.getHostCard() != null) {
                h.addProperty("host", k.sa.getHostCard().getId());
                h.addProperty("host_card", RlFeaturizer.resolveCard(menuFeat.index(), k.sa.getHostCard()));
                h.addProperty("api", k.sa.getApi() == null ? "none" : k.sa.getApi().name());
            }
            h.add("options", (JsonArray) got[1]);
            final JsonArray ch = new JsonArray();
            for (int i : k.chosen) {
                ch.add(i);
            }
            h.add("chosen", ch);
            h.add("chosen_a", entry == null || !entry.has("a") ? com.google.gson.JsonNull.INSTANCE : entry.get("a"));
            final String ek = entry == null || !entry.has("k") ? "" : entry.get("k").getAsString();
            final String em = entry == null || !entry.has("m") ? "" : entry.get("m").getAsString();
            final String ep = entry == null || !entry.has("post") ? "" : entry.get("post").getAsString();
            h.addProperty("entry_k", ek);
            h.addProperty("entry_m", em);
            h.addProperty("entry_post", ep);
            h.addProperty("entry_dup", menuDup.merge(seat + "|" + ek + "|" + em + "|" + ep, 1, Integer::sum) - 1);
            h.addProperty("frame", "at_ask_forked_knowledge");
            h.addProperty("C", (Integer) got[4]);
            h.addProperty("obs", menuFeat.version());
            h.addProperty("turns_before_end", tbe);
            h.addProperty("tape_turns", tapeTurns);
            h.addProperty("tape_result", tape.getAsJsonArray("result").get(seat).getAsInt());
            h.addProperty("jar_sha", jarSha);
            if (fixedSettle) {
                h.addProperty("knowledge_settle", "fixed");
            }
            menuRecords.add(RlReplayLabeller.record(h, (byte[]) got[3]));
            menuByMethod.merge(method, 1, Integer::sum);
            menuOptions += k.options();
            menuWide += k.options() >= 10 ? 1 : 0;
        } catch (RuntimeException e) {
            // a menu that cannot be read is counted and skipped; the game and its frames go on unchanged
            menuSkipped++;
            if (menuSkipped <= 3) {
                System.err.println("[frame-dump] menu skipped (" + method + ") game " + Long.toUnsignedString(uid) + ": "
                        + e);
            }
        } finally {
            menuMs += (System.nanoTime() - t0) / 1e6;
        }
    }
}
