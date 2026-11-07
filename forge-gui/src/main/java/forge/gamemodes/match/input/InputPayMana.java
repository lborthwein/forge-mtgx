package forge.gamemodes.match.input;

import com.google.common.collect.Lists;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.event.EventValueChangeType;
import forge.game.event.GameEventManaPool;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.card.CardCollection;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.Mana;
import forge.game.mana.ManaPool;
import forge.game.player.PlaySpellAbility;
import forge.game.player.Player;
import forge.game.player.PlayerController.FullControlFlag;
import forge.game.player.PlayerView;
import forge.game.player.actions.PayManaFromPoolAction;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;
import forge.gui.FThreads;
import forge.player.PlayerControllerHuman;
import forge.util.Evaluator;
import forge.util.ITriggerEvent;
import forge.util.Localizer;
import forge.util.TextUtil;

import java.util.*;

public abstract class InputPayMana extends InputSyncronizedBase {
    private static final long serialVersionUID = 718128600948280315L;

    protected int phyLifeToLose = 0;

    protected final Player player;
    protected final Game game;
    protected ManaCostBeingPaid manaCost;
    protected final SpellAbility saPaidFor;
    /** Read-only identity of the actual payment, not the last tapped mana source. */
    public final CardView getPaymentSourceCard() {
        return saPaidFor == null ? null : CardView.get(saPaidFor.getHostCard());
    }
    protected boolean effect;
    protected boolean mandatory = false;
    private final boolean wasFloatingMana;
    private final Queue<Card> delaySelectCards = new LinkedList<>();

    private boolean bPaid = false;
    /** null = not yet evaluated, or not payable; otherwise cards Auto would tap */
    private CardCollection autoPayManaSources = null;
    private boolean autoPayManaSourcesKnown = false;

    private boolean locked = false;

    protected InputPayMana(final PlayerControllerHuman controller, final SpellAbility saPaidFor0, final Player player0, final boolean effect) {
        super(controller);
        player = player0;
        game = player.getGame();
        saPaidFor = saPaidFor0;
        this.effect = effect;

        //if player is floating mana, show mana pool to make it easier to use that mana
        wasFloatingMana = !player.getManaPool().isEmpty();
        if (wasFloatingMana) {
            getController().getGui().showManaPool(PlayerView.get(player));
        }
    }

    /** A payment asked while an effect resolves (an "unless" cost, echo, a "you may pay"), not a cast's. */
    public final boolean isEffectPayment() {
        return effect;
    }

    /** A payment that must be made (CR 118.3c, a forced cast paid from the pool). */
    public final boolean isMandatoryPayment() {
        return mandatory;
    }

    @Override
    protected void onStop() {
        getController().clearActionableCards();
        if (!isFinished()) {
            // Clear current Mana cost being paid for SA
            saPaidFor.setManaCostBeingPaid(null);
            player.popPaidForSA();

            if (wasFloatingMana) { //hide mana pool if it was shown due to floating mana
                getController().getGui().hideManaPool(PlayerView.get(player));
            }
        }
    }

    @Override
    protected boolean onCardSelected(final Card card, final List<Card> otherCardsToSelect, final ITriggerEvent triggerEvent) {
        if (getController().getGui().isLibgdxPort()) {
            // Mobile Forge allows to tap cards underneath the current card even if the current one is tapped
            if (otherCardsToSelect != null) {
                for (Card c : otherCardsToSelect) {
                    for (SpellAbility sa : getAllManaAbilities(c)) {
                        if (sa.canPlay()) {
                            delaySelectCards.add(c);
                            break;
                        }
                    }
                }
            }
            if (!getAllManaAbilities(card).isEmpty() && activateManaAbility(card)) {
                return true;
            }
            return activateDelayedCard();
        }

        List<SpellAbility> manaAbilities = getAllManaAbilities(card);
        // The browser seat (owner, 2026-11-07): a source with one ability per colour (a dual land's
        // basic land types) offers the abilities that make a colour able to pay what is left. One
        // left: Forge takes it, and the seat's log is told which colour.
        byte offeredBefore = 0;
        final List<SpellAbility> canPay = abilitiesThatCanPay(manaAbilities);
        if (canPay != null) {
            for (SpellAbility sa : manaAbilities) {
                offeredBefore |= producibleColours(sa);
            }
            manaAbilities = canPay;
        }
        // Desktop Forge floating menu functionality
        if (manaAbilities.size() == 1) {
            return activateManaAbility(card, manaAbilities.get(0), offeredBefore);
        }
        SpellAbility spellAbility = getController().getAbilityToPlay(card, manaAbilities, triggerEvent);
        if (spellAbility != null) {
            return activateManaAbility(card, spellAbility);
        }
        return true;
    }

