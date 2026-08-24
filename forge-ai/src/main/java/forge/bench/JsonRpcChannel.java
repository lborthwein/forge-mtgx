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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Blocking newline-delimited JSON transport for the mtgx benchmark bridge
 * (wire protocol v1).
 *
 * <p>stdout carries protocol lines <em>only</em>; every log/diagnostic goes to stderr.
 * The channel is single-threaded by construction: Forge runs a game on one thread and
 * every controller callback blocks on {@link #ask}.
 */
public final class JsonRpcChannel {
    /**
     * v2 (2026-08-19) is strictly additive over v1: card keywords, per-attacker
     * {@code minBlockers}, a seat-private {@code decklist} message, structured
     * cost/X/mode data on an ability, and structured stack targets. A v1 host reading a v2
     * stream sees only fields it does not know about. See PROTOCOL-NOTES.md.
     */
    public static final int PROTOCOL_VERSION = 2;

    /**
     * Minor level within protocol 2, additive only. Bumped when a new field appears that a
     * host may want to gate on.
     * <ul>
     *   <li>2.0 — keywords, minBlockers, decklist, structured cost/X/modes, stack targets.</li>
     *   <li>2.1 — {@code targets} asks can carry stack (spell) candidates, in their own id
     *       namespace. See PROTOCOL-NOTES.md "B-1".</li>
     *   <li>2.2 — {@code attackers} asks state attack requirements outright
     *       ({@code mustAttack}, {@code mustAttackAny}, {@code requiresAlso},
     *       {@code bestAttackViolations}).</li>
     *   <li>2.10 — {@code frameFile} in the config installs a mid-game position
     *       ({@code forge.game.GameState}) through {@code Match.startGame(game, hook)};
     *       the JVM answers with a {@code frameApplied} message carrying Forge's own
     *       {@code initFromGame} dump of what it actually installed. Additive: a config
     *       without {@code frameFile} behaves exactly as 2.9.</li>
     *   <li>2.11 — {@code outcome.aborted} names the abort ({@code "timeout"} /
     *       {@code "InstrumentError"}) on a game that did not finish, and {@code reason}
     *       keeps it instead of being overwritten by Forge's win condition. Forge's own
     *       verdict for such a game is preserved beside it as {@code winCondition}.
     *       A game that finished carries neither field, exactly as 2.10.</li>
     *   <li>2.12 — the position format ({@code forge.game.GameState}) carries three
     *       designations it previously dropped on the floor: {@code monarch=p<n>} and
     *       {@code initiative=p<n>} (CR 724 / CR 725), a dungeon in the command zone
     *       with its mid-dungeon position ({@code T:undercity|CurrentRoom:Arena},
     *       CR 309.4), and {@code p<n>completeddungeons=} (CR 309.3). All four keys
     *       are optional and their absence means "nobody holds it" / "no dungeon" /
     *       "none completed", so a 2.11 frame file installs identically. Purely a
     *       {@code frameFile} widening — no wire message changed.</li>
     *   <li>2.13 — the simulation search gained a WIDTH bound to go with its depth bound.
     *       Config accepts {@code simMaxSimulations} (a deterministic node budget per
     *       top-level decision, 0 = unbounded = Forge's own behaviour) and
     *       {@code simTraceMs} (a diagnostic progress trace); {@code hello} and
     *       {@code seatInfo} echo them. Without it, one Doomsday-castable frame is
     *       ~31^5 whole-game copies inside a single depth level and the JVM never
     *       returns. Both keys are optional and default to off, so a 2.12 config
     *       behaves identically. See {@code forge.ai.simulation.SimSearchBudget}.</li>
     *   <li>2.14 — every {@code state.stack[]} entry says WHAT IT IS: {@code kind}
     *       ({@code "spell" | "activated" | "triggered" | "replacement" | "other"}) and
     *       the {@code isAbility} boolean derived from it. Nothing on the wire carried
     *       this before: {@code fid} is the SOURCE card's id and an ability has one too,
     *       so a host deriving "ability" from a missing {@code fid} read {@code false}
     *       for every entry ever sent, and {@code description} cannot stand in for it
     *       (Firebolt-the-spell and Scavenging Ooze's activation both render
     *       "Name (id) - &lt;effect&gt;"). Additive: both keys are absent when the
     *       instance carries no {@code SpellAbility}, and a 2.13 host that ignores them
     *       behaves exactly as before. See {@code StateEncoder.stackKind}.</li>
     * </ul>
     */
    public static final int PROTOCOL_MINOR = 14;

    private final BufferedReader in;
    private final PrintStream out;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private boolean closed = false;

    public JsonRpcChannel(final InputStream is, final OutputStream os) {
        this.in = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        this.out = new PrintStream(os, true, StandardCharsets.UTF_8);
    }

    public static JsonRpcChannel stdio() {
        return new JsonRpcChannel(System.in, new java.io.FileOutputStream(java.io.FileDescriptor.out));
    }

    /** Log a line to stderr. Never touches stdout. */
    public static void log(final String msg) {
        System.err.println("[bench] " + msg);
        System.err.flush();
    }

    public static void logErr(final String msg, final Throwable t) {
        System.err.println("[bench] " + msg + ": " + t);
        t.printStackTrace(System.err);
        System.err.flush();
    }

    /** Fire-and-forget message (hello / event / result). */
    public synchronized void send(final JsonObject msg) {
        out.println(msg.toString());
        out.flush();
    }

    /** Read one JSON line from the host, or null at EOF. */
    public synchronized JsonObject readLine() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            JsonElement el = JsonParser.parseString(line);
            if (el.isJsonObject()) {
                return el.getAsJsonObject();
            }
            log("ignoring non-object protocol line: " + line);
        }
        return null;
    }

    /**
     * Send an {@code ask} and block for its matching {@code answer}.
     *
     * @param kind  protocol kind (priority / attackers / ... )
     * @param body  the ask payload; {@code type}, {@code id} and {@code kind} are filled in here
     * @return the answer object, or an object carrying {@code delegate:true} if the channel died
     */
    public JsonObject ask(final String kind, final JsonObject body) {
        final int id = nextId.getAndIncrement();
        body.addProperty("type", "ask");
        body.addProperty("id", id);
        body.addProperty("kind", kind);
        // v2.9: stamp the minor on EVERY ask, not just the session `hello`. An archived
        // corpus then replays in its own era's shape inside a newer build, without the
        // reader having to remember which session it came from.
        body.addProperty("protocolMinor", PROTOCOL_MINOR);
        send(body);
        if (closed) {
            return delegateAnswer(id);
        }
        try {
            JsonObject msg;
            while ((msg = readLine()) != null) {
                String type = msg.has("type") ? msg.get("type").getAsString() : "";
                if (!"answer".equals(type)) {
                    log("ignoring unexpected message while awaiting answer " + id + ": " + msg);
                    continue;
                }
                int gotId = msg.has("id") ? msg.get("id").getAsInt() : -1;
                if (gotId != id) {
                    log("late/stale answer id " + gotId + " while awaiting " + id + "; discarding");
                    continue;
                }
                return msg;
            }
            closed = true;
            log("host closed stdin while awaiting answer " + id + "; delegating from here on");
        } catch (IOException e) {
            closed = true;
            logErr("channel read failure awaiting answer " + id, e);
        }
        return delegateAnswer(id);
    }

    public boolean isClosed() {
        return closed;
    }

    private static JsonObject delegateAnswer(final int id) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "answer");
        o.addProperty("id", id);
        o.addProperty("delegate", true);
        return o;
    }
}
