package forge.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;

import forge.ai.dp.DecisionAnswerer;
import forge.ai.dp.DecisionPoint;
import forge.card.ColorSet;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

/**
 * decisionpoint-1102 (PR-B): the choose-a-card hook ({@link CacHook}) as a {@link DecisionAnswerer}. One case per
 * hooked ctx; each is the call (and the guards around it) the controller method made before PR-B, verbatim, on the
 * point's raw arguments, with {@code current} = Forge AI's answer in the place of the old {@code def}. The controller
 * consults it under the old condition (a hook is set and not suppressed), so every hooked answer is unchanged.
 */
public final class CacAdapter implements DecisionAnswerer {
    private static final Set<String> HANDLED = Set.of("choosePermanentsToSacrifice", "chooseSingleEntityForEffect",
            "chooseEntitiesForEffect", "confirmAction", "arrangeForScry", "arrangeForSurveil", "orderMoveToZoneList",
            "chooseCardsToDiscardFrom", "chooseCardsToDelve", "tuckCardsViaMulligan", "chooseSpellAbilityToPlay",
            "chooseColor", "payCostToPreventEffect", "orderSimultaneousSa", "chooseCardsForConvokeOrImprovise",
            "chooseSingleCardForZoneChange");

    private final PlayerControllerAi c;
    private final CacHook h;

    CacAdapter(final PlayerControllerAi c, final CacHook h) {
        this.c = c;
        this.h = h;
    }

    /** Whether a ctx has a choose-a-card case (every other point is Forge's or an answerer's). */
    public static boolean handles(final String ctx) {
        return HANDLED.contains(ctx);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object answer(final DecisionPoint dp, final Object current) {
        final SpellAbility sa = dp.source();
        switch (dp.ctx()) {
            case "choosePermanentsToSacrifice":
                return h.sacrifice(c, sa, (Integer) dp.arg(0), (Integer) dp.arg(1), (CardCollectionView) dp.arg(2),
                        (CardCollectionView) current);
            case "chooseSingleEntityForEffect": {
                // the legend rule's keep only
                if (sa == null || sa.getApi() != ApiType.InternalLegendaryRule || !(current instanceof Card)) {
                    return current;
                }
                final List<Card> opts = new ArrayList<>();
                for (Object t : (FCollectionView<? extends GameEntity>) dp.arg(0)) {
                    if (!(t instanceof Card)) return current;
                    opts.add((Card) t);
                }
                final Card k = h.legendKeep(c, opts, (Card) current);
                return opts.contains(k) ? k : current;
            }
            case "chooseEntitiesForEffect":
                return PlayerControllerAi.cacDig(c, h, (FCollectionView<GameEntity>) dp.arg(0), (Integer) dp.arg(1),
                        (Integer) dp.arg(2), sa, (List<GameEntity>) current);
            case "confirmAction":
                return h.confirm(c, sa, (PlayerActionConfirmMode) dp.arg(0), (String) dp.arg(1), (Boolean) current);
            case "arrangeForScry":
                return h.scry(c, (CardCollection) dp.arg(0), (ImmutablePair<CardCollection, CardCollection>) current);
            case "arrangeForSurveil":
                return h.surveil(c, (CardCollection) dp.arg(0), (ImmutablePair<CardCollection, CardCollection>) current);
            case "orderMoveToZoneList":
                return h.order(c, (CardCollectionView) dp.arg(0), (ZoneType) dp.arg(1), sa, (CardCollectionView) current);
            case "chooseCardsToDiscardFrom":
                return h.discard(c, (Player) dp.arg(0), sa, (CardCollection) dp.arg(1), (Integer) dp.arg(2),
                        (Integer) dp.arg(3), (CardCollection) current);
            case "chooseCardsToDelve": {
                final int genericAmount = (Integer) dp.arg(0);
                final CardCollection grave = (CardCollection) dp.arg(1);
                final CardCollectionView def = (CardCollectionView) current;
                final CardCollectionView out = h.costCards(c, null, "Delve", grave, genericAmount, def);
                return out == null || out.size() > genericAmount || !grave.containsAll(out) ? def : out;
            }
            case "tuckCardsViaMulligan":
                return h.tuck(c, (CardCollectionView) dp.arg(0), (Integer) dp.arg(1), (CardCollectionView) current);
            case "chooseSpellAbilityToPlay":
                return h.priority(c, (List<SpellAbility>) current);
            case "chooseColor": {
                final ColorSet colors = (ColorSet) dp.arg(0);
                final byte def = (Byte) current;
                if (colors.countColors() < 2) {
                    return def;
                }
                final byte k = h.chooseColor(c, sa, colors, def);
                return (colors.getColor() & k) != 0 && Integer.bitCount(k & 0xff) == 1 ? k : def;
            }
            case "payCostToPreventEffect":
                return h.payToPrevent(c, (Cost) dp.arg(0), sa, (Boolean) dp.arg(1), (Boolean) current);
            case "orderSimultaneousSa":
                return c.cacOrder((List<SpellAbility>) dp.arg(0), (List<SpellAbility>) current);
            case "chooseCardsForConvokeOrImprovise": {
                final Map<Card, ManaCostShard> def = (Map<Card, ManaCostShard>) current;
                final CardCollectionView untappedCards = (CardCollectionView) dp.arg(1);
                final CardCollection d = new CardCollection(def.keySet());
                final CardCollectionView out = h.costCards(c, sa, "Convoke", untappedCards, d.size(), d);
                if (out == null || out == d || !untappedCards.containsAll(out)) {
                    return def;
                }
                return ComputerUtilMana.getConvokeOrImproviseFromList((ManaCost) dp.arg(0), new ArrayList<>(out),
                        (Boolean) dp.arg(2), (Boolean) dp.arg(3));
            }
            case "chooseSingleCardForZoneChange":
                return h.pick(c, (ZoneType) dp.arg(0), (List<ZoneType>) dp.arg(1), sa, (CardCollection) dp.arg(2),
                        (Boolean) dp.arg(3), (Player) dp.arg(4), (Card) current);
            default:
                return current;
        }
    }
}