    /**
     * For a seat whose produced mana floats ({@link FullControlFlag#NoPaymentFromManaAbility}): those of
     * {@code abilities} that make a colour able to pay what is left of the cost once the floating
     * unrestricted mana is counted, or null to keep them all (another seat, a generic part left, which
     * any of them pays, no colour that can pay, or every ability can).
     */
    private List<SpellAbility> abilitiesThatCanPay(final List<SpellAbility> abilities) {
        if (abilities.size() < 2) {
            return null;
        }
        final byte payable = coloursThatCanPayLeft(abilities.get(0).getHostCard());
        if (payable == 0 || leftAfterFloating().isAnyPartPayableWith((byte) ManaAtom.GENERIC, player.getManaPool())) {
            return null;
        }
        final List<SpellAbility> canPay = new ArrayList<>();
        for (SpellAbility sa : abilities) {
            if ((producibleColours(sa) & payable) != 0) {
                canPay.add(sa);
            }
        }
        return canPay.isEmpty() || canPay.size() == abilities.size() ? null : canPay;
    }

    protected List<SpellAbility> getAllManaAbilities(Card card) {
        List<SpellAbility> result = Lists.newArrayList();
        for (SpellAbility sa : card.getManaAbilities()) {
            result.add(sa);
            result.addAll(GameActionUtil.getAlternativeCosts(sa, player, false));
        }
        final Collection<SpellAbility> toRemove = Lists.newArrayListWithCapacity(result.size());
        for (final SpellAbility sa : result) {
            sa.setActivatingPlayer(player);
            if (sa.canPlay(true)) {
                continue;
            }
            toRemove.add(sa);
        }
        result.removeAll(toRemove);
        return result;
    }

    @Override
    public String getActivateAction(Card card) {
        for (SpellAbility sa : getAllManaAbilities(card)) {
            if (sa.canPlay()) {
                return Localizer.getInstance().getMessage("lblPayManaWithCard");
            }
        }
        return null;
    }

    private boolean activateDelayedCard() {
        if (delaySelectCards.isEmpty()) {
            return false;
        }
        if (manaCost.isPaid()) {
            delaySelectCards.clear(); //clear delayed cards if mana cost already paid
            return false;
        }
        if (activateManaAbility(delaySelectCards.poll())) {
            return true;
        }
        return activateDelayedCard();
    }

    @Override
    public boolean selectAbility(final SpellAbility ab) {
        if (ab != null && ab.isManaAbility()) {
            return activateManaAbility(ab.getHostCard(), ab);
        }
        return false;
    }

    @Deprecated
    public List<SpellAbility> getUsefulManaAbilities(Card card) {
        List<SpellAbility> abilities = new ArrayList<>();

        if (card.getController() != player) {
            return abilities;
        }

        byte colorCanUse = 0;
        for (final byte color : ManaAtom.MANATYPES) {
            if (manaCost.isAnyPartPayableWith(color, player.getManaPool())) {
                colorCanUse |= color;
            }
        }
        if (manaCost.isAnyPartPayableWith((byte) ManaAtom.GENERIC, player.getManaPool())) {
            colorCanUse |= ManaAtom.GENERIC;
        }
        if (colorCanUse == 0) { // no mana cost or something
            return abilities;
        }

        //        final String typeRes = manaCost.getSourceRestriction();
        //        if (StringUtils.isNotBlank(typeRes) && !card.getType().hasStringType(typeRes)) {
        //            return abilities;
        //        }

        for (SpellAbility ma : getAllManaAbilities(card)) {
            ma.setActivatingPlayer(player);
            if (ma.isManaAbilityFor(saPaidFor, colorCanUse))
                abilities.add(ma);
        }
        return abilities;
    }

    public void useManaFromPool(byte colorCode) {
        // find the matching mana in pool.
        if (player.getManaPool().tryPayCostWithColor(colorCode, saPaidFor, manaCost, saPaidFor.getPayingMana())) {
            // Record paying mana from pool here
            getController().macros().addRememberedAction(new PayManaFromPoolAction(colorCode));
            showMessage();
        }
    }

    /** A browser payment is a sequence of exact pool objects, never an auto-tap plan.
     * A null mana step means paying two life through the cost-payment input. */
    public record PoolPaymentStep(Mana mana) { }
    public record PoolPaymentChoice(List<PoolPaymentStep> steps, String state, List<Mana> pool) { }
    public record PoolPaymentChoices(List<PoolPaymentChoice> choices, boolean complete) { }

