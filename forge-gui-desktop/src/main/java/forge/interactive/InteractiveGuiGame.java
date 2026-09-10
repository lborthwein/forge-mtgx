/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011 Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or any later version.
 */
package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import forge.LobbyPlayer;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.deck.CardPool;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameEntityView;
import forge.game.GameState;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.event.GameEvent;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventGameOutcome;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.AbstractGuiGame;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputAttack;
import forge.gamemodes.match.input.InputBlock;
import forge.gamemodes.match.input.InputConfirm;
import forge.gamemodes.match.input.InputLockUI;
import forge.gamemodes.match.input.InputLondonMulligan;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gamemodes.match.input.InputPayMana;
import forge.gamemodes.match.input.InputPayManaOfCostPayment;
import forge.gamemodes.match.input.InputSelectEntitiesFromList;
import forge.gamemodes.match.input.InputSelectTargets;
import forge.gui.FThreads;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerControllerHuman;
import forge.player.PlayerZoneUpdate;
import forge.player.PlayerZoneUpdates;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

import com.google.common.eventbus.Subscribe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A real {@link PlayerControllerHuman} GUI whose renderer and input device are an NDJSON
 * browser client. No decision in this class is delegated to an AI or default heuristic.
 */
final class InteractiveGuiGame extends AbstractGuiGame implements AutoCloseable {
    private final InteractiveProtocol.Channel channel;
    private final int humanSeat;
    private final AtomicLong requestSequence = new AtomicLong();
    private final AtomicLong publishGeneration = new AtomicLong();
    private final AtomicReference<ActiveRequest> activeRequest = new AtomicReference<>();
    private final AtomicReference<ModalRequest> modalRequest = new AtomicReference<>();
    private final ConcurrentHashMap<String, PendingInput> inputIds = new ConcurrentHashMap<>();
    private final Set<Input> inFlightInputs = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean aborting = new AtomicBoolean();
    private final ExecutorService actions;

    private volatile Game game;
    private volatile Player human;
    private volatile PlayerControllerHuman controller;
    private volatile Thread readerThread;
    private volatile String promptMessage = "";
    private volatile Input presentedInput;
    private volatile CardView promptCard;
    private volatile String okLabel = "OK";
    private volatile String cancelLabel = "Cancel";
    private volatile boolean okEnabled;
    private volatile boolean cancelEnabled;
    private volatile String lastInputFingerprint = "";
    private final ThreadLocal<Set<Integer>> explicitlyOfferedCards =
            ThreadLocal.withInitial(Collections::emptySet);

    InteractiveGuiGame(final InteractiveProtocol.Channel channel, final int humanSeat) {
        this.channel = Objects.requireNonNull(channel);
        this.humanSeat = humanSeat;
        final ThreadFactory factory = runnable -> {
            final Thread thread = new Thread(runnable, "Game Interactive Human Input");
            thread.setDaemon(true);
            return thread;
        };
        // A mana/cost action may block in a nested InputQueue.showAndWait.
        // Its answer needs a separate action lane; per-Input claims below keep
        // the same input from executing twice while the outer call is suspended.
        actions = Executors.newCachedThreadPool(factory);
    }

    void bind(final Game game, final Player human, final PlayerControllerHuman controller) {
        if (this.game != null) {
            throw new IllegalStateException("interactive GUI may bind only one game");
        }
        this.game = Objects.requireNonNull(game);
        this.human = Objects.requireNonNull(human);
        this.controller = Objects.requireNonNull(controller);
    }

