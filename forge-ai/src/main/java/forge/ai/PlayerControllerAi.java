package forge.ai;

import com.google.common.collect.*;
import forge.LobbyPlayer;
import forge.StaticData;
import forge.ai.ability.ProtectAi;
import forge.card.CardStateName;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.cost.*;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.*;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.*;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.function.Consumer;

import forge.ai.dp.DecisionAnswerer;
import forge.ai.dp.DecisionPoint;
import forge.ai.dp.DecisionPoints;
import forge.ai.dp.Kind;
import java.util.function.Predicate;
import java.util.stream.Collectors;


/**
 * A prototype for player controller class
 *
 * Handles phase skips for now.
 */
public class PlayerControllerAi extends PlayerController {
    private final AiController brains;

    private boolean pilotsNonAggroDeck = false;

    /** fairNaming (lane ai-misplays-1006; {@link FairNaming}): names of opponents' cards this player has seen this game. */
    private final Set<String> fairNamingSeen = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PlayerControllerAi(Game game, Player p, LobbyPlayer lp) {
        super(game, p, lp);

        brains = new AiController(p, game);
    }

    public boolean pilotsNonAggroDeck() {
        return pilotsNonAggroDeck;
    }

    /**
     * mtgx lane forge-bot-tables-1011 (null unless a host sets one): what this AI seat is shown (reveals) and what it looks
     * at to arrange (scry, surveil, an ordered move), reported before the AI's own handling, as the review's observed
     * human controller reports them. The AI's decisions do not change; a game copy's controller never has a hook.
     */
    public interface KnowledgeHook {
        void onReveal(List<Card> cards, ZoneType zone, Player owner);

        void onLook(List<Card> cards, ZoneType destination);
    }

    private KnowledgeHook knowledgeHook = null;

    /**
     * r2-choose-a-card-1029 (research; null = off, the default, and then every answer below is Forge AI's own, byte for
     * byte): the choose-a-card hook ({@link CacHook}). Forge AI answers first, exactly as without it; the hook sees that
     * answer and may replace it. Set only on a live seat's controller; a game copy's controllers never have one.
     */
    private CacHook cacHook = null;
    /**
     * r2-choose-a-card-1029: a subclass that answers an ask itself (the RL bridge) and applies the hook once to its own
     * final answer sets this while it computes that answer, so a fallback through these methods is not hooked twice.
     */
    protected boolean cacSuppressed = false;

    // ------------------------------------------------------------------ decisionpoint-1102 (layer 2, PR-B)
    /** The two answers of a yes/no point. */
    static final List<Boolean> DP_BOOLS = List.of(Boolean.TRUE, Boolean.FALSE);
    /** An answerer for this seat's decision points (null = none, the default). */
    private DecisionAnswerer dpAnswerer = null;
    /** Decision points this controller is computing an answer to right now (> 0: the next one is nested). */
    private int dpDepth = 0;

    public void setDecisionAnswerer(final DecisionAnswerer a) {
        this.dpAnswerer = a;
    }

    public DecisionAnswerer getDecisionAnswerer() {
        return dpAnswerer;
    }

    /**
     * decisionpoint-1102 (CENSUS.md §5.4): one decision of this seat. {@code forge} computes Forge AI's own answer,
     * exactly as the method did before; then the choose-a-card hook (through {@link CacAdapter}, under the same
     * condition as before: a hook is set and not suppressed), an installed answerer and the census observer
     * ({@link DecisionPoints#resolve}). With no hook, answerer or observer it returns Forge's answer and builds nothing.
     */
    @SuppressWarnings("unchecked")
    protected final <A> A dp(final Kind kind, final String ctx, final SpellAbility sa, final Collection<?> options,
            final int min, final int max, final java.util.function.Supplier<A> forge, final Object... args) {
        final boolean inst = dpAnswerer != null || DecisionPoints.observer() != null;
        final A def;
        final boolean nested = dpDepth > 0;
        if (inst) {
            dpDepth++;
            try {
                def = forge.get();
            } finally {
                dpDepth--;
            }
        } else {
            def = forge.get();
        }
        final boolean cac = cacHook != null && !cacSuppressed && CacAdapter.handles(ctx);
        if (!inst && !cac) {
            return def;
        }
        final DecisionPoint p = nested
                ? DecisionPoints.nestedPoint(kind, ctx, player, sa, options, min, max, def, args)
                : DecisionPoints.point(kind, ctx, player, sa, options, min, max, def, args);
        return (A) DecisionPoints.resolve(p, cac ? new CacAdapter(this, cacHook) : null, dpAnswerer);
    }

    /** Whether anything (answerer or census observer) looks at this seat's decision points. */
    public final boolean dpInstrumented() {
        return dpAnswerer != null || DecisionPoints.observer() != null;
    }

    /**
     * decisionpoint-1102 PR-C: one decision made inside Forge AI's execution code (a seam: a mana source per symbol, a
     * cost part's cards, the targets / X fixed before payment), for {@code p}'s seat. {@code forge} is Forge AI's answer
     * exactly as the seam computed it; {@code cacAnswer} (null = none) is the choose-a-card hook's answer where the
     * seam already had one (the cost review), counted as {@code by=cac}; {@code valid} (null = the option list's)
     * checks an answerer's departure. Without an answerer or observer: {@code forge}'s value, nothing else.
     */
    @SuppressWarnings("unchecked")
    public static <A> A seam(final Player p, final Kind kind, final String ctx, final SpellAbility sa,
            final Collection<?> options, final int min, final int max, final java.util.function.Predicate<Object> valid,
            final java.util.function.Supplier<A> forge, final java.util.function.UnaryOperator<A> cacAnswer,
            final Object... args) {
        final PlayerControllerAi pc = cacOf(p);
        if (pc == null || !pc.dpInstrumented()) {
            final A def = forge.get();
            return cacAnswer == null ? def : cacAnswer.apply(def);
        }
        final boolean nested = pc.dpDepth > 0; // review #175 S6: a seam asked inside another answer is nested
        final A def = forge.get();
        final DecisionPoint pt = (nested ? DecisionPoints.nestedPoint(kind, ctx, p, sa, options, min, max, def, args)
                : DecisionPoints.point(kind, ctx, p, sa, options, min, max, def, args)).withValidator(valid);
        final forge.ai.dp.DecisionAnswerer cac = cacAnswer == null ? null : (d, cur) -> cacAnswer.apply((A) cur);
        return (A) DecisionPoints.resolve(pt, cac, pc.dpAnswerer);
    }

    /** A decision answered by mutating its argument (combat declarations): observed only; no answerer in PR-B. */
    protected final void dpVoid(final Kind kind, final String ctx, final SpellAbility sa, final Runnable forge,
            final Object... args) {
        dp(kind, ctx, sa, null, -1, -1, () -> {
            forge.run();
            return null;
        }, args);
    }

    /**
     * A subclass (the RL bridge) that computed its own answer with the hook suppressed applies the choose-a-card hook
     * once to it, through the same adapter. The bridge's own answer was already observed through {@link #dp}, so a
     * departure here is noted as {@code cac-override}, not counted twice.
     */
    @SuppressWarnings("unchecked")
    protected final <A> A cacOnce(final Kind kind, final String ctx, final SpellAbility sa,
            final Collection<?> options, final int min, final int max, final A def, final Object... args) {
        if (cacHook == null || !CacAdapter.handles(ctx)) {
            return def;
        }
        final DecisionPoint p = DecisionPoints.point(kind, ctx, player, sa, options, min, max, def, args);
        final A out = (A) new CacAdapter(this, cacHook).answer(p, def);
        if (out != def && !java.util.Objects.equals(out, def)) {
            DecisionPoints.note(player, kind, ctx, p.optionCount(), "cac-override");
        }
        return out;
    }

    public void setCacHook(final CacHook hook) {
        this.cacHook = hook;
    }

    public CacHook getCacHook() {
        return cacHook;
    }

    /**
     * Review #147 S2: the controller holding a player's choose-a-card hook. A taped seat's controller is the
     * {@link AiTapeController} wrapper, and the hook is installed on the controller it wraps.
     */
    public static PlayerControllerAi cacOf(final Player p) {
        final forge.game.player.PlayerController c = p == null ? null : p.getController();
        if (c instanceof AiTapeController t) return t.inner();
        return c instanceof PlayerControllerAi pc ? pc : null;
    }

    public void setKnowledgeHook(final KnowledgeHook hook) {
        this.knowledgeHook = hook;
    }

    /**
     * Lane bvw-decision-log-1027: what this seat's observers see when it looks at {@code cards} to arrange them (scry,
     * surveil, an ordered move), as its own arranging methods report it first, without deciding anything. The AI tape's
     * forced replay calls it where the record's decision was (the decision is the record's).
     */
    public void witnessLook(final List<Card> cards, final ZoneType destination) {
        if (knowledgeHook != null && cards != null) {
            knowledgeHook.onLook(cards, destination);
        }
    }

    /** fairNaming memory ({@link FairNaming#observe}); filled only when this player's option is not off. */
    Set<String> getFairNamingSeen() {
        return fairNamingSeen;
    }

