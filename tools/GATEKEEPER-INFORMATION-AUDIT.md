# Cemetery Gatekeeper information-set witness — 2026-09-10

Status: real-controller development reproduction, **not a played benchmark or
an estimate of historical impact**. No AI policy or live runtime was changed.
Base Forge `1bee5179b099bb7abfa128d08afbc746e4633b66`.

## Result

`GatekeeperInformationEngineSmoke` loads the real Cemetery Gatekeeper script,
constructs its `TrigExile` ability, and invokes the actual stock
`PlayerControllerAi.chooseSingleCardForZoneChange` callback used by this
`Hidden$ True` zone-change ability. Both legal selectable graveyard cards are
public. The actual controller and `ChangeZoneAi` implementations come from the
surviving pinned jar, not a test double or copied preference function.
`ChangeZoneEffect`'s real hidden-origin resolver obtains all graveyards when
neither hand/library nor `DefinedPlayer` is specified (lines 1009–1011), then
calls this controller callback (line 1206); the fixture options match that
production candidate set.

| Controlled comparison | Same visible decision | Same registered deck | Actual Default choice |
| --- | --- | --- | --- |
| Opponent hidden hand/library has land vs creature majority | Yes | No | Mountain vs Savannah Lions |
| Same hidden multiset, different hand/library partition | Yes | Yes | Mountain in both |
| Same deck, hidden library card swapped with opaque face-down exile card | Yes | Yes | Mountain vs Savannah Lions |

The last comparison holds the public cards and their IDs, hand/library sizes,
opaque exile object ID, visibility permission, registered opponent deck
(`3 Mountain + 3 Savannah Lions`), and decision RNG seed fixed. Only a hidden
library identity and hidden exile identity are exchanged. The decider has no
permission to inspect those cards. Independent public-object assertions avoid
relying on the encoder's omission of wholly hidden exile objects. The opposing
hand is also identical in this pair. Three fixture-local seeds reproduce each
comparison: 15 actual isolated AI choices, no matches or trained interventions.

These are synthetic legal-zone/permission snapshots, not reconstructed full
game histories. They prove dependence of this callback on information absent
from its current seat-visible observation, including a fixed-deck case; they
do not prove an outcome advantage, full-policy information purity, or a
particular historical game divergence.

The source (`forge-ai/.../ability/ChangeZoneAi.java`,
`doExilePreferenceLogic`, `MostProminentOppType`) explicitly assumes an open
opponent decklist. With all cards outside hand/library individually known,
their combined multiset can indeed be inferred from that decklist. Therefore
the first witness alone is **not** evidence of improper play under an open-list
contract. The opaque-exile witness shows why even open lists are insufficient
when outside-zone identities are not known. No claim of unqualified
"cheating" is warranted.

## Benchmark scope and current information contract

The primary and surviving gen64e `public/data/mush.json` both contain 540 cards,
have SHA-256
`754d389152b6af89addaa7532131aa5f5b526b097d9f588cffcb5189025fd934`, and contain
**no Cemetery Gatekeeper**. The four gen64e bridge manifests under
`gen64e/data/forge-external-gen64e-0908/a/g64e{C,D,E,F}-bridge/run/manifest.json`
declare that cube hash. Their `--decks` paths point to
`pilot2-v2/data/forge-external-gen64b-0904/decks-ext{C,D,E,F}`; all four present
directories were scanned, 48 `.dck` files each, and none names Gatekeeper.
This scopes this particular card out of those checked decklists; it does not
certify their provenance/completeness or all other privileged AI reads.

The Java `BenchMain` decklist loop sends each bridged seat only its own
registered decklist. The TS audit tree
`/Users/channel/Documents/GitHub/mtgx-worktrees/forge-eval-integrity-0909`
declares `DecklistMessage` explicitly seat-private in
`src/ai/forgeBench/protocol.ts`; `acceptDecklist` stores by `game:seat`,
`deckFor` reads that exact seat, and policy callsites use the current ask's
seat. The TS policy does not receive an opponent registered decklist through
this channel. The harness process knowing both decks to start the game is not
equivalent to exposing both lists to each policy.

## Go-forward experiment contract recommendation

1. Preserve **Default Forge as shipped** as a separately named opponent. Its
   information privileges are part of that estimand, not evidence of an
   equal-information comparison. Do not leak hidden identities into TS to
   imitate these privileges.
2. Define a separately versioned equal-information arm with an explicit
   observation/history boundary. Opponent decklists remain private unless a
   new open-list contract is intentionally selected for both policies.
   Open lists alone do not authorize inspecting unknown face-down exile,
   opponent hand location, or library order.
3. Before that arm is measured, enforce hidden-state metamorphic tests at real
   policy callbacks: identical permitted observation/history and replayed RNG
   must produce the same action. Classify unsupported policy reads explicitly;
   never silently substitute a heuristic or call the modified arm Default.
4. Apply the gate to the frozen benchmark's reachable card/decision scope.
   This out-of-cube witness motivates the audit but does not establish bias
   in the checked extC–F results. Frozen same-lineage outcome controls and the
   remaining bridge/payment/rules gates still determine whether new numbers
   can be trusted. No retrospective correction is computed from this fixture.

## Reproduction and evidence

Run through the installed shared Studio admission system (`--lane test`):

```sh
/bin/bash /Users/channel/.mtgx/admission-v1/runtime/admitted-check.sh \
  /Users/channel/Documents/GitHub/mtgx-worktrees/studio-admission-0910/tools/lib/arena-lock.sh \
  audit-gatekeeper-info-0910 --lane test --wait 300 -- \
  /bin/bash tools/test-gatekeeper-information.sh PINNED_JAR NEW_ARTIFACT_DIR
```

Pinned jar: surviving `gen64e/.../a/g64eC-bridge/run/forge-pinned.jar`, SHA-256
`d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c`.
Card script SHA-256:
`e09822241c5b743dcd3764589da78b2f94d10767c8a97bc1c8ba6b3af954d2aa`.
Each witness emits the complete seat observation, its digest, actual selection,
and clearly labelled private test facts into an isolated artifact log. Those
private facts are never supplied to the TS policy.

Preserved artifacts under `/Users/channel/runs/`:

- `2026-09-10-gatekeeper-information-v1/fixture.log`: compile failure from an
  incomplete overlay dependency list; no decisions executed.
- `2026-09-10-gatekeeper-information-v2/fixture.log`: passing first reproduction,
  before adding independent public object assertions and class origins.
- `2026-09-10-gatekeeper-information-v3/fixture.log`: **passed, exit 0**, clean
  committed source `e7d3830d8329e272714e61ef8fd232df1dacafc2`; all 15 actual
  decisions and nine comparison assertions passed. Both AI class-origin lines
  identify the pinned jar. Includes independent public object assertions.
