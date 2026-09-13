# F1 per-site controller ownership classification

Source before this pass: integrate/repaired-jar-0912 at 936336e3,
`forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java`.

The source has **83 legacy count sites across 77 distinct names**.
The handoff's 77 is a name count; overloads and exceptional paths add sites.
Every site appears below with its original line number. Reproduction:
`scripts/classification-table.py`, `legacy-sites.json`, preserved source
`PlayerControllerBridge-before-f1.java`.

A callback begins UNCLASSIFIED; only successful completion moves it to a named
owner. STOCK stays STOCK on a bridged seat. Nested callbacks retain independent
receipts; untrusted children still block rules-wrapper certification. The
single remaining legacy count is an exception path and stays UNCLASSIFIED.

| Site | Original line | Method | Classification and evidence |
| --- | --- | --- | --- |
| 1 | 1072 | `chooseNumberForKeywordCost` | Accepted host return HOST; actual superclass return STOCK; exceptions UNCLASSIFIED. |
| 2 | 1115 | `chooseOptionalCosts` | Accepted host return HOST; actual superclass return STOCK; exceptions UNCLASSIFIED. Menu-construction empty optional-cost answer RULES (unchanged alternate-cost enumeration). |
| 3 | 1417 | `chooseStartingPlayer` | Accepted host return HOST; actual superclass return STOCK; exceptions UNCLASSIFIED. |
| 4 | 1549 | `chooseCardsToDiscardToMaximumHandSize` | Accepted host return HOST; actual superclass return STOCK; exceptions UNCLASSIFIED. |
| 5 | 1568 | `choosePermanentsToSacrifice` | Accepted host return HOST; actual superclass return STOCK; exceptions UNCLASSIFIED. |
| 6 | 2039 | `chooseEntitiesForEffect` | Accepted host return HOST; actual superclass return STOCK; exceptions UNCLASSIFIED. |
| 7 | 2081 | `chooseNumber` | Accepted host return HOST; actual superclass return STOCK; exceptions UNCLASSIFIED. |
| 8 | 2110 | `chooseNumber` | Accepted host return HOST; actual superclass return STOCK; exceptions UNCLASSIFIED. |
| 9 | 2215 | `confirmAction` | Accepted host return HOST; actual superclass return STOCK; exceptions UNCLASSIFIED. |
| 10 | 2246 | `chooseBinary` | Accepted host return HOST; actual superclass return STOCK; exceptions UNCLASSIFIED. |
| 11 | 2326 | `orderBlockers` | Accepted host return HOST; actual superclass return STOCK; exceptions UNCLASSIFIED. |
| 12 | 2526 | `acceptsDrawOffer` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 13 | 2550 | `chooseBinary` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 14 | 2554 | `chooseNumber` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 15 | 2598 | `getAbilityToPlay` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 16 | 2849 | `playSaFromPlayEffect` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 17 | 2851 | `sideboard` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 18 | 2853 | `chooseCardsYouWonToAddToDeck` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 19 | 2855 | `divideShield` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 20 | 2857 | `specifyManaCombo` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 21 | 2859 | `choosePermanentsToDestroy` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 22 | 2861 | `announceRequirements` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 23 | 2863 | `chooseNewTargetsFor` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 24 | 2865 | `chooseTarget` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 25 | 2867 | `helpPayForAssistSpell` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 26 | 2869 | `choosePlayerToAssistPayment` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 27 | 2871 | `chooseCardsForEffectMultiple` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 28 | 2873 | `chooseSpellAbilitiesForEffect` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 29 | 2875 | `chooseSingleSpellForEffect` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 30 | 2877 | `confirmBidAction` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 31 | 2879 | `confirmReplacementEffect` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 32 | 2881 | `confirmStaticApplication` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 33 | 2923 | `exertAttackers` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 34 | 2925 | `enlistAttackers` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 35 | 2927 | `orderBlocker` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 36 | 2929 | `orderAttackers` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 37 | 2954 | `reveal` | Null superclass completion STOCK; failed exact-view lookup UNCLASSIFIED. Non-hand bridge observation remains UNCLASSIFIED (partial names-only history). |
| 38 | 2979 | `reveal` | Null superclass completion STOCK; failed exact-view lookup UNCLASSIFIED. Non-hand bridge observation remains UNCLASSIFIED (partial names-only history). |
| 39 | 2986 | `arrangeForSurveil` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 40 | 2988 | `willPutCardOnTop` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 41 | 3087 | `chooseCardsToDiscardUnlessType` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 42 | 3089 | `chooseCardsToDelve` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 43 | 3091 | `chooseCardsForConvokeOrImprovise` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 44 | 3093 | `chooseCardsForSplice` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 45 | 3095 | `chooseCardsToRevealFromHand` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 46 | 3097 | `chooseSaToActivateFromOpeningHand` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 47 | 3099 | `chooseStartingHand` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 48 | 3101 | `chooseManaFromPool` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 49 | 3103 | `chooseSomeType` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 50 | 3105 | `chooseSector` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 51 | 3107 | `chooseContraptionsToCrank` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 52 | 3109 | `chooseSprocket` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 53 | 3111 | `choosePDRollToIgnore` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 54 | 3113 | `chooseRollToIgnore` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 55 | 3115 | `chooseDiceToReroll` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 56 | 3117 | `chooseRollToModify` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 57 | 3119 | `chooseRollToSwap` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 58 | 3121 | `chooseRollSwapValue` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 59 | 3123 | `vote` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 60 | 3192 | `chooseNumberForCostReduction` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 61 | 3231 | `chooseFlipResult` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 62 | 3259 | `chooseColor` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 63 | 3262 | `chooseColorAllowColorless` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 64 | 3264 | `chooseColors` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 65 | 3266 | `chooseSingleCardFace` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 66 | 3268 | `chooseSingleCardFace` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 67 | 3270 | `chooseSingleCardState` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 68 | 3272 | `chooseCardsPile` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 69 | 3294 | `chooseKeywordForPump` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 70 | 3296 | `confirmPayment` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 71 | 3298 | `chooseSingleReplacementEffect` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 72 | 3300 | `chooseSingleStaticAbility` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 73 | 3302 | `chooseProtectionType` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 74 | 3310 | `orderCosts` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 75 | 3367 | `payCostDuringRoll` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 76 | 3369 | `payCombatCost` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 77 | 3397 | `applyManaToCost` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 78 | 3399 | `chooseCardsForCost` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 79 | 3401 | `getCostDecisionMaker` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 80 | 3403 | `chooseCardName` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 81 | 3405 | `chooseCardName` | STOCK after superclass completion; exceptions UNCLASSIFIED. |
| 82 | 3449 | `chooseSingleCardForZoneChange` | Accepted host return HOST; actual superclass return STOCK; exceptions UNCLASSIFIED. |
| 83 | 3482 | `chooseCardsForZoneChange` | Accepted host return HOST; actual superclass return STOCK; exceptions UNCLASSIFIED. |

