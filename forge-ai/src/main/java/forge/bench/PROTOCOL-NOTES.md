# forge.bench — wire protocol implementation notes

**Current version: 2** (`hello.protocol`), minor **17** (`hello.protocolMinor`). v2 is strictly additive over v1 — a v1 host
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

## Protocol v2.1 — stack candidates on a `targets` ask (queue item B-1)

`hello` now also carries `protocolMinor`. 2.1 is additive over 2.0.

### The defect

`hasEnoughTargets` and `chooseTargetsFor` both enumerated targets through
`TargetRestrictions.getAllCandidates`, which walks `game.getCardsIn(tgtZone)` and tests
`sa.canTarget(Card)` → `isValid("Spell")` **against the card**. A permanent spell sitting
on the stack is a creature/artifact/enchantment card, so it fails that predicate. Result:
a counterspell was never offered against a permanent spell — the menu filter dropped the
counterspell entirely, and on the frames where it was offered the target never appeared.
Measured by the harness lane: 29 of 32 payable frames omitted; ~11 of Forge's 17 counters
on identical seeds.

Forge's own count does not have this hole. `TargetRestrictions.getNumCandidates`
(TargetRestrictions.java:556) handles the stack on a **separate branch**, iterating
`game.getStack()` as `SpellAbilityStackInstance`s and testing
`sa.canTargetSpellAbility(...)`, then **adds** `getAllCandidates(sa).size()`.

### The fix

`stackCandidates(sa)` reproduces that branch; `candidateCount(sa)` sums it with
`getAllCandidates` exactly as `getNumCandidates` does, and both the menu filter and the
`targets` ask use the union.

**Mixed-zone is an OR, never an either/or.** The code never branches exclusively on
`tgtZone.contains(ZoneType.Stack)`: both branches are always enumerated and summed. A
"counter target spell or destroy target permanent" shape names both zones, and an
exclusive stack branch would make it vanish whenever the stack is empty — the same defect
pointed the other way.

### New fields on a `targets` ask

| field | meaning |
|---|---|
| `stackCandidates` | how many menu entries are stack instances (0 when none) |
| `spellTargetIdBase` | the id-namespace offset, `1000000000` |
| `targetsStackZone` | whether the restriction names the stack at all |

and each stack menu entry:

```json
{"id":1000000007,"stackId":7,"kind":"spell","zone":"Stack",
 "name":"Spined Thopter","fid":32,"isSpell":true,"isPermanentSpell":true,
 "types":"Artifact Creature - Phyrexian Thopter","manaCost":"{2}{U/P}","cmc":3,
 "power":2,"toughness":1,
 "controller":2,"activator":2,"controllerSeat":1,
 "api":"","description":"…"}
```

**Id namespace.** Forge counts card ids and spell-ability ids on separate sequences, so a
stack instance's id can collide with a battlefield card's id. Stack candidates are
published at `SPELL_TARGET_ID_BASE + stackId`; an answer id **at or above** the base is
resolved only against the stack list, **below** it only against cards and players. The
namespaces cannot overlap, so a spell id can never decode as a card. The base is positive,
so it never collides with the `-1` sentinel used by `assignDamage`/`entityChoice`.

**Controller tagging.** Stack entries carry `controller`/`activator` (Forge player ids) and
`controllerSeat` (turn-order index). The seat index is there so a host never has to infer
ownership from a player id and fall through to "ours" — that fallthrough is how a pilot
ends up countering its own spell with two spells on the stack.

**Answers.** Unchanged shape: `{"choices":[id,…]}`. A stack id is applied with
`TargetChoices.add(SpellAbility)` after `canTargetSpellAbility` re-validates it against the
live game.

**Refusals are counted, never silent.** Every decode mismatch — unknown id in either
namespace, an id the engine rejects, a `TargetChoices.add` that returns false, and now also
a throw out of candidate enumeration — goes through `refuse()` and lands in
`delegatedRefused.chooseTargetsFor` before falling back. Forge picking our targets without
that record would credit the pilot for Forge's choices.

## Protocol v2.16 — "produces nothing" becomes a statement instead of an absence

*One boolean, and it exists because **v2.15's own claim below is wrong** — §1 of that
section ends "omitted … so 'absent' and 'produces nothing' stay distinguishable", and they
were not. This is the correction, and it is the second half of the round trip the host's
`producedMana` census opened.*

### The defect the census measured

The host shipped its v2.15 reader **fail-closed**, exactly as this file prescribed: absent
`producedMana` keeps the printed-oracle-text derivation. Then it measured what that costs,
over 3,262 decision frames on a v2.15 jar:

```
  our untapped battlefield permanents the wire is SILENT about   3,436
  of those, the host emits a tap_for_mana for                      310   in 4 card shapes
  PHANTOM tap_for_mana actions emitted                             614   across 9.5% of frames
```

| card | the host offered | why the JVM is right to say nothing |
|---|---|---|
| `Chrome Mox`, nothing imprinted | `WUBRG` ×5 | the ability exists and every colour answers false |
| `Nissa, Who Shakes the World` | `G` | *"whenever you tap a Forest for mana"* is a **trigger off the Forest's tap**, not Nissa's mana ability |
| `Chandra, Torch of Defiance` | `R` | **CR 605.1a** excludes loyalty abilities from mana abilities *by name* |
| `Utopia Sprawl` | `WUBRG` ×5 | same trigger shape as Nissa |

Every one of those is mana the pilot cannot make, counted by its own `manaSources` and
planned through by its cast scorer — the **mirror image** of the choice-list defect v2.15
§1 closed, and the worse direction: that one hid mana the seat had, this one invents mana
it does not have and lets it announce a spell the engine will refuse.

### Why the host may not fix it alone

Absent carried **four** meanings at once and the wire could not separate them: a pre-2.15
jar, no mana ability at all, an ability that currently produces nothing (an un-imprinted
Chrome Mox, a level-0 Joraga Treespeaker), and an enumeration that threw. Reading absent as
"produces nothing" would be inferring a fact the protocol declines to state — and it would
be wrong on the first of the four, silently, for every archived corpus.

### The field

`producedManaKnown: true` accompanies every card whose enumeration **succeeded**, whatever
it found:

```
"producedMana": ["U"], "producedManaKnown": true    // Chrome Mox, blue card imprinted
"producedManaKnown": true                           // Chrome Mox, nothing imprinted -> NO MANA
                                                    // Chandra / Nissa / Utopia Sprawl -> NO MANA
(neither key)                                       // the enumeration threw, or a pre-2.16 jar
```

The reader's rule is one line — *known is `producedManaKnown === true`; what it produces is
`producedMana ?? []`* — and the key is **omitted only when `encodeProducedMana` throws**,
which is byte-for-byte the shape a jar that never heard of the field emits, so the
fail-closed host behaviour is unchanged on exactly the population that still needs it.

`producedMana` itself is **unchanged**: same values, still omitted when empty. Every
observation that carried it in v2.15 carries the identical array in v2.16, so a 2.15 host
reading a 2.16 stream is not affected at all — the boolean is a new key an unknown-field
skipper ignores.

`StateEncoder.encodeProducedMana` returns `null` for the throw case rather than an empty
array; the two shared one encoding through v2.15 and that conflation *is* the bug.

Host side: `MTGX_PHANTOMTAP` (`mtgx: src/ai/forgeBench/decode.ts`), which emits no
`tap_for_mana` for a permanent the wire declares known-and-empty, and stops
`manaOpen`/`manaTypes` claiming a colour for it.

## Protocol v2.15 — four state facts the host was guessing, and one id space it could not read

