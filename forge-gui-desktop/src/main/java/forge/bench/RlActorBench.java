/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.bench;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.GuiDesktop;
import forge.LobbyPlayer;
import forge.ai.AiCache;
import forge.bench.rl.CardIndex;
import forge.bench.rl.RlClient;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlKnowledge;
import forge.bench.rl.RlSchema;
import forge.bench.rl.RlSearch;
import forge.bench.rl.RlSeat;
import forge.bench.rl.RlTape;
import forge.bench.rl.RlWire;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.player.PlayerOutcome;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.IdScope;
import forge.util.MyRandom;

/**
 * The RL actor runner (lane rl-r0-b1-1005; GOAL rev 5, R0 Phase A): game threads that play Forge games whose bridged
 * seats are answered by {@link RlSeat} over {@code mtgx-rl-wire/1} (interfaces.md §2), one TCP connection per game
 * thread. Nothing else uses this class; it changes no existing behaviour.
 *
 * <p>Modes: {@code train} (RL seats vs the server's policy slots, privileged block when GAME says so), {@code eval}
 * (EVAL decks only, never a privileged block), {@code record} (Forge Default on the recorder seats, Forge's answers
 * sent as RECORD frames), {@code replay} (a tape's line(s) replayed without a server; digests compared).
 *
 * <p>Games are set up exactly as {@code RlSimBench} sets up a {@code policy:"forge"} game (same lobby seats, rules,
 * per-thread random stream seeded with the GAME seed, id scope, AI-cache scope, AI timeout), so a record game and an
 * RlSimBench Forge game on the same seed and decks give the same digest (the do-no-harm check).
 *
 * <p>Usage: {@code java -cp forge.jar forge.bench.RlActorBench <cfg.json>} or
 * {@code ... RlActorBench --mode replay --tape <file> --line <n> [--card-index <tsv>] [--rl-root <dir>] [--out <f>]};
 * cwd = forge-gui with res/. Config keys: {@code mode, server, actorId, threads, rlRoot, cardIndex, tapesDir,
 * capActions (40), gameTimeoutSec (300), maxDecisions (3000), jarSha, aiTimeoutSec (5), readTimeoutSec (120),
 * tapeRotate (1000), maxGames (0 = until stop), replay {tapes, lines [[fileIdx, line]], out}, k8 (the spec of a
 * {@code lookahead:K8} opponent seat, lane gen-check-1007), loopGuard (0 = off; the live seat's "Cancel Search?" loop
 * guard on the RL seats, lane exploit-panel-1015), watchdog (replay mode: the stall watchdog,
 * {@link ReplayStallWatchdog}; on by default, {@code false} = off)}. Opponent controllers besides {@code forge}:
 * {@code forge:<Profile>} (Forge AI under a shipped {@code res/ai} profile; train and eval) and {@code lookahead:K8}
 * (Forge AI Default plus the look-ahead with the {@code k8} spec; eval only).
 * Exit codes: 0 done; 2 config / HELLO refused / deck guard; 3 a thread died on a transport or protocol error;
 * 4 replay finished with an unequal digest; 5 replay stalled (the stall watchdog voided its unfinished games).
 */
public final class RlActorBench {

    private RlActorBench() {
    }

    // ------------------------------------------------------------------------------------------------ config

    static final class Cfg {
        String mode = "train";
        String server = null;
        String actorId = "a00";
        int threads = 1;
        String rlRoot = System.getProperty("user.home") + "/mtgx-data/rl";
        String cardIndex = null;
        String tapesDir = null;
        int capActions = 40;
        int gameTimeoutSec = 300;
        int maxDecisions = 3000;
        String jarSha = null;
        int aiTimeoutSec = 5;
        int readTimeoutSec = 120;
        int tapeRotate = 1000;
        int maxGames = 0;
        /** MyRandom's global (non-thread) random, seeded once at boot; RlSimBench seeds it with its config seed. */
        long globalSeed = 0x5eedL;
        /** Debug only (K8 converter parity, B6): one JSONL row per sent frame with the ForgeState at that moment. */
        String debugForgeStateOut = null;
        List<String> replayTapes = new ArrayList<>();
        List<int[]> replayLines = new ArrayList<>();
        String replayOut = null;
        /** Replay every (or a seeded sample of N) non-void line of every tape under this directory. */
        String replayDir = null;
        int replaySample = 0;
        long replaySampleSeed = 1005L;
        /** Observation schema: 1 (mtgx-rl-obs/1, wire/1; the default) or 2 (mtgx-rl-obs/2, wire/2; lane rl-obs-v2-1006). */
        int obsSchema = 1;
        /**
         * S1 (lane s1-search-1007; null = off, the default, and nothing changes): the look-ahead over the RL seats' own
         * priority decisions ({@link RlSearch.Config}), eval and train modes, in the actor's observation schema (obs-v1,
         * or obs-v2 since lane search-v2-1009; a spec {@code obs} pin must equal {@code obsSchema}).
         */
        RlSearch.Config search = null;
        /**
         * gen-check-1007 (null = none): the spec of a {@code lookahead:K8} opponent seat: keys of LookaheadBench's
         * "lookahead" block ({@link #K8_KEYS}) plus the seat's own Forge AI options aiFixes0928 and fairNaming. A GAME's
         * (or a replayed tape's) own "k8" object takes precedence; the tape records the spec it played.
         */
        JsonObject k8 = null;
        /**
         * exploit-panel-1015 (0 = off, the default, and nothing changes): the RL seats' "Cancel Search?" loop guard
         * ({@link RlSeat.Config#loopGuard}, fork #85), as the live policy seat sets it ({@code loopGuard=2} in the live
         * spec). Without it a bench seat that answers Show and Tell's card ask with no card and the confirm with no
         * repeats the pair until {@link #maxDecisions} voids the game; live, the guard answers yes after K cycles.
         */
        int loopGuard = 0;
        /**
         * bvw-plr-1023 (null = off, the default, and nothing changes): record mode without a server. A JSONL file of GAME
         * messages, played in order by the actor's threads; the RECORD frames go nowhere (the tapes are the output) and
         * each game's GAME_END row goes to {@code <tapesDir>/games-<pid>.jsonl}.
         */
        String gamesFile = null;
        /**
         * bvw-plr-1023 (null = off, the default): replay mode labels every replayed game with the honest solver
         * ({@link forge.bench.rl.RlReplayLabeller}); {@code label} = {@link forge.ai.simulation.HonestLabel.Spec}'s keys
         * plus {@code turns} (T, default 3) and {@code out} (the label directory). A spec that is not honest is refused.
         */
        forge.ai.simulation.HonestLabel.Spec labelSpec = null;
        int labelTurns = 3;
        String labelOut = null;
        /**
         * bvw-step1-build-1024 (absent = step 0's window [0, turns)): the band [lo, hi) of turns before the end; and the
         * step-1 search block (null = none: step-0 rows exactly).
         */
        int labelBandLo = 0;
        forge.bench.rl.StepSearch.Spec labelSearch = null;
        /** r3-labelsearch-1030 (E1; null = off): the R3 label search block and its ranker (label.r3, label.r3Ranker). */
        forge.ai.simulation.R3LabelSearch.Spec labelR3 = null;
        JsonObject labelR3Ranker = null;
        /**
         * bvw-step1-build-1024 (OPEN-6 ruling; null = off): the read-only face-down-exile scan of replayed tapes
         * ({@link forge.bench.rl.RlExileScan}): {@code exileScan} = {"turns": T, "out": DIR}. Not with a label spec.
         */
        int exileScanTurns = 0;
        String exileScanOut = null;
        /**
         * bvw-step1-build-1024 (the seat/dec_idx swap, 2026-10-25; null = no check): step-0 label index files whose
         * positions (turns before the end in [0, referenceTurns)) a labelled or scanned replay must reproduce exactly,
         * or the game writes no rows ({@code label.reference} / {@code exileScan.reference}, {@code referenceTurns}).
         * It aligns step 0's window, not the band (review-align #3). A label search on HonestForcedWin tapes must set
         * it, with referenceTurns = {@link forge.bench.rl.TrajectoryRef#STEP0_WINDOW}, and the reference must know every
         * replayed game (review-align #2, #6): {@link #referenceRefusal}.
         */
        List<String> reference = null;
        int referenceTurns = 3;
        /** lki-fix-port-1027: replay mode's stall watchdog (on by default; {@code "watchdog": false} = off). */
        ReplayStallWatchdog.Thresholds watchdog = new ReplayStallWatchdog.Thresholds();
        /**
         * bvw-decision-log-1027 ({@link DecisionLog}; null = the mode's default). Generation (train / record / eval):
         * {@code "off"} (the default: nothing changes) or {@code "record"} (every decision of both Forge seats goes on
         * the tape line, key {@code decision_log}). Replay: {@code "auto"} (the default: a tape line, or a
         * {@link #decisionLogs} file, that carries a log is replayed FORCED from it, never re-searched; a tape without
         * one re-runs its seats, as before), {@code "rerun"} (re-run the seats, whatever the tape carries) or
         * {@code "record"} (re-run the seats of a tape without a log and write their log to {@link #decisionLogOut}).
         */
        String decisionLog = null;
        /**
         * bvw-tape-decisions-1028 (false, the default: nothing changes): a RECORD log's top-level entries also carry
         * per-decision diagnostics ({@code "d"}: wall ms; the forced-win solver's engine ms, worlds, nodes, ms_bound).
         */
        boolean decisionLogDiag = false;
        /** bvw-decision-log-1027: replay {@code "record"}: the directory of {@code decision-logs-<pid>.jsonl.gz}. */
        String decisionLogOut = null;
        /** bvw-decision-log-1027: replay: logs an earlier {@code "record"} replay wrote (used for digest-equal games). */
        List<String> decisionLogs = null;
        /**
         * Review #124 S2-3 (null = none): a later step's label index files whose rows' {@code state_sha} a RECORD replay
         * checks at their own positions ({@code decisionLogReference.stateRows}); a log with a mismatch is not accepted.
         */
        List<String> decisionLogStateRows = null;
        /**
         * Review #137 S2-2 (null = none): step 0's LABEL replay rows ({@code replay-*.jsonl}: game_uid, equal,
         * digest_lb_replay), {@code decisionLogReference.labelReplays}. A game's step-0 labels were made on that replay's
         * game, whose LookaheadBench digest can differ from the tape's (78 of 18,808 equal step-0 label replays); a
         * record pass's log must reproduce that one ({@code digest_lb_reference}).
         */
        List<String> decisionLogLabelReplays = null;
        /** Review #137 S3: the record pass's tier and attempt, written on every sidecar line (null / 0 = not given). */
        String decisionLogTier = null;
        int decisionLogAttempt = 0;
    }