    void startReader() {
        if (readerThread != null) {
            throw new IllegalStateException("input reader already started");
        }
        readerThread = new Thread(this::readInputs, "Forge Interactive NDJSON Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    void onEngineEvent(final GameEvent engineEvent) {
        if (channel.isEnded() || game == null || human == null) {
            return;
        }
        try {
            final JsonObject body = new JsonObject();
            final JsonObject event = InteractiveGameEvents.encode(engineEvent, game, human.getView());
            body.add("event", event);
            channel.send("event", body);
        } catch (InteractiveProtocol.ProtocolException e) {
            fail("eof", e.getMessage(), null, "onEngineEvent", null, e);
        } catch (Throwable e) {
            fail("engine", "could not encode Forge event: " + safeThrowable(e),
                    null, "onEngineEvent", null, e);
        }
    }

    void engineFailure(final Throwable failure) {
        fail("engine", "Forge game failed: " + safeThrowable(failure), null,
                "match.startGame", null, failure);
    }

    boolean hasFailed() {
        return aborting.get() || channel.isEnded();
    }

    private void readInputs() {
        try {
            while (!channel.isEnded()) {
                acceptInput(channel.readInput());
            }
        } catch (InteractiveProtocol.ProtocolException e) {
            fail(e.code(), e.getMessage(), null, "readInput", null, e);
        } catch (IOException e) {
            fail("eof", "stdin failed while awaiting human input: " + e.getMessage(),
                    null, "readInput", null, e);
        } catch (Throwable e) {
            fail("engine", "input reader failed: " + safeThrowable(e), null,
                    "readInput", null, e);
        }
    }

    private void acceptInput(final InteractiveProtocol.InputMessage input) {
        final PendingInput pending = new PendingInput(input.original());
        final PendingInput existing = inputIds.putIfAbsent(input.inputId(), pending);
        if (existing != null) {
            if (!existing.original.equals(input.original())) {
                emitAck(input.requestId(), input.inputId(), input.kind(), false,
                        "inputId was already used for a different message");
            } else {
                existing.result.whenComplete((result, failure) -> {
                    if (result != null && !channel.isEnded()) {
                        emitAck(result.requestId, input.inputId(), result.kind,
                                result.accepted, result.reason);
                    }
                });
            }
            return;
        }

        final ModalRequest modal = modalRequest.get();
        if (modal != null && modal.requestId.equals(input.requestId())) {
            acceptModal(input, pending, modal);
            return;
        }

        final ActiveRequest request = activeRequest.get();
        if (request == null || !request.requestId.equals(input.requestId())) {
            finishInput(input, pending, false, "stale or unknown requestId");
            return;
        }
        if (!request.kind.equals(input.kind())) {
            finishInput(input, pending, false, "kind does not match active request");
            return;
        }
        if (request.input != controller.getInputProxy().getInput()) {
            activeRequest.compareAndSet(request, null);
            lastInputFingerprint = "";
            finishInput(input, pending, false, "request no longer matches Forge's current input");
            scheduleInputPublish();
            return;
        }
        final JsonObject action = input.action();
        final String controlId = string(action, "controlId");
        final ControlBinding binding = controlId == null ? null : request.controls.get(controlId);
        if (binding == null) {
            finishInput(input, pending, false, "controlId was not advertised by the active request");
            return;
        }
        if (!binding.type.equals(string(action, "type"))) {
            finishInput(input, pending, false, "action type does not match advertised control");
            return;
        }
        if ("concede".equals(binding.type)) {
            if (!request.claimed.compareAndSet(false, true)) {
                finishInput(input, pending, false,
                        "another input is already executing for this request");
                return;
            }
            // Concede may wake match.startGame and let Main emit terminal immediately.
            // Put its required accepted ack on the wire before triggering that outcome.
            finishInput(input, pending, true, null);
            activeRequest.compareAndSet(request, null);
            inFlightInputs.add(request.input);
            actions.execute(() -> {
                try {
                    controller.concede();
                } catch (Throwable failure) {
                    fail("engine", "concede failed: " + safeThrowable(failure),
                            request.requestId, "dispatch:concede", request.kind, failure);
                } finally {
                    inFlightInputs.remove(request.input);
                }
            });
            return;
        }
        if (!request.claimed.compareAndSet(false, true)) {
            finishInput(input, pending, false, "another input is already executing for this request");
            return;
        }
        if (!inFlightInputs.add(request.input)) {
            request.claimed.set(false);
            finishInput(input, pending, false, "another action is still executing for this Forge input");
            return;
        }
        actions.execute(() -> {
            ActionResult result;
            try {
                if (request.input != controller.getInputProxy().getInput()) {
                    finishInput(input, pending, false,
                            "request no longer matches Forge's current input");
                    activeRequest.compareAndSet(request, null);
                    lastInputFingerprint = "";
                    inFlightInputs.remove(request.input);
                    scheduleInputPublish();
                    return;
                }
                result = binding.handler.apply(action);
            } catch (GameConceded conceded) {
                finishInput(input, pending, false, "game was conceded during a nested request");
                activeRequest.compareAndSet(request, null);
                inFlightInputs.remove(request.input);
                return;
            } catch (Throwable failure) {
                pending.result.completeExceptionally(failure);
                fail("engine", "human action failed: " + safeThrowable(failure),
                        request.requestId, "dispatch:" + binding.type, request.kind, failure);
                return;
            }

            finishInput(input, pending, result.accepted, result.reason);
            if (result.accepted) {
                activeRequest.compareAndSet(request, null);
                lastInputFingerprint = "";
            } else {
                request.claimed.set(false);
            }
            inFlightInputs.remove(request.input);
            scheduleInputPublish();
        });
    }

    private void acceptModal(final InteractiveProtocol.InputMessage input,
                             final PendingInput pending, final ModalRequest modal) {
        if (!modal.kind.equals(input.kind())) {
            finishInput(input, pending, false, "kind does not match modal request");
            return;
        }
        if (!modal.claimed.compareAndSet(false, true)) {
            finishInput(input, pending, false, "another answer is already executing for this request");
            return;
        }
        if ("concede".equals(string(input.action(), "type"))
                && "game:concede".equals(string(input.action(), "controlId"))) {
            finishInput(input, pending, true, null);
            modalRequest.compareAndSet(modal, null);
            try {
                controller.concede();
            } finally {
                modal.answer.completeExceptionally(new GameConceded());
            }
            return;
        }
        final String rejection;
        try {
            rejection = modal.validator.apply(input.action());
        } catch (Throwable e) {
            modal.claimed.set(false);
            finishInput(input, pending, false, "invalid modal answer: " + safeThrowable(e));
            return;
        }
        if (rejection != null) {
            modal.claimed.set(false);
            finishInput(input, pending, false, rejection);
            return;
        }
        finishInput(input, pending, true, null);
        modalRequest.compareAndSet(modal, null);
        modal.answer.complete(input.action().deepCopy());
    }

    private void finishInput(final InteractiveProtocol.InputMessage input,
                             final PendingInput pending, final boolean accepted,
                             final String reason) {
        final AckResult result = new AckResult(input.requestId(), input.kind(), accepted, reason);
        emitAck(input.requestId(), input.inputId(), input.kind(), accepted, reason);
        pending.result.complete(result);
    }

    private void emitAck(final String requestId, final String inputId, final String kind,
                         final boolean accepted, final String reason) {
        if (channel.isEnded()) {
            return;
        }
        try {
            channel.ack(requestId, inputId, kind, accepted, reason);
        } catch (InteractiveProtocol.ProtocolException e) {
            fail("eof", e.getMessage(), requestId, "ack", kind, e);
        }
    }

    private void scheduleInputPublish() {
        if (channel.isEnded()) {
            return;
        }
        final long generation = publishGeneration.incrementAndGet();
        FThreads.invokeInEdtLater(() -> {
            if (generation == publishGeneration.get()) {
                publishCurrentInput();
            }
        });
    }

    private void publishCurrentInput() {
        if (channel.isEnded() || modalRequest.get() != null || controller == null || game == null
                || game.isGameOver()) {
            return;
        }
        final Input input = controller.getInputProxy().getInput();
        if (input != null && inFlightInputs.contains(input)) {
            return;
        }
        if (input == null || input instanceof InputLockUI) {
            activeRequest.set(null);
            return;
        }
        // InputProxy publishes its new input before queuing showMessageInitial
        // on the EDT. Earlier event callbacks must not serialize stale buttons
        // or declare that not-yet-presented input unsupported.
        if (input != presentedInput) {
            return;
        }

        try {
            final String kind = kindFor(input);
            final LinkedHashMap<String, ControlBinding> bindings = new LinkedHashMap<>();
            final JsonArray controls = buildStatefulControls(input, kind, bindings);
            if (bindings.values().stream().allMatch(binding -> "concede".equals(binding.type))) {
                throw unsupported("publishCurrentInput",
                        "Forge input " + input.getClass().getName() + " exposed no human controls",
                        kind);
            }
            final String cleanPrompt = sanitizeText(promptMessage);
            final String fingerprint = System.identityHashCode(input) + "|" + kind + "|"
                    + cleanPrompt + "|" + controls;
            if (fingerprint.equals(lastInputFingerprint) && activeRequest.get() != null) {
                return;
            }
            lastInputFingerprint = fingerprint;

            final String requestId = nextRequestId();
            final JsonObject body = requestBody(requestId, kind,
                    inputClassName(input), "Forge", cleanPrompt,
                    input instanceof InputLondonMulligan london ? london.getCardsToReturn() : getSelectionMin(),
                    input instanceof InputLondonMulligan london ? london.getCardsToReturn() : getSelectionMax(),
                    !(input instanceof InputLondonMulligan) && cancelEnabled, controls);
            final ActiveRequest request = new ActiveRequest(requestId, kind, input, bindings);
            activeRequest.set(request);
            channel.send("request", body);
        } catch (InteractiveAbort ignored) {
            // The fatal message was already emitted.
        } catch (Throwable failure) {
            fail("engine", "could not publish Forge input: " + safeThrowable(failure),
                    null, "publishCurrentInput", null, failure);
        }
    }

    private JsonArray buildStatefulControls(final Input input, final String kind,
                                            final Map<String, ControlBinding> bindings) {
        final JsonArray controls = new JsonArray();
        if (input instanceof InputBlock) {
            addBlockControls(controls, bindings);
        }
        final Set<Integer> cardIds = new LinkedHashSet<>();
        game.forEachCardInGame(card -> {
            // Blocking is a pair chosen by the browser, not Forge's currently
            // highlighted attacker followed by an otherwise ambiguous card click.
            if (input instanceof InputBlock) {
                return true;
            }
            final CardView view = card.getView();
            // Hidden, unselectable cards cannot yield a browser control. Avoid
            // constructing their alternative abilities merely to discard them.
            if (!view.canBeShownTo(human.getView()) && !isSelectable(view)) {
                return true;
            }
            final boolean londonCard = input instanceof InputLondonMulligan london
                    && london.canSelectCard(card);
            if (input instanceof InputLondonMulligan && !londonCard) return true;
            final var priorityAbilities = input instanceof InputPassPriority
                    ? card.getAllPossibleAbilities(human, true) : null;
            // InputPassPriority.getActivateAction computes this same list.
            // Reuse it without caching across changes in Forge's game state.
            final String activate = priorityAbilities == null ? input.getActivateAction(card)
                    : priorityAbilities.isEmpty() ? null
                    : forge.util.Localizer.getInstance().getMessage(priorityAbilities.get(0).isSpell()
                            ? "lblCastSpell" : priorityAbilities.get(0).isLandAbility()
                            ? "lblPlayLand" : "lblActivateAbility");
            final var affordableAbilities = priorityAbilities == null ? null : priorityAbilities.stream()
                    .filter(a -> forge.player.HumanManaAffordability.mayAfford(human, a)).toList();
            if (priorityAbilities != null && !priorityAbilities.isEmpty() && affordableAbilities.isEmpty()) return true;
            if (!londonCard && activate == null && !isSelectable(view) && !isWeaklySelectable(view)) {
                return true;
            }
            if (!cardIds.add(card.getId())) {
                return true;
            }
            final String id = "card:" + card.getId();
            final JsonObject control = control(id, "selectCard",
                    (activate == null ? "Select" : sanitizeText(activate)) + ": "
                            + InteractiveState.safeCardLabel(view, human.getView()));
            control.addProperty("cardId", card.getId());
            final JsonObject value = new JsonObject();
            value.addProperty("selected", isHighlighted(view));
            value.addProperty("zone", String.valueOf(view.getZone()));
            if (input instanceof InputPassPriority) {
                // Same candidate list that Forge uses when this card is clicked.
                // A land with a non-mana ability must still hold priority.
                value.addProperty("manaOnly", !affordableAbilities.isEmpty()
                        && affordableAbilities.stream().allMatch(ability -> ability.isManaAbility()));
            }
            if (input instanceof InputAttack && game.getCombat() != null
                    && game.getCombat().getDefenders().contains(card)) {
                value.addProperty("combatAction", "defender");
            }
            control.add("value", value);
            controls.add(control);
            bindings.put(id, new ControlBinding("selectCard", action -> {
                final Card current = game.findById(card.getId());
                if (input instanceof InputLondonMulligan london
                        && (current == null || !london.canSelectCard(current))) {
                    return ActionResult.reject("card is no longer a legal mulligan selection");
                }
                if (current == null || (!current.getView().canBeShownTo(human.getView())
                        && !isSelectable(current.getView()))) {
                    return ActionResult.reject("card is no longer visible/selectable");
                }
                return controller.selectCard(current.getView(), null, null)
                        ? ActionResult.accept() : ActionResult.reject("Forge rejected card selection");
            }));
            return true;
        });

        if (input instanceof InputSelectTargets || input instanceof InputAttack
                || input instanceof InputSelectEntitiesFromList<?>) {
            int seat = 0;
            for (Player player : game.getPlayers()) {
                final int playerSeat = seat++;
                if (input instanceof InputSelectTargets targetInput && !targetInput.canSelectPlayer(player)) {
                    continue;
                }
                if (input instanceof InputAttack && (game.getCombat() == null
                        || !game.getCombat().getDefenders().contains(player))) {
                    continue;
                }
                if (input instanceof InputSelectEntitiesFromList<?> selectEntities
                        && !selectEntities.getValidChoices().contains(player)) {
                    continue;
                }
                final String id = "player:" + playerSeat;
                final JsonObject control = control(id, "selectPlayer", player.getName());
                control.addProperty("player", playerSeat);
                if (input instanceof InputAttack) {
                    final JsonObject value = new JsonObject();
                    value.addProperty("combatAction", "defender");
                    control.add("value", value);
                }
                controls.add(control);
                bindings.put(id, new ControlBinding("selectPlayer", action -> {
                    final Player current = playerAtSeat(playerSeat);
                    if (current == null) {
                        return ActionResult.reject("player is no longer present");
                    }
                    if (input instanceof InputSelectTargets targetInput && !targetInput.canSelectPlayer(current)) {
                        return ActionResult.reject("player is not a legal target for this decision");
                    }
                    if (input instanceof InputAttack && (game.getCombat() == null
                            || !game.getCombat().getDefenders().contains(current))) {
                        return ActionResult.reject("player is not a legal attack defender");
                    }
                    controller.selectPlayer(current.getView(), null);
                    return ActionResult.accept();
                }));
            }
        }


        if (input instanceof InputPayMana paymentInput) {
            final InputPayMana.PoolPaymentChoices payments = paymentInput.getPoolPaymentChoices();
            final Map<Integer, Integer> sourceNumbers = new LinkedHashMap<>();
            final Map<String, Set<Integer>> sourceNames = new LinkedHashMap<>();
            for (InputPayMana.PoolPaymentChoice payment : payments.choices()) {
                for (InputPayMana.PoolPaymentStep step : payment.steps()) {
                    if (step.mana() == null) { continue; }
                    final int sourceId = step.mana().getSourceCard().getId();
                    sourceNumbers.computeIfAbsent(sourceId, ignored -> sourceNumbers.size() + 1);
                    final String sourceName = InteractiveState.safeCardLabel(
                            step.mana().getSourceCard().getView(), human.getView());
                    sourceNames.computeIfAbsent(sourceName, ignored -> new LinkedHashSet<>()).add(sourceId);
                }
            }
            final List<JsonObject> paymentControls = new ArrayList<>();
            final Map<String, Integer> paymentLabels = new LinkedHashMap<>();
            int paymentIndex = 0;
            for (InputPayMana.PoolPaymentChoice payment : payments.choices()) {
                final String id = "payment:" + paymentIndex++;
                final JsonObject mana = new JsonObject();
                final JsonArray sources = new JsonArray();
                final Map<String, Integer> sourceCounts = new LinkedHashMap<>();
                int life = 0;
                final StringBuilder label = new StringBuilder("Pay ");
                for (InputPayMana.PoolPaymentStep step : payment.steps()) {
                    if (step.mana() == null) { life += 2; continue; }
                    final String color = MagicColor.toShortString(step.mana().getColor());
                    mana.addProperty(color, mana.has(color) ? mana.get(color).getAsInt() + 1 : 1);
                    final JsonObject source = new JsonObject();
                    source.addProperty("cardId", step.mana().getSourceCard().getId());
                    String sourceLabel = InteractiveState.safeCardLabel(
                            step.mana().getSourceCard().getView(), human.getView());
                    if (sourceNames.get(sourceLabel).size() > 1) {
                        sourceLabel += " (source " + sourceNumbers.get(step.mana().getSourceCard().getId()) + ")";
                    }
                    if (step.mana().isSnow()) { sourceLabel += " · snow"; }
                    if (step.mana().isRestricted()) { sourceLabel += " · restricted"; }
                    if (step.mana().triggersWhenSpent()) { sourceLabel += " · spend trigger"; }
                    if (step.mana().isPersistentMana()) { sourceLabel += " · persistent"; }
                    if (step.mana().isCombatMana()) { sourceLabel += " · combat mana"; }
                    if (step.mana().addsCounters(null)) { sourceLabel += " · counter bonus"; }
                    if (step.mana().addsKeywords(null)) { sourceLabel += " · ability bonus"; }
                    if (step.mana().getManaAbility() != null
                            && step.mana().getManaAbility().isCannotCounterPaidWith()) {
                        sourceLabel += " · counterspell protection";
                    }
                    source.addProperty("label", sourceLabel);
                    source.addProperty("color", color);
                    sources.add(source);
                    sourceCounts.merge(sourceLabel, 1, Integer::sum);
                }
                // Canonical color order keeps equivalent display costs identical
                // regardless of the internal legal payment sequence.
                for (byte color : ManaAtom.MANATYPES) {
                    final String symbol = MagicColor.toShortString(color);
                    if (!mana.has(symbol)) { continue; }
                    for (int i = 0; i < mana.get(symbol).getAsInt(); i++) {
                        label.append('{').append(symbol).append('}');
                    }
                }
                if (life > 0) { label.append(mana.size() > 0 ? " + " : "").append(life).append(" life"); }
                final JsonObject control = control(id, "choice", label.toString());
                final JsonObject value = new JsonObject();
                value.addProperty("payment", true);
                value.add("mana", mana);
                value.addProperty("life", life);
                value.add("sources", sources);
                final List<String> sourceSummary = new ArrayList<>();
                sourceCounts.forEach((source, count) -> sourceSummary.add(count > 1 ? count + " from " + source : source));
                value.addProperty("sourceSummary", String.join(", ", sourceSummary));
                value.addProperty("completeEnumeration", payments.complete());
                control.add("value", value);
                controls.add(control);
                paymentControls.add(control);
                paymentLabels.merge(label.toString(), 1, Integer::sum);
                bindings.put(id, new ControlBinding("choice", action ->
                        paymentInput.payPoolPaymentChoice(payment) ? ActionResult.accept()
                                : ActionResult.reject("Payment changed; choose again from the current mana pool")));
            }
            for (JsonObject control : paymentControls) {
                final String label = control.get("label").getAsString();
                if (paymentLabels.get(label) > 1) {
                    control.addProperty("label", label + " — "
                            + control.getAsJsonObject("value").get("sourceSummary").getAsString());
                }
            }
            // Keep individual controls available when the bounded search cannot
            // enumerate every payment, or the player still needs to produce mana.
            if (input instanceof InputPayManaOfCostPayment payment && payment.canPayManaWithLife()) {
                final String id = "player:" + humanSeat;
                final JsonObject control = control(id, "selectPlayer",
                        "Pay 2 life");
                control.addProperty("player", humanSeat);
                controls.add(control);
                bindings.put(id, new ControlBinding("selectPlayer", action -> {
                    if (!payment.canPayManaWithLife()) {
                        return ActionResult.reject("life cannot pay any remaining mana cost");
                    }
                    controller.selectPlayer(human.getView(), null);
                    return ActionResult.accept();
                }));
            }
            for (byte color : ManaAtom.MANATYPES) {
                if (human.getManaPool().getAmountOfColor(color) <= 0) {
                    continue;
                }
                final String id = "mana:" + color;
                final JsonObject control = control(id, "useMana",
                        "Use " + MagicColor.toShortString(color) + " mana");
                control.add("value", new JsonPrimitive(color));
                control.addProperty("paymentEnumerationComplete", payments.complete());
                controls.add(control);
                bindings.put(id, new ControlBinding("useMana", action -> {
                    if (human.getManaPool().getAmountOfColor(color) <= 0) {
                        return ActionResult.reject("mana is no longer in the pool");
                    }
                    controller.useMana(color);
                    return ActionResult.accept();
                }));
            }
            // InputPayMana's OK button is labelled Auto and invokes ComputerUtilMana.
            // It is deliberately absent: the browser-owned seat must pay manually.
            if (cancelEnabled) {
                controls.add(control("button:cancel", "cancel", sanitizeText(cancelLabel)));
                bindings.put("button:cancel", new ControlBinding("cancel", action -> {
                    controller.selectButtonCancel();
                    return ActionResult.accept();
                }));
            }
        } else if (input instanceof InputConfirm) {
            addConfirmButton(controls, bindings, "confirm:yes", okLabel, true, okEnabled);
            addConfirmButton(controls, bindings, "confirm:no", cancelLabel, false, cancelEnabled);
        } else {
            if (okEnabled) {
                final boolean priority = input instanceof InputPassPriority;
                final String type = priority ? "passPriority" : "ok";
                final String id = priority ? "priority:pass" : "button:ok";
                controls.add(control(id, type, sanitizeText(okLabel)));
                bindings.put(id, new ControlBinding(type, action -> {
                    if (priority) {
                        controller.passPriority();
                    } else {
                        controller.selectButtonOk();
                    }
                    return ActionResult.accept();
                }));
            }
            if (cancelEnabled && !(input instanceof InputLondonMulligan)) {
                controls.add(control("button:cancel", "cancel", sanitizeText(cancelLabel)));
                bindings.put("button:cancel", new ControlBinding("cancel", action -> {
                    controller.selectButtonCancel();
                    return ActionResult.accept();
                }));
            }
        }
        if ((input instanceof InputPassPriority || input instanceof InputPayMana)
                && controller.canUndoLastAction() && game.getStack().canUndoMana(human)) {
            controls.add(control("undo:mana", "undoMana", "Undo mana"));
            bindings.put("undo:mana", new ControlBinding("undoMana", action -> {
                // Never route through cancel: the priority cancel handler can
                // pass the rest of the turn when the undo entry has gone away.
                if (!controller.canUndoLastAction() || !game.getStack().canUndoMana(human)) {
                    return ActionResult.reject("This mana activation can no longer be undone");
                }
                if (!controller.tryUndoLastAction()) {
                    return ActionResult.reject("Forge could not undo this mana activation");
                }
                if (input instanceof InputPayMana payment
                        && controller.getInputProxy().getInput() == input) {
                    payment.showMessage();
                }
                return ActionResult.accept();
            }));
        }
        if (!game.isGameOver()) {
            controls.add(control("game:concede", "concede", "Concede game"));
            bindings.put("game:concede", new ControlBinding("concede", action -> {
                controller.concede();
                return ActionResult.accept();
            }));
        }
        return controls;
    }

    /** Keep legality and mutation in Forge while allowing blocker-first board input. */
    private void addBlockControls(final JsonArray controls,
                                  final Map<String, ControlBinding> bindings) {
        final Combat combat = game.getCombat();
        if (combat == null) {
            return;
        }
        for (Card blocker : human.getCreaturesInPlay()) {
            if (!blocker.getView().canBeShownTo(human.getView())) {
                continue;
            }
            final int blockerId = blocker.getId();
            if (combat.isBlocking(blocker)) {
                final String id = "combat:unblock:" + blockerId;
                final JsonObject control = control(id, "selectCard", "Remove block: "
                        + InteractiveState.safeCardLabel(blocker.getView(), human.getView()));
                control.addProperty("cardId", blockerId);
                final JsonObject value = new JsonObject();
                value.addProperty("combatAction", "unblock");
                value.addProperty("blockerId", blockerId);
                control.add("value", value);
                controls.add(control);
                bindings.put(id, new ControlBinding("selectCard", action -> {
                    final Card current = game.findById(blockerId);
                    if (current == null || current.getController() != human
                            || game.getCombat() != combat || !combat.isBlocking(current)) {
                        return ActionResult.reject("blocker is no longer assigned");
                    }
                    // InputBlock's existing right-click handler removes all assignments
                    // regardless of its currently highlighted attacker.
                    final ITriggerEvent remove = new ITriggerEvent() {
                        public int getButton() { return 3; }
                        public int getX() { return 0; }
                        public int getY() { return 0; }
                    };
                    return controller.selectCard(current.getView(), null, remove)
                            ? ActionResult.accept() : ActionResult.reject("Forge rejected block removal");
                }));
            }
            for (Card attacker : combat.getAttackers()) {
                if (!attacker.getView().canBeShownTo(human.getView())
                        || !CombatUtil.canBlock(attacker, blocker, combat)) {
                    continue;
                }
                final int attackerId = attacker.getId();
                final String id = "combat:block:" + blockerId + ":" + attackerId;
                final JsonObject control = control(id, "selectCard", "Block "
                        + InteractiveState.safeCardLabel(attacker.getView(), human.getView())
                        + " with " + InteractiveState.safeCardLabel(blocker.getView(), human.getView()));
                control.addProperty("cardId", blockerId);
                final JsonObject value = new JsonObject();
                value.addProperty("combatAction", "block");
                value.addProperty("blockerId", blockerId);
                value.addProperty("attackerId", attackerId);
                control.add("value", value);
                controls.add(control);
                bindings.put(id, new ControlBinding("selectCard", action -> {
                    final Card currentBlocker = game.findById(blockerId);
                    final Card currentAttacker = game.findById(attackerId);
                    if (game.getCombat() != combat || currentBlocker == null
                            || currentBlocker.getController() != human || currentAttacker == null
                            || !combat.isAttacking(currentAttacker)
                            || !CombatUtil.canBlock(currentAttacker, currentBlocker, combat)) {
                        return ActionResult.reject("block pair is no longer legal");
                    }
                    // One user-selected pair, executed by the two existing Forge input
                    // operations under the request's in-flight guard. No policy chooses it.
                    if (!controller.selectCard(currentAttacker.getView(), null, null)) {
                        return ActionResult.reject("Forge rejected attacker selection");
                    }
                    return controller.selectCard(currentBlocker.getView(), null, null)
                            ? ActionResult.accept() : ActionResult.reject("Forge rejected block assignment");
                }));
            }
        }
    }

    private void addConfirmButton(final JsonArray controls,
                                  final Map<String, ControlBinding> bindings,
                                  final String id, final String label, final boolean answer,
                                  final boolean enabled) {
        if (!enabled) {
            return;
        }
        final JsonObject control = control(id, "confirm", sanitizeText(label));
        control.addProperty("value", answer);
        controls.add(control);
        bindings.put(id, new ControlBinding("confirm", action -> {
            if (!action.has("confirm") || !action.get("confirm").isJsonPrimitive()
                    || action.get("confirm").getAsBoolean() != answer) {
                return ActionResult.reject("confirm value does not match control");
            }
            if (answer) {
                controller.selectButtonOk();
            } else {
                controller.selectButtonCancel();
            }
            return ActionResult.accept();
        }));
    }

    private JsonObject requestBody(final String requestId, final String kind,
                                   final String inputClass, final String title,
                                   final String message, final Integer min, final Integer max,
                                   final boolean cancellable, final JsonArray controls) {
        final JsonObject body = new JsonObject();
        body.addProperty("requestId", requestId);
        body.addProperty("kind", kind);
        body.addProperty("inputClass", inputClass);
        body.addProperty("seat", humanSeat);
        body.add("view", visibleState());
        final JsonObject prompt = new JsonObject();
        if (title != null && !title.isBlank()) {
            prompt.addProperty("title", sanitizeText(title));
        }
        // Forge's generic dialogs sometimes put all instructions in the title.
        // Keep the wire prompt meaningful even when their message is blank.
        final String promptMessage = message != null && !message.isBlank() ? message
                : title != null && !title.isBlank() ? title : "Choose an action";
        // revealMessage already sanitizes its heading and appends only the
        // explicitly offered objects. Re-scrubbing by hidden duplicate names
        // would erase the very identities Forge authorized us to reveal.
        prompt.addProperty("message", "modal:reveal".equals(inputClass)
                ? promptMessage : sanitizeText(promptMessage));
        if (min != null && min >= 0) {
            prompt.addProperty("min", min);
        }
        if (max != null && max >= 0) {
            prompt.addProperty("max", max);
        }
        prompt.addProperty("cancellable", cancellable);
        body.add("prompt", prompt);
        body.add("controls", controls);
        return body;
    }

    private JsonObject ask(final String kind, final String inputClass, final String title,
                           final String message, final Integer min, final Integer max,
                           final boolean cancellable, final JsonArray controls,
                           final Function<JsonObject, String> validator) {
        if (channel.isEnded()) {
            throw new InteractiveAbort("protocol session ended");
        }
        if (game != null && !game.isGameOver() && !hasControl(controls, "game:concede")) {
            controls.add(control("game:concede", "concede", "Concede game"));
        }
        final String requestId = nextRequestId();
        final ModalRequest modal = new ModalRequest(requestId, kind, validator);
        if (!modalRequest.compareAndSet(null, modal)) {
            throw unsupported(inputClass, "nested blocking GUI callbacks are unsupported", kind);
        }
        activeRequest.set(null);
        lastInputFingerprint = "";
        try {
            channel.send("request", requestBody(requestId, kind, inputClass, title, message,
                    min, max, cancellable, controls));
            return modal.answer.join();
        } catch (InteractiveProtocol.ProtocolException e) {
            modalRequest.compareAndSet(modal, null);
            fail("eof", e.getMessage(), requestId, inputClass, kind, e);
            throw new InteractiveAbort(e.getMessage(), e);
        } catch (CompletionException e) {
            if (e.getCause() instanceof GameConceded conceded) {
                throw conceded;
            }
            throw new InteractiveAbort("modal request aborted", e.getCause());
        } finally {
            modalRequest.compareAndSet(modal, null);
            lastInputFingerprint = "";
            scheduleInputPublish();
        }
    }

    private JsonObject visibleState() {
        final JsonObject view = InteractiveState.encode(game, human);
        view.addProperty("seat", humanSeat);
        normalizePlayerNamespaces(view);
        return view;
    }

    private void normalizePlayerNamespaces(final JsonObject view) {
        if (view.has("players") && view.get("players").isJsonArray()) {
            for (JsonElement playerElement : view.getAsJsonArray("players")) {
                if (!playerElement.isJsonObject()) {
                    continue;
                }
                final JsonObject player = playerElement.getAsJsonObject();
                for (String zone : List.of("hand", "battlefield", "graveyard", "exile", "command")) {
                    addCardSeatFields(player.get(zone));
                }
            }
        }
        if (view.has("stack") && view.get("stack").isJsonArray()) {
            for (JsonElement entry : view.getAsJsonArray("stack")) {
                if (entry.isJsonObject()) {
                    addSeatForRawPlayerId(entry.getAsJsonObject(), "controller", "controllerSeat");
                }
            }
        }
    }

    private void addCardSeatFields(final JsonElement zone) {
        if (zone == null || !zone.isJsonArray()) {
            return;
        }
        for (JsonElement element : zone.getAsJsonArray()) {
            if (element.isJsonObject()) {
                addSeatForRawPlayerId(element.getAsJsonObject(), "controller", "controllerSeat");
                addSeatForRawPlayerId(element.getAsJsonObject(), "owner", "ownerSeat");
            }
        }
    }

    private void addSeatForRawPlayerId(final JsonObject object, final String rawField,
                                       final String seatField) {
        if (!object.has(rawField) || !object.get(rawField).isJsonPrimitive()) {
            return;
        }
        final int rawId;
        try {
            rawId = object.get(rawField).getAsInt();
        } catch (RuntimeException ignored) {
            return;
        }
        int seat = 0;
        for (Player player : game.getRegisteredPlayers()) {
            if (player.getId() == rawId) {
                object.addProperty(seatField, seat);
                return;
            }
            seat++;
        }
    }

    private void emitState(final String reason) {
        // These callbacks run during mutations as well as on the EDT. Forge's
        // model is not a concurrent snapshot collection. Full views belong at
        // presented human requests and after match.startGame completes.
        scheduleInputPublish();
    }

    void emitFinalState() {
        if (channel.isEnded() || game == null || human == null) {
            return;
        }
        try {
            final JsonObject body = new JsonObject();
            body.addProperty("reason", "game-completed");
            body.add("view", visibleState());
            channel.send("state", body);
        } catch (InteractiveProtocol.ProtocolException e) {
            fail("eof", e.getMessage(), null, "state", null, e);
        } catch (Throwable e) {
            fail("engine", "could not encode Forge state: " + safeThrowable(e),
                    null, "state", null, e);
        }
    }

    private Player playerAtSeat(final int seat) {
        int index = 0;
        for (Player player : game.getPlayers()) {
            if (index++ == seat) {
                return player;
            }
        }
        return null;
    }

    private String sanitizeText(final String text) {
        String result = Objects.requireNonNullElse(text, "");
        if (game == null || human == null) {
            return result;
        }
        final Set<Integer> authorized = explicitlyOfferedCards.get();
        final List<InteractiveText.CardLabel> labels = new ArrayList<>();
        game.forEachCardInGame(card -> {
            labels.add(new InteractiveText.CardLabel(card.getId(), card.getName(),
                    authorized.contains(card.getId())
                    || InteractiveState.mayReceiveIdentity(card.getView(), human.getView())));
            return true;
        });
        return InteractiveText.sanitize(result, labels);
    }

    private static String inputClassName(final Input input) {
        final String name = input.getClass().getSimpleName();
        // Forge's discard input is an anonymous subclass. Its simple name is
        // empty, but the wire requires a diagnostic identity for every input.
        return name.isBlank() ? input.getClass().getName() : name;
    }

    private String kindFor(final Input input) {
        final String name = inputClassName(input);
        if (input instanceof InputPayMana) {
            return "mana";
        }
        if (input instanceof InputSelectTargets) {
            return "target";
        }
        if (input instanceof InputAttack || input instanceof InputBlock) {
            return "combat";
        }
        if (input instanceof InputPassPriority) {
            return "priority";
        }
        if (name.contains("Mulligan") || name.contains("StartingHand")) {
            return "mulligan";
        }
        if (input instanceof InputConfirm) {
            return "confirm";
        }
        return "choice";
    }

    private String nextRequestId() {
        return "r" + requestSequence.incrementAndGet();
    }

    private InteractiveAbort unsupported(final String method, final String detail,
                                         final String kind) {
        fail("unsupported", detail, null, method, kind, null);
        return new InteractiveAbort(detail);
    }

    private void fail(final String code, final String message, final String requestId,
                      final String method, final String kind, final Throwable failure) {
        if (!aborting.compareAndSet(false, true)) {
            return;
        }
        if (failure != null) {
            System.err.println("[forge.interactive] " + method + ": " + safeThrowable(failure));
            failure.printStackTrace(System.err);
        }
        channel.fatal(code, message, requestId, method, kind);
        final ModalRequest modal = modalRequest.getAndSet(null);
        if (modal != null) {
            modal.answer.completeExceptionally(new InteractiveAbort(message));
        }
        final PlayerControllerHuman currentController = controller;
        if (currentController != null) {
            currentController.macros().cancelCurrentMacro();
            currentController.getInputQueue().onGameOver(true);
        }
        final Game currentGame = game;
        if (currentGame != null && !currentGame.isGameOver()) {
            try {
                currentGame.setGameOver(GameEndReason.Draw);
            } catch (Throwable secondary) {
                System.err.println("[forge.interactive] could not stop failed game: "
                        + safeThrowable(secondary));
            }
        }
        actions.shutdownNow();
    }

    @Override
    public void close() {
        actions.shutdownNow();
    }

    private static JsonObject control(final String id, final String type, final String label) {
        final JsonObject control = new JsonObject();
        control.addProperty("controlId", id);
        control.addProperty("type", type);
        control.addProperty("label", Objects.requireNonNullElse(label, type));
        control.addProperty("enabled", true);
        return control;
    }

    private static JsonObject item(final String id, final String label) {
        final JsonObject item = new JsonObject();
        item.addProperty("id", id);
        item.addProperty("label", label);
        return item;
    }

    private static boolean hasControl(final JsonArray controls, final String controlId) {
        for (JsonElement element : controls) {
            if (element.isJsonObject()
                    && controlId.equals(string(element.getAsJsonObject(), "controlId"))) {
                return true;
            }
        }
        return false;
    }

    private static String string(final JsonObject object, final String field) {
        return object.has(field) && object.get(field).isJsonPrimitive()
                && object.getAsJsonPrimitive(field).isString()
                ? object.get(field).getAsString() : null;
    }

    private static String safeThrowable(final Throwable throwable) {
        if (throwable == null) {
            return "unknown failure";
        }
        return throwable.getClass().getSimpleName() + ": "
                + Objects.requireNonNullElse(throwable.getMessage(), "(no message)");
    }

    private record ActionResult(boolean accepted, String reason) {
        static ActionResult accept() {
            return new ActionResult(true, null);
        }

        static ActionResult reject(final String reason) {
            return new ActionResult(false, reason);
        }
    }

    @FunctionalInterface
    private interface ActionHandler {
        ActionResult apply(JsonObject action);
    }

    private record ControlBinding(String type, ActionHandler handler) {
    }

    private static final class ActiveRequest {
        private final String requestId;
        private final String kind;
        @SuppressWarnings("unused")
        private final Input input;
        private final Map<String, ControlBinding> controls;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private ActiveRequest(final String requestId, final String kind, final Input input,
                              final Map<String, ControlBinding> controls) {
            this.requestId = requestId;
            this.kind = kind;
            this.input = input;
            this.controls = Map.copyOf(controls);
        }
    }

    private static final class ModalRequest {
        private final String requestId;
        private final String kind;
        private final Function<JsonObject, String> validator;
        private final CompletableFuture<JsonObject> answer = new CompletableFuture<>();
        private final AtomicBoolean claimed = new AtomicBoolean();

        private ModalRequest(final String requestId, final String kind,
                             final Function<JsonObject, String> validator) {
            this.requestId = requestId;
            this.kind = kind;
            this.validator = validator;
        }
    }

    private static final class PendingInput {
        private final JsonObject original;
        private final CompletableFuture<AckResult> result = new CompletableFuture<>();

        private PendingInput(final JsonObject original) {
            this.original = original.deepCopy();
        }
    }

    private record AckResult(String requestId, String kind, boolean accepted, String reason) {
    }

    static final class InteractiveAbort extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InteractiveAbort(final String message) {
            super(message);
        }

        InteractiveAbort(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    static final class GameConceded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        GameConceded() {
            super("game conceded");
        }
    }

    @Subscribe
    public void receiveGameEvent(final GameEvent event) {
        if (event instanceof GameEventGameOutcome && controller != null) {
            controller.macros().cancelCurrentMacro();
            controller.getInputQueue().onGameOver(true);
        }
        onEngineEvent(event);
        if (event instanceof GameEventGameFinished) {
            finishGame();
        }
    }

    @Override
    public boolean isLibgdxPort() {
        return false;
    }

    @Override
    public boolean supportsAutoPayMana() {
        // Browser users select whole payments themselves; no Auto button or
        // desktop AI auto-tap preview is presented. Do not compute that plan.
        return false;
    }

    @Override
    public boolean promptsForCosmeticExileOrder() {
        return false;
    }

    @Override
    public boolean defersLondonMulliganTuckUntilKeep() {
        return true;
    }

    @Override
    public boolean supportsFloatingHandReveal() {
        // Send the actual revealed cards through our informational reveal path.
        // There is no desktop FloatingZone for an OK / End Turn dialog to cover.
        return false;
    }

    @Override
    public void setGameView(final forge.game.GameView gameView) {
        super.setGameView(gameView);
        scheduleInputPublish();
    }

    @Override
    protected void updateCurrentPlayer(final PlayerView player) {
        scheduleInputPublish();
    }

    @Override
    public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final PhaseType phase) {
        return false;
    }