*Filed by the host's systematic decode audit (`mtgx:
docs/qa/losing-lines/recon/decode-audit.md` §4), which walked **every field
`decode.ts` derives from a JVM message** against 2,050 observed protocol frames and
66,074 card observations. Four of its six filed items were "ask Forge for a field".
This is that batch. Every one is a new key on an existing message and every one is
**omitted where the JVM has nothing to say**, so absent never asserts a value.*

### 1. `ForgeCard.producedMana` — a Treasure token made no mana

The host derives `manaOpen` / `manaTypes` from **printed oracle text**, looked up by card
name in its own cube index. A **token has no printed text and no cube entry**: a Treasure,
a Blood or a Food reached the pilot as a permanent that produces nothing. Observed in the
archived corpus: Treasure Token 12, Blood Token 37, Food Token 46.

The same re-derivation is also the shape of the largest correctness defect this bridge has
had — the host's own parser read *"Add {W} or {U}"* as `[W]` on **63 of 540 cube cards**
(every original dual, shock, fastland, horizon land, Triome, both Hierarchs, all ten
Talismans), which is a mono-coloured mana base for the whole benchmark. Publishing what the
engine already knows makes that class structurally impossible rather than repaired.

```
"producedMana": ["W","U"]        // Tundra
"producedMana": ["C"]            // Treasure Token  (any colour -> see below)
```

Asked through `Card.canProduceColorMana(Set)`, one colour at a time over
`MagicColor.Constant.COLORS_AND_COLORLESS`, so reflected mana (`ManaReflected`) and combo
mana are the engine's answer and not a re-parse. **Read-only**: no `setActivatingPlayer`,
so encoding a state cannot perturb one. Omitted — not an empty array — for anything whose
`getManaAbilities()` is empty.

> **Corrected at v2.16.** This paragraph originally ended *"so 'absent' and 'produces
> nothing' stay distinguishable"*. They were not: omission also covered a pre-2.15 jar, a
> currently-blank producer and a thrown enumeration, and the host's fail-closed reading of
> it cost 614 phantom mana offers across 9.5% of frames. `producedManaKnown` is the fix —
> see the v2.16 section above.

### 2. `ForgePlayerState.maxLandPlays` / `maxLandPlaysInfinite`

The host reads `landLimit - landsPlayed` **explicitly because of Azusa and Oracle of Mul
Daya**, and then had to hardcode `landLimit` to 1 because the wire never said. Oracle of
Mul Daya is in the bench cube (80 battlefield observations in the archived corpus) and the
constant is provably wrong on **26 of 2,050 frames**.

Not recoverable host-side. Inferring the limit from whether a land play appears in the
priority menu would make a state field a function of a menu — and the menu is built *after*
the engine consults the limit. `getMaxLandPlays()` is base 1 plus every active adjustment;
`maxLandPlaysInfinite` is the separate flag no integer can express.

### 3. `combat.attackers[].defenderKind` / `defenderSeat`

`defenderId` is **one int over two namespaces**: a player's id is its seat index, a
planeswalker's or battle's is its `fid`, and both count from 0/1. The host had no way to
separate them and recorded **every attack as an attack on the face**. Measured in the
archived corpus: `defenderId` takes the value 27 on **24 attacker records**, and fid 27 is
`Nissa, Who Shakes the World` — a Ninja Token and a Thief of Sanity attacking a walker,
decoded as 5 power coming at the player. That is not cosmetic: it inflates the perceived
clock at exactly the frames where the clock decides whether to block.

The `attackers` **ask** has published `legalPairsTyped` since v2.8 and
`AskAssignDamage` has published `defenderKind`; the **state's** combat block never did.
This mirrors those. `defenderKind` is emitted only for the two kinds that exist
(`"player"` | `"card"`), so absent means *this jar does not say*. `defenderSeat` is the
turn-order index — the space a host's seat map already speaks — for the same reason
`encodeStackCandidate` publishes `controllerSeat` beside the raw `controller`.

### 4. `ForgeCard.attachedToKind` — and a correction to the audit that asked for it

The audit filed *"attachments are absent from the protocol"* on the evidence that
`attachedTo` / `attachments` are **0 occurrences across all 66,074 card observations**.

**That is a population zero, not a protocol gap, and the record should say so.**
`encodeCardUnchecked` has emitted both keys **since protocol v1** (`6d5fb4e1`), from
`Card.getEntityAttachedTo()` and `Card.getAttachedCards()`. The corpus contains **one**
`Equip` observation in eight games and that Equipment was never equipped, so the fields had
nothing to describe. *Absence on the wire is evidence about the games, not about the
encoder.*

What v2.15 does add is the half that was genuinely missing: **which namespace `attachedTo`
is in.** An Aura may enchant a **player** (CR 303.4a), whose id is a seat index, while a
Card's id is an fid from 1 — the same two-spaces-in-one-int shape `entityRef` was
introduced for at v2.8. A host resolving a bare `attachedTo` through its card map gets the
wrong object, silently and only sometimes.

```
"attachedTo": 41, "attachedToKind": "card"
"attachedTo": 0,  "attachedToKind": "player"
```

### Not in this batch, and why

- **`activePlayer: -1`.** 13 archived frames (turn 0, phase `"null"`) send `-1` for both
  `activePlayer` and `priorityPlayer`, and the host mapped it to *the opponent*. `-1` is
  the honest encoding of "nobody" — there is no Forge-side repair, and the fix belongs in
  the host's seat mapping.
- **`protectionsNow` / structured keyword parameters.** Forge writes protection as
  `"Protection from red"` and ward as `"Ward:2"` inside the `keywords` array, so the
  parameter *is* on the wire; the host's reader wants it split out. That is a host-side
  parse of a string this jar already sends, and the archived corpus contains **no**
  protection or ward string at all (0 of 51 distinct keyword spellings), so a jar change
  would ship unmeasured.

## Protocol v2.14 — a stack entry says what it IS (spell vs ability)

*The one field the stack payload never had, and the host had no honest way to derive.*

### The defect, host-side

`state.stack[]` carried `id`, `name`, `fid`, `controller`, `description` and the v2 target
structure — and **nothing that separates a spell from an ability**. The host
(`mtgx: src/ai/forgeBench/decode.ts`) derived `isAbility` as `fid == null`, which is
exactly backwards: **`fid` is the SOURCE card's id, and an activated or triggered ability
has a source** — so `fid` is set on every entry Forge has ever sent and `isAbility` was
`false` for all of them. Three shipped host guards read that flag and were therefore
silently inert on the entire bridge surface (`claimedByOwnStack`, `handPutIsAtCapacity`,
`sacrificesClaimedSource`).

Measured on the archived traces before the fix: **1,096 stack entries over 522 asks in one
game, `fid` set on 1,096 of 1,096, and every one of the nine keys present on every entry** —
one uniform key shape, no discriminator anywhere in it.

### Why it has to come from the JVM

`description` is `si.getStackDescription()`, a rendering and not a contract, and it is
genuinely ambiguous: an instant and an activated ability render the *same* shape.

```
Fatal Push (74) - Destroy Scavenging Ooze (31).                             <- SPELL
Scavenging Ooze (31) - Exile Scalding Tarn (70) from the graveyard. …       <- ACTIVATED
```

`SpellAbilityStackInstance.getSpellAbility()` knows; nothing on the wire did.

### The fields

| field | meaning |
|---|---|
| `kind` | `"spell"` \| `"activated"` \| `"triggered"` \| `"replacement"` \| `"other"` |
| `isAbility` | `!"spell".equals(kind)` — the half a host branches on |

The first three words are deliberately the host engine's own `StackEntry.kind` vocabulary,
so both engines decode into the same three names.

Order of the tests in `StateEncoder.stackKind` is load-bearing: a trigger arrives as a
`WrappedAbility` and `isActivatedAbility()` can also answer true for the ability it wraps,
so `isTrigger()` is asked first, then `isReplacementAbility()`, then
`isActivatedAbility()`.

**Additive, and absent means "ask the old way".** Both keys are omitted when the instance
carries no `SpellAbility` (and if the classification throws, which is logged). A 2.13 host
ignoring them behaves exactly as before, and the 2.14 host reads them only when present —
it does **not** fall back to a rendering heuristic on a pre-2.14 jar, because a wrong
`isAbility` asserted with the authority of the wire is worse than the inert flag it
replaces.

## Protocol v2.9 — every ask states its own minor, and the divided total reaches the wire

Two additive items from the TS lane's v2.8 reconciliation. Nothing changes for a host that
ignores both.

### (a) `protocolMinor` on every ask

`JsonRpcChannel.ask` stamped `type`, `id` and `kind`; the minor was only on the session
`hello`. An archived corpus therefore replayed in whatever shape the *reading* build
assumed, and the reader had to remember which session each line came from. Every ask now
carries `protocolMinor`, so a line is self-describing:

```json
{"type":"ask","id":7,"kind":"targets","protocolMinor":9, …}
```

The TS side reads ask-minor first, session-minor second. Verified: **721 of 721 asks**
across `startingPlayer`, `mulligan`, `priority` and `targets` stamped `9`.

### (b) the divided-as-you-choose total

v2.7 accepts a `divide` map on a `targets` answer, but the total being divided
(`sa.getDividedValue()`) never reached the wire — so a host had no number to partition and
could emit nothing, and every allocation silently fell back to the JVM's even split. The
`targets` ask now publishes:

| field | meaning |
|---|---|
| `dividedAsYouChoose` | whether this ability divides an amount at all |
| `divideTotal` | the total to divide; `null` if the engine has not fixed it yet |
| `divideRemaining` | what is still unassigned (`getStillToDivide`) — the figure a re-entrant targeting pass actually has to work with |

Verified on Arc Lightning (*3 damage divided as you choose among one, two, or three
targets*): the ask arrives as
`{"dividedAsYouChoose":true,"divideTotal":3,"divideRemaining":3,"min":1,"max":3}`, and an
answer naming two targets with `"divide":{"card:30":2,"card:31":1}` is accepted — 8 such
answers, **0 refusals**. Note the typed `"card:<id>"` key form from the v2.8 audit is what
the host should emit; bare ids still work.

## ID SPACES — the namespace rule for every answer

**A Player's id is its seat index (0, 1). A Card's id is its fid, which starts at 1.** The
two spaces overlap: **id 1 is both seat 1 and the first card**. Any answer field that mixes
them and carries a bare integer is ambiguous, and the ambiguity is silent — it resolves to
whichever the menu listed first, which is players.

Measured in the field: the host named a creature, the bare id resolved to the player, and
the burn spell hit its own controller's face. Reproduced here on a fixed seed — same ask,
only the answer's naming differing:

```
untyped  [1]                              -> lifeChanged Seat1-Burn: 20 -> 18   (our own face)
typed    [{"kind":"card","id":1}]         -> zoneChange Grizzly Bears (1): Battlefield -> Graveyard
```
with the menu itself showing the collision:
```
id 1 -> [{"kind":"player","id":1,"name":"Seat1-Burn","life":20},
         {"kind":"card","id":1,"name":"Grizzly Bears"}]