    protected boolean payLifeOnCopy(ManaCostBeingPaid cost, int additionalLife) {
        return false;
    }

    protected void commitPoolPaymentLife() {
        throw new IllegalStateException("Life payment is not supported by this input");
    }

    private List<Mana> currentPoolObjects() {
        final List<Mana> pool = new ArrayList<>();
        player.getManaPool().forEach(pool::add);
        return pool;
    }

    private String poolPaymentState(List<Mana> pool) {
        final StringBuilder key = new StringBuilder(manaCost.toString());
        key.append('|').append(manaCost.getColorsPaid()).append('|')
                .append(manaCost.getXManaCostPaidByColor()).append('|')
                .append(phyLifeToLose).append('|').append(player.getLife());
        return key.toString();
    }

    private boolean canSpendPoolMana(ManaCostBeingPaid cost, Mana mana) {
        return mana.meetsManaRestrictions(saPaidFor)
                && saPaidFor.allowsPayingWithShard(mana.getSourceCard(), mana.getColor())
                && cost.isNeeded(mana, player.getManaPool());
    }

    /** Same source and same producer, not merely Mana.equals (which can merge
     * sources). Source properties are observable through ManaFrom predicates. */
    private static boolean interchangeablePoolObjects(Mana a, Mana b) {
        return a.getColor() == b.getColor() && a.getPlayer() == b.getPlayer()
                && a.getSourceCard().getId() == b.getSourceCard().getId()
                && a.getManaAbility() == b.getManaAbility() && a.isSnow() == b.isSnow();
    }

    private static int[] poolPaymentGroups(List<Mana> pool) {
        final int[] groups = new int[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            groups[i] = i;
            for (int j = 0; j < i; j++) {
                if (interchangeablePoolObjects(pool.get(i), pool.get(j))) {
                    groups[i] = groups[j];
                    break;
                }
            }
        }
        return groups;
    }

    /** Read-only search using Forge's actual mana-shard transitions and restrictions.
     * No pool mutation, ability activation, AI controller, or default choice. A capped
     * result is explicitly incomplete so clients must retain manual payment controls. */
    public final PoolPaymentChoices getPoolPaymentChoices() {
        final List<Mana> pool = currentPoolObjects();
        final List<PoolPaymentChoice> choices = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final Set<String> outcomes = new HashSet<>();
        final int[] budget = {20000};
        final boolean[] complete = {true};
        enumeratePoolPayments(new ManaCostBeingPaid(manaCost), pool, poolPaymentGroups(pool), new BitSet(),
                new ArrayList<>(), 0, poolPaymentState(pool), choices, seen, outcomes, budget, complete);
        return new PoolPaymentChoices(List.copyOf(choices), complete[0]);
    }

    private void enumeratePoolPayments(ManaCostBeingPaid cost, List<Mana> pool, int[] groups, BitSet used,
            List<PoolPaymentStep> steps, int life, String state, List<PoolPaymentChoice> choices,
            Set<String> seen, Set<String> outcomes, int[] budget, boolean[] complete) {
        if (--budget[0] < 0 || choices.size() >= 128) {
            complete[0] = false;
            return;
        }
        final String spent = used + ":" + life + ":" + cost.getColorsPaid()
                + ":" + cost.getXManaCostPaidByColor();
        if (!seen.add(spent + ":" + cost)) { return; }
        if (cost.isPaid()) {
            if (outcomes.add(spent)) {
                choices.add(new PoolPaymentChoice(List.copyOf(steps), state, List.copyOf(pool)));
            }
            return;
        }
        // Use only the first remaining object of each fungible group. Ten ordinary
        // globes from one source paying six mana produce one branch, not 210
        // subsets (or factorial orderings). Keep different source effects distinct.
        final BitSet visitedGroups = new BitSet();
        for (int i = 0; i < pool.size(); i++) {
            if (!complete[0]) { return; }
            if (used.get(i) || visitedGroups.get(groups[i])) { continue; }
            visitedGroups.set(groups[i]);
            if (!canSpendPoolMana(cost, pool.get(i))) { continue; }
            final ManaCostBeingPaid next = new ManaCostBeingPaid(cost);
            next.payMana(pool.get(i), player.getManaPool());
            used.set(i);
            steps.add(new PoolPaymentStep(pool.get(i)));
            enumeratePoolPayments(next, pool, groups, used, steps, life, state, choices, seen, outcomes, budget, complete);
            steps.remove(steps.size() - 1);
            used.clear(i);
        }
        final ManaCostBeingPaid next = new ManaCostBeingPaid(cost);
        if (payLifeOnCopy(next, life + 2)) {
            steps.add(new PoolPaymentStep(null));
            enumeratePoolPayments(next, pool, groups, used, steps, life + 2, state, choices, seen, outcomes, budget, complete);
            steps.remove(steps.size() - 1);
        }
    }