    @Override
    public PlayerZoneUpdates openZones(final PlayerView owner,
                                       final Collection<ZoneType> zones,
                                       final Map<PlayerView, Object> players,
                                       final boolean backupLastZones) {
        final PlayerZoneUpdates opened = new PlayerZoneUpdates();
        final Collection<PlayerView> owners = players == null || players.isEmpty()
                ? (owner == null ? Collections.emptyList() : List.of(owner))
                : players.keySet();
        for (PlayerView player : owners) {
            for (ZoneType zone : zones) {
                opened.add(new PlayerZoneUpdate(player, zone));
            }
        }
        emitState("open-zones");
        return opened;
    }

    @Override
    public void restoreOldZones(final PlayerView playerView,
                                final PlayerZoneUpdates playerZoneUpdates) {
        emitState("restore-zones");
    }

    @Override
    public void openView(final TrackableCollection<PlayerView> myPlayers) {
        emitState("open-view");
    }

    @Override
    public void showCombat() {
        emitState("combat");
        scheduleInputPublish();
    }

    @Override
    public void finishGame() {
        activeRequest.set(null);
        emitState("game-finished");
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message,
                                  final CardView card) {
        promptMessage = Objects.requireNonNullElse(message, "");
        promptCard = card;
        presentedInput = controller == null ? null : controller.getInputProxy().getInput();
        scheduleInputPublish();
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1,
                              final String label2, final boolean enable1,
                              final boolean enable2, final boolean focus1) {
        okLabel = Objects.requireNonNullElse(label1, "OK");
        cancelLabel = Objects.requireNonNullElse(label2, "Cancel");
        okEnabled = enable1;
        cancelEnabled = enable2;
        scheduleInputPublish();
    }