```

### Every answer field, and its space

| ask | answer field | space | mixed? |
|---|---|---|---|
| `priority` | `choice` | menu index | safe |
| `priority` | `x` | scalar | n/a |
| `targets` | `choices` | **players + cards + stack** | **TYPED (v2.8)** |
| `targets` | `divide` keys | same as `choices` | **TYPED (v2.8)**, `"card:1"` / `"player:1"`, bare accepted |
| `attackers` | `pairs[i][0]` | attacker card fid | cards only — safe |
| `attackers` | `pairs[i][1]` | **players + planeswalkers + battles** | **TYPED (v2.8)** |
| `blockers` | `pairs` | card fids both sides | cards only — safe |
| `orderBlockers` | `order` | card fids | cards only — safe |
| `assignDamage` | `assign` keys | card fids, plus the `-1` sentinel | cards only — safe; `-1` guarded in v2.7 |
| `cardsChoice` | `choices` | card fids | homogeneous menu — safe |
| `scry` | `top` / `bottom` | card fids | homogeneous menu — safe |
| `entityChoice` | `choice` / `choices` | **menu indices, not ids** | safe by construction |
| `mode`, `optionalCosts` | `choices` | menu indices | safe by construction |
| `number`, `keywordCost` | `value` | scalar | n/a |
| `confirm` | `yes` | boolean | n/a |
| `startingPlayer` | `play` | boolean | n/a |

Stack (spell) candidates were already disjoint: they are published at
`SPELL_TARGET_ID_BASE + stackId` (1e9) and resolved only in that namespace — see v2.1.

### The typed form

```json
{"choices":[{"kind":"player"|"card"|"spell","id":n}, …]}
{"pairs":[[attackerFid, {"kind":"card","id":n}], …]}
```

The menu already publishes `kind` on every entry; the answer simply echoes it. The JVM
additionally publishes `legalPairsTyped` and `mustAttackTyped` on the `attackers` ask, and
`StateEncoder.entityRef` is the single place the shape is produced, so a host never has to
construct an ambiguous reference.

**Bare ints remain accepted for one minor version** and resolve **exactly as they always
have** — first match in menu order, i.e. players before cards. That path is ambiguous by
construction and cannot be made correct; it is preserved unchanged rather than
"repaired" so the deprecation window does not silently move any existing host's decisions.
Every use increments `legacy.untypedRef`. **Deprecation: bare ints are removed at v2.9** —
watch that counter reach zero first.

## Protocol v2.7 — three bridge crashes, and "Draw" stops meaning "crashed"

**Every "Draw" in a bridged arm was a crashed game.** 15 of 288 bridged games, 0 of 288
null, all NullPointerExceptions stamped `GameEndReason.Draw` by `BenchMain`'s `finally`.
Downstream they scored ½ and were read as game results.

### The reason field

A game that throws now reports `reason:"InstrumentError"` with `crashed:true` and an
`error` string, and the catch widened from `Exception | StackOverflowError` to `Throwable`
so an `OutOfMemoryError` is classified too. **Classify on `outcome.crashed`, never on
`reason == "draw"`** — a real draw and an instrument failure are different events and were
indistinguishable until now.

### (a) the `-1` sentinel in the blocker path — 10 of 15

`assignCombatDamage` is called for **blockers** as well as attackers: Forge uses it to
divide a blocker's damage among the attackers it blocks, and passes `defender == null`
there. Our answer decoder accepted `fid < 0` unconditionally, so the "excess to the
defender" sentinel became `damageMap.put(blocker, null, n)` at
`Combat.assignBlockersDamage:750`, and Guava's `checkNotNull` threw.

Fail-closed: the sentinel never leaves the decoder unless there is a defender to receive
it. `defenderId == -1` with a positive amount is a **counted refusal** (the assignment
falls to Forge's AI); a zero amount is dropped with an instrument
(`damage.droppedZeroExcess`). The ask also now carries `allowExcessToDefender`, so a
correct host never constructs the illegal answer in the first place.

### (b) `getDividedValue` returned null — 3 of 15

Choosing targets is only half of targeting a "divided as you choose" spell:
`DamageDealEffect.resolve:248` then reads `sa.getDividedValue(target)` per target and
dereferences it. `chooseTargetsFor` added targets and never allocated, so it was null.

A `targets` answer may now carry `"divide": {"<targetId>": n}` — validated to cover every
chosen target, to give each at least 1 (CR 601.2d), and to sum to the total. Without it the
amount is split evenly with the remainder on the first target, which is Forge's own
convention (`PossibleTargetSelector`). If the engine has not published a total to divide,
the call is refused rather than allocated by guess.

### (c) `encodeSpellAbility` on a null host card — 2 of 15

`toString()` and `getStackDescription()` both walk the host card, which can be null for an
ability detached from its source (seen inside `chooseSingleEntityForEffect`). A rendering
failure was killing the game. Both are now produced through a `safeText` helper that
returns `"(no host card)"` / `"(undescribable)"` instead of throwing — an ability we cannot
describe is still an ability we must publish.

### Verified — the crashing pairing, driven into the crash on purpose

`decks-p1-sel` seed 3007 (9 and 8 crashes in the banked runs), 6 games per seat
assignment, with a host that deliberately emits the `-1` excess key:

| | our seat p0 | our seat p1 |
|---|---|---|
| games | 6 | 6 |
| **real winners** | **6** | **6** |
| **crashed (InstrumentError)** | **0** | **0** |
| draws | 0 | 0 |
| `assignDamage` asks / sentinel used | 34 / 7 | 39 / 6 |
| `assignCombatDamage` refusals (the guard firing) | 5 | 0 |

Worker stderr: **0 NullPointerExceptions, 0 "game threw"** in both runs. The guard's log
line names the rule it is enforcing:
`answer routed 4 to the defender, but this assignment has none (blocker path, CR 510.1d)`.

Note the sign, from the autopsy: the crashed games were games the bridged seat was losing
(mean life 11.2 vs 15.3), and ½ credit flattered it. Fixing them **costs** a little winrate.
This is corpus hygiene, not a gain.

## Protocol v2.6 — play/draw reaches the host, and the pre-game surface audit

**`chooseStartingPlayer` was an uncounted decision surface.** The bridge inherited
`PlayerControllerAi`'s "AI is brave" — always take the play — so Forge silently decided
play/draw for the bridged seat: 88 uncounted calls in one panel, and our deck on the play
**61% against the null arm's 50%**. Bridge-vs-null arms had never been matched on
play/draw mix.

### Who is asked, and why the confound is a collider

From `GameAction.java:2415-2440`: game 1 picks the chooser with `Aggregates.random`; every
later game in a match gives the choice to the **loser of the previous game**. Only that one
player's controller is called, and its return value is the player who actually goes first —
so **our seat is not asked at all when the opponent won the roll**.

That makes the 61/50 gap a *collider*, not a stray covariate: the bridged seat loses more
often → it is the previous-game loser more often → it is the chooser more often → and it
always took the play. On-the-play rate is a **function of** win rate here, so matching arms
on it after the fact is not enough; the decision has to be on the wire.

Visible in the smoke: 8 games produced **7** asks, because game 1's roll went to the
opponent and our seat was never consulted.

### The ask

```json
{"kind":"startingPlayer","game":"g2","seat":0,
 "winner":0,"choosingSeat":0,"firstGame":false,"state":{…}}
