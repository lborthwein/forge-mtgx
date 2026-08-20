# forge.bench — wire protocol implementation notes

**Current version: 2** (`hello.protocol`). v2 is strictly additive over v1 — a v1 host
reading a v2 stream sees only fields it does not know about, plus one new message type
(`decklist`) and one informational message (`seats`), both of which an unknown-`type`
skipper already ignores. See "Protocol v2" below for what was added and why.

Implements the mtgx↔Forge benchmark bridge described in
`mtgx-worktrees/forge-bench/docs/qa/forge-bench/ARCHITECTURE.md`
(sections "Play layer", "Wire protocol v1", "Draft layer").

All code here is derivative of GPL-3.0 Forge classes and **must never be copied into the
mtgx repo**. mtgx contains only its own protocol/decoder/harness code.

## Protocol v2 (2026-08-19) — the four measured gaps

The TS harness ran full panels and measured four places where the protocol handicapped
only the bridged seat (`mtgx: docs/qa/forge-bench/TOOLS.md`, "Known protocol gaps").
All four are closed here. Every addition is a new field on an existing message, or a new
message type; nothing was removed or re-keyed.

### 1. Card keywords, and per-attacker `minBlockers`

*Measured cost: 6 refusals per 120 games, every one of them a token.*

Forge validates a block declaration **as a whole**, so a single blocker on a menacing
attacker invalidates the entire answer and the whole block step falls to Forge's AI. The
host read menace off its own cube entry, but a **token** has no cube entry, so a 4/1
Skeleton with menace was unreadable.

- Every card in `state` and in every card menu now carries
  `keywords: string[]` — Forge's own keyword strings (`"Flying"`, `"Menace"`,
  `"Protection from red"`, `"Bushido 1"`). Read from `Card.getKeywords()`, which walks the
  **live** keyword state through `visitKeywords`, so granted, removed and
  continuous-effect keywords are all reflected. This is not the printed card script.
- The `blockers` ask additionally carries `minBlockers: {attackerFid: n}` from
  `CombatUtil.getMinNumBlockersForAttacker`. Keywords alone are not enough: the same
  requirement can come from an effect that grants no keyword at all, so the number is
  stated outright.

Verified: an attacking Boggart Brute arrives as
`{"name":"Boggart Brute","fid":27,"keywords":["Menace"]}` with `"minBlockers":{"27":2}`,
and a host that honours it declares multi-blocks with **zero** `declareBlockers` refusals.

### 2. The seat's own decklist (`decklist` message)

*Unlocks `src/ai/keep.ts`, the real mulligan decision, which needs the deck.*

Before the first ask of each game, every **bridged** seat receives:

```
{"type":"decklist","game":"g1","seat":0,"name":"Cube0",
 "main":[{"name":"Withdraw","set":"PCY","count":1}, …],"sideboard":[…]}
```

Seat-private on purpose — the message is emitted only for bridged seats and carries only
that seat's own list, so it does not widen what the bridged seat plays on beyond what a
human pilot would have. Sent per game because a match may sideboard between games.

**Fairness footgun:** one JVM has one stdio channel, so if the harness bridges *both*
seats in the same process (including the `null`/`null` configuration, where both seats are
`LobbyPlayerBridge`), the host receives both decklists on the same stream. Route each
`decklist` to the pilot instance for `msg.seat` and to no other — the bridge cannot enforce
this for you. The intended benchmark shape is one bridged seat against a pure Forge seat,
where the question does not arise.

### 3. Structured cost, X range, and modes on a priority option

*Options that differed only in a cost arrived as several menu entries the scorer could not
tell apart, and mode selection happened inside Forge after the answer.*

`ForgeAbility` gains:

| field | meaning |
|---|---|
| `optionKey` | stable discriminator: host fid, api, rendered cost, optional costs baked in, and spell/ability/land |
| `cost.mana` | the mana cost as a string (`"{X}{R}"`) |
| `cost.cmc` | its converted cost |
| `cost.onlyMana` | whether anything else must be paid |
| `cost.parts[]` | `{kind, rendered}` per `CostPart` — tap, sacrifice, discard, life… |
| `cost.optionalPaid[]` | which optional costs (kicker, buyback…) are baked into *this* entry |
| `x.has` / `x.min` / `x.max` | whether the option has X, and the largest X the activating player can currently pay for (`ComputerUtilMana.getAvailableManaEstimate` minus the fixed cost) — an X spell with no ceiling is not a priceable option |
| `modes[]` | for a modal (`Charm`) ability: `{index, api, description, usesTargeting}` from `CharmEffect.makePossibleOptions`, which already drops modes whose targets do not exist |
| `minTargets` / `maxTargets` | present when the option targets |

