# forge.bench — wire protocol v1 implementation notes

Implements the mtgx↔Forge benchmark bridge described in
`mtgx-worktrees/forge-bench/docs/qa/forge-bench/ARCHITECTURE.md`
(sections "Play layer", "Wire protocol v1", "Draft layer").

All code here is derivative of GPL-3.0 Forge classes and **must never be copied into the
mtgx repo**. mtgx contains only its own protocol/decoder/harness code.

## Message shapes as implemented

```
JVM→host  {"type":"hello","protocol":1,"forgeCommit":"…","forgeVersion":"…",
           "aiProfile":"Default","seed":123,"games":3,"useSimulation":false,
           "sequentialAi":true}
JVM→host  {"type":"ask","id":7,"game":"g1","seat":0,"kind":"priority","state":{…},"menu":[…]}
host→JVM  {"type":"answer","id":7,…}
host→JVM  {"type":"answer","id":7,"delegate":true}
JVM→host  {"type":"event","game":"g1","event":{"kind":…}}
JVM→host  {"type":"result","game":"g1","outcome":{…},"delegationCounts":{…}}
JVM→host  {"type":"bye"}
```

Answer payload per `kind` (the spec left "int, int[], or kind-specific object" open):

| kind | answer field(s) | notes |
|---|---|---|
| `mulligan` | `keep` (bool) — or `choice` 0/1 | |
| `priority` | `choice` (int) | index into `menu`; **0 is always pass** |
| `attackers` | `pairs` `[[attackerFid, defenderId], …]` | |
| `blockers` | `pairs` `[[blockerFid, attackerFid], …]` | |
| `targets` | `choices` (int[] of **entity ids**) | |
| `entityChoice` | `choice` (int index) / `choices` (int[] of **indices**), `none` (bool) | |
| `cardsChoice` | `choices` (int[] of **card fids**) | |
| `number` | `value` (int) | |
| `mode` | `choices` (int[] of mode indices) | |
| `confirm` | `yes` (bool) | |
| `scry` | `top` / `bottom` (int[] of fids) | must partition the revealed set |
| `orderBlockers` | `order` (int[] of fids) | permutation of all blockers |
| `assignDamage` | `assign` `{"<fid>": n, …}` | key `-1` = through to the defender; sum must equal the damage |

`entityChoice` answers are **indices into the offered menu**, not ids: the option list can
legitimately mix cards, players and other `GameEntity`s whose id spaces overlap.
`cardsChoice`, `targets`, `scry` and `orderBlockers` are keyed by Forge id (`fid`) because
those menus are homogeneous and the host wants to name cards, not positions.

## Deviations from the spec

1. **`LobbyPlayerBridge extends LobbyPlayerAi`, not `LobbyPlayer`.**
   `AiProfileUtil.getAIProp` returns `""` (i.e. every AI property falls back to its
   hard-coded default) for a lobby player that is not a `LobbyPlayerAi`. A plain
   `LobbyPlayer` subclass would therefore have given the bridged seat a *different* Forge
   AI from the unbridged one and quietly broken the null-mode equivalence. `GameCopier`
   makes the same `instanceof` check for full-simulation mode.

2. **Null-mode instrumentation covers every abstract entry point (109 declarations).**
   The strategic set (17 names / 18 declarations, `chooseNumber` has two overloads) counts
   itself inside its own override; the other 91 are mechanically generated
   `count(name); return super.name(args);` bodies. The non-abstract `final` convenience
   overloads on `PlayerController` (`confirmAction(4-arg)`, `chooseBinary(3-arg)`, …)
   cannot be overridden and are not counted separately — each funnels into a counted
   abstract method, so no call is lost, only the arity is not distinguished.

3. **The `priority` menu excludes mana abilities.** Forge plays mana abilities during cost
   payment, never at priority; offering them invites a non-terminating priority loop
   (`PhaseHandler.mainLoopStep` keeps asking while the answer is non-null). Land plays are
   included.

4. **`state` is attached to every ask**, not only to the kinds the spec listed. It is the
   same seat-visible encoding throughout, so a host decoder has one code path.

5. **`bye`** is sent after the last `result` so a host can distinguish a clean finish from
   a crashed JVM. Not in the spec; additive.

6. **Event trace is filtered.** `zoneChange` is emitted only when the battlefield or a
   graveyard is one end of the move (otherwise a game emits thousands of library/stack
   shuffles). `phase` events are emitted in full.

7. **Answers arriving for a stale id are discarded with a stderr log**, and EOF on stdin
   latches the channel closed — from then on every decision delegates to Forge's AI rather
   than deadlocking.

## Hidden information

`StateEncoder` filters every card through `CardView.canBeShownTo(PlayerView)`. Libraries
are never enumerated in either direction (size only). This makes the bridged seat
*stricter* than Forge's own AI, which peeks at hidden information in places
(`ChangeZoneAi`, `BalanceAi` read opponent hand contents). Documented asymmetry, as agreed
in the architecture note.

## Determinism

- `MyRandom.setRandom(new Random(seed + gameIndex))` per game.
- `Game.AI_CAN_USE_TIMEOUT = false` per game.
- `-Dforge.bench.sequentialAi=true` makes `AiAttackController`'s per-attacker
  forced-attacker `CompletableFuture`s run on the calling thread (`Runnable::run` as the
  executor), so insertion order into the shared `Combat` is attacker order and there is no
  wall-clock abandonment. Default (property unset) is byte-for-byte unchanged.

## Verified nulls

- **Protocol null:** seats `{"0":"null","1":"null"}` produce a byte-identical event trace
  and identical outcomes to seats `{"0":"forge","1":"forge"}` over 3 games
  (897 event lines, identical).
- **Repeat-run determinism:** identical output across two runs of the same config, for
  both `BenchMain` and `DraftMain`.
- **Refusal path:** deliberately illegal `attackers` answers are refused, counted under
  `delegatedRefused.declareAttackers`, and the game continues on Forge's AI.
