package forge.bench;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.bench.rl.CardIndex;
import forge.bench.rl.RlFrameObs;
import forge.bench.rl.RlKnowledge;
import forge.bench.rl.RlWire;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameState;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.item.PaperCard;

/**
 * Lane frame-score-1105 (a research entry point; nothing runs it unless asked): saved Forge positions -> the RL
 * featurizer's value frames, many frames in one JVM. Each frame (a {@link GameState} text: tools/forge/from-frame.mjs,
 * ComboTrace's frames, choice-search's post-pick frames, or {@link forge.bench.rl.RlFrameStates} rows) is installed in a
 * fresh two-seat game through the frame-install path of FrameLabelRun / ForcedWinSolverTest (GameState applied on the
 * game thread, then {@code onStackResolved}), its sidecar (when it has one) restores what the text does not carry
 * ({@link RlFrameObs#applySidecar}: decklists, the seat-knowledge tracker, per-turn counters), and the seat's frame is
 * taken by {@link RlFrameObs#valueFrame}: the frame dump's own featurizer path.
 *
 * <pre>
 * java -cp JAR forge.bench.FrameObsRun --card-index card-index.tsv --out DIR
 *      (--rows ROWS.json | --states STATES.jsonl.gz ...) [--obs 2] [--bare] [--limit N]
 * </pre>
 * ROWS.json = {"rows": [{"id", "frame": path | "frame_text": text, "seat": 0|1, "rl": path | object (optional),
 * "game_uid", "dec_idx" (optional: the payload header; default 0)}]}. --states: RlFrameStates rows (frame and sidecar
 * inline). --bare ignores every sidecar (an empty tracker, empty decklists): what a bare frame gives.
 * Out: DIR/obs.mxl.gz (one gzip member of mtgx-rl-labelsink/1 value records, tools/ml/rl/labels.py read_sink), DIR/index.jsonl (one row per
 * frame: id, ok or error, payload_sha256, C, truncated, ms), DIR/summary.json. cwd = the forge-gui dir (res/).
 */
public final class FrameObsRun {
    public static final String SCHEMA = "mtgx-rl-frame-obs/1";

    private FrameObsRun() {
    }

    /** GameState's install, on the calling thread (ForcedWinSolverTest.NowState). */
    static final class NowState extends GameState {
        void applyNow(final Game g) {
            applyGameOnThread(g);
        }
    }