    /** Execute only a currently valid complete sequence. Validate the entire sequence
     * before changing the pool; the interactive request dispatcher serializes inputs. */
    public final boolean payPoolPaymentChoice(PoolPaymentChoice choice) {
        if (locked || isFinished() || !choice.state().equals(poolPaymentState(currentPoolObjects()))) {
            return false;
        }
        final List<Mana> remaining = currentPoolObjects();
        if (remaining.size() != choice.pool().size()) { return false; }
        for (int i = 0; i < remaining.size(); i++) {
            if (remaining.get(i) != choice.pool().get(i)) { return false; }
        }
        final ManaCostBeingPaid check = new ManaCostBeingPaid(manaCost);
        int life = 0;
        for (PoolPaymentStep step : choice.steps()) {
            final Mana mana = step.mana();
            if (mana == null) {
                life += 2;
                if (!payLifeOnCopy(check, life)) { return false; }
            } else {
                int found = -1;
                for (int i = 0; i < remaining.size(); i++) {
                    if (remaining.get(i) == mana) { found = i; break; }
                }
                if (found < 0 || !canSpendPoolMana(check, mana)) { return false; }
                remaining.remove(found);
                check.payMana(mana, player.getManaPool());
            }
        }
        if (!check.isPaid()) { return false; }
        for (PoolPaymentStep step : choice.steps()) {
            if (step.mana() == null) {
                commitPoolPaymentLife();
            } else {
                // Mana.equals intentionally merges some sources; remove the captured
                // object by identity so snow/spend-trigger sources cannot be swapped.
                boolean removed = false;
                for (Iterator<Mana> it = player.getManaPool().iterator(); it.hasNext();) {
                    if (it.next() == step.mana()) {
                        it.remove();
                        removed = true;
                        break;
                    }
                }
                if (!removed) {
                    throw new IllegalStateException("Validated pool payment changed during execution");
                }
                manaCost.payMana(step.mana(), player.getManaPool());
                saPaidFor.getPayingMana().add(step.mana());
            }
        }
        final Set<MagicColor.Color> colors = EnumSet.noneOf(MagicColor.Color.class);
        for (PoolPaymentStep step : choice.steps()) {
            if (step.mana() != null) { colors.add(MagicColor.Color.fromByte(step.mana().getColor())); }
        }
        if (!colors.isEmpty()) {
            player.updateManaForView();
            game.fireEvent(new GameEventManaPool(player, EventValueChangeType.Removed, colors));
        }
        showMessage();
        return true;
    }

    protected boolean activateManaAbility(final Card card) {
        return activateManaAbility(card, null);
    }
    protected boolean activateManaAbility(final Card card, SpellAbility chosenAbility) {
        return activateManaAbility(card, chosenAbility, (byte) 0);
    }

