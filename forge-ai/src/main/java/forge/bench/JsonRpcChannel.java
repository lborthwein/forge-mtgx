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
     *   <li><b>15</b> — <i>four state facts the host had to guess at, and one id space it
     *       could not read.</i> {@code ForgeCard.producedMana} (a token has no printed
     *       text and the host was re-deriving cube cards' production from oracle text);
     *       {@code ForgePlayerState.maxLandPlays} / {@code maxLandPlaysInfinite} (the host
     *       hardcoded 1, wrong under Azusa and Oracle of Mul Daya);
     *       {@code combat.attackers[].defenderKind} / {@code defenderSeat} (an attack on a
     *       planeswalker decoded as an attack on the face); and
     *       {@code ForgeCard.attachedToKind} beside the {@code attachedTo} that has been
     *       on the wire since v1 — an Aura may enchant a PLAYER, and a bare id cannot say
     *       so. Every one is a new key on an existing message; every one is OMITTED where
     *       the JVM has nothing to say, so absent never asserts a value and a 2.14 host is
     *       unaffected. See {@code StateEncoder.encodeProducedMana} and
     *       {@code StateEncoder.encodeCombat}.</li>
     *   <li><b>16</b> — <i>{@code ForgeCard.producedManaKnown}: "produces nothing" is now
     *       a statement, not an absence.</i> v2.15 sent {@code producedMana} only when
     *       non-empty and asserted that this kept "absent" and "produces nothing" apart.
     *       It did not. Absent carried FOUR meanings at once — a pre-2.15 jar, no mana
     *       ability at all, a mana ability that currently produces nothing (an
     *       un-imprinted Chrome Mox, a level-0 Joraga Treespeaker), and an enumeration
     *       that threw — so the only reading a host could take from it was the one
     *       v2.15's own docstring prescribed: keep the printed-text derivation. Printed
     *       text says "add" on a loyalty ability (CR 605.1a excludes loyalty abilities
     *       from mana abilities <i>by name</i>), on a trigger that fires off someone
     *       else's tap ({@code Nissa, Who Shakes the World}, {@code Utopia Sprawl}), and
     *       on a blank Chrome Mox — so the host offered mana that does not exist, on
     *       9.5% of its decision frames.
     *       <p>{@code producedManaKnown: true} accompanies every card whose enumeration
     *       SUCCEEDED, alongside {@code producedMana} when that is non-empty and alone
     *       when it is empty. The reader's rule is one line — <i>known is
     *       {@code producedManaKnown === true}; what it produces is
     *       {@code producedMana ?? []}</i> — and the key is omitted only when the
     *       enumeration threw, which is the same shape (and the same host behaviour) as
     *       a jar that never heard of the field. {@code producedMana} itself is
     *       byte-identical to v2.15 on every observation that carried it, so a 2.15 host
     *       reading a 2.16 stream is unaffected. See
     *       {@code StateEncoder.encodeProducedMana}.</li>
     *   <li><b>17</b> — <i>a new ask kind, {@code zoneChange}, and it is the first one
     *       added since v2.0 rather than a new field on an existing one.</i>
     *       {@code PlayerControllerBridge.chooseSingleCardForZoneChange} and
     *       {@code chooseCardsForZoneChange} were {@code count(); return super....} —
     *       counted and handed to Forge's own AI with no wire ask at all. The counter
     *       reads <b>1,072 per 384 games</b>, so a thousand library searches a bench were
     *       decided by {@code ChangeZoneAi} while the bridged seat watched, Doomsday's
     *       five-card pile among them. Both now take the {@code chooseCardsForEffect}
     *       shape: a {@code bridged()} guard, one {@code zoneChange} round trip, and a
     *       {@code null} answer meaning <i>delegate</i>, so every path the host declines
     *       is byte-identical to the old behaviour.
     *       <br>The ask body is {@code cardsChoice}'s ({@code title}, {@code min},
     *       {@code max}, {@code menu}, {@code ability}) plus five fields a host cannot
     *       infer: {@code destination} and {@code origin[]} (zone names — a fetch to hand,
     *       to the battlefield, to the graveyard and to the library are four different
     *       decisions and the prompt text does not reliably say which); {@code changeNum}
     *       and {@code chosen} (a multi-card search reaches an <b>AI</b> controller as N
     *       SEQUENTIAL single-card asks off a shrinking {@code fetchList} — see
     *       {@code ChangeZoneEffect.allowMultiSelect}, which requires
     *       {@code !decider.getController().isAI()} — so without the index the host
     *       re-derives a different pile at every pick); and {@code optional}/{@code single}
     *       (which method is asking, and whether declining is legal).
     *       <br>Additive: a pre-2.17 host has never seen the kind, answers nothing, and
     *       the JVM delegates exactly as it did before. See
     *       {@code PlayerControllerBridge.askForZoneChange}.</li>
     *   <li><b>18</b> — <i>the two remaining wire clamps: the ORDER half of the pile, and
     *       the mana abilities that were never on the wire at all.</i> Two independent
     *       additions, each additive and each declinable.
     *       <p><b>{@code orderZone}, a second new ask kind.</b>
     *       {@code PlayerController.orderMoveToZoneList} was the same
     *       {@code count(); return super....} shape one method along, at <b>495 calls per
     *       288 games</b>. It is the controller entry point for
     *       {@code RearrangeTopOfLibraryEffect}, {@code DigEffect}'s remainder,
     *       {@code ChangeZoneAllEffect} and {@code ChangeZoneEffect}'s own
     *       {@code chosenCards} ordering — and <b>Doomsday's {@code SVar:DBDig} is a
     *       {@code RearrangeTopOfLibrary}</b>, so after v2.17 the host chose WHICH five
     *       cards and Forge still chose in WHAT ORDER they were stacked. Same shape as
     *       {@code zoneChange}: a {@code bridged()} guard, one round trip, {@code null}
     *       meaning <i>delegate</i>.
     *       <br>The answer is a PERMUTATION of the menu's fids — every card exactly once,
     *       nothing added and nothing dropped — and it is in <b>MOVE ORDER</b>, which is
     *       to say the exact list this method returns to its caller. The bridge performs
     *       <b>no transformation whatsoever</b> on it. That matters because the callers do
     *       not agree with each other about what the order means:
     *       {@code RearrangeTopOfLibraryEffect} walks the list calling
     *       {@code moveToLibrary(next, 0)}, so the LAST element ends up on top of the
     *       library. {@code PlayerController.orderedMoveToTopOfLibrary} is the predicate
     *       that says when that reversal applies, and it is published as {@code topFirst}
     *       rather than applied here, so the host reads the same fact Forge's own two
     *       controllers read and no reversal happens twice. ({@code PlayerControllerHuman}
     *       collects a top-first order from its user and reverses before returning;
     *       {@code PlayerControllerAi} builds a top-first list and reverses at the same
     *       gate. Both would be indistinguishable from the outside had the bridge chosen
     *       to reverse too, and one of the three would have been wrong.)
     *       <br>Also on the body: {@code destination} (the zone), {@code count}, and
     *       {@code ability}. An answer that is not a permutation is REFUSED and the call
     *       delegates.
     *       <p><b>{@code manaAbilities} on the {@code priority} body.</b>
     *       {@code legalSpellAbilities} skips every {@code sa.isManaAbility()} when it
     *       builds the priority menu — deliberately, since v1, because a mana ability is
     *       part of paying for something rather than a thing to do. The consequence was
     *       invisible until it was counted: the host builds {@code CardView.abilities} out
     *       of that menu, so on the bench a permanent's mana abilities <b>are not in its
     *       ability list at all</b>. Measured host-side: {@code isManaAbility} appears
     *       <b>57,053 times on the wire and is {@code false} every time</b>, and
     *       <b>0 of 118,481</b> menu options is a mana ability. A Grim Monolith published
     *       one ability, {@code "{4}: Untap this artifact."}, and never the
     *       {@code "{T}: Add {C}{C}{C}"} the loop is made of.
     *       <br>This is a PARALLEL CHANNEL and not a menu change, on purpose: the
     *       {@code menu} array is byte-identical to v2.17, so every index a host answers
     *       with means what it always meant, and a host that ignores the new key behaves
     *       exactly as it did. The entries are {@code encodeSpellAbility} objects — the
     *       same encoding the menu uses, each already carrying the host card's
     *       {@code fid}, {@code isManaAbility: true}, {@code payCosts} and
     *       {@code description} — for every card the bridged seat controls whose
     *       {@code getManaAbilities()} is non-empty. Enumeration failures are swallowed
     *       per card and the key is simply shorter, never wrong.
     *       <br>Additive on both counts: a pre-2.18 host sees an unknown kind it never
     *       answers (the JVM delegates) and an unknown key it never reads.</li>
     *   <li><b>v2.19 — {@code spellsCastThisTurn} on every player state.</b>
     *       The STORM COUNT, and its absence was never noticed because nothing on the
     *       wire was wrong: {@code decodeState} builds its view off
     *       {@code emptyTableView}, whose {@code spellsCast} is {@code [0, 0]}, and no
     *       field ever overwrote it. So the bridged pilot read a structural zero on
     *       <b>every bench frame ever recorded</b> — {@code planner.ts}'s copy count
     *       ({@code 1 + spellsCast[seat]}), {@code heuristic.ts}'s storm payoff and
     *       {@code turnSpend.ts}'s {@code theirSpellsCast} all priced a storm of zero
     *       while the game had a storm.
     *       <br><b>Not derivable host-side, which is why it is a wire field.</b> The
     *       bridge publishes no {@code log}, so {@code TableView.log} is empty and the
     *       native reading has nothing to count; and the ask stream shows a seat only
     *       its OWN questions, so the opponent's casts are invisible between our asks
     *       even in principle. {@code Player.getSpellsCastThisTurn()} is the engine's
     *       own count — the one CR 702.40a (storm) reads — filtered to the activating
     *       player, and it resets at the turn boundary exactly as
     *       {@code state.spellsCastThisTurn} does natively.
     *       <br>Additive: one integer on the player object, no menu change, no ask kind.
     *       A pre-2.19 host never reads the key; a post-2.19 host reading a pre-2.19 jar
     *       finds it absent and keeps the structural zero it always had.</li>
     *   <li><b>v2.20 — the {@code delegated} message: WHAT FORGE CHOSE.</b>
     *       Every previous minor widened what the host is TOLD BEFORE it answers. This
     *       one is the first that reports back what happened when the host declined to
     *       answer at all, and it exists because the behaviour-clone lane cannot start
     *       without it.
     *       <br><b>The hole.</b> {@code AnswerDelegate} — {@code {"delegate": true}} — is
     *       WRITE-ONLY. The JVM hands the call to {@code PlayerControllerAi}, plays the
     *       result, and says nothing; {@code ResultMessage.delegationCounts} then reports
     *       {@code delegatedRequested}/{@code delegatedRefused} as PER-METHOD COUNTS with
     *       no choices in them. So a host running {@code MTGX_FORGEDELEG} could measure
     *       how often it handed a kind away and never once what came back, and the data
     *       pipeline's imitation-target column ({@code forgeAction}) was
     *       <b>100% empty by construction</b> — three lanes found it independently.
     *       <br><b>The shape.</b> After the delegated call returns, and only for an ask the
     *       host actually declined, the JVM sends one fire-and-forget line:
     *       <pre>{type:"delegated", game, seat, id, kind, method, protocolMinor,
     *  answer:{…}, match?:"identity"|"structural"|"none"}</pre>
     *       where {@code id} is the id of the ask that was declined and {@code answer} is
     *       Forge's decision <b>in the same shape that ask's own answer would have
     *       taken</b> — {@code {choice}} for a {@code priority}, {@code {pairs}} for an
     *       {@code attackers}, {@code {choices}} of fids for a {@code cardsChoice}, and so
     *       on down all eighteen kinds. That is deliberate and it is the whole design
     *       constraint: the host already owns a decoder and an action-space grammar for
     *       every answer shape it emits, so an echo in that shape needs no second grammar
     *       and cannot drift from the first.
     *       <br><b>{@code match} is on the {@code priority} echo only, and it is a
     *       confession.</b> Forge's own {@code AiController} enumerates its abilities
     *       independently of {@link PlayerControllerBridge#legalSpellAbilities}, so the
     *       {@code SpellAbility} it returns is frequently not an object in the menu the
     *       ask published. The echo resolves it by object identity first, then by a
     *       structural key (host card fid + API + description), and says which — or
     *       {@code "none"} with {@code choice: null} and the full {@code sa} encoding
     *       beside it. A host must never read {@code choice} without reading
     *       {@code match}; an unmatched echo is a real Forge decision that this menu could
     *       not name, not a menu index.
     *       <br><b>Additive, and silent when nothing is delegated.</b> The message is sent
     *       only on a host-requested delegation ({@code {"delegate": true}}), never on a
     *       REFUSAL — a refused answer is our encoding defect and Forge deciding after it
     *       is a fallback, not a handoff, and mixing the two would put our own bugs in the
     *       teacher corpus. It is never sent on an unbridged seat, inside a simulation
     *       copy, or once the channel has closed. A pre-2.20 host sees an unknown message
     *       type; a post-2.20 host reading a pre-2.20 jar sees none, which is exactly the
     *       empty column it had before.</li>
     * </ul>
     */
    public static final int PROTOCOL_MINOR = 20;

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