    public void setupAutoProfile(Deck deck) {
        pilotsNonAggroDeck = deck.getName().contains("Control") || deck.getAverageCMC() > 3;
    }

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        return dp(Kind.SELECT, "getAbilityToPlay", null, abilities, 1, 1, () -> getAbilityToPlayAi(hostCard, abilities, triggerEvent), hostCard, abilities, triggerEvent);
    }

    private SpellAbility getAbilityToPlayAi(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        if (abilities.isEmpty()) {
            return null;
        }
        return abilities.get(0);
    }

    public AiController getAi() {
        return brains;
    }

    @Override
    public boolean isAI() {
        return true;
    }

    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        if (!brains.getGame().getRules().getAISideboardingEnabled() || !deck.has(DeckSection.Sideboard)) {
            return null;
        }

        Map<PaperCard, PaperCard> sideboardPlan = Maps.newHashMap();
        List<PaperCard> main = deck.get(DeckSection.Main).toFlatList();
        List<PaperCard> sideboard = deck.get(DeckSection.Sideboard).toFlatList();

        // Predefined sideboard plan from deck metadata (AI hints)
        boolean definedSideboardPlan = false;
        String sideboardAiHint = deck.getAiHint("SideboardingPlan");
        if (!sideboardAiHint.isEmpty()) {
            for (String element : sideboardAiHint.split(";")) {
                String[] cardPair = element.split("->");
                PaperCard src = null, tgt = null;
                for (PaperCard cMain : main) {
                    if (cMain.getCardName().equals(cardPair[0].trim())) {
                        src = cMain;
                        break;
                    }
                }
                for (PaperCard cSide : sideboard) {
                    if (cSide.getCardName().equals(cardPair[1].trim())) {
                        tgt = cSide;
                        break;
                    }
                }
                if (src != null && tgt != null) {
                    sideboardPlan.put(src, tgt);
                }
            }
            if (!sideboardPlan.isEmpty()) {
                definedSideboardPlan = true;
            }
        }

        boolean sbLimitedFormats = getAi().getBoolProperty(AiProps.SIDEBOARDING_IN_LIMITED_FORMATS);
        boolean sbSharedTypesOnly = getAi().getBoolProperty(AiProps.SIDEBOARDING_SHARED_TYPE_ONLY);
        boolean sbPlaneswalkerException = getAi().getBoolProperty(AiProps.SIDEBOARDING_PLANESWALKER_EQ_CREATURE);
        int sbChanceOnWin = getAi().getIntProperty(AiProps.SIDEBOARDING_CHANCE_ON_WIN);
        int sbChancePerCard = getAi().getIntProperty(AiProps.SIDEBOARDING_CHANCE_PER_CARD);

        if (!sbLimitedFormats && gameType.isCardPoolLimited()) {
            return null;
        }

        GameOutcome lastOutcome = brains.getGame().getMatch().getLastOutcome();
        if (lastOutcome.getWinningPlayer().getPlayer().equals(player.getLobbyPlayer())
            && MyRandom.getRandom().nextInt(100) > sbChanceOnWin) {
            return null;
        }

        // Devise a sideboarding plan
        if (!definedSideboardPlan) {
            List<PaperCard> processed = Lists.newArrayList();
            for (PaperCard cSide : sideboard) {
                if (processed.contains(cSide)) {
                    continue;
                } else if (cSide.getRules().getAiHints().getRemAIDecks()) {
                    continue; // don't sideboard in anything that we don't know how to play
                } else if (cSide.getRules().getType().isLand()) {
                    continue; // don't know how to sideboard lands efficiently yet
                }

                for (PaperCard cMain : main) {
                    if (processed.contains(cMain)) {
                        continue;
                    } else if (cMain.getName().equals(cSide.getName())) {
                        continue;
                    } else if (cMain.getRules().getType().isLand()) {
                        continue; // don't know how to sideboard lands efficiently yet
                    }

                    if (sbSharedTypesOnly) {
                        if (!cMain.getRules().getType().sharesCardTypeWith(cSide.getRules().getType())) {
                            continue; // Only equivalent types allowed
                        }
                    } else {
                        if ((cMain.getRules().getType().isCreature() && !cSide.getRules().getType().isCreature())
                                || (cSide.getRules().getType().isCreature()) && !cMain.getRules().getType().isCreature()) {
                            if (!(sbPlaneswalkerException && (cMain.getRules().getType().isPlaneswalker() || cSide.getRules().getType().isPlaneswalker()))) {
                                continue; // Creature exception: only trade a creature for another creature unless planeswalkers are allowed as a replacement
                            }
                        }
                    }

                    if (!Card.fromPaperCard(cMain, player).getManaAbilities().isEmpty()) {
                        processed.add(cMain);
                        continue; // Mana Ability exception: Don't sideboard out cards that produce mana, can screw up the mana base
                    }

                    // Try not to screw up the mana curve or color distribution too much
                    if (cSide.getRules().getColor().hasNoColorsExcept(cMain.getRules().getColor())
                            && cMain.getRules().getManaCost().getCMC() == cSide.getRules().getManaCost().getCMC()) {
                        sideboardPlan.put(cMain, cSide);
                        processed.add(cSide);
                        processed.add(cMain);
                        break;
                    }
                }
            }
        }

        // Make changes according to the sideboarding plan suggested above
        for (Map.Entry<PaperCard, PaperCard> ent : sideboardPlan.entrySet()) {
            if (!definedSideboardPlan && MyRandom.getRandom().nextInt(100) < sbChancePerCard) {
                continue;
            }
            long inMain = main.stream().filter(pc -> pc.getCardName().equals(ent.getKey().getName())).count();
            long inSide = sideboard.stream().filter(pc -> pc.getCardName().equals(ent.getValue().getName())).count();
            while (inMain-- > 0 && inSide-- > 0) {
                sideboard.remove(ent.getValue());
                sideboard.add(ent.getKey());
                main.add(ent.getValue());
                main.remove(ent.getKey());
            }
        }

        // Return the new Main. It's important to make sure that the overall content of the deck (Main+Sideboard)
        // does not change above, or the AI may cheat (sneak some cards in or remove them from the deck altogether).
        return main;
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        return dp(Kind.NUMBER, "assignCombatDamage", null, blockers, -1, -1, () -> assignCombatDamageAi(attacker, blockers, remaining, damageDealt, defender, overrideOrder), attacker, blockers, remaining, damageDealt, defender, overrideOrder);
    }

    private Map<Card, Integer> assignCombatDamageAi(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        return ComputerUtilCombat.distributeAIDamage(player, attacker, blockers, remaining, damageDealt, defender, overrideOrder);
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        return dp(Kind.NUMBER, "divideShield", null, affected == null ? null : affected.keySet(), -1, -1, () -> divideShieldAi(effectSource, affected, shieldAmount), effectSource, affected, shieldAmount);
    }

    private Map<GameEntity, Integer> divideShieldAi(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        // TODO: AI currently can't use this so this is not implemented.
        return new HashMap<>();
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        return dp(Kind.CHOICE, "specifyManaCombo", sa, null, -1, -1, () -> specifyManaComboAi(sa, colorSet, manaAmount, different), sa, colorSet, manaAmount, different);
    }

    private Map<Byte, Integer> specifyManaComboAi(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        Map<Byte, Integer> result = new HashMap<>();
        for (int i = 0; i < manaAmount; ++i) {
            Byte chosen = chooseColor("", sa, colorSet);
            result.merge(chosen, 1, Integer::sum);
            if (different) {
                colorSet = ColorSet.fromMask(colorSet.getColor() - chosen);
            }
        }
        return result;
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        return dp(Kind.NUMBER, "announceRequirements", ability, null, min, max, () -> announceRequirementsAi(ability, min, max, announce), ability, min, max, announce);
    }

    private Integer announceRequirementsAi(SpellAbility ability, int min, int max, String announce) {
        // For now, these "announcements" are made within the AI classes of the appropriate SA effects
        if (ability.getApi() != null) {
            switch (ability.getApi()) {
                case ChooseNumber:
                    Player payingPlayer = ability.getActivatingPlayer();
                    String logic = ability.getParamOrDefault("AILogic", "");
                    boolean anyController = logic.equals("MaxForAnyController");

                    if (logic.startsWith("PowerLeakMaxMana.") && ability.getHostCard().isEnchantingCard()) {
                        // For cards like Power Leak, the payer will be the owner of the enchanted card
                        // TODO: is there any way to generalize this and avoid a special exclusion?
                        payingPlayer = ability.getHostCard().getEnchantingCard().getController();
                    }

                    int number = ComputerUtilMana.determineLeftoverMana(ability, player, false);

                    if (logic.startsWith("MaxMana.") || logic.startsWith("PowerLeakMaxMana.")) {
                        number = Math.min(number, Integer.parseInt(logic.substring(logic.indexOf(".") + 1)));
                    }

                    return payingPlayer.isOpponentOf(player) && !anyController ? 0 : number;
                case BidLife:
                    return 0;
                default:
                    return null;
            }
        }
        return null; // return incorrect value to indicate that
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        // r3-inline-choices-1030: the choose-a-card hook may replace the sacrificed permanents (no hook: Forge AI's)
        return dp(Kind.SELECT, "choosePermanentsToSacrifice", sa, validTargets, min, max,
                () -> ComputerUtil.choosePermanentsToSacrifice(player, validTargets, max, sa, false, min == 0),
                min, max, validTargets);
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return dp(Kind.SELECT, "choosePermanentsToDestroy", sa, validTargets, min, max, () -> choosePermanentsToDestroyAi(sa, min, max, validTargets, message), sa, min, max, validTargets, message);
    }

    private CardCollectionView choosePermanentsToDestroyAi(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return ComputerUtil.choosePermanentsToSacrifice(player, validTargets, max, sa, true, min == 0);
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        return dp(Kind.SELECT, "chooseCardsForEffect", sa, sourceList, min, max, () -> chooseCardsForEffectAi(sourceList, sa, title, min, max, isOptional, params), sourceList, sa, title, min, max, isOptional, params);
    }

    private CardCollectionView chooseCardsForEffectAi(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        return brains.chooseCardsForEffect(sourceList, sa, min, max, isOptional, params);
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        return dp(Kind.SELECT, "chooseContraptionsToCrank", null, contraptions, 0, contraptions == null ? -1 : contraptions.size(), () -> chooseContraptionsToCrankAi(contraptions), contraptions);
    }

    private List<Card> chooseContraptionsToCrankAi(List<Card> contraptions) {
        return CardLists.filter(contraptions, c -> {
            Trigger crankTrigger = IterableUtil.find(c.getTriggers(), t -> t.getMode() == TriggerType.CrankContraption);
            return confirmTrigger(new WrappedAbility(crankTrigger, crankTrigger.getOverridingAbility(), player));
        });
    }

    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        return dp(Kind.CHOICE, "helpPayForAssistSpell", sa, DP_BOOLS, 1, 1, () -> helpPayForAssistSpellAi(cost, sa, max, requested), cost, sa, max, requested);
    }

    private boolean helpPayForAssistSpellAi(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        int toPay = getAi().attemptToAssist(sa, max, requested);

        if (toPay == 0) {
            return true;
        } else {
            ManaCost manaCost = ManaCost.get(toPay);
            ManaCostBeingPaid assistCost = new ManaCostBeingPaid(manaCost);
            if (ComputerUtilMana.canPayManaCost(assistCost, sa, player, false)) {
                ComputerUtilMana.payManaCost(assistCost, sa, player, false);
                cost.decreaseGenericMana(toPay);
                return true;
            }
        }
        return true;
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) {
        return dp(Kind.SELECT, "choosePlayerToAssistPayment", sa, optionList, 0, 1, () -> choosePlayerToAssistPaymentAi(optionList, sa, title, max), optionList, sa, title, max);
    }

    private Player choosePlayerToAssistPaymentAi(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) {
        //        if (optionList.size() == 1) {
        //            return null;
        //        }
        //return optionList.getFirst();

        // AI is dumb and will request assistance even if they can't afford with assistance.
        // For now, just never try to use Assist.

        // Ideally, it would do something like
        // Verify we actually want to play this. Including: "Would play with assistance" and "would play without assistance"
        // Find an ally/player that might be helpful to pay for an effect
        // If no one seems likely, just return null
        // If player fails to assist, don't try to request assistance until next turn
        // If player fails to assist, maybe still cast it anyway?

        return null;
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player targetedPlayer, Map<String, Object> params) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        // r3-inline-choices-1030: the legend rule's keep goes to the choose-a-card hook (no hook: Forge AI's)
        return dp(Kind.SELECT, "chooseSingleEntityForEffect", sa, optionList, isOptional ? 0 : 1, 1,
                () -> SpellApiToAi.Converter.get(sa).chooseSingleEntity(player, sa, optionList, isOptional, targetedPlayer, params),
                optionList);
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(
            FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title,
            Player targetedPlayer, Map<String, Object> params) {
        return dp(Kind.SELECT, "chooseEntitiesForEffect", sa, optionList, min, max,
                () -> chooseEntitiesForEffectAi(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params),
                optionList, min, max);
    }

    /** r2-choose-a-card-1029: a Dig ability's pick among our own cards goes to the hook; anything else is {@code def}. */
    @SuppressWarnings("unchecked")
    protected static <T extends GameEntity> List<T> cacDig(final PlayerControllerAi c, final CacHook h,
            final FCollectionView<T> optionList, final int min, final int max, final SpellAbility sa, final List<T> def) {
        if (sa == null || sa.getApi() != forge.game.ability.ApiType.Dig || optionList == null || def == null) {
            return def;
        }
        final List<Card> opts = new ArrayList<>();
        for (T t : optionList) {
            if (!(t instanceof Card)) {
                return def;
            }
            opts.add((Card) t);
        }
        final List<Card> d = new ArrayList<>();
        for (T t : def) {
            d.add((Card) t);
        }
        return (List<T>) h.dig(c, opts, min, max, sa, d);
    }

    private <T extends GameEntity> List<T> chooseEntitiesForEffectAi(
            FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title,
            Player targetedPlayer, Map<String, Object> params) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        FCollection<T> remaining = new FCollection<>(optionList);
        List<T> selecteds = new ArrayList<>();
        T selected;
        do {
            selected = chooseSingleEntityForEffect(remaining, null, sa, title, selecteds.size()>=min, targetedPlayer, params);
            if ( selected != null ) {
                remaining.remove(selected);
                selecteds.add(selected);
            }
        } while ( (selected != null ) && (selecteds.size() < max) );
        return selecteds;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title,
            int num, Map<String, Object> params) {
        return dp(Kind.SELECT, "chooseSpellAbilitiesForEffect", sa, spells, 0, num, () -> chooseSpellAbilitiesForEffectAi(spells, sa, title, num, params), spells, sa, title, num, params);
    }

    private List<SpellAbility> chooseSpellAbilitiesForEffectAi(List<SpellAbility> spells, SpellAbility sa, String title,
            int num, Map<String, Object> params) {
        List<SpellAbility> remaining = Lists.newArrayList(spells);
        List<SpellAbility> selecteds = Lists.newArrayList();
        SpellAbility selected;
        do {
            selected = chooseSingleSpellForEffect(remaining, sa, title, params);
            if (selected != null) {
                remaining.remove(selected);
                selecteds.add(selected);
            }
        } while (selected != null && selecteds.size() < num);
        return selecteds;
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title,
            Map<String, Object> params) {
        return dp(Kind.SELECT, "chooseSingleSpellForEffect", sa, spells, 1, 1, () -> chooseSingleSpellForEffectAi(spells, sa, title, params), spells, sa, title, params);
    }

    private SpellAbility chooseSingleSpellForEffectAi(List<SpellAbility> spells, SpellAbility sa, String title,
            Map<String, Object> params) {
        return SpellApiToAi.Converter.get(sa).chooseSingleSpellAbility(player, sa, spells, params);
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params) {
        return dp(Kind.CHOICE, "confirmAction", sa, DP_BOOLS, 1, 1,
                () -> confirmActionAi(sa, mode, message, options, cardToShow, params), mode, message);
    }

    private boolean confirmActionAi(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params) {
        return getAi().confirmAction(sa, mode, message, params);
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode mode, String string,
            int bid, Player winner) {
        return dp(Kind.CHOICE, "confirmBidAction", sa, DP_BOOLS, 1, 1, () -> confirmBidActionAi(sa, mode, string, bid, winner), sa, mode, string, bid, winner);
    }

    private boolean confirmBidActionAi(SpellAbility sa, PlayerActionConfirmMode mode, String string,
            int bid, Player winner) {
        return getAi().confirmBidAction(sa, mode, string, bid, winner);
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) {
        return dp(Kind.CHOICE, "confirmStaticApplication", null, DP_BOOLS, 1, 1, () -> confirmStaticApplicationAi(hostCard, mode, message, logic), hostCard, mode, message, logic);
    }

    private boolean confirmStaticApplicationAi(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) {
        return getAi().confirmStaticApplication(hostCard, logic);
    }

    // TODO: accept based on game state (infinite loop / unwinnable position); always declines for now
    public boolean acceptsDrawOffer() {
        return false;
    }

    @Override
    public boolean confirmTrigger(WrappedAbility wrapper) {
        return dp(Kind.CHOICE, "confirmTrigger", wrapper, DP_BOOLS, 1, 1, () -> confirmTriggerAi(wrapper), wrapper);
    }

    private boolean confirmTriggerAi(WrappedAbility wrapper) {
        final SpellAbility sa = wrapper.getWrappedAbility();
        //final Trigger regtrig = wrapper.getTrigger();
        if (wrapper.isMandatory()) {
            return true;
        }
        // Store/replace target choices more properly to get this SA cleared.
        TargetChoices tc = null;
        TargetChoices subtc = null;
        boolean storeChoices = sa.usesTargeting();
        final SpellAbility sub = sa.getSubAbility();
        boolean storeSubChoices = sub != null && sub.usesTargeting();
        boolean ret = true;

        if (storeChoices) {
            tc = sa.getTargets();
            sa.resetTargets();
        }
        if (storeSubChoices) {
            subtc = sub.getTargets();
            sub.resetTargets();
        }
        // There is no way this doTrigger here will have the same target as stored above
        // So it's possible it's making a different decision here than will actually happen
        if (!brains.doTrigger(sa, false)) {
            ret = false;
        }
        if (storeChoices) {
            sa.resetTargets();
            sa.setTargets(tc);
        }
        if (storeSubChoices) {
            sub.resetTargets();
            sub.setTargets(subtc);
        }

        return ret;
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String prompt, SpellAbility sa) {
        return dp(Kind.CHOICE, "confirmPayment", sa, DP_BOOLS, 1, 1, () -> confirmPaymentAi(costPart, prompt, sa), costPart, prompt, sa);
    }

    private boolean confirmPaymentAi(CostPart costPart, String prompt, SpellAbility sa) {
        return brains.confirmPayment(costPart); // AI is expected to know what it is paying for at the moment (otherwise add another parameter to this method)
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) {
        return dp(Kind.CHOICE, "confirmReplacementEffect", effectSA, DP_BOOLS, 1, 1, () -> confirmReplacementEffectAi(replacementEffect, effectSA, affected, question), replacementEffect, effectSA, affected, question);
    }

    private boolean confirmReplacementEffectAi(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) {
        Card host = replacementEffect.getHostCard();
        if (host.hasAlternateState()) {
            host = host.getGame().getCardState(host);
        }
        if (effectSA != null) {
            effectSA.setActivatingPlayer(host.getController());
        }
        return brains.aiShouldRun(replacementEffect, effectSA, host, affected);
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        return dp(Kind.SELECT, "exertAttackers", null, attackers, 0, attackers == null ? -1 : attackers.size(), () -> exertAttackersAi(attackers), attackers);
    }

    private List<Card> exertAttackersAi(List<Card> attackers) {
        return AiAttackController.exertAttackers(attackers, brains.getAttackAggression());
    }
 
    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return dp(Kind.SELECT, "enlistAttackers", null, attackers, 0, attackers == null ? -1 : attackers.size(), () -> enlistAttackersAi(attackers), attackers);
    }

    private List<Card> enlistAttackersAi(List<Card> attackers) {
        CardCollection cards = CostEnlist.getCardsForEnlisting(brains.getPlayer());
        cards = CardLists.filter(cards, CardPredicates.hasGreaterPowerThan(0));
        CardCollection chosenAttackers = new CardCollection(attackers);
        ComputerUtilCard.sortByEvaluateCreature(chosenAttackers);

        // do not enlist more than available payment choices (currently ignores multiple instances of Enlist, but can that even happen?)
        if (attackers.size() > cards.size()) {
            chosenAttackers = chosenAttackers.subList(0, cards.size());
        }
        // TODO check if not needed as defender
        return chosenAttackers;
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return dp(Kind.ARRANGE, "orderBlockers", null, blockers, -1, -1, () -> orderBlockersAi(attacker, blockers), attacker, blockers);
    }

    private CardCollection orderBlockersAi(Card attacker, CardCollection blockers) {
        return AiBlockController.orderBlockers(attacker, blockers);
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        return dp(Kind.ARRANGE, "orderBlocker", null, null, -1, -1, () -> orderBlockerAi(attacker, blocker, oldBlockers), attacker, blocker, oldBlockers);
    }

    private CardCollection orderBlockerAi(Card attacker, Card blocker, CardCollection oldBlockers) {
    	return AiBlockController.orderBlocker(attacker, blocker, oldBlockers);
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return dp(Kind.ARRANGE, "orderAttackers", null, attackers, -1, -1, () -> orderAttackersAi(blocker, attackers), blocker, attackers);
    }

    private CardCollection orderAttackersAi(Card blocker, CardCollection attackers) {
        return AiBlockController.orderAttackers(blocker, attackers);
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addSuffix) {
        if (knowledgeHook != null && cards != null) {
            knowledgeHook.onReveal(Lists.newArrayList(cards), zone, owner);
        }
        for (Card c : cards) {
            AiCardMemory.rememberCard(player, c, AiCardMemory.MemorySet.REVEALED_CARDS);
        }
        FairNaming.observeRevealed(player, cards);
    }

    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix, boolean addSuffix) {
        if (knowledgeHook != null && cards != null) {
            final List<Card> found = new ArrayList<>();
            for (CardView cv : cards) {
                final Card c = cv == null ? null : player.getGame().findByView(cv);
                if (c != null) {
                    found.add(c);
                }
            }
            Player own = null;
            for (Player p : player.getGame().getPlayers()) {
                if (owner != null && p.getView() == owner) {
                    own = p;
                }
            }
            knowledgeHook.onReveal(found, zone, own);
        }
        for (CardView cv : cards) {
            AiCardMemory.rememberCard(player, player.getGame().findByView(cv), AiCardMemory.MemorySet.REVEALED_CARDS);
        }
        if (FairNaming.mode(player) != AiFixes.Mode.OFF) {
            final List<Card> revealed = new ArrayList<>();
            for (CardView cv : cards) {
                revealed.add(player.getGame().findByView(cv));
            }
            FairNaming.observeRevealed(player, revealed);
        }
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        return dp(Kind.ARRANGE, "arrangeForScry", null, topN, -1, -1, () -> arrangeForScryAi(topN), topN);
    }

    private ImmutablePair<CardCollection, CardCollection> arrangeForScryAi(CardCollection topN) {
        if (knowledgeHook != null && topN != null) {
            knowledgeHook.onLook(Lists.newArrayList(topN), ZoneType.Library);
        }
        CardCollection toBottom = new CardCollection();
        CardCollection toTop = new CardCollection();

        for (Card c: topN) {
            if (ComputerUtil.scryWillMoveCardToBottomOfLibrary(player, c)) {
                toBottom.add(c);
            } else {
                toTop.add(c);
            }
        }

        // put the rest on top in random order
        CardLists.shuffle(toTop);
        return ImmutablePair.of(toTop, toBottom);
    }

    /* (non-Javadoc)
     * @see forge.game.player.PlayerController#arrangeForSurveil(forge.game.card.CardCollection)
     */
    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        return dp(Kind.ARRANGE, "arrangeForSurveil", null, topN, -1, -1, () -> arrangeForSurveilAi(topN), topN);
    }

    private ImmutablePair<CardCollection, CardCollection> arrangeForSurveilAi(CardCollection topN) {
        if (knowledgeHook != null && topN != null) {
            knowledgeHook.onLook(Lists.newArrayList(topN), ZoneType.Library);
        }
        CardCollection toGraveyard = new CardCollection();
        CardCollection toTop = new CardCollection();

        // TODO: Currently this logic uses the same routine as Scry. Possibly differentiate this and implement
        // a specific logic for Surveil (e.g. maybe to interact better with Reanimator strategies etc.).
        if (getPlayer().getCardsIn(ZoneType.Library).size() <= getAi().getIntProperty(AiProps.SURVEIL_NUM_CARDS_IN_LIBRARY_TO_BAIL)) {
            toTop.addAll(topN);
        } else {
            for (Card c : topN) {
                if (ComputerUtil.scryWillMoveCardToBottomOfLibrary(player, c)) {
                    toGraveyard.add(c);
                } else {
                    toTop.add(c);
                }
            }
        }

        CardLists.shuffle(toTop);
        return ImmutablePair.of(toTop, toGraveyard);
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        return dp(Kind.CHOICE, "willPutCardOnTop", null, DP_BOOLS, 1, 1, () -> willPutCardOnTopAi(c), c);
    }

    private boolean willPutCardOnTopAi(Card c) {
        // This is used for Clash. Currently uses Scry logic to determine whether the card should be put on top.
        // Note that the AI does not know what will happen next (another clash or that would become his topdeck)

        return !ComputerUtil.scryWillMoveCardToBottomOfLibrary(player, c);
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        if (cacHook != null && !cacSuppressed) {
            cacHook.beforeAsk(this);
        }
        return dp(Kind.ARRANGE, "orderMoveToZoneList", source, cards, -1, -1,
                () -> orderMoveToZoneListAi(cards, destinationZone, source), cards, destinationZone);
    }

    private CardCollectionView orderMoveToZoneListAi(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        if (knowledgeHook != null && cards != null) {
            knowledgeHook.onLook(Lists.newArrayList(cards), destinationZone);
        }
        //TODO Add more logic for AI ordering here

        if (cards.isEmpty()) {
            return cards;
        }

        if (destinationZone == ZoneType.Graveyard) {
            // In presence of Volrath's Shapeshifter in deck, try to place the best creature on top of the graveyard
            // honest-belief-1020: under fairNaming=on, only cards this player may see
            if (FairNaming.fair(player, getGame().getCardsInGame()).anyMatch(card -> {
                // need a custom predicate here since Volrath's Shapeshifter may have a different name OTB
                return card.getOriginalState(CardStateName.Original).getName().equals("Volrath's Shapeshifter");
            })) {
                int bestValue = 0;
                Card bestCreature = null;
                for (Card c : cards) {
                    int curValue = ComputerUtilCard.evaluateCreature(c);
                    if (c.isCreature() && curValue > bestValue) {
                        bestValue = curValue;
                        bestCreature = c;
                    }
                }

                if (bestCreature != null) {
                    CardCollection reordered = new CardCollection();
                    for (Card c : cards) {
                        if (!c.equals(bestCreature)) {
                            reordered.add(c);
                        }
                    }
                    reordered.add(bestCreature);
                    return reordered;
                }
            }
        } else if (destinationZone == ZoneType.Library) {
            // Ponder and similar cards
            Player p = cards.getFirst().getController(); // whose library are we reordering?
            CardCollection reordered = new CardCollection();

            // Try to use the Scry logic to figure out what should be closer to the top and what should be closer to the bottom
            CardCollection topLands = new CardCollection(), topNonLands = new CardCollection(), bottom = new CardCollection();
            for (Card c : cards) {
                if (ComputerUtil.scryWillMoveCardToBottomOfLibrary(p, c)) {
                    bottom.add(c);
                } else {
                    if (c.isLand()) {
                        topLands.add(c);
                    } else {
                        topNonLands.add(c);
                    }
                }
            }

            int landsOTB = CardLists.count(p.getCardsIn(ZoneType.Battlefield), CardPredicates.LANDS_PRODUCING_MANA);

            if (!p.isOpponentOf(player)) {
                if (landsOTB <= 2) {
                    // too few lands, add all the lands from the "top" category first
                    reordered.addAll(topLands);
                    topLands.clear();
                } else {
                    // we would have scried a land to top, so add one land from the "top" category if it's available there, but not more
                    if (!topLands.isEmpty()) {
                        Card first = topLands.getFirst();
                        reordered.add(first);
                        topLands.remove(first);
                    }
                }
                // add everything that was deemed playable
                reordered.addAll(topNonLands);
                // then all the land extras that may be there
                reordered.addAll(topLands);
                // and then everything else that was deemed unplayable and thus scriable to the bottom
                reordered.addAll(bottom);
            } else {
                // try to screw the opponent up as much as possible by placing the uncastables first
                reordered.addAll(bottom);
                if (landsOTB <= 5) {
                    reordered.addAll(topNonLands);
                    reordered.addAll(topLands);
                } else {
                    reordered.addAll(topLands);
                    reordered.addAll(topNonLands);
                }
            }

            if (orderedMoveToTopOfLibrary(destinationZone, source)) {
                //Cards going to the top of a deck are returned in reverse order.
                Collections.reverse(reordered);
            }

            assert(reordered.size() == cards.size());

            return reordered;
        }

        // Default: return with the same order as was passed into this method
        return cards;
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        return dp(Kind.SELECT, "chooseCardsToDiscardFrom", sa, validCards, min, max,
                () -> chooseCardsToDiscardFromAi(p, sa, validCards, min, max, visibleToChooser), p, validCards, min, max);
    }

    private CardCollection chooseCardsToDiscardFromAi(Player p, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        if (p == player) {
            return brains.getCardsToDiscard(min, max, validCards, sa);
        }

        boolean isTargetFriendly = !p.isOpponentOf(player);

        return isTargetFriendly
               ? ComputerUtil.getCardsToDiscardFromFriend(player, p, sa, validCards, min, max)
               : ComputerUtil.getCardsToDiscardFromOpponent(player, p, sa, validCards, min, max);
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean canSetupTargets) {
        if (canSetupTargets)
            brains.doTrigger(effectSA, true); // first parameter does not matter, since return value won't be used
        ComputerUtil.playNoStack(player, effectSA, getGame(), true);
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        // r3-inline-choices-1030: delve's exiled cards go to the choose-a-card hook (amount = the most it may exile)
        return dp(Kind.SELECT, "chooseCardsToDelve", null, grave, 0, genericAmount,
                () -> getAi().chooseCardsToDelve(genericAmount, grave), genericAmount, grave);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int num, CardCollectionView hand, String[] uTypes, SpellAbility sa) {
        return dp(Kind.SELECT, "chooseCardsToDiscardUnlessType", sa, hand, num, num, () -> chooseCardsToDiscardUnlessTypeAi(num, hand, uTypes, sa), num, hand, uTypes, sa);
    }

    private CardCollectionView chooseCardsToDiscardUnlessTypeAi(int num, CardCollectionView hand, String[] uTypes, SpellAbility sa) {
        Iterable<Card> cardsOfType = IterableUtil.filter(hand, CardPredicates.restriction(uTypes, sa.getActivatingPlayer(), sa.getHostCard(), sa));
        if (!Iterables.isEmpty(cardsOfType)) {
            Card toDiscard = Aggregates.itemWithMin(cardsOfType, Card::getCMC);
            return new CardCollection(toDiscard);
        }
        return getAi().getCardsToDiscard(num, null, sa);
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        return dp(Kind.SELECT, "chooseManaFromPool", null, manaChoices, 1, 1, () -> chooseManaFromPoolAi(manaChoices), manaChoices);
    }

    private Mana chooseManaFromPoolAi(List<Mana> manaChoices) {
        return manaChoices.get(0); // no brains used
    }

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        return dp(Kind.CHOICE, "chooseSomeType", sa, validTypes, isOptional ? 0 : 1, 1, () -> chooseSomeTypeAi(kindOfType, sa, validTypes, isOptional), kindOfType, sa, validTypes, isOptional);
    }

    private String chooseSomeTypeAi(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        String chosen = ComputerUtil.chooseSomeType(player, kindOfType, sa, validTypes);
        if (StringUtils.isBlank(chosen) && !validTypes.isEmpty()) {
            chosen = validTypes.iterator().next();
            System.err.println("AI has no idea how to choose " + kindOfType +", defaulting to arbitrary element: " + chosen);
        }
        return chosen;
    }

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        return dp(Kind.CHOICE, "vote", sa, options, optional ? 0 : 1, 1, () -> voteAi(sa, prompt, options, votes, forPlayer, optional), sa, prompt, options, votes, forPlayer, optional);
    }

    private Object voteAi(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        return ComputerUtil.vote(player, options, sa, votes, forPlayer);
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        return dp(Kind.CHOICE, "chooseSector", null, sectors, 1, 1, () -> chooseSectorAi(assignee, ai, sectors), assignee, ai, sectors);
    }

    private String chooseSectorAi(Card assignee, String ai, List<String> sectors) {
        return Aggregates.random(sectors);
    }

    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) {
        return dp(Kind.CHOICE, "chooseSprocket", null, sprockets, 1, 1, () -> chooseSprocketAi(assignee, sprockets), assignee, sprockets);
    }

    private int chooseSprocketAi(Card assignee, List<Integer> sprockets) {
        int nextSprocket = (player.getCrankCounter() % 3) + 1;
        if(sprockets.contains(nextSprocket))
            return nextSprocket;
        else if(sprockets.contains((nextSprocket % 3) + 1))
            return (nextSprocket % 3) + 1;
        return sprockets.stream().findAny().orElse(0);
    }

    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) {
        return dp(Kind.CHOICE, "choosePDRollToIgnore", null, rolls, 1, 1, () -> choosePDRollToIgnoreAi(rolls), rolls);
    }

    private PlanarDice choosePDRollToIgnoreAi(List<PlanarDice> rolls) {
        //TODO create AI logic for this
        return Aggregates.random(rolls);
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        return dp(Kind.CHOICE, "chooseRollToIgnore", null, rolls, 1, 1, () -> chooseRollToIgnoreAi(rolls), rolls);
    }

    private Integer chooseRollToIgnoreAi(List<Integer> rolls) {
        //TODO create AI logic for this
        return Aggregates.random(rolls);
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        return dp(Kind.SELECT, "chooseDiceToReroll", null, rolls, 0, rolls == null ? -1 : rolls.size(), () -> chooseDiceToRerollAi(rolls), rolls);
    }

    private List<Integer> chooseDiceToRerollAi(List<Integer> rolls) {
        //TODO create AI logic for this
        return new ArrayList<>();
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        return dp(Kind.CHOICE, "chooseRollToModify", null, rolls, 1, 1, () -> chooseRollToModifyAi(rolls), rolls);
    }

    private Integer chooseRollToModifyAi(List<Integer> rolls) {
        //TODO create AI logic for this
        return Aggregates.random(rolls);
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        return dp(Kind.CHOICE, "chooseRollToSwap", null, rolls, 1, 1, () -> chooseRollToSwapAi(rolls), rolls);
    }

    private RollDiceEffect.DieRollResult chooseRollToSwapAi(List<RollDiceEffect.DieRollResult> rolls) {
        //TODO create AI logic for this
        return Aggregates.random(rolls);
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness) {
        return dp(Kind.CHOICE, "chooseRollSwapValue", null, swapChoices, 1, 1, () -> chooseRollSwapValueAi(swapChoices, currentResult, power, toughness), swapChoices, currentResult, power, toughness);
    }

    private String chooseRollSwapValueAi(List<String> swapChoices, Integer currentResult, int power, int toughness) {
        //TODO create AI logic for this
        return Aggregates.random(swapChoices);
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn)  {
        return dp(Kind.CHOICE, "mulliganKeepHand", null, DP_BOOLS, 1, 1, () -> mulliganKeepHandAi(firstPlayer, cardsToReturn), firstPlayer, cardsToReturn);
    }

    private boolean mulliganKeepHandAi(Player firstPlayer, int cardsToReturn)  {
        return !ComputerUtil.wantMulligan(player, cardsToReturn);
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        return dp(Kind.SELECT, "tuckCardsViaMulligan", null, hand, cardsToReturn, cardsToReturn,
                () -> tuckCardsViaMulliganAi(hand, cardsToReturn), hand, cardsToReturn);
    }

    private CardCollectionView tuckCardsViaMulliganAi(CardCollectionView hand, int cardsToReturn) {
        // TODO This is better than it was before, but still suboptimal (but fast).
        // Maybe score a bunch of hands based on projected hand size and return the "duds"
        int numLandsDesired = (player.getStartingHandSize() - cardsToReturn) / 2;

        CardCollection toReturn = new CardCollection();
        for (int i = 0; i < cardsToReturn; i++) {
            hand.removeAll(toReturn);

            CardCollection landsInHand = CardLists.filter(hand, CardPredicates.LANDS);
            int numLandsInHand = landsInHand.size() - CardLists.count(toReturn, CardPredicates.LANDS);

            // If we're flooding with lands, get rid of the worst land we have
            if (numLandsInHand > 0 && numLandsInHand > numLandsDesired) {
                CardCollection producingLands = CardLists.filter(landsInHand, CardPredicates.LANDS_PRODUCING_MANA);
                CardCollection nonProducingLands = CardLists.filter(landsInHand, CardPredicates.LANDS_PRODUCING_MANA.negate());
                Card worstLand = nonProducingLands.isEmpty() ? ComputerUtilCard.getWorstLand(producingLands)
                        : ComputerUtilCard.getWorstLand(nonProducingLands);
                toReturn.add(worstLand);
                continue;
            }

            // See if we'd scry something to the bottom in this situation. If we want to, probably get rid of it.
            CardCollection scryBottom = new CardCollection();
            for (Card c : hand) {
                // Lands are evaluated separately above, factoring in the number of cards to be returned to the library
                if (!c.isLand() && !toReturn.contains(c) && !willPutCardOnTop(c)) {
                    scryBottom.add(c);
                }
            }
            if (!scryBottom.isEmpty()) {
                CardLists.sortByCmcDesc(scryBottom);
                toReturn.add(scryBottom.getFirst()); // assume the max CMC one is worse since we're not guaranteed to have lands for it
                continue;
            }

            // If we don't want to scry anything to the bottom, remove the worst card that we have in order to satisfy the requirement
            toReturn.add(ComputerUtilCard.getWorstAI(hand));
        }

        return CardCollection.getView(toReturn);
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        dpVoid(Kind.ARRANGE, "declareAttackers", null, () -> declareAttackersAi(attacker, combat), attacker, combat);
    }

    private void declareAttackersAi(Player attacker, Combat combat) {
        brains.declareAttackers(attacker, combat);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        dpVoid(Kind.ARRANGE, "declareBlockers", null, () -> declareBlockersAi(defender, combat), defender, combat);
    }

    private void declareBlockersAi(Player defender, Combat combat) {
        brains.declareBlockersFor(defender, combat);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        return dp(Kind.PRIORITY, "chooseSpellAbilityToPlay", null, null, -1, -1, this::chooseSpellAbilityToPlayAi);
    }

    private List<SpellAbility> chooseSpellAbilityToPlayAi() {
        FairNaming.observe(player);
        return brains.chooseSpellAbilityToPlay();
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        if (sa.isLandAbility()) {
            if (sa.canPlay()) {
                sa.resolve();
            }
        } else {
            ComputerUtil.handlePlayingSpellAbility(player, sa, getDeferredTargetingPlayerAction(sa));
        }
        return true;
    }

    /**
     * If any ability in the SA chain has a TargetingPlayer,
     * defers the human choice from canPlayAI (worker thread with possibly low timeout)
     * to handlePlayingSpellAbility (game thread, no timeout).
     */
    Consumer<SpellAbility> getDeferredTargetingPlayerAction(SpellAbility sa) { // package: AiTapeController
        while (sa != null) {
            if (sa.hasParam("TargetingPlayer") && sa.getTargetingPlayer() != null) {
                return root -> {
                    SpellAbility cur = root;
                    while (cur != null) {
                        if (cur.hasParam("TargetingPlayer") && cur.getTargetingPlayer() != null) {
                            cur.clearTargets();
                            cur.getTargetingPlayer().getController().chooseTargetsFor(cur);
                            // there's a chance a target gets selected that makes the cost unaffordable
                            if (!ComputerUtilCost.canPayCost(root, root.getActivatingPlayer(), false)) {
                                cur.resetTargets();
                                root.setSkip(true);
                                return;
                            }
                        }
                        cur = cur.getSubAbility();
                    }
                };
            }
            sa = sa.getSubAbility();
        }
        return null;
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        return dp(Kind.SELECT, "chooseCardsToDiscardToMaximumHandSize", null, null, numDiscard, numDiscard, () -> chooseCardsToDiscardToMaximumHandSizeAi(numDiscard), numDiscard);
    }

    private CardCollectionView chooseCardsToDiscardToMaximumHandSizeAi(int numDiscard) {
        return brains.getCardsToDiscard(numDiscard, null, null);
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        return dp(Kind.SELECT, "chooseCardsToRevealFromHand", null, valid, min, max, () -> chooseCardsToRevealFromHandAi(min, max, valid), min, max, valid);
    }

    private CardCollectionView chooseCardsToRevealFromHandAi(int min, int max, CardCollectionView valid) {
        int numCardsToReveal = Math.min(max, valid.size());
        return numCardsToReveal == 0 ? CardCollection.EMPTY : (CardCollection)valid.subList(0, numCardsToReveal);
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        return dp(Kind.CHOICE, "chooseStartingPlayer", null, null, 1, 1, () -> chooseStartingPlayerAi(isFirstGame), isFirstGame);
    }

    private Player chooseStartingPlayerAi(boolean isFirstGame) {
        return this.player; // AI is brave :)
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        return dp(Kind.CHOICE, "chooseStartingHand", null, zones, 1, 1, () -> chooseStartingHandAi(zones), zones);
    }

    private PlayerZone chooseStartingHandAi(List<PlayerZone> zones) {
        // Rate all the hands using the AI's hand evaluation function
        int bestScore = Integer.MIN_VALUE;
        PlayerZone bestZone = null;
        for (PlayerZone zone : zones) {
            int score = ComputerUtil.scoreHand(zone.getCards(), this.player, 0);
            if (score > bestScore) {
                bestScore = score;
                bestZone = zone;
            }
        }

        return bestZone;
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        return dp(Kind.SELECT, "chooseSaToActivateFromOpeningHand", null, usableFromOpeningHand, 0, usableFromOpeningHand == null ? -1 : usableFromOpeningHand.size(), () -> chooseSaToActivateFromOpeningHandAi(usableFromOpeningHand), usableFromOpeningHand);
    }

    private List<SpellAbility> chooseSaToActivateFromOpeningHandAi(List<SpellAbility> usableFromOpeningHand) {
        return brains.chooseSaToActivateFromOpeningHand(usableFromOpeningHand);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return dp(Kind.NUMBER, "chooseNumber", sa, null, min, max, () -> chooseNumberAi(sa, title, min, max), sa, title, min, max);
    }

    private int chooseNumberAi(SpellAbility sa, String title, int min, int max) {
        return brains.chooseNumber(sa, title, min, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String string, int min, int max, Map<String, Object> params) {
        return dp(Kind.NUMBER, "chooseNumber", sa, null, min, max, () -> chooseNumberAi(sa, string, min, max, params), sa, string, min, max, params);
    }

    private int chooseNumberAi(SpellAbility sa, String string, int min, int max, Map<String, Object> params) {
        return SpellApiToAi.Converter.get(sa).chooseNumber(player, sa, min, max, params);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> options, Player relatedPlayer) {
        return dp(Kind.NUMBER, "chooseNumber", sa, options, -1, -1, () -> chooseNumberAi(sa, title, options, relatedPlayer), sa, title, options, relatedPlayer);
    }

    private int chooseNumberAi(SpellAbility sa, String title, List<Integer> options, Player relatedPlayer) {
        return brains.chooseNumber(sa, title, options, relatedPlayer);
    }

    /* (non-Javadoc)
     * @see forge.game.player.PlayerController#chooseFlipResult(forge.Card, forge.game.player.Player, java.lang.String[], boolean)
     */
    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        return dp(Kind.CHOICE, "chooseFlipResult", sa, DP_BOOLS, 1, 1, () -> chooseFlipResultAi(sa, flipper, call), sa, flipper, call);
    }

    private boolean chooseFlipResultAi(SpellAbility sa, Player flipper, boolean call) {
        if (call) {
            // Win if possible
            return true;
        }
        // heads or tails, AI doesn't know which is better now
        return MyRandom.getRandom().nextBoolean();
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility saSrc, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        return dp(Kind.SELECT, "chooseTarget", saSrc, allTargets, 1, 1, () -> chooseTargetAi(saSrc, allTargets), saSrc, allTargets);
    }

    private Pair<SpellAbilityStackInstance, GameObject> chooseTargetAi(SpellAbility saSrc, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        // TODO Teach AI how to determine the most damaging subability when retargeting a spell
        // with multiple targets (Arc Lightning, Cone of Flame, etc.) with Spellskite
        // (currently simply always returns the first valid target ability)
        return allTargets.get(0);
    }

    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject realtedTarget, String value) {
        // AI should take into consideration creature types, numbers and other information (mostly choices) arriving through this channel
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultVal) {
        return dp(Kind.CHOICE, "chooseBinary", sa, DP_BOOLS, 1, 1, () -> chooseBinaryAi(sa, question, kindOfChoice, defaultVal), sa, question, kindOfChoice, defaultVal);
    }

    private boolean chooseBinaryAi(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultVal) {
        switch (kindOfChoice) {
            case TapOrUntap: return true;
            case UntapOrLeaveTapped:
                Card source = sa.getHostCard();
                if (source != null && source.hasSVar("AIUntapPreference")) {
                    switch (source.getSVar("AIUntapPreference")) {
                        case "Always":
                            return true;
                        case "Never":
                            return false;
                        case "StorageLand":
                            return shouldUntapStorageLand(source);
                        case "NothingRemembered":
                            if (!source.hasRemembered()) {
                                return true;
                            } else {
                                Card rem = (Card) source.getFirstRemembered();
                                if (!rem.isInPlay()) {
                                    return true;
                                }
                            }
                            break;
                        case "BetterTgtThanRemembered":
                            if (source.hasGainControlTarget()) {
                                Card rem = source.getGainControlTargets().get(0);
                                //  avoid pumping opponent creature
                                if (!rem.isInPlay() || rem.getController().isOpponentOf(source.getController())) {
                                    return true;
                                }
                                for (Card c : source.getController().getCreaturesInPlay()) {
                                    if (c != rem && ComputerUtilCard.evaluateCreature(c) > ComputerUtilCard.evaluateCreature(rem) + 30) {
                                        return true;
                                    }
                                }
                                return false;
                            }
                            break;
                        default:
                            break;
                    }
                }
                return defaultVal != null && defaultVal;
            case LeftOrRight: return brains.chooseDirection(sa);
            case OddsOrEvens: return brains.chooseEvenOdd(sa);
            case HeadsOrTails:
                // this is the result if AI gets to choose after
                return true;
            default:
                return MyRandom.getRandom().nextBoolean();
        }
    }

    private static boolean shouldUntapStorageLand(Card source) {
        final int otherManaSources = CardLists.count(source.getController().getLandsInPlay(),
                land -> land != source && CardPredicates.LANDS_PRODUCING_MANA.test(land));
        final int availableMana = otherManaSources + source.getCounters(CounterType.getType("STORAGE"));
        return source.getController().getCardsIn(ZoneType.Hand).anyMatch(
                card -> card.getCMC() > otherManaSources && card.getCMC() <= availableMana);
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * forge.game.player.PlayerController#chooseBinary(forge.game.spellability.
     * SpellAbility, java.lang.String,
     * forge.game.player.PlayerController.BinaryChoiceType, java.util.Map)
     */
    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Map<String, Object> params) {
        return dp(Kind.CHOICE, "chooseBinary", sa, DP_BOOLS, 1, 1, () -> chooseBinaryAi(sa, question, kindOfChoice, params), sa, question, kindOfChoice, params);
    }

    private boolean chooseBinaryAi(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Map<String, Object> params) {
        return SpellApiToAi.Converter.get(sa).chooseBinary(kindOfChoice, sa, params);
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        return dp(Kind.SELECT, "chooseModeForAbility", sa, possible, min, num, () -> chooseModeForAbilityAi(sa, possible, min, num, allowRepeat), sa, possible, min, num, allowRepeat);
    }

    private List<AbilitySub> chooseModeForAbilityAi(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        List<AbilitySub> result = brains.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        if (result != null) {
            return result;
        }
        /**
         * Called when CharmEffect resolves for the AI to select its choices.
         * The list of chosen options (sa.getChosenList()) should be set by
         * CharmAi.canPlayAi() for cast spells while CharmAi.doTrigger() deals
         * with triggers. The logic in CharmAi should only be called once to
         * account for probabilistic choices that may result in different
         * results in subsequent calls.
         */
        if (sa.getChosenList() == null) {
            getAi().doTrigger(sa, true);
        }
        return sa.getChosenList();
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card card, ColorSet colors) {
        return dp(Kind.CHOICE, "chooseColorAllowColorless", null, null, 1, 1, () -> chooseColorAllowColorlessAi(message, card, colors), message, card, colors);
    }

    private byte chooseColorAllowColorlessAi(String message, Card card, ColorSet colors) {
        final String c = ComputerUtilCard.getMostProminentColor(player.getCardsIn(ZoneType.Hand));
        byte chosenColorMask = MagicColor.fromName(c);
        if ((colors.getColor() & chosenColorMask) != 0) {
            return chosenColorMask;
        }
        return Iterables.getFirst(colors, MagicColor.Color.COLORLESS).getColorMask();
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        // r3-inline-choices-1030: the choose-a-card hook may pick another of the offered colours (no hook: Forge AI's)
        return dp(Kind.CHOICE, "chooseColor", sa, null, 1, 1, () -> chooseColorAi(message, sa, colors), colors);
    }

    private byte chooseColorAi(String message, SpellAbility sa, ColorSet colors) {
        if (colors.countColors() < 2) {
            return Iterables.getFirst(colors, MagicColor.Color.WHITE).getColorMask();
        }
        // You may switch on sa.getApi() here and use sa.getParam("AILogic")
        CardCollectionView hand = player.getCardsIn(ZoneType.Hand);
        if (sa.getApi() == ApiType.Mana) {
            hand = CardCollection.combine(hand, player.getCardsIn(ZoneType.Stack));
        }
        final String c = ComputerUtilCard.getMostProminentColor(hand);
        byte chosenColorMask = MagicColor.fromName(c);

        if ((colors.getColor() & chosenColorMask) != 0) {
            return chosenColorMask;
        }
        return Iterables.getFirst(colors, MagicColor.Color.WHITE).getColorMask();
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        return dp(Kind.CHOICE, "chooseColors", sa, null, min, max, () -> chooseColorsAi(message, sa, min, max, options), message, sa, min, max, options);
    }

    private ColorSet chooseColorsAi(String message, SpellAbility sa, int min, int max, ColorSet options) {
        return ColorSet.fromNames(ComputerUtilCard.chooseColor(sa, min, max, options.stream().map(MagicColor.Color::getName).collect(Collectors.toList())));
    }

    /*
     * (non-Javadoc)
     *
     * @see forge.game.player.PlayerController#chooseCounterType(java.util.List,
     * forge.game.spellability.SpellAbility, java.lang.String, java.util.Map)
     */
    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt,
            Map<String, Object> params) {
        return dp(Kind.CHOICE, "chooseCounterType", sa, options, 1, 1, () -> chooseCounterTypeAi(options, sa, prompt, params), options, sa, prompt, params);
    }

    private CounterType chooseCounterTypeAi(List<CounterType> options, SpellAbility sa, String prompt,
            Map<String, Object> params) {
        // short cut if there is no options to choose
        if (options.size() <= 1) {
            return Iterables.getFirst(options, null);
        }
        return SpellApiToAi.Converter.get(sa).chooseCounterType(options, sa, params);
    }

    @Override
    public String chooseKeywordForPump(final List<String> options, final SpellAbility sa, final String prompt, final Card tgtCard) {
        return dp(Kind.CHOICE, "chooseKeywordForPump", sa, options, 1, 1, () -> chooseKeywordForPumpAi(options, sa, prompt, tgtCard), options, sa, prompt, tgtCard);
    }

    private String chooseKeywordForPumpAi(final List<String> options, final SpellAbility sa, final String prompt, final Card tgtCard) {
        if (options.size() <= 1) {
            return Iterables.getFirst(options, null);
        }
        List<String> possible = Lists.newArrayList();
        CardCollection oppUntappedCreatures = CardLists.filter(player.getOpponents().getCreaturesInPlay(), CardPredicates.UNTAPPED);
        if (tgtCard != null) {
            for (String kw : options) {
                if (tgtCard.hasKeyword(kw)) {
                    continue;
                } else if ("Indestructible".equals(kw)) {
                    if (oppUntappedCreatures.isEmpty()) {
                        continue; // no threats on battlefield - removal still a concern perhaps?
                    } else {
                        possible.clear();
                        possible.add(kw); // prefer Indestructible above all else
                        break;
                    }
                } else if ("Flying".equals(kw)) {
                    if (oppUntappedCreatures.isEmpty()) {
                        continue; // no need for evasion
                    } else {
                        boolean flyingGood = true;
                        for (Card c : oppUntappedCreatures) {
                            if (c.hasKeyword(Keyword.FLYING) || c.hasKeyword(Keyword.REACH)) {
                                flyingGood = false;
                                break;
                            }
                        }
                        if (flyingGood) {
                            possible.clear();
                            possible.add(kw); // flying is great when no one else has it
                            break;
                        } // even if opp has flying or reach, flying might still be useful so we won't skip it
                    }
                } else if (kw.startsWith("Protection from ")) {
                    //currently, keyword choice lists only include color protection
                    final String fromWhat = kw.substring(16);
                    boolean found = false;
                    for (String color : MagicColor.Constant.ONLY_COLORS) {
                        if (color.equalsIgnoreCase(fromWhat)) {
                            CardCollection known = player.getOpponents().getCardsIn(ZoneType.Battlefield);
                            for (final Card c : known) {
                                if (c.associatedWithColor(color)) {
                                    found = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!found) {
                        continue;
                    }
                }
                possible.add(kw);
            }
        }
        if (!possible.isEmpty()) {
            return Aggregates.random(possible);
        }

        return Aggregates.random(options); // if worst comes to worst, at least do something
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        return dp(Kind.CHOICE, "chooseSingleReplacementEffect", null, possibleReplacers, 1, 1, () -> chooseSingleReplacementEffectAi(possibleReplacers), possibleReplacers);
    }

    private ReplacementEffect chooseSingleReplacementEffectAi(List<ReplacementEffect> possibleReplacers) {
        return brains.chooseSingleReplacementEffect(possibleReplacers);
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleStatics) {
        return dp(Kind.CHOICE, "chooseSingleStaticAbility", null, possibleStatics, 1, 1, () -> chooseSingleStaticAbilityAi(possibleStatics), possibleStatics);
    }

    private StaticAbility chooseSingleStaticAbilityAi(List<StaticAbility> possibleStatics) {
        // only matters in corner cases
        return Iterables.getFirst(possibleStatics, null);
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        return dp(Kind.CHOICE, "chooseProtectionType", sa, choices, 1, 1, () -> chooseProtectionTypeAi(sa, choices), sa, choices);
    }

    private String chooseProtectionTypeAi(SpellAbility sa, List<String> choices) {
        String choice = choices.get(0);
        SpellAbility hostsa = null;     //for Protect sub-ability
        if (getGame().stack.size() > 1) {
            for (SpellAbilityStackInstance si : getGame().getStack()) {
                SpellAbility spell = si.getSpellAbility();
                if (sa != spell && sa.getHostCard() != spell.getHostCard()) {
                    String s = ProtectAi.toProtectFrom(spell.getHostCard(), sa);
                    if (s != null) {
                        return s;
                    }
                    break;
                }
            }
        }
        final Combat combat = getGame().getCombat();
        if (combat != null) {
            if (getGame().stack.size() == 1) {
                SpellAbility topstack = getGame().stack.peekAbility();
                if (topstack.getSubAbility() == sa) {
                    hostsa = topstack;
                }
            }
            Card toSave = hostsa == null ? sa.getTargetCard() : hostsa.getTargetCard();
            CardCollection threats = null;
            if (toSave != null) {
                if (combat.isBlocked(toSave)) {
                    threats = combat.getBlockers(toSave);
                }
                if (combat.isBlocking(toSave)) {
                    threats = combat.getAttackersBlockedBy(toSave);
                }
            }
            if (threats != null && !threats.isEmpty()) {
                ComputerUtilCard.sortByEvaluateCreature(threats);
                String s = ProtectAi.toProtectFrom(threats.get(0), sa);
                if (s != null) {
                    return s;
                }
            }
        }
        final PhaseHandler ph = getGame().getPhaseHandler();
        if (ph.getPlayerTurn() == sa.getActivatingPlayer() && ph.getPhase() == PhaseType.MAIN1 && sa.getTargetCard() != null) {
            AiAttackController aiAtk = new AiAttackController(sa.getActivatingPlayer(), sa.getTargetCard());
            String s = aiAtk.toProtectAttacker(sa);
            if (s != null) {
                return s;
            }
        }
        final String logic = sa.getParam("AILogic");
        if (logic == null || logic.equals("MostProminentHumanCreatures")) {
            CardCollection list = player.getOpponents().getCreaturesInPlay();
            if (list.isEmpty()) {
                // honest-belief-1020: under fairNaming=on, only the opponents' cards this player has seen
                list = FairNaming.fair(player, CardLists.filterControlledBy(getGame().getCardsInGame(), player.getOpponents()));
            }
            if (!list.isEmpty()) {
                choice = ComputerUtilCard.getMostProminentColor(list);
            }
        }
        return choice;
    }

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt /* ai needs hints as well */, ManaConversionMatrix matrix, boolean effect) {
        return ComputerUtilMana.payManaCost(new Cost(toPay, effect), player, sa, effect);
    }

    @Override
    public boolean payCombatCost(Card c, Cost cost, SpellAbility sa, String prompt) {
        return dp(Kind.CHOICE, "payCombatCost", sa, DP_BOOLS, 1, 1, () -> payCombatCostAi(c, cost, sa, prompt), c, cost, sa, prompt);
    }

    private boolean payCombatCostAi(Card c, Cost cost, SpellAbility sa, String prompt) {
        if (ComputerUtil.playNoStack(c.getController(), sa, getGame(), true)) {
            return true;
        }
        return false;
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        return dp(Kind.SELECT, "chooseCardsForCost", sa, optionList, amount, amount, () -> chooseCardsForCostAi(optionList, sa, cpl, amount, isOptional, prompt), optionList, sa, cpl, amount, isOptional, prompt);
    }

    private CardCollectionView chooseCardsForCostAi(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        assert(false);
        //Untested placeholder. The AI does not currently pay like this.
        return cpl.accept(new AiCostDecision(player, sa, true)).cards;
    }

    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt, ManaConversionMatrix matrix, boolean effect) {
        assert(false);
        //Untested placeholder. The AI does not currently pay like this.
        return ComputerUtilMana.payManaCost(toPay, ability, player, effect);
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability, boolean effect, String prompt) {
        return new AiCostDecision(player, ability, effect);
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        boolean will = SpellApiToAi.Converter.get(sa).willPayUnlessCost(player, sa, cost, alreadyPaid, allPayers);
        if (cacHook == null || cacSuppressed) {
            final boolean forgeWill = will;
            will = dp(Kind.CHOICE, "payCostToPreventEffect", sa, DP_BOOLS, 1, 1, () -> forgeWill, cost, null);
            if (!will) {
                return false;
            }
        } else {
            // r3-inline-choices-1030: pay or not goes to the choose-a-card hook (no hook: exactly the branch above)
            final boolean canPay = ComputerUtilCost.canPayCost(cost, sa, player, true);
            final boolean forgeWill = will && canPay;
            will = dp(Kind.CHOICE, "payCostToPreventEffect", sa, DP_BOOLS, 1, 1, () -> forgeWill, cost, canPay);
            if (!will || !canPay) {
                return false;
            }
            final CostPayment pay = new CostPayment(cost, sa);
            return pay.payComputerCosts(AiCostDecision.forPayment(player, sa, true));
        }
        if (!ComputerUtilCost.canPayCost(cost, sa, player, true)) {
            return false;
        }
        final CostPayment pay = new CostPayment(cost, sa);
        return pay.payComputerCosts(AiCostDecision.forPayment(player, sa, true));
    }

    public boolean payCostDuringRoll(final Cost cost, final SpellAbility sa) {
        return dp(Kind.CHOICE, "payCostDuringRoll", sa, DP_BOOLS, 1, 1, () -> payCostDuringRollAi(cost, sa), cost, sa);
    }

    private boolean payCostDuringRollAi(final Cost cost, final SpellAbility sa) {
        // TODO logic for AI to pay rerolls and modification costs
        return false;
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        // r3-inline-choices-1030: Forge's orderPlaySa REMOVES entries from the list it is given (filterList), so the
        // hook gets a copy taken before Forge orders (Forge itself still gets the original list, as before)
        final List<SpellAbility> asked = cacHook == null || cacSuppressed || activePlayerSAs == null ? activePlayerSAs
                : new ArrayList<>(activePlayerSAs);
        // lane replay-record-1021: the AI tape records the order (a forced replay plays the record's)
        final AiTape tape = AiTape.covering(player);
        if (tape != null && activePlayerSAs != null && activePlayerSAs.size() > 1) { // one ability: no order to choose
            // the tape records the order actually played (the hook's, when it departs); a forced replay returns the
            // record without asking either
            return tape.order(activePlayerSAs, () -> dpOrder(asked, activePlayerSAs));
        }
        return dpOrder(asked, activePlayerSAs);
    }

    /** decisionpoint-1102: the order Forge AI plays simultaneous abilities in, as a decision point (CacAdapter: cacOrder). */
    private List<SpellAbility> dpOrder(final List<SpellAbility> asked, final List<SpellAbility> offered) {
        // Forge's orderPlaySa removes entries from the list it orders: the point keeps a copy of what was offered
        final List<SpellAbility> opts = asked != null && asked == offered && DecisionPoints.observer() != null
                ? new ArrayList<>(asked) : asked;
        return dp(Kind.ARRANGE, "orderSimultaneousSa", null, opts, -1, -1, () -> getAi().orderPlaySa(offered), asked);
    }

    /** r3-inline-choices-1030: 2+ simultaneous abilities' order goes to the choose-a-card hook (no hook: {@code def}). */
    List<SpellAbility> cacOrder(final List<SpellAbility> sas, final List<SpellAbility> def) { // package: CacAdapter
        if (cacHook == null || cacSuppressed || sas == null || sas.size() < 2 || def == null || def.size() != sas.size()) {
            return def;
        }
        final List<SpellAbility> out = cacHook.orderTriggers(this, sas, def);
        return out != null && out.size() == def.size() && out.containsAll(def) ? out : def;
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        final boolean lone = activePlayerSAs != null && activePlayerSAs.size() == 1;
        for (final SpellAbility sa : orderSimultaneousSa(activePlayerSAs)) {
            if (sa.isTrigger() && !sa.isCopied()) {
                if (prepareSingleSa(sa.getHostCard(), sa, true)) {
                    // r3-target-choices-1030: a lone trigger's targets, as Forge AI set them, go to the choose-a-card
                    // hook, which may replace them in place (no hook: unchanged)
                    if (lone && cacHook != null && !cacSuppressed && sa.usesTargeting()) {
                        cacHook.triggerTargets(this, sa);
                    }
                    ComputerUtil.playStack(sa, player, getGame());
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

                    if (sa.isMayChooseNewTargets()) {
                        chooseNewTargetsForCopy(sa);
                        // FIXME: the new implementation (below) requires implementing setupNewTargets in the AI controller, among other possible changes, otherwise breaks AI
                        // sa.setupNewTargets(player);
                    }
                    AiTape.checkpoint(player, sa, "c"); // lane replay-record-1021
                }
                // need finally add the new spell to the stack
                getGame().getStack().add(sa);
            }
        }
    }

    void chooseNewTargetsForCopy(final SpellAbility copy) { // package: AiTapeController
        List<TargetChoices> oldTargets = new ArrayList<>();
        boolean targetingPlayer = false;
        for (SpellAbility s = copy; s != null; s = s.getSubAbility()) {
            oldTargets.add(s.getTargets());
            targetingPlayer |= s.hasParam("TargetingPlayer");
        }

        boolean chosen;
        if (targetingPlayer) {
            // another player picks the targets, so leave that to setupTargets
            chosen = copy.setupTargets();
        } else {
            for (SpellAbility s = copy; s != null; s = s.getSubAbility()) {
                s.clearTargets();
            }
            // choosing new targets is optional for a copy of our own spell
            Card original = copy.getHostCard().getCopiedPermanent();
            boolean ownSpell = (original != null ? original : copy.getHostCard()).getController().equals(player);
            chosen = brains.doTrigger(copy, !ownSpell);
            for (SpellAbility s = copy; chosen && s != null; s = s.getSubAbility()) {
                chosen = !s.usesTargeting() || s.isTargetNumberValid();
            }
        }

        if (!chosen) {
            // if AI can't choose targets need to keep old one even if illegal
            int i = 0;
            for (SpellAbility s = copy; s != null; s = s.getSubAbility()) {
                s.setTargets(oldTargets.get(i++));
            }
        }
    }

    boolean prepareSingleSa(final Card host, final SpellAbility sa, final boolean isMandatory) { // package: AiTapeController
        // lane replay-record-1021: whether the seat plays the trigger, and what it decided preparing it, are taped
        return AiTape.prepare(player, () -> prepareSingleSaUntaped(host, sa, isMandatory));
    }

    private boolean prepareSingleSaUntaped(final Card host, SpellAbility sa, boolean isMandatory) {
        if (sa.getApi() == ApiType.Charm) {
            if (!CharmEffect.makeChoices(sa)) {
                return false;
            }
            if (!sa.hasParam("Random")) {
                return true;
            }
            sa = sa.getSubAbility();
        }
        if (sa.hasParam("TargetingPlayer")) {
            Player targetingPlayer = AbilityUtils.getDefinedPlayers(host, sa.getParam("TargetingPlayer"), sa).get(0);
            sa.setTargetingPlayer(targetingPlayer);
            return targetingPlayer.getController().chooseTargetsFor(sa);
        }
        return brains.doTrigger(sa, isMandatory);
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        if (prepareSingleSa(host, wrapperAbility, isMandatory)) {
            return ComputerUtil.playNoStack(wrapperAbility.getActivatingPlayer(), wrapperAbility, getGame(), true);
        }
        return false;
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        boolean optional = !tgtSA.getPayCosts().isMandatory();
        boolean noManaCost = tgtSA.hasParam("WithoutManaCost");
        if (tgtSA instanceof Spell spell) { // Isn't it ALWAYS a spell?
            // TODO if mandatory AI is only forced to use mana when it's already in the pool
            if (brains.canPlayFromEffectAI(spell, !optional, noManaCost) == AiPlayDecision.WillPlay || !optional) {
                return ComputerUtil.playStack(tgtSA, player, getGame());
            }
            return false; // didn't play spell
        }
        return true;
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        return dp(Kind.SELECT, "chooseTargetsFor", currentAbility, null, -1, -1, () -> chooseTargetsForAi(currentAbility), currentAbility);
    }

    private boolean chooseTargetsForAi(SpellAbility currentAbility) {
        return brains.doTrigger(currentAbility, true);
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        return dp(Kind.SELECT, "chooseNewTargetsFor", ability, null, -1, -1, () -> chooseNewTargetsForAi(ability, filter, optional), ability, filter, optional);
    }

    private TargetChoices chooseNewTargetsForAi(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        // AI currently can't do this. But when it can it will need to be based on Ability API
        return null;
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        return dp(Kind.CHOICE, "chooseCardsPile", sa, DP_BOOLS, 1, 1, () -> chooseCardsPileAi(sa, pile1, pile2, faceUp), sa, pile1, pile2, faceUp);
    }

    private boolean chooseCardsPileAi(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        if (faceUp.equals("True")) {
            // AI will choose the first pile if it is larger or the same
            // TODO Improve this to be slightly more random to not be so predictable
            return pile1.size() >= pile2.size();
        } else if (faceUp.equals("One")) {
            // Probably want to see if the face up pile has anything "worth it", then potentially take face down pile
            return pile1.size() >= pile2.size();
        } else {
            boolean allCreatures = IterableUtil.all(Iterables.concat(pile1, pile2), CardPredicates.CREATURES);
            int cmc1 = allCreatures ? ComputerUtilCard.evaluateCreatureList(pile1) : ComputerUtilCard.evaluatePermanentList(pile1);
            int cmc2 = allCreatures ? ComputerUtilCard.evaluateCreatureList(pile2) : ComputerUtilCard.evaluatePermanentList(pile2);

            // for now, this assumes that the outcome will be bad
            // TODO: This should really have a ChooseLogic param to
            // figure this out
            return "Worst".equals(sa.getParam("AILogic")) ^ (cmc1 >= cmc2);
        }
    }

    @Override
    public void revealAnte(String message, Multimap<Player, PaperCard> removedAnteCards) {
        // Ai won't understand that anyway
    }

    @Override
    public void revealAISkipCards(String message, Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) {
        // Ai won't understand that anyway
    }

    @Override
    public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) {
        // Ai won't understand that anyway
    }

    @Override
    public Map<DeckSection, List<? extends PaperCard>> complainCardsCantPlayWell(Deck myDeck) {
        // TODO check if profile detection set to Auto
        setupAutoProfile(myDeck);

        return brains.complainCardsCantPlayWell(myDeck);
    }

    @Override
    public CardCollectionView cheatShuffle(CardCollectionView list) {
        return dp(Kind.ARRANGE, "cheatShuffle", null, list, -1, -1, () -> cheatShuffleAi(list), list);
    }

    private CardCollectionView cheatShuffleAi(CardCollectionView list) {
        return brains.cheatShuffle(list);
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        // TODO AI takes all by default
        return losses;
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        // r3-inline-choices-1030: convoke / improvise tappers go to the choose-a-card hook (no hook: Forge AI's)
        return dp(Kind.SELECT, "chooseCardsForConvokeOrImprovise", sa, untappedCards, -1, -1,
                () -> chooseCardsForConvokeOrImproviseAi(sa, manaCost, untappedCards, artifacts, creatures, maxReduction),
                manaCost, untappedCards, artifacts, creatures);
    }

    private Map<Card, ManaCostShard> chooseCardsForConvokeOrImproviseAi(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        final Player ai = sa.getActivatingPlayer();
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        // Filter out mana sources that will interfere with payManaCost()
        CardCollection untapped = CardLists.filter(untappedCards, c -> c.getManaAbilities().isEmpty());

        // Filter out creatures if AI hasn't attacked yet
        if (ph.isPlayerTurn(ai) && ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
            if (!creatures) {
                untapped = CardLists.filter(untapped, c -> !c.isCreature());
            } else {
                // TODO AI needs to learn how to use Convoke or Waterbend
                return new HashMap<>();
            }
        }

        if ((ph.isPlayerTurn(ai) && ph.getPhase().isAfter(PhaseType.COMBAT_BEGIN)) ||
                (!ph.isPlayerTurn(ai) && ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_BLOCKERS))) {
            // Do not convoke potential blockers until after opponent's attack
            final CardCollectionView blockers = ComputerUtil.protectRecursion(sa, () -> ComputerUtilCard.getLikelyBlockers(ai, null), CardCollection.EMPTY);
            untapped.removeAll(blockers);
            // Add threatened creatures
            if (!ai.getGame().getStack().isEmpty() && !blockers.isEmpty()) {
                final List<GameObject> objects = ComputerUtil.predictThreatenedObjects(ai, null);
                for (Card c : blockers) {
                    if (objects.contains(c) && (creatures || c.isArtifact())) {
                        untapped.add(c);
                    }
                    if (maxReduction != null && untapped.size() >= maxReduction) {
                        break;
                    }
                }
            }
        }
        return ComputerUtilMana.getConvokeOrImproviseFromList(manaCost, untapped, artifacts, creatures);
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        return dp(Kind.CHOICE, "chooseCardName", sa, faces, 1, 1, () -> chooseCardNameAi(sa, faces, message), sa, faces, message);
    }

    private String chooseCardNameAi(SpellAbility sa, List<ICardFace> faces, String message) {
        return SpellApiToAi.Converter.get(sa).chooseCardName(player, sa, faces);
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        return dp(Kind.CHOICE, "chooseCardName", sa, null, 1, 1, () -> chooseCardNameAi(sa, cpp, valid, message), sa, cpp, valid, message);
    }

    private String chooseCardNameAi(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        if (sa.hasParam("AILogic")) {
            CardCollectionView aiLibrary = player.getCardsIn(ZoneType.Library);
            CardCollectionView oppLibrary = player.getStrongestOpponent().getCardsIn(ZoneType.Library);
            final Card source = sa.getHostCard();
            final String logic = sa.getParam("AILogic");

            // Filter for valid options only
            if (!valid.isEmpty()) {
                aiLibrary = CardLists.getValidCards(aiLibrary, valid, source.getController(), source, sa);
                oppLibrary = CardLists.getValidCards(oppLibrary, valid, source.getController(), source, sa);
            }

            if (source != null && source.getState(CardStateName.Original).hasKeyword(Keyword.HIDDEN_AGENDA)) {
                // If any Conspiracies are present, try not to choose the same name twice
                // (otherwise the AI will spam the same name)
                for (Card consp : player.getCardsIn(ZoneType.Command)) {
                    if (consp.getState(CardStateName.Original).hasKeyword(Keyword.HIDDEN_AGENDA)) {
                        String chosenName = consp.getNamedCard();
                        if (!chosenName.isEmpty()) {
                            aiLibrary = CardLists.filter(aiLibrary, CardPredicates.nameNotEquals(chosenName));
                        }
                    }
                }
            }

            String name = "";
            if (logic.equals("MostProminentInComputerDeck")) {
                name = ComputerUtilCard.getMostProminentCardName(aiLibrary);
            } else if (logic.equals("MostProminentInHumanDeck")) {
                // fairNaming (lane ai-misplays-1006): ON counts the opponent's cards this player has seen, not its library.
                final CardCollectionView upstreamLibrary = oppLibrary;
                name = FairNaming.decide(player, source == null ? logic : source.getName(),
                        () -> ComputerUtilCard.getMostProminentCardName(upstreamLibrary),
                        () -> {
                            final Player opp = player.getStrongestOpponent();
                            CardCollectionView known = FairNaming.known(player, opp);
                            if (!valid.isEmpty()) {
                                known = CardLists.getValidCards(known, valid, source.getController(), source, sa);
                            }
                            return ComputerUtilCard.getMostProminentCardName(known);
                        });
            } else if (logic.equals("MostProminentCreatureInComputerDeck")) {
                CardCollectionView cards = CardLists.getValidCards(aiLibrary, "Creature", player, sa.getHostCard(), sa);
                name = ComputerUtilCard.getMostProminentCardName(cards);
            } else if (logic.equals("BestCreatureInComputerDeck")) {
                Card bestCreature = ComputerUtilCard.getBestCreatureAI(aiLibrary);
                name = bestCreature != null ? bestCreature.getName() : "";
            } else if (logic.equals("RandomInComputerDeck")) {
                name = aiLibrary.isEmpty() ? "" : Aggregates.random(aiLibrary).getName();
            } else if (logic.equals("MostProminentSpellInComputerDeck")) {
                CardCollectionView cards = CardLists.getValidCards(aiLibrary, "Card.Instant,Card.Sorcery", player, sa.getHostCard(), sa);
                name = ComputerUtilCard.getMostProminentCardName(cards);
            } else if (logic.equals("MakeCard")) {
                name = chooseCardName(sa, StaticData.instance().getCommonCards()
                        .streamAllFaces().filter(cpp).sorted().collect(Collectors.toList()),message);
            } else if (logic.equals("CursedScroll")) {
                name = SpecialCardAi.CursedScroll.chooseCard(player, sa);
            } else if (logic.equals("PithingNeedle") || logic.equals("PhyrexianRevoker") || logic.equals("SorcerousSpyglass")) {
                name = SpecialCardAi.PithingNeedle.chooseCard(player, sa);
            }

            if (!StringUtils.isBlank(name)) {
                return name;
            }
        } else {
            CardCollectionView list = CardLists.filterControlledBy(getGame().getCardsInGame(), player.getOpponents());
            final CardCollectionView nonLands = CardLists.filter(list, CardPredicates.NON_LANDS);
            // fairNaming (lane ai-misplays-1006): ON names only an opponent's card this player has seen.
            return FairNaming.decide(player, sa.getHostCard() == null ? "NameCard" : sa.getHostCard().getName(),
                    () -> nonLands.isEmpty() ? "Morphling" : nonLands.get(0).getName(),
                    () -> {
                        final CardCollectionView known = FairNaming.knownAmong(player, nonLands);
                        return known.isEmpty() ? "Morphling" : known.get(0).getName();
                    });
        }
        return "Morphling";
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination,
            List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal,
            String selectPrompt, boolean isOptional, Player decider) {
        if (cacHook != null && !cacSuppressed) {
            cacHook.beforeAsk(this);
        }
        return dp(Kind.SELECT, "chooseSingleCardForZoneChange", sa, fetchList, isOptional ? 0 : 1, 1,
                () -> chooseSingleCardForZoneChangeAi(destination, origin, sa, fetchList, delayedReveal, selectPrompt,
                        isOptional, decider), destination, origin, fetchList, isOptional, decider);
    }

    private Card chooseSingleCardForZoneChangeAi(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        return brains.chooseCardToHiddenOriginChangeZone(destination, origin, sa, fetchList, player, decider);
    }

    @Override
    public List<Card> chooseCardsForZoneChange(
	    ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min, int max,
            DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        return dp(Kind.SELECT, "chooseCardsForZoneChange", sa, fetchList, min, max, () -> chooseCardsForZoneChangeAi(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider), destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider);
    }

    private List<Card> chooseCardsForZoneChangeAi(
	    ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min, int max,
            DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        // this isn't used
        return null;
    }

    @Override
    public void resetAtEndOfTurn() {
        // TODO - if card memory is ever used to remember something for longer than a turn, make sure it's not reset here.
        getAi().getCardMemory().clearAllRemembered();
    }

    @Override
    public void autoPassCancel() {
        // Do nothing
    }

    @Override
    public void awaitNextInput() {
        // Do nothing
    }
    @Override
    public void cancelAwaitNextInput() {
        // Do nothing
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        return dp(Kind.CHOICE, "chooseSingleCardFace", sa, faces, 1, 1, () -> chooseSingleCardFaceAi(sa, faces, message), sa, faces, message);
    }

    private ICardFace chooseSingleCardFaceAi(SpellAbility sa, List<ICardFace> faces, String message) {
        return SpellApiToAi.Converter.get(sa).chooseCardFace(player, sa, faces);
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) {
        return dp(Kind.CHOICE, "chooseSingleCardFace", sa, null, 1, 1, () -> chooseSingleCardFaceAi(sa, message, cpp, name), sa, message, cpp, name);
    }

    private ICardFace chooseSingleCardFaceAi(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) {
        throw new UnsupportedOperationException("Should not be called for AI"); // or implement it if you know how
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) {
        return dp(Kind.CHOICE, "chooseSingleCardState", sa, states, 1, 1, () -> chooseSingleCardStateAi(sa, states, message, params), sa, states, message, params);
    }

    private CardState chooseSingleCardStateAi(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) {
        return SpellApiToAi.Converter.get(sa).chooseCardState(player, sa, states, params);
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return dp(Kind.SELECT, "chooseCardsForSplice", sa, cards, 0, cards == null ? -1 : cards.size(), () -> chooseCardsForSpliceAi(sa, cards), sa, cards);
    }

    private List<Card> chooseCardsForSpliceAi(SpellAbility sa, List<Card> cards) {
        // sort from best to worst
        CardLists.sortByCmcDesc(cards);

        List<Card> result = Lists.newArrayList();

        SpellAbility oldSA = sa;
        // TODO maybe add some more Logic into it
        for (final Card c : cards) {
            SpellAbility newSA = oldSA.copy();
            AbilityUtils.addSpliceEffect(newSA, c);
            // check if AI still wants or can play the card with spliced effect
            if (AiPlayDecision.WillPlay == getAi().canPlayFromEffectAI((Spell) newSA, false, false)) {
                oldSA = newSA;
                result.add(c);
            }
        }
        return result;
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen, List<OptionalCostValue> optionalCostValues) {
        return dp(Kind.SELECT, "chooseOptionalCosts", chosen, optionalCostValues, 0, optionalCostValues == null ? -1 : optionalCostValues.size(), () -> chooseOptionalCostsAi(chosen, optionalCostValues), chosen, optionalCostValues);
    }

    private List<OptionalCostValue> chooseOptionalCostsAi(SpellAbility chosen, List<OptionalCostValue> optionalCostValues) {
        return SpellApiToAi.Converter.get(chosen).chooseOptionalCosts(player, chosen, optionalCostValues);
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        return dp(Kind.NUMBER, "chooseNumberForKeywordCost", sa, null, 0, max, () -> chooseNumberForKeywordCostAi(sa, cost, keyword, prompt, max), sa, cost, keyword, prompt, max);
    }

    private int chooseNumberForKeywordCostAi(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        if (sa.hasOptionalKeywordAmount(keyword)) {
            return Math.min(sa.getOptionalKeywordAmount(keyword), max);
        }
        // TODO: improve the logic depending on the keyword and the playability of the cost-modified SA (enough targets present etc.)
        if (keyword.getKeyword() == Keyword.CASUALTY
                && "true".equalsIgnoreCase(sa.getHostCard().getSVar("AINoCasualtyPayment"))) {
            // TODO: Grisly Sigil - currently will be misplayed if Casualty is paid (the cost is always paid, targeting is wrong).
            return 0;
        }

        int chosenAmount = 0;

        Cost costSoFar = sa.getPayCosts().copy();

        for (int i = 0; i < max; i++) {
            costSoFar.add(cost);
            SpellAbility fullCostSa = sa.copyWithDefinedCost(costSoFar);
            if (ComputerUtilCost.canPayCost(fullCostSa, player, sa.isTrigger())) {
                chosenAmount++;
            } else {
                break;
            }
        }

        return chosenAmount;
    }

    @Override
    public int chooseNumberForCostReduction(final SpellAbility sa, final int min, final int max) {
        return dp(Kind.NUMBER, "chooseNumberForCostReduction", sa, null, min, max, () -> chooseNumberForCostReductionAi(sa, min, max), sa, min, max);
    }

    private int chooseNumberForCostReductionAi(final SpellAbility sa, final int min, final int max) {
        return max;
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return dp(Kind.ARRANGE, "orderCosts", null, costs, -1, -1, () -> orderCostsAi(costs), costs);
    }

    private List<CostPart> orderCostsAi(List<CostPart> costs) {
        return costs;
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        return dp(Kind.SELECT, "chooseCardsForEffectMultiple", sa, null, -1, -1, () -> chooseCardsForEffectMultipleAi(validMap, sa, title, isOptional), validMap, sa, title, isOptional);
    }

    private CardCollection chooseCardsForEffectMultipleAi(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        CardCollection choices = new CardCollection();

        for (String mapKey: validMap.keySet()) {
            CardCollection cc = validMap.get(mapKey);
            cc.removeAll(choices);
            Card chosen = ComputerUtilCard.getBestAI(cc);
            if (chosen != null) {
                choices.add(chosen);
            }
        }

        return choices;
    }
}
