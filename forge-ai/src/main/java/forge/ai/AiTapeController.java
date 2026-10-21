package forge.ai;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.LobbyPlayer;
import forge.StaticData;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameOutcome.AnteResult;
import forge.game.GameType;
import forge.game.PlanarDice;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardState;
import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.Cost;
import forge.game.cost.CostDecisionMakerBase;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPartWithList;
import forge.game.cost.CostPayment;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerView;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.Spell;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.ITriggerEvent;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The AI seat's controller while its decisions are taped (lane replay-record-1021; see {@link AiTape}). Swapped in, with
 * {@link Player#dangerouslySetController}, after the seat was bound (the look-ahead, the policy seat), so everything that
 * looked the bound controller up still holds it: it is {@link #inner()}, and every call is answered by it, except
 *
 * <ul>
 *   <li>RECORD: a top-level call (on the game thread, no decision open) is recorded with its answer;</li>
 *   <li>FORCED: a top-level CHOICE is answered from the record (the inner controller never sees it); an EXECUTION
 *   method (play a chosen spell, a trigger, pay a cost) runs, with Forge AI's own execution path, and the decisions it
 *   makes are answered from its recorded children.</li>
 * </ul>
 *
 * Everything that is not a decision (reveals, notifications, the AI's state, the cost decision maker itself) is the
 * inner controller's in both modes. Without a tape this class is never created.
 */
public final class AiTapeController extends PlayerControllerAi {
    private final PlayerControllerAi inner;
    private final AiTape tape;

    public AiTapeController(final PlayerControllerAi inner, final AiTape tape) {
        super(inner.getGame(), inner.getPlayer(), inner.getLobbyPlayer());
        this.inner = inner;
        this.tape = tape;
    }

    /** The bound seat controller this one wraps. */
    public PlayerControllerAi inner() {
        return inner;
    }

    private Game g() {
        return getGame();
    }

    // ------------------------------------------------------------------ the two shapes of a decision

    /** A choice: recorded with its answer, or (forced) answered from the record without asking the inner controller. */
    private <T> T choice(final String m, final Supplier<T> live, final Function<T, JsonElement> enc,
            final Function<JsonElement, T> dec) {
        if (!tape.controllerHere()) {
            return live.get();
        }
        if (tape.recordsNow("m", m)) {
            final Object r = tape.begin("m", m, false);
            T v = null;
            boolean ok = false;
            try {
                v = live.get();
                ok = true;
            } finally {
                JsonElement a;
                if (!ok) {
                    a = AiTapeCodec.unencodable(new IllegalStateException("the decision threw"));
                } else {
                    final long t0 = System.nanoTime();
                    try {
                        a = enc.apply(v);
                    } catch (RuntimeException e) {
                        tape.noteUnencodable();
                        a = AiTapeCodec.unencodable(e);
                    }
                    tape.noteRecordNanos(System.nanoTime() - t0);
                }
                tape.end(r, a);
            }
            return v;
        }
        final JsonObject e = tape.take("m", m);
        final T v;
        try {
            v = dec.apply(e.get("a"));
        } catch (AiTape.Divergence x) {
            throw x;
        } catch (RuntimeException x) {
            throw tape.diverge(m + ": " + x.getMessage());
        }
        tape.done(e);
        return v;
    }

    /**
     * An execution method: it runs in both modes (live: the inner controller's; forced: {@code forced}, Forge AI's
     * execution with the record's decisions), and the decisions it makes are its children. {@code shortcut}: a recorded
     * result for which the forced replay need not run anything (a declined trigger), or null.
     */
    private <T> T exec(final String m, final Supplier<T> live, final Supplier<T> forced, final Function<T, JsonElement> enc,
            final Function<JsonElement, T> shortcut) {
        if (!tape.controllerHere()) {
            return live.get();
        }
        if (tape.recordsNow("m", m)) {
            final Object r = tape.begin("m", m, true);
            T v = null;
            boolean ok = false;
            try {
                v = live.get();
                ok = true;
            } finally {
                tape.end(r, ok ? enc.apply(v) : AiTapeCodec.unencodable(new IllegalStateException("the call threw")));
            }
            return v;
        }
        final JsonObject e = tape.take("m", m);
        final JsonElement a = e.get("a");
        AiTapeCodec.checkEncodable(a);
        if (shortcut != null) {
            final T s = shortcut.apply(a);
            if (s != null) {
                tape.done(e);
                return s;
            }
        }
        tape.enter(e, true);
        final T v = forced.get();
        tape.leave(e);
        final JsonElement got = enc.apply(v);
        if (a != null && !a.equals(got)) {
            throw tape.diverge(m + " returned " + got + " in the replay, " + a + " live");
        }
        tape.done(e);
        return v;
    }

    /** A choice of one of {@code options}, recorded by position (the options are copied first: some AI code reorders them). */
    private <T> T oneOf(final String m, final List<T> options, final Supplier<T> live) {
        final List<T> snap = options == null ? new ArrayList<>() : new ArrayList<>(options);
        return choice(m, live, v -> AiTapeCodec.index(snap, v), e -> AiTapeCodec.pickOne(snap, e));
    }

    /** A choice of several of {@code options} in order, recorded by position (the options copied first). */
    private <T> List<T> manyOf(final String m, final List<T> options, final Supplier<List<T>> live) {
        final List<T> snap = options == null ? new ArrayList<>() : new ArrayList<>(options);
        return choice(m, live, v -> AiTapeCodec.indices(snap, v), e -> AiTapeCodec.pick(snap, e));
    }

    private static JsonElement bool(final Boolean b) {
        return b == null ? JsonNull.INSTANCE : new JsonPrimitive(b);
    }

    private static Boolean bool(final JsonElement e) {
        AiTapeCodec.checkEncodable(e);
        return e == null || e.isJsonNull() ? null : e.getAsBoolean();
    }

    private static JsonElement num(final Number n) {
        return n == null ? JsonNull.INSTANCE : new JsonPrimitive(n);
    }

    private static Integer num(final JsonElement e) {
        AiTapeCodec.checkEncodable(e);
        return e == null || e.isJsonNull() ? null : e.getAsInt();
    }

    private static JsonElement text(final String s) {
        return s == null ? JsonNull.INSTANCE : new JsonPrimitive(s);
    }

    private static String text(final JsonElement e) {
        AiTapeCodec.checkEncodable(e);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private JsonElement cards(final Iterable<? extends GameEntity> cs) {
        return cs == null ? JsonNull.INSTANCE : AiTapeCodec.refs(g(), cs);
    }

    private <T> List<T> among(final Collection<T> options, final JsonElement e) {
        AiTapeCodec.checkEncodable(e);
        return AiTapeCodec.amongAll(g(), options, e);
    }

    private CardCollection amongCards(final Iterable<Card> options, final JsonElement e) {
        final List<Card> opts = new ArrayList<>();
        if (options != null) {
            for (Card c : options) {
                opts.add(c);
            }
        }
        final List<Card> l = among(opts, e);
        return l == null ? null : new CardCollection(l);
    }

    private <T> T amongOne(final Collection<T> options, final JsonElement e) {
        AiTapeCodec.checkEncodable(e);
        return e == null || e.isJsonNull() ? null : AiTapeCodec.among(g(), options, e.getAsString());
    }

    private JsonElement one(final Object o) {
        return o == null ? JsonNull.INSTANCE : new JsonPrimitive(AiTapeCodec.ref(g(), o));
    }

    private static <T> List<T> listOf(final Iterable<T> it) {
        final List<T> l = new ArrayList<>();
        if (it != null) {
            for (T t : it) {
                l.add(t);
            }
        }
        return l;
    }

    // ------------------------------------------------------------------ not decisions: always the inner controller's

    @Override public boolean isAI() { return inner.isAI(); }
    @Override public AiController getAi() { return inner.getAi(); }
    @Override public boolean pilotsNonAggroDeck() { return inner.pilotsNonAggroDeck(); }
    @Override public void setKnowledgeHook(final KnowledgeHook hook) { inner.setKnowledgeHook(hook); }
    @Override Set<String> getFairNamingSeen() { return inner.getFairNamingSeen(); }
    @Override public void setupAutoProfile(final Deck deck) { inner.setupAutoProfile(deck); }
    @Override public boolean acceptsDrawOffer() { return inner.acceptsDrawOffer(); }
    @Override public void tempShowCards(final Iterable<Card> cards) { inner.tempShowCards(cards); }
    @Override public void endTempShowCards() { inner.endTempShowCards(); }
    @Override public List<PaperCard> sideboard(final Deck deck, final GameType gameType, final String message) { return inner.sideboard(deck, gameType, message); }
    @Override public List<PaperCard> chooseCardsYouWonToAddToDeck(final List<PaperCard> losses) { return inner.chooseCardsYouWonToAddToDeck(losses); }
    @Override public void reveal(final CardCollectionView cards, final ZoneType zone, final Player owner, final String messagePrefix, final boolean addSuffix) { inner.reveal(cards, zone, owner, messagePrefix, addSuffix); }
    @Override public void reveal(final List<CardView> cards, final ZoneType zone, final PlayerView owner, final String messagePrefix, final boolean addSuffix) { inner.reveal(cards, zone, owner, messagePrefix, addSuffix); }
    @Override public void notifyOfValue(final SpellAbility saSource, final GameObject realtedTarget, final String value) { inner.notifyOfValue(saSource, realtedTarget, value); }
    @Override public void revealAnte(final String message, final Multimap<Player, PaperCard> removedAnteCards) { inner.revealAnte(message, removedAnteCards); }
    @Override public void revealAISkipCards(final String message, final Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) { inner.revealAISkipCards(message, deckCards); }
    @Override public void revealUnsupported(final Map<Player, List<PaperCard>> unsupported) { inner.revealUnsupported(unsupported); }
    @Override public Map<DeckSection, List<? extends PaperCard>> complainCardsCantPlayWell(final Deck myDeck) { return inner.complainCardsCantPlayWell(myDeck); }
    @Override public CardCollectionView cheatShuffle(final CardCollectionView list) { return inner.cheatShuffle(list); }
    @Override public void resetAtEndOfTurn() { inner.resetAtEndOfTurn(); }
    @Override public Set<FullControlFlag> getFullControl() { return inner.getFullControl(); }
    @Override public boolean isFullControl(final FullControlFlag f) { return inner.isFullControl(f); }
    @Override public void autoPassCancel() { inner.autoPassCancel(); }
    @Override public void awaitNextInput() { inner.awaitNextInput(); }
    @Override public void cancelAwaitNextInput() { inner.cancelAwaitNextInput(); }
    @Override public boolean isGuiPlayer() { return inner.isGuiPlayer(); }
    @Override public boolean canPlayUnlimitedLands() { return inner.canPlayUnlimitedLands(); }
    @Override public AnteResult getAnteResult() { return inner.getAnteResult(); }
    @Override public boolean isOrderedZone() { return inner.isOrderedZone(); }
    @Override public boolean defersLondonMulliganTuckUntilKeep() { return inner.defersLondonMulliganTuckUntilKeep(); }
    /** The decision maker itself is not a decision: its decisions are taped where the payment makes them (DecisionTap). */
    @Override public CostDecisionMakerBase getCostDecisionMaker(final Player p, final SpellAbility ability, final boolean effect, final String prompt) { return inner.getCostDecisionMaker(p, ability, effect, prompt); }

    // ------------------------------------------------------------------ the priority decision

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        return choice("chooseSpellAbilityToPlay", inner::chooseSpellAbilityToPlay, sas -> {
            if (sas == null) {
                return JsonNull.INSTANCE;
            }
            final JsonArray a = new JsonArray();
            for (SpellAbility sa : sas) {
                a.add(AiTapePlays.encode(g(), getPlayer(), sa));
            }
            return a;
        }, e -> {
            AiTapeCodec.checkEncodable(e);
            if (e == null || e.isJsonNull()) {
                return null;
            }
            final List<SpellAbility> out = new ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) {
                out.add(AiTapePlays.decode(g(), getPlayer(), x.getAsJsonObject()));
            }
            return out;
        });
    }

    @Override
    public boolean playChosenSpellAbility(final SpellAbility sa) {
        return exec("playChosenSpellAbility", () -> inner.playChosenSpellAbility(sa), () -> {
            // PlayerControllerAi.playChosenSpellAbility, with the record's targets (the "h" checkpoint)
            if (sa.isLandAbility()) {
                if (sa.canPlay()) {
                    sa.resolve();
                }
            } else {
                ComputerUtil.handlePlayingSpellAbility(getPlayer(), sa, inner.getDeferredTargetingPlayerAction(sa));
            }
            return true;
        }, AiTapeController::bool, null);
    }

    // ------------------------------------------------------------------ combat

    @Override
    public void declareAttackers(final Player attacker, final Combat combat) {
        final List<Card> before = combat == null ? new ArrayList<>() : listOf(combat.getAttackers());
        choice("declareAttackers", () -> {
            inner.declareAttackers(attacker, combat);
            return Boolean.TRUE;
        }, ignored -> {
            final JsonArray a = new JsonArray();
            for (Card c : combat.getAttackers()) {
                if (before.contains(c)) {
                    continue;
                }
                final JsonArray pair = new JsonArray();
                pair.add(AiTapeCodec.ref(g(), c));
                pair.add(AiTapeCodec.ref(g(), combat.getDefenderByAttacker(c)));
                a.add(pair);
            }
            return a;
        }, e -> {
            AiTapeCodec.checkEncodable(e);
            for (JsonElement x : e.getAsJsonArray()) {
                final Card c = (Card) AiTapeCodec.deref(g(), x.getAsJsonArray().get(0).getAsString());
                final GameEntity d = (GameEntity) AiTapeCodec.deref(g(), x.getAsJsonArray().get(1).getAsString());
                if (!CombatUtil.canAttack(c, d)) {
                    throw new IllegalStateException("the record's attacker " + AiTapeCodec.ref(g(), c) + " cannot attack "
                            + AiTapeCodec.ref(g(), d));
                }
                combat.addAttacker(c, d);
            }
            return Boolean.TRUE;
        });
    }

    @Override
    public void declareBlockers(final Player defender, final Combat combat) {
        final List<String> before = blockPairs(combat);
        choice("declareBlockers", () -> {
            inner.declareBlockers(defender, combat);
            return Boolean.TRUE;
        }, ignored -> {
            final JsonArray a = new JsonArray();
            for (Card b : combat.getAllBlockers()) {
                for (Card at : combat.getAttackersBlockedBy(b)) {
                    final String key = b.getId() + ">" + at.getId();
                    if (before.contains(key)) {
                        continue;
                    }
                    final JsonArray pair = new JsonArray();
                    pair.add(AiTapeCodec.ref(g(), b));
                    pair.add(AiTapeCodec.ref(g(), at));
                    a.add(pair);
                }
            }
            return a;
        }, e -> {
            AiTapeCodec.checkEncodable(e);
            for (JsonElement x : e.getAsJsonArray()) {
                final Card b = (Card) AiTapeCodec.deref(g(), x.getAsJsonArray().get(0).getAsString());
                final Card at = (Card) AiTapeCodec.deref(g(), x.getAsJsonArray().get(1).getAsString());
                if (!combat.isAttacking(at)) {
                    throw new IllegalStateException("the record blocks " + AiTapeCodec.ref(g(), at) + ", which is not attacking");
                }
                combat.addBlocker(at, b);
            }
            return Boolean.TRUE;
        });
    }

    private static List<String> blockPairs(final Combat combat) {
        final List<String> out = new ArrayList<>();
        if (combat == null) {
            return out;
        }
        for (Card b : combat.getAllBlockers()) {
            for (Card at : combat.getAttackersBlockedBy(b)) {
                out.add(b.getId() + ">" + at.getId());
            }
        }
        return out;
    }

    @Override
    public List<Card> exertAttackers(final List<Card> attackers) {
        return choice("exertAttackers", () -> inner.exertAttackers(attackers), this::cards, e -> among(attackers, e));
    }

    @Override
    public List<Card> enlistAttackers(final List<Card> attackers) {
        return choice("enlistAttackers", () -> inner.enlistAttackers(attackers), this::cards, e -> among(attackers, e));
    }

    @Override
    public CardCollection orderBlockers(final Card attacker, final CardCollection blockers) {
        return choice("orderBlockers", () -> inner.orderBlockers(attacker, blockers), this::cards, e -> amongCards(blockers, e));
    }

    @Override
    public CardCollection orderBlocker(final Card attacker, final Card blocker, final CardCollection oldBlockers) {
        final CardCollection all = new CardCollection(oldBlockers);
        all.add(blocker);
        return choice("orderBlocker", () -> inner.orderBlocker(attacker, blocker, oldBlockers), this::cards, e -> amongCards(all, e));
    }

    @Override
    public CardCollection orderAttackers(final Card blocker, final CardCollection attackers) {
        return choice("orderAttackers", () -> inner.orderAttackers(blocker, attackers), this::cards, e -> amongCards(attackers, e));
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(final Card attacker, final CardCollectionView blockers, final CardCollectionView remaining,
            final int damageDealt, final GameEntity defender, final boolean overrideOrder) {
        return choice("assignCombatDamage", () -> inner.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder),
                this::damageMap, e -> {
                    AiTapeCodec.checkEncodable(e);
                    if (e.isJsonNull()) {
                        return null;
                    }
                    final Map<Card, Integer> m = new LinkedHashMap<>();
                    for (JsonElement x : e.getAsJsonArray()) {
                        final JsonArray p = x.getAsJsonArray();
                        m.put(p.get(0).isJsonNull() ? null : (Card) AiTapeCodec.deref(g(), p.get(0).getAsString()), p.get(1).getAsInt());
                    }
                    return m;
                });
    }

    private JsonElement damageMap(final Map<? extends GameEntity, Integer> m) {
        if (m == null) {
            return JsonNull.INSTANCE;
        }
        final JsonArray a = new JsonArray();
        for (Map.Entry<? extends GameEntity, Integer> en : m.entrySet()) {
            final JsonArray p = new JsonArray();
            p.add(en.getKey() == null ? null : AiTapeCodec.ref(g(), en.getKey()));
            p.add(en.getValue());
            a.add(p);
        }
        return a;
    }

    @Override
    public Map<GameEntity, Integer> divideShield(final Card effectSource, final Map<GameEntity, Integer> affected, final int shieldAmount) {
        return choice("divideShield", () -> inner.divideShield(effectSource, affected, shieldAmount), this::damageMap, e -> {
            AiTapeCodec.checkEncodable(e);
            if (e.isJsonNull()) {
                return null;
            }
            final Map<GameEntity, Integer> m = new LinkedHashMap<>();
            for (JsonElement x : e.getAsJsonArray()) {
                final JsonArray p = x.getAsJsonArray();
                m.put(p.get(0).isJsonNull() ? null : (GameEntity) AiTapeCodec.deref(g(), p.get(0).getAsString()), p.get(1).getAsInt());
            }
            return m;
        });
    }

    // ------------------------------------------------------------------ targets

    @Override
    public boolean chooseTargetsFor(final SpellAbility currentAbility) {
        return choice("chooseTargetsFor", () -> inner.chooseTargetsFor(currentAbility), r -> {
            final JsonObject o = new JsonObject();
            o.addProperty("r", r);
            o.add("cfg", AiTapeCodec.config(g(), getPlayer(), currentAbility));
            return o;
        }, e -> {
            AiTapeCodec.checkEncodable(e);
            AiTapeCodec.applyConfig(g(), getPlayer(), currentAbility, e.getAsJsonObject().get("cfg"));
            return e.getAsJsonObject().get("r").getAsBoolean();
        });
    }

    @Override
    public TargetChoices chooseNewTargetsFor(final SpellAbility ability, final Predicate<GameObject> filter, final boolean optional) {
        return choice("chooseNewTargetsFor", () -> inner.chooseNewTargetsFor(ability, filter, optional),
                tc -> tc == null ? JsonNull.INSTANCE : AiTapeCodec.refs(g(), tc), e -> {
                    AiTapeCodec.checkEncodable(e);
                    if (e.isJsonNull()) {
                        return null;
                    }
                    final TargetChoices tc = new TargetChoices();
                    for (Object o : AiTapeCodec.derefs(g(), e)) {
                        tc.add((GameObject) o);
                    }
                    return tc;
                });
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(final SpellAbility saSrc,
            final List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        return oneOf("chooseTarget", allTargets, () -> inner.chooseTarget(saSrc, allTargets));
    }

    @Override
    public Integer announceRequirements(final SpellAbility ability, final int min, final int max, final String announce) {
        return choice("announceRequirements", () -> inner.announceRequirements(ability, min, max, announce), AiTapeController::num,
                AiTapeController::num);
    }

    // ------------------------------------------------------------------ choosing among cards, players, abilities

    @Override
    public SpellAbility getAbilityToPlay(final Card hostCard, final List<SpellAbility> abilities, final ITriggerEvent triggerEvent) {
        return oneOf("getAbilityToPlay", abilities, () -> inner.getAbilityToPlay(hostCard, abilities, triggerEvent));
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(final SpellAbility sa, final int min, final int max,
            final CardCollectionView validTargets, final String message) {
        return choice("choosePermanentsToSacrifice", () -> inner.choosePermanentsToSacrifice(sa, min, max, validTargets, message),
                this::cards, e -> amongCards(validTargets, e));
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(final SpellAbility sa, final int min, final int max,
            final CardCollectionView validTargets, final String message) {
        return choice("choosePermanentsToDestroy", () -> inner.choosePermanentsToDestroy(sa, min, max, validTargets, message),
                this::cards, e -> amongCards(validTargets, e));
    }

    @Override
    public CardCollectionView chooseCardsForEffect(final CardCollectionView sourceList, final SpellAbility sa, final String title,
            final int min, final int max, final boolean isOptional, final Map<String, Object> params) {
        return choice("chooseCardsForEffect", () -> inner.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params),
                this::cards, e -> amongCards(sourceList, e));
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(final Map<String, CardCollection> validMap, final SpellAbility sa,
            final String title, final boolean isOptional) {
        final CardCollection all = new CardCollection();
        for (CardCollection c : validMap.values()) {
            all.addAll(c);
        }
        return choice("chooseCardsForEffectMultiple", () -> inner.chooseCardsForEffectMultiple(validMap, sa, title, isOptional),
                this::cards, e -> amongCards(all, e));
    }

    @Override
    public List<Card> chooseContraptionsToCrank(final List<Card> contraptions) {
        return choice("chooseContraptionsToCrank", () -> inner.chooseContraptionsToCrank(contraptions), this::cards,
                e -> among(contraptions, e));
    }

    @Override
    public Player choosePlayerToAssistPayment(final FCollectionView<Player> optionList, final SpellAbility sa, final String title, final int max) {
        return choice("choosePlayerToAssistPayment", () -> inner.choosePlayerToAssistPayment(optionList, sa, title, max), this::one,
                e -> amongOne(listOf(optionList), e));
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(final FCollectionView<T> optionList, final DelayedReveal delayedReveal,
            final SpellAbility sa, final String title, final boolean isOptional, final Player targetedPlayer, final Map<String, Object> params) {
        return choice("chooseSingleEntityForEffect",
                () -> inner.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params),
                this::one, e -> amongOne(listOf(optionList), e));
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(final FCollectionView<T> optionList, final int min, final int max,
            final DelayedReveal delayedReveal, final SpellAbility sa, final String title, final Player targetedPlayer,
            final Map<String, Object> params) {
        return choice("chooseEntitiesForEffect",
                () -> inner.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params),
                this::cards, e -> among(listOf(optionList), e));
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(final List<SpellAbility> spells, final SpellAbility sa, final String title,
            final int num, final Map<String, Object> params) {
        return manyOf("chooseSpellAbilitiesForEffect", spells, () -> inner.chooseSpellAbilitiesForEffect(spells, sa, title, num, params));
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(final List<SpellAbility> spells, final SpellAbility sa, final String title,
            final Map<String, Object> params) {
        return oneOf("chooseSingleSpellForEffect", spells, () -> inner.chooseSingleSpellForEffect(spells, sa, title, params));
    }

    @Override
    public CardCollectionView orderMoveToZoneList(final CardCollectionView cards, final ZoneType destinationZone, final SpellAbility source) {
        return choice("orderMoveToZoneList", () -> inner.orderMoveToZoneList(cards, destinationZone, source), this::cards,
                e -> amongCards(cards, e));
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(final CardCollection topN) {
        return choice("arrangeForScry", () -> inner.arrangeForScry(topN), this::pile2, e -> unpile2(topN, e));
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(final CardCollection topN) {
        return choice("arrangeForSurveil", () -> inner.arrangeForSurveil(topN), this::pile2, e -> unpile2(topN, e));
    }

    private JsonElement pile2(final ImmutablePair<CardCollection, CardCollection> p) {
        if (p == null) {
            return JsonNull.INSTANCE;
        }
        final JsonArray a = new JsonArray();
        a.add(cards(p.getLeft()));
        a.add(cards(p.getRight()));
        return a;
    }

    private ImmutablePair<CardCollection, CardCollection> unpile2(final CardCollection topN, final JsonElement e) {
        AiTapeCodec.checkEncodable(e);
        if (e.isJsonNull()) {
            return null;
        }
        final JsonArray a = e.getAsJsonArray();
        return ImmutablePair.of(amongCards(topN, a.get(0)), amongCards(topN, a.get(1)));
    }

    @Override
    public boolean willPutCardOnTop(final Card c) {
        return choice("willPutCardOnTop", () -> inner.willPutCardOnTop(c), AiTapeController::bool, AiTapeController::bool);
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(final Player p, final SpellAbility sa, final CardCollection validCards, final int min,
            final int max, final CardCollectionView visibleToChooser) {
        return choice("chooseCardsToDiscardFrom", () -> inner.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser),
                this::cards, e -> amongCards(validCards, e));
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(final int num, final CardCollectionView hand, final String[] uTypes,
            final SpellAbility sa) {
        return choice("chooseCardsToDiscardUnlessType", () -> inner.chooseCardsToDiscardUnlessType(num, hand, uTypes, sa), this::cards,
                e -> amongCards(hand, e));
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(final int numDiscard) {
        return choice("chooseCardsToDiscardToMaximumHandSize", () -> inner.chooseCardsToDiscardToMaximumHandSize(numDiscard),
                this::cards, e -> amongCards(getPlayer().getCardsIn(ZoneType.Hand), e));
    }

    @Override
    public CardCollectionView chooseCardsToDelve(final int genericAmount, final CardCollection grave) {
        return choice("chooseCardsToDelve", () -> inner.chooseCardsToDelve(genericAmount, grave), this::cards, e -> amongCards(grave, e));
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(final SpellAbility sa, final ManaCost manaCost,
            final CardCollectionView untappedCards, final boolean artifacts, final boolean creatures, final Integer maxReduction) {
        return choice("chooseCardsForConvokeOrImprovise",
                () -> inner.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction), m -> {
                    if (m == null) {
                        return JsonNull.INSTANCE;
                    }
                    final JsonArray a = new JsonArray();
                    for (Map.Entry<Card, ManaCostShard> en : m.entrySet()) {
                        final JsonArray p = new JsonArray();
                        p.add(AiTapeCodec.ref(g(), en.getKey()));
                        p.add(en.getValue().name());
                        a.add(p);
                    }
                    return a;
                }, e -> {
                    AiTapeCodec.checkEncodable(e);
                    if (e.isJsonNull()) {
                        return null;
                    }
                    final Map<Card, ManaCostShard> m = new LinkedHashMap<>();
                    final List<Card> opts = listOf(untappedCards);
                    for (JsonElement x : e.getAsJsonArray()) {
                        final JsonArray p = x.getAsJsonArray();
                        m.put(AiTapeCodec.among(g(), opts, p.get(0).getAsString()), ManaCostShard.valueOf(p.get(1).getAsString()));
                    }
                    return m;
                });
    }

    @Override
    public List<Card> chooseCardsForSplice(final SpellAbility sa, final List<Card> cards) {
        return choice("chooseCardsForSplice", () -> inner.chooseCardsForSplice(sa, cards), this::cards, e -> among(cards, e));
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(final int min, final int max, final CardCollectionView valid) {
        return choice("chooseCardsToRevealFromHand", () -> inner.chooseCardsToRevealFromHand(min, max, valid), this::cards,
                e -> amongCards(valid, e));
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(final List<SpellAbility> usable) {
        return manyOf("chooseSaToActivateFromOpeningHand", usable, () -> inner.chooseSaToActivateFromOpeningHand(usable));
    }

    @Override
    public Player chooseStartingPlayer(final boolean isFirstGame) {
        return choice("chooseStartingPlayer", () -> inner.chooseStartingPlayer(isFirstGame), this::one,
                e -> amongOne(listOf(g().getRegisteredPlayers()), e));
    }

    @Override
    public PlayerZone chooseStartingHand(final List<PlayerZone> zones) {
        return oneOf("chooseStartingHand", zones, () -> inner.chooseStartingHand(zones));
    }

    @Override
    public Mana chooseManaFromPool(final List<Mana> manaChoices) {
        return oneOf("chooseManaFromPool", manaChoices, () -> inner.chooseManaFromPool(manaChoices));
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(final CardCollectionView hand, final int cardsToReturn) {
        return choice("tuckCardsViaMulligan", () -> inner.tuckCardsViaMulligan(hand, cardsToReturn), this::cards, e -> amongCards(hand, e));
    }

    @Override
    public boolean mulliganKeepHand(final Player firstPlayer, final int cardsToReturn) {
        return choice("mulliganKeepHand", () -> inner.mulliganKeepHand(firstPlayer, cardsToReturn), AiTapeController::bool,
                AiTapeController::bool);
    }

    @Override
    public CardCollectionView chooseCardsForCost(final CardCollectionView optionList, final SpellAbility sa, final CostPartWithList cpl,
            final int amount, final boolean isOptional, final String prompt) {
        return choice("chooseCardsForCost", () -> inner.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt), this::cards,
                e -> amongCards(optionList, e));
    }

    @Override
    public Card chooseSingleCardForZoneChange(final ZoneType destination, final List<ZoneType> origin, final SpellAbility sa,
            final CardCollection fetchList, final DelayedReveal delayedReveal, final String selectPrompt, final boolean isOptional,
            final Player decider) {
        return choice("chooseSingleCardForZoneChange",
                () -> inner.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider),
                this::one, e -> amongOne(listOf(fetchList), e));
    }

    @Override
    public List<Card> chooseCardsForZoneChange(final ZoneType destination, final List<ZoneType> origin, final SpellAbility sa,
            final CardCollection fetchList, final int min, final int max, final DelayedReveal delayedReveal, final String selectPrompt,
            final Player decider) {
        return choice("chooseCardsForZoneChange",
                () -> inner.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider),
                this::cards, e -> among(listOf(fetchList), e));
    }

    // ------------------------------------------------------------------ yes/no, numbers, names, colors, modes

    @Override
    public boolean confirmAction(final SpellAbility sa, final PlayerActionConfirmMode mode, final String message, final List<String> options,
            final Card cardToShow, final Map<String, Object> params) {
        return choice("confirmAction", () -> inner.confirmAction(sa, mode, message, options, cardToShow, params), AiTapeController::bool,
                AiTapeController::bool);
    }

    @Override
    public boolean confirmBidAction(final SpellAbility sa, final PlayerActionConfirmMode mode, final String string, final int bid,
            final Player winner) {
        return choice("confirmBidAction", () -> inner.confirmBidAction(sa, mode, string, bid, winner), AiTapeController::bool,
                AiTapeController::bool);
    }

    @Override
    public boolean confirmStaticApplication(final Card hostCard, final PlayerActionConfirmMode mode, final String message, final String logic) {
        return choice("confirmStaticApplication", () -> inner.confirmStaticApplication(hostCard, mode, message, logic),
                AiTapeController::bool, AiTapeController::bool);
    }

    @Override
    public boolean confirmTrigger(final WrappedAbility wrapper) {
        return choice("confirmTrigger", () -> inner.confirmTrigger(wrapper), AiTapeController::bool, AiTapeController::bool);
    }

    @Override
    public boolean confirmPayment(final CostPart costPart, final String prompt, final SpellAbility sa) {
        return choice("confirmPayment", () -> inner.confirmPayment(costPart, prompt, sa), AiTapeController::bool, AiTapeController::bool);
    }

    @Override
    public boolean confirmReplacementEffect(final ReplacementEffect replacementEffect, final SpellAbility effectSA, final GameEntity affected,
            final String question) {
        return choice("confirmReplacementEffect", () -> inner.confirmReplacementEffect(replacementEffect, effectSA, affected, question),
                AiTapeController::bool, AiTapeController::bool);
    }

    @Override
    public int chooseNumber(final SpellAbility sa, final String title, final int min, final int max) {
        return choice("chooseNumber", () -> inner.chooseNumber(sa, title, min, max), AiTapeController::num, AiTapeController::num);
    }

    @Override
    public int chooseNumber(final SpellAbility sa, final String string, final int min, final int max, final Map<String, Object> params) {
        return choice("chooseNumber", () -> inner.chooseNumber(sa, string, min, max, params), AiTapeController::num, AiTapeController::num);
    }

    @Override
    public int chooseNumber(final SpellAbility sa, final String title, final List<Integer> options, final Player relatedPlayer) {
        return choice("chooseNumber", () -> inner.chooseNumber(sa, title, options, relatedPlayer), AiTapeController::num,
                AiTapeController::num);
    }

    @Override
    public int chooseNumberForKeywordCost(final SpellAbility sa, final Cost cost, final KeywordInterface keyword, final String prompt,
            final int max) {
        return choice("chooseNumberForKeywordCost", () -> inner.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max),
                AiTapeController::num, AiTapeController::num);
    }

    @Override
    public int chooseNumberForCostReduction(final SpellAbility sa, final int min, final int max) {
        return choice("chooseNumberForCostReduction", () -> inner.chooseNumberForCostReduction(sa, min, max), AiTapeController::num,
                AiTapeController::num);
    }

    @Override
    public boolean chooseBinary(final SpellAbility sa, final String question, final BinaryChoiceType kindOfChoice, final Boolean defaultVal) {
        return choice("chooseBinary", () -> inner.chooseBinary(sa, question, kindOfChoice, defaultVal), AiTapeController::bool,
                AiTapeController::bool);
    }

    @Override
    public boolean chooseBinary(final SpellAbility sa, final String question, final BinaryChoiceType kindOfChoice,
            final Map<String, Object> params) {
        return choice("chooseBinary", () -> inner.chooseBinary(sa, question, kindOfChoice, params), AiTapeController::bool,
                AiTapeController::bool);
    }

    @Override
    public boolean chooseFlipResult(final SpellAbility sa, final Player flipper, final boolean call) {
        return choice("chooseFlipResult", () -> inner.chooseFlipResult(sa, flipper, call), AiTapeController::bool, AiTapeController::bool);
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(final SpellAbility sa, final List<AbilitySub> possible, final int min, final int num,
            final boolean allowRepeat) {
        final List<AbilitySub> offered = new ArrayList<>(possible);
        return choice("chooseModeForAbility", () -> inner.chooseModeForAbility(sa, possible, min, num, allowRepeat), v -> {
            if (v == null) {
                return JsonNull.INSTANCE;
            }
            final JsonArray a = new JsonArray();
            for (AbilitySub s : v) {
                int i = offered.indexOf(s);
                if (i < 0) {
                    // a mode the AI rebuilt: the offered one with its description
                    for (int k = 0; k < offered.size() && i < 0; k++) {
                        if (AiTapeCodec.descOf(offered.get(k)).equals(AiTapeCodec.descOf(s))) {
                            i = k;
                        }
                    }
                }
                if (i < 0) {
                    throw new IllegalStateException("a mode outside its options");
                }
                a.add(i);
            }
            final JsonObject o = new JsonObject();
            o.add("i", a);
            o.addProperty("of", offered.size());
            return o;
        }, e -> {
            AiTapeCodec.checkEncodable(e);
            if (e.isJsonNull()) {
                return null;
            }
            final JsonObject o = e.getAsJsonObject();
            if (o.get("of").getAsInt() != offered.size()) {
                throw new IllegalStateException(offered.size() + " modes offered, " + o.get("of").getAsInt() + " recorded");
            }
            final List<AbilitySub> out = new ArrayList<>();
            for (JsonElement x : o.getAsJsonArray("i")) {
                out.add(offered.get(x.getAsInt()));
            }
            return out;
        });
    }

    @Override
    public byte chooseColor(final String message, final SpellAbility sa, final ColorSet colors) {
        return choice("chooseColor", () -> inner.chooseColor(message, sa, colors), b -> new JsonPrimitive(b), e -> {
            AiTapeCodec.checkEncodable(e);
            return e.getAsByte();
        });
    }

    @Override
    public byte chooseColorAllowColorless(final String message, final Card card, final ColorSet colors) {
        return choice("chooseColorAllowColorless", () -> inner.chooseColorAllowColorless(message, card, colors), b -> new JsonPrimitive(b),
                e -> {
                    AiTapeCodec.checkEncodable(e);
                    return e.getAsByte();
                });
    }

    @Override
    public ColorSet chooseColors(final String message, final SpellAbility sa, final int min, final int max, final ColorSet options) {
        return choice("chooseColors", () -> inner.chooseColors(message, sa, min, max, options),
                c -> c == null ? JsonNull.INSTANCE : new JsonPrimitive(c.getColor()), e -> {
                    AiTapeCodec.checkEncodable(e);
                    return e.isJsonNull() ? null : ColorSet.fromMask(e.getAsInt());
                });
    }

    @Override
    public String chooseSomeType(final String kindOfType, final SpellAbility sa, final Collection<String> validTypes, final boolean isOptional) {
        return choice("chooseSomeType", () -> inner.chooseSomeType(kindOfType, sa, validTypes, isOptional), AiTapeController::text,
                AiTapeController::text);
    }

    @Override
    public String chooseSector(final Card assignee, final String ai, final List<String> sectors) {
        return choice("chooseSector", () -> inner.chooseSector(assignee, ai, sectors), AiTapeController::text, AiTapeController::text);
    }

    @Override
    public int chooseSprocket(final Card assignee, final List<Integer> sprockets) {
        return choice("chooseSprocket", () -> inner.chooseSprocket(assignee, sprockets), AiTapeController::num, AiTapeController::num);
    }

    @Override
    public PlanarDice choosePDRollToIgnore(final List<PlanarDice> rolls) {
        return oneOf("choosePDRollToIgnore", rolls, () -> inner.choosePDRollToIgnore(rolls));
    }

    @Override
    public Integer chooseRollToIgnore(final List<Integer> rolls) {
        return choice("chooseRollToIgnore", () -> inner.chooseRollToIgnore(rolls), AiTapeController::num, AiTapeController::num);
    }

    @Override
    public List<Integer> chooseDiceToReroll(final List<Integer> rolls) {
        return choice("chooseDiceToReroll", () -> inner.chooseDiceToReroll(rolls), v -> {
            if (v == null) {
                return JsonNull.INSTANCE;
            }
            final JsonArray a = new JsonArray();
            v.forEach(a::add);
            return a;
        }, e -> {
            AiTapeCodec.checkEncodable(e);
            if (e.isJsonNull()) {
                return null;
            }
            final List<Integer> out = new ArrayList<>();
            e.getAsJsonArray().forEach(x -> out.add(x.getAsInt()));
            return out;
        });
    }

    @Override
    public Integer chooseRollToModify(final List<Integer> rolls) {
        return choice("chooseRollToModify", () -> inner.chooseRollToModify(rolls), AiTapeController::num, AiTapeController::num);
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(final List<RollDiceEffect.DieRollResult> rolls) {
        return oneOf("chooseRollToSwap", rolls, () -> inner.chooseRollToSwap(rolls));
    }

    @Override
    public String chooseRollSwapValue(final List<String> swapChoices, final Integer currentResult, final int power, final int toughness) {
        return choice("chooseRollSwapValue", () -> inner.chooseRollSwapValue(swapChoices, currentResult, power, toughness),
                AiTapeController::text, AiTapeController::text);
    }

    @Override
    public Object vote(final SpellAbility sa, final String prompt, final List<Object> options, final ListMultimap<Object, Player> votes,
            final Player forPlayer, final boolean optional) {
        return oneOf("vote", options, () -> inner.vote(sa, prompt, options, votes, forPlayer, optional));
    }

    @Override
    public ICardFace chooseSingleCardFace(final SpellAbility sa, final List<ICardFace> faces, final String message) {
        return oneOf("chooseSingleCardFace", faces, () -> inner.chooseSingleCardFace(sa, faces, message));
    }

    @Override
    public ICardFace chooseSingleCardFace(final SpellAbility sa, final String message, final Predicate<ICardFace> cpp, final String name) {
        return choice("chooseSingleCardFace", () -> inner.chooseSingleCardFace(sa, message, cpp, name),
                f -> f == null ? JsonNull.INSTANCE : new JsonPrimitive(f.getName()), e -> {
                    AiTapeCodec.checkEncodable(e);
                    if (e.isJsonNull()) {
                        return null;
                    }
                    final ICardFace f = StaticData.instance().getCommonCards().getFaceByName(e.getAsString());
                    if (f == null) {
                        throw new IllegalStateException("the named face is not in this card database");
                    }
                    return f;
                });
    }

    @Override
    public CardState chooseSingleCardState(final SpellAbility sa, final List<CardState> states, final String message,
            final Map<String, Object> params) {
        return oneOf("chooseSingleCardState", states, () -> inner.chooseSingleCardState(sa, states, message, params));
    }

    @Override
    public boolean chooseCardsPile(final SpellAbility sa, final CardCollectionView pile1, final CardCollectionView pile2, final String faceUp) {
        return choice("chooseCardsPile", () -> inner.chooseCardsPile(sa, pile1, pile2, faceUp), AiTapeController::bool,
                AiTapeController::bool);
    }

    @Override
    public CounterType chooseCounterType(final List<CounterType> options, final SpellAbility sa, final String prompt,
            final Map<String, Object> params) {
        return oneOf("chooseCounterType", options, () -> inner.chooseCounterType(options, sa, prompt, params));
    }

    @Override
    public String chooseKeywordForPump(final List<String> options, final SpellAbility sa, final String prompt, final Card tgtCard) {
        return choice("chooseKeywordForPump", () -> inner.chooseKeywordForPump(options, sa, prompt, tgtCard), AiTapeController::text,
                AiTapeController::text);
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(final List<ReplacementEffect> possibleReplacers) {
        return oneOf("chooseSingleReplacementEffect", possibleReplacers, () -> inner.chooseSingleReplacementEffect(possibleReplacers));
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(final List<StaticAbility> possibleStatics) {
        return oneOf("chooseSingleStaticAbility", possibleStatics, () -> inner.chooseSingleStaticAbility(possibleStatics));
    }

    @Override
    public String chooseProtectionType(final SpellAbility sa, final List<String> choices) {
        return choice("chooseProtectionType", () -> inner.chooseProtectionType(sa, choices), AiTapeController::text, AiTapeController::text);
    }

    @Override
    public String chooseCardName(final SpellAbility sa, final List<ICardFace> faces, final String message) {
        return choice("chooseCardName", () -> inner.chooseCardName(sa, faces, message), AiTapeController::text, AiTapeController::text);
    }

    @Override
    public String chooseCardName(final SpellAbility sa, final Predicate<ICardFace> cpp, final String valid, final String message) {
        return choice("chooseCardName", () -> inner.chooseCardName(sa, cpp, valid, message), AiTapeController::text,
                AiTapeController::text);
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(final SpellAbility chosen, final List<OptionalCostValue> optionalCostValues) {
        return manyOf("chooseOptionalCosts", optionalCostValues, () -> inner.chooseOptionalCosts(chosen, optionalCostValues));
    }

    @Override
    public List<CostPart> orderCosts(final List<CostPart> costs) {
        return manyOf("orderCosts", costs, () -> inner.orderCosts(costs));
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(final SpellAbility sa, final ColorSet colorSet, final int manaAmount, final boolean different) {
        return choice("specifyManaCombo", () -> inner.specifyManaCombo(sa, colorSet, manaAmount, different), m -> {
            if (m == null) {
                return JsonNull.INSTANCE;
            }
            final JsonArray a = new JsonArray();
            for (Map.Entry<Byte, Integer> en : m.entrySet()) {
                final JsonArray p = new JsonArray();
                p.add(en.getKey());
                p.add(en.getValue());
                a.add(p);
            }
            return a;
        }, e -> {
            AiTapeCodec.checkEncodable(e);
            if (e.isJsonNull()) {
                return null;
            }
            final Map<Byte, Integer> m = new LinkedHashMap<>();
            for (JsonElement x : e.getAsJsonArray()) {
                m.put(x.getAsJsonArray().get(0).getAsByte(), x.getAsJsonArray().get(1).getAsInt());
            }
            return m;
        });
    }

    @Override
    public boolean payCostDuringRoll(final Cost cost, final SpellAbility sa) {
        return choice("payCostDuringRoll", () -> inner.payCostDuringRoll(cost, sa), AiTapeController::bool, AiTapeController::bool);
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(final List<SpellAbility> activePlayerSAs) {
        return manyOf("orderSimultaneousSa", activePlayerSAs, () -> inner.orderSimultaneousSa(activePlayerSAs));
    }

    // ------------------------------------------------------------------ execution: runs in both modes

    @Override
    public void playSpellAbilityNoStack(final SpellAbility effectSA, final boolean canSetupTargets) {
        exec("playSpellAbilityNoStack", () -> {
            inner.playSpellAbilityNoStack(effectSA, canSetupTargets);
            return Boolean.TRUE;
        }, () -> {
            // PlayerControllerAi's, without the AI choosing targets: the "n" checkpoint sets the record's
            ComputerUtil.playNoStack(getPlayer(), effectSA, getGame(), true);
            return Boolean.TRUE;
        }, AiTapeController::bool, null);
    }

    @Override
    public boolean playTrigger(final Card host, final WrappedAbility wrapperAbility, final boolean isMandatory) {
        return exec("playTrigger", () -> inner.playTrigger(host, wrapperAbility, isMandatory), () -> {
            if (forcedPrepare(host, wrapperAbility)) {
                return ComputerUtil.playNoStack(wrapperAbility.getActivatingPlayer(), wrapperAbility, getGame(), true);
            }
            return false;
        }, AiTapeController::bool, a -> a.getAsBoolean() ? null : Boolean.FALSE);
    }

    @Override
    public boolean playSaFromPlayEffect(final SpellAbility tgtSA) {
        return exec("playSaFromPlayEffect", () -> inner.playSaFromPlayEffect(tgtSA), () -> {
            if (tgtSA instanceof Spell) {
                return ComputerUtil.playStack(tgtSA, getPlayer(), getGame());
            }
            return true;
        }, AiTapeController::bool, a -> a.getAsBoolean() ? null : Boolean.FALSE);
    }

    @Override
    public void orderAndPlaySimultaneousSa(final List<SpellAbility> activePlayerSAs) {
        exec("orderAndPlaySimultaneousSa", () -> {
            inner.orderAndPlaySimultaneousSa(activePlayerSAs);
            return Boolean.TRUE;
        }, () -> {
            // PlayerControllerAi.orderAndPlaySimultaneousSa: the order and every trigger's targets are the record's
            final List<SpellAbility> order = activePlayerSAs.size() > 1 ? inner.orderSimultaneousSa(activePlayerSAs) : activePlayerSAs;
            for (final SpellAbility sa : order) {
                if (sa.isTrigger() && !sa.isCopied()) {
                    if (forcedPrepare(sa.getHostCard(), sa)) {
                        ComputerUtil.playStack(sa, getPlayer(), getGame());
                    }
                } else {
                    if (sa.isCopied()) {
                        if (sa.isSpell()) {
                            if (!sa.getHostCard().isInZone(ZoneType.Stack)) {
                                sa.setHostCard(getGame().getAction().moveToStack(sa.getHostCard(), sa));
                            } else {
                                getGame().getStackZone().add(sa.getHostCard());
                            }
                        }
                        AiTape.checkpoint(getPlayer(), sa, "c");
                    }
                    getGame().getStack().add(sa);
                }
            }
            return Boolean.TRUE;
        }, AiTapeController::bool, null);
    }

    /**
     * PlayerControllerAi.prepareSingleSa without the AI's own target choice (the record's are set at the payment's
     * checkpoint): a charm's modes (its chooseModeForAbility is a recorded decision), another player's targets (theirs).
     */
    private boolean forcedPrepare(final Card host, final SpellAbility sa0) {
        return tape.forcedPrep(() -> forcedPrepareNonAi(host, sa0));
    }

    private boolean forcedPrepareNonAi(final Card host, SpellAbility sa) {
        if (sa.getApi() == ApiType.Charm) {
            if (!CharmEffect.makeChoices(sa)) {
                return false;
            }
            if (!sa.hasParam("Random")) {
                return true;
            }
            sa = sa.getSubAbility();
        }
        if (sa != null && sa.hasParam("TargetingPlayer")) {
            final Player targetingPlayer = AbilityUtils.getDefinedPlayers(host, sa.getParam("TargetingPlayer"), sa).get(0);
            sa.setTargetingPlayer(targetingPlayer);
            return targetingPlayer.getController().chooseTargetsFor(sa);
        }
        return true;
    }

    @Override
    public boolean payCostToPreventEffect(final Cost cost, final SpellAbility sa, final boolean alreadyPaid, final FCollectionView<Player> allPayers) {
        return exec("payCostToPreventEffect", () -> inner.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers), () -> {
            final CostPayment pay = new CostPayment(cost, sa);
            return pay.payComputerCosts(AiCostDecision.forPayment(getPlayer(), sa, true));
        }, AiTapeController::bool, a -> a.getAsBoolean() ? null : Boolean.FALSE);
    }

    @Override
    public boolean payManaCost(final ManaCost toPay, final CostPartMana costPartMana, final SpellAbility sa, final String prompt,
            final ManaConversionMatrix matrix, final boolean effect) {
        return exec("payManaCost", () -> inner.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect),
                () -> ComputerUtilMana.payManaCost(new Cost(toPay, effect), getPlayer(), sa, effect), AiTapeController::bool, null);
    }

    @Override
    public boolean applyManaToCost(final ManaCostBeingPaid toPay, final SpellAbility ability, final String prompt,
            final ManaConversionMatrix matrix, final boolean effect) {
        return exec("applyManaToCost", () -> inner.applyManaToCost(toPay, ability, prompt, matrix, effect),
                () -> ComputerUtilMana.payManaCost(toPay, ability, getPlayer(), effect), AiTapeController::bool, null);
    }

    @Override
    public boolean payCombatCost(final Card c, final Cost cost, final SpellAbility sa, final String prompt) {
        return exec("payCombatCost", () -> inner.payCombatCost(c, cost, sa, prompt),
                () -> ComputerUtil.playNoStack(c.getController(), sa, getGame(), true), AiTapeController::bool, null);
    }

    @Override
    public boolean helpPayForAssistSpell(final ManaCostBeingPaid cost, final SpellAbility sa, final int max, final int requested) {
        return exec("helpPayForAssistSpell", () -> inner.helpPayForAssistSpell(cost, sa, max, requested),
                () -> inner.helpPayForAssistSpell(cost, sa, max, requested), AiTapeController::bool, null);
    }

    /** LobbyPlayer identity is the inner controller's (the same object). */
    @Override
    public LobbyPlayer getLobbyPlayer() {
        return inner.getLobbyPlayer();
    }
}
