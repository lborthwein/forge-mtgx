package forge.interactive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.AiController;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilMana;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.mana.ManaCostBeingPaid;
import forge.card.mana.ManaCostShard;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityView;
import forge.util.collect.FCollection;
import forge.game.spellability.SpellAbility;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputAttack;
import forge.gamemodes.match.input.InputBlock;
import forge.gamemodes.match.input.InputLondonMulligan;
import forge.gamemodes.match.input.InputPayMana;
import forge.gamemodes.match.input.InputSelectEntitiesFromList;
import forge.gamemodes.match.input.InputSelectTargets;

/**
 * Forge's own AI action for the bridge's human seat — the "expert hint".
 *
 * <p>This is the inverse-blend engine. At every decision the human seat is
 * asked about, this class runs a real {@link PlayerControllerAi} <em>as that
 * seat</em> and reports what Forge would have done, so the client can either
 * render it as advice or auto-submit it for the decision classes a policy has
 * chosen to defer. Every deferred decision still travels through
 * {@code POST /input}, so a blended game stays as replayable and auditable as a
 * hand-played one.
 *
 * <h2>Why the controller is swapped rather than constructed alongside</h2>
 *
 * Roughly sixty sites across {@code forge-ai} and {@code forge-game} reach the
 * brains by casting: {@code ((PlayerControllerAi) ai.getController()).getAi()}.
 * {@code AiCardMemory} does it on essentially every {@code canPlaySa} call. If
 * the human seat's {@code getController()} is still a
 * {@code PlayerControllerHuman}, each of those is a {@link ClassCastException}
 * waiting at depth — the kind that passes a smoke test and then fails on the
 * first card with a memory-backed or cost-backed AI path. So the evaluation
 * runs inside {@link Player#runWithController}, Forge's own Mindslaver
 * mechanism, which makes {@code getController()} return the AI controller for
 * the duration. It is invoked with {@code event = false}, so no
 * {@code GameEventPlayerControl} fires and the client sees no spurious
 * "control changed" event.
 *
 * <h2>Profile fidelity</h2>
 *
 * The controller is built against a synthetic {@link LobbyPlayerAi} carrying the
 * <em>same profile string the opponent seat plays</em>, not against the human's
 * own lobby player. Passing the human lobby player does not crash — Forge's
 * {@code AiProfileUtil.getAIProp} is guarded — but it silently downgrades every
 * {@code AiProps} lookup to the compiled-in default. That would make the hint a
 * quietly different policy from the opponent in exactly the storm and land-drop
 * areas that matter. With the synthetic profile, "the hint is the same AI the
 * opponent runs" is a true statement rather than an assumed one.
 *
 * <h2>Fail-closed</h2>
 *
 * Every path is wrapped in {@code catch (Throwable)}. A hint that throws
 * degrades to {@code hint: {degraded: "..."}} — never a fatal, never a dropped
 * request. A crash in advice must not end a game.
 */
final class ExpertHint {
    /** Soft budget: hints slower than this are flagged, not discarded. */
    static final long DEFAULT_BUDGET_MS = 250L;
    /**
     * Hard ceiling applied to {@link Game#AI_TIMEOUT} while a hint runs.
     * Forge's timeout is in whole seconds, so 250 ms cannot itself be a hard
     * ceiling without abandoning a thread mid-mutation. One second is the
     * tightest Forge allows and stays well inside the 5 s default.
     */
    static final int DEFAULT_CEILING_SEC = 1;

    private final boolean enabled;
    private final long budgetMs;
    private final int ceilingSec;
    private final String aiProfile;
    private final AtomicLong decisions = new AtomicLong();
    /**
     * Whether an AI evaluation is running right now, and on which thread.
     *
     * This exists to answer one question with evidence rather than argument: if
     * the engine faults, was the hint inside Forge's data structures at that
     * moment? Without it, a ConcurrentModificationException is unattributable.
     */
    private final AtomicReference<String> inFlightOn = new AtomicReference<>();
    private final AtomicLong evaluations = new AtomicLong();

