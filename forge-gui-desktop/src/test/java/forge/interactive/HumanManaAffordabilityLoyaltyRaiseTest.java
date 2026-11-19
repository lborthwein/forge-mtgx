package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.EnumSet;
import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.ComputerUtilMana;
import forge.card.GamePieceType;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.cost.CostAdjustment;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.player.HumanManaAffordability;
import forge.player.HumanManaAffordability.Proof;

/**
 * loyalty-glow-1119: the owner's report of 2026-11-19 (06:42Z, jar 2f7fc66a), "When planeswalkers are activateable
 * nothing indicates they are (no glow etc)". Elspeth, Knight-Errant's loyalty abilities read {@code payableProof:
 * "affordable"} on turns 8-16, then {@code "unknown"} from turn 18, once Elite Spellbinder's "costs {2} more" effect was
 * in the command zone. Any raise, reduce or set static in the game sends the proof through Forge's presentation price,
 * which gave up on any cost part other than mana or {T}: a loyalty [+1] is a counter part. The client fails closed on
 * {@code "unknown"}, so nothing glowed.
 *
 * <p>The rule now: the ability's own non-mana parts no longer make the price unknown (the no-static path never let
 * them); a part a raise adds or changes still does. Each case also checks that the offer ({@code assess}) is the base
 * jar's when {@code -Dforge.afford.baselineJar} names one.</p>
 */
public class HumanManaAffordabilityLoyaltyRaiseTest extends AITest {

    private Game game;
    private Player me;
    private Player foe;

    private void board() {
        game = initAndCreateGame();
        me = game.getPlayers().get(1);
        foe = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
    }

    private Card mine(final String name, final boolean tapped) {
        final Card card = addCard(name, me);
        card.setTapped(tapped);
        card.setSickness(false);
        return card;
    }

    private Card theirs(final String name) {
        final Card card = addCard(name, foe);
        card.setSickness(false);
        return card;
    }

    private Card elspeth(final int loyalty) {
        final Card card = mine("Elspeth, Knight-Errant", false);
        card.setCounters(CounterEnumType.LOYALTY, loyalty);
        return card;
    }

    /** A command-zone effect with one static, as an Effect API resolution leaves it. */
    private Card effect(final Player owner, final Card source, final String... statics) {
        final Card effect = new Card(game.nextCardId(), null, game);
        effect.setOwner(owner);
        effect.setGamePieceType(GamePieceType.EFFECT);
        effect.setName(source.getName() + "'s Effect");
        effect.setEffectSource(source);
        // As EffectEffect does: an effect's statics work from the command zone.
        for (String st : statics) effect.addStaticAbility(st).setActiveZone(EnumSet.of(ZoneType.Command));
        effect.setGameTimestamp(game.getNextTimestamp());
        owner.getZone(ZoneType.Command).add(effect);
        return effect;
    }

    /**
     * Elite Spellbinder's effect after it exiled a card from the foe's hand: the card stays playable by its owner, and
     * a spell cast that way costs {2} more (Elite Spellbinder's own script, its MayPlay and CostsMore statics).
     */
    private Card spellbinderTax() {
        final Card spellbinder = mine("Elite Spellbinder", false);
        final Card exiled = addCardToZone("Tishana's Tidebinder", foe, ZoneType.Exile);
        final Card effect = effect(me, spellbinder,
                "Mode$ Continuous | Affected$ Card.IsRemembered | AffectedZone$ Exile | MayPlay$ True | MayPlayPlayer$ CardOwner",
                "Mode$ RaiseCost | ValidCard$ Card.IsRemembered+CastSa Spell.MayPlaySource | AffectedZone$ Exile | Type$ Spell | Amount$ 2");
        effect.addRemembered(exiled);
        return effect;
    }

    private void settle() {
        game.getAction().checkStateEffects(true);
    }

    /** The loyalty abilities the bridge offers on the card at priority (buildStatefulControls). */
    private List<SpellAbility> loyalty(final Card card) {
        return card.getAllPossibleAbilities(me, true).stream()
                .filter(SpellAbility::isPwAbility)
                .filter(a -> InteractiveGuiGame.passesCastRestrictions(me, a))
                .filter(a -> HumanManaAffordability.mayAfford(me, a)).toList();
    }

    /** Elspeth's two [+1] abilities (her [-8] needs eight loyalty), with each proof and the control's. */
    private List<SpellAbility> plusOnes(final Card elspeth) {
        final List<SpellAbility> offered = loyalty(elspeth);
        assertEquals(offered.size(), 2, "Elspeth's two [+1] abilities are offered: " + offered);
        for (SpellAbility sa : offered) sameOffer(sa);
        return offered;
    }

    private void affordable(final Card elspeth, final String why) {
        final List<SpellAbility> offered = plusOnes(elspeth);
        for (SpellAbility sa : offered) {
            assertEquals(HumanManaAffordability.proof(me, sa), Proof.AFFORDABLE, why + ": " + sa);
            assertTrue(forgePays(sa), why + ": Forge's payer pays " + sa);
        }
        assertEquals(InteractiveGuiGame.controlProof(me, offered), Proof.AFFORDABLE, why + ": the control's proof");
    }