    /**
     * {@code offeredBefore}: the colours the source's abilities offered before they were narrowed to
     * {@code chosenAbility}, the only one that can pay (the browser seat), else 0.
     */
    private boolean activateManaAbility(final Card card, SpellAbility chosenAbility, final byte offeredBefore) {
        if (locked) {
            System.err.print("Should wait till previous call to playAbility finishes.");
            return false;
        }

        // make sure computer's lands aren't selected

        byte colorCanUse = 0;
        byte colorNeeded = 0;

        for (final byte color : ManaAtom.MANATYPES) {
            if (manaCost.isAnyPartPayableWith(color, player.getManaPool())) { colorCanUse |= color; }
            if (manaCost.needsColor(color, player.getManaPool()))           { colorNeeded |= color; }
        }
        if (manaCost.isAnyPartPayableWith((byte) ManaAtom.GENERIC, player.getManaPool())) {
            colorCanUse |= ManaAtom.GENERIC;
        }

        if (colorCanUse == 0) { // no mana cost or something
            return false;
        }

        final SpellAbility chosen;
        // The browser seat: the colours the source's mana abilities offered before they were
        // narrowed to the ones that can pay (0 when no narrowing happened).
        byte cardColours = offeredBefore;

        if (chosenAbility == null) {
            HashMap<SpellAbilityView, SpellAbility> abilitiesMap = new HashMap<>();
            // you can't remove unneeded abilities inside a for (am:abilities) loop :(

            boolean guessAbilityWithRequiredColors = true;
            int amountOfMana = -1;
            for (SpellAbility ma : getAllManaAbilities(card)) {
                ma.setActivatingPlayer(player);

                if (!ma.isManaAbilityFor(saPaidFor, colorCanUse)) { continue; }

                // If Mana Abilities produce differing amounts of mana, let the player choose
                int maAmount = ma.totalAmountOfManaGenerated(saPaidFor, true);
                if (amountOfMana == -1) {
                    amountOfMana = maAmount;
                } else if (amountOfMana != maAmount) {
                    guessAbilityWithRequiredColors = false;
                }

                abilitiesMap.put(ma.getView(), ma);

                // skip express mana if the ability is not undoable or reusable
                if (!ma.isUndoable() || !ma.getPayCosts().isRenewableResource() || ma.getSubAbility() != null
                        || ma.isManaCannotCounter(saPaidFor)) {
                    guessAbilityWithRequiredColors = false;
                }
            }

            if (abilitiesMap.isEmpty()) {
                return false;
            }

            // Store some information about color costs to help with any mana choices
            if (colorNeeded == 0) { // only colorless left
                if (saPaidFor.getHostCard() != null && saPaidFor.getHostCard().hasSVar("ManaNeededToAvoidNegativeEffect")) {
                    String[] negEffects = saPaidFor.getHostCard().getSVar("ManaNeededToAvoidNegativeEffect").split(",");
                    for (String negColor : negEffects) {
                        byte col = ManaAtom.fromName(negColor);
                        colorCanUse |= col;
                    }
                }
            }

            // If the card has any ability that tracks mana spent, skip express Mana choice
            if (saPaidFor.tracksManaSpent()) {
                colorCanUse = ColorSet.WUBRG.getColor();
                guessAbilityWithRequiredColors = false;
            }

            boolean choice = true;
            // The browser seat (produced mana floats, so the cost never shrinks): Forge's narrowing
            // kept only the abilities making a colour the whole cost names, and a dual land paying
            // {1}{G} made {G} with no question though its {U} pays the {1}. The same rule as a
            // selected card: the abilities that make a colour able to pay what is left.
            final boolean floatingSeat = guessAbilityWithRequiredColors
                    && player.getController().isFullControl(FullControlFlag.NoPaymentFromManaAbility)
                    && !saPaidFor.tracksManaSpent();
            if (floatingSeat) {
                final List<SpellAbility> canPay = abilitiesThatCanPay(new ArrayList<>(abilitiesMap.values()));
                if (canPay != null) {
                    for (SpellAbility sa : abilitiesMap.values()) {
                        cardColours |= producibleColours(sa);
                    }
                    abilitiesMap = new HashMap<>();
                    for (SpellAbility sa : canPay) {
                        abilitiesMap.put(sa.getView(), sa);
                    }
                    if (abilitiesMap.size() != 1) {
                        cardColours = 0; // no colour was decided here: the seat is asked
                    }
                }
            } else if (guessAbilityWithRequiredColors) {
                // express Mana Choice
                if (colorNeeded == 0) {
                    choice = false;
                    //avoid unnecessary prompt by pretending we need White
                    //for the sake of "Add one mana of any color" effects
                    colorNeeded = MagicColor.WHITE;
                } else {
                    final HashMap<SpellAbilityView, SpellAbility> colorMatches = new HashMap<>();
                    for (SpellAbility sa : abilitiesMap.values()) {
                        if (sa.isManaAbilityFor(saPaidFor, colorNeeded)) {
                            colorMatches.put(sa.getView(), sa);
                        }
                    }

                    if (colorMatches.isEmpty()) {
                        // can only match colorless just grab the first and move on.
                        // This is wrong. Sometimes all abilities aren't created equal
                        choice = false;
                    }
                    else if (colorMatches.size() < abilitiesMap.size()) {
                        // leave behind only color matches
                        abilitiesMap = colorMatches;
                    }
                }
            }

            ArrayList<SpellAbilityView> choices = new ArrayList<>(abilitiesMap.keySet());
            chosen = abilitiesMap.size() > 1 && choice ? abilitiesMap.get(getController().getGui().one(Localizer.getInstance().getMessage("lblChooseManaAbility"),  choices)) : abilitiesMap.get(choices.get(0));
        } else {
            chosen = chosenAbility;
        }

        ColorSet colors = ColorSet.fromMask(0 == colorNeeded ? colorCanUse : colorNeeded);

        // Filter the colors for the express choice so that only actually producible colors can be chosen
        int producedColorMask = 0;
        for (final byte color : ManaAtom.MANATYPES) {
            if (chosen.canProduce(MagicColor.toShortString(color)) && colors.hasAnyColor(color)) {
                producedColorMask |= color;
            }
        }

        // With NoPaymentFromManaAbility (the browser seat) produced mana floats and the cost never
        // shrinks, so colorNeeded keeps every colour the cost had, and a one-mana "{G} or {U}"
        // source took the express choice's first colour every time: Botanical Sanctum paying for
        // Oko ({1}{G}{U}) always made {U}, even after an Island had floated the {U} (owner report
        // 2026-10-06T00-39-37). Count the floating mana first; ask when colours remain.
        // Any colour-choice source on this seat (owner, 2026-11-06T20-10-11 and 2026-11-07): an
        // any-colour source, a dual land, "any combination". The seat is asked among the colours that
        // can pay what is left, unless only one can, and then the table is told which colour Forge took.
        final byte offered = colourChoiceOffer(chosen);
        final int comboAmount = offered != 0 && chosen.getManaPart().isComboMana()
                ? chosen.amountOfManaGenerated(false) : 0;
        final boolean askColour = Integer.bitCount(offered & 0xFF) > 1 && comboAmount == 1;
        final boolean askCombination = Integer.bitCount(offered & 0xFF) > 1 && comboAmount > 1;
        final byte forcedColour;
        if (offered != 0) {
            producedColorMask = offered;
            forcedColour = Integer.bitCount(offered & 0xFF) == 1
                    && Integer.bitCount(producibleColours(chosen) & 0xFF) > 1 ? offered : 0;
        } else {
            // A one-colour ability Forge chose because only its colour can pay what is left, on a
            // source whose abilities offered more (a dual land paying a {U}).
            final byte made = cardColours != 0 ? producibleColours(chosen) : 0;
            forcedColour = Integer.bitCount(made & 0xFF) == 1 && Integer.bitCount(cardColours & 0xFF) > 1 ? made : 0;
        }
        chosen.setManaExpressChoice(ColorSet.fromMask(producedColorMask));
        if (comboAmount > 1 && forcedColour != 0) {
            // "Any combination" with one colour that can pay: every mana is that colour. ManaEffect
            // reads the express choice by position, so the colour is written once per mana.
            chosen.getManaPart().setExpressChoice(repeatColour(forcedColour, comboAmount));
        }

        // System.out.println("Chosen sa=" + chosen + " of " + chosen.getHostCard() + " to pay mana");

        locked = true;
        game.getAction().invoke(() -> {
            if (askColour) {
                // ManaEffect narrows a combo source to the express choice's first colour, so the
                // question is asked here, on the game thread, as ManaEffect asks at priority.
                final byte picked = getController().chooseColor(
                        Localizer.getInstance().getMessage("lblSelectManaProduce"), chosen,
                        ColorSet.fromMask(offered));
                if (picked != 0) {
                    chosen.setManaExpressChoice(ColorSet.fromMask(picked));
                }
            }
            if (askCombination) {
                // ManaEffect narrows "any combination" mana by position from the express choice, so
                // the combination is asked here among the colours that can pay, as ManaEffect asks
                // it at priority, and written back one colour per mana.
                final Map<Byte, Integer> picked = getController().specifyManaCombo(chosen,
                        ColorSet.fromMask(offered), comboAmount,
                        chosen.getManaPart().getOrigProduced().contains("Different"));
                final StringBuilder each = new StringBuilder();
                if (picked != null) {
                    for (final byte color : ManaAtom.MANATYPES) {
                        final Integer count = picked.get(color);
                        for (int i = 0; count != null && i < count; i++) {
                            each.append(each.length() == 0 ? "" : " ").append(MagicColor.toShortString(color));
                        }
                    }
                }
                chosen.getManaPart().setExpressChoice(each.toString());
            }
            if (PlaySpellAbility.playSpellAbility(getController(), chosen.getActivatingPlayer(), chosen)) {
                final List<AbilityManaPart> manaAbilities = chosen.getAllManaParts();
                boolean restrictionsMet = true;

                for (AbilityManaPart sa : manaAbilities) {
                    if (!sa.meetsManaRestrictions(saPaidFor)) {
                        restrictionsMet = false;
                        break;
                    }
                }

                if (restrictionsMet && !player.getController().isFullControl(FullControlFlag.NoPaymentFromManaAbility)) {
                    player.getManaPool().payManaFromAbility(saPaidFor, manaCost, chosen);
                }
                if (!restrictionsMet || chosen.getPayCosts().hasManaCost()) {
                    // force refresh in case too much mana got spent
                    updateButtons();
                    invalidateAutoPayManaSources();
                }
                if (forcedColour != 0) {
                    getController().getGui().notifyManaColourForced(CardView.get(chosen.getHostCard()), forcedColour);
                }
            } else {
                // The activation was cancelled, usually while paying its own cost. The ability object
                // outlives this payment, so the narrowing must not: left set, a later activation with
                // no cost pending made this payment's colour without a question (a source tapped for a
                // {B}{B}{B} and cancelled made {B} two turns later, owner report 2026-11-06T20-10-11).
                chosen.clearManaExpressChoice();
            }
            // Need to call this to unlock
            onStateChanged();
        });

        return true;
    }

