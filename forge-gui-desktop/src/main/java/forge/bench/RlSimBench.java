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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.GuiDesktop;
import forge.LobbyPlayer;
import forge.ai.AiCache;
import forge.ai.simulation.GameCopier;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.IdScope;
import forge.util.MyRandom;

/**
 * RL-simulator probe (lane rl-sim-forge-1005). Measures Forge as a self-play simulator: whole games in which BOTH seats
 * are driven by a trivial in-process policy through the bench bridge ({@link PlayerControllerBridge} answering via
 * {@link BenchSession.LocalAnswerer}), so the cost is the rules engine plus legal-option enumeration, not Forge AI.
 * Nothing else uses this class; it changes no existing behaviour.
 *
 * <p>Usage: {@code java -cp forge.jar forge.bench.RlSimBench <config.json>} (cwd = forge-gui with res/). Config keys:
 * <ul>
 * <li>{@code pairs}: [[deckA.dck, deckB.dck], ...] (game i plays pair i mod n); {@code games}, {@code seed}
 * (game i uses seed+i), {@code start} (first game index), {@code out} (jsonl, one row per game)</li>
 * <li>{@code policy}: {@code random} (uniform over each ask's legal answers), {@code first} (first non-pass priority
 * option, every legal attacker, no blocks, the first legal picks), {@code forge} (both seats Forge AI, counted)</li>
 * <li>{@code encodeState} (default true): build the seat-visible state JSON on every ask, as the wire does</li>
 * <li>{@code serialize} (default false): also serialize every ask to a JSON string and parse it back (wire cost
 * without IPC); {@code socket} "host:port": also send every ask to a local line server and wait for its reply</li>
 * <li>{@code threads} (default 1): games played concurrently in this JVM, each on its own thread with its own id
 * scope, AI-cache scope and random stream</li>
 * <li>{@code timeoutSec} (default 300) per game; {@code aiProfile} (Default)</li>
 * <li>{@code probe}: {@code off} | {@code cost} (time {@code probeReps} GameCopier copies at the first own-turn
 * empty-stack priority of each turn in {@code probeTurns}) | {@code fidelity} (cost, then at turn
 * probeTurns[i mod n]: play two copies to the end on fixed streams, then put the live game on the same streams and
 * compare the three continuations) | {@code leak} (cost plus one copy played to the end, the live game untouched: its
 * digest must equal the probe-off run's)</li>
 * <li>{@code memProbe} (default false): heap after a full GC at turn 6 and between games (threads = 1 only)</li>
 * </ul>
 */
public final class RlSimBench {

    private RlSimBench() {
    }

    // ------------------------------------------------------------------------------------------------- config

    static final class Cfg {
        List<String[]> pairs = new ArrayList<>();
        int games = 10;
        int start = 0;
        long seed = 1L;
        String policy = "random";
        boolean encodeState = true;
        boolean serialize = false;
        String socket = null;
        int threads = 1;
        int timeoutSec = 300;
        String aiProfile = "Default";
        String probe = "off";
        int[] probeTurns = {3, 6, 10};
        int probeReps = 5;
        int maxSteps = 200000;
        boolean memProbe = false;
        String out = null;
    }

    static Cfg parse(final JsonObject o) {
        final Cfg c = new Cfg();
        for (JsonElement e : o.getAsJsonArray("pairs")) {
            final JsonArray a = e.getAsJsonArray();
            c.pairs.add(new String[] {a.get(0).getAsString(), a.get(1).getAsString()});
        }
        if (o.has("games")) c.games = o.get("games").getAsInt();
        if (o.has("start")) c.start = o.get("start").getAsInt();
        if (o.has("seed")) c.seed = o.get("seed").getAsLong();
        if (o.has("policy")) c.policy = o.get("policy").getAsString();
        if (o.has("encodeState")) c.encodeState = o.get("encodeState").getAsBoolean();
        if (o.has("serialize")) c.serialize = o.get("serialize").getAsBoolean();
        if (o.has("socket") && !o.get("socket").isJsonNull()) c.socket = o.get("socket").getAsString();
        if (o.has("threads")) c.threads = Math.max(1, o.get("threads").getAsInt());
        if (o.has("timeoutSec")) c.timeoutSec = o.get("timeoutSec").getAsInt();
        if (o.has("aiProfile")) c.aiProfile = o.get("aiProfile").getAsString();
        if (o.has("probe")) c.probe = o.get("probe").getAsString();
        if (o.has("probeTurns")) {
            final JsonArray a = o.getAsJsonArray("probeTurns");
            c.probeTurns = new int[a.size()];
            for (int i = 0; i < a.size(); i++) c.probeTurns[i] = a.get(i).getAsInt();
        }
        if (o.has("probeReps")) c.probeReps = o.get("probeReps").getAsInt();
        if (o.has("maxSteps")) c.maxSteps = o.get("maxSteps").getAsInt();
        if (o.has("memProbe")) c.memProbe = o.get("memProbe").getAsBoolean();
        if (o.has("out")) c.out = o.get("out").getAsString();
        return c;
    }

    // ------------------------------------------------------------------------------------------------- digest

    /** Turn-level fingerprints of one game (id-free: names, sizes, life), plus the outcome at the end. */
    static final class Digest {
        final Game g;
        final List<String> fps = new ArrayList<>();

        Digest(final Game g) {
            this.g = g;
        }

