# Compact exact payment domain prototype — 2026-09-10

`RulesPaymentDomain` replaces a list of every payment plan with the complete
rules-assessed domain. It is an additive prototype, **not wired into production**.
The normal bridge and its existing version negotiation are unchanged.

## API and wire contract

`new RulesPaymentDomain(payer, ability)` obtains the existing rules-only
`PaymentSpace`; `request()` returns a defensive snapshot; `select(answer)`
returns the existing `PaymentWitness` for the exact supplied payment.

Request:

- `paymentVersion: "rules-payment-v4-domain"`;
  `representation: "token-shard-domain-v1"`; `complete: true`.
- `cost.mana`, `cost.x=0`, `cost.life` (action-only fixed life), and
  `cost.shards[]`. Each array index is a distinct required cost-shard slot.
- `lifeAvailable` is the current shared life budget; zero life payment remains
  legal even at negative life if the player has not lost (Platinum Angel).
- `sourceOptions[]` retains the prior source/ability/choice/output fields and
  adds `group`. Select zero or one option per group, in any explicit order.
  Selecting sources whose output remains unspent is permitted.
- Each output unit is addressed as `sourceOption.id + ":" + outputIndex`.
  `pool[]` has a distinct ID for every actual floating Mana object, plus color,
  source FID, and `persistent`, `combat`, `snow` traits. No floating provenance
  is collapsed, including equal-color tokens from the same producing ability.
- There is **no `menu`** and no count of materialized plans.

Host answer (alongside ordinary RPC envelope fields):

```json
{
  "paymentVersion": "rules-payment-v4-domain",
  "sourceOrder": ["s4-a19-o0"],
  "spend": [{"token": "s4-a19-o0:0", "shardIndex": 0}],
  "lifePaid": 0
}
```

Source/token IDs here are illustrative. `lifePaid` is action life plus the
life of **all** selected source activations, including deliberate surplus.
`select` requires every shard exactly once, each token at most once, legal
token color, selected producer, source-group exclusivity, and exact affordable
life total. It retains allocation and source order. Invalid versions, old plan
indices, delegation, unknown identities, coercible numeric strings, overflow,
partial payments, and duplicate use fail explicitly. It never chooses a payer.

The representation is linear in the assessed source/output/pool/shard domain;
validation is linear in the submitted witness. The upstream rules assessment
still has its own 32-source and search bounds and unsupported cost classes.
Those bounds have **not** been removed or relabelled as completeness. Removing
eager-plan limits does not by itself certify the full cube benchmark scope.

## Evidence and semantics

Admitted development run
`/Users/channel/runs/2026-09-10-compact-payment-domain-v2/fixture.log` exited 0:
50 checks, no matches. Earlier v1 passed 30 checks.

All 25 existing eager witnesses across Bolt/two Mountains,
Incinerate/three Mountains, Bolt/Lotus, and Bolt/Confluence+Mountain decode to
exactly equal old `PaymentWitness` records. The symbolic format also names the
individual interchangeable output slots from a single verified effectless
activation; it does not canonicalize floating tokens or distinct sources.

Twenty ordinary Mountains paying one red reproducibly exhaust the eager plan
limit. The compact request contains all 20 sources and no eager plan list.
Actual engine casts execute either only the explicitly chosen last source, or
all 20 sources in reversed order while deliberately leaving 19 mana floating.
No extra taps are pruned or chosen by Java. RNG and original ability/state/AI
memory tripwires cover domain creation and validation.

Actual pooled Mana objects from Mountain, Snow-Covered Mountain, Grand Warlord
Radha's persistent-mana script, and Avatar Roku's combat-mana script retain
their different traits. Four actual casts separately consume exactly each
requested token reference and retain the other three. Life-only Citadel,
zero-cost negative-life payment, shared source-life equality and overpayment,
request mutation, wrong color, and malformed witnesses are covered.

## Promotion work, deliberately not hidden by this prototype

1. Capture future source output traits immutably at assessment and compare them
   against actual emitted mana before spending. The initial prototype reads
   source traits at offer time, while the existing executor checks amount,
   color and restrictions but not persistence/combat/snow. A sacrifice/static
   transition can invalidate that expectation. Parent authorized this next
   scoped repair; this initial commit does not claim it solved.
2. Implement the TS symbolic adapter and exact capability negotiation. Keep
   native `planMana`/spend policy in charge. Color-count output alone does not
   choose among different floating or cross-source token provenance. If the
   selected token set is not uniquely implied, fail explicitly until an
   authorized policy semantics extension exists—never select the first token.
3. Connect the bridge's payment ask/decoder to this helper; prove actual
   priority→host selection→engine execution with protocol fault injections.
4. Run the full same-lineage null/probe and benchmark integrity gates before
   strength games. This representation is not whole-engine or policy parity.

Reproduce through installed `admitted-check.sh` with `--lane test`, invoking
`/bin/bash tools/test-compact-payment-domain.sh PINNED_JAR NEW_ARTIFACT_DIR`.
The runner refuses an occupied evidence directory and overlays only isolated
classes. It uses the surviving gen64e jar SHA-256
`d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c`.