    /**
     * For a seat whose produced mana floats ({@link FullControlFlag#NoPaymentFromManaAbility}), when
     * {@code chosen} is a colour-choice source (from its produced-mana options: any colour, a
     * combination such as "{G} or {U}", or "any combination"): the colours to offer, which are those
     * it makes that can pay what is left of the cost once the floating unrestricted mana is counted
     * (a generic part counts). Every colour it makes when none can (it then only floats), when the
     * spell cares what was spent, or when "any combination" makes more mana than is left to pay.
     * 0 when this is not such a source or not such a seat.
     */
    private byte colourChoiceOffer(final SpellAbility chosen) {
        if (!player.getController().isFullControl(FullControlFlag.NoPaymentFromManaAbility)) {
            return 0;
        }
        final AbilityManaPart part = chosen.getManaPart();
        if (part == null || chosen.getAllManaParts().size() != 1 || chosen.hasParam("Each")
                || !(part.isAnyMana() || part.isComboMana())) {
            return 0;
        }
        final byte producible = producibleColours(chosen);
        if (Integer.bitCount(producible & 0xFF) < 2) {
            return 0;
        }
        final int amount = chosen.amountOfManaGenerated(false);
        if (amount < 1) {
            return 0;
        }
        if (!part.meetsManaRestrictions(saPaidFor)) {
            return producible;
        }
        final byte payable = (byte) (coloursThatCanPayLeft(chosen.getHostCard()) & producible);
        if (payable == 0) {
            return producible;
        }
        if (part.isComboMana() && amount > 1) {
            // "Any combination": mana past what is left to pay only floats, so any colour may be it.
            if (amount > leftAfterFloating().getConvertedManaCost()) {
                return producible;
            }
            if (part.getOrigProduced().contains("Different") && Integer.bitCount(payable & 0xFF) < amount) {
                return producible;
            }
        }
        return payable;
    }