```
answered `{"play": true|false}`, from our seat's perspective. `winner` and `choosingSeat`
are always our own seat, by the semantics above; they are published anyway so a decoder
never has to assume it. `play:false` returns the opponent (refused in a >2-player game
rather than inventing a seating order). No answer = a counted requested-delegation, never
silent.

Instruments: `start.tookPlay`, `start.tookDraw`.

### Verified — same decks and seed, only the answer differing

| `startingPlayer` answered | asks | our seat on the play | counted |
|---|---|---|---|
| `delegate` (reproduces the inherited behaviour) | 7 | **7/8 (88%)** | 7 requested-delegations |
| `{"play":true}` | 7 | 7/8 (88%) | `start.tookPlay` 7 |
| `{"play":false}` | 7 | **0/8 (0%)** | `start.tookDraw` 7 |

0 refusals in every arm.

### Audit — other surfaces Forge decides for our seat before turn 1

Counted over a 3-game cube corpus (2 seats):

| surface | calls | status |
|---|---|---|
| `chooseStartingPlayer` | 3 | **now on the wire** (v2.6) |
| `tuckCardsViaMulligan` | 2 | **now on the wire** — London bottoming; Forge was choosing which cards our seat put on the bottom. Routed through the existing `cardsChoice` kind with min = max = cards to return. |
| `sideboard` | 4 | **inert.** `PlayerControllerAi.sideboard` returns null immediately unless `GameRules.getAISideboardingEnabled()`, which defaults false and `BenchMain` never sets. Counted, no game effect. Becomes live the day a run enables AI sideboarding. |
| `chooseStartingHand` | 0 | variant-gated (Backup Plan and relatives); does not fire in this cube. Still inherited — put it on the wire before running a format that has it. |
| `chooseSaToActivateFromOpeningHand` | 0 | leyline/Gemstone-Caverns class; does not fire here. Same caveat. |
| `revealUnsupported` | 6 | informational, no decision. |
| `revealAnte`, `chooseCardsYouWonToAddToDeck` | 0 | ante only. |

The two that fire are fixed; the two that would fire in another format are named rather
than left to be rediscovered.

## Protocol v2.5 — `menuDiag`, and why the quiet opponent-turn window is NOT bridge-side

The campaign measured a wall: a deferrable instant-speed activation reached our menu 157
times outside end steps and 4 times at end steps, 87.6% of their-end-step priority frames
arrived pass-only, and `acti.releasedTerminal` was 0/288 games. Three suspects were named:
(1) the candidate scan using AI-desirability filters, (2) Forge auto-passing our windows,
(3) `PhaseHandler` granting priority only when Forge's AI would act.

**All three are refuted.** The bridge does not prune the window.

### (2) and (3): we do get priority

Over 2 games against a do-nothing opponent, our seat received a `priority` ask at
**107 of 108** of their end steps, and at every other opponent-turn step
(`THEIRS/UPKEEP` 108, `MAIN1` 107, `COMBAT_BEGIN` 107, `COMBAT_DECLARE_ATTACKERS` 107,
`COMBAT_END` 107, `MAIN2` 107). There is no skip and no auto-pass.

### (1): the scan is legality-only, and the menu is full when the resources are

`legalSpellAbilities` filters on `sa.canPlay()`, `ComputerUtilCost.canPayCost` and target
existence. None is an opinion: `canPlay()` routes through
`SpellAbilityRestriction.canPlay`, which itself calls `sa.canCastTiming(...)`
(SpellAbilityRestriction.java:560) — so sorcery-speed timing is enforced *by the legality
check itself*, and `canPlayAI` is never consulted anywhere in the bridge path.

Rig: 30 Island / 15 Brainstorm / 15 Prodigal Sorcerer against a 60-Forest opponent, our
seat casting only at its own main and never activating, so resources are guaranteed.

| their END_OF_TURN, 107 frames | |
|---|---|
| pass-only menus | **3 (2.8%)** |
| frames offering the instant | **104** |
| frames offering the activation | **100** |
| avg untapped Islands / ready activators | 15.7 / 7.9 |

```
turn 13, their END_OF_TURN — 6 untapped Islands, 3 ready Tims, 4 Brainstorms in hand
menu: [pass, Brainstorm, Brainstorm, Brainstorm, Brainstorm,
       Prodigal Sorcerer, Prodigal Sorcerer, Prodigal Sorcerer]
```

### What the wall actually is

Same jar, same campaign decks (`decks-deploy`, 4 seeds × 2 games), **only the host's own-main
policy differing**:

| their-turn priority frames | spend at our main | hold |
|---|---|---|
| frames | 722 | 592 |
| **pass-only** | **722 (100.0%)** | **157 (26.5%)** |
| candidates scanned | 1226 | 970 |
| **offered** | **0** | **721** |
| rejected — timing | 802 | 8 |
| rejected — unaffordable | 424 | 173 |
| rejected — no legal target | 0 | 68 |
| *their END_OF_TURN* pass-only | 65/65 (100%) | 16/53 (30.2%) |

The window is destroyed **upstream, at our own main phase**, by spending the mana and
tapping the permanents. By the time the window arrives there is nothing left to defer.
Note the split: in the spending arm most rejections are *timing* (a board of
sorcery-speed activations — loyalty abilities, equip — that are simply not legal at an
opponent's turn), and every instant-speed-legal option that remains is *unaffordable*.

This is why every pilot-side window seam nulled: a deferral seam that fires **at the
window** is asking a question whose answer was already fixed one phase earlier.

### The instrument

Rather than widen a menu that is already complete — widening it would mean offering
illegal actions — every `priority` ask now carries the census:

```json
"menuDiag":{"candidates":37,"offered":0,"rejectedTiming":22,
            "rejectedUnaffordable":15,"rejectedNoTarget":0}
```

and the per-seat counters gain `menu.ourTurn.*` / `menu.theirTurn.*` totals
(`frames`, `passOnlyFrames`, `candidates`, `offered`, `rejectedTiming`,
`rejectedUnaffordable`, `rejectedNoTarget`) in the `instruments` map. A quiet window is now
attributable at the frame instead of inferred.

Cost: five ints per priority ask, no change to menu contents, no measurable wall-time
change. **Bridged-seat decisions are unchanged by this commit** — the menu is byte-for-byte
the same set of options; only the diagnostic block is new.

## Protocol v2.4 — optional extra costs reach the host (B1, the Chalice frame)

**The host voted on a free spell and was billed ten mana.** Everflowing Chalice reached
the ballot as `cost.mana "{0}"`, `cmc 0`, `optionalPaid []`, `x.has false`; the pilot chose
it as a zero-mana cast, and Forge then multikicked it for 10 of our 12 mana sources,
stranding Mana Drain. No pricing seam could fire — there was no cost in the ballot to price.

### Where the decision actually lives

Not `chooseOptionalCosts`, and not `announceRequirements`. Multikicker, Kicker, Casualty,
Conspire and Offspring are **extra keyword costs**, applied by
`GameActionUtil.addExtraKeywordCost` during `handlePlayingSpellAbility`, and every one of
them routes through **`chooseNumberForKeywordCost`** — `addKeywordCost` is a non-abstract
convenience that calls it with `max = 1`. One choke point covers the whole family.

`chooseOptionalCosts` is a different thing and is only ever called from
`ComputerUtilAbility.getOriginalAndAltCostAbilities`, i.e. **while the priority menu is
being built** — and it drops the unkicked ability from the menu when the answer is
non-empty.

### `keywordCost` — a new ask kind

Issued when the engine asks how many times an optional extra cost is paid:

```json
{"kind":"keywordCost","keyword":"Multikicker:2","keywordTitle":"Multikicker {2}",
 "prompt":"Choose Amount for Multikicker: {2}",
 "cost":{"rendered":"{2}","mana":"{2}","cmc":2},
 "min":0,"max":0,"engineMax":-1,
 "ability":{…},"state":{…}}