    /** A fresh two-seat game holding {@code text}; seat decklists from {@code decks} (null or empty = an empty deck). */
    static Game install(final String text, final JsonArray decks) {
        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) {
            final LobbyPlayerAi a = new LobbyPlayerAi("Seat" + s, null);
            a.setAiProfile("Default");
            players.add(new RegisteredPlayer(deck(decks == null || decks.size() <= s ? null
                    : decks.get(s).getAsJsonArray())).setPlayer(a));
        }
        final GameRules rules = new GameRules(GameType.Constructed);
        final Match match = new Match(rules, players, "FrameObsRun");
        final Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        final NowState gs = new NowState();
        gs.parse(Arrays.asList(text.split("\\R")));
        gs.applyNow(game);
        game.getPhaseHandler().onStackResolved();
        return game;
    }

    static Deck deck(final JsonArray list) {
        final Deck d = new Deck();
        if (list == null) {
            return d;
        }
        for (JsonElement e : list) {
            final JsonArray x = e.getAsJsonArray();
            final String name = x.get(0).getAsString();
            PaperCard pc = StaticData.instance().getCommonCards().getCard(name);
            if (pc == null) {
                StaticData.instance().attemptToLoadCard(name);
                pc = StaticData.instance().getCommonCards().getCard(name);
            }
            if (pc == null) {
                throw new IllegalArgumentException("sidecar deck: no card named " + name);
            }
            d.getOrCreate(DeckSection.Main).add(pc, x.get(1).getAsInt());
        }
        return d;
    }

    static final class Args {
        String cardIndex, out, rows;
        final List<String> states = new ArrayList<>();
        int obs = 2;
        boolean bare = false;
        int limit = 0;
    }

    static Args parse(final String[] a) {
        final Args x = new Args();
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--card-index": x.cardIndex = a[++i]; break;
                case "--out": x.out = a[++i]; break;
                case "--rows": x.rows = a[++i]; break;
                case "--states":
                    while (i + 1 < a.length && !a[i + 1].startsWith("--")) {
                        x.states.add(a[++i]);
                    }
                    break;
                case "--obs": x.obs = Integer.parseInt(a[++i]); break;
                case "--bare": x.bare = true; break;
                case "--limit": x.limit = Integer.parseInt(a[++i]); break;
                default: throw new IllegalArgumentException("FrameObsRun: unknown argument " + a[i]);
            }
        }
        if (x.cardIndex == null || x.out == null || (x.rows == null) == x.states.isEmpty()) {
            throw new IllegalArgumentException("FrameObsRun: --card-index, --out and one of --rows / --states");
        }
        return x;
    }

    /** The rows to featurize: {id, frame_text, seat, rl (object or absent), game_uid, dec_idx}. */
    static List<JsonObject> rows(final Args a) throws IOException {
        final List<JsonObject> out = new ArrayList<>();
        if (a.rows != null) {
            final Path base = Paths.get(a.rows).toAbsolutePath().getParent();
            final JsonArray rs = JsonParser.parseString(Files.readString(Paths.get(a.rows))).getAsJsonObject()
                    .getAsJsonArray("rows");
            for (JsonElement e : rs) {
                final JsonObject r = e.getAsJsonObject().deepCopy();
                if (!r.has("frame_text")) {
                    r.addProperty("frame_text", Files.readString(base.resolve(r.get("frame").getAsString())));
                }
                if (r.has("rl") && r.get("rl").isJsonPrimitive()) {
                    r.add("rl", JsonParser.parseString(Files.readString(base.resolve(r.get("rl").getAsString()))));
                }
                out.add(r);
            }
        } else {
            for (String f : a.states) {
                try (InputStream in = open(f);
                        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.isBlank()) {
                            continue;
                        }
                        final JsonObject s = JsonParser.parseString(line).getAsJsonObject();
                        final JsonObject r = new JsonObject();
                        r.addProperty("id", s.get("game_uid").getAsString() + ":" + s.get("seat").getAsInt() + ":"
                                + s.get("dec_idx").getAsInt());
                        r.add("frame_text", s.get("frame"));
                        r.add("seat", s.get("seat"));
                        r.add("rl", s.get("rl"));
                        r.add("game_uid", s.get("game_uid"));
                        r.add("dec_idx", s.get("dec_idx"));
                        r.add("payload_sha256_ref", s.get("payload_sha256"));
                        out.add(r);
                    }
                }
            }
        }
        if (a.limit > 0 && out.size() > a.limit) {
            return new ArrayList<>(out.subList(0, a.limit));
        }
        return out;
    }

    static InputStream open(final String f) throws IOException {
        final InputStream in = new FileInputStream(f);
        return f.endsWith(".gz") ? new GZIPInputStream(in, 1 << 16) : in;
    }

    public static void main(final String[] argv) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        final Args a = parse(argv);
        final long tBoot = System.nanoTime();
        RlActorBench.boot();
        final CardIndex index = CardIndex.load(Paths.get(a.cardIndex));
        final double bootS = (System.nanoTime() - tBoot) / 1e9;
        final List<JsonObject> rows = rows(a);
        Files.createDirectories(Paths.get(a.out));
        int ok = 0, err = 0, refEq = 0, refNe = 0;
        double ms = 0;
        final long t0 = System.nanoTime();
        try (OutputStream mxl = new java.util.zip.GZIPOutputStream(new FileOutputStream(Paths.get(a.out, "obs.mxl.gz").toFile()), 1 << 16);
                OutputStream idx = new FileOutputStream(Paths.get(a.out, "index.jsonl").toFile())) {
            for (JsonObject r : rows) {
                final JsonObject o = new JsonObject();
                o.add("id", r.get("id"));
                o.add("seat", r.get("seat"));
                final long f0 = System.nanoTime();
                try {
                    final JsonObject sc = a.bare || !r.has("rl") || r.get("rl").isJsonNull() ? null
                            : r.getAsJsonObject("rl");
                    final Game g = install(r.get("frame_text").getAsString(), sc == null ? null
                            : sc.getAsJsonArray("decks"));
                    RlKnowledge k = sc == null ? null : RlFrameObs.applySidecar(g, sc);
                    if (k == null) {
                        k = new RlKnowledge(g); // a bare frame: a tracker that has observed nothing
                        k.v2 = a.obs == 2;
                    }
                    final int seat = r.get("seat").getAsInt();
                    final Player me = g.getRegisteredPlayers().get(seat);
                    final long uid = r.has("game_uid") ? RlWire.parseUid(r.get("game_uid").getAsString()) : 0L;
                    final int dec = r.has("dec_idx") ? r.get("dec_idx").getAsInt() : 0;
                    final RlFrameObs.Frame f = RlFrameObs.valueFrame(g, me, k, index, a.obs, uid, dec);
                    final JsonObject h = new JsonObject();
                    h.addProperty("kind", "value");
                    h.addProperty("schema", SCHEMA);
                    h.add("id", r.get("id"));
                    h.addProperty("game_uid", Long.toUnsignedString(uid));
                    h.addProperty("seat", seat);
                    h.addProperty("dec_idx", dec);
                    h.addProperty("turn", g.getPhaseHandler().getTurn());
                    h.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
                    h.addProperty("stack", g.getStack().size());
                    h.addProperty("family", forge.bench.rl.RlSchema.F_PRIORITY);
                    h.addProperty("C", f.C);
                    h.addProperty("obs", f.obs);
                    h.addProperty("sidecar", sc != null);
                    h.add("target", com.google.gson.JsonNull.INSTANCE);
                    h.addProperty("target_source", "none");
                    mxl.write(RlFrameObs.record(h, f.payload));
                    o.addProperty("ok", true);
                    o.addProperty("payload_sha256", f.sha256());
                    o.addProperty("C", f.C);
                    o.addProperty("truncated", f.truncated);
                    o.addProperty("sidecar", sc != null);
                    o.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
                    o.addProperty("stack", g.getStack().size());
                    if (r.has("payload_sha256_ref") && !r.get("payload_sha256_ref").isJsonNull()) {
                        final boolean eq = r.get("payload_sha256_ref").getAsString().equals(f.sha256());
                        o.addProperty("ref_equal", eq);
                        if (eq) {
                            refEq++;
                        } else {
                            refNe++;
                        }
                    }
                    ok++;
                } catch (RuntimeException | Error e) {
                    o.addProperty("ok", false);
                    o.addProperty("error", String.valueOf(e));
                    err++;
                }
                final double fm = (System.nanoTime() - f0) / 1e6;
                ms += fm;
                o.addProperty("ms", Math.round(fm * 10) / 10.0);
                idx.write((RlWire.canonicalString(o) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        final double wall = (System.nanoTime() - t0) / 1e9;
        final JsonObject s = new JsonObject();
        s.addProperty("schema", SCHEMA);
        s.addProperty("frames", rows.size());
        s.addProperty("ok", ok);
        s.addProperty("errors", err);
        s.addProperty("ref_equal", refEq);
        s.addProperty("ref_differ", refNe);
        s.addProperty("bare", a.bare);
        s.addProperty("obs", a.obs);
        s.addProperty("boot_s", Math.round(bootS * 100) / 100.0);
        s.addProperty("wall_s", Math.round(wall * 100) / 100.0);
        s.addProperty("frames_per_s", rows.isEmpty() ? 0 : Math.round(rows.size() / wall * 100) / 100.0);
        s.addProperty("ms_per_frame", rows.isEmpty() ? 0 : Math.round(ms / rows.size() * 10) / 10.0);
        Files.writeString(Paths.get(a.out, "summary.json"), RlWire.canonicalString(s) + "\n");
        System.err.println("[frame-obs] " + s);
        System.exit(0);
    }
}