    static void parseReference(final Cfg c, final JsonObject o, final String where) {
        if (!o.has("reference") || o.get("reference").isJsonNull()) {
            if (o.has("referenceTurns")) {
                throw new IllegalArgumentException(where + ".referenceTurns without reference");
            }
            return;
        }
        c.reference = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("reference")) {
            c.reference.add(e.getAsString());
        }
        if (c.reference.isEmpty()) {
            throw new IllegalArgumentException(where + ".reference: at least one step-0 index file");
        }
        c.referenceTurns = o.has("referenceTurns") ? o.get("referenceTurns").getAsInt() : 3;
        if (c.referenceTurns < 1) {
            throw new IllegalArgumentException(where + ".referenceTurns >= 1");
        }
    }

    static Cfg parse(final JsonObject o) {
        final Cfg c = new Cfg();
        if (o.has("mode")) c.mode = o.get("mode").getAsString();
        if (o.has("server") && !o.get("server").isJsonNull()) c.server = o.get("server").getAsString();
        if (o.has("actorId")) c.actorId = o.get("actorId").getAsString();
        if (o.has("threads")) c.threads = Math.max(1, o.get("threads").getAsInt());
        if (o.has("rlRoot")) c.rlRoot = o.get("rlRoot").getAsString();
        if (o.has("cardIndex")) c.cardIndex = o.get("cardIndex").getAsString();
        if (o.has("tapesDir")) c.tapesDir = o.get("tapesDir").getAsString();
        if (o.has("capActions")) c.capActions = o.get("capActions").getAsInt();
        if (o.has("gameTimeoutSec")) c.gameTimeoutSec = o.get("gameTimeoutSec").getAsInt();
        if (o.has("maxDecisions")) c.maxDecisions = o.get("maxDecisions").getAsInt();
        if (o.has("loopGuard")) {
            c.loopGuard = o.get("loopGuard").getAsInt();
            if (c.loopGuard < 0) {
                throw new IllegalArgumentException("loopGuard >= 0 (0 = off), not " + c.loopGuard);
            }
        }
        if (o.has("jarSha")) c.jarSha = o.get("jarSha").getAsString();
        if (o.has("aiTimeoutSec")) c.aiTimeoutSec = o.get("aiTimeoutSec").getAsInt();
        if (o.has("readTimeoutSec")) c.readTimeoutSec = o.get("readTimeoutSec").getAsInt();
        if (o.has("tapeRotate")) c.tapeRotate = o.get("tapeRotate").getAsInt();
        if (o.has("maxGames")) c.maxGames = o.get("maxGames").getAsInt();
        if (o.has("globalSeed")) c.globalSeed = o.get("globalSeed").getAsLong();
        if (o.has("debugForgeStateOut")) c.debugForgeStateOut = o.get("debugForgeStateOut").getAsString();
        if (o.has("obsSchema")) {
            c.obsSchema = o.get("obsSchema").getAsInt();
            if (c.obsSchema != 1 && c.obsSchema != 2) {
                throw new IllegalArgumentException("obsSchema must be 1 or 2, not " + c.obsSchema);
            }
        }
        if (o.has("search") && o.get("search").isJsonObject()) {
            c.search = RlSearch.Config.parse(o.getAsJsonObject("search"));
            if (c.search.obs != 0 && c.search.obs != c.obsSchema) {
                throw new IllegalArgumentException("search.obs " + c.search.obs + " != obsSchema " + c.obsSchema);
            }
        }
        if (o.has("k8") && !o.get("k8").isJsonNull()) {
            c.k8 = o.getAsJsonObject("k8");
            k8Config(c.k8, 0L); // every key and value is checked once at JVM start (exit 2 on a bad spec)
        }
        if (o.has("games")) c.gamesFile = o.get("games").getAsString();
        if (o.has("label") && !o.get("label").isJsonNull()) {
            // bvw-plr-1023: refused (IllegalArgumentException, exit 2) unless honest: redraw=belief, hideOwnLibrary
            final JsonObject l = o.getAsJsonObject("label");
            c.labelSpec = forge.ai.simulation.HonestLabel.Spec.parse(l);
            c.labelTurns = l.has("turns") ? l.get("turns").getAsInt() : 3;
            if (c.labelTurns < 1) {
                throw new IllegalArgumentException("label.turns >= 1, not " + c.labelTurns);
            }
            if (l.has("band")) {
                // bvw-step1-build-1024: [lo, hi) turns before the end (refused together with turns)
                if (l.has("turns")) {
                    throw new IllegalArgumentException("label: band or turns, not both");
                }
                final JsonArray b = l.getAsJsonArray("band");
                if (b.size() != 2) {
                    throw new IllegalArgumentException("label.band is [lo, hi)");
                }
                c.labelBandLo = b.get(0).getAsInt();
                c.labelTurns = b.get(1).getAsInt();
                if (c.labelBandLo < 0 || c.labelBandLo >= c.labelTurns) {
                    throw new IllegalArgumentException("label.band [lo, hi) needs 0 <= lo < hi");
                }
            }
            if (l.has("search") && !l.get("search").isJsonNull() && l.has("r3") && !l.get("r3").isJsonNull()) {
                throw new IllegalArgumentException("refused: label.search and label.r3 are two teachers");
            }
            if (l.has("search") && !l.get("search").isJsonNull()) {
                // bvw-step1-build-1024: refused unless honest, host-independent and tied to the proof check
                if (!c.labelSpec.counterBudgets()) {
                    throw new IllegalArgumentException("refused: a label search needs the proof check on budget=counter");
                }
                c.labelSearch = forge.bench.rl.StepSearch.Spec.parse(l.getAsJsonObject("search"), c.labelSpec,
                        c.labelBandLo, c.labelTurns);
            } else if (l.has("r3") && !l.get("r3").isJsonNull()) {
                // r3-labelsearch-1030 (E1): the R3 label search is the band's teacher (counter budgets only)
                if (!l.has("band")) {
                    throw new IllegalArgumentException("refused: label.r3 needs a band");
                }
                if (!c.labelSpec.counterBudgets()) {
                    throw new IllegalArgumentException("refused: label.r3 needs the proof check on budget=counter");
                }
                c.labelR3 = forge.ai.simulation.R3LabelSearch.Spec.parse(l.getAsJsonObject("r3"));
                if (!l.has("r3Ranker") || !l.get("r3Ranker").isJsonObject()) {
                    throw new IllegalArgumentException("refused: label.r3 needs r3Ranker {\"kind\": \"service\", "
                            + "\"rlsearch\": {...}, \"valueSha\": ...} (or \"static\": report only)");
                }
                c.labelR3Ranker = l.getAsJsonObject("r3Ranker");
                final String rk = c.labelR3Ranker.has("kind") ? c.labelR3Ranker.get("kind").getAsString() : "";
                if (!"static".equals(rk) && !"service".equals(rk)) {
                    throw new IllegalArgumentException("label.r3Ranker.kind is \"service\" or \"static\"");
                }
                if ("service".equals(rk) && (!c.labelR3Ranker.has("rlsearch") || !c.labelR3Ranker.has("valueSha"))) {
                    throw new IllegalArgumentException("refused: r3Ranker service needs rlsearch and valueSha");
                }
            } else if (l.has("band")) {
                // review #14: a band is a step-k labeller; step-0 rows come only from the turns window
                throw new IllegalArgumentException("refused: a label band needs a search block (step-0 rows use turns)");
            }
            // review #3: a counter spec runs only under the Forge AI settings it pins
            final String no = c.labelSpec.runtimeRefusal(c.aiTimeoutSec);
            if (no != null) {
                throw new IllegalArgumentException(no);
            }
            if (!l.has("out")) {
                throw new IllegalArgumentException("label.out (the label directory) is required");
            }
            c.labelOut = l.get("out").getAsString();
            parseReference(c, l, "label");
        }
        if (o.has("exileScan") && !o.get("exileScan").isJsonNull()) {
            final JsonObject x = o.getAsJsonObject("exileScan");
            for (String k : x.keySet()) {
                if (!"turns".equals(k) && !"out".equals(k) && !"reference".equals(k) && !"referenceTurns".equals(k)) {
                    throw new IllegalArgumentException("exileScan: unknown key " + k + " (turns, out, reference)");
                }
            }
            parseReference(c, x, "exileScan");
            if (c.labelSpec != null) {
                throw new IllegalArgumentException("refused: exileScan is a read-only scan, not with a label spec");
            }
            if (!x.has("out") || !x.has("turns") || x.get("turns").getAsInt() < 1) {
                throw new IllegalArgumentException("exileScan needs turns >= 1 and out");
            }
            c.exileScanTurns = x.get("turns").getAsInt();
            c.exileScanOut = x.get("out").getAsString();
        }
        if (o.has("watchdog")) {
            c.watchdog = ReplayStallWatchdog.Thresholds.parse(o.get("watchdog")); // lki-fix-port-1027
        }
        if (o.has("decisionLog") && !o.get("decisionLog").isJsonNull()) {
            // bvw-decision-log-1027: checked once, at JVM start (exit 2 on a bad value)
            c.decisionLog = o.get("decisionLog").getAsString();
            final boolean replay = "replay".equals(c.mode);
            // review #124 S2-4: "forced" = every replayed game is forced from its log; a game without one is void
            final java.util.Set<String> ok = new java.util.TreeSet<>(replay
                    ? java.util.Arrays.asList("auto", "rerun", "record", "forced") : java.util.Arrays.asList("off", "record"));
            if (!ok.contains(c.decisionLog)) {
                throw new IllegalArgumentException("decisionLog in " + c.mode + " mode is one of " + ok + ", not "
                        + c.decisionLog);
            }
        }
        if (o.has("decisionLogDiag") && !o.get("decisionLogDiag").isJsonNull()) {
            c.decisionLogDiag = o.get("decisionLogDiag").getAsBoolean();
            if (c.decisionLogDiag && !"record".equals(c.decisionLog)) {
                throw new IllegalArgumentException("decisionLogDiag is for decisionLog record");
            }
        }
        if (o.has("decisionLogOut") && !o.get("decisionLogOut").isJsonNull()) {
            c.decisionLogOut = o.get("decisionLogOut").getAsString();
        }
        if (o.has("decisionLogs") && !o.get("decisionLogs").isJsonNull()) {
            c.decisionLogs = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("decisionLogs")) {
                c.decisionLogs.add(e.getAsString());
            }
        }
        if ("record".equals(c.decisionLog) && "replay".equals(c.mode) && c.decisionLogOut == null) {
            throw new IllegalArgumentException("decisionLog record in replay mode needs decisionLogOut");
        }
        if (c.decisionLogOut != null && !("record".equals(c.decisionLog) && "replay".equals(c.mode))) {
            throw new IllegalArgumentException("decisionLogOut is for decisionLog record in replay mode");
        }
        if (c.decisionLogs != null && (!"replay".equals(c.mode) || "rerun".equals(c.decisionLog))) {
            throw new IllegalArgumentException("decisionLogs are read by a replay that may force them (not rerun)");
        }
        if (o.has("decisionLogReference") && !o.get("decisionLogReference").isJsonNull()) {
            // review #124 S2-3: a record replay's own trajectory check against step 0, and a later step's state_sha rows
            if (!"record".equals(c.decisionLog) || !"replay".equals(c.mode)) {
                throw new IllegalArgumentException("decisionLogReference is for decisionLog record in replay mode");
            }
            if (c.reference != null) {
                throw new IllegalArgumentException("decisionLogReference: the label's or exile scan's reference is already "
                        + "the trajectory check; give stateRows there only");
            }
            final JsonObject x = o.getAsJsonObject("decisionLogReference");
            for (String k : x.keySet()) {
                if (!"reference".equals(k) && !"referenceTurns".equals(k) && !"stateRows".equals(k)
                        && !"labelReplays".equals(k)) {
                    throw new IllegalArgumentException("decisionLogReference: unknown key " + k);
                }
            }
            parseReference(c, x, "decisionLogReference");
            if (x.has("stateRows")) {
                c.decisionLogStateRows = new ArrayList<>();
                for (JsonElement e : x.getAsJsonArray("stateRows")) {
                    c.decisionLogStateRows.add(e.getAsString());
                }
            }
            if (x.has("labelReplays")) {
                c.decisionLogLabelReplays = new ArrayList<>();
                for (JsonElement e : x.getAsJsonArray("labelReplays")) {
                    c.decisionLogLabelReplays.add(e.getAsString());
                }
            }
        }
        if (o.has("decisionLogTier") && !o.get("decisionLogTier").isJsonNull()) {
            c.decisionLogTier = o.get("decisionLogTier").getAsString();
        }
        if (o.has("decisionLogAttempt") && !o.get("decisionLogAttempt").isJsonNull()) {
            c.decisionLogAttempt = o.get("decisionLogAttempt").getAsInt();
        }
        if ((c.decisionLogTier != null || c.decisionLogAttempt != 0)
                && !("record".equals(c.decisionLog) && "replay".equals(c.mode))) {
            throw new IllegalArgumentException("decisionLogTier / decisionLogAttempt are for decisionLog record in replay mode");
        }
        if (o.has("replay") && o.get("replay").isJsonObject()) {
            final JsonObject r = o.getAsJsonObject("replay");
            if (r.has("tapes")) for (JsonElement e : r.getAsJsonArray("tapes")) c.replayTapes.add(e.getAsString());
            if (r.has("lines")) {
                for (JsonElement e : r.getAsJsonArray("lines")) {
                    final JsonArray a = e.getAsJsonArray();
                    c.replayLines.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()});
                }
            }
            if (r.has("out")) c.replayOut = r.get("out").getAsString();
            if (r.has("dir")) c.replayDir = r.get("dir").getAsString();
            if (r.has("sample")) c.replaySample = r.get("sample").getAsInt();
            if (r.has("sampleSeed")) c.replaySampleSeed = r.get("sampleSeed").getAsLong();
        }
        return c;
    }

    static Cfg parseArgs(final String[] args) throws IOException {
        if (args.length == 1 && !args[0].startsWith("--")) {
            return parse(JsonParser.parseString(new String(Files.readAllBytes(Paths.get(args[0])),
                    StandardCharsets.UTF_8)).getAsJsonObject());
        }
        final Cfg c = new Cfg();
        String tape = null;
        int line = 0;
        for (int i = 0; i + 1 < args.length; i += 2) {
            final String k = args[i], v = args[i + 1];
            switch (k) {
                case "--mode": c.mode = v; break;
                case "--tape": tape = v; break;
                case "--line": line = Integer.parseInt(v); break;
                case "--card-index": c.cardIndex = v; break;
                case "--rl-root": c.rlRoot = v; break;
                case "--out": c.replayOut = v; break;
                case "--ai-timeout": c.aiTimeoutSec = Integer.parseInt(v); break;
                case "--tapes": c.replayDir = v; break;
                case "--sample": c.replaySample = Integer.parseInt(v); break;
                case "--sample-seed": c.replaySampleSeed = Long.parseLong(v); break;
                default: throw new IllegalArgumentException("unknown argument " + k);
            }
        }
        if (tape != null) {
            c.replayTapes.add(tape);
            c.replayLines.add(new int[] {0, line});
        }
        return c;
    }

    // ------------------------------------------------------------------------------------------------ deck guard

    /** §8 (Java side). Null when the deck may be loaded in this mode, else the refusal. */
    static String deckGuard(final String mode, final String rlRoot, final String path, final String sha) {
        final boolean eval = "eval".equals(mode);
        try {
            final Path root = Paths.get(rlRoot, "data", eval ? "eval" : "train").toRealPath();
            final Path given = Paths.get(path);
            if (!given.isAbsolute()) {
                return "deck path is not absolute: " + path;
            }
            final Path real = given.toRealPath();
            if (!real.startsWith(root)) {
                return "deck " + path + " resolves outside " + root + " (" + real + ")";
            }
            if (!eval) {
                for (Path p : new Path[] {given, real}) {
                    for (Path part : p) {
                        if ("eval".equals(part.toString())) {
                            return "deck path has an 'eval' component: " + p;
                        }
                    }
                }
            }
            final String fileSha = CardIndex.sha256(Files.readAllBytes(real));
            if (sha == null || !sha.equals(fileSha)) {
                return "deck sha mismatch for " + path + ": GAME says " + sha + ", file is " + fileSha;
            }
            final Path rel = root.relativize(real);
            if (rel.getNameCount() < 2) {
                return "deck " + path + " is not inside a bank directory";
            }
            final Path manifest = root.resolve(rel.getName(0)).resolve("MANIFEST.json");
            final JsonObject m = manifest(manifest);
            final String use = m.has("use") ? m.get("use").getAsString() : null;
            if (!(eval ? "eval" : "train").equals(use)) {
                return "bank manifest " + manifest + " has use " + use;
            }
            boolean listed = false;
            for (JsonElement d : m.getAsJsonArray("decks")) {
                if (fileSha.equals(d.getAsJsonObject().get("sha256").getAsString())) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                return "deck sha " + fileSha + " is not in " + manifest;
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return "deck guard could not check " + path + ": " + e;
        }
    }

    private static final Map<Path, JsonObject> MANIFESTS = new ConcurrentHashMap<>();

    private static JsonObject manifest(final Path p) throws IOException {
        JsonObject m = MANIFESTS.get(p);
        if (m == null) {
            m = JsonParser.parseString(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).getAsJsonObject();
            MANIFESTS.put(p, m);
        }
        return m;
    }

    static final Map<String, Deck> DECKS = new ConcurrentHashMap<>();

    static Deck deck(final String path) {
        return DECKS.computeIfAbsent(path, p -> {
            final Deck d = DeckSerializer.fromFile(new File(p));
            if (d == null) {
                throw new IllegalArgumentException("could not parse deck " + p);
            }
            // k8-determinism-1008: a Deck loads its card sections lazily, without a lock, on first access. This cache
            // shares one Deck between game threads, and two games copying a not-yet-loaded Deck at once could give one
            // of them a partial deck (a game "lost to its library on turn 1"). Load it here, inside computeIfAbsent,
            // before any game can see it; a loaded Deck is only read.
            d.getMain();
            return d;
        });
    }

    // ------------------------------------------------------------------------------------------------ opponent seats

    /** gen-check-1007: the K8 look-ahead opponent seat's controller value (ICR B7-k8-opponent-seat). */
    static final String K8_CONTROLLER = "lookahead:K8";

    /**
     * gen-check-1007: the keys a {@code k8} spec may set. The search keys have LookaheadBench's "lookahead" names and
     * defaults (the panel's K-arm instrument); aiFixes0928 and fairNaming are the seat's own Forge AI options (as
     * LookaheadBench's look-ahead seats). Anything else refuses the spec.
     */
    static final java.util.Set<String> K8_KEYS = Collections.unmodifiableSet(new java.util.TreeSet<>(
            java.util.Arrays.asList("worlds", "breadth", "horizonTurns", "threads", "reuse", "margin", "departZ",
                    "deadEtb", "zeroX", "crewNoop", "copyEot", "departMedian", "maxSteps", "budgetMs", "aiFixes0928",
                    "fairNaming", "copyFidelity")));

    /**
     * The Forge AI profile of a seat: {@code forge:<Profile>} plays that shipped profile; {@code forge}, the K8 seat
     * and every bridged seat play "Default" (as before this option).
     */
    static String aiProfileOf(final String controller) {
        return controller != null && controller.startsWith("forge:") ? controller.substring("forge:".length())
                : "Default";
    }

    /** The K8 seat's search config from a spec (LookaheadBench's parse of the same keys); throws on a bad spec. */
    static forge.ai.simulation.LookaheadSearch.Config k8Config(final JsonObject la, final long seed) {
        for (String k : la.keySet()) {
            if (!K8_KEYS.contains(k)) {
                throw new IllegalArgumentException("k8: unknown key '" + k + "' (allowed " + K8_KEYS + ")");
            }
        }
        final forge.ai.simulation.LookaheadSearch.Config c = new forge.ai.simulation.LookaheadSearch.Config();
        c.worlds = la.has("worlds") ? la.get("worlds").getAsInt() : 1;
        c.breadth = la.has("breadth") ? la.get("breadth").getAsInt() : 4;
        c.horizonTurns = la.has("horizonTurns") ? la.get("horizonTurns").getAsInt() : 2;
        c.threads = la.has("threads") ? la.get("threads").getAsInt() : 0;
        c.reuse = la.has("reuse") && la.get("reuse").getAsBoolean();
        c.margin = la.has("margin") ? la.get("margin").getAsDouble() : 0.0;
        c.departZ = la.has("departZ") ? la.get("departZ").getAsDouble() : 0.0;
        c.deadEtb = forge.ai.AiFixes.Mode.parse(la.has("deadEtb") ? la.get("deadEtb").getAsString() : null);
        c.zeroX = forge.ai.AiFixes.Mode.parse(la.has("zeroX") ? la.get("zeroX").getAsString() : null);
        c.crewNoop = forge.ai.AiFixes.Mode.parse(la.has("crewNoop") ? la.get("crewNoop").getAsString() : null);
        c.copyEot = forge.ai.AiFixes.Mode.parse(la.has("copyEot") ? la.get("copyEot").getAsString() : null);
        c.departMedian = forge.ai.AiFixes.Mode.parse(la.has("departMedian") ? la.get("departMedian").getAsString() : null);
        c.maxSteps = la.has("maxSteps") ? la.get("maxSteps").getAsInt() : 5000;
        c.budgetMs = la.has("budgetMs") ? la.get("budgetMs").getAsLong() : 0L;
        c.copyFidelity = forge.ai.simulation.LookaheadSearch.Config.copyFidelityLevel(
                la.has("copyFidelity") ? la.get("copyFidelity").getAsInt() : 0);
        k8Mode(la, "aiFixes0928");
        k8Mode(la, "fairNaming");
        c.seed = seed;
        return c;
    }

    static forge.ai.AiFixes.Mode k8Mode(final JsonObject la, final String key) {
        return forge.ai.AiFixes.Mode.parse(la.has(key) ? la.get(key).getAsString() : null);
    }

    // ------------------------------------------------------------------------------------------------ one game

    /** The outcome of one played game. */
    static final class Played {
        JsonObject end;       // GAME_END
        JsonObject tape;      // tape line
        String guardError;    // deck guard refusal (nothing played)
        String fatal;         // the connection is unusable
        RlSeat seat;
        RlKnowledge knowledge; // the game's seat-knowledge tracker (tests)
        RlSearch search;       // S1: the game's look-ahead (null when off)
        forge.ai.LobbyPlayerAi[] lobbies; // gen-check-1007: each seat's lobby player (tests)
        DecisionLog decisionLog; // bvw-decision-log-1027: the game's log (record or forced), or null
    }

    static final ThreadMXBean TMX = ManagementFactory.getThreadMXBean();
    static final Map<Thread, Object[]> RUNNING = new ConcurrentHashMap<>(); // thread -> {game, deadlineMs, uid}
    static final Map<Long, Boolean> TIMED_OUT = new ConcurrentHashMap<>();

    /**
     * Play one GAME message. {@code ep} answers the RL seats (a server connection, or a tape in replay).
     * {@code listener} (tests) observes every sent frame.
     */
    static Played play(final Cfg cfg, final String mode, final JsonObject g, final RlFeaturizer feat,
            final RlSeat.Endpoint ep, final RlSeat.FrameListener listener, final String jarSha) {
        return play(cfg, mode, g, feat, ep, listener, jarSha, null);
    }

    /**
     * bvw-plr-1023: told the game, its seat and its seat-knowledge tracker after all three are set up, before the game
     * starts (null = none).
     */
    interface GameHook {
        void start(Game game, RlSeat seat, RlKnowledge know);
    }

    static Played play(final Cfg cfg, final String mode, final JsonObject g, final RlFeaturizer feat,
            final RlSeat.Endpoint ep, final RlSeat.FrameListener listener, final String jarSha, final GameHook hook) {
        // bvw-decision-log-1027: a generation run's decisionLog "record" logs every game (a replay chooses per tape)
        return play(cfg, mode, g, feat, ep, listener, jarSha, hook,
                "record".equals(cfg.decisionLog) && !"replay".equals(cfg.mode) ? DecisionLog.record(cfg.decisionLogDiag) : null);
    }

    /** bvw-decision-log-1027: {@code dl} (null = none, as before) records or forces both Forge seats' decisions. */
    static Played play(final Cfg cfg, final String mode, final JsonObject g, final RlFeaturizer feat,
            final RlSeat.Endpoint ep, final RlSeat.FrameListener listener, final String jarSha, final GameHook hook,
            final DecisionLog dl) {
        final Played out = new Played();
        final long uid = RlWire.parseUid(g.get("game_uid").getAsString());
        final long seed = g.get("seed").getAsLong();
        final JsonArray decksJ = g.getAsJsonArray("decks");
        final JsonArray shaJ = g.getAsJsonArray("deck_sha");
        final JsonArray ctlJ = g.getAsJsonArray("controllers");
        final String[] decks = {decksJ.get(0).getAsString(), decksJ.get(1).getAsString()};
        final String[] shas = {shaJ.get(0).getAsString(), shaJ.get(1).getAsString()};
        final String[] ctl = {ctlJ.get(0).getAsString(), ctlJ.get(1).getAsString()};
        final boolean trainSide = "train".equals(mode) || "record".equals(mode);
        final boolean priv = trainSide && g.has("priv") && g.get("priv").getAsBoolean();
        for (int s = 0; s < 2; s++) {
            final String why = deckGuard(mode, cfg.rlRoot, decks[s], shas[s]);
            if (why != null) {
                out.guardError = why;
                return out;
            }
            final RlSeat.Role r;
            try {
                r = RlSeat.roleOf(ctl[s]);
            } catch (IllegalArgumentException e) {
                out.guardError = "controller " + ctl[s] + ": " + e.getMessage();
                return out;
            }
            final boolean okRole = "record".equals(mode) ? r != RlSeat.Role.RL
                    : "replay".equals(mode) || r != RlSeat.Role.RECORD;
            if (!okRole) {
                out.guardError = "controller " + ctl[s] + " is not allowed in " + mode + " mode";
                return out;
            }
        }
        // gen-check-1007: the opponent-profile and K8 seats (eval; profiles also in train); refusals play nothing
        final JsonObject k8spec = g.has("k8") && g.get("k8").isJsonObject() ? g.getAsJsonObject("k8") : cfg.k8;
        for (int s = 0; s < 2; s++) {
            if (ctl[s].startsWith("forge:")) {
                if (!"eval".equals(mode) && !"train".equals(mode)) {
                    out.guardError = "controller " + ctl[s] + " is not allowed in " + mode + " mode";
                    return out;
                }
                final List<String> shipped = forge.ai.AiProfileUtil.getAvailableProfiles();
                // combo-ai-port-1009: a built-in profile (forge:CubeCombo, the cube combo policy) is accepted too
                if (!forge.ai.AiProfileUtil.isKnownProfile(aiProfileOf(ctl[s]))) {
                    out.guardError = "controller " + ctl[s] + ": AI profile '" + aiProfileOf(ctl[s])
                            + "' is not shipped (available " + shipped + ")";
                    return out;
                }
            } else if (K8_CONTROLLER.equals(ctl[s])) {
                if (!"eval".equals(mode)) {
                    out.guardError = "controller " + ctl[s] + " is not allowed in " + mode + " mode";
                    return out;
                }
                if (k8spec == null) {
                    out.guardError = "controller " + ctl[s] + " needs the actor config's k8 spec";
                    return out;
                }
                try {
                    k8Config(k8spec, 0L);
                } catch (RuntimeException e) {
                    out.guardError = "controller " + ctl[s] + ": " + e.getMessage();
                    return out;
                }
            }
        }
        if (dl != null) {
            // bvw-decision-log-1027: the log tapes Forge seats (forge, forge:<Profile>); a bridged seat's answers are its
            // dec rows, and the K8 seat is not taped here
            for (int s = 0; s < 2; s++) {
                if (K8_CONTROLLER.equals(ctl[s]) || RlSeat.roleOf(ctl[s]) != RlSeat.Role.FORGE) {
                    out.guardError = "decision log: seat " + s + " (" + ctl[s] + ") is not a Forge seat";
                    return out;
                }
            }
        }
        // cm-curriculum-1009 (G3): opening-hand seeding, a TRAIN curriculum; null (off, the default) without a GAME
        // "seeding" object, and then nothing below changes (no subscription, no random draw, no tape key)
        final RlSeeding seeding;
        try {
            seeding = RlSeeding.of(g, seed);
        } catch (RuntimeException e) {
            out.guardError = "seeding: " + e.getMessage();
            return out;
        }
        if (seeding != null && "eval".equals(mode)) {
            out.guardError = "seeding is a TRAIN curriculum: not allowed in eval mode";
            return out;
        }
        // r3-distill-1010 (R3-CM label games): the look-ahead for ONE seat (an RL seat), or -1 (absent: every RL seat)
        final int searchSeat;
        if (g.has("search_seat") && !g.get("search_seat").isJsonNull()) {
            searchSeat = g.get("search_seat").getAsInt();
            if (searchSeat != 0 && searchSeat != 1) {
                out.guardError = "search_seat must be 0 or 1, not " + searchSeat;
                return out;
            }
            if (cfg.search == null) {
                out.guardError = "search_seat without the actor's search spec";
                return out;
            }
            if (RlSeat.roleOf(ctl[searchSeat]) != RlSeat.Role.RL) {
                out.guardError = "search_seat " + searchSeat + " is not an RL seat (" + ctl[searchSeat] + ")";
                return out;
            }
        } else {
            searchSeat = -1;
        }

        final JsonRpcChannel ch = new JsonRpcChannel(InputStream.nullInputStream(), OutputStream.nullOutputStream());
        final BenchSession session = new BenchSession(ch);
        session.setEncodeState(false);
        final RlSeat.Config sc = new RlSeat.Config();
        sc.mode = mode;
        sc.capActions = cfg.capActions;
        sc.maxDecisions = cfg.maxDecisions;
        // exploit-panel-1015: the actor's loopGuard (0, the default = no guard, as before); r4-design-1015: a GAME's
        // (a replayed tape's) own "loop_guard" takes precedence, so a replay plays the guard its tape played
        final int loopGuard;
        try {
            loopGuard = loopGuardOf(g, cfg.loopGuard);
        } catch (RuntimeException e) {
            out.guardError = "loop_guard: " + e.getMessage();
            return out;
        }
        sc.loopGuard = loopGuard;
        // bvw-plr-1023: the GAME's (a replayed tape's) fair_naming puts Forge's fair naming on the recorder and Forge seats
        // (absent = false, the default: those seats keep upstream Forge AI, and nothing changes)
        final boolean fairNaming = g.has("fair_naming") && g.get("fair_naming").getAsBoolean();
        // the observation schema: the actor's, or (replay) the tape's
        final int obsVersion = g.has("obs_schema") ? g.get("obs_schema").getAsInt() : cfg.obsSchema;
        feat.setVersion(obsVersion);
        final RlSeat seat = new RlSeat(feat, ep, sc, uid, ctl, priv);
        seat.listener = listener;
        feat.captureForgeState = cfg.debugForgeStateOut != null;
        if (cfg.debugForgeStateOut != null) {
            seat.listener = forgeStateDumper(cfg.debugForgeStateOut, listener, decks);
        }
        out.seat = seat;
        boolean anyBridged = false;
        final List<RegisteredPlayer> seats = new ArrayList<>();
        final LobbyPlayerBridge[] lps = new LobbyPlayerBridge[2];      // null for the K8 seat
        final forge.ai.LobbyPlayerAi[] lobbies = new forge.ai.LobbyPlayerAi[2];
        forge.ai.simulation.LobbyPlayerLookahead k8Lobby = null;
        forge.ai.simulation.LookaheadSearch k8Search = null;
        int k8Seat = -1;
        for (int s = 0; s < 2; s++) {
            final forge.ai.LobbyPlayerAi any;
            if (K8_CONTROLLER.equals(ctl[s])) {
                // gen-check-1007: Forge AI (Default) plus the look-ahead, as LookaheadBench's "lookahead" seat; its
                // search seed is the game seed mixed with the seat index, as there
                final forge.ai.simulation.LobbyPlayerLookahead l = new forge.ai.simulation.LobbyPlayerLookahead("Seat" + s);
                l.setAiProfile("Default");
                l.setAiFixes0928(k8Mode(k8spec, "aiFixes0928"));
                l.setFairNaming(k8Mode(k8spec, "fairNaming"));
                k8Search = new forge.ai.simulation.LookaheadSearch(k8Config(k8spec, seed * 31 + s));
                k8Lobby = l;
                k8Seat = s;
                any = l;
            } else {
                final boolean bridged = RlSeat.roleOf(ctl[s]) != RlSeat.Role.FORGE;
                anyBridged |= bridged;
                final LobbyPlayerBridge lp = new LobbyPlayerBridge("Seat" + s, null, session,
                        bridged ? BenchSession.Mode.BRIDGE : BenchSession.Mode.NULL, s);
                // "Default" unless a forge:<Profile> opponent seat names a shipped profile (gen-check-1007)
                lp.setAiProfile(aiProfileOf(ctl[s]));
                if (RlSeat.roleOf(ctl[s]) == RlSeat.Role.RL || fairNaming) {
                    // an RL seat's delegated naming ("Forge's choice") must not read hidden zones (ICR B4 coordination);
                    // Forge seats and recorders keep upstream Forge AI (do-no-harm) unless the GAME asks for fair naming
                    lp.setFairNaming(forge.ai.AiFixes.Mode.ON);
                }
                lps[s] = lp;
                any = lp;
            }
            lobbies[s] = any;
            final RegisteredPlayer rp = new RegisteredPlayer(deck(decks[s]));
            rp.setPlayer(any);
            seats.add(rp);
        }
        out.lobbies = lobbies;
        if (anyBridged) {
            session.setLocalAnswerer(seat);
        }
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
        rules.setSimTimeout(cfg.gameTimeoutSec);
        final Match match = new Match(rules, seats, "rl-sim");

        // bvw-decision-log-1027: a logged game's random stream is TrackedRandom (new Random(seed)'s stream, its state
        // readable: the log syncs it); without a log, as before
        final Random gameRandom = dl != null ? new forge.ai.simulation.PlayoutKeys.TrackedRandom(seed) : new Random(seed);
        MyRandom.setThreadRandom(gameRandom);
        IdScope.open();
        AiCache.openScope();
        // k8-determinism-1008: the game's own random stream, id scope and AI-cache scope; each must still be this
        // thread's at game end (a look-ahead that dropped the AI-cache scope shared Forge AI's cache across games)
        final Object gameIds = IdScope.capture();
        final Object gameCache = AiCache.captureScope();
        feat.reset();
        // S1 (lane s1-search-1007; measurement only): the CPU of this game's other threads (Forge AI eval threads, the
        // look-ahead's workers) is charged to this account; cpu_ms stays the game thread's own
        final java.util.concurrent.atomic.AtomicLong gameAcc = new java.util.concurrent.atomic.AtomicLong();
        forge.ai.CpuAccount.set(new java.util.concurrent.atomic.AtomicLong[] {gameAcc});
        final long cpu0 = TMX.getCurrentThreadCpuTime();
        final long t0 = System.nanoTime();
        Game game = null;
        RlSimBench.Digest dg = null;
        LookaheadBench.Digest dgLb = null;
        String crash = null;
        RlSearch search = null;
        try {
            game = match.createGame();
            if (seeding != null) {
                seeding.attach(game); // G3: reorders the seeded seats' libraries when the game starts
            }
            game.AI_TIMEOUT = cfg.aiTimeoutSec;
            session.setLiveGame(game);
            seat.setGame(game);
            if (k8Lobby != null) {
                k8Lobby.bind(game, k8Search); // gen-check-1007: the K8 seat searches this live game only
            }
            // observation v1: what each seat has observed (reveals, its own looks, public moves, the stack tail)
            final RlKnowledge know = new RlKnowledge(game);
            know.v2 = obsVersion == 2;
            know.attach();
            final java.util.function.Function<Game, RlKnowledge.Log> logF = KNOWLEDGE_LOG;
            if (logF != null) {
                know.log = logF.apply(game);
            }
            final java.util.function.Function<Game, BenchSession.KnowledgeObserver> tapF = KNOWLEDGE_TAP;
            if (tapF == null) {
                session.setKnowledgeObserver(know);
            } else {
                final BenchSession.KnowledgeObserver tap = tapF.apply(game);
                session.setKnowledgeObserver(new BenchSession.KnowledgeObserver() {
                    @Override
                    public void onReveal(final Game g, final Player v, final List<forge.game.card.Card> cards,
                            final forge.game.zone.ZoneType z, final Player owner) {
                        tap.onReveal(g, v, cards, z, owner);
                        know.onReveal(g, v, cards, z, owner);
                    }

                    @Override
                    public void onLook(final Game g, final Player v, final List<forge.game.card.Card> cards,
                            final forge.game.zone.ZoneType dest) {
                        tap.onLook(g, v, cards, dest);
                        know.onLook(g, v, cards, dest);
                    }
                });
            }
            feat.setKnowledge(know);
            seat.seenNames = know::opponentSeen; // NAME candidates (ICR B4-families-coordination)
            out.knowledge = know;
            if (cfg.search != null && !"replay".equals(mode) && !"record".equals(mode)) {
                // S1: the look-ahead over the RL seats' own priority decisions (one per game; world seeds from the GAME seed)
                search = new RlSearch(cfg.search, seed, uid, feat.index(), know, jarSha, cfg.actorId, obsVersion);
                search.searchSeat = searchSeat;     // r3-distill-1010: -1 unless the GAME names one
                seat.searchSeat = searchSeat;
                seat.search = search;
                out.search = search;
                // search-bones-1015: the plan hooks of the RL seats (no-op unless the spec sets forcedWin or plans)
                final boolean[] rlSeats = new boolean[ctl.length];
                for (int s = 0; s < ctl.length; s++) {
                    rlSeats[s] = RlSeat.roleOf(ctl[s]) == RlSeat.Role.RL;
                }
                search.attach(game, rlSeats, seat);
            }
            if (hook != null) {
                hook.start(game, seat, know); // bvw-plr-1023: the replay labeller attaches here
            }
            dg = new RlSimBench.Digest(game);
            game.subscribeToEvents(dg);
            // k8-determinism-1008 (measurement only): LookaheadBench's digest of the same game, so a game here and its
            // LookaheadBench row (panel N / K rows, pf1 rows) compare digest for digest
            dgLb = new LookaheadBench.Digest(game);
            game.subscribeToEvents(dgLb);
            if (dl != null) {
                // bvw-decision-log-1027: both seats' taping controllers, last (every seat bound, every listener on)
                out.decisionLog = dl;
                dl.install(game, (forge.ai.simulation.PlayoutKeys.TrackedRandom) gameRandom, new int[] {0, 1});
                dl.bindGameThread();
            }
            RUNNING.put(Thread.currentThread(), new Object[] {game,
                    System.currentTimeMillis() + cfg.gameTimeoutSec * 1000L, uid});
            match.startGame(game, null);
        } catch (Throwable e) {
            crash = e.toString();
            System.err.println("[rlactor] game " + Long.toUnsignedString(uid) + " crashed: " + e);
            final JsonArray fr = RlSimBench.forgeFrames(e);
            for (JsonElement f : fr) {
                System.err.println("[rlactor]   " + f.getAsString());
            }
        } finally {
            RUNNING.remove(Thread.currentThread());
            if (dl != null) {
                dl.finish(); // bvw-decision-log-1027: the tapes' end lines; uninstalled
            }
            if (game != null && !game.isGameOver()) {
                game.setGameOver(GameEndReason.Draw);
            }
            if (search != null) {
                search.flushLog();
                search.close();
            }
        }
        JsonObject k8Stats = null;
        if (k8Search != null) {
            // gen-check-1007: as LookaheadBench records a look-ahead seat (stats + config), then frees its workers
            if (game != null) {
                k8Search.finishGame(game);
            }
            k8Stats = k8Search.getStats().toJson();
            k8Stats.add("config", k8Search.getConfig().toJson());
            k8Stats.addProperty("seat", k8Seat);
            k8Search.shutdown();
        }
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;
        final long cpuMs = (TMX.getCurrentThreadCpuTime() - cpu0) / 1_000_000L;
        forge.ai.CpuAccount.set(null);
        final long cpuOtherMs = gameAcc.get() / 1_000_000L;
        String voidReason = null;
        if (TIMED_OUT.remove(uid) != null) {
            voidReason = "timeout";
        } else if (seat.voidReason != null) {
            voidReason = seat.voidReason;
        } else if (dl != null && dl.divergence() != null) {
            voidReason = DecisionLog.VOID; // bvw-decision-log-1027: the game stopped at its log's divergence
        } else if (crash != null || game == null) {
            voidReason = "crash";
        }
        out.fatal = seat.fatal;

        final List<String> fps = new ArrayList<>(dg == null ? Collections.emptyList() : dg.fps);
        fps.add(game == null ? "none" : RlSimBench.outcomeText(game));
        final String digest = RlSimBench.sha16(fps);
        final String digestLb = dgLb == null || game == null ? null : dgLb.hex();
        // k8-determinism-1008: which of the game's own scopes this thread no longer holds (empty = isolated)
        final JsonArray lost = new JsonArray();
        if (MyRandom.getThreadRandom() != gameRandom) {
            lost.add("random");
        }
        if (IdScope.capture() != gameIds) {
            lost.add("ids");
        }
        if (AiCache.captureScope() != gameCache) {
            lost.add("aiCache");
        }
        final int[] result = {0, 0};
        String reason = voidReason == null ? "draw" : voidReason;
        if (voidReason == null && game != null && game.getOutcome() != null && !game.getOutcome().isDraw()) {
            final LobbyPlayer w = game.getOutcome().getWinningLobbyPlayer();
            int ws = -1; // the seat whose lobby player won (a bridge's getSeat() is its index; the K8 seat has none)
            for (int s = 0; s < 2; s++) {
                if (lobbies[s] == w) {
                    ws = s;
                }
            }
            if (ws == 0 || ws == 1) {
                result[ws] = 1;
                result[1 - ws] = -1;
            }
            reason = lossReason(game);
        }
        final int turns = game == null ? 0 : game.getPhaseHandler().getTurn();
        int startingSeat = -1;
        if (game != null && game.getStartingPlayer() != null) {
            startingSeat = game.getRegisteredPlayers().indexOf(game.getStartingPlayer());
        }

        // forge_decided: every controller call of a bridged seat that the seat did not answer, plus the caps
        final Map<String, Integer> forgeDecided = new TreeMap<>();
        for (int s = 0; s < 2; s++) {
            if (RlSeat.roleOf(ctl[s]) == RlSeat.Role.FORGE) {
                continue;
            }
            final JsonObject cj = lps[s].getCounters().toJson();
            if (cj.has("calls") && cj.get("calls").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : cj.getAsJsonObject("calls").entrySet()) {
                    forgeDecided.merge(e.getKey(), e.getValue().getAsInt(), Integer::sum);
                }
            }
        }
        for (Map.Entry<String, Integer> e : seat.okAnswers.entrySet()) {
            forgeDecided.merge(e.getKey(), -e.getValue(), Integer::sum);
        }
        forgeDecided.values().removeIf(v -> v <= 0);
        forgeDecided.keySet().removeAll(NOT_DECISIONS);
        forgeDecided.putAll(seat.forgeDecidedExtra);

        if (voidReason == null && forge.ai.simulation.DeckBeliefModel.refusal() != null) {
            // review #155 S1: a deck-belief refusal (sticky) voids every game that ends after it
            voidReason = "deck_belief_refused";
        }
        final JsonObject end = new JsonObject();
        end.addProperty("game_uid", Long.toUnsignedString(uid));
        end.add("result", RlWire.intArray(result));
        if (voidReason == null) {
            end.add("void", com.google.gson.JsonNull.INSTANCE);
        } else {
            end.addProperty("void", voidReason);
        }
        end.addProperty("reason", reason);
        end.addProperty("turns", turns);
        end.add("decisions", RlWire.intArray(seat.decisions(0), seat.decisions(1)));
        final JsonArray ov = new JsonArray();
        for (int d : seat.overridden) ov.add(d);
        end.add("overridden", ov);
        end.addProperty("cap_hits", seat.capHits);
        if (loopGuard > 0) {
            end.addProperty("loop_guard_hits", seat.loopGuardHits()); // exploit-panel-1015: only when on
        }
        end.addProperty("digest", digest);
        if (digestLb != null) {
            end.addProperty("digest_lb", digestLb);
        }
        if (lost.size() > 0) {
            end.add("scope_lost", lost);
        }
        end.addProperty("cpu_ms", cpuMs);
        end.addProperty("wall_ms", wallMs);
        if (cpuOtherMs > 0) {
            // S1: the game's other threads (Forge AI eval threads, the look-ahead's workers); cpu_ms + this = the game's
            end.addProperty("cpu_other_ms", cpuOtherMs);
        }
        if (search != null) {
            // S1: the look-ahead's worker threads' CPU is not in cpu_ms (the game thread's)
            end.addProperty("search_pool_cpu_ms", Math.round(search.poolCpuMs()));
            end.add("search", search.summary());
        }
        if (k8Stats != null) {
            end.add("opp_search", k8Stats); // gen-check-1007: the K8 seat's look-ahead stats and config
        }
        if (seeding != null) {
            end.add("seeded", seeding.seeded()); // G3: per seat, what was placed (or why not)
        }
        if (game != null) {
            final JsonArray generators = new JsonArray();
            boolean anyGenerator = false;
            for (Player p : game.getRegisteredPlayers()) {
                JsonObject summary = null;
                if (p.getController() instanceof forge.ai.CubeComboSeat) {
                    summary = ((forge.ai.CubeComboSeat) p.getController()).cubeCombo().honestGeneratorSummary();
                }
                anyGenerator |= summary != null;
                generators.add(summary);
            }
            if (anyGenerator) end.add("honest_generator", generators);
        }
        out.end = end;

        final JsonObject t = new JsonObject();
        t.addProperty("schema", RlTape.SCHEMA);
        t.addProperty("game_uid", Long.toUnsignedString(uid));
        t.addProperty("seed", seed);
        t.addProperty("mode", mode);
        final JsonArray dj = new JsonArray();
        for (int s = 0; s < 2; s++) {
            final JsonObject d = new JsonObject();
            d.addProperty("path", decks[s]);
            d.addProperty("sha", shas[s]);
            dj.add(d);
        }
        t.add("decks", dj);
        t.add("controllers", ctlJ.deepCopy());
        t.addProperty("starting_seat", startingSeat);
        t.addProperty("jar_sha", jarSha);
        t.addProperty("schema_sha", feat.schemaSha());
        t.addProperty("card_index_sha", feat.index().sha());
        t.add("result", end.get("result"));
        t.add("void", end.get("void"));
        t.addProperty("reason", reason);
        t.addProperty("turns", turns);
        if (end.has("honest_generator")) t.add("honest_generator", end.get("honest_generator"));
        t.addProperty("digest", digest);
        if (digestLb != null) {
            t.addProperty("digest_lb", digestLb);
        }
        if (lost.size() > 0) {
            t.add("scope_lost", lost);
        }
        t.addProperty("cap_hits", seat.capHits);
        final JsonObject fd = new JsonObject();
        for (Map.Entry<String, Integer> e : forgeDecided.entrySet()) fd.addProperty(e.getKey(), e.getValue());
        t.add("forge_decided", fd);
        final JsonObject census = seat.censusJson();
        t.add("census", census);
        t.add("overridden", ov);
        t.add("versions", seat.versions);
        final JsonArray decRows = new JsonArray();
        for (JsonArray r : seat.dec) decRows.add(r);
        t.add("dec", decRows);
        t.addProperty("cpu_ms", cpuMs);
        t.addProperty("wall_ms", wallMs);
        if (cpuOtherMs > 0) {
            t.addProperty("cpu_other_ms", cpuOtherMs);
        }
        if (search != null) {
            t.addProperty("search_pool_cpu_ms", Math.round(search.poolCpuMs()));
            t.add("search", end.get("search"));
        }
        if (k8Seat >= 0) {
            // gen-check-1007: the K8 seat's spec (a replay plays the tape's spec) and its search stats
            t.add("k8", k8spec.deepCopy());
            t.add("opp_search", end.get("opp_search"));
        }
        if (seeding != null) {
            t.add("seeding", seeding.spec()); // G3: the spec as played (a replay re-applies it) and its outcome
            t.add("seeded", end.get("seeded"));
        }
        if (fairNaming) {
            t.addProperty("fair_naming", true); // bvw-plr-1023: replays re-apply it; no key when off
        }
        if (loopGuard > 0) {
            // r4-design-1015: the guard as played (a replay re-applies it) and its hits; no key when off, so a guard-off
            // tape is unchanged
            t.addProperty("loop_guard", loopGuard);
            t.addProperty("loop_guard_hits", seat.loopGuardHits());
        }
        if (dl != null && dl.mode == DecisionLog.Mode.RECORD) {
            t.add(DecisionLog.KEY, dl.tapeJson()); // bvw-decision-log-1027: no key without a log
        }
        out.tape = t;

        AiCache.closeScope();
        IdScope.close();
        MyRandom.setThreadRandom(null);
        return out;
    }

    /**
     * r4-design-1015: the RL seats' loop guard K for one game: the GAME's own {@code loop_guard} (a replayed tape's,
     * or a server's) when present, else the actor's {@link Cfg#loopGuard}. 0 = off. A negative or non-integer value
     * is refused.
     */
    static int loopGuardOf(final JsonObject g, final int actorDefault) {
        if (g == null || !g.has("loop_guard") || g.get("loop_guard").isJsonNull()) {
            return actorDefault;
        }
        final int k = g.get("loop_guard").getAsInt();
        if (k < 0) {
            throw new IllegalArgumentException("loop_guard >= 0 (0 = off), not " + k);
        }
        return k;
    }

    /** Controller entry points that notify or plumb rather than decide; left out of forge_decided. */
    static final java.util.Set<String> NOT_DECISIONS = new java.util.HashSet<>(java.util.Arrays.asList("reveal",
            "notifyOfValue", "revealAnte", "revealAISkipCards", "revealUnsupported", "autoPassCancel",
            "awaitNextInput", "cancelAwaitNextInput", "getCostDecisionMaker", "playChosenSpellAbility"));

    private static PrintWriter forgeStateOut;

    /** The K8 parity dump (debug): {game_uid, dec_idx, seat, family, frame_type, frame_b64, forgeState, startingSeat,
     *  mulligans, deckPath} per sent frame, the ForgeState taken with the frame's observation. */
    static RlSeat.FrameListener forgeStateDumper(final String path, final RlSeat.FrameListener next,
            final String[] decks) {
        return (g, player, f, m, o, steps, answer) -> {
            if (next != null) {
                next.onFrame(g, player, f, m, o, steps, answer);
            }
            final JsonObject row = new JsonObject();
            row.addProperty("game_uid", Long.toUnsignedString(f.gameUid));
            row.addProperty("dec_idx", f.decIdx);
            row.addProperty("seat", f.seat);
            row.addProperty("family", f.family);
            row.addProperty("frame_type", f.teacher.length > 0 ? "RECORD" : "DECIDE");
            row.addProperty("frame_b64", java.util.Base64.getEncoder().encodeToString(RlWire.encodeDecide(f)));
            row.add("forgeState", o == null ? null : o.forgeState);
            final Player sp = g.getStartingPlayer();
            row.addProperty("startingSeat", sp == null ? -1 : g.getRegisteredPlayers().indexOf(sp));
            final JsonArray mull = new JsonArray();
            for (Player p : g.getRegisteredPlayers()) {
                mull.add(p.getStats().getMulliganCount());
            }
            row.add("mulligans", mull);
            row.addProperty("deckPath", decks[f.seat]);
            synchronized (RlActorBench.class) {
                try {
                    if (forgeStateOut == null) {
                        forgeStateOut = new PrintWriter(new FileWriter(path, StandardCharsets.UTF_8, true), true);
                    }
                    forgeStateOut.println(RlWire.canonicalString(row));
                } catch (IOException e) {
                    System.err.println("[rlactor] forge-state dump failed: " + e);
                }
            }
        };
    }

    static String lossReason(final Game game) {
        for (Player p : game.getRegisteredPlayers()) {
            final PlayerOutcome po = p.getOutcome();
            if (po != null && po.lossState != null) {
                final GameLossReason r = po.lossState;
                switch (r) {
                    case LifeReachedZero: return "life";
                    case Milled: return "library";
                    case Poisoned: return "poison";
                    case Conceded: return "concede";
                    default: return r.name().toLowerCase();
                }
            }
        }
        final GameOutcome go = game.getOutcome();
        return go == null ? "none" : String.valueOf(go.getWinCondition()).toLowerCase();
    }

    // ------------------------------------------------------------------------------------------------ live endpoint

    static RlSeat.Endpoint endpoint(final RlClient cl) {
        return new RlSeat.Endpoint() {
            @Override
            public RlWire.Decision decide(final byte[] p) throws IOException {
                return cl.decide(p);
            }

            @Override
            public void record(final byte[] p) throws IOException {
                cl.record(p);
            }
        };
    }

    /** Replay: RL seats answer from the tape's {@code dec} rows, asserting family and C at each dec_idx. */
    static RlSeat.Endpoint tapeEndpoint(final JsonObject line, final long uid) {
        final int version = line.has("schema_sha")
                && forge.bench.rl.RlSchemaV2.schemaSha().equals(line.get("schema_sha").getAsString()) ? 2 : 1;
        final Map<Integer, JsonArray> rows = new HashMap<>();
        for (JsonElement e : line.getAsJsonArray("dec")) {
            final JsonArray r = e.getAsJsonArray();
            rows.put(r.get(0).getAsInt(), r);
        }
        return new RlSeat.Endpoint() {
            @Override
            public RlWire.Decision decide(final byte[] p) throws IOException {
                final RlWire.Decide d = RlWire.decodeDecide(p, version);
                final JsonArray r = rows.get(d.decIdx);
                if (r == null) {
                    throw new RlClient.ServerError("replay", "no tape row for dec " + d.decIdx);
                }
                if (r.get(2).getAsInt() != d.family || r.get(3).getAsInt() != d.C) {
                    throw new RlClient.ServerError("replay", "dec " + d.decIdx + ": tape family/C " + r.get(2) + "/"
                            + r.get(3) + ", replay " + d.family + "/" + d.C);
                }
                final JsonArray st = r.get(4).getAsJsonArray();
                final RlWire.Decision x = new RlWire.Decision();
                x.gameUid = uid;
                x.decIdx = d.decIdx;
                x.status = RlWire.ST_OK;
                x.steps = new short[st.size()];
                for (int i = 0; i < st.size(); i++) x.steps[i] = (short) st.get(i).getAsInt();
                return x;
            }

            @Override
            public void record(final byte[] p) {
                // a record tape replays with Forge on the recorder seats; the frames go nowhere
            }
        };
    }

    // ------------------------------------------------------------------------------------------------ main

    static final AtomicInteger EXIT = new AtomicInteger(0);
    static final AtomicLong GAMES = new AtomicLong(), VOIDS = new AtomicLong(), SENT = new AtomicLong(),
            CAPPED_GAMES = new AtomicLong(), OVERRIDDEN = new AtomicLong();
    static final Map<String, AtomicLong> VOID_BY = new ConcurrentHashMap<>();

    static void boot() {
        boot(0x5eedL);
    }

    static void boot(final long globalSeed) {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            forge.bench.HeadlessSound.silence(prefs); // block-search-1019: no sounds or music headless
            return null;
        });
        MyRandom.setRandom(new Random(globalSeed));
    }

    static String jarSha(final Cfg cfg) {
        if (cfg.jarSha != null) {
            return cfg.jarSha;
        }
        try {
            final java.net.URL u = RlActorBench.class.getProtectionDomain().getCodeSource().getLocation();
            final Path p = Paths.get(u.toURI());
            if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                return CardIndex.sha256(Files.readAllBytes(p));
            }
        } catch (Exception e) {
            // fall through
        }
        return "unknown";
    }

    public static void main(final String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        final Cfg cfg;
        try {
            cfg = parseArgs(args);
            if (cfg.cardIndex == null) {
                cfg.cardIndex = cfg.rlRoot + "/data/card-index.tsv";
            }
        } catch (RuntimeException e) {
            System.err.println("[rlactor] bad config: " + e);
            System.exit(2);
            return;
        }
        System.exit(run(cfg));
    }

    /** The whole actor run; returns the exit code. Boots Forge once per JVM. */
    public static int run(final Cfg cfg) throws Exception {
        ensureBooted(cfg.globalSeed);
        final CardIndex index = CardIndex.load(Paths.get(cfg.cardIndex));
        final String jarSha = jarSha(cfg);
        if ("replay".equals(cfg.mode)) {
            return replay(cfg, index, jarSha);
        }
        if (!"train".equals(cfg.mode) && !"eval".equals(cfg.mode) && !"record".equals(cfg.mode)) {
            System.err.println("[rlactor] unknown mode " + cfg.mode);
            return 2;
        }
        if (cfg.server == null && cfg.gamesFile != null && ("record".equals(cfg.mode) || "train".equals(cfg.mode))) {
            return recordLocal(cfg, index, jarSha); // bvw-plr-1023: Forge-only games without a server
        }
        if (cfg.server == null) {
            System.err.println("[rlactor] no server");
            return 2;
        }
        final Path tapesDir = Paths.get(cfg.tapesDir != null ? cfg.tapesDir : "tapes/" + cfg.actorId);
        final RlTape tape = new RlTape(tapesDir, cfg.tapeRotate);
        EXIT.set(0);
        final long w0 = System.nanoTime();
        final com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        final long pcpu0 = os.getProcessCpuTime();
        final List<Thread> workers = new ArrayList<>();
        for (int k = 0; k < cfg.threads; k++) {
            final int thread = k;
            final Thread t = new Thread(() -> worker(cfg, thread, index, tape, jarSha), "rlactor-w" + k);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        final int hung = watchdog(workers, cfg);
        tape.close();
        final JsonObject s = new JsonObject();
        s.addProperty("type", "summary");
        s.addProperty("mode", cfg.mode);
        s.addProperty("actor_id", cfg.actorId);
        s.addProperty("threads", cfg.threads);
        s.addProperty("games", GAMES.get());
        s.addProperty("voids", VOIDS.get());
        final JsonObject vb = new JsonObject();
        for (Map.Entry<String, AtomicLong> e : VOID_BY.entrySet()) vb.addProperty(e.getKey(), e.getValue().get());
        s.add("void_by", vb);
        s.addProperty("sent", SENT.get());
        s.addProperty("capped_games", CAPPED_GAMES.get());
        s.addProperty("overridden", OVERRIDDEN.get());
        s.addProperty("hung", hung);
        s.addProperty("wall_s", (System.nanoTime() - w0) / 1e9);
        s.addProperty("process_cpu_s", (os.getProcessCpuTime() - pcpu0) / 1e9);
        s.addProperty("unknown_card_lookups", index.unknownLookups());
        final JsonArray unk = new JsonArray();
        final List<String> un = new ArrayList<>(index.unknownNames());
        Collections.sort(un);
        for (String n : un.subList(0, Math.min(200, un.size()))) unk.add(n);
        s.add("unknown_names", unk);
        s.addProperty("exit", hung > 0 ? 3 : EXIT.get());
        System.err.println("[rlactor] " + s);
        try (PrintWriter w = new PrintWriter(new FileWriter(tapesDir.resolve("summary-" + ProcessHandle.current().pid()
                + ".json").toFile(), StandardCharsets.UTF_8))) {
            w.println(RlWire.canonicalString(s));
        }
        return hung > 0 ? 3 : EXIT.get();
    }

    private static volatile boolean booted = false;

    /** Boot Forge once per JVM (card database, preferences). */
    static synchronized void ensureBooted() {
        ensureBooted(0x5eedL);
    }

    static synchronized void ensureBooted(final long globalSeed) {
        if (!booted) {
            boot(globalSeed);
            booted = true;
        }
    }
    /** Tests only (knowledge witness): an extra observer of every game's reveals and looks. Null by default. */
    static volatile java.util.function.Function<Game, BenchSession.KnowledgeObserver> KNOWLEDGE_TAP = null;
    /** Tests only (knowledge census): every game's learn / forget log. Null by default. */
    static volatile java.util.function.Function<Game, RlKnowledge.Log> KNOWLEDGE_LOG = null;
    /** Tests only (goldens, visibility): observes every sent frame of every game. Null by default. */
    static volatile RlSeat.FrameListener LISTENER = null;

    static void worker(final Cfg cfg, final int thread, final CardIndex index, final RlTape tape, final String jarSha) {
        final RlFeaturizer feat = new RlFeaturizer(index);
        RlClient cl = null;
        try {
            cl = RlClient.connect(cfg.server, 10_000, cfg.readTimeoutSec * 1000);
            final JsonObject hello = new JsonObject();
            hello.addProperty("proto", cfg.obsSchema == 2 ? RlWire.PROTO_V2 : RlWire.PROTO);
            hello.addProperty("schema_sha", cfg.obsSchema == 2 ? forge.bench.rl.RlSchemaV2.schemaSha()
                    : RlSchema.schemaSha());
            hello.addProperty("card_index_sha", index.sha());
            hello.addProperty("jar_sha", jarSha);
            hello.addProperty("actor_id", cfg.actorId);
            hello.addProperty("thread", thread);
            hello.addProperty("mode", cfg.mode);
            hello.addProperty("pid", ProcessHandle.current().pid());
            try {
                cl.hello(hello);
            } catch (RlClient.ServerError e) {
                System.err.println("[rlactor] w" + thread + " HELLO refused: " + e.getMessage());
                EXIT.compareAndSet(0, 2);
                return;
            }
            int played = 0;
            while (cfg.maxGames <= 0 || played < cfg.maxGames) {
                final JsonObject g = cl.nextGame(cfg.actorId, thread);
                if (g.has("stop") && g.get("stop").getAsBoolean()) {
                    break;
                }
                if (g.has("wait_ms")) {
                    Thread.sleep(Math.max(1, g.get("wait_ms").getAsLong()));
                    continue;
                }
                final RlClient conn = cl;
                final Played p = play(cfg, cfg.mode, g, feat, endpoint(conn), LISTENER, jarSha);
                if (p.guardError != null) {
                    System.err.println("[rlactor] w" + thread + " refusing GAME: " + p.guardError);
                    cl.error("deck_guard", p.guardError);
                    EXIT.compareAndSet(0, 2);
                    return;
                }
                played++;
                GAMES.incrementAndGet();
                SENT.addAndGet(p.seat.totalSent());
                OVERRIDDEN.addAndGet(p.seat.overridden.size());
                if (p.seat.capHits > 0) {
                    CAPPED_GAMES.incrementAndGet();
                }
                if (!p.end.get("void").isJsonNull()) {
                    VOIDS.incrementAndGet();
                    VOID_BY.computeIfAbsent(p.end.get("void").getAsString(), k -> new AtomicLong()).incrementAndGet();
                }
                tape.write(p.tape);
                if (p.fatal != null) {
                    System.err.println("[rlactor] w" + thread + " connection unusable: " + p.fatal);
                    EXIT.compareAndSet(0, 3);
                    return;
                }
                cl.gameEnd(p.end);
            }
        } catch (Exception e) {
            System.err.println("[rlactor] w" + thread + " stopped: " + e);
            EXIT.compareAndSet(0, 3);
        } finally {
            if (cl != null) {
                cl.close();
            }
        }
    }

    /** Ends games past their deadline; returns the number of games that ignored it for 60 s (hangs). */
    static int watchdog(final List<Thread> workers, final Cfg cfg) throws InterruptedException {
        final Map<Thread, Long> stuckSince = new HashMap<>();
        while (true) {
            boolean alive = false;
            for (Thread t : workers) alive |= t.isAlive();
            if (!alive) {
                return 0;
            }
            Thread.sleep(500);
            final long now = System.currentTimeMillis();
            for (Map.Entry<Thread, Object[]> e : RUNNING.entrySet()) {
                final Object[] r = e.getValue();
                if (now > (Long) r[1]) {
                    TIMED_OUT.put((Long) r[2], Boolean.TRUE);
                    ((Game) r[0]).setGameOver(GameEndReason.Draw);
                    final long since = stuckSince.computeIfAbsent(e.getKey(), x -> now);
                    if (now - since > 60_000L) {
                        System.err.println("[rlactor] game " + Long.toUnsignedString((Long) r[2])
                                + " ignored its timeout for 60 s: hung");
                        return 1;
                    }
                } else {
                    stuckSince.remove(e.getKey());
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ replay

    /** Expand {@code replay.dir}: every tape file under it, every non-void line (or a seeded sample of N). */
    static void expandReplayDir(final Cfg cfg) throws IOException {
        if (cfg.replayDir == null) {
            return;
        }
        final List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> st = Files.walk(Paths.get(cfg.replayDir))) {
            st.filter(p -> p.getFileName().toString().matches("tapes-\\d+\\.jsonl\\.gz")).sorted().forEach(files::add);
        }
        final List<int[]> lines = new ArrayList<>();
        for (Path f : files) {
            final int fi = cfg.replayTapes.size();
            cfg.replayTapes.add(f.toString());
            final List<JsonObject> rows = RlTape.read(f);
            for (int li = 0; li < rows.size(); li++) {
                if (rows.get(li).get("void").isJsonNull()) {
                    lines.add(new int[] {fi, li});
                }
            }
        }
        if (cfg.replaySample > 0 && cfg.replaySample < lines.size()) {
            Collections.shuffle(lines, new Random(cfg.replaySampleSeed));
            lines.subList(cfg.replaySample, lines.size()).clear();
            lines.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        }
        cfg.replayLines.addAll(lines);
    }

    /**
     * Replay the configured tape lines; with {@code threads} > 1 (lane rl-r0-b4b-1006) that many games run at once, each
     * thread with its own featurizer as in train/record mode. Rows are written in the configured line order either way.
     */
    static int replay(final Cfg cfg, final CardIndex index, final String jarSha) throws IOException {
        expandReplayDir(cfg);
        forge.bench.rl.RlReplayLabeller.FATAL.set(null); // review #9: a fatal search error stops this replay only
        if (cfg.labelSpec != null) {
            // bvw-plr-1023: the label directory and its per-JVM files
            labelSink = new forge.bench.rl.RlReplayLabeller.Sink(Paths.get(cfg.labelOut));
            System.err.println("[bvw-label] labelling replays: T=" + cfg.labelTurns + " K=" + cfg.labelSpec.worlds
                    + " spec " + cfg.labelSpec.sha().substring(0, 12) + " -> " + labelSink.labelsFile());
            if (cfg.labelBandLo > 0 || cfg.labelSearch != null) {
                // bvw-step1-build-1024
                System.err.println("[bvw-label] band [" + cfg.labelBandLo + ", " + cfg.labelTurns + ") search "
                        + (cfg.labelSearch == null ? "none" : cfg.labelSearch.sha().substring(0, 12) + " value "
                        + cfg.labelSearch.valueSha));
            }
        }
        if (cfg.exileScanOut != null) {
            // bvw-step1-build-1024 (OPEN-6 ruling): the read-only face-down-exile scan
            scanSink = new forge.bench.rl.RlExileScan.Sink(Paths.get(cfg.exileScanOut));
            System.err.println("[bvw-exile-scan] scanning replays: T=" + cfg.exileScanTurns + " -> " + scanSink.scanFile());
        }
        // bvw-decision-log-1027: logs an earlier "record" replay wrote (forced for their digest-equal games), and this
        // replay's own sink when it records
        final Map<String, List<JsonObject>> cache = new HashMap<>();
        for (int[] fl : cfg.replayLines) {
            final String f = cfg.replayTapes.get(fl[0]);
            if (!cache.containsKey(f)) {
                cache.put(f, RlTape.read(Paths.get(f)));
            }
        }
        final java.util.Set<String> wantedUids = new java.util.HashSet<>();
        for (int[] fl : cfg.replayLines) {
            wantedUids.add(forge.bench.rl.TrajectoryRef.canonicalUid(
                    cache.get(cfg.replayTapes.get(fl[0])).get(fl[1]).get("game_uid").getAsString()));
        }
        DecisionLog.SIDECAR = cfg.decisionLogs == null ? null : DecisionLog.loadSidecars(cfg.decisionLogs, wantedUids);
        DecisionLog.SINK = "record".equals(cfg.decisionLog) ? new DecisionLog.Sink(Paths.get(cfg.decisionLogOut)) : null;
        stateRows = cfg.decisionLogStateRows == null ? null : loadStateRows(cfg.decisionLogStateRows, wantedUids);
        labelLb = cfg.decisionLogLabelReplays == null ? null : loadLabelLb(cfg.decisionLogLabelReplays, wantedUids);
        if (DecisionLog.SINK != null) {
            // review #137 S3: every sidecar line names the pass (tier, attempt), its jar and its reference files
            final JsonObject prov = new JsonObject();
            if (cfg.decisionLogTier != null) {
                prov.addProperty("tier", cfg.decisionLogTier);
            }
            if (cfg.decisionLogAttempt != 0) {
                prov.addProperty("attempt", cfg.decisionLogAttempt);
            }
            prov.addProperty("jar_sha", jarSha);
            prov.add("reference_sha256", shas(cfg.reference));
            prov.add("state_rows_sha256", shas(cfg.decisionLogStateRows));
            prov.add("label_replays_sha256", shas(cfg.decisionLogLabelReplays));
            DecisionLog.SINK.provenance = prov;
        }
        if (DecisionLog.SIDECAR != null || DecisionLog.SINK != null) {
            System.err.println("[decision-log] replay: " + (DecisionLog.SIDECAR == null ? "" : DecisionLog.SIDECAR.size()
                    + " logged games of " + wantedUids.size() + " from " + cfg.decisionLogs.size() + " file(s)")
                    + (DecisionLog.SINK == null ? "" : " recording -> " + DecisionLog.SINK.file())
                    + (stateRows == null ? "" : "; state_sha rows for " + stateRows.size() + " games"));
        }
        trajectoryRef = null;
        forge.bench.rl.TrajectoryRef.NO_STACK_GAMES.set(0);
        final List<JsonObject> replayed = new ArrayList<>();
        for (int[] fl : cfg.replayLines) {
            replayed.add(cache.get(cfg.replayTapes.get(fl[0])).get(fl[1]));
        }
        if ("record".equals(cfg.decisionLog) && cfg.reference == null) {
            // review #124 S2-3: an HonestForcedWin tape's re-run is host-dependent; its log is never written unchecked
            int hfw = 0;
            for (JsonObject t : replayed) {
                hfw += honestForcedWinTape(t) && !t.has(DecisionLog.KEY) ? 1 : 0;
            }
            if (hfw > 0) {
                System.err.println("[decision-log] refused: a record replay of " + hfw + " HonestForcedWin tape(s) needs the "
                        + "step-0 trajectory reference (decisionLogReference, label.reference or exileScan.reference)");
                return 2;
            }
        }
        final JsonObject coverage = coverage(cfg, replayed); // review #124 S2-4: logs by archetype
        System.err.println("[decision-log] coverage " + RlWire.canonicalString(coverage));
        final String noRef = referenceRefusal(cfg, replayed);
        if (noRef != null) {
            // review-align #2: a step-1 search run on step-0 tapes is never run without the trajectory check
            System.err.println("[bvw-trajectory] " + noRef);
            return 2;
        }
        if (cfg.reference != null) {
            // the seat/dec_idx swap fix: the step-0 positions of exactly these games (canonical uids, review-align #9)
            final java.util.Set<String> uids = new java.util.TreeSet<>();
            for (JsonObject t : replayed) {
                uids.add(forge.bench.rl.TrajectoryRef.canonicalUid(t.get("game_uid").getAsString()));
            }
            trajectoryRef = forge.bench.rl.TrajectoryRef.load(cfg.reference, cfg.referenceTurns, uids);
            System.err.println("[bvw-trajectory] step-0 reference: " + trajectoryRef.games() + " of " + uids.size()
                    + " games, window " + cfg.referenceTurns + " turns, " + RlWire.canonicalString(
                    trajectoryRef.provenance()) + (trajectoryRef.rowsWithoutStack() > 0 ? "; " + trajectoryRef
                    .rowsWithoutStack() + " reference rows WITHOUT stack (their games: seat|turn|phase only)" : ""));
            if (trajectoryRef.games() < uids.size()) {
                // review-align #6: a game without reference rows would be labelled in full, then voided: refused
                final List<String> missing = new ArrayList<>();
                for (String u : uids) {
                    if (!trajectoryRef.knows(u)) {
                        missing.add(u);
                    }
                }
                System.err.println("[bvw-trajectory] refused: the reference knows " + trajectoryRef.games() + " of the "
                        + uids.size() + " replayed games (window " + cfg.referenceTurns + " turns); missing "
                        + missing.size() + ": " + missing.subList(0, Math.min(10, missing.size()))
                        + (missing.size() > 10 ? " ..." : ""));
                trajectoryRef = null;
                return 2;
            }
        }
        PrintWriter w = null;
        if (cfg.replayOut != null) {
            final Path o = Paths.get(cfg.replayOut);
            if (o.getParent() != null) {
                Files.createDirectories(o.getParent());
            }
            w = new PrintWriter(new FileWriter(o.toFile(), StandardCharsets.UTF_8, true), true);
        }
        final int n = cfg.replayLines.size();
        final long w0 = System.nanoTime();
        // lki-fix-port-1027: shared with the stall watchdog, which writes the finished rows if it stalls the replay
        final java.util.concurrent.atomic.AtomicReferenceArray<String> out = new java.util.concurrent.atomic.AtomicReferenceArray<>(n);
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        final int threads = Math.max(1, Math.min(cfg.threads, n));
        final List<Thread> ts = new ArrayList<>();
        for (int k = 0; k < threads; k++) {
            final Thread t = new Thread(() -> {
                final RlFeaturizer feat = new RlFeaturizer(index);
                for (int i = next.getAndIncrement(); i < n; i = next.getAndIncrement()) {
                    if (forge.bench.rl.RlReplayLabeller.FATAL.get() != null) {
                        // review #9: fail fast: no more games after a fatal search error
                        final JsonObject skip = new JsonObject();
                        skip.addProperty("line", cfg.replayLines.get(i)[1]);
                        skip.addProperty("equal", false);
                        skip.addProperty("error", "skipped after a fatal search error");
                        out.set(i, RlWire.canonicalString(skip));
                        continue;
                    }
                    final String r = RlWire.canonicalString(replayOne(cfg, cache, cfg.replayLines.get(i), feat, jarSha));
                    System.err.println("[rlactor] replay " + r);
                    out.set(i, r);
                }
            }, "rlreplay-w" + k);
            ts.add(t); // started below, after the stall watchdog (review S3-5)
        }
        // lki-fix-port-1027: the stall watchdog (GC fraction, the rlreplay-w* threads' mutator share, old gen after GC),
        // armed before the game threads start (review S3-5); null when the collector is not G1 / Parallel / Serial
        ReplayStallWatchdog wd = null;
        if (cfg.watchdog.enabled && n > 0) {
            final List<String> paths = new ArrayList<>();
            for (int[] fl : cfg.replayLines) {
                paths.add(cfg.replayTapes.get(fl[0]));
            }
            final Path wdir = Paths.get(cfg.watchdog.dir != null ? cfg.watchdog.dir
                    : cfg.replayOut != null && Paths.get(cfg.replayOut).getParent() != null
                            ? Paths.get(cfg.replayOut).getParent().toString()
                    : cfg.labelOut != null ? cfg.labelOut : ".");
            wd = ReplayStallWatchdog.start(cfg.watchdog, ts, wdir, replayed, paths, cfg.replayLines, out,
                    cfg.labelSpec != null ? labelSink : null,
                    cfg.labelR3 != null ? forge.bench.rl.RlReplayLabeller.SCHEMA_R3
                            : cfg.labelSearch != null ? forge.bench.rl.RlReplayLabeller.SCHEMA_STEP
                            : forge.bench.rl.RlReplayLabeller.SCHEMA, w, jarSha);
            if (wd != null) {
                System.err.println("[rlactor] stall watchdog " + RlWire.canonicalString(cfg.watchdog.toJson()) + " -> "
                        + wd.progressFile());
            }
        }
        for (Thread t : ts) {
            t.start();
        }
        for (Thread t : ts) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("replay interrupted", e);
            }
        }
        if (wd != null && !wd.close()) {
            return ReplayStallWatchdog.EXIT_STALL; // the watchdog wrote every row (a stall exits the JVM before this)
        }
        int unequal = 0;
        for (int i = 0; i < n; i++) {
            final String r = out.get(i);
            if (r == null || !com.google.gson.JsonParser.parseString(r).getAsJsonObject().get("equal").getAsBoolean()) {
                unequal++;
            }
            if (w != null && r != null) {
                w.println(r);
            }
        }
        if (w != null) {
            w.close();
        }
        if (DecisionLog.SINK != null) {
            DecisionLog.SINK.close(); // bvw-decision-log-1027
        }
        // bvw-plr-1023: the replay's wall time and thread count (label throughput is read from them)
        final JsonObject sum = new JsonObject();
        sum.addProperty("type", "replay-summary");
        sum.addProperty("lines", n);
        sum.addProperty("unequal", unequal);
        sum.addProperty("threads", threads);
        sum.addProperty("wall_s", (System.nanoTime() - w0) / 1e9);
        sum.addProperty("label", cfg.labelSpec != null);
        sum.add("watchdog", cfg.watchdog.toJson()); // lki-fix-port-1027
        sum.addProperty("watchdog_armed", wd != null); // review S3-8: false when disabled or under another collector
        sum.add("decision_log_coverage", coverage); // review #124 S2-4
        if (wd != null) {
            sum.addProperty("watchdog_samples", wd.samples());
        }
        if (trajectoryRef != null) {
            // review-align #2 / #9: the trajectory check's outcome and its noStack fallbacks, per replay
            int trajVoid = 0;
            for (int i = 0; i < n; i++) {
                final String r = out.get(i);
                final JsonObject o = r == null ? null : com.google.gson.JsonParser.parseString(r).getAsJsonObject();
                if (o != null && o.has("trajectory_equal") && !o.get("trajectory_equal").getAsBoolean()) {
                    trajVoid++;
                }
            }
            sum.addProperty("trajectory_void", trajVoid);
            sum.addProperty("no_stack_games", forge.bench.rl.TrajectoryRef.NO_STACK_GAMES.get());
            sum.add("reference", trajectoryRef.provenance());
        }
        final String fatal = forge.bench.rl.RlReplayLabeller.FATAL.get();
        if (fatal != null) {
            sum.addProperty("fatal", fatal); // review #9
        }
        System.err.println("[rlactor] " + RlWire.canonicalString(sum));
        if (fatal != null) {
            System.err.println("[bvw-label] FATAL, the replay stopped: " + fatal);
            return 2;
        }
        return unequal > 0 ? 4 : 0;
    }

    static JsonObject replayOne(final Cfg cfg, final Map<String, List<JsonObject>> cache, final int[] fl,
            final RlFeaturizer feat, final String jarSha) {
        final String f = cfg.replayTapes.get(fl[0]);
        final JsonObject row = new JsonObject();
        row.addProperty("tape", f);
        row.addProperty("line", fl[1]);
        try {
            final JsonObject t = cache.get(f).get(fl[1]);
            row.add("game_uid", t.get("game_uid"));
            row.add("digest_tape", t.get("digest"));
            // bvw-decision-log-1027: the game's log (the tape's own, or an earlier record replay's for this exact game):
            // replayed FORCED, so its seats decide nothing and their budgets cannot bind; without one, as before
            final DecisionLog dl = decisionLogFor(cfg, t);
            final boolean forced = dl != null && dl.mode == DecisionLog.Mode.FORCED;
            final String noRuntime = t.has("controllers") && !forced ? counterGeneratorRefusal(
                    t.getAsJsonArray("controllers"), cfg.aiTimeoutSec) : null;
            if (!t.get("void").isJsonNull()) {
                row.addProperty("error", "void");
                row.addProperty("equal", false);
            } else if ("forced".equals(cfg.decisionLog) && !forced) {
                // review #124 S2-4: forced-only: a game without a log is void, never re-run
                row.addProperty("void", DecisionLog.MISSING);
                row.addProperty("error", DecisionLog.MISSING);
                row.addProperty("equal", false);
            } else if (noRuntime != null) {
                // review #3: a counted generator's tape replays only under the Forge AI settings it was played with
                row.addProperty("error", noRuntime);
                row.addProperty("equal", false);
            } else {
                final JsonObject g = new JsonObject();
                g.add("game_uid", t.get("game_uid"));
                g.add("seed", t.get("seed"));
                final JsonArray decks = new JsonArray(), shas = new JsonArray();
                for (JsonElement d : t.getAsJsonArray("decks")) {
                    decks.add(d.getAsJsonObject().get("path"));
                    shas.add(d.getAsJsonObject().get("sha"));
                }
                g.add("decks", decks);
                g.add("deck_sha", shas);
                g.add("controllers", t.get("controllers"));
                if (t.has("k8")) {
                    g.add("k8", t.get("k8")); // gen-check-1007: the K8 seat replays with the spec it played
                }
                if (t.has("seeding")) {
                    g.add("seeding", t.get("seeding")); // cm-curriculum-1009: the seeding it played, re-applied
                }
                // r4-design-1015: the loop guard it played (absent = off), whatever the replay actor's own loopGuard
                g.addProperty("loop_guard", t.has("loop_guard") ? t.get("loop_guard").getAsInt() : 0);
                if (t.has("fair_naming")) {
                    g.add("fair_naming", t.get("fair_naming")); // bvw-plr-1023: the fair naming it played
                }
                g.addProperty("priv", false);
                g.addProperty("obs_schema", t.has("schema_sha") && forge.bench.rl.RlSchemaV2.schemaSha().equals(
                        t.get("schema_sha").getAsString()) ? 2 : 1);
                // the deck guard of the tape's own mode; the seat's behaviour is the tape mode's
                final String tmode = t.get("mode").getAsString();
                // bvw-plr-1023: the honest-solver labeller at both seats' priority decisions (refused unless the spec
                // is honest; checked again here, before the game is built)
                if (cfg.labelSpec != null) {
                    cfg.labelSpec.checkHonest();
                    forge.bench.rl.RlReplayLabeller.checkTrainTape(t);
                }
                final forge.bench.rl.RlReplayLabeller[] labs = new forge.bench.rl.RlReplayLabeller[1];
                final int tobs = g.get("obs_schema").getAsInt();
                final forge.bench.rl.RlExileScan[] scans = new forge.bench.rl.RlExileScan[1];
                final GameHook hook0 = cfg.labelSpec != null ? (game, seat, know) -> {
                    labs[0] = new forge.bench.rl.RlReplayLabeller(cfg.labelSpec, cfg.labelBandLo, cfg.labelTurns,
                            cfg.labelSearch, t, jarSha, game, know, feat.index(), tobs, cfg.labelR3 != null);
                    if (cfg.labelR3 != null) {
                        // r3-labelsearch-1030 (E1): the R3 rows (priority + choice) of this game
                        final long ruid = forge.bench.rl.RlWire.parseUid(t.get("game_uid").getAsString());
                        labs[0].setR3(cfg.labelR3, "static".equals(cfg.labelR3Ranker.get("kind").getAsString())
                                ? forge.bench.rl.RlReplayLabeller.staticRanker()
                                : forge.bench.rl.RlReplayLabeller.serviceRanker(cfg.labelR3Ranker.getAsJsonObject("rlsearch"),
                                        cfg.labelR3Ranker.get("valueSha").getAsString(), feat.index(), tobs, jarSha, ruid));
                    }
                    if (trajectoryRef != null) {
                        labs[0].setReference(trajectoryRef.of(t.get("game_uid").getAsString()), trajectoryRef.turns,
                                trajectoryRef.provenance());
                    }
                    game.addPriorityDecisionListener(labs[0]);
                } : cfg.exileScanOut != null ? (game, seat, know) -> {
                    scans[0] = new forge.bench.rl.RlExileScan(cfg.exileScanTurns, t, game);
                    if (trajectoryRef != null) {
                        scans[0].setReference(trajectoryRef.of(t.get("game_uid").getAsString()), trajectoryRef.turns,
                                trajectoryRef.provenance());
                    }
                    game.addPriorityDecisionListener(scans[0]);
                } : null;
                // review #124 S2-3: a record replay's checks (the trajectory against step 0 when no labeller or scan
                // runs it; a later step's state_sha rows), read-only
                final String cuid = forge.bench.rl.TrajectoryRef.canonicalUid(t.get("game_uid").getAsString());
                final boolean ownTrajectory = cfg.labelSpec == null && cfg.exileScanOut == null && trajectoryRef != null;
                final Map<String, String> rowsHere = stateRows == null ? null : stateRows.get(cuid);
                final DecisionLogCheck[] checks = new DecisionLogCheck[1];
                final GameHook hook = dl == null || dl.mode != DecisionLog.Mode.RECORD || (!ownTrajectory && rowsHere == null)
                        ? hook0 : (game, seat, know) -> {
                    if (hook0 != null) {
                        hook0.start(game, seat, know);
                    }
                    checks[0] = new DecisionLogCheck(game, t, ownTrajectory ? new forge.bench.rl.TrajectoryRef.Recorder(
                            trajectoryRef.of(t.get("game_uid").getAsString()), trajectoryRef.turns,
                            trajectoryRef.provenance()) : null, rowsHere);
                    game.addPriorityDecisionListener(checks[0]);
                };
                final Played p = play(cfg, tmode, g, feat, tapeEndpoint(t, RlWire.parseUid(
                        t.get("game_uid").getAsString())), null, jarSha, hook, dl);
                final forge.bench.rl.RlReplayLabeller lab = labs[0];
                if (p.guardError != null) {
                    row.addProperty("error", "deck_guard: " + p.guardError);
                    row.addProperty("equal", false);
                } else {
                    row.add("digest_replay", p.end.get("digest"));
                    // k8-determinism-1008: the LookaheadBench digest (the tape's, when it has one) and a K8 seat's
                    // search counts, so a replay audits against LookaheadBench rows and the live run's counts
                    if (t.has("digest_lb")) {
                        row.add("digest_lb_tape", t.get("digest_lb"));
                    }
                    if (p.end.has("digest_lb")) {
                        row.add("digest_lb_replay", p.end.get("digest_lb"));
                    }
                    if (p.end.has("opp_search")) {
                        final JsonObject os = p.end.getAsJsonObject("opp_search");
                        final JsonObject k = new JsonObject();
                        for (String key : new String[] {"decisions", "searched", "departed"}) {
                            if (os.has(key)) {
                                k.add(key, os.get(key));
                            }
                        }
                        row.add("opp_search", k);
                    }
                    if (p.end.has("scope_lost")) {
                        row.add("scope_lost", p.end.get("scope_lost"));
                    }
                    boolean eq = p.end.get("digest").getAsString().equals(t.get("digest").getAsString())
                            && p.end.get("void").isJsonNull();
                    if (p.decisionLog != null || cfg.decisionLog != null) {
                        // bvw-decision-log-1027: the replay's own CPU (game thread + its other threads), for the forced
                        // vs re-run comparison; only in runs that use the log (other rows are unchanged)
                        row.add("cpu_ms", p.end.get("cpu_ms"));
                        if (p.end.has("cpu_other_ms")) {
                            row.add("cpu_other_ms", p.end.get("cpu_other_ms"));
                        }
                    }
                    if (p.decisionLog != null) {
                        // bvw-decision-log-1027: a forced replay is equal only when every seat used its whole log and
                        // none diverged (a divergence is void, never re-searched)
                        row.add(DecisionLog.KEY, p.decisionLog.replayJson());
                        row.addProperty("decision_log_source", dlSource(t, dl));
                        if (forced) {
                            eq &= p.decisionLog.complete();
                            // review #124 S2-2: and LookaheadBench's digest of the game, when the tape has one
                            if (t.has("digest_lb") && p.end.has("digest_lb")) {
                                // review #137 S2-2: a sidecar log's game is the one its pass verified (step 0's label
                                // replay's digest_lb where that differed from the tape's)
                                final String side = "sidecar".equals(dlSource(t, dl)) ? DecisionLog.sidecarLb(t) : null;
                                eq &= (side != null ? side : t.get("digest_lb").getAsString())
                                        .equals(p.end.get("digest_lb").getAsString());
                            }
                        }
                    }
                    if (forced) {
                        // the generator did not run: its spec is the log's (no honest_generator summary to compare)
                        row.addProperty("generator", "forced");
                    } else if (t.has("honest_generator") || p.end.has("honest_generator")) {
                        final boolean sameSpec = generatorSpecs(t).equals(generatorSpecs(p.end));
                        row.addProperty("generator_spec_equal", sameSpec);
                        eq &= sameSpec;
                    }
                    if (t.has("seeded")) {
                        // cm-curriculum-1009: a seeded tape replays only if the same pieces went to the same places
                        final boolean se = t.get("seeded").equals(p.end.get("seeded"));
                        row.addProperty("seeded_equal", se);
                        eq &= se;
                    }
                    row.addProperty("equal", eq);
                    if (cfg.labelSpec != null && lab == null) {
                        row.addProperty("error", "label: the labeller was never attached");
                        row.addProperty("equal", false);
                    }
                    if (cfg.exileScanOut != null) {
                        if (scans[0] == null) {
                            row.addProperty("error", "exile scan: never attached");
                            row.addProperty("equal", false);
                        } else {
                            try {
                                scans[0].finish(scanSink, eq, row);
                            } catch (IOException e) {
                                row.addProperty("error", "exile scan sink: " + e);
                                row.addProperty("equal", false);
                            }
                        }
                    }
                    if (checks[0] != null) {
                        checks[0].finish(t.get("game_uid").getAsString(), row);
                    }
                    if (lab != null) {
                        if (p.decisionLog != null) {
                            // review #124 S2-2: the log's verdict on the games and index rows
                            lab.setDecisionLog(p.decisionLog.labelVerdict(dlSource(t, dl)));
                        }
                        // bvw-plr-1023: the game's label rows are written only when its digest is the tape's
                        try {
                            lab.finish(labelSink, eq, row);
                        } catch (IOException e) {
                            row.addProperty("error", "label sink: " + e);
                            row.addProperty("equal", false);
                        }
                    }
                    if (dl != null && dl.mode == DecisionLog.Mode.RECORD && DecisionLog.SINK != null) {
                        // bvw-decision-log-1027: the re-run seats' log, for later forced replays of this exact game
                        try {
                            // review #137 S3: the row's final verdict (label and scan finish included), not the local eq
                            if (labelLb != null) {
                                final String ref = labelLb.get(forge.bench.rl.TrajectoryRef.canonicalUid(
                                        t.get("game_uid").getAsString()));
                                if (ref != null) {
                                    row.addProperty("digest_lb_reference", ref);
                                }
                            }
                            DecisionLog.SINK.write(t, f, fl[1], row, row.get("equal").getAsBoolean(), dl);
                        } catch (IOException e) {
                            row.addProperty("error", "decision log sink: " + e);
                            row.addProperty("equal", false);
                        }
                    }
                    // the replayed game's own census (lane rl-r0-b4b-1006): a jar's asks on the exact games of a
                    // tape, e.g. the before/after of a record-mode mapping fix
                    if (p.tape != null && p.tape.has("census")) {
                        row.add("census", p.tape.get("census"));
                    }
                    if (p.seat.fatal != null) {
                        row.addProperty("error", p.seat.fatal);
                    }
                }
            }
        } catch (RuntimeException e) {
            row.addProperty("error", e.toString());
            row.addProperty("equal", false);
        }
        return row;
    }

    /**
     * bvw-decision-log-1027: the log a replay of tape line {@code t} plays: FORCED from the line's own
     * {@link DecisionLog#KEY} or from an earlier record replay's log of this exact game (uid and digest); RECORD (replay
     * {@code "record"}, a line without a log); null (re-run, as before: {@code "rerun"}, or no log).
     */
    static DecisionLog decisionLogFor(final Cfg cfg, final JsonObject t) {
        final String mode = cfg.decisionLog == null ? "auto" : cfg.decisionLog;
        if ("rerun".equals(mode)) {
            return null;
        }
        if (t.has(DecisionLog.KEY) && t.get(DecisionLog.KEY).isJsonObject()) {
            return DecisionLog.forced(t.getAsJsonObject(DecisionLog.KEY));
        }
        final JsonObject side = DecisionLog.sidecarFor(t);
        if (side != null) {
            return DecisionLog.forced(side);
        }
        return "record".equals(mode) ? DecisionLog.record(cfg.decisionLogDiag) : null;
    }

    static String dlSource(final JsonObject t, final DecisionLog dl) {
        if (dl.mode == DecisionLog.Mode.RECORD) {
            return "recorded";
        }
        return t.has(DecisionLog.KEY) ? "tape" : "sidecar";
    }

    /**
     * bvw-step1-build-1024 (review-align #2): null, or why this replay is refused before any game: a label search block
     * on HonestForcedWin tapes (the step-0 generator, profile HonestForcedWin, HonestForcedWinCounted or HonestForcedWinCounted2, or a tape with
     * honest_generator seats) without {@code label.reference}, or with a reference window other than step 0's
     * ({@link forge.bench.rl.TrajectoryRef#STEP0_WINDOW} turns).
     */
    static String referenceRefusal(final Cfg cfg, final List<JsonObject> tapes) {
        if (cfg.labelSearch == null) {
            return null;
        }
        int hfw = 0;
        for (JsonObject t : tapes) {
            hfw += honestForcedWinTape(t) ? 1 : 0;
        }
        if (hfw == 0) {
            return null;
        }
        if (cfg.reference == null) {
            return "refused: a label search on " + hfw + " HonestForcedWin (step-0) tape(s) needs label.reference (the "
                    + "step-0 label index rows of its games; the trajectory check)";
        }
        if (cfg.referenceTurns != forge.bench.rl.TrajectoryRef.STEP0_WINDOW) {
            return "refused: a label search on HonestForcedWin (step-0) tapes checks step 0's window: referenceTurns "
                    + forge.bench.rl.TrajectoryRef.STEP0_WINDOW + ", not " + cfg.referenceTurns;
        }
        return null;
    }

    /** A tape of the step-0 generator: an HonestForcedWin(Counted, Counted2) seat, or honest_generator seats on the tape. */
    static boolean honestForcedWinTape(final JsonObject t) {
        if (t.has("honest_generator") && t.get("honest_generator").isJsonArray()) {
            for (JsonElement e : t.getAsJsonArray("honest_generator")) {
                if (!e.isJsonNull()) {
                    return true;
                }
            }
        }
        if (t.has("controllers") && t.get("controllers").isJsonArray()) {
            for (JsonElement c : t.getAsJsonArray("controllers")) {
                final String s = c.isJsonNull() ? "" : c.getAsString();
                if (("forge:" + forge.ai.AiProfileUtil.HONEST_FORCED_WIN_PROFILE).equals(s)
                        || ("forge:" + forge.ai.AiProfileUtil.HONEST_FORCED_WIN_COUNTED_PROFILE).equals(s)
                        || ("forge:" + forge.ai.AiProfileUtil.HONEST_FORCED_WIN_COUNTED2_PROFILE).equals(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * bvw-step1-build-1024 (review #3): a counter-budget generator seat (the HonestForcedWinCounted(2) profile, or a
     * forcedWin.spec override with budget=counter) refuses a run whose Forge AI clocks can bind. Null = fine.
     */
    static String counterGeneratorRefusal(final JsonArray controllers, final int aiTimeoutSec) {
        boolean counter = String.valueOf(System.getProperty("forge.ai.forcedWin.spec")).contains("budget=counter");
        for (JsonElement c : controllers) {
            counter |= ("forge:" + forge.ai.AiProfileUtil.HONEST_FORCED_WIN_COUNTED_PROFILE).equals(c.getAsString())
                    || ("forge:" + forge.ai.AiProfileUtil.HONEST_FORCED_WIN_COUNTED2_PROFILE).equals(c.getAsString());
        }
        return counter ? forge.ai.simulation.ForcedWinSolver.counterRuntimeRefusal(aiTimeoutSec) : null;
    }

    /** Review #124 S2-3: a later step's state_sha rows, by canonical uid, then seat | dec_idx (null = none). */
    static Map<String, Map<String, String>> stateRows = null;
    /** Review #137 S2-2: step 0's label replay digest_lb per canonical uid (decisionLogReference.labelReplays), or null. */
    static Map<String, String> labelLb = null;

    /** Step 0's label replay rows: the LookaheadBench digest of each equal replay of a wanted game. */
    static Map<String, String> loadLabelLb(final List<String> files, final java.util.Set<String> wanted) throws IOException {
        final Map<String, String> m = new HashMap<>();
        for (String f : files) {
            for (String line : Files.readAllLines(Paths.get(f), StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                final JsonObject r = JsonParser.parseString(line).getAsJsonObject();
                if (!r.has("digest_lb_replay") || r.get("digest_lb_replay").isJsonNull()
                        || !r.has("equal") || !r.get("equal").getAsBoolean()) {
                    continue;
                }
                final String uid = forge.bench.rl.TrajectoryRef.canonicalUid(r.get("game_uid").getAsString());
                if (wanted.contains(uid)) {
                    final String prev = m.put(uid, r.get("digest_lb_replay").getAsString());
                    if (prev != null && !prev.equals(r.get("digest_lb_replay").getAsString())) {
                        throw new IllegalArgumentException("labelReplays: game " + uid + " has two label replays of different games");
                    }
                }
            }
        }
        return m;
    }

    /** The files' sha256 (hex), in order; null when there are none. */
    static JsonElement shas(final List<String> files) {
        if (files == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        final JsonArray a = new JsonArray();
        for (String f : files) {
            try {
                final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                final byte[] d = md.digest(Files.readAllBytes(Paths.get(f)));
                final StringBuilder sb = new StringBuilder();
                for (byte b : d) {
                    sb.append(String.format("%02x", b));
                }
                a.add(sb.toString());
            } catch (IOException | java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("sha256 of " + f + ": " + e, e);
            }
        }
        return a;
    }

    /** Review #124 S2-3: the state_sha of each (seat, dec_idx) row of {@code files} for the games in {@code wanted}. */
    static Map<String, Map<String, String>> loadStateRows(final List<String> files, final java.util.Set<String> wanted)
            throws IOException {
        final Map<String, Map<String, String>> m = new HashMap<>();
        for (String f : files) {
            for (String line : Files.readAllLines(Paths.get(f), StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                final JsonObject r = JsonParser.parseString(line).getAsJsonObject();
                if (!r.has("state_sha") || r.get("state_sha").isJsonNull()) {
                    continue;
                }
                final String uid = forge.bench.rl.TrajectoryRef.canonicalUid(r.get("game_uid").getAsString());
                if (wanted.contains(uid)) {
                    // review #137 S2-1: keyed by the position; the row's seat is part of what must match there
                final String prev = m.computeIfAbsent(uid, x -> new HashMap<>()).put(String.valueOf(r.get("dec_idx").getAsInt()),
                        r.get("seat").getAsInt() + "|" + r.get("state_sha").getAsString());
                if (prev != null && !prev.equals(r.get("seat").getAsInt() + "|" + r.get("state_sha").getAsString())) {
                    throw new IllegalArgumentException("stateRows: game " + uid + " has two different rows at dec_idx "
                            + r.get("dec_idx").getAsInt());
                }
                }
            }
        }
        return m;
    }

    /**
     * Review #124 S2-4: how many of the replayed games carry a log (on the tape or in a decisionLogs file), by archetype
     * (each seat's deck family: {@code cur-<family>-NNNN.dck}).
     */
    static JsonObject coverage(final Cfg cfg, final List<JsonObject> replayed) {
        final Map<String, int[]> by = new TreeMap<>();
        int logged = 0;
        for (JsonObject t : replayed) {
            final boolean has = !"rerun".equals(cfg.decisionLog) && (t.has(DecisionLog.KEY) || DecisionLog.sidecarFor(t) != null);
            logged += has ? 1 : 0;
            for (JsonElement d : t.getAsJsonArray("decks")) {
                final String name = Paths.get(d.getAsJsonObject().get("path").getAsString()).getFileName().toString();
                final String fam = name.replaceAll("^cur-", "").replaceAll("-\\d+\\.dck$", "").replaceAll("\\.dck$", "");
                final int[] c = by.computeIfAbsent(fam, x -> new int[2]);
                c[has ? 0 : 1]++;
            }
        }
        final JsonObject o = new JsonObject();
        o.addProperty("games", replayed.size());
        o.addProperty("logged", logged);
        final JsonObject fams = new JsonObject();
        for (Map.Entry<String, int[]> e : by.entrySet()) {
            final JsonObject x = new JsonObject();
            x.addProperty("logged", e.getValue()[0]);
            x.addProperty("missing", e.getValue()[1]);
            fams.add(e.getKey(), x);
        }
        o.add("by_archetype_seat", fams);
        return o;
    }

    /** bvw-plr-1023: the replay's label sink (one per JVM), or null. */
    static forge.bench.rl.RlReplayLabeller.Sink labelSink = null;
    /** bvw-step1-build-1024: the exile scan's per-JVM files (exileScan replays only). */
    static forge.bench.rl.RlExileScan.Sink scanSink = null;
    /** bvw-step1-build-1024: the step-0 reference positions (null = no trajectory check). */
    static forge.bench.rl.TrajectoryRef trajectoryRef = null;

    /** Config identity, excluding wall time and other diagnostic counters. */
    static JsonArray generatorSpecs(final JsonObject row) {
        final JsonArray out = new JsonArray();
        if (row.has("honest_generator")) {
            for (JsonElement e : row.getAsJsonArray("honest_generator")) {
                out.add(e.isJsonNull() ? com.google.gson.JsonNull.INSTANCE : e.getAsJsonObject().get("spec"));
            }
        }
        return out;
    }

    /** bvw-plr-1023: a RECORD endpoint with no server: the frames go nowhere (the tapes are the output). */
    static RlSeat.Endpoint nullRecorder() {
        return new RlSeat.Endpoint() {
            @Override
            public RlWire.Decision decide(final byte[] p) throws IOException {
                throw new IOException("record mode without a server answers no DECIDE");
            }

            @Override
            public void record(final byte[] p) {
            }
        };
    }

    /**
     * bvw-plr-1023: Forge-only games without a server ({@link Cfg#gamesFile}): record mode (recorder seats; their RECORD
     * frames go nowhere) or train mode with Forge seats only ({@code forge}, {@code forge:<Profile>}; e.g. the cube combo
     * profile, which recorders refuse). The GAME messages of the file, in order, over the actor's threads; tapes as in
     * those modes; one GAME_END row per game (plus seed, controllers, decks, the GAME's meta) in
     * {@code <tapesDir>/games-<pid>.jsonl}. Exit codes as {@link #run}.
     */
    static int recordLocal(final Cfg cfg, final CardIndex index, final String jarSha) throws Exception {
        final List<JsonObject> games = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(cfg.gamesFile), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                games.add(JsonParser.parseString(line).getAsJsonObject());
            }
        }
        // record mode: recorder seats only; train mode: Forge seats only (forge, forge:<Profile>); never an RL seat
        final RlSeat.Role want = "record".equals(cfg.mode) ? RlSeat.Role.RECORD : RlSeat.Role.FORGE;
        for (JsonObject g : games) {
            for (JsonElement c : g.getAsJsonArray("controllers")) {
                if (RlSeat.roleOf(c.getAsString()) != want) {
                    System.err.println("[rlactor] local " + cfg.mode + " mode plays " + want + " seats only, not " + c);
                    return 2;
                }
            }
            final String no = counterGeneratorRefusal(g.getAsJsonArray("controllers"), cfg.aiTimeoutSec);
            if (no != null) {
                System.err.println("[rlactor] " + no);
                return 2;
            }
        }
        final Path tapesDir = Paths.get(cfg.tapesDir != null ? cfg.tapesDir : "tapes/" + cfg.actorId);
        final RlTape tape = new RlTape(tapesDir, cfg.tapeRotate);
        final Path endLog = tapesDir.resolve("games-" + ProcessHandle.current().pid() + ".jsonl");
        EXIT.set(0);
        final long w0 = System.nanoTime();
        final AtomicInteger next = new AtomicInteger();
        final List<Thread> workers = new ArrayList<>();
        for (int k = 0; k < cfg.threads; k++) {
            final Thread t = new Thread(() -> {
                final RlFeaturizer feat = new RlFeaturizer(index);
                for (int i = next.getAndIncrement(); i < games.size(); i = next.getAndIncrement()) {
                    final JsonObject g = games.get(i);
                    final Played p = play(cfg, cfg.mode, g, feat, nullRecorder(), LISTENER, jarSha);
                    if (p.guardError != null) {
                        System.err.println("[rlactor] refusing GAME " + g.get("game_uid") + ": " + p.guardError);
                        EXIT.compareAndSet(0, 2);
                        return;
                    }
                    GAMES.incrementAndGet();
                    if (!p.end.get("void").isJsonNull()) {
                        VOIDS.incrementAndGet();
                        VOID_BY.computeIfAbsent(p.end.get("void").getAsString(), x -> new AtomicLong()).incrementAndGet();
                    }
                    try {
                        tape.write(p.tape);
                        final JsonObject e = p.end.deepCopy();
                        e.add("seed", g.get("seed"));
                        e.add("controllers", g.get("controllers"));
                        e.add("decks", g.get("decks"));
                        e.add("deck_sha", g.get("deck_sha"));
                        if (g.has("meta")) {
                            e.add("meta", g.get("meta"));
                        }
                        synchronized (RlActorBench.class) {
                            Files.write(endLog, (RlWire.canonicalString(e) + "\n").getBytes(StandardCharsets.UTF_8),
                                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                        }
                    } catch (IOException e) {
                        System.err.println("[rlactor] tape write failed: " + e);
                        EXIT.compareAndSet(0, 3);
                        return;
                    }
                }
            }, "rlrecord-w" + k);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        final int hung = watchdog(workers, cfg);
        tape.close();
        final JsonObject s = new JsonObject();
        s.addProperty("type", "summary");
        s.addProperty("mode", "record-local");
        s.addProperty("games", GAMES.get());
        s.addProperty("voids", VOIDS.get());
        final JsonObject vb = new JsonObject();
        for (Map.Entry<String, AtomicLong> e : VOID_BY.entrySet()) vb.addProperty(e.getKey(), e.getValue().get());
        s.add("void_by", vb);
        s.addProperty("hung", hung);
        s.addProperty("wall_s", (System.nanoTime() - w0) / 1e9);
        s.addProperty("exit", hung > 0 ? 3 : EXIT.get());
        System.err.println("[rlactor] " + s);
        return hung > 0 ? 3 : EXIT.get();
    }
}