    /**
     * For a seat whose produced mana floats ({@link FullControlFlag#NoPaymentFromManaAbility}): the
     * colours (WUBRG) that can pay some part of what is left of the cost once the floating
     * unrestricted mana is counted, with mana from {@code source}. 0 for any other seat, when the
     * spell cares what was spent, or when no colour can pay.
     */
    private byte coloursThatCanPayLeft(final Card source) {
        if (!player.getController().isFullControl(FullControlFlag.NoPaymentFromManaAbility)
                || saPaidFor.tracksManaSpent()) {
            return 0;
        }
        final ManaPool pool = player.getManaPool();
        final ManaCostBeingPaid left = leftAfterFloating();
        final byte every = ColorSet.WUBRG.getColor();
        byte payable = 0;
        for (final byte color : ManaAtom.MANATYPES) {
            if ((every & color) != 0 && left.isAnyPartPayableWith(color, pool)
                    && saPaidFor.allowsPayingWithShard(source, color)) {
                payable |= color;
            }
        }
        return payable;
    }

    /** The cost being paid less the floating unrestricted mana that can pay it. */
    private ManaCostBeingPaid leftAfterFloating() {
        final ManaPool pool = player.getManaPool();
        final ManaCostBeingPaid left = new ManaCostBeingPaid(manaCost);
        for (final Mana mana : Lists.newArrayList(pool)) {
            if (!mana.isRestricted() && left.isNeeded(mana, pool)) {
                left.payMana(mana, pool);
            }
        }
        return left;
    }