```
Answered `{"value": n}`.

`max` is the **affordable** ceiling (mana left after the base cost, divided by the repeat's
own mana cost), because the engine's own bound is `Integer.MAX_VALUE` for Multikicker and
that is not a range a host can price against. `engineMax` reports the engine's bound, `-1`
when effectively unbounded. A value outside `[0, max]` is a counted refusal.

### `optionalCosts` — a new ask kind, and the menu change

While the menu is being built the bridge **declines** optional costs, so the unkicked
ability survives, and adds `GameActionUtil.addOptionalCosts(sa, …)` as its **own menu
entry** with its true total cost. The host votes on the cost instead of inheriting it; the
two entries are distinguishable by `optionKey` and `cost.optionalPaid`. Outside menu
construction the kind is published as a normal ask (`menu` of bundles with their mana,
answered `{"choices":[index,…]}`).

### `cost.pendingKeywordCosts` — ballot honesty

Every ability now publishes the optional extra costs that will be asked about *after* it is
chosen:

```json
"pendingKeywordCosts":[{"keyword":"Multikicker:2","title":"Multikicker {2}",
                        "repeatable":true,"cost":"2"}]
```

These never appear in `optionalPaid` or in the rendered mana cost, which is exactly why a
`{0}` ballot looked free. The keyword line is republished verbatim rather than re-parsed,
so it cannot disagree with the engine about what the cost is.

### Verified — same jar, same seed, only the answer differs

Rig: 34 Island / 16 Everflowing Chalice / 10 Mana Leak, our seat bridged, seed 4100, 2 games.

| `keywordCost` answered | Chalice charge counters by fid | kicks paid |
|---|---|---|
| `delegate` (Forge decides — the inherited path) | `{6:0, 7:0, 12:1, 14:2, 15:0}` | **3** |
| `{"value":0}` (host votes) | `{6:0, 7:0, 12:0, 14:0, 15:0}` | **0** |

6 `keywordCost` asks, 0 refusals. Voting 2 instead produces Chalices carrying
`{"CHARGE":1}` and `{"CHARGE":2}` — the counters equal the votes, clamped where no mana was
free. The payment matches the ballot.

New instruments: `keywordCost.answered`, `keywordCost.paid`.

## Protocol v2.3 — announced X on a `priority` answer (D-1)

**The bridge never transmitted X.** `chooseSpellAbilityToPlay` returned the chosen
`SpellAbility` with `XManaCostPaid` untouched, i.e. 0, because Forge announces X inside
`canPlayAI` — a path a host-chosen ability never goes down. Every {X} spell the bridged
seat cast was announced at **X=0**: Walking Ballista arrived as a 0/0 and died to
state-based actions on the adjacent event; Forth Eorlingas! made zero tokens. Measured by
the morning lane at 0 Ballista casts with X≥1 across 12 games.

### The answer field

`AnswerPriority` may now carry `x`:

```json
{"type":"answer","id":42,"choice":2,"x":1}
```

`x` is the **value of X**, not mana spent on X — Forge multiplies by the {X} symbol count
itself (`ComputerUtilMana.calculateManaCost`). It is applied with `setXManaCostPaid` before
targeting and before the affordability re-check, because an X spell's legal target count
can be derived from X and `canPayCost` has to price the announcement. The payment path then
reads it back through `calculateAmount(host, "X", sa)`, so setting it here *is* the
announcement.

### The ability's `x` block gains two fields

| field | meaning |
|---|---|
| `x.max` | mana available for X: affordability estimate − the cost's fixed pips. **Not** divided by the {X} symbol count. Unchanged from v2.0 — the TS lane's `d1.xClamped` instrument is calibrated on this figure. |
| `x.symbols` | how many `{X}` the mana cost carries. Walking Ballista is 2. |
| `x.maxAnnounce` | `x.max / x.symbols` — the largest X actually announceable, and the value the JVM clamps to. A host that trusts this cannot over-announce. |

A `{X}{X}` card with 2 mana for X announces **X=1**, not X=2. Publishing both figures
rather than redefining `x.max` keeps the existing instrument readable.

### Guards

- **Only when the cost has {X}** (`costHasX() || hasXInAnyCostPart()`). An `x` on an
  ability with no X is a decode mismatch: counted as a refusal on
  `chooseSpellAbilityToPlay` and delegated, never silently dropped, so a host-side
  menu-indexing bug cannot hide behind a field the JVM ignores. Verified: answering `x` on
  every priority choice produced 50 refusals and 1 legitimate announcement.
- **Clamped** to `[max(0, xMin), maxAnnounce]`, where `xMin` is the cost's stated minimum
  (CR 601.2b).
- **Instrumented** in a new `instruments` map on the per-seat counters —
  `x.announced`, `x.announcedNonZero`, `x.clampedByJvm`. Deliberately *not* in `calls`:
  that map is the decision-surface measurement and anything else in it distorts
  `totalCalls`.

### Verified

`decks-deploy` seed 2004, 4 games, our seat bridged. 3 X announcements, all non-zero, 0
refusals. Walking Ballista at turn 5 MAIN1 with `{X}{X}` and
`x:{has:true,min:0,max:2,symbols:2,maxAnnounce:1}` announced **X=1** and appears on the
battlefield as a **1/1 carrying `{"P1P1": 1}`**, alive on turns 5,6,7,8,9,10 — where it
previously died on the event adjacent to its own arrival.

Note the *cast event label* still renders `Walking Ballista - Creature 0 / 0 (X=0)`: that
string is Forge's stack description built when the spell goes on the stack, before payment
writes X back into it. The board state is the authority, and it says X=1. Do not re-open
D-1 on the label.

## Protocol v2.2 — attack requirements on an `attackers` ask (P1 residual)

`CombatUtil.validateAttackers` rejects a declaration that leaves more attack requirements
unmet than the best legal attack would (CR 508.1d). The host could not see those
requirements at all, which produced 4 residual `declareAttackers` refusals in the P1
panel — every one of them **Goblin tokens under Goblin Rabblemaster** ("other Goblin
creatures you control attack each combat if able").

Reading `keywords` cannot fix this: Forge models must-attack as a **static ability**
(`StaticAbilityMustAttack`, mode `MustAttack`), never as a keyword string. And the cards it
bit on were *tokens*, which have no cube entry to read text from — the same token-blindness
class `minBlockers` closed for the block step.

New fields on an `attackers` ask, all keyed by attacker fid:

| field | meaning |
|---|---|
| `mustAttack` | `{attackerFid: [defenderId, …]}` — defenders this attacker is required to attack |
| `mustAttackAny` | `[attackerFid, …]` — required to attack, no specific defender (the requirement covers every legal defender) |
| `requiresAlso` | `{attackerFid: [otherAttackerFid, …]}` — other attackers whose *not* attacking counts as a violation (`AttackRequirement.getCausesToAttack`) |
| `bestAttackViolations` | the ceiling: a declaration is legal iff its own violation count is `<=` this. Non-zero is how the host distinguishes a hard requirement from one it may leave unmet. `null` if enumeration failed — absent rather than wrong, and a host must not read a missing ceiling as zero. |

Sourced from `Combat.getAttackConstraints()`, i.e. the same object `validateAttackers`
judges against. `getLegalAttackers()` searches the attack space, so it is only called when
at least one requirement exists; with none, the ceiling is trivially 0.

Verified: 13 of 13 `attackers` asks in a Rabblemaster pod carried a non-empty `mustAttack`,
e.g. `{"23":[1],"124":[1],"126":[1]}` with `mustAttackAny:[23,124,126]` and
`bestAttackViolations:1`, where 124/126 are **Goblin Tokens** — the exact objects the P1
refusals came from. 0 `declareAttackers` refusals.

## The AI search budget (`aiCanUseTimeout` / `aiTimeoutSec` / `simMaxDepth`)

Sim-mode runs were dying with `OutOfMemoryError` at `-Xmx4g`.

### What the flag actually gates

`Game.AI_CAN_USE_TIMEOUT` is read in exactly **one** place in the whole codebase:
`AiAttackController`'s `CompletableFuture.allOf(...).completeOnTimeout(...)` over the
forced-attacker evaluation. It is **not** a budget on the simulation search:

- `SpellAbilityPicker.chooseSpellAbilityToPlayImpl` has **no wall-clock bound at all**.
  It is bounded only by `SimulationController.maxDepth` (3) and the candidate count, and
  it recurses through `GameSimulator.simulateSpellAbility` → `GameCopier.copyGameState`
  (a full `CardFactory` rebuild per card) once per candidate per level.
- `AiController.chooseSpellAbilityToPlayFromList` — the *heuristic* path — applies
  `future.get(game.getAITimeout(), SECONDS)` **unconditionally**, not gated by
  `canUseTimeout()`. So `AI_TIMEOUT` was already bounding the heuristic arm even while the
  bench forced `AI_CAN_USE_TIMEOUT` off.
- `usesFullSimulation()` routes `chooseSpellAbilityToPlay` straight to the sim picker,
  bypassing that bounded path entirely.

So the bench's unconditional `AI_CAN_USE_TIMEOUT = false` did not remove a budget the
simulation search had — the search never had one. The knobs below exist because a run
should be able to choose its budget, and `simMaxDepth` is the lever that actually bounds
the nested copy explosion.

### Config

| key | type | default |
|---|---|---|
| `aiCanUseTimeout` | bool | `true` when any seat simulates, else `false` |
| `aiTimeoutSec` | int | 5 (Forge's own `Game.AI_TIMEOUT` default) |
| `simMaxDepth` | int | 3 (`SimulationController`'s own default) |

Resolution rule, in order: **explicit config value** > **`true` if any seat has simulation
enabled** > **`false`**. `aiTimeoutSec` and `simMaxDepth` are taken from config or left at
Forge's defaults; both are applied whether or not a seat simulates, so a heuristic arm can
be given a tighter `aiTimeoutSec` too.

`simMaxDepth` is exposed through a new static default on `SimulationController`
(`setDefaultMaxDepth`) read at construction. Forge already had a `SimulationController(Score,
int)` constructor; only `SpellAbilityPicker` used the no-depth one, so this is a four-line
change and Forge's own behaviour is unchanged when nothing sets it. There is **no** node or
time budget in `GameSimulator`/`SimulationController` to expose — adding one would mean
threading a counter through the recursion, which is a real change to Forge's AI and out of
scope here.

### Measured: the timeout does not prevent the OOM

Two games, drafted cube decks, `simSeats:[1]`, `-Xmx4g`:

| run | outcome |
|---|---|
| `aiCanUseTimeout:false`, depth 3 (the old unconditional setting) | wedged at the heap ceiling, no game finished in 10 min, killed |
| `aiCanUseTimeout:true`, depth 3 (the new default) | game 1 finished (11 turns); **`OutOfMemoryError` during game 2**, 5m39s in |
| `aiCanUseTimeout:true`, `simMaxDepth:2` | both games finished, 176s total, no OOM (155.1s + 16.2s); peaked at 4.4GB and GC'd back |
| `aiCanUseTimeout:true`, `simMaxDepth:1` | **both games finished, 40s total, no OOM** (25.3s + 9.2s) |

The failing stack is inside a *single* `SpellAbilityPicker.evaluateSa` →
`GameSimulator.simulateSpellAbility` → `resolveStack` → `Game.copyLastState` →
`CardCopyService.getLKICopyList`, i.e. the simulation's own per-resolve LKI copying, which
no wall-clock flag touches. **The flag was not the cause.** Budget a sim arm with heap and
`simMaxDepth`, not with `aiCanUseTimeout`.

`simMaxDepth` still defaults to Forge's 3, deliberately: search depth *is* the opponent's
strength, and silently weakening it would change the policy identity the benchmark is
measuring. A sim arm must choose — raise the heap (the TS harness's `--heap 12g` works) or
lower the depth — and record the choice in the manifest.

### Protocol v2.10 — `frameFile`: start a game from a position, not from a shuffle

Additive. A config without `frameFile` behaves exactly as v2.9 (verified: two games, no
`frameApplied`, normal outcomes).

### The config key

```json
{"decks":["/abs/p0.dck","/abs/p1.dck"],"games":1,"seed":7,
 "seats":{"0":"forge","1":"forge"},"aiCanUseTimeout":false,
 "frameFile":"/abs/frame.txt","timeoutSec":180}