    private boolean forgePays(final SpellAbility sa) {
        return ComputerUtilMana.canPayManaCost(sa, me, 0, false);
    }

    // ------------------------------------------------------------------ the owner's board

    /** Turns 8-16: no cost static anywhere; the no-static path always said "affordable" (passes on the live jar). */
    @Test
    public void owner1119_withNoCostStaticLoyaltyIsAffordable() {
        board();
        for (int i = 0; i < 4; i++) mine("Plains", true);
        final Card elspeth = elspeth(5);
        settle();
        affordable(elspeth, "no static in the game");
    }

    /**
     * Turn 18 on: Elite Spellbinder's "costs {2} more" effect in the seat's command zone. It applies to the exiled card's
     * spell only, never to a loyalty ability, so the abilities stay "affordable" (live jar: "unknown").
     */
    @Test
    public void owner1119_aSpellTaxEffectLeavesLoyaltyAffordable() {
        board();
        for (int i = 0; i < 4; i++) mine("Plains", true);
        final Card elspeth = elspeth(5);
        spellbinderTax();
        settle();
        affordable(elspeth, "Elite Spellbinder's spell tax does not apply to a loyalty ability");
        for (SpellAbility sa : plusOnes(elspeth)) {
            assertEquals(CostAdjustment.presentationManaCost(sa, true), ManaCost.ZERO, "nothing to pay: " + sa);
            // The one-argument price keeps its meaning for its other callers (the offer, X, the preview's mana).
            assertNull(CostAdjustment.presentationManaCost(sa), "the one-argument price is unchanged: " + sa);
        }
    }

    /** The same with a printed "costs {2} more" on the foe's side: Aura of Silence taxes artifact and enchantment spells. */
    @Test
    public void anOpponentsSpellTaxLeavesLoyaltyAffordable() {
        board();
        final Card elspeth = elspeth(4);
        theirs("Aura of Silence");
        settle();
        affordable(elspeth, "Aura of Silence taxes spells only");
    }

    // ------------------------------------------------------------------ a raise that applies

    /** Suppression Field: "Activated abilities cost {2} more to activate unless they're mana abilities." */
    @Test
    public void aRaiseOnActivatedAbilitiesPricesLoyaltyAtTwo() {
        board();
        final Card elspeth = elspeth(4);
        theirs("Suppression Field");
        settle();
        for (SpellAbility sa : plusOnes(elspeth)) {
            final ManaCost price = CostAdjustment.presentationManaCost(sa, true);
            assertEquals(price == null ? -1 : price.getCMC(), 2, "{2} more: " + sa);
            assertEquals(price.getGenericCost(), 2, "no coloured pip: " + sa);
            assertNotEquals(HumanManaAffordability.proof(me, sa), Proof.AFFORDABLE, "no mana: " + sa);
            assertFalse(forgePays(sa), "Forge's payer cannot pay {2} with no mana: " + sa);
        }
        mine("Plains", false);
        settle();
        for (SpellAbility sa : plusOnes(elspeth)) {
            assertNotEquals(HumanManaAffordability.proof(me, sa), Proof.AFFORDABLE, "one Plains for {2}: " + sa);
            assertFalse(forgePays(sa), "Forge's payer cannot pay {2} with one Plains: " + sa);
        }
        assertNotEquals(InteractiveGuiGame.controlProof(me, loyalty(elspeth)), Proof.AFFORDABLE);
        mine("Island", false);
        settle();
        affordable(elspeth, "two untapped lands pay {2}");
    }

    // ------------------------------------------------------------------ a reduce static

    /**
     * Zirda, the Dawnwaker: "abilities you activate that aren't mana abilities cost {2} less ... can't reduce the mana in
     * that cost to less than one mana". A loyalty ability has no mana for it to reduce (live jar: "unknown").
     */
    @Test
    public void aReduceStaticLeavesLoyaltyAffordable() {
        board();
        final Card elspeth = elspeth(4);
        mine("Zirda, the Dawnwaker", false);
        settle();
        affordable(elspeth, "Zirda reduces nothing on a cost with no mana");
    }

    /** With mana to reduce, Zirda's floor still makes the price unknown, as before (Mutavault's {1}). */
    @Test
    public void aFlooredReduceOnRealManaStaysUnknown() {
        board();
        mine("Plains", false);
        mine("Plains", false);
        final Card mutavault = mine("Mutavault", false);
        mine("Zirda, the Dawnwaker", false);
        settle();
        final SpellAbility animate = mutavault.getAllPossibleAbilities(me, true).stream()
                .filter(a -> !a.isManaAbility()).findFirst().orElseThrow();
        sameOffer(animate);
        assertNull(CostAdjustment.presentationManaCost(animate, true), "a floored reduction on {1}");
        assertNull(CostAdjustment.presentationManaCost(animate));
        assertEquals(HumanManaAffordability.proof(me, animate), Proof.UNKNOWN);
    }

    // ------------------------------------------------------------------ a raise that adds or changes a non-mana part