    /** The colours (WUBRG) {@code sa} can make, from its produced-mana options. */
    private static byte producibleColours(final SpellAbility sa) {
        final byte every = ColorSet.WUBRG.getColor();
        byte made = 0;
        for (final byte color : ManaAtom.MANATYPES) {
            if ((every & color) != 0 && sa.canProduce(MagicColor.toShortString(color))) {
                made |= color;
            }
        }
        return made;
    }

    private static String repeatColour(final byte colour, final int times) {
        final StringBuilder each = new StringBuilder();
        for (int i = 0; i < times; i++) {
            each.append(i == 0 ? "" : " ").append(MagicColor.toShortString(colour));
        }
        return each.toString();
    }

    protected boolean isAlreadyPaid() {
        if (manaCost.isPaid()) {
            bPaid = true;
        }
        return bPaid;
    }

    protected boolean supportAutoPay() {
        return getController().getGui().supportsAutoPayMana();
    }

    protected void runAsAi(Runnable proc) {
        player.runWithController(proc, new PlayerControllerAi(game, player, player.getOriginalLobbyPlayer()));
    }

    @Override
    protected void onOk() {
        if (supportAutoPay() && !locked) { //prevent AI taking over from double-clicking Auto
            locked = true;
            //use AI utility to automatically pay mana cost if possible
            final Runnable proc = () -> ComputerUtilMana.payManaCost(manaCost, saPaidFor, player, effect);
            //must run in game thread as certain payment actions can only be automated there
            game.getAction().invoke(() -> {
                runAsAi(proc);
                onStateChanged();
            });
        }
    }

    protected void updateButtons() {
        if (supportAutoPay()) {
            getController().getGui().updateButtons(getOwner(), Localizer.getInstance().getMessage("lblAuto"), Localizer.getInstance().getMessage("lblCancel"), false, !mandatory, false);
        } else {
            getController().getGui().updateButtons(getOwner(), "", Localizer.getInstance().getMessage("lblCancel"), false, !mandatory, false);
        }
    }

    protected final void updateMessage() {
        locked = false;
        if (activateDelayedCard()) {
            return;
        }
        if (supportAutoPay()) {
            ensureAutoPayManaSources();
            if (autoPayManaSources != null) { //enabled Auto button if mana cost can be paid
                getController().getGui().updateButtons(getOwner(), Localizer.getInstance().getMessage("lblAuto"), Localizer.getInstance().getMessage("lblCancel"), true, !mandatory, true);
            }
        }
        // Drop just-tapped sources from the highlight set; emphasize the AI's auto-tap plan.
        getController().pushActionableCards(true, getAutoTapPreviewViews());
        showMessage(getMessage(), saPaidFor.getView());
    }

    @Override
    public void showMessage() {
        if (isFinished()) { return; }
        getController().pushActionableCards(true);
        updateButtons();
        onStateChanged();
    }

    protected void onStateChanged() {
        if (isAlreadyPaid()) {
            done();
            stop();
        } else {
            FThreads.invokeInEdtNowOrLater(this::updateMessage);
        }
    }

    protected abstract void done();
    protected abstract String getMessage();

    private void invalidateAutoPayManaSources() {
        autoPayManaSourcesKnown = false;
        autoPayManaSources = null;
    }

    private void ensureAutoPayManaSources() {
        if (autoPayManaSourcesKnown) {
            return;
        }
        final ManaCostBeingPaid costCopy = new ManaCostBeingPaid(manaCost);
        Evaluator<CardCollection> proc = new Evaluator<>() {
            @Override
            public CardCollection evaluate() {
                return ComputerUtilMana.getManaSourcesToPayCost(costCopy, saPaidFor, player, effect);
            }
        };
        runAsAi(proc);
        autoPayManaSources = proc.getResult();
        autoPayManaSourcesKnown = true;
    }

    /** Cards the Auto button would tap, for emphasized highlighting; null when unavailable. */
    private Iterable<CardView> getAutoTapPreviewViews() {
        if (!supportAutoPay() || manaCost == null || manaCost.isPaid()
                || !autoPayManaSourcesKnown || autoPayManaSources == null) {
            return null;
        }
        final Set<CardView> views = new HashSet<>();
        for (Card c : autoPayManaSources) {
            views.add(c.getView());
        }
        return views;
    }

    @Override
    public String toString() {
        return TextUtil.concatNoSpace("PayManaBase ", manaCost.toString(), " left");
    }

    public boolean isPaid() { return bPaid; }

    public boolean isActivatingManaAbility() { return locked; }

    protected String messagePrefix;
    public void setMessagePrefix(String prompt) {
        // TODO Auto-generated method stub
        messagePrefix = prompt;
    }
}