```

`frameFile` names a file in `forge.game.GameState`'s own dev-mode/puzzle text format. It is
read once at startup; `hello` echoes `frameFile` and its `frameSha256` so a host can prove
the JVM read the bytes it sent. Per game the text is re-parsed (the parsed model's id maps
are consumed by `applyToGame`) and installed through the **two-argument**
`Match.startGame(game, hook)`, whose hook `PhaseHandler.setupFirstTurn` runs after the
untap of turn 1 and before priority is ever offered — the seam every GUI puzzle screen
uses. The normal opening (draw seven, mulligan) still happens and is then overwritten:
`applyToGame` clears every zone first, so **a frame that omits `pNlibrary=` decks that
seat on its next draw.**

### `frameApplied`

```json
{"type":"frameApplied","game":"g1","frameFile":"…","frameSha256":"…","dump":"turn=7\n…"}
```

`dump` is `new GameState().initFromGame(game).toString()` taken immediately after the
install: **Forge's own account of what it believes it installed.** This is not decoration.
`processCardsForZone` SKIPS a card whose name/set it cannot resolve and only prints to
stderr (`GameState.java:1279-1281`), so without the round-trip a silently dropped card
reads to the host as a loss. Diff it against what you sent, every run.

### The threading hazard, and why the install renames its thread

`GameState.applyToGame` goes through `GameAction.invoke`, which runs the install inline
only when `ThreadUtil.isGameThread()` — i.e. when the current thread's name starts with
`Game`. Otherwise it posts to an **unbounded cached pool**. The bench's game runs on
`TimeLimitedCodeBlock`'s `pool-N-thread-1`, so the install would be handed to another
thread and race the game loop `startGame` is about to enter. That is not theoretical: the
first run of this feature came back with a `frameApplied` dump showing an untouched
opening hand and an empty seat-0 board. The hook therefore renames the calling thread to
`Game-frame-install` for the duration and restores it in a `finally`. An install that is
not synchronous is not reproducible from a seed.

### What the format cannot carry

The stack; and duration-limited continuous effects with no permanent behind them. The
mtgx-side exporter (`mtgx:tools/forge/from-frame.mjs`) refuses such a frame by name rather
than exporting a position nobody was ever in.

**Tokens are no longer on that list** — see the next section.

### Tokens round-trip (2026-08-23)

`GameState` had three ways to write a token and only one of them was used, which is what
the standing *"TODO: Make sure Game State conversion works with new tokens"* in
`processCardsForZone` was about. `addCard` wrote every token as `t:<TokenInfo>`, and a
`TokenInfo` is a **body, not a card**: name, P/T, colours, types and keywords, with no
abilities and no static abilities. Measured consequences, both reproduced headless before
the change:

- an Urza's Saga Construct (`c_0_0_a_construct_total_artifacts`, a printed **0/0** whose
  size is `S:Mode$ Continuous | AddPower$ X …`) installs as a literal 0/0 and CR 704.5f
  puts it in the graveyard before anyone gets priority — the dump comes back with the
  token simply **gone**;
- a Food installs with no sacrifice ability;
- `ColorSet` has no `toString()` override, so the writer emitted
  `Color:forge.card.ColorSet@1f2e3d` and **every** token read back colourless;
- `Types:`/`Keywords:` were `null` when the field was absent (NPE inside the install) and
  `[""]` when it was present-but-empty.

`addCard` now writes a token in the strongest form that reads back:

| form | when | what survives |
|---|---|---|
| `T:<script>\|Set:<code>` | the token came from a token script (recovered from the card's own image key, `t:<script>\|<SET>…`, and only when `TokenDb.containsRule` confirms it) | everything — it is rebuilt from the database entry |
| `<Name>\|Set:..\|Art:..\|IsToken` | the token is a COPY of a real card (Kiki-Jiki, Splinter Twin, Metamorph) — no `PaperToken`, but a `PaperCard` | every ability, rebuilt from the card database; `IsToken` is an existing reader flag |
| `t:<TokenInfo>` | neither — an unscripted synthesized body | name/P/T/colours/types/keywords, as before |

The reader gained the matching half: the `t:` lane now tries
`TokenInfo.makeScriptedToken` first, so a `t:` written by an older build (or by a shipped
`.pzl`) whose `Image:` names a real script is rebuilt from that script instead of as a
body. `T:` with an unusable `Set:` no longer throws out of the whole install.

The point is **idempotence**: `initFromGame` -> `applyToGame` is now a fixed point for
tokens, which is what makes the `frameApplied` dump a usable acceptance test.

Verified on `mtgx:docs/qa/owner-queue/inbox/2026-08-23T05-43-10-loss-storm-tendrils-vs-ug`
action 337 (a Food and three Urza's Saga Constructs across both seats), 20 reseeded runs:
**20/20 installed, 20/20 round-trip clean, 84 cards sent and 84 in Forge's own dump**, with
the tokens echoed as `T:c_a_food_sac|Set:BIG` and
`T:c_0_0_a_construct_total_artifacts|Set:BIG|SummonSick`. A hand-built copy token
`Pestermite|Set:LRW|IsToken|Tapped|Counters:P1P1=2` came back as
`Pestermite|Set:LRW|Art:1|IsToken|Tapped|Counters:P1P1=2`.

**What still cannot be carried, and must be refused upstream:** a token with a delayed
"sacrifice/exile it at the beginning of the next end step" rider. Both faithful lanes
rebuild the permanent from a database entry and neither carries a delayed trigger, so a
Kiki-Jiki copy exported this way would be a *permanent* copy. `from-frame.mjs` refuses it
by name (`frame-has-ephemeral-token`) rather than dropping the rider silently.

### Protocol v2.12 — monarch, the initiative, and dungeons round-trip (2026-08-23)

Additive to the **position format only**; no wire message changed, and a 2.11 frame file
installs byte-identically because every new key's absence is its old meaning.

`grep -i "monarch\|initiative\|dungeon" GameState.java` used to return nothing, which is
why `from-frame.mjs` refused those frames outright. On the mtgx owner corpus that was the
**second-largest blocker after the stack**: `frame-has-initiative` 355 frames and
`frame-has-dungeon` 355 frames, one tape and one card
(`2026-08-22T20-19-35-win-kiki-twin-vs-ug`, Undermountain Adventurer).

| key | scope | meaning |
|---|---|---|
| `monarch=p0` | game | CR 724. Written only when someone is the monarch. |
| `initiative=p1` | game | CR 725. Same. |
| `T:<script>\|Set:<code>\|CurrentRoom:<Room>` | a card in `pNcommand=` | CR 309.4 — the dungeon and the room the player is in. |
| `p0completeddungeons=undercity,undercity` | player | CR 309.3 — dungeons finished, by token script. |

Four things are worth knowing before touching this again.

**1. The dungeon was already being written, and was silently unreadable.** A dungeon is a
`GamePieceType.DUNGEON` card built from a *token script* (`VentureEffect.getDungeonCard`),
but `Card.isToken()` is false for it, so `addCard` took the paper-card lane and wrote
`Undercity|Set:CLB|Art:0`. There is no such entry in the card database, so
`processCardsForZone` printed to stderr and **skipped** it: the dungeon vanished and the
player read back as never having ventured. It now takes the `T:` lane, and a dungeon whose
script cannot be recovered throws instead of falling through to `IsToken` or
`t:<TokenInfo>` — neither of which can carry the room triggers.

**2. `CardFactory.getCard` hands back a TOKEN.** `cp.isToken()` is true for the
`PaperToken` a dungeon is built from, so the reader must re-stamp `GamePieceType.DUNGEON`
(exactly as `VentureEffect` does) or the dungeon ceases to exist in the command zone the
moment state-based actions run.

**3. An unknown room name fails closed.** Forge's `RoomName$` values are not display
names and are not the exporter's: Undercity's sixth room is `Archive`, while the card, the
`K:Dungeon:` line and mtgx all call it *Archives*. A name no trigger answers to would leave
`currentRoom` set to a string that reads downstream as "not in a dungeon" — a **silent room
reset**, the exact failure mode the campaign forbids. `CurrentRoom:` is therefore checked
against the script's own `RoomName$` params and throws when it does not match.

**4. The designations go on after the zones and while triggers are still suppressed.**
`setupPlayerState` empties the command zone, which is where the monarch/initiative effect
card lives, so `applyDesignations` runs after the per-player loop. It must also run *inside*
the `setSuppressAllTriggers(true)` window, because `GameAction.takeInitiative` fires
`TriggerType.TakesInitiative` and the initiative effect's own trigger is *"whenever you take
the initiative … venture into Undercity"* — installing a position would otherwise advance
the dungeon room it had just installed. Both designations are **cleared first**: a position
that does not name a monarch is a position with no monarch, and `game.monarch` left pointing
at a player whose effect card was just wiped is a designation with nothing behind it.

The set code is the designation's *art* and the format does not carry it;
`CardEdition.UNKNOWN_CODE` makes `StaticData.getOtherImageKey` fall through to its scan over
every edition. (Note `Player.getMonarchSet()` is inverted upstream — `monarchEffect == null
? monarchEffect.getSetCode() : null` — and always returns null; it is not on this path.)

Verified as a fixed point on mtgx tape `2026-08-22T20-19-35-win-kiki-twin-vs-ug` action 262
(p1 has the initiative and is in Undercity's Secret Entrance): see the mtgx-side report.

### Turn-based actions and `activephaseadvance`

`applyGameOnThread` calls `PhaseHandler.devModeSet`, which sets the phase **without**
running its `onPhaseBegin`. Forge declares attackers inside
`onPhaseBegin(COMBAT_DECLARE_ATTACKERS)`, so a frame installed *at* that phase never gets
an attack declared — it is simply given priority in an empty combat. The format's own
escape is `activephaseadvance=`, which runs `devAdvanceToPhase` at the end of the install.
A pending-combat frame is therefore installed at `MAIN1` with
`activephaseadvance=COMBAT_DECLARE_ATTACKERS`; no priority is offered in the phases
skipped, so the board stays exactly as exported and the only thing Forge is asked is the
declaration.

## Determinism

`hello` carries `deterministic`, which is `!aiCanUseTimeout`. A wall-clock bound is not
reproducible from a seed: the same seed and decks can diverge run to run because the search
abandons at a different point under different machine load. **A simulation arm is
therefore not seed-deterministic**, and that is inherent to the tier, not a bench defect —
a manifest must record `aiCanUseTimeout`, `aiTimeoutSec` and `simMaxDepth` alongside the
seed, and paired-seed comparisons across a sim arm need resampling at the pairing, not the
game. Setting `aiCanUseTimeout:false` restores determinism at the cost of an unbounded
search.

Resolved values are echoed in **`hello`** (`simulationSeats`, `aiCanUseTimeout`,
`aiTimeoutSec`, `simMaxDepth`, `deterministic`) and mirrored in the `seats` message.

### Working recipe

`-Xmx4g` per worker: `simMaxDepth:1` is the only depth that is comfortably fast
(2 games in 40s). `simMaxDepth:2` survives but the first game took 155s and the JVM
peaked at 4.4GB, so it has no headroom on a loaded box. Depth 3 needs more heap — the TS
harness's `--heap 12g` is the right lever there, and `--workers 3` at 12g each needs a
machine that can take 36GB.

### Simulated games never reach the host

`GameCopier.clonePlayer` keeps the existing `LobbyPlayer` whenever it is a
`LobbyPlayerAi` — and `LobbyPlayerBridge` is one — so **every copied game inside the
simulation search builds a real `PlayerControllerBridge`**. Left alone, a `bridge` seat
would send `ask` messages about hypothetical positions the host cannot distinguish from
the real game, and a `null` seat would inflate the decision-surface counters with search
internals.

`BenchSession.getLiveGame()` holds the one real `Game` for the current game; the controller
compares `getGame()` against it by identity and, inside a copy, both suppresses counting
and behaves as plain `PlayerControllerAi`. This is why the null-mode instrumentation
figures elsewhere in this document are counts of decisions the seat actually made.

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

## Protocol v2.17 — the zone-change asks reach the host at last

`PlayerControllerBridge.chooseSingleCardForZoneChange` and `chooseCardsForZoneChange` were
`count(); return super.…` — **counted and delegated unconditionally**. The counter is in
every bench manifest and reads **`chooseSingleCardForZoneChange: 1072` per 384 games**
against `chooseCardsForZoneChange: 0`, so roughly a thousand library searches a bench were
decided by `ChangeZoneAi` while the bridged seat watched: every tutor, every fetchland, and
**every Doomsday pile** (`Origin$ Graveyard,Library | ChangeNum$ 5`).

Both now take the `chooseCardsForEffect` shape — `bridged()` guard, one round trip, `null`
meaning *delegate* — behind a **new ask kind, `zoneChange`**, the first new kind since v2.0.

```
{"type":"ask","id":41,"kind":"zoneChange","protocolMinor":17,
 "game":"g3","seat":0,"state":{…},
 "title":"Select a card from your graveyard and library (2 / 5)",
 "min":1,"max":1,
 "destination":"Library","origin":["Graveyard","Library"],
 "changeNum":5,"chosen":1,"optional":false,"single":true,
 "menu":[{"fid":312,"name":"Gush",…},…],
 "ability":{…}}