        @Subscribe
        public void receive(final GameEventTurnBegan ev) {
            fps.add(fingerprint(g));
        }
    }

    static String fingerprint(final Game g) {
        final StringBuilder sb = new StringBuilder();
        final PhaseHandler ph = g.getPhaseHandler();
        sb.append('T').append(ph.getTurn()).append(' ').append(ph.getPhase()).append(' ')
                .append(g.getPlayers().indexOf(ph.getPlayerTurn()));
        for (Player p : g.getPlayers()) {
            sb.append(" |L").append(p.getLife()).append(" H").append(p.getZone(ZoneType.Hand).size())
                    .append(" Y").append(p.getZone(ZoneType.Library).size())
                    .append(" G").append(p.getZone(ZoneType.Graveyard).size())
                    .append(" X").append(p.getZone(ZoneType.Exile).size()).append(" B[");
            final List<String> bf = new ArrayList<>();
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                bf.add(c.getName() + (c.isTapped() ? "*" : "") + (c.isCreature() ? ":" + c.getNetPower() + "/" + c.getNetToughness() : ""));
            }
            Collections.sort(bf);
            sb.append(String.join(",", bf)).append(']');
        }
        return sb.toString();
    }

    static String outcomeText(final Game g) {
        final GameOutcome go = g.getOutcome();
        if (go == null) {
            return "none";
        }
        int w = -1;
        if (!go.isDraw()) {
            final LobbyPlayer wlp = go.getWinningLobbyPlayer();
            for (int i = 0; i < g.getPlayers().size(); i++) {
                if (g.getPlayers().get(i).getLobbyPlayer() == wlp) {
                    w = i;
                }
            }
        }
        return "W" + w + " " + go.getWinCondition() + " T" + go.getLastTurnNumber();
    }

    static String sha16(final List<String> parts) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String s : parts) {
                md.update(s.getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            final StringBuilder hex = new StringBuilder();
            final byte[] d = md.digest();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", d[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------------------------------------- policy

    /** Per-game counters of the in-process policy. */
    static final class GameStats {
        long asks, delegated, policyNanos, serNanos, ioNanos, askBytes, encodeLess;
        final Map<String, Integer> byKind = new TreeMap<>();
        long askHash = 1125899906842597L;   // rolling hash of (kind, menu size, answer), for continuation identity

        void note(final String kind, final int n, final JsonObject ans) {
            asks++;
            byKind.merge(kind, 1, Integer::sum);
            final String s = kind + "/" + n + "/" + (ans == null ? "-" : ans.toString());
            for (int i = 0; i < s.length(); i++) {
                askHash = 31 * askHash + s.charAt(i);
            }
        }
    }

    /**
     * The in-process external-policy stand-in. Each game (live or copy) draws from its own stream; a copy's stream is
     * set by the probe, so a copy and the live game can be put on the same stream.
     */
    static final class Policy implements BenchSession.LocalAnswerer {
        final String mode;
        final Cfg cfg;
        final Map<Game, Random> streams = Collections.synchronizedMap(new IdentityHashMap<>());
        final Map<Game, GameStats> stats = Collections.synchronizedMap(new IdentityHashMap<>());
        Probe probe;   // set per live game when probing
        private final ThreadLocal<Object[]> sock = new ThreadLocal<>();

        Policy(final String mode, final Cfg cfg) {
            this.mode = mode;
            this.cfg = cfg;
        }

        Random rng(final Game g) {
            return streams.computeIfAbsent(g, k -> new Random(0x5eed));
        }

        GameStats st(final Game g) {
            return stats.computeIfAbsent(g, k -> new GameStats());
        }

        @Override
        public JsonObject answer(final Game game, final Player player, final String kind, final JsonObject body) {
            if (probe != null && probe.live == game && "priority".equals(kind)) {
                probe.atPriority(game, player);
            }
            final GameStats s = st(game);
            if (cfg.serialize || cfg.socket != null) {
                final long a = System.nanoTime();
                final String line = kind + "\t" + body.toString();
                s.askBytes += line.length();
                if (cfg.serialize) {
                    JsonParser.parseString(line.substring(kind.length() + 1));
                }
                final long b = System.nanoTime();
                s.serNanos += b - a;
                if (cfg.socket != null) {
                    roundTrip(line);
                    s.ioNanos += System.nanoTime() - b;
                }
            }
            final long t = System.nanoTime();
            final JsonObject ans = "first".equals(mode) ? first(kind, body) : random(rng(game), kind, body);
            s.policyNanos += System.nanoTime() - t;
            s.note(kind, menuSize(body), ans);
            if (ans == null) {
                s.delegated++;
            }
            return ans;
        }

        private void roundTrip(final String line) {
            try {
                Object[] o = sock.get();
                if (o == null) {
                    final String[] hp = cfg.socket.split(":");
                    final Socket so = new Socket(hp[0], Integer.parseInt(hp[1]));
                    so.setTcpNoDelay(true);
                    o = new Object[] {so, new PrintStream(so.getOutputStream(), false, StandardCharsets.UTF_8),
                            new BufferedReader(new InputStreamReader(so.getInputStream(), StandardCharsets.UTF_8))};
                    sock.set(o);
                }
                final PrintStream ps = (PrintStream) o[1];
                ps.print(line);
                ps.print('\n');
                ps.flush();
                ((BufferedReader) o[2]).readLine();
            } catch (Exception e) {
                throw new IllegalStateException("socket round trip failed", e);
            }
        }

        static int menuSize(final JsonObject b) {
            if (b.has("menu") && b.get("menu").isJsonArray()) return b.getAsJsonArray("menu").size();
            if (b.has("legalAttackers")) return b.getAsJsonArray("legalAttackers").size();
            if (b.has("legalBlockers")) return b.getAsJsonArray("legalBlockers").size();
            return -1;
        }

        static JsonArray menu(final JsonObject b) {
            return b.has("menu") && b.get("menu").isJsonArray() ? b.getAsJsonArray("menu") : new JsonArray();
        }

        static int gi(final JsonObject b, final String k, final int dflt) {
            return b.has(k) && !b.get(k).isJsonNull() ? b.get(k).getAsInt() : dflt;
        }

        static JsonObject delegate() {
            final JsonObject o = new JsonObject();
            o.addProperty("delegate", true);
            return o;
        }

        /** k distinct indices of [0, n), uniformly. */
        static List<Integer> pick(final Random r, final int n, final int k) {
            final List<Integer> all = new ArrayList<>();
            for (int i = 0; i < n; i++) all.add(i);
            Collections.shuffle(all, r);
            return new ArrayList<>(all.subList(0, Math.max(0, Math.min(k, n))));
        }

        static int count(final Random r, final int min, final int max, final int n) {
            final int hi = Math.min(max < 0 ? n : max, n);
            final int lo = Math.min(Math.max(0, min), hi);
            return lo + r.nextInt(hi - lo + 1);
        }

        static JsonArray ints(final List<Integer> l) {
            final JsonArray a = new JsonArray();
            for (int i : l) a.add(i);
            return a;
        }

        static JsonArray fids(final JsonArray menu, final List<Integer> idx, final String key) {
            final JsonArray a = new JsonArray();
            for (int i : idx) a.add(menu.get(i).getAsJsonObject().get(key).getAsInt());
            return a;
        }

        static JsonObject random(final Random r, final String kind, final JsonObject b) {
            final JsonObject o = new JsonObject();
            final JsonArray m = menu(b);
            switch (kind) {
                case "priority": {
                    final int c = r.nextInt(m.size());
                    o.addProperty("choice", c);
                    final JsonObject it = m.get(c).getAsJsonObject();
                    if (c > 0 && it.has("x") && it.getAsJsonObject("x").has("has")
                            && it.getAsJsonObject("x").get("has").getAsBoolean()) {
                        final int hi = Math.max(0, gi(it.getAsJsonObject("x"), "maxAnnounce", 0));
                        o.addProperty("x", r.nextInt(Math.min(hi, 20) + 1));
                    }
                    return o;
                }
                case "attackers": {
                    final JsonArray pairs = new JsonArray();
                    final JsonObject lp = b.getAsJsonObject("legalPairsTyped");
                    for (Map.Entry<String, JsonElement> e : lp.entrySet()) {
                        final JsonArray defs = e.getValue().getAsJsonArray();
                        if (defs.size() > 0 && r.nextBoolean()) {
                            final JsonArray pr = new JsonArray();
                            pr.add(Integer.parseInt(e.getKey()));
                            pr.add(defs.get(r.nextInt(defs.size())));
                            pairs.add(pr);
                        }
                    }
                    o.add("pairs", pairs);
                    return o;
                }
                case "blockers": {
                    final JsonArray pairs = new JsonArray();
                    final JsonObject lp = b.getAsJsonObject("legalPairs");
                    for (Map.Entry<String, JsonElement> e : lp.entrySet()) {
                        final JsonArray atk = e.getValue().getAsJsonArray();
                        if (atk.size() > 0 && r.nextBoolean()) {
                            final JsonArray pr = new JsonArray();
                            pr.add(Integer.parseInt(e.getKey()));
                            pr.add(atk.get(r.nextInt(atk.size())).getAsInt());
                            pairs.add(pr);
                        }
                    }
                    o.add("pairs", pairs);
                    return o;
                }
                case "mulligan":
                    o.addProperty("keep", r.nextBoolean());
                    return o;
                case "startingPlayer":
                    o.addProperty("play", r.nextBoolean());
                    return o;
                case "cardsChoice":
                case "zoneChange": {
                    final List<Integer> idx = pick(r, m.size(), count(r, gi(b, "min", 0), gi(b, "max", m.size()), m.size()));
                    o.add("choices", fids(m, idx, "fid"));
                    return o;
                }
                case "targets": {
                    final int min = gi(b, "min", 0);
                    if (m.size() < min || m.size() == 0) {
                        return delegate();
                    }
                    final List<Integer> idx = pick(r, m.size(), count(r, min, gi(b, "max", 1), m.size()));
                    final JsonArray ch = new JsonArray();
                    for (int i : idx) {
                        final JsonObject e = m.get(i).getAsJsonObject();
                        final JsonObject ref = new JsonObject();
                        ref.addProperty("kind", e.has("kind") ? e.get("kind").getAsString() : "card");
                        ref.addProperty("id", e.get("id").getAsInt());
                        ch.add(ref);
                    }
                    o.add("choices", ch);
                    return o;
                }
                case "entityChoice": {
                    if (b.has("min")) {
                        o.add("choices", ints(pick(r, m.size(), count(r, gi(b, "min", 0), gi(b, "max", m.size()), m.size()))));
                        return o;
                    }
                    final boolean opt = b.has("optional") && b.get("optional").getAsBoolean();
                    final int c = r.nextInt(m.size() + (opt ? 1 : 0));
                    if (c == m.size()) {
                        o.addProperty("none", true);
                    } else {
                        o.addProperty("choice", c);
                    }
                    return o;
                }
                case "number":
                case "keywordCost": {
                    if (b.has("values")) {
                        final JsonArray v = b.getAsJsonArray("values");
                        o.addProperty("value", v.get(r.nextInt(v.size())).getAsInt());
                    } else {
                        final int lo = gi(b, "min", 0);
                        final int hi = Math.min(gi(b, "max", lo), lo + 20);
                        o.addProperty("value", lo + r.nextInt(Math.max(0, hi - lo) + 1));
                    }
                    return o;
                }
                case "mode":
                    o.add("choices", ints(pick(r, m.size(), count(r, gi(b, "min", 1), gi(b, "num", 1), m.size()))));
                    return o;
                case "optionalCosts": {
                    final List<Integer> idx = new ArrayList<>();
                    for (int i = 0; i < m.size(); i++) if (r.nextBoolean()) idx.add(i);
                    o.add("choices", ints(idx));
                    return o;
                }
                case "confirm":
                    o.addProperty("yes", r.nextBoolean());
                    return o;
                case "scry": {
                    final JsonArray top = new JsonArray(), bottom = new JsonArray();
                    final List<Integer> perm = pick(r, m.size(), m.size());
                    for (int i : perm) {
                        (r.nextBoolean() ? top : bottom).add(m.get(i).getAsJsonObject().get("fid").getAsInt());
                    }
                    o.add("top", top);
                    o.add("bottom", bottom);
                    return o;
                }
                case "orderBlockers":
                    o.add("order", fids(m, pick(r, m.size(), m.size()), "fid"));
                    return o;
                case "orderZone":
                    o.add("choices", fids(m, pick(r, m.size(), m.size()), "fid"));
                    return o;
                default:   // assignDamage and anything new: Forge decides (counted as delegated)
                    return delegate();
            }
        }

        static JsonObject first(final String kind, final JsonObject b) {
            final JsonObject o = new JsonObject();
            final JsonArray m = menu(b);
            final List<Integer> lowest = new ArrayList<>();
            switch (kind) {
                case "priority":
                    o.addProperty("choice", m.size() > 1 ? 1 : 0);
                    return o;
                case "attackers": {
                    final JsonArray pairs = new JsonArray();
                    for (Map.Entry<String, JsonElement> e : b.getAsJsonObject("legalPairsTyped").entrySet()) {
                        final JsonArray defs = e.getValue().getAsJsonArray();
                        if (defs.size() > 0) {
                            final JsonArray pr = new JsonArray();
                            pr.add(Integer.parseInt(e.getKey()));
                            pr.add(defs.get(0));
                            pairs.add(pr);
                        }
                    }
                    o.add("pairs", pairs);
                    return o;
                }
                case "blockers":
                    o.add("pairs", new JsonArray());
                    return o;
                case "mulligan":
                    o.addProperty("keep", true);
                    return o;
                case "startingPlayer":
                    o.addProperty("play", true);
                    return o;
                case "cardsChoice":
                case "zoneChange": {
                    final int k = Math.min(m.size(), Math.max(gi(b, "min", 0), Math.min(1, gi(b, "max", 1))));
                    for (int i = 0; i < k; i++) lowest.add(i);
                    o.add("choices", fids(m, lowest, "fid"));
                    return o;
                }
                case "targets": {
                    final int min = gi(b, "min", 0);
                    final int k = Math.min(m.size(), Math.max(min, Math.min(1, gi(b, "max", 1))));
                    if (m.size() < min || k == 0) {
                        return delegate();
                    }
                    final JsonArray ch = new JsonArray();
                    for (int i = 0; i < k; i++) {
                        final JsonObject e = m.get(i).getAsJsonObject();
                        final JsonObject ref = new JsonObject();
                        ref.addProperty("kind", e.has("kind") ? e.get("kind").getAsString() : "card");
                        ref.addProperty("id", e.get("id").getAsInt());
                        ch.add(ref);
                    }
                    o.add("choices", ch);
                    return o;
                }
                case "entityChoice":
                    if (b.has("min")) {
                        final int k = Math.min(m.size(), Math.max(gi(b, "min", 0), Math.min(1, gi(b, "max", 1))));
                        for (int i = 0; i < k; i++) lowest.add(i);
                        o.add("choices", ints(lowest));
                    } else {
                        o.addProperty("choice", 0);
                    }
                    return o;
                case "number":
                case "keywordCost":
                    if (b.has("values")) {
                        o.addProperty("value", b.getAsJsonArray("values").get(0).getAsInt());
                    } else {
                        o.addProperty("value", gi(b, "min", 0));
                    }
                    return o;
                case "mode": {
                    final int k = Math.min(m.size(), Math.max(1, gi(b, "min", 1)));
                    for (int i = 0; i < k; i++) lowest.add(i);
                    o.add("choices", ints(lowest));
                    return o;
                }
                case "optionalCosts":
                    o.add("choices", new JsonArray());
                    return o;
                case "confirm":
                    o.addProperty("yes", true);
                    return o;
                case "scry":
                    for (int i = 0; i < m.size(); i++) lowest.add(i);
                    o.add("top", fids(m, lowest, "fid"));
                    o.add("bottom", new JsonArray());
                    return o;
                case "orderBlockers":
                    for (int i = 0; i < m.size(); i++) lowest.add(i);
                    o.add("order", fids(m, lowest, "fid"));
                    return o;
                case "orderZone":
                    for (int i = 0; i < m.size(); i++) lowest.add(i);
                    o.add("choices", fids(m, lowest, "fid"));
                    return o;
                default:
                    return delegate();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- probe

    private static Field fPrio, fFirst, fGive;

    private static synchronized void initFields() {
        if (fPrio != null) {
            return;
        }
        try {
            fPrio = PhaseHandler.class.getDeclaredField("pPlayerPriority");
            fFirst = PhaseHandler.class.getDeclaredField("pFirstPriority");
            fGive = PhaseHandler.class.getDeclaredField("givePriorityToPlayer");
            fPrio.setAccessible(true);
            fFirst.setAccessible(true);
            fGive.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One live game's GameCopier probe (cost at each probe turn; continuation identity or live-leak at one turn). */
    static final class Probe {
        final Game live;
        final Cfg cfg;
        final Policy pol;
        final BenchSession session;
        final Digest liveDigest;
        final long gseed;
        final int fidelityTurn;
        final Set<Integer> done = new HashSet<>();
        final JsonArray costs = new JsonArray();
        JsonObject fork = null;   // the continuation experiment
        int liveForkIdx = -1;
        long liveAskHashAtFork;
        boolean busy = false;

        Probe(final Game live, final Cfg cfg, final Policy pol, final BenchSession session, final Digest d,
                final long gseed, final int gameIndex) {
            this.live = live;
            this.cfg = cfg;
            this.pol = pol;
            this.session = session;
            this.liveDigest = d;
            this.gseed = gseed;
            this.fidelityTurn = cfg.probeTurns[Math.floorMod(gameIndex, cfg.probeTurns.length)];
        }

        void atPriority(final Game g, final Player p) {
            if (busy) {
                return;
            }
            final PhaseHandler ph = g.getPhaseHandler();
            final int turn = ph.getTurn();
            if (done.contains(turn) || ph.getPlayerTurn() != p || !g.getStack().isEmpty()) {
                return;
            }
            boolean wanted = false;
            for (int t : cfg.probeTurns) wanted |= t == turn;
            if (!wanted) {
                return;
            }
            done.add(turn);
            busy = true;
            try {
                cost(g, turn);
                if (turn == fidelityTurn && ("fidelity".equals(cfg.probe) || "leak".equals(cfg.probe))) {
                    continuation(g, p, turn);
                }
            } finally {
                busy = false;
            }
        }

        private void cost(final Game g, final int turn) {
            final JsonObject o = new JsonObject();
            o.addProperty("turn", turn);
            o.addProperty("cards", g.getCardsInGame().size());
            int bf = 0;
            for (Player pl : g.getPlayers()) bf += pl.getZone(ZoneType.Battlefield).size();
            o.addProperty("battlefield", bf);
            final JsonArray ms = new JsonArray();
            int fpSame = 0;
            final String liveFp = fingerprint(g);
            for (int r = 0; r < cfg.probeReps; r++) {
                final long a = System.nanoTime();
                final Game c = inScopes(gseed * 31 + r, () -> new GameCopier(g).makeCopy());
                ms.add((System.nanoTime() - a) / 1e6);
                if (c != null && liveFp.equals(fingerprint(c))) {
                    fpSame++;
                }
            }
            o.add("copyMs", ms);
            o.addProperty("fingerprintSame", fpSame);
            costs.add(o);
        }

        private <T> T inScopes(final long seed, final java.util.function.Supplier<T> body) {
            final Random prev = MyRandom.getThreadRandom();
            final Object prevIds = IdScope.capture();
            final Object prevCache = AiCache.captureScope();
            MyRandom.setThreadRandom(new Random(seed));
            AiCache.openScope();
            IdScope.open();
            try {
                return body.get();
            } catch (RuntimeException | StackOverflowError e) {
                System.err.println("[rlsim] copy failed: " + e);
                return null;
            } finally {
                AiCache.installScope(prevCache);
                IdScope.install(prevIds);
                MyRandom.setThreadRandom(prev);
            }
        }

        /** Play one copy of the live game (at the live seat's pending priority) to the end on streams (x, y). */
        private JsonObject playCopy(final Game g, final Player p, final long x, final long y) {
            final JsonObject o = new JsonObject();
            final Random prev = MyRandom.getThreadRandom();
            final Object prevIds = IdScope.capture();
            final Object prevCache = AiCache.captureScope();
            MyRandom.setThreadRandom(new Random(x));
            AiCache.openScope();
            IdScope.open();
            final Game liveWas = session.getLiveGame();
            Game c = null;
            try {
                final long a = System.nanoTime();
                final GameCopier gc = new GameCopier(g);
                c = gc.makeCopy();
                final Player cp = (Player) gc.find(p);
                final PhaseHandler lph = g.getPhaseHandler();
                final PhaseHandler cph = c.getPhaseHandler();
                initFields();
                final Player liveFirst = (Player) fFirst.get(lph);
                fPrio.set(cph, cp);
                fFirst.set(cph, liveFirst == null ? cp : (Player) gc.find(liveFirst));
                fGive.setBoolean(cph, true);
                o.addProperty("copyMs", (System.nanoTime() - a) / 1e6);
                final Digest d = new Digest(c);
                c.subscribeToEvents(d);
                pol.streams.put(c, new Random(y));
                session.setLiveGame(c);
                MyRandom.setThreadRandom(new Random(x));
                final long b = System.nanoTime();
                int steps = 0;
                while (!c.isGameOver() && steps < cfg.maxSteps) {
                    cph.mainLoopStep();
                    steps++;
                }
                o.addProperty("playMs", (System.nanoTime() - b) / 1e6);
                o.addProperty("steps", steps);
                final List<String> fps = new ArrayList<>(d.fps);
                fps.add(outcomeText(c));
                o.addProperty("digest", sha16(fps));
                o.addProperty("outcome", outcomeText(c));
                o.addProperty("turns", d.fps.size());
                final GameStats s = pol.stats.get(c);
                o.addProperty("asks", s == null ? 0 : s.asks);
                o.addProperty("askHash", s == null ? 0 : s.askHash);
                o.add("fps", toArray(fps));
            } catch (Throwable e) {
                o.addProperty("error", e.toString());
            } finally {
                session.setLiveGame(liveWas);
                if (c != null) {
                    pol.streams.remove(c);
                    pol.stats.remove(c);
                }
                AiCache.installScope(prevCache);
                IdScope.install(prevIds);
                MyRandom.setThreadRandom(prev);
            }
            return o;
        }

        private void continuation(final Game g, final Player p, final int turn) {
            final long x = gseed * 1000003L + 17, y = gseed * 1000033L + 29;
            fork = new JsonObject();
            fork.addProperty("turn", turn);
            fork.add("c1", playCopy(g, p, x, y));
            if ("fidelity".equals(cfg.probe)) {
                fork.add("c2", playCopy(g, p, x, y));
                // the live game continues on the same streams from here
                MyRandom.setThreadRandom(new Random(x));
                pol.streams.put(g, new Random(y));
                liveForkIdx = liveDigest.fps.size();
                liveAskHashAtFork = 0;
                final GameStats s = pol.st(g);
                s.askHash = 1125899906842597L;
                fork.addProperty("liveAsksAtFork", s.asks);
            }
        }

        JsonObject finish(final Game g) {
            final JsonObject o = new JsonObject();
            o.add("costs", costs);
            if (fork != null) {
                if (liveForkIdx >= 0) {
                    final List<String> fps = new ArrayList<>(liveDigest.fps.subList(liveForkIdx, liveDigest.fps.size()));
                    fps.add(outcomeText(g));
                    final GameStats s = pol.st(g);
                    fork.addProperty("liveDigest", sha16(fps));
                    fork.addProperty("liveAskHash", s.askHash);
                    fork.addProperty("liveAsks", s.asks - fork.get("liveAsksAtFork").getAsLong());
                    fork.addProperty("liveOutcome", outcomeText(g));
                    final JsonObject c1 = fork.getAsJsonObject("c1");
                    final JsonObject c2 = fork.getAsJsonObject("c2");
                    fork.addProperty("c1EqC2", c1.has("digest") && c2.has("digest")
                            && c1.get("digest").equals(c2.get("digest")) && c1.get("askHash").equals(c2.get("askHash")));
                    fork.addProperty("c1EqLive", c1.has("digest") && c1.get("digest").getAsString().equals(sha16(fps))
                            && c1.get("askHash").getAsLong() == s.askHash);
                    fork.addProperty("c1EqLiveTraj", c1.has("digest") && c1.get("digest").getAsString().equals(sha16(fps)));
                    // first divergent turn fingerprint (diagnostics)
                    if (c1.has("fps")) {
                        final JsonArray cf = c1.getAsJsonArray("fps");
                        int k = 0;
                        while (k < cf.size() && k < fps.size() && cf.get(k).getAsString().equals(fps.get(k))) k++;
                        fork.addProperty("firstDiff", k);
                        if (k < fps.size() && k < cf.size()) {
                            fork.addProperty("diffLive", fps.get(k));
                            fork.addProperty("diffCopy", cf.get(k).getAsString());
                        }
                    }
                }
                for (String k : new String[] {"c1", "c2"}) {
                    if (fork.has(k)) fork.getAsJsonObject(k).remove("fps");
                }
                o.add("fork", fork);
            }
            return o;
        }
    }

    static JsonArray toArray(final List<String> l) {
        final JsonArray a = new JsonArray();
        for (String s : l) a.add(s);
        return a;
    }

    // ------------------------------------------------------------------------------------------------- runner

    static final ThreadMXBean TMX = ManagementFactory.getThreadMXBean();
    static final Map<String, Deck> DECKS = new ConcurrentHashMap<>();
    static final Map<Thread, Object[]> RUNNING = new ConcurrentHashMap<>();   // thread -> {game, deadlineMs, index}

    static Deck deck(final String path) {
        return DECKS.computeIfAbsent(path, p -> {
            final Deck d = DeckSerializer.fromFile(new File(p));
            if (d == null) {
                throw new IllegalArgumentException("could not parse deck " + p);
            }
            return d;
        });
    }

    static long heapAfterGc(final MemoryMXBean mem) {
        System.gc();
        System.gc();
        return mem.getHeapMemoryUsage().getUsed();
    }

    static JsonObject playOne(final Cfg cfg, final int i, final MemoryMXBean mem) {
        final long gseed = cfg.seed + i;
        final String[] pair = cfg.pairs.get(Math.floorMod(i, cfg.pairs.size()));
        final JsonObject row = new JsonObject();
        row.addProperty("i", i);
        row.addProperty("seed", gseed);
        row.addProperty("a", new File(pair[0]).getName());
        row.addProperty("b", new File(pair[1]).getName());
        row.addProperty("policy", cfg.policy);
        row.addProperty("thread", Thread.currentThread().getName());

        final JsonRpcChannel ch = new JsonRpcChannel(InputStream.nullInputStream(), OutputStream.nullOutputStream());
        final BenchSession session = new BenchSession(ch);
        final boolean forge = "forge".equals(cfg.policy);
        final Policy pol = new Policy(cfg.policy, cfg);
        if (!forge) {
            session.setLocalAnswerer(pol);
        }
        session.setEncodeState(cfg.encodeState);
        final List<RegisteredPlayer> seats = new ArrayList<>();
        final List<LobbyPlayerBridge> lps = new ArrayList<>();
        for (int s = 0; s < 2; s++) {
            final LobbyPlayerBridge lp = new LobbyPlayerBridge("Seat" + s, null, session,
                    forge ? BenchSession.Mode.NULL : BenchSession.Mode.BRIDGE, s);
            lp.setAiProfile(cfg.aiProfile);
            lps.add(lp);
            final RegisteredPlayer rp = new RegisteredPlayer(deck(pair[s]));
            rp.setPlayer(lp);
            seats.add(rp);
        }
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
        rules.setSimTimeout(cfg.timeoutSec);
        final Match match = new Match(rules, seats, "rl-sim");

        MyRandom.setThreadRandom(new Random(gseed));
        IdScope.open();
        AiCache.openScope();
        final long tid = Thread.currentThread().getId();
        final com.sun.management.ThreadMXBean stmx = (com.sun.management.ThreadMXBean) TMX;
        final long alloc0 = stmx.getThreadAllocatedBytes(tid);
        final long cpu0 = TMX.getCurrentThreadCpuTime();
        final long t0 = System.nanoTime();
        Game game = null;
        String abort = null;
        Probe probe = null;
        Digest dg = null;
        long heap6 = -1;
        try {
            game = match.createGame();
            game.AI_TIMEOUT = 5;
            session.setLiveGame(game);
            pol.streams.put(game, new Random(gseed ^ 0x9e3779b97f4a7c15L));
            dg = new Digest(game);
            game.subscribeToEvents(dg);
            if (!forge && !"off".equals(cfg.probe)) {
                probe = new Probe(game, cfg, pol, session, dg, gseed, i);
                pol.probe = probe;
            }
            if (cfg.memProbe && cfg.threads == 1) {
                final Game gg = game;
                final long[] h = {-1};
                game.subscribeToEvents(new Object() {
                    @Subscribe
                    public void receive(final GameEventTurnBegan ev) {
                        if (ev.turnNumber() == 6 && h[0] < 0) {
                            h[0] = heapAfterGc(mem);
                            row.addProperty("heapAtTurn6", h[0]);
                            row.addProperty("cardsAtTurn6", gg.getCardsInGame().size());
                        }
                    }
                });
            }
            RUNNING.put(Thread.currentThread(), new Object[] {game, System.currentTimeMillis() + cfg.timeoutSec * 1000L, i});
            match.startGame(game, null);
        } catch (Throwable e) {
            abort = "crash";
            row.addProperty("error", e.toString());
            final StackTraceElement[] st = e.getStackTrace();
            final JsonArray top = new JsonArray();
            for (int k = 0; k < Math.min(6, st.length); k++) top.add(st[k].toString());
            row.add("stack", top);
        } finally {
            RUNNING.remove(Thread.currentThread());
            if (game != null && !game.isGameOver()) {
                game.setGameOver(GameEndReason.Draw);
            }
        }
        final long wallNs = System.nanoTime() - t0;
        final long cpuNs = TMX.getCurrentThreadCpuTime() - cpu0;
        final long alloc = stmx.getThreadAllocatedBytes(tid) - alloc0;
        if (TIMED_OUT.remove(i) != null && abort == null) {
            abort = "timeout";
        }
        row.addProperty("wallMs", wallNs / 1e6);
        row.addProperty("cpuMs", cpuNs / 1e6);
        row.addProperty("allocMB", alloc / 1048576.0);
        if (abort != null) {
            row.addProperty("abort", abort);
        }
        if (game != null) {
            row.addProperty("outcome", outcomeText(game));
            row.addProperty("turns", game.getPhaseHandler().getTurn());
            final List<String> fps = new ArrayList<>(dg == null ? Collections.emptyList() : dg.fps);
            fps.add(outcomeText(game));
            row.addProperty("digest", sha16(fps));
            final GameStats s = pol.stats.get(game);
            if (s != null) {
                row.addProperty("asks", s.asks);
                row.addProperty("delegatedAsks", s.delegated);
                row.addProperty("policyMs", s.policyNanos / 1e6);
                if (cfg.serialize || cfg.socket != null) {
                    row.addProperty("serMs", s.serNanos / 1e6);
                    row.addProperty("ioMs", s.ioNanos / 1e6);
                    row.addProperty("askKB", s.askBytes / 1024.0);
                }
                final JsonObject bk = new JsonObject();
                for (Map.Entry<String, Integer> e : s.byKind.entrySet()) bk.addProperty(e.getKey(), e.getValue());
                row.add("asksByKind", bk);
                row.addProperty("askHash", s.askHash);
            }
            if (probe != null) {
                row.add("probe", probe.finish(game));
            }
            // controller calls (all entry points, both seats; includes copies' asks when probing)
            int calls = 0, refused = 0, delegated = 0;
            for (LobbyPlayerBridge lp : lps) {
                final JsonObject cj = lp.getCounters().toJson();
                calls += sumOf(cj, "calls");
                refused += sumOf(cj, "delegatedRefused");
                delegated += sumOf(cj, "delegatedRequested");
            }
            row.addProperty("controllerCalls", calls);
            row.addProperty("refused", refused);
            row.addProperty("delegatedCalls", delegated);
        }
        AiCache.closeScope();
        IdScope.close();
        MyRandom.setThreadRandom(null);
        if (cfg.memProbe && cfg.threads == 1) {
            row.addProperty("heapBetween", heapAfterGc(mem));
        }
        return row;
    }

    static int sumOf(final JsonObject cj, final String key) {
        if (!cj.has(key) || !cj.get(key).isJsonObject()) {
            return 0;
        }
        int n = 0;
        for (Map.Entry<String, JsonElement> e : cj.getAsJsonObject(key).entrySet()) {
            try {
                n += e.getValue().getAsInt();
            } catch (RuntimeException ex) {
                // not a count
            }
        }
        return n;
    }

    static final Map<Integer, Boolean> TIMED_OUT = new ConcurrentHashMap<>();

    public static void main(final String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        final Cfg cfg = parse(JsonParser.parseString(new String(Files.readAllBytes(Paths.get(args[0])),
                StandardCharsets.UTF_8)).getAsJsonObject());
        final long boot0 = System.nanoTime();
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        MyRandom.setRandom(new Random(cfg.seed));
        final double bootMs = (System.nanoTime() - boot0) / 1e6;
        final MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        final com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        final long pcpu0 = os.getProcessCpuTime();
        final long heapBoot = heapAfterGc(mem);
        final PrintWriter out = new PrintWriter(new FileWriter(cfg.out, StandardCharsets.UTF_8, true), true);
        final AtomicInteger next = new AtomicInteger(cfg.start);
        final int end = cfg.start + cfg.games;
        final AtomicInteger done = new AtomicInteger();
        final long w0 = System.nanoTime();
        final List<Thread> workers = new ArrayList<>();
        for (int k = 0; k < cfg.threads; k++) {
            final Thread t = new Thread(() -> {
                int i;
                while ((i = next.getAndIncrement()) < end) {
                    final JsonObject row = playOne(cfg, i, mem);
                    synchronized (out) {
                        out.println(row.toString());
                    }
                    done.incrementAndGet();
                }
            }, "rlsim-w" + k);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        // watchdog: a game past its deadline is ended at its next main-loop step; one that never yields stops the JVM
        final Map<Thread, Long> stuckSince = new HashMap<>();
        while (true) {
            boolean alive = false;
            for (Thread t : workers) alive |= t.isAlive();
            if (!alive) {
                break;
            }
            Thread.sleep(500);
            final long now = System.currentTimeMillis();
            for (Map.Entry<Thread, Object[]> e : RUNNING.entrySet()) {
                final Object[] r = e.getValue();
                if (now > (Long) r[1]) {
                    TIMED_OUT.put((Integer) r[2], Boolean.TRUE);
                    ((Game) r[0]).setGameOver(GameEndReason.Draw);
                    final long since = stuckSince.computeIfAbsent(e.getKey(), x -> now);
                    if (now - since > 60_000L) {
                        System.err.println("[rlsim] game " + r[2] + " ignored its timeout for 60 s; stopping the JVM");
                        final JsonObject row = new JsonObject();
                        row.addProperty("i", (Integer) r[2]);
                        row.addProperty("abort", "stuck");
                        synchronized (out) {
                            out.println(row.toString());
                        }
                        summary(cfg, done.get(), w0, pcpu0, os, mem, heapBoot, bootMs);
                        System.exit(3);
                    }
                }
            }
        }
        summary(cfg, done.get(), w0, pcpu0, os, mem, heapBoot, bootMs);
        System.exit(0);
    }

    static void summary(final Cfg cfg, final int games, final long w0, final long pcpu0,
            final com.sun.management.OperatingSystemMXBean os, final MemoryMXBean mem, final long heapBoot,
            final double bootMs) {
        final JsonObject s = new JsonObject();
        s.addProperty("type", "summary");
        s.addProperty("games", games);
        s.addProperty("threads", cfg.threads);
        s.addProperty("policy", cfg.policy);
        s.addProperty("bootMs", bootMs);
        s.addProperty("wallS", (System.nanoTime() - w0) / 1e9);
        s.addProperty("processCpuS", (os.getProcessCpuTime() - pcpu0) / 1e9);
        s.addProperty("heapBootMB", heapBoot / 1048576.0);
        s.addProperty("heapEndMB", heapAfterGc(mem) / 1048576.0);
        s.addProperty("heapMaxMB", mem.getHeapMemoryUsage().getMax() / 1048576.0);
        System.err.println("[rlsim] " + s);
        try (PrintWriter w = new PrintWriter(new FileWriter(cfg.out + ".summary", StandardCharsets.UTF_8, true))) {
            w.println(s.toString());
        } catch (Exception e) {
            // the stderr line has it
        }
    }
}