    /** An effect making each planeswalker's abilities cost an additional 2 life: a part the ability did not have. */
    @Test
    public void aRaiseThatAddsANonManaPartStaysUnknown() {
        board();
        mine("Plains", false);
        mine("Plains", false);
        final Card elspeth = elspeth(4);
        effect(foe, theirs("Grizzly Bears"),
                "Mode$ RaiseCost | ValidCard$ Planeswalker | Type$ Ability | Cost$ PayLife<2>");
        settle();
        for (SpellAbility sa : plusOnes(elspeth)) {
            assertNull(CostAdjustment.presentationManaCost(sa, true), "a raise added PayLife: " + sa);
            assertEquals(HumanManaAffordability.proof(me, sa), Proof.UNKNOWN, sa.toString());
        }
    }

    /** Carth the Lion's extra [+1] merges into the ability's own counter part: a changed part, so no proof. */
    @Test
    public void aRaiseThatChangesTheOwnPartStaysUnknown() {
        board();
        final Card elspeth = elspeth(4);
        mine("Carth the Lion", false);
        settle();
        for (SpellAbility sa : plusOnes(elspeth)) {
            assertNull(CostAdjustment.presentationManaCost(sa, true), "Carth changed the [+1]: " + sa);
            assertEquals(HumanManaAffordability.proof(me, sa), Proof.UNKNOWN, sa.toString());
        }
    }

    // ------------------------------------------------------------------ a set static on a cost with no mana

    /**
     * Found by {@code InteractivePayableTest}'s random boards (board 67): with the ability's own parts allowed, a cost
     * with no mana part reaches the price. Trinisphere sets Subtlety's evoke ("exile a blue card from your hand") to
     * {3}, as payment does; it is never free.
     */
    @Test
    public void aSetStaticPricesACostWithNoMana() {
        board();
        mine("Island", false);
        final Card subtlety = addCardToZone("Subtlety", me, ZoneType.Hand);
        addCardToZone("Brainstorm", me, ZoneType.Hand);
        theirs("Trinisphere");
        settle();
        final SpellAbility evoke = subtlety.getAllPossibleAbilities(me, true).stream()
                .filter(a -> a.isSpell() && a.getPayCosts().getCostMana() == null).findFirst().orElseThrow();
        sameOffer(evoke);
        final ManaCost price = CostAdjustment.presentationManaCost(evoke, true);
        assertEquals(price == null ? -1 : price.getCMC(), 3, "Trinisphere: three mana");
        assertNotEquals(HumanManaAffordability.proof(me, evoke), Proof.AFFORDABLE, "one Island for {3}");
        assertFalse(forgePays(evoke), "Forge's payer cannot pay {3} with one Island");
        mine("Island", false);
        mine("Plains", false);
        settle();
        assertEquals(HumanManaAffordability.proof(me, evoke), Proof.AFFORDABLE, "three lands for {3}");
        assertTrue(forgePays(evoke), "Forge's payer pays {3} with three lands");
    }

    // ------------------------------------------------------------------ hidden information

    /**
     * The price reads only the battlefield, the stack, the command zone and the host, as before: a Suppression Field in
     * the foe's hidden hand changes nothing, so the proof says nothing of it.
     */
    @Test
    public void aHiddenCardChangesNothing() {
        board();
        final Card elspeth = elspeth(4);
        addCardToZone("Suppression Field", foe, ZoneType.Hand);
        theirs("Aura of Silence");
        settle();
        affordable(elspeth, "the foe's hand is not read");
    }

    // ------------------------------------------------------------------ the offer is the base jar's

    /** {@code HumanManaAffordability.assess} of {@code -Dforge.afford.baselineJar}'s classes; null without the property. */
    private void sameOffer(final SpellAbility sa) {
        final Method baseline = baselineAssess();
        if (baseline == null) return;
        final String live;
        try {
            live = String.valueOf(baseline.invoke(null, me, sa));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("baseline assess failed", e);
        }
        assertEquals(HumanManaAffordability.assess(me, sa).toString(), live, "the offer of " + sa + " is the base jar's");
    }

    private static Method baselineMethod;

    /** The base jar's {@code HumanManaAffordability.assess}, loaded child-first; every other class is this tree's. */
    private static synchronized Method baselineAssess() {
        final String jar = System.getProperty("forge.afford.baselineJar");
        if (jar == null || jar.isBlank()) return null;
        if (baselineMethod == null) {
            try {
                final ClassLoader parent = HumanManaAffordability.class.getClassLoader();
                final URLClassLoader loader = new URLClassLoader(new URL[] {new File(jar).toURI().toURL()}, parent) {
                    @Override
                    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                        if (!name.startsWith("forge.player.HumanManaAffordability")) return super.loadClass(name, resolve);
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> c = findLoadedClass(name);
                            if (c == null) c = findClass(name);
                            if (resolve) resolveClass(c);
                            return c;
                        }
                    }
                };
                baselineMethod = loader.loadClass("forge.player.HumanManaAffordability")
                        .getMethod("assess", Player.class, SpellAbility.class);
            } catch (ReflectiveOperationException | java.io.IOException e) {
                throw new AssertionError("cannot load the baseline jar " + jar, e);
            }
        }
        return baselineMethod;
    }
}