Note `payCosts` — the only cost field in v1 — is **the empty string for a spell**; it only
renders non-mana cost parts. `cost.mana` is the field to read. Verified: Preordain arrives
with `"payCosts":""` and `"cost":{"mana":"{U}","cmc":1,…}`.

The `mode` ask also gained `max` (alias for the existing `num`) and each menu entry gained
`index`, `api` and `usesTargeting`, so the index the answer uses is stated rather than
implied by position.

Residual, unchanged: `ComputerUtilAbility.getOriginalAndAltCostAbilities` calls
`chooseOptionalCosts` while building the menu, so *which* optional costs are offered is
still Forge's AI's decision — the host now sees which ones were taken (`cost.optionalPaid`)
but does not choose them. Measured at ~70 `chooseOptionalCosts` calls per game per seat;
closing it means enumerating optional-cost subsets, which is a menu-size decision the
harness should make deliberately.

### 4. Structured stack targets

*`stack[].targets` was Forge's rendered string (`"[]"`), which is not a contract.*

Each stack entry now also carries:

- `targetIds: number[]` — ids of targeted cards and players
- `targetsDetail: ForgeEntity[]` — the same, as full entity records
- `targetSpells: [{stackId, fid, name}]` — targets that are themselves spells
  (counterspells), kept separate because their ids live in the stack-instance space, not
  the card/player space

`targets` (the string) is unchanged and still sent, so a v1 decoder is unaffected.

## Forge's simulation AI (`useSimulation` / `simSeats`)

`AIOption.USE_FULL_SIMULATION` is a **per-seat policy identity**, so it is selected per
seat:

- `"useSimulation": true` — every seat runs the simulation AI.
- `"simSeats": [1]` — only the listed seats do; overrides `useSimulation`.

The JVM echoes what it resolved in a `{"type":"seats","simulationSeats":[…]}` message right
after `hello`, and the per-seat stderr line reads `seat 1 = forge+sim / …`.

Verified end-to-end on the same seed and decks: no simulation → 1474 / 619 ms per game;
`useSimulation:true` → 6981 / 8066 ms with different event counts (408→402, 384→385), i.e.
the policy actually changed rather than the flag merely being accepted. Raise
`timeoutSec` accordingly — simulation is 5-13x slower here.

Note the simulation AI also skips the `AI:RemoveDeck:All` script filter, which is the other
reason the architecture note lists it as an option for cube cards Forge's heuristic AI
refuses to cast.

## Message shapes as implemented

```
JVM→host  {"type":"hello","protocol":2,"forgeCommit":"…","forgeVersion":"…",
           "aiProfile":"Default","seed":123,"games":3,"useSimulation":false,
           "sequentialAi":true}
JVM→host  {"type":"seats","simulationSeats":[1]}          // v2, informational
JVM→host  {"type":"decklist","game":"g1","seat":0,…}      // v2, once per game per bridged seat
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

3. **The `priority` menu excludes mana abilities, and excludes abilities with too few
   legal targets.** Forge plays mana abilities during cost payment, never at priority;
   offering them invites a non-terminating priority loop (`PhaseHandler.mainLoopStep` keeps
   asking while the answer is non-null). `SpellAbility.canPlay` does not check target
   availability, so a counterspell would otherwise be offered with an empty stack. Land
   plays are included.

3b. **The bridge re-targets a host-chosen ability before handing it back** (`ensureTargets`).
   This is load-bearing and was not in the spec. Forge's AI assigns targets inside
   `canPlayAI`, while deciding *whether* to play the ability;
   `ComputerUtil.handlePlayingSpellAbility` then puts it on the stack with whatever targets
   are already attached and never asks again. Measured: **zero `chooseTargetsFor` calls
   across three AI-vs-AI cube games**. The abilities in our priority menu have not been
   through `canPlayAI`, so without this step a host-chosen targeted spell would reach the
   stack untargeted. Routing the fix through `chooseTargetsFor` means the host gets a
   `targets` ask, and a delegating host falls back to Forge's per-API targeting
   (`AiController.doTrigger`).

   Residual, documented rather than fixed: modal (`Charm`) abilities choose their modes
   inside `handlePlayingSpellAbility` *after* this step, so targets for a mode's
   sub-abilities are still assigned by Forge's AI. Run with `useSimulation:true` if that
   matters for a given campaign.

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
