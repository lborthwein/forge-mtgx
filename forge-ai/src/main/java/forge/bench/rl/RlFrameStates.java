package forge.bench.rl;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonObject;

import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.GameState;
import forge.game.PriorityDecisionListener;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.IdScope;

/**
 * Lane frame-score-1105 (off unless the bench config has {@code frameStates}, which needs {@code frameDump}): beside a
 * frame-dump replay, the Forge position of every {@code every}-th priority decision as a {@link GameState} text frame
 * plus its {@link RlFrameObs#sidecar}, keyed as the dump's rows (game_uid, seat, dec_idx), so {@code FrameObsRun} can
 * featurize the frame in another JVM and be compared byte for byte with the dump's payload. Added after the dump's
 * listener: it reads the tracker after the dump's frame settled it. It only reads the game, under the bench isolation.
 *
 * <p>Out: {@code states-<pid>.jsonl.gz} (one gzip member per digest-equal game; schema {@link #SCHEMA}).
 */
public final class RlFrameStates implements PriorityDecisionListener {
    public static final String SCHEMA = "mtgx-rl-frame-states/1";

    /** One per JVM. */
    public static final class Sink {
        private final Path file;

        public Sink(final Path dir) throws IOException {
            Files.createDirectories(dir);
            this.file = dir.resolve("states-" + ProcessHandle.current().pid() + ".jsonl.gz");
        }

        synchronized void write(final byte[] member) throws IOException {
            try (OutputStream o = new FileOutputStream(file.toFile(), true)) {
                o.write(member);
            }
        }

        public Path file() {
            return file;
        }
    }

    private final int every;
    private final long uid;
    private final int tapeTurns;
    private final Game game;
    private final RlKnowledge know;
    private final CardIndex index;
    private final int obs;
    private final List<String> rows = new ArrayList<>();
    private int decisions = 0;
    private final List<List<Object[]>> decks;

    public RlFrameStates(final int every, final JsonObject tapeLine, final Game game, final RlKnowledge know,
            final CardIndex index, final int obs) {
        if (every < 1) {
            throw new IllegalArgumentException("frameStates.every >= 1");
        }
        this.every = every;
        this.uid = RlWire.parseUid(tapeLine.get("game_uid").getAsString());
        this.tapeTurns = tapeLine.get("turns").getAsInt();
        this.game = game;
        this.know = know;
        this.index = index;
        this.obs = obs;
        this.decks = RlFrameObs.decks(game);
    }

    @Override
    public void beforeDecision(final Player p) {
        final int ord = decisions++;
        final Game g = game;
        if (g == null || g.isGameOver() || ord % every != 0) {
            return;
        }
        final int seat = g.getRegisteredPlayers().indexOf(p);
        if (seat < 0 || seat > 1) {
            return;
        }
        final JsonObject r = PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> {
            final JsonObject o = new JsonObject();
            o.addProperty("schema", SCHEMA);
            o.addProperty("game_uid", Long.toUnsignedString(uid));
            o.addProperty("seat", seat);
            o.addProperty("dec_idx", ord);
            o.addProperty("turn", g.getPhaseHandler().getTurn());
            o.addProperty("turns_before_end", tapeTurns - g.getPhaseHandler().getTurn());
            o.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
            o.addProperty("stack", g.getStack().size());
            o.addProperty("obs", obs);
            o.addProperty("knowledge_settle", know != null && know.fixedSettle() ? "fixed" : "query");
            final GameState gs = new GameState();
            gs.initFromGame(g);
            o.addProperty("frame", gs.toString());
            o.add("rl", RlFrameObs.sidecar(g, know, decks));
            return o;
        }));
        // the payload the dump wrote at this decision (same featurizer, same tracker state): the proof's reference sha
        final RlFrameObs.Frame f = RlFrameObs.valueFrame(g, p, know, index, obs, uid, ord);
        r.addProperty("payload_sha256", f.sha256());
        rows.add(RlWire.canonicalString(r));
    }

    @Override
    public void afterDecision(final Player p, final List<SpellAbility> chosen) {
    }

    /** The game's rows, only when its replay is digest-equal; returns the number written. */
    public int finish(final Sink sink, final boolean digestEqual) throws IOException {
        if (!digestEqual || rows.isEmpty() || sink == null) {
            return 0;
        }
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bo)) {
            for (String s : rows) {
                gz.write((s + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        sink.write(bo.toByteArray());
        return rows.size();
    }
}