    @Override
    public void flashIncorrectAction() {
        emitNotice("warning", "Forge rejected that action", "incorrect-action");
        scheduleInputPublish();
    }

    @Override
    public void alertUser() {
        emitNotice("notice", "Forge requires your attention", "alert");
    }

    @Override
    public void updatePhase(final boolean saveState) {
        emitState("phase");
        scheduleInputPublish();
    }

    @Override
    public void updateTurn(final PlayerView player) {
        emitState("turn");
        scheduleInputPublish();
    }

    @Override
    public void updatePlayerControl() {
        emitState("player-control");
        scheduleInputPublish();
    }

    @Override
    public void enableOverlay() {
        emitState("overlay-enabled");
    }

    @Override
    public void disableOverlay() {
        emitState("overlay-disabled");
    }

    @Override
    public void showManaPool(final PlayerView player) {
        emitState("mana-pool-visible");
        scheduleInputPublish();
    }

    @Override
    public void hideManaPool(final PlayerView player) {
        emitState("mana-pool-hidden");
        scheduleInputPublish();
    }

    @Override
    public void updateStack() {
        emitState("stack");
        scheduleInputPublish();
    }

    @Override
    public Iterable<PlayerZoneUpdate> tempShowZones(final PlayerView owner,
                                                    final Iterable<PlayerZoneUpdate> zones) {
        emitState("temporary-zones-visible");
        scheduleInputPublish();
        return zones;
    }

