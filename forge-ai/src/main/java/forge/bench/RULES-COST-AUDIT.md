# Bounded benchmark rules-cost repair — 2026-09-10

Status: **first implementation, not whole-bridge certification**. Based on
Forge3544576919d55f1deb6fe8391a3c2ff446c30c0c. Existing stock/default AI behavior is
preserved; the new cost-decision overload is used only by the external bridge.
No browser runtime deployment, benchmark matches, or playing-strength claims.

`PlayerControllerBridge` now uses `RulesCostFeasibility` at initial and post-target
cost checks, not `ComputerUtilCost.canPayCost`. The latter intentionally combines
legality with AI strategy. The replacement returns PAYABLE, UNPAYABLE, UNSUPPORTED;
the last is a `BENCH_INTEGRITY_UNSUPPORTED` exception, never pass/delegate. Menu
construction exceptions log `BENCH_INTEGRITY_FAILURE` and rethrow instead of
returning a partial menu. The benchmark reader must reject the whole affected panel.

V1 handles ordinary mana shards, source tap and source counter costs, literal
generic increases, unrestricted floating mana and basic tap mana sources, including
coupled one-color multi-mana output such as Black Lotus. Multiple mana abilities on
one physical card are alternatives, not independent sources. Source restrictions
are queried on disposable ability copies; no printed source activator is changed.
The feasibility helper never asks an AI/controller for a payment plan, reads AI
reservation sets, or invokes policy affordability. Future Nether Void/Ward trigger
costs are not added to casting costs. Spending remaining loyalty is legal.

All nonempty independent optional-cost subsets are now emitted (bounded at eight
options; exceeding the bound throws instead of truncating). Discovery uses a copy
because Forge clears pips on its argument. Alternate-host optional discovery is
explicitly unsupported because it simulates static changes on a host.

## Evidence

16 actual-card checks pass against surviving gen64e's pinned d43b3b91 jar. Every
feasibility query is repeated three times under a throwing `MyRandom.next(bits)`
provider and equality checks for rules-state projection and all AI memory sets.
Cases: Bolt color availability; Nether Void versus Sphere; reserved basic sources;
cheap Lotus expenditure; Lotus cannot supply Esper Charm's three distinct colors;
last loyalty and overspending Liliana; unaffordable later Ward; explicit unsupported
Mana Confluence, Mana Reflection, Dismember; Thornscape Battlemage both singleton
kickers and combined kicker; unknown raises instead of silently delegating.

Log: `/Users/channel/runs/2026-09-10-forge-rules-integrity-v4/fixture.log`.
The first two fixture boots failed before assertions because GuiDesktop needs a
desktop session; the test now uses a no-window, fail-on-unexpected-call IGuiBase
proxy. V3 passed 14 affordability cases then failed an incorrect test comparison:
`Cost.toString()` omits spell mana. V4 compares `getTotalMana()` and passes all16.

Run only through the existing Studio admission wrapper:

```
/bin/bash /Users/channel/Documents/GitHub/mtgx/tools/research-team/admitted-check.sh \
 /Users/channel/Documents/GitHub/mtgx-worktrees/forge-browser-opponent-0909/tools/lib/arena-lock.sh \
 audit-forge-rules-0910 --wait 0 -- \
 /bin/bash tools/test-benchmark-rules-cost.sh PINNED_JAR NEW_ABSOLUTE_ARTIFACT_DIR
```

The script compiles the changed production classes and actual-card fixture
against the pinned shaded jar, then runs with isolated classes/user.home. It does
not use Maven caches, shared targets, GUI windows, or launch a game match.

## Phase 2 implementation — verification pending

The bridge now asks the host to select a complete payment witness, including source
ability identities, source activation order and exact mana-token/shard allocation.
The executor uses Forge's real cost payment and mana ability resolution primitives,
then verifies actual produced mana and spends the selected tokens. It does not call
the default AI payment planner. Missing, ambiguous or unsupported answers invalidate
the run rather than choosing a canonical witness or delegating.

The advertised capability trio is `rules-cost-v2-witness-bounded`,
`rules-payment-v1`, `host-complete-witness`. It is a bounded protocol, not a general
legality certificate. Initial support excludes X and nonordinary payment costs.
The finite menu includes surplus source activations and retains distinct sources
and floating mana-token provenance. Equal-colored units from one fixed, effectless
source activation are treated as interchangeable; an actual Lotus output fixture
is added to check that assumption. Complete enumeration has explicit bounds of
200,000 nodes and 4,096 plans, and throws before publishing any truncated menu.
These bounds can reject ordinary late-game positions. A combinatorial domain plus
validated host-submitted witness is the planned scalability replacement, not a
larger bound or silent pruning.

New fixtures cover exact host-selected alternative sources, Lotus execution,
floating-token identity, tapped non-tap mana abilities, and actual Thief of Sanity
versus Expensive Taste face-down land permission. They also snapshot original
ability fields and the global ability-ID counter. **These phase-2 changes have not
been compiled or run yet:** the human Forge browser game owns Studio admission.
Only the 16 phase-1 checks above are passing evidence. No deployment is authorized
by this unverified implementation snapshot.

## Outstanding gates and next implementation

- **Do not run an unrestricted cube benchmark yet.** X/announcements, Phyrexian,
  snow, two-generic hybrid, convoke/delve/improvise, reductions/set-costs, restricted
  mana, life-paying/filter/nonbattlefield/dynamic sources and coupled nonmana costs
  are explicitly unsupported. Bound exhaustion is likewise invalidity, not illegal.
- Verify the phase-2 execution witness and host policy selection together. Exact
  execution of one chosen witness does not establish complete payment coverage or
  parity with the TypeScript pilot's intended strategic payment policy.
- Source interactions need independent adversarial review before broadening support:
  resource-dependent activation restrictions, source sacrifice changing another
  source, activation-tax adjustments, replacement/trigger and mana conversion effects.
- Pure static pricing needs extension using Forge's rules primitives on immutable
  inputs, not AI `canPayManaCost` or a browser optimistic upper bound. Use explicit
  announced-X plus taxes as the next targeted gate; never clamp X silently.
- Whole-menu construction still uses older available-card/alternative-cost APIs and
  `sa.canPlay()`, which can mutate transient ability fields. A separate full-menu RNG,
  memory/state tripwire and stock/null/delegate controls remain necessary. These16
  tests prove only their measured checker cases, not universal state purity.
- Optional subsets are finite keyword options, not a proof of every repeatable
  cost/alternative announcement. Add actual multikicker and complex additional-cost
  completeness tests; unresolved surfaces must invalidate certification.
- Preserved stock Forge is not an equal-information AI by default. Observation,
  chance replay, reverse-engine equivalence, all decision surfaces and final panel
  provenance remain separate parent-owned gates. No historical number is certified
  by this patch, and no effect size of historical bias is inferred.