    private volatile Game game;
    private volatile Player human;
    private volatile LobbyPlayerAi syntheticLobby;

    ExpertHint(final boolean enabled, final long budgetMs, final int ceilingSec, final String aiProfile) {
        this.enabled = enabled;
        this.budgetMs = budgetMs > 0 ? budgetMs : DEFAULT_BUDGET_MS;
        this.ceilingSec = ceilingSec > 0 && ceilingSec <= 5 ? ceilingSec : DEFAULT_CEILING_SEC;
        this.aiProfile = aiProfile == null || aiProfile.isBlank() ? "Default" : aiProfile;
    }

    /** Reads the launch flags. Off unless explicitly enabled. */
    static ExpertHint fromSystemProperties(final String aiProfile) {
        final boolean on = Boolean.parseBoolean(System.getProperty("forge.interactive.hint", "false"));
        final long budget = longProperty("forge.interactive.hint.budgetMs", DEFAULT_BUDGET_MS);
        final int ceiling = (int) longProperty("forge.interactive.hint.ceilingSec", DEFAULT_CEILING_SEC);
        return new ExpertHint(on, budget, ceiling, aiProfile);
    }

    private static long longProperty(final String name, final long fallback) {
        try {
            final String raw = System.getProperty(name);
            return raw == null || raw.isBlank() ? fallback : Long.parseLong(raw.trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    long budgetMs() {
        return budgetMs;
    }

    void bind(final Game boundGame, final Player boundHuman) {
        this.game = boundGame;
        this.human = boundHuman;
        // The name is diagnostic only; the profile is what changes decisions.
        final LobbyPlayerAi lobby = new LobbyPlayerAi("Expert Hint", null);
        lobby.setAiProfile(aiProfile);
        this.syntheticLobby = lobby;
    }

    long nextDecisionId() {
        return decisions.incrementAndGet();
    }

    /** Null when no evaluation is running, else the thread it is running on. */
    String inFlightThread() {
        return inFlightOn.get();
    }

    long evaluationCount() {
        return evaluations.get();
    }

    /**
     * Computes one plan for the decision this input opens. Returns null when
     * hints are off or this input does not open a decision of its own.
     */
    HintPlan planFor(final Input input, final String kind, final long decisionId) {
        if (!enabled || game == null || human == null || input == null) {
            return null;
        }
        final long started = System.nanoTime();
        try {
            if (input instanceof InputAttack) {
                return timed(decisionId, kind, started, this::planAttacks);
            }
            if (input instanceof InputBlock) {
                return timed(decisionId, kind, started, this::planBlocks);
            }
            if (input instanceof InputLondonMulligan london) {
                return timed(decisionId, kind, started, id -> planLondon(id, london));
            }
            if (input instanceof InputPayMana payment) {
                return timed(decisionId, kind, started, id -> planMana(id, payment));
            }
            if (input instanceof InputSelectTargets targeting) {
                return timed(decisionId, kind, started, id -> planTargets(id, targeting));
            }
            if (input instanceof InputSelectEntitiesFromList<?> selection) {
                return timed(decisionId, kind, started, id -> planSelection(id, selection));
            }
            if ("mulligan".equals(kind)) {
                return timed(decisionId, kind, started, this::planMulliganKeep);
            }
            if ("priority".equals(kind)) {
                return timed(decisionId, kind, started, this::planPriority);
            }
            return null;
        } catch (Throwable failure) {
            return HintPlan.degraded(decisionId, kind, "threw: " + brief(failure), elapsedMs(started));
        }
    }

    private interface PlanStep {
        HintPlan apply(long decisionId) throws Exception;
    }

    private HintPlan timed(final long decisionId, final String kind, final long started,
                           final PlanStep step) {
        final int restore = game.AI_TIMEOUT;
        game.AI_TIMEOUT = ceilingSec;
        try {
            final HintPlan plan = step.apply(decisionId);
            return plan;
        } catch (Throwable failure) {
            return HintPlan.degraded(decisionId, kind, "threw: " + brief(failure), elapsedMs(started));
        } finally {
            game.AI_TIMEOUT = restore;
        }
    }

    private static long elapsedMs(final long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String brief(final Throwable failure) {
        final String name = failure.getClass().getSimpleName();
        final String message = failure.getMessage();
        return message == null || message.isBlank() ? name : name + ": " + message;
    }

    /** Runs {@code body} with the human seat temporarily controlled by the AI. */
    private void underAi(final java.util.function.Consumer<PlayerControllerAi> body) {
        final PlayerControllerAi temp = new PlayerControllerAi(game, human, syntheticLobby);
        final String thread = Thread.currentThread().getName();
        inFlightOn.set(thread);
        evaluations.incrementAndGet();
        try {
            human.runWithController(() -> body.accept(temp), temp);
        } finally {
            inFlightOn.compareAndSet(thread, null);
        }
    }

    // ---------------------------------------------------------------- priority

    private HintPlan planPriority(final long decisionId) {
        final long started = System.nanoTime();
        final AtomicReference<SpellAbility> chosen = new AtomicReference<>();
        underAi(temp -> {
            final AiController brains = temp.getAi();
            final List<SpellAbility> line = brains.chooseSpellAbilityToPlay();
            if (line != null && !line.isEmpty()) {
                // Forge's own singleSpellAbilityList shape: the head is the
                // action to take now. A tail, when present, is the follow-up
                // line the AI intends next, not an alternative to this one.
                chosen.set(line.get(0));
            }
        });
        return HintPlan.priority(decisionId, chosen.get(), elapsedMs(started));
    }

    // ------------------------------------------------------------------ combat

    /**
     * Declares attackers into a scratch {@link Combat} rather than the live one.
     * Running the AI into the live combat would assign the attackers for real,
     * and the client's own {@code combat:attack:*} submissions would then toggle
     * them back off. The scratch object is read and discarded.
     */
    private HintPlan planAttacks(final long decisionId) {
        final long started = System.nanoTime();
        final Map<Integer, String> kinds = new LinkedHashMap<>();
        final Map<Integer, Integer> defenders = new LinkedHashMap<>();
        underAi(temp -> {
            final Combat scratch = new Combat(human);
            scratch.initConstraints();
            temp.getAi().declareAttackers(human, scratch);
            for (Card attacker : scratch.getAttackers()) {
                final GameEntity defender = scratch.getDefenderByAttacker(attacker);
                if (defender == null) {
                    continue;
                }
                kinds.put(attacker.getId(), defender instanceof Player ? "player" : "card");
                defenders.put(attacker.getId(), defender.getId());
            }
        });
        return HintPlan.attacks(decisionId, kinds, defenders, elapsedMs(started));
    }

    /**
     * Blockers need the live attackers, and {@link Combat} has no cheap clone,
     * so the AI is run against the live combat and its assignments are then
     * removed again. The restore is verified: any blocker the AI added that
     * cannot be removed degrades the hint rather than leaving the live combat
     * changed behind the seat's back.
     */
    private HintPlan planBlocks(final long decisionId) {
        final long started = System.nanoTime();
        final Map<Integer, Integer> blocks = new LinkedHashMap<>();
        final Combat combat = game.getCombat();
        if (combat == null) {
            return HintPlan.degraded(decisionId, "combat", "no live combat to block in", elapsedMs(started));
        }
        final Set<Card> before = new java.util.LinkedHashSet<>(combat.getAllBlockers());
        final AtomicReference<String> restoreFailure = new AtomicReference<>();
        underAi(temp -> {
            temp.getAi().declareBlockersFor(human, combat);
            final List<Card> added = new ArrayList<>();
            for (Card blocker : combat.getAllBlockers()) {
                if (before.contains(blocker)) {
                    continue;
                }
                added.add(blocker);
                final CardCollection blocked = combat.getAttackersBlockedBy(blocker);
                if (blocked != null && !blocked.isEmpty()) {
                    blocks.put(blocker.getId(), blocked.get(0).getId());
                }
            }
            for (Card blocker : added) {
                combat.removeFromCombat(blocker);
            }
            for (Card blocker : added) {
                if (combat.getAllBlockers().contains(blocker)) {
                    restoreFailure.set("could not un-assign scratch blocker " + blocker.getId());
                    return;
                }
            }
        });
        if (restoreFailure.get() != null) {
            return HintPlan.degraded(decisionId, "combat", restoreFailure.get(), elapsedMs(started));
        }
        return HintPlan.blocks(decisionId, blocks, elapsedMs(started));
    }

    // ---------------------------------------------------------------- mulligan

    private HintPlan planMulliganKeep(final long decisionId) {
        final long started = System.nanoTime();
        final AtomicReference<Boolean> keep = new AtomicReference<>();
        underAi(temp -> keep.set(!ComputerUtil.wantMulligan(human, 0)));
        if (keep.get() == null) {
            return HintPlan.degraded(decisionId, "mulligan", "no mulligan answer", elapsedMs(started));
        }
        return HintPlan.mulligan(decisionId, keep.get(), elapsedMs(started));
    }

    private HintPlan planLondon(final long decisionId, final InputLondonMulligan london) {
        final long started = System.nanoTime();
        final int toReturn = london.getCardsToReturn();
        final List<Integer> tuck = new ArrayList<>();
        underAi(temp -> {
            final CardCollectionView chosen =
                    temp.tuckCardsViaMulligan(human.getCardsIn(forge.game.zone.ZoneType.Hand), toReturn);
            if (chosen == null) {
                return;
            }
            for (Card card : chosen) {
                tuck.add(card.getId());
            }
        });
        return HintPlan.london(decisionId, tuck, elapsedMs(started));
    }

    // -------------------------------------------------------------------- mana

    /**
     * Asks {@link ComputerUtilMana} which sources it would tap. The overlay
     * constraint is respected exactly: this <em>calls</em> ComputerUtilMana and
     * never edits it, so the pinned bench instrument is untouched.
     */
    private HintPlan planMana(final long decisionId, final InputPayMana payment) {
        final long started = System.nanoTime();
        final ManaCostBeingPaid cost = payment.getManaCostBeingPaid();
        final SpellAbility paying = payment.getSpellAbilityBeingPaidFor();
        if (cost == null) {
            return HintPlan.degraded(decisionId, "mana", "payment exposed no remaining cost", elapsedMs(started));
        }
        final List<Integer> sources = new ArrayList<>();
        final AtomicReference<String> failure = new AtomicReference<>();
        underAi(temp -> {
            // A fresh ManaCostBeingPaid: the query pays into its argument in
            // test mode, and the live input's cost must not be touched.
            final CardCollection chosen = ComputerUtilMana.getManaSourcesToPayCost(
                    new ManaCostBeingPaid(cost), paying, human, false);
            if (chosen == null) {
                failure.set("AI found no payment for the remaining cost");
                return;
            }
            for (Card card : chosen) {
                sources.add(card.getId());
            }
        });
        if (failure.get() != null) {
            return HintPlan.degraded(decisionId, "mana", failure.get(), elapsedMs(started));
        }
        return HintPlan.mana(decisionId, sources, elapsedMs(started));
    }

    // ----------------------------------------------------------------- targets

    /**
     * Asks the AI to choose targets for the ability actually on the way.
     *
     * The options offered are exactly the ones this input will accept — Forge's
     * own {@code getSelectableCards()} plus the players it says are legal — so
     * the AI is choosing inside the same set the request advertises, and the
     * mapper cannot produce a control the seat could not have clicked.
     */
    private HintPlan planTargets(final long decisionId, final InputSelectTargets targeting) {
        final long started = System.nanoTime();
        final SpellAbility sa = targeting.getSpellAbility();
        if (sa == null) {
            return HintPlan.degraded(decisionId, "target", "targeting input exposed no ability", elapsedMs(started));
        }
        final List<String> chosen = new ArrayList<>();
        final AtomicReference<String> failure = new AtomicReference<>();
        underAi(temp -> {
            final FCollection<GameEntity> options = new FCollection<>();
            for (Card card : targeting.getSelectableCards()) {
                options.add(card);
            }
            for (Player player : game.getPlayers()) {
                if (targeting.canSelectPlayer(player)) {
                    options.add(player);
                }
            }
            if (options.isEmpty()) {
                failure.set("no legal target was offered");
                return;
            }
            // Forge's per-API choosers read this map without a null check.
            final GameEntity picked = temp.chooseSingleEntityForEffect(
                    options, null, sa, "Choose a target", false, null, new java.util.HashMap<>());
            if (picked == null) {
                failure.set("AI declined to choose a target");
                return;
            }
            if (picked instanceof Card card) {
                chosen.add("card:" + card.getId());
            } else if (picked instanceof Player player) {
                int index = 0;
                for (Player candidate : game.getPlayers()) {
                    if (candidate == player) {
                        chosen.add("player:" + index);
                        break;
                    }
                    index++;
                }
            }
        });
        if (failure.get() != null) {
            return HintPlan.degraded(decisionId, "target", failure.get(), elapsedMs(started));
        }
        if (chosen.isEmpty()) {
            return HintPlan.degraded(decisionId, "target", "AI target did not resolve to an id", elapsedMs(started));
        }
        return HintPlan.targets(decisionId, chosen, elapsedMs(started));
    }

    // ------------------------------------------------- entity selections

    /**
     * "Choose N of these" — discard, sacrifice, tutor, and everything else
     * Forge routes through {@link InputSelectEntitiesFromList}.
     *
     * The seat is offered a concrete list, so the AI can be asked to pick from
     * exactly that list. It is asked once per entity rather than for the whole
     * set: Forge re-opens the input after each selection with the chosen ones
     * removed, so one answer per request is what the wire actually wants, and
     * asking for a set would mean naming controls the next request withdraws.
     */
    private HintPlan planSelection(final long decisionId, final InputSelectEntitiesFromList<?> selection) {
        final long started = System.nanoTime();
        final List<String> chosen = new ArrayList<>();
        final AtomicReference<String> failure = new AtomicReference<>();
        underAi(temp -> {
            final FCollection<GameEntity> options = new FCollection<>();
            for (GameEntity option : selection.getValidChoices()) {
                // Already-selected entities are no longer on offer.
                if (!selection.getSelected().contains(option)) {
                    options.add(option);
                }
            }
            if (options.isEmpty()) {
                failure.set("no unselected entity remained");
                return;
            }
            final SpellAbility sa = selection.getSelectionSpellAbility();
            if (sa == null) {
                // Without an ability there is no per-API chooser to consult, and
                // guessing which of these the seat wants is exactly the thing
                // this class must not do.
                failure.set("selection has no ability context");
                return;
            }
            final GameEntity picked = temp.chooseSingleEntityForEffect(
                    options, null, sa, "Choose", selection.getMinSelected() <= 0, null,
                    new java.util.HashMap<>());
            if (picked == null) {
                failure.set("AI declined to choose");
                return;
            }
            if (picked instanceof Card card) {
                chosen.add("card:" + card.getId());
            } else if (picked instanceof Player player) {
                int index = 0;
                for (Player candidate : game.getPlayers()) {
                    if (candidate == player) { chosen.add("player:" + index); break; }
                    index++;
                }
            }
        });
        if (failure.get() != null) {
            return HintPlan.degraded(decisionId, "choice", failure.get(), elapsedMs(started));
        }
        if (chosen.isEmpty()) {
            return HintPlan.degraded(decisionId, "choice", "AI choice did not resolve to an id", elapsedMs(started));
        }
        return HintPlan.targets(decisionId, chosen, elapsedMs(started));
    }

    // ----------------------------------------------- mana ability chooser

    /**
     * Which ability of a multi-colour mana source the AI would activate.
     *
     * A triome asks this on every tap, so without it the blend stalls the first
     * time it pays for anything off a dual land. The answer comes from
     * {@link ComputerUtilMana#chooseManaAbility}, called never edited, so the
     * pinned bench overlay stays untouched.
     */
    HintPlan planManaAbility(final long decisionId, final Card host,
                             final Map<String, SpellAbilityView> offered,
                             final InputPayMana payment) {
        final long started = System.nanoTime();
        if (!enabled || host == null || payment == null) {
            return null;
        }
        final ManaCostBeingPaid cost = payment.getManaCostBeingPaid();
        final SpellAbility paying = payment.getSpellAbilityBeingPaidFor();
        if (cost == null || paying == null) {
            return HintPlan.degraded(decisionId, "choice", "no live payment context", elapsedMs(started));
        }
        final AtomicReference<Integer> viewId = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>();
        underAi(temp -> {
            // Only the abilities this request actually offered are candidates.
            final List<SpellAbility> candidates = new ArrayList<>();
            for (SpellAbility ability : ComputerUtilMana.getAIPlayableMana(host)) {
                final SpellAbilityView view = ability.getView();
                if (view == null) {
                    continue;
                }
                for (SpellAbilityView offeredView : offered.values()) {
                    if (offeredView != null && offeredView.getId() == view.getId()) {
                        candidates.add(ability);
                        break;
                    }
                }
            }
            if (candidates.isEmpty()) {
                failure.set("no offered ability was an AI-playable mana ability");
                return;
            }
            for (ManaCostShard shard : cost.getDistinctShards()) {
                final SpellAbility picked = ComputerUtilMana.chooseManaAbility(
                        new ManaCostBeingPaid(cost), paying, human, shard, candidates, true);
                if (picked != null && picked.getView() != null) {
                    viewId.set(picked.getView().getId());
                    return;
                }
            }
            failure.set("AI chose no mana ability for any unpaid shard");
        });
        if (failure.get() != null) {
            return HintPlan.degraded(decisionId, "choice", failure.get(), elapsedMs(started));
        }
        return HintPlan.manaAbility(decisionId, viewId.get(), elapsedMs(started));
    }

    // ------------------------------------------------------------------ encode

    /**
     * The wire shape. {@code hint} is optional on every request, so a jar that
     * produces none — or an older jar — is indistinguishable from "no advice",
     * and the field can be dark-launched without a client change.
     */
    JsonObject encode(final HintPlan plan, final ExpertHintMapper.Mapped mapped,
                      final boolean projected) {
        final JsonObject hint = new JsonObject();
        hint.addProperty("source", "forge-ai");
        hint.addProperty("path", "live-swap");
        hint.addProperty("profile", aiProfile);
        hint.addProperty("decisionId", plan.decisionId);
        hint.addProperty("openedKind", plan.openedKind);
        hint.addProperty("ms", plan.ms);
        hint.addProperty("budgetMs", budgetMs);
        if (plan.ms > budgetMs) {
            hint.addProperty("overBudget", true);
        }
        if (projected) {
            hint.addProperty("projected", true);
        }
        final JsonArray ids = new JsonArray();
        if (mapped != null) {
            for (String id : mapped.controlIds()) {
                ids.add(id);
            }
        }
        hint.add("controlIds", ids);
        final String degraded = plan.degraded != null ? plan.degraded
                : mapped == null ? "no mapping attempted" : mapped.degraded();
        if (degraded != null) {
            hint.addProperty("degraded", degraded);
        }
        if (mapped != null && mapped.diagnostic() != null) {
            hint.addProperty("diagnostic", mapped.diagnostic());
        }
        return hint;
    }
}