## Existing beginCall sites repaired

Missing superclass-return classifications were also repaired in London
bottoming, mulligan, scry, zone ordering, payment, single-entity choice, mode
choice, setupAutoProfile, complainCardsCantPlayWell, end-turn reset,
no-stack/simultaneous/static trigger execution, and ordinary playChosenSpellAbility.
These are STOCK after inherited AI completion. Accepted explicit entity and
mode answers classify HOST outside controlled announcements too. Existing
FORCED and RULES paths remain intact.

## Deliberate certification limits

Non-hand bridge reveals still deliver names-only history and remain
UNCLASSIFIED. No complete ordered/characteristic observation claim is made.
Stock-only legacy surfaces remain STOCK if reached by a bridge; this pass
adds no host implementation for them. A bridge/null smoke can establish
CLASSIFIED_CONTROL only for its exercised domain, never globally.

The first compile caught seven mechanically over-scoped edits in private
helpers, removed before the corrected f1-2 compile passed. Logs are retained.
The first fixture run lacked an activating player on its synthetic payment
ability; that setup was repaired without changing controller code.

## Validation

Admitted compile f1-2 passes. Real legacy callback fixture: 78 checks; existing
per-invocation counter fixture: 24 checks; real NULL_PROBE wiring and
HostChoiceIntegritySmoke pass. ControllerOwnershipEngineSmoke originally
expected the three inherited callbacks to remain unknown; its assertions now
require STOCK and zero HOST/FORCED/UNCLASSIFIED, retaining exact answer/RNG
comparisons and the failed-action-identity negative case. The updated ownership
suite and all 67 observation checks pass (logs/f1-tests-3.log). Expected
fault-injection exceptions are retained, not gameplay failures.