    @Override
    public void hideZones(final PlayerView owner, final Iterable<PlayerZoneUpdate> zones) {
        emitState("temporary-zones-hidden");
        scheduleInputPublish();
    }

    @Override
    public void updateZones(final Iterable<PlayerZoneUpdate> zones) {
        emitState("zones");
        scheduleInputPublish();
    }

    @Override
    public void updateCards(final Iterable<CardView> cards) {
        emitState("cards");
        scheduleInputPublish();
    }

    @Override
    public GameState getGamestate() {
        return null;
    }

    @Override
    public void updateManaPool(final Iterable<PlayerView> players) {
        emitState("mana-pool");
        scheduleInputPublish();
    }

    @Override
    public void updateLives(final Iterable<PlayerView> players) {
        emitState("lives");
        scheduleInputPublish();
    }

    @Override
    public void updateShards(final Iterable<PlayerView> players) {
        emitState("shards");
        scheduleInputPublish();
    }

    @Override
    public void setPanelSelection(final CardView hostCard) {
        promptCard = hostCard;
        scheduleInputPublish();
    }

    @Override
    public void setHighlighted(final Iterable<GameEntityView> entities, final boolean highlighted) {
        super.setHighlighted(entities, highlighted);
        scheduleInputPublish();
    }

    @Override
    public void setSelectables(final Iterable<CardView> cards, final int min, final int max) {
        super.setSelectables(cards, min, max);
        scheduleInputPublish();
    }

    @Override
    public void clearSelectables() {
        super.clearSelectables();
        scheduleInputPublish();
    }

    @Override
    public void setWeaklySelectable(final Iterable<CardView> cards) {
        super.setWeaklySelectable(cards);
        scheduleInputPublish();
    }

    @Override
    public void clearWeaklySelectable() {
        super.clearWeaklySelectable();
        scheduleInputPublish();
    }