```

Answer form is `cardsChoice`'s: `{"choices":[fid,…]}`, **Forge card ids, not menu
indices**, validated against `[min,max]` and against the fetch list before anything moves.
An out-of-range count, an unknown id or a duplicate is `refuse`d and the call falls back to
`super`, which is the pre-2.17 behaviour exactly.

Five fields beyond `cardsChoice`, each because the host cannot infer it:

| field | why it is on the wire |
|---|---|
| `destination`, `origin[]` | a fetch to HAND, to the BATTLEFIELD, to the GRAVEYARD and to the LIBRARY are four different decisions, and the printed `selectPrompt` does not reliably distinguish them |
| `changeNum`, `chosen` | `ChangeZoneEffect.allowMultiSelect` requires `!decider.getController().isAI()`, and this controller extends `PlayerControllerAi`. **So a five-card pile arrives as FIVE SEQUENTIAL SINGLE-CARD ASKS** off a `fetchList` one card shorter each time. Without the index a host re-derives a different pile at every pick. `chosen` is counted JVM-side against `SpellAbility` identity because the effect's loop is the only place the index exists |
| `optional`, `single` | `isOptional` is `!mandatory` at the call site and is what makes `min` 0; `single` says which of the two methods is asking (one card, or a `[min,max]` unordered set) |

`chooseCardsForZoneChange` is bridged although **it has never once been called on this
bench** — `allowMultiSelect`'s AI test excludes it, and `PlayerControllerAi`'s own body is
`return null` under the comment *"this isn't used"*. Its ceiling check is load-bearing all
the same: the caller loops `while (selectedCards != null && selectedCards.size() >
changeNum)`, so an over-long answer would be an infinite loop rather than a refusal.

`delayedReveal` is honoured on the bridged path exactly as `super` does (AI card memory is
written whichever side answers), and the fallback call passes `null` for it so a delegated
retry cannot reveal twice.

**Additive.** A pre-2.17 host has never heard of the kind, answers nothing, and the JVM
delegates — which is what it did for every one of those 1,072 calls before this change.

## Protocol v2.18 — the two remaining wire clamps

Two independent additions, each additive and each declinable. They are in one minor because
they are one finding: **the bridge decided, silently and in two different places, that a
decision was not worth publishing.**

### `orderZone` — the ORDER half of the pile

`PlayerController.orderMoveToZoneList` was `count(); return super.…`, the same shape one
method along from v2.17's pair, at **495 calls per 288 games**. It is the controller entry
point for `RearrangeTopOfLibraryEffect`, for `DigEffect`'s remainder, for
`ChangeZoneAllEffect`, for `ChangeZoneEffect`'s own `chosenCards` ordering, and for
`GameActionUtil.orderCardsByTheirOwners`.

**Doomsday's `SVar:DBDig` is a `RearrangeTopOfLibrary`.** So after v2.17 the host chose
WHICH five cards and Forge's scry heuristic still chose in WHAT ORDER they were stacked —
which for a five-card library is the whole plan.

```
{"type":"ask","id":58,"kind":"orderZone","protocolMinor":18,
 "game":"g3","seat":0,"state":{…},
 "destination":"Library","count":5,"topFirst":true,
 "menu":[{"fid":312,"name":"Gush",…},…],
 "ability":{…}}
