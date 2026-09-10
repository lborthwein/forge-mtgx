# Compact exact payment domain prototype — 2026-09-10

`RulesPaymentDomain` replaces a list of every payment plan with the complete
rules-assessed domain. The Java production bridge now uses it for the payment
ask and validation; hello, priority and payment agree on
`rules-payment-v4-domain`. The eager helper is retained only as an explicitly
versioned v3 differential reference, not called by the production bridge.

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
- Future source output traits are captured immutably in `SourceChoice.traits`
  during assessment; request encoding does not re-read a changed live source.
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

1. **Implemented and tested:** immutable future source output traits plus
   equality against every actual emitted Mana before spending. The follow-up
   run `2026-09-10-compact-payment-domain-v3/fixture.log` exited 0 with all 50
   compact tests plus 31 new trait checks. Real Rimefeather Owl makes a
   Mountain/Black Lotus snow; actual Lotus sacrifice preserves snow in the
   producing card's LKI even after the graveyard card loses the static snow
   effect. Removing Owl after selection preserves the old expected trait and
   rejects actual changed output. Independently forged persistent/combat/snow
   expectations each fail before payment mana is consumed. These are
   invalidating errors, not a rollback promise or fallback payer.
2. Integrate and verify the TS symbolic adapter and exact capability negotiation. Keep
   native `planMana`/spend policy in charge. Color-count output alone does not
   choose among different floating or cross-source token provenance. If the
   selected token set is not uniquely implied, fail explicitly until an
   authorized policy semantics extension exists—never select the first token.
3. **Java production hookup implemented and tested:**
   `2026-09-10-symbolic-payment-production-v2/fixture.log` exited 0 for all 13
   integrated fixture programs. The new production test passes 164 assertions:
   six actual channel→host answer→engine casts (20-source minimal and reverse
   surplus, mixed ordinary RW, mixed hybrid RG/RG, source life, action life),
   plus 13 malformed/delegated/legacy/duplicate/partial/source-order faults.
   Exact source order, output token identity, life, surplus, private engine
   receipt, and original priority request ID are checked. Rejected answers
   obtain no action receipt and leave original engine/RNG state unchanged.
   `ProductionPaymentDomainEngineSmoke ROOT --stdio CASE` uses a real external
   stdio host instead of the scripted test host; external TS execution is a
   separate pending check. Six `WIRE_PAYMENT_FIXTURE` rows retain actual
   priority/payment/answer envelopes, including seat-visible state and life.
4. Run the full same-lineage null/probe and benchmark integrity gates before
   strength games. This representation is not whole-engine or policy parity.

New legality finding retained, **not resolved by this hookup commit**: the
actual Citadel/Sol Ring priority request offers both the legal one-life variant
and an erroneous twice-applied two-life variant. The first production run v1
stopped on the fixture's ambiguous-card check. V2 explicitly selects the intended
one-life alternative to test payment execution, but does not certify that menu.
The v2 `life-only` raw exported request preserves both variants. A separate
general permission-enumeration repair and exact-menu regression are required.

TS native color-count payment also does not currently specify every possible
source-to-shard allocation (for example two equal-color sources paying a colored
plus generic bill). Do not relieve that explicit unsupported case by assuming
different `payingMana` orders or provenance are equivalent without a separate
rules/consumer proof.

Reproduce through installed `admitted-check.sh` with `--lane test`, invoking
`/bin/bash tools/test-compact-payment-domain.sh PINNED_JAR NEW_ARTIFACT_DIR`.
The runner refuses an occupied evidence directory and overlays only isolated
classes. It uses the surviving gen64e jar SHA-256
`d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c`.