    private boolean canChooseOfferedAbility(final SpellAbilityView view) {
        final SpellAbility actual = controller.getBrowserAbility(view);
        // Forge also invokes this chooser while preparing an engine-triggered
        // ability. WrappedAbility delegates canPlay() to AbilitySub, which is
        // deliberately false: it cannot be manually activated from priority.
        // Trust only the current controller's exact engine-offered trigger,
        // never a null mouse event or an arbitrary client-provided ability.
        return (view.canPlay() || (actual != null && actual.isTrigger()))
                && controller.mayAffordAbility(view);
    }

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard,
                                             final List<SpellAbilityView> abilities,
                                             final ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) {
            return null;
        }
        if (abilities.stream().noneMatch(this::canChooseOfferedAbility)) return null;
        if (abilities.size() == 1) {
            final SpellAbilityView only = abilities.get(0);
            if (!canChooseOfferedAbility(only)) return null;
            if (triggerEvent == null) {
                return only;
            }
            if (!only.promptIfOnlyPossibleAbility()) {
                return canChooseOfferedAbility(only) ? only : null;
            }
        }

        final JsonArray controls = new JsonArray();
        final Map<String, SpellAbilityView> byId = new LinkedHashMap<>();
        int index = 0;
        for (SpellAbilityView ability : abilities) {
            if (!canChooseOfferedAbility(ability)) {
                index++;
                continue;
            }
            final String id = "ability:" + index++;
            byId.put(id, ability);
            final JsonObject control = control(id, "selectAbility",
                    safeAbilityLabel(ability));
            control.addProperty("abilityId", ability.getId());
            controls.add(control);
        }
        controls.add(control("ability:cancel", "cancel", "Cancel"));
        if (byId.isEmpty()) {
            return null;
        }
        final JsonObject answer = ask("choice",
                triggerEvent == null ? "modal:getAbilityToPlay" : "modal:getAbilityToPlay:trigger",
                "Choose ability", hostCard == null ? "Choose an ability"
                        : "Choose an ability for " + InteractiveState.safeCardLabel(hostCard, human.getView()),
                0, 1, true, controls, action -> {
                    final String type = string(action, "type");
                    final String id = string(action, "controlId");
                    if ("cancel".equals(type) && "ability:cancel".equals(id)) {
                        return null;
                    }
                    if (!"selectAbility".equals(type) || !byId.containsKey(id)) {
                        return "answer must select an advertised playable ability or cancel";
                    }
                    return null;
                });
        final String selected = string(answer, "controlId");
        return "ability:cancel".equals(selected) ? null : byId.get(selected);
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker,
                                                     final List<CardView> blockers,
                                                     final int damage,
                                                     final GameEntityView defender,
                                                     final boolean overrideOrder,
                                                     final boolean maySkip) {
        if (damage <= 0) {
            return Collections.emptyMap();
        }
        if (blockers == null || blockers.isEmpty()) {
            throw unsupported("assignCombatDamage", "damage assignment had no blockers", "combat");
        }

        final boolean deathtouch = attacker.getCurrentState().hasDeathtouch();
        final boolean divideDamage = attacker.getCurrentState().hasDivideDamage();
        final boolean canHitDefender = defender != null
                && (attacker.getCurrentState().hasTrample()
                || (divideDamage && overrideOrder));
        final JsonArray items = new JsonArray();
        final LinkedHashMap<String, CardView> recipients = new LinkedHashMap<>();
        int index = 0;
        for (CardView blocker : blockers) {
            final String id = "blocker:" + index++ + ":" + blocker.getId();
            recipients.put(id, blocker);
            final JsonObject item = item(id, InteractiveState.safeCardLabel(blocker, human.getView()));
            final JsonObject bounds = new JsonObject();
            bounds.addProperty("min", 0);
            bounds.addProperty("max", damage);
            bounds.addProperty("lethal", lethalDamage(blocker, deathtouch));
            item.add("value", bounds);
            items.add(item);
        }
        final String defenderId = "defender";
        if (canHitDefender) {
            final JsonObject item = item(defenderId, safeObjectLabel(defender, null));
            final JsonObject bounds = new JsonObject();
            bounds.addProperty("min", 0);
            bounds.addProperty("max", damage);
            item.add("value", bounds);
            items.add(item);
        }

        final JsonObject allocation = control("combat-damage", "order", "Assign combat damage");
        allocation.add("items", items);
        final JsonObject mode = new JsonObject();
        mode.addProperty("mode", "allocation");
        mode.addProperty("total", damage);
        mode.addProperty("atLeastOne", false);
        mode.addProperty("combat", true);
        mode.addProperty("overrideOrder", overrideOrder);
        mode.addProperty("divideDamage", divideDamage);
        allocation.add("value", mode);
        final JsonArray controls = new JsonArray();
        controls.add(allocation);
        if (maySkip) {
            controls.add(control("combat-damage:skip", "cancel", "Skip damage assignment"));
        }
        final JsonObject answer = ask("combat", "modal:assignCombatDamage",
                "Assign combat damage", "Assign exactly " + damage + " damage from "
                        + InteractiveState.safeCardLabel(attacker, human.getView()),
                damage, damage, maySkip, controls, action -> validateCombatAllocation(action,
                        recipients, defenderId, canHitDefender, damage, deathtouch,
                        divideDamage, overrideOrder, maySkip));
        if ("cancel".equals(string(answer, "type"))) {
            return null;
        }
        final Map<String, Integer> assigned = parseAllocation(answer);
        final Map<CardView, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, CardView> entry : recipients.entrySet()) {
            result.put(entry.getValue(), assigned.getOrDefault(entry.getKey(), 0));
        }
        if (canHitDefender) {
            result.put(null, assigned.getOrDefault(defenderId, 0));
        }
        return result;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource,
                                                    final Map<Object, Integer> targets,
                                                    final int amount,
                                                    final boolean atLeastOne,
                                                    final String amountLabel) {
        if (amount <= 0) {
            return Collections.emptyMap();
        }
        if (targets == null || targets.isEmpty()) {
            throw unsupported("assignGenericAmount", "amount assignment had no targets", "choice");
        }
        final JsonArray items = new JsonArray();
        final LinkedHashMap<String, Object> byId = new LinkedHashMap<>();
        int index = 0;
        int minimumTotal = 0;
        for (Map.Entry<Object, Integer> entry : targets.entrySet()) {
            final String id = stableObjectId(entry.getKey(), index++);
            byId.put(id, entry.getKey());
            final int maximum = entry.getValue() == null ? amount : entry.getValue();
            final int minimum = atLeastOne ? 1 : 0;
            if (maximum < minimum) {
                throw unsupported("assignGenericAmount",
                        "a target maximum is below its required minimum", "choice");
            }
            minimumTotal += minimum;
            final JsonObject option = item(id, safeObjectLabel(entry.getKey(), null));
            final JsonObject bounds = new JsonObject();
            bounds.addProperty("min", minimum);
            bounds.addProperty("max", maximum);
            option.add("value", bounds);
            items.add(option);
        }
        if (minimumTotal > amount) {
            throw unsupported("assignGenericAmount",
                    "per-target minimum exceeds amount to assign", "choice");
        }
        final JsonObject allocation = control("generic-allocation", "order",
                "Assign " + sanitizeText(amountLabel));
        allocation.add("items", items);
        final JsonObject mode = new JsonObject();
        mode.addProperty("mode", "allocation");
        mode.addProperty("total", amount);
        mode.addProperty("atLeastOne", atLeastOne);
        allocation.add("value", mode);
        final JsonArray controls = new JsonArray();
        controls.add(allocation);
        final JsonObject answer = ask("order", "modal:assignGenericAmount",
                "Assign " + sanitizeText(amountLabel), "Assign exactly " + amount,
                amount, amount, false, controls,
                action -> validateGenericAllocation(action, byId, targets, amount, atLeastOne));
        final Map<String, Integer> assigned = parseAllocation(answer);
        final Map<Object, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : byId.entrySet()) {
            result.put(entry.getValue(), assigned.getOrDefault(entry.getKey(), 0));
        }
        return result;
    }

    @Override
    public void message(final String message, final String title) {
        awaitAcknowledgement(title, message, "modal:message");
    }

    @Override
    public void showErrorDialog(final String message, final String title) {
        awaitAcknowledgement(title, message, "modal:error");
    }

    @Override
    public boolean showConfirmDialog(final String message, final String title,
                                     final String yesButtonText, final String noButtonText,
                                     final boolean defaultYes) {
        return askConfirm(title, message, yesButtonText, noButtonText,
                "modal:showConfirmDialog");
    }

    @Override
    public int showOptionDialog(final String message, final String title,
                                final FSkinProp icon, final List<String> options,
                                final int defaultOption) {
        if (options == null || options.isEmpty()) {
            return -1;
        }
        final List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            indices.add(i);
        }
        final List<Integer> result = chooseOpaque(title, message, 1, 1, indices, null,
                value -> options.get(value), "modal:showOptionDialog");
        return result.isEmpty() ? -1 : result.get(0);
    }

    @Override
    public String showInputDialog(final String message, final String title,
                                  final FSkinProp icon, final String initialInput,
                                  final List<String> inputOptions, final boolean isNumeric) {
        if (inputOptions != null && !inputOptions.isEmpty()) {
            final List<String> result = chooseOpaque(title, message, 0, 1,
                    inputOptions, initialInput == null ? null : List.of(initialInput),
                    value -> value, "modal:showInputDialog:options");
            return result.isEmpty() ? null : result.get(0);
        }
        if (!isNumeric) {
            throw unsupported("showInputDialog", "free-form text input is not supported", "choice");
        }
        final JsonObject number = control("number", "number", sanitizeText(message));
        number.addProperty("min", Integer.MIN_VALUE);
        number.addProperty("max", Integer.MAX_VALUE);
        final JsonArray controls = new JsonArray();
        controls.add(number);
        controls.add(control("number:cancel", "cancel", "Cancel"));
        final JsonObject answer = ask("number", "modal:showInputDialog:number", title,
                message, Integer.MIN_VALUE, Integer.MAX_VALUE, true, controls, action -> {
                    if ("cancel".equals(string(action, "type"))
                            && "number:cancel".equals(string(action, "controlId"))) {
                        return null;
                    }
                    if (!"number".equals(string(action, "type"))
                            || !"number".equals(string(action, "controlId"))
                            || !isIntegralNumber(action.get("number"))) {
                        return "answer must be a 32-bit integer or cancel";
                    }
                    try {
                        action.get("number").getAsInt();
                    } catch (RuntimeException e) {
                        return "number is outside the 32-bit integer range";
                    }
                    return null;
                });
        return "cancel".equals(string(answer, "type"))
                ? null : Integer.toString(answer.get("number").getAsInt());
    }

    @Override
    public boolean confirm(final CardView card, final String question,
                           final boolean defaultIsYes, final List<String> options) {
        final String yes = options != null && !options.isEmpty() ? options.get(0) : "Yes";
        final String no = options != null && options.size() > 1 ? options.get(1) : "No";
        final String message = (card == null ? "" : InteractiveState.safeCardLabel(card, human.getView())
                + "\n") + question;
        return askConfirm("Forge", message, yes, no, "modal:confirm");
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max,
                                  final List<T> choices, final List<T> selected,
                                  final FSerializableFunction<T, String> display) {
        if (min == -1 && max == -1) {
            final Set<Integer> previous = authorizeOfferedCards(choices);
            try {
                awaitAcknowledgement("Forge", revealMessage(message, choices, display),
                        "modal:reveal");
            } finally {
                explicitlyOfferedCards.set(previous);
            }
            return Collections.emptyList();
        }
        return chooseOpaque("Forge", message, min, max, choices, selected, display,
                "modal:getChoices");
    }

    <T> List<T> globalChoices(final String message, final int min, final int max,
                              final Collection<T> choices, final Collection<T> selected,
                              final FSerializableFunction<T, String> display) {
        return chooseOpaque("Forge", message, min, max,
                choices == null ? null : new ArrayList<>(choices),
                selected == null ? null : new ArrayList<>(selected), display,
                "global:getChoices");
    }

    @Override
    public <T> OrderResult<T> order(final String title, final String top,
                                    final int remainingObjectsMin,
                                    final int remainingObjectsMax,
                                    final List<T> sourceChoices,
                                    final List<T> destChoices,
                                    final CardView referenceCard,
                                    final boolean sideboardingMode,
                                    final boolean showRememberCheckbox) {
        final List<T> source = sourceChoices == null ? Collections.emptyList() : sourceChoices;
        final List<T> destination = destChoices == null ? Collections.emptyList() : destChoices;
        final List<T> all = new ArrayList<>(source.size() + destination.size());
        all.addAll(source);
        all.addAll(destination);
        final List<String> ids = new ArrayList<>(all.size());
        final JsonArray items = new JsonArray();
        for (int i = 0; i < all.size(); i++) {
            final String id = "item:" + i;
            ids.add(id);
            items.add(item(id, offeredObjectLabel(all.get(i), null)));
        }
        final int total = all.size();
        final int minSelected;
        final int maxSelected;
        if (remainingObjectsMax < 0) {
            minSelected = 0;
            maxSelected = total;
        } else {
            minSelected = Math.max(0, total - remainingObjectsMax);
            maxSelected = Math.min(total, total - Math.max(0, remainingObjectsMin));
        }
        final JsonObject order = control("selection-order", "order", sanitizeText(top));
        order.add("items", items);
        final JsonObject mode = new JsonObject();
        mode.addProperty("mode", "selectionOrder");
        mode.addProperty("minSelected", minSelected);
        mode.addProperty("maxSelected", maxSelected);
        final JsonArray initial = new JsonArray();
        for (int i = source.size(); i < all.size(); i++) {
            initial.add(ids.get(i));
        }
        mode.add("initialIds", initial);
        mode.add("requiredIds", new JsonArray());
        mode.addProperty("sideboarding", sideboardingMode);
        mode.addProperty("rememberAvailable", showRememberCheckbox);
        order.add("value", mode);
        final JsonArray controls = new JsonArray();
        controls.add(order);
        final Set<String> known = Set.copyOf(ids);
        final JsonObject answer = ask("order", "modal:order", title, top,
                minSelected, maxSelected, false, controls,
                action -> validateOrderedIds(action, "selection-order", known,
                        minSelected, maxSelected, false));
        final List<String> selectedIds = parseStringArray(answer.get("order"));
        final List<T> result = new ArrayList<>(selectedIds.size());
        for (String id : selectedIds) {
            result.add(all.get(Integer.parseInt(id.substring("item:".length()))));
        }
        // "remember" changes only future GUI convenience, never the current game result.
        // Keeping it false guarantees that a later trigger is not silently auto-answered.
        return new IGuiGame.OrderResult<>(result, false);
    }

    <T> List<T> globalOrder(final String title, final String top,
                            final int remainingObjectsMin, final int remainingObjectsMax,
                            final List<T> sourceChoices, final List<T> destChoices) {
        return order(title, top, remainingObjectsMin, remainingObjectsMax, sourceChoices,
                destChoices, null, false, false).ordered();
    }

    @Override
    public List<PaperCard> sideboard(final CardPool sideboard, final CardPool main,
                                     final String message) {
        return order("Sideboard: " + sanitizeText(message), "Main deck", -1, -1,
                sideboard.toFlatList(), main.toFlatList(), null, true, false).ordered();
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(final String title,
                                                       final List<? extends GameEntityView> options,
                                                       final DelayedReveal delayedReveal,
                                                       final boolean optional) {
        if (delayedReveal != null) {
            reveal(delayedReveal.getMessagePrefix(), delayedReveal.getCards());
        }
        final List<? extends GameEntityView> result = chooseOpaque("Forge", title,
                optional ? 0 : 1, 1, options, null, null,
                "modal:chooseSingleEntityForEffect");
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title,
                                                        final List<? extends GameEntityView> options,
                                                        final int min, final int max,
                                                        final DelayedReveal delayedReveal) {
        if (delayedReveal != null) {
            reveal(delayedReveal.getMessagePrefix(), delayedReveal.getCards());
        }
        final List<? extends GameEntityView> selected = chooseOpaque("Forge", title, min, max,
                options, null, null, "modal:chooseEntitiesForEffect");
        return new ArrayList<>(selected);
    }

    @Override
    public List<CardView> manipulateCardList(final String title,
                                             final Iterable<CardView> cards,
                                             final Iterable<CardView> manipulable,
                                             final boolean toTop, final boolean toBottom,
                                             final boolean toAnywhere) {
        final List<CardView> all = copyIterable(cards);
        final Set<CardView> movableCards = new LinkedHashSet<>(copyIterable(manipulable));
        final JsonArray items = new JsonArray();
        final Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < all.size(); i++) {
            final String id = "card-position:" + i;
            ids.add(id);
            final JsonObject option = item(id, offeredObjectLabel(all.get(i), null));
            final JsonObject itemState = new JsonObject();
            itemState.addProperty("movable", movableCards.contains(all.get(i)));
            option.add("value", itemState);
            items.add(option);
        }
        final JsonObject order = control("card-order", "order", sanitizeText(title));
        order.add("items", items);
        final JsonObject mode = new JsonObject();
        mode.addProperty("mode", "exactOrder");
        mode.addProperty("toTop", toTop);
        mode.addProperty("toBottom", toBottom);
        mode.addProperty("toAnywhere", toAnywhere);
        order.add("value", mode);
        final JsonArray controls = new JsonArray();
        controls.add(order);
        final JsonObject answer = ask("order", "modal:manipulateCardList", title, title,
                all.size(), all.size(), false, controls,
                action -> {
                    final String structural = validateOrderedIds(action, "card-order", ids,
                            all.size(), all.size(), true);
                    if (structural != null) {
                        return structural;
                    }
                    return validateManipulatedOrder(parseStringArray(action.get("order")), all,
                            movableCards, toTop, toBottom, toAnywhere);
                });
        final List<CardView> result = new ArrayList<>(all.size());
        for (String id : parseStringArray(answer.get("order"))) {
            result.add(all.get(Integer.parseInt(id.substring("card-position:".length()))));
        }
        return result;
    }

    @Override
    public void setCard(final CardView card) {
        promptCard = card;
        scheduleInputPublish();
    }

    @Override
    public void setPlayerAvatar(final LobbyPlayer player, final IHasIcon icon) {
        // Avatar rendering is intentionally outside the game protocol.
    }

    private void emitNotice(final String severity, final String message, final String event) {
        if (channel.isEnded()) {
            return;
        }
        try {
            final JsonObject body = new JsonObject();
            final JsonObject eventBody = new JsonObject();
            eventBody.addProperty("class", event);
            eventBody.addProperty("severity", severity);
            eventBody.addProperty("message", sanitizeText(message));
            body.add("event", eventBody);
            if (game != null && human != null) {
                body.add("view", visibleState());
            }
            channel.send("event", body);
        } catch (InteractiveProtocol.ProtocolException e) {
            fail("eof", e.getMessage(), null, "emitNotice", null, e);
        } catch (Throwable e) {
            fail("engine", "could not encode Forge notice: " + safeThrowable(e),
                    null, "emitNotice", null, e);
        }
    }

    private void awaitAcknowledgement(final String title, final String message,
                                      final String inputClass) {
        final JsonArray controls = new JsonArray();
        controls.add(control("acknowledge", "ok", "Continue"));
        ask("confirm", inputClass, title, message, 1, 1, false, controls,
                action -> "ok".equals(string(action, "type"))
                        && "acknowledge".equals(string(action, "controlId"))
                        ? null : "answer must acknowledge the message");
    }

    private boolean askConfirm(final String title, final String message,
                               final String yesText, final String noText,
                               final String inputClass) {
        final JsonArray controls = new JsonArray();
        final JsonObject yes = control("confirm:yes", "confirm", sanitizeText(yesText));
        yes.addProperty("value", true);
        controls.add(yes);
        final JsonObject no = control("confirm:no", "confirm", sanitizeText(noText));
        no.addProperty("value", false);
        controls.add(no);
        final JsonObject answer = ask("confirm", inputClass, title, message, 1, 1,
                false, controls, action -> {
                    if (!"confirm".equals(string(action, "type"))
                            || !action.has("confirm") || !action.get("confirm").isJsonPrimitive()) {
                        return "answer must be an advertised confirmation";
                    }
                    final boolean value = action.get("confirm").getAsBoolean();
                    final String expected = value ? "confirm:yes" : "confirm:no";
                    return expected.equals(string(action, "controlId")) ? null
                            : "confirm value does not match controlId";
                });
        return answer.get("confirm").getAsBoolean();
    }

    private <T> List<T> chooseOpaque(final String title, final String message,
                                     final int requestedMin, final int requestedMax,
                                     final Collection<? extends T> choices,
                                     final Collection<? extends T> selected,
                                     final FSerializableFunction<T, String> display,
                                     final String inputClass) {
        if (choices == null || choices.isEmpty()) {
            if (requestedMin <= 0) {
                return Collections.emptyList();
            }
            throw unsupported(inputClass, "required choice requested from an empty list", "choice");
        }
        final List<? extends T> options = new ArrayList<>(choices);
        final int min = Math.max(0, requestedMin);
        final int max = requestedMax < 0 ? options.size() : Math.min(requestedMax, options.size());
        if (min > max) {
            throw unsupported(inputClass, "choice minimum exceeds maximum", "choice");
        }
        final JsonArray items = new JsonArray();
        final LinkedHashMap<String, T> byId = new LinkedHashMap<>();
        for (int i = 0; i < options.size(); i++) {
            final T option = options.get(i);
            final String id = "choice:" + i;
            byId.put(id, option);
            items.add(item(id, offeredObjectLabel(option, display)));
        }
        final JsonObject chooser = control("choices", "choice", sanitizeText(message));
        chooser.add("items", items);
        chooser.addProperty("min", min);
        chooser.addProperty("max", max);
        if (selected != null && !selected.isEmpty()) {
            final JsonArray initial = new JsonArray();
            for (Map.Entry<String, T> entry : byId.entrySet()) {
                if (selected.contains(entry.getValue())) {
                    initial.add(entry.getKey());
                }
            }
            final JsonObject value = new JsonObject();
            value.add("initial", initial);
            chooser.add("value", value);
        }
        final JsonArray controls = new JsonArray();
        controls.add(chooser);
        if (min == 0) {
            controls.add(control("choices:cancel", "cancel", "None"));
        }
        final JsonObject answer = ask("choice", inputClass, title, message, min, max,
                min == 0, controls, action -> validateChoices(action, byId.keySet(), min, max));
        if ("cancel".equals(string(answer, "type"))) {
            return Collections.emptyList();
        }
        final List<String> ids = actionChoiceIds(answer);
        final List<T> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            result.add(byId.get(id));
        }
        return result;
    }

    private String validateChoices(final JsonObject action, final Set<String> known,
                                   final int min, final int max) {
        if ("cancel".equals(string(action, "type"))) {
            return min == 0 && "choices:cancel".equals(string(action, "controlId"))
                    ? null : "this choice cannot be cancelled";
        }
        if (!"choice".equals(string(action, "type"))
                || !"choices".equals(string(action, "controlId"))) {
            return "answer must use the advertised choice control";
        }
        final List<String> ids;
        try {
            ids = actionChoiceIds(action);
        } catch (RuntimeException e) {
            return "choice values must be string option IDs";
        }
        if (ids.size() < min || ids.size() > max) {
            return "choice count is outside the advertised bounds";
        }
        if (new LinkedHashSet<>(ids).size() != ids.size() || !known.containsAll(ids)) {
            return "choice contains duplicate or unknown option IDs";
        }
        return null;
    }

    private String validateOrderedIds(final JsonObject action, final String controlId,
                                      final Set<String> known, final int min,
                                      final int max, final boolean exact) {
        if (!"order".equals(string(action, "type"))
                || !controlId.equals(string(action, "controlId"))
                || !action.has("order") || !action.get("order").isJsonArray()) {
            return "answer must use the advertised order control";
        }
        final List<String> ids;
        try {
            ids = parseStringArray(action.get("order"));
        } catch (RuntimeException e) {
            return "order must contain string option IDs";
        }
        if (ids.size() < min || ids.size() > max) {
            return "ordered selection size is outside the advertised bounds";
        }
        if (new LinkedHashSet<>(ids).size() != ids.size() || !known.containsAll(ids)) {
            return "order contains duplicate or unknown option IDs";
        }
        if (exact && ids.size() != known.size()) {
            return "order must contain every advertised item exactly once";
        }
        return null;
    }

    /**
     * Mirrors the desktop ListCardArea drag constraints. Non-manipulable cards can never
     * move relative to each other. Without toAnywhere, a movable card may remain in its
     * original fixed-card interval, move into the top interval when toTop is enabled, or
     * move into the bottom interval when toBottom is enabled. Cards that remain in an
     * interval which is not itself an enabled destination cannot be reordered there.
     */
    private String validateManipulatedOrder(final List<String> orderedIds,
                                             final List<CardView> original,
                                             final Set<CardView> movable,
                                             final boolean toTop,
                                             final boolean toBottom,
                                             final boolean toAnywhere) {
        final List<Integer> order = new ArrayList<>(orderedIds.size());
        for (String id : orderedIds) {
            if (id == null || !id.startsWith("card-position:")) {
                return "card order contains a malformed position ID";
            }
            try {
                order.add(Integer.parseInt(id.substring("card-position:".length())));
            } catch (NumberFormatException e) {
                return "card order contains a malformed position ID";
            }
        }

        final List<Integer> fixedOriginal = new ArrayList<>();
        for (int i = 0; i < original.size(); i++) {
            if (!movable.contains(original.get(i))) {
                fixedOriginal.add(i);
            }
        }
        final List<Integer> fixedResult = new ArrayList<>();
        for (int index : order) {
            if (!movable.contains(original.get(index))) {
                fixedResult.add(index);
            }
        }
        if (!fixedOriginal.equals(fixedResult)) {
            return "non-manipulable cards must retain their relative order";
        }
        if (toAnywhere) {
            return null;
        }

        final int[] originalInterval = fixedIntervals(original, movable);
        final int[] resultInterval = fixedIntervals(order, original, movable);
        final int bottomInterval = fixedOriginal.size();
        for (int position = 0; position < order.size(); position++) {
            final int originalIndex = order.get(position);
            if (!movable.contains(original.get(originalIndex))) {
                continue;
            }
            final int destination = resultInterval[position];
            if (destination != originalInterval[originalIndex]
                    && !(toTop && destination == 0)
                    && !(toBottom && destination == bottomInterval)) {
                return "a movable card was placed outside an allowed destination";
            }
        }

        for (int interval = 0; interval <= bottomInterval; interval++) {
            if ((interval == 0 && toTop) || (interval == bottomInterval && toBottom)) {
                continue;
            }
            final List<Integer> originalSubsequence = new ArrayList<>();
            for (int i = 0; i < original.size(); i++) {
                if (movable.contains(original.get(i)) && originalInterval[i] == interval) {
                    originalSubsequence.add(i);
                }
            }
            final List<Integer> resultSubsequence = new ArrayList<>();
            for (int position = 0; position < order.size(); position++) {
                final int index = order.get(position);
                if (movable.contains(original.get(index))
                        && resultInterval[position] == interval) {
                    resultSubsequence.add(index);
                }
            }
            int cursor = 0;
            for (int index : originalSubsequence) {
                if (cursor < resultSubsequence.size()
                        && resultSubsequence.get(cursor) == index) {
                    cursor++;
                }
            }
            if (cursor != resultSubsequence.size()) {
                return "cards cannot be reordered inside a fixed interval";
            }
        }
        return null;
    }

    private static int[] fixedIntervals(final List<CardView> cards,
                                        final Set<CardView> movable) {
        final int[] intervals = new int[cards.size()];
        int fixedBefore = 0;
        for (int i = 0; i < cards.size(); i++) {
            intervals[i] = fixedBefore;
            if (!movable.contains(cards.get(i))) {
                fixedBefore++;
            }
        }
        return intervals;
    }

    private static int[] fixedIntervals(final List<Integer> order,
                                        final List<CardView> original,
                                        final Set<CardView> movable) {
        final int[] intervals = new int[order.size()];
        int fixedBefore = 0;
        for (int i = 0; i < order.size(); i++) {
            intervals[i] = fixedBefore;
            if (!movable.contains(original.get(order.get(i)))) {
                fixedBefore++;
            }
        }
        return intervals;
    }

    private String validateGenericAllocation(final JsonObject action,
                                             final Map<String, Object> byId,
                                             final Map<Object, Integer> maxima,
                                             final int total, final boolean atLeastOne) {
        final Map<String, Integer> assigned;
        try {
            assigned = parseAllocationAction(action, "generic-allocation");
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        long sum = 0;
        for (Map.Entry<String, Integer> entry : assigned.entrySet()) {
            if (!byId.containsKey(entry.getKey())) {
                return "allocation contains an unknown target";
            }
            final Object target = byId.get(entry.getKey());
            final int max = maxima.get(target) == null ? total : maxima.get(target);
            if (entry.getValue() < (atLeastOne ? 1 : 0) || entry.getValue() > max) {
                return "allocation violates a target bound";
            }
            sum += entry.getValue();
        }
        for (String id : byId.keySet()) {
            if (atLeastOne && !assigned.containsKey(id)) {
                return "allocation must assign at least one to every target";
            }
        }
        return sum == total ? null : "allocation must assign the exact total";
    }

    private String validateCombatAllocation(final JsonObject action,
                                            final LinkedHashMap<String, CardView> blockers,
                                            final String defenderId,
                                            final boolean defenderAllowed,
                                            final int total, final boolean deathtouch,
                                            final boolean divideDamage,
                                            final boolean overrideOrder,
                                            final boolean maySkip) {
        if ("cancel".equals(string(action, "type"))) {
            return maySkip && "combat-damage:skip".equals(string(action, "controlId"))
                    ? null : "combat damage assignment cannot be skipped";
        }
        final Map<String, Integer> assigned;
        try {
            assigned = parseAllocationAction(action, "combat-damage");
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        long sum = 0;
        for (Map.Entry<String, Integer> entry : assigned.entrySet()) {
            if (!blockers.containsKey(entry.getKey())
                    && !(defenderAllowed && defenderId.equals(entry.getKey()))) {
                return "combat allocation contains an unknown recipient";
            }
            if (entry.getValue() < 0 || entry.getValue() > total) {
                return "combat damage must be a nonnegative integer";
            }
            sum += entry.getValue();
        }
        if (sum != total) {
            return "combat allocation must assign the exact total";
        }
        if (overrideOrder && divideDamage) {
            return null;
        }
        if (overrideOrder) {
            int positiveSublethal = 0;
            boolean anySublethal = false;
            for (Map.Entry<String, CardView> blocker : blockers.entrySet()) {
                final int amount = assigned.getOrDefault(blocker.getKey(), 0);
                if (amount < lethalDamage(blocker.getValue(), deathtouch)) {
                    anySublethal = true;
                    if (amount > 0) {
                        positiveSublethal++;
                    }
                }
            }
            if (assigned.getOrDefault(defenderId, 0) > 0 && anySublethal) {
                return "the defender cannot receive damage before every blocker has lethal";
            }
            // The player may choose any blocker order, but ordinary lethal-before-next
            // assignment still applies within that chosen order. Put every lethal blocker
            // first and at most one positive sublethal blocker last.
            return positiveSublethal <= 1 ? null
                    : "damage cannot be split sublethally across multiple blockers";
        }
        boolean earlierSurvives = false;
        for (Map.Entry<String, CardView> blocker : blockers.entrySet()) {
            final int amount = assigned.getOrDefault(blocker.getKey(), 0);
            if (earlierSurvives && amount > 0) {
                return "a later blocker cannot receive damage before earlier blockers have lethal";
            }
            if (amount < lethalDamage(blocker.getValue(), deathtouch)) {
                earlierSurvives = true;
            }
        }
        if (assigned.getOrDefault(defenderId, 0) > 0 && earlierSurvives) {
            return "the defender cannot receive damage before every blocker has lethal";
        }
        return null;
    }

    private static Map<String, Integer> parseAllocationAction(final JsonObject action,
                                                              final String controlId) {
        if (!"order".equals(string(action, "type"))
                || !controlId.equals(string(action, "controlId"))) {
            throw new IllegalArgumentException("answer must use the advertised allocation control");
        }
        return parseAllocation(action);
    }

    private static Map<String, Integer> parseAllocation(final JsonObject action) {
        if (!action.has("order") || !action.get("order").isJsonArray()) {
            throw new IllegalArgumentException("allocation order must be an array");
        }
        final Map<String, Integer> result = new LinkedHashMap<>();
        for (JsonElement element : action.getAsJsonArray("order")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("allocation entries must be objects");
            }
            final JsonObject entry = element.getAsJsonObject();
            final String id = string(entry, "id");
            if (id == null || result.containsKey(id) || !isIntegralNumber(entry.get("amount"))) {
                throw new IllegalArgumentException("allocation IDs must be unique and amounts integral");
            }
            final int amount;
            try {
                amount = new java.math.BigDecimal(entry.get("amount").getAsString())
                        .intValueExact();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("allocation amount is outside the 32-bit range", e);
            }
            result.put(id, amount);
        }
        return result;
    }

    private static boolean isIntegralNumber(final JsonElement element) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            return new java.math.BigDecimal(element.getAsString()).stripTrailingZeros().scale() <= 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<String> actionChoiceIds(final JsonObject action) {
        if (action.has("choices")) {
            return parseStringArray(action.get("choices"));
        }
        if (action.has("choice") && action.get("choice").isJsonPrimitive()
                && action.getAsJsonPrimitive("choice").isString()) {
            return List.of(action.get("choice").getAsString());
        }
        return Collections.emptyList();
    }

    private static List<String> parseStringArray(final JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException("expected an array");
        }
        final List<String> result = new ArrayList<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("expected string IDs");
            }
            result.add(value.getAsString());
        }
        return result;
    }

    private int lethalDamage(final CardView blocker, final boolean deathtouch) {
        int lethal = Math.max(0, blocker.getLethalDamage());
        if (blocker.getCurrentState().isPlaneswalker()) {
            try {
                lethal = Integer.parseInt(blocker.getCurrentState().getLoyalty());
            } catch (NumberFormatException e) {
                throw unsupported("assignCombatDamage",
                        "could not determine planeswalker lethal damage", "combat");
            }
        } else if (deathtouch) {
            lethal = Math.min(lethal, 1);
        }
        return lethal;
    }

    private String safeAbilityLabel(final SpellAbilityView ability) {
        if (ability == null) {
            return "Unknown ability";
        }
        final CardView host = ability.getHostCard();
        if (host != null && !InteractiveState.mayReceiveIdentity(host, human.getView())) {
            return "Ability of face-down card (" + host.getId() + ")";
        }
        final var offered = controller == null ? null : controller.getBrowserAbility(ability);
        if (offered != null) {
            final StringBuilder description = new StringBuilder();
            for (var node = offered; node != null; node = node.getSubAbility()) {
                final Card source = node.getHostCard();
                if (source == null || !InteractiveState.mayReceiveIdentity(source.getView(), human.getView())) {
                    return "Ability of a hidden card";
                }
                // Sanitize dynamic literal references first. Only then fill the
                // engine's self-reference placeholders from a verified public
                // source. A hidden duplicate name must not erase CARDNAME, and
                // knowing one public copy never authorizes other hidden copies.
                final String name = node.getHostName(node).getTranslatedName();
                String part = sanitizeText(node.getDescription())
                        .replace("CARDNAME", name)
                        .replace("NICKNAME", forge.util.Lang.getInstance().getNickName(name));
                if (part.contains("ORIGINALHOST")) {
                    final Card original = node.getOriginalHost();
                    part = part.replace("ORIGINALHOST", original != null
                            && InteractiveState.mayReceiveIdentity(original.getView(), human.getView())
                            ? original.getDisplayName() : "a hidden card");
                }
                if (!part.isBlank()) {
                    if (description.length() > 0) description.append(' ');
                    description.append(part);
                }
            }
            if (description.length() > 0) return description.toString();
        }
        return sanitizeText(Objects.requireNonNullElse(ability.getDescription(), "Ability"));
    }

    private <T> String safeObjectLabel(final T value,
                                       final FSerializableFunction<T, String> display) {
        if (value instanceof CardView card) {
            return InteractiveState.safeCardLabel(card, human.getView());
        }
        if (value instanceof SpellAbilityView ability) {
            return safeAbilityLabel(ability);
        }
        if (value instanceof PlayerView player) {
            return sanitizeText(player.getName());
        }
        final String rendered;
        if (display != null) {
            rendered = display.apply(value);
        } else if (value instanceof PaperCard card) {
            rendered = card.getName();
        } else {
            rendered = String.valueOf(value);
        }
        return sanitizeText(rendered);
    }

    /** Labels only objects Forge explicitly supplied to this human decision/reveal callback. */
    private <T> String offeredObjectLabel(final T value,
                                          final FSerializableFunction<T, String> display) {
        if (value instanceof CardView card) {
            if (explicitlyOfferedCards.get().contains(card.getId())) {
                return InteractiveState.visibleCardLabel(card);
            }
            return InteractiveState.safeCardLabel(card, human.getView());
        }
        return safeObjectLabel(value, display);
    }

    private Set<Integer> authorizeOfferedCards(final Collection<?> values) {
        final Set<Integer> previous = explicitlyOfferedCards.get();
        if (values == null || values.isEmpty()) {
            return previous;
        }
        final Set<Integer> authorized = new LinkedHashSet<>(previous);
        for (Object value : values) {
            if (value instanceof CardView card) {
                authorized.add(card.getId());
            }
        }
        explicitlyOfferedCards.set(Collections.unmodifiableSet(authorized));
        return previous;
    }

    private String stableObjectId(final Object value, final int index) {
        if (value instanceof CardView card) {
            return "card:" + card.getId();
        }
        if (value instanceof PlayerView player) {
            return "player-id:" + player.getId();
        }
        if (value instanceof Byte mana) {
            return "mana:" + mana;
        }
        if (value instanceof MagicColor.Color color) {
            return "color:" + color.getShortName();
        }
        return "target:" + index;
    }

    private <T> String revealMessage(final String message, final Collection<T> values,
                                     final FSerializableFunction<T, String> display) {
        final StringBuilder result = new StringBuilder(sanitizeText(message));
        if (values != null) {
            for (T value : values) {
                result.append("\n").append(offeredObjectLabel(value, display));
            }
        }
        return result.toString();
    }

    private static <T> List<T> copyIterable(final Iterable<T> values) {
        final List<T> result = new ArrayList<>();
        if (values != null) {
            for (T value : values) {
                result.add(value);
            }
        }
        return result;
    }
}