```

Answer form is `{"choices":[fid,…]}` and it must be a **PERMUTATION of the menu** — every
card exactly once, nothing added and nothing dropped. A wrong arity, a duplicate or an
unknown id is `refuse`d and the call falls back to `super`, which is pre-2.18 behaviour
exactly. A `null` answer delegates, as does an unbridged session and any list shorter than
two.

**The order on the wire is MOVE ORDER and the bridge transforms nothing.**
`orderMoveToZoneList`'s own javadoc: *"The cards will be returned in the order that they
should be moved, one at a time, to the given zone and position. Be aware that when moving
cards to the top of a deck, this will be the reverse of the order they will ultimately end
up in."* `RearrangeTopOfLibraryEffect` walks the returned list calling
`moveToLibrary(next, 0)`, so the LAST element is the card drawn first.

`PlayerController.orderedMoveToTopOfLibrary(destinationZone, source)` is the predicate that
says when that reversal applies — a deck destination whose `LibraryPosition` /
`RevealedLibraryPosition` is non-negative or absent — and it is published as **`topFirst`**
rather than applied here. Both of Forge's own controllers build a top-first list and
reverse at that gate (`PlayerControllerHuman` after its user has ordered,
`PlayerControllerAi` after its scry sort). Publishing the fact instead of applying it keeps
exactly one reversal in the system, on the side that holds the pile. A bridge that also
reversed would look identical in the wire log and be wrong in the game.

### `manaAbilities` — a parallel channel on the `priority` body

`legalSpellAbilities` skips every `sa.isManaAbility()`, deliberately and correctly: Forge
plays those during cost payment, and offering them at priority invites a non-terminating
loop. The host, however, builds `CardView.abilities` **out of that menu**, so on this bench
a permanent's mana abilities are not in its ability list at all. Measured host-side over one
384-game corpus: `isManaAbility` appears **57,053 times on the wire and is `false` every
time**, and **0 of 118,481** menu options is a mana ability. Grim Monolith published exactly
one ability — `"{4}: Untap this artifact."` — and never the `"{T}: Add {C}{C}{C}"` that
makes the loop.

```
"manaAbilities":[{"fid":118,"source":"Grim Monolith","api":"Mana",
                  "isManaAbility":true,"payCosts":"T",
                  "description":"{T}: Add {C}{C}{C}.", …}, …]
```

`encodeSpellAbility` entries, the same encoding the menu uses, for every card the bridged
seat controls whose `getManaAbilities()` is non-empty. **`menu` is byte-identical to
v2.17** — this is beside it, not inside it — so every index a host answers with means what
it always meant, and a host that ignores the key cannot behave differently. Scope is our own
battlefield (the reader this exists for walks our permanents, and enumerating the
opponent's would publish hidden information for nobody). `setActivatingPlayer` is not
called: this is a read of the card, not a preparation of an ability. A per-card enumeration
failure is swallowed, so the array is shorter and never wrong.

**Additive on both counts.** A pre-2.18 host sees an unknown kind it never answers (the JVM
delegates) and an unknown key it never reads.

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
