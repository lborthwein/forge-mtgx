package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.player.HumanManaAffordability;
import forge.player.HumanManaAffordability.Assessment;

/**
 * afford-jar-1021: the castable glow ({@code value.payable}) on the owner's boards, the Chromatic Star spec
 * (star-affordability/spec.md, 5 cases) and #1109's free / life / tap-only regressions.
 *
 * <p>The owner boards 2026-10-21T16-49-11 and 16-50-10: the opponent's Kinnan, Bonder Prodigy ("Whenever YOU tap a
 * nonland permanent for mana, add one mana ...", {@code Activator$ You}) is a TapsForMana trigger on the battlefield.
 * Both proof loops gave up (UNKNOWN = payable) on ANY such trigger in play, whoever controlled it and whatever it could
 * see, so every card of the human glowed while the AI's Kinnan was out.</p>
 *
 * <p>{@code flag} is what the bridge publishes for a card: null when the card is not offered at all (proven
 * unaffordable by {@link HumanManaAffordability#assess}), else {@link InteractiveGuiGame#controlPayable}.</p>
 */
public class HumanManaAffordabilityOwnerBoardsTest extends AITest {

    private Game game;
    private Player me;
    private Player foe;

    private void board(final PhaseType phase, final boolean mine) {
        game = initAndCreateGame();
        me = game.getPlayers().get(1);
        foe = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(phase, mine ? me : foe);
    }

    private Card mine(final String name, final boolean tapped) {
        final Card card = addCard(name, me);
        card.setTapped(tapped);
        card.setSickness(false);
        return card;
    }

    private Card theirs(final String name, final boolean tapped) {
        final Card card = addCard(name, foe);
        card.setTapped(tapped);
        return card;
    }

    private Card hand(final String name) {
        return addCardToZone(name, me, ZoneType.Hand);
    }

    private void settle() {
        game.getAction().checkStateEffects(true);
    }

    private SpellAbility spell(final Card card) {
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        return sa;
    }

    private SpellAbility manaAbility(final Card card, final boolean withManaCost) {
        for (SpellAbility sa : card.getManaAbilities()) {
            if ((sa.getPayCosts().getCostMana() != null) == withManaCost) {
                sa.setActivatingPlayer(me);
                return sa;
            }
        }
        throw new AssertionError("no such mana ability on " + card);
    }

    private List<SpellAbility> offered(final Card card) {
        return card.getAllPossibleAbilities(me, true).stream()
                .filter(a -> InteractiveGuiGame.passesCastRestrictions(me, a))
                .filter(a -> HumanManaAffordability.mayAfford(me, a)).toList();
    }

    /** Null: not offered. Else the published payable flag. */
    private Boolean flag(final Card card) {
        final List<SpellAbility> offered = offered(card);
        return offered.isEmpty() ? null : InteractiveGuiGame.controlPayable(me, offered);
    }

    private void glows(final Card card) {
        assertEquals(flag(card), Boolean.TRUE, card.getName() + " is payable and offered");
    }

    /** Not glowing: either not offered at all or offered with payable false, and the proof says so. */
    private void doesNotGlow(final Card card) {
        assertNotEquals(flag(card), Boolean.TRUE, card.getName() + " must not glow");
        assertEquals(HumanManaAffordability.assessPayable(me, spell(card)), Assessment.PROVEN_UNAFFORDABLE,
                card.getName() + ": the payable proof");
        assertTrue(!HumanManaAffordability.payable(me, spell(card)), card.getName());
    }

    private void protectionFromEverything() {
        me.addChangedKeywords(List.of("Protection from everything"), List.of(), game.getNextTimestamp(), 0);
        settle();
    }

    private void kinnanAndLotus() {
        theirs("Breeding Pool", true);
        theirs("Forest", true);
        theirs("Black Lotus", false);
        theirs("Kinnan, Bonder Prodigy", false);
    }

    // ------------------------------------------------------------------ the four owner boards

    /** 2026-10-19T19-11-01, turn 7 main 1: every land and The One Ring tapped, its protection on, nothing floating. */
    @Test
    public void owner1019_191101_ringTurnNothingGlows() {
        board(PhaseType.MAIN1, true);
        mine("Island", true);
        mine("Island", true);
        mine("Swamp", true);
        mine("Recurring Nightmare", false);
        mine("Swamp", true);
        mine("The One Ring", true).setCounters(CounterEnumType.BURDEN, 1);
        final Card fow = hand("Force of Will");
        hand("Karakas");
        hand("Island");
        final Card talisman = hand("Talisman of Dominance");
        final Card damnation = hand("Damnation");
        addCardToZone("Gitaxian Probe", me, ZoneType.Graveyard);
        theirs("Forest", true);
        theirs("Retrofitter Foundry", false);
        theirs("Forest", true);
        theirs("Porcelain Legionnaire", true);
        theirs("Plains", true);
        me.setLife(17, null);
        protectionFromEverything();
        for (Card card : List.of(fow, talisman, damnation)) doesNotGlow(card);
    }

    /** 2026-10-19T19-28-43, turn 5 main 2: Swamp, Island, Ancient Tomb and The One Ring tapped, protection on. */
    @Test
    public void owner1019_192843_ringTurnNothingGlows() {
        board(PhaseType.MAIN2, true);
        mine("Swamp", true);
        mine("Island", true);
        mine("Ancient Tomb", true);
        mine("The One Ring", true).setCounters(CounterEnumType.BURDEN, 1);
        final Card defiler = hand("Chaos Defiler");
        final Card fow = hand("Force of Will");
        hand("Island");
        hand("Swamp");
        hand("Island");
        final Card vamp = hand("Vampiric Tutor");
        theirs("Raging Ravine", true);
        theirs("Mountain", true);
        theirs("Kumano Faces Kakkazan", false);
        me.setLife(15, null);
        protectionFromEverything();
        for (Card card : List.of(defiler, fow, vamp)) doesNotGlow(card);
    }

    /** 2026-10-21T16-49-11, turn 3 (the AI's) end step: the only land, an Island, is tapped; the AI has Kinnan. */
    @Test
    public void owner1021_164911_tappedIslandOpponentKinnan() {
        board(PhaseType.END_OF_TURN, false);
        mine("Island", true);
        final Card snap = hand("Snapcaster Mage");
        hand("Urborg, Tomb of Yawgmoth");
        hand("Swamp");
        hand("Underworld Breach");
        final Card consider = hand("Consider");
        hand("Urza, Lord High Artificer");
        hand("Swamp");
        addCardToZone("Troll of Khazad-dûm", me, ZoneType.Graveyard);
        kinnanAndLotus();
        settle();
        doesNotGlow(snap);
        doesNotGlow(consider);
        assertEquals(HumanManaAffordability.assess(me, spell(consider)), Assessment.PROVEN_UNAFFORDABLE,
                "the opponent's Kinnan acts only on mana its controller makes");
    }

    /** 16-49-11's control: the same board without Kinnan was already proven. */
    @Test
    public void owner1021_164911_withoutKinnan() {
        board(PhaseType.END_OF_TURN, false);
        mine("Island", true);
        final Card consider = hand("Consider");
        hand("Urza, Lord High Artificer");
        addCardToZone("Troll of Khazad-dûm", me, ZoneType.Graveyard);
        theirs("Black Lotus", false);
        settle();
        doesNotGlow(consider);
    }

    /** 2026-10-21T16-50-10, turn 4 main 1: Island and Urborg untapped. The colour and amount case. */
    @Test
    public void owner1021_165010_islandAndUrborg() {
        board(PhaseType.MAIN1, true);
        mine("Island", false);
        mine("Urborg, Tomb of Yawgmoth", false);
        final Card snap = hand("Snapcaster Mage");
        hand("Swamp");
        final Card breach = hand("Underworld Breach");
        final Card consider = hand("Consider");
        final Card urza = hand("Urza, Lord High Artificer");
        hand("Swamp");
        final Card map = hand("Expedition Map");
        addCardToZone("Troll of Khazad-dûm", me, ZoneType.Graveyard);
        kinnanAndLotus();
        settle();
        glows(snap);
        glows(consider);
        glows(map);
        doesNotGlow(breach);   // {1}{R}: no red source
        doesNotGlow(urza);     // {2}{U}{U}: two lands make two mana
    }

    /**
     * 2026-10-21T16-57-44, turn 9, the AI's end step: the only untapped source is an Island. Snapcaster ({1}{U}) and
     * Dark Depths' {3} were marked payable, so autopass stopped at every step. None of the offers is payable; the
     * Island's own mana control (manaOnly, which the client never counts as an action) stays payable.
     */
    @Test
    public void owner1021_165744_oneIslandNothingCastable() {
        board(PhaseType.END_OF_TURN, false);
        final Card island = mine("Island", false);
        mine("Urborg, Tomb of Yawgmoth", true);
        final Card depths = mine("Dark Depths", true);
        depths.setCounters(CounterEnumType.ICE, 10);
        mine("Raugrin Triome", true);
        final Card snap = hand("Snapcaster Mage");
        hand("Swamp");
        hand("Underworld Breach");
        hand("Urza, Lord High Artificer");
        hand("Swamp");
        hand("Swamp");
        for (String n : new String[] {"Troll of Khazad-dûm", "Preordain", "Consider", "Pentad Prism", "Expedition Map"}) {
            addCardToZone(n, me, ZoneType.Graveyard);
        }
        for (String n : new String[] {"Breeding Pool", "Forest", "Black Lotus", "Kinnan, Bonder Prodigy", "Tropical Island",
                "Forest"}) {
            theirs(n, true);
        }
        theirs("Oko, Thief of Crowns", false).setCounters(CounterEnumType.LOYALTY, 9);
        theirs("Joraga Treespeaker", false);
        theirs("Fyndhorn Elves", false);
        me.setLife(3, null);
        settle();
        doesNotGlow(snap);
        final SpellAbility thaw = depths.getSpellAbilities().stream()
                .filter(sa -> !sa.isManaAbility() && !sa.isLandAbility() && sa.getPayCosts() != null
                        && sa.getPayCosts().getCostMana() != null)
                .findFirst().orElseThrow();
        thaw.setActivatingPlayer(me);
        assertEquals(HumanManaAffordability.assessPayable(me, thaw), Assessment.PROVEN_UNAFFORDABLE, "Dark Depths' {3}");
        assertNotEquals(flag(depths), Boolean.TRUE, "Dark Depths must not glow");
        assertEquals(flag(island), Boolean.TRUE, "the Island's own mana ability");
    }

    /**
     * 2026-10-21T17-16-51, turn 1, the AI's main phase with its Ignoble Hierarch on the stack: no lands in play, an empty
     * pool. The only offer was Raugrin Triome's cycling ({3}, discard it); the table paused on the stack (client
     * #1250 makes autopass skip payable:false offers). The jar must keep it false, and nothing in hand may glow.
     */
    @Test
    public void owner1021_171651_cyclingLandWithNoLands() {
        board(PhaseType.MAIN1, false);
        theirs("Forest", true);
        final Card triome = hand("Raugrin Triome");
        final List<Card> rest = List.of(hand("Walking Ballista"), hand("Preordain"), hand("Island"), hand("Wheel of Fortune"),
                hand("Sol Ring"), hand("Tendrils of Agony"));
        settle();
        final SpellAbility cycling = triome.getSpellAbilities().stream()
                .filter(sa -> !sa.isLandAbility() && !sa.isSpell() && !sa.isManaAbility()).findFirst().orElseThrow();
        cycling.setActivatingPlayer(me);
        assertEquals(HumanManaAffordability.assessPayable(me, cycling), Assessment.PROVEN_UNAFFORDABLE, "cycling {3}");
        assertEquals(flag(triome), Boolean.FALSE, "the Triome's control: offered (cycling), not payable");
        for (Card card : rest) assertNotEquals(flag(card), Boolean.TRUE, card.getName() + " must not glow");
    }

    // ------------------------------------------------------------------ scoping of the mana hooks

    /** My own Kinnan sees only nonland permanents I tap for mana: with lands alone the proof stands. */
    @Test
    public void ownKinnanWithLandsOnly() {
        board(PhaseType.MAIN1, true);
        mine("Island", false);
        mine("Kinnan, Bonder Prodigy", false);
        final Card urza = hand("Urza, Lord High Artificer");
        final Card consider = hand("Consider");
        settle();
        doesNotGlow(urza);
        glows(consider);
    }

    /** My own Kinnan with a Signet can add mana: unknown, so it glows. */
    @Test
    public void ownKinnanWithASignetStaysUnknown() {
        board(PhaseType.MAIN1, true);
        mine("Island", false);
        mine("Azorius Signet", false);
        mine("Kinnan, Bonder Prodigy", false);
        final Card cancel = hand("Cancel");
        settle();
        assertEquals(HumanManaAffordability.assessPayable(me, spell(cancel)), Assessment.UNKNOWN);
        glows(cancel);
    }

    /** The opponent's Mana Flare acts on every land: unknown while I have an untapped land, a proof with none. */
    @Test
    public void opponentsManaFlare() {
        board(PhaseType.MAIN1, true);
        mine("Island", false);
        theirs("Mana Flare", false);
        final Card cancel = hand("Cancel");
        settle();
        assertEquals(HumanManaAffordability.assessPayable(me, spell(cancel)), Assessment.UNKNOWN);
        board(PhaseType.MAIN1, true);
        mine("Island", true);
        theirs("Mana Flare", false);
        final Card consider = hand("Consider");
        settle();
        doesNotGlow(consider);
    }

    /**
     * Review S1: Vizier of the Menagerie's ManaConvert has {@code ValidCard$ Creature.YouCtrl}, which Forge tests at
     * payment, with the spell on the stack under the caster's control. An opponent-owned creature card in exile that
     * the human may cast (Praetor's Grasp) fails it where it sits now; the proof must still count the conversion.
     */
    @Test
    public void vizierConvertsForAnOpponentsCardCastFromExile() {
        board(PhaseType.MAIN1, true);
        mine("Vizier of the Menagerie", false);
        mine("Forest", false);
        final Card delver = addCardToZone("Delver of Secrets", foe, ZoneType.Exile);   // {U}, owned by the opponent
        settle();
        final SpellAbility cast = spell(delver);
        assertNotEquals(HumanManaAffordability.assessPayable(me, cast), Assessment.PROVEN_UNAFFORDABLE,
                "Vizier lets the Forest's {G} pay {U} for a creature spell");
        assertNotEquals(HumanManaAffordability.assess(me, cast), Assessment.PROVEN_UNAFFORDABLE);
        assertTrue(HumanManaAffordability.payable(me, cast));
        // The proof still stands with no mana to convert.
        board(PhaseType.MAIN1, true);
        mine("Vizier of the Menagerie", false);
        mine("Forest", true);
        final Card delver2 = addCardToZone("Delver of Secrets", foe, ZoneType.Exile);
        settle();
        assertEquals(HumanManaAffordability.assessPayable(me, spell(delver2)), Assessment.PROVEN_UNAFFORDABLE);
    }

    /** Cycling, landcycling and a hand creature's mana ability are not sources. */
    @Test
    public void handAndGraveyardAbilitiesAreNotSources() {
        board(PhaseType.MAIN1, true);
        mine("Island", true);
        hand("Urza, Lord High Artificer");
        hand("Troll of Khazad-dûm");
        addCardToZone("Troll of Khazad-dûm", me, ZoneType.Graveyard);
        addCardToZone("Lotus Petal", me, ZoneType.Graveyard);
        final Card consider = hand("Consider");
        settle();
        doesNotGlow(consider);
    }

    // ------------------------------------------------------------------ star-affordability spec (5 cases)

    private Card starBoard(final boolean extraIsland) {
        board(PhaseType.COMBAT_DECLARE_BLOCKERS, false);
        mine("Urborg, Tomb of Yawgmoth", true);
        mine("Island", true);
        mine("Swamp", true);
        if (extraIsland) mine("Island", false);
        return mine("Chromatic Star", false);
    }

    /** Case 1: the only untapped source is the Star, and its {1} cannot be made. */
    @Test
    public void star1_starAloneIsNotASource() {
        final Card star = starBoard(false);
        final Card tutor = hand("Mystical Tutor");
        hand("Gush");
        hand("Relic of Progenitus");
        hand("Doomsday");
        settle();
        doesNotGlow(tutor);
        assertEquals(HumanManaAffordability.assessPayable(me, manaAbility(star, true)), Assessment.PROVEN_UNAFFORDABLE);
        assertEquals(flag(star), Boolean.FALSE, "the Star's control: its own {1} cannot be paid");
    }

    /** Case 1 with cost reducers: one that may reach a mana ability gives up; Zirda's (not mana abilities) does not. */
    @Test
    public void star1_costReducers() {
        starBoard(false);
        mine("Training Grounds", false);
        final Card tutor = hand("Mystical Tutor");
        settle();
        assertNotEquals(HumanManaAffordability.assessPayable(me, spell(tutor)), Assessment.PROVEN_UNAFFORDABLE,
                "Training Grounds may lower an activated ability's cost");
        starBoard(false);
        mine("Zirda, the Dawnwaker", false);
        final Card tutor2 = hand("Mystical Tutor");
        settle();
        doesNotGlow(tutor2);
    }

    /** Case 2: one more untapped Island pays the Star, and Tutor either way. */
    @Test
    public void star2_withAnIsland() {
        final Card star = starBoard(true);
        final Card tutor = hand("Mystical Tutor");
        settle();
        glows(tutor);
        assertEquals(flag(star), Boolean.TRUE);
    }

    /** Case 3: a Signet with one untapped land: two mana, not three. */
    @Test
    public void star3_signet() {
        board(PhaseType.MAIN1, true);
        mine("Island", false);
        mine("Azorius Signet", false);
        final Card snap = hand("Snapcaster Mage");
        final Card cancel = hand("Cancel");
        settle();
        glows(snap);
        doesNotGlow(cancel);
    }

    /** Case 4: a filter land or filter artifact with no other source. */
    @Test
    public void star4_filtersAlone() {
        board(PhaseType.MAIN1, true);
        final Card gate = mine("Mystic Gate", false);
        final Card consider = hand("Consider");
        settle();
        doesNotGlow(consider);
        assertEquals(HumanManaAffordability.assessPayable(me, manaAbility(gate, true)), Assessment.PROVEN_UNAFFORDABLE,
                "the filter's {W/U} needs another source");
        assertTrue(!HumanManaAffordability.payable(me, manaAbility(gate, true)));

        board(PhaseType.MAIN1, true);
        final Card lens = mine("Prismatic Lens", false);
        final Card consider2 = hand("Consider");
        final Card map = hand("Expedition Map");
        settle();
        doesNotGlow(consider2);
        glows(map);
        assertEquals(HumanManaAffordability.assessPayable(me, manaAbility(lens, true)), Assessment.PROVEN_UNAFFORDABLE);

        board(PhaseType.MAIN1, true);
        mine("Mystic Gate", false);
        mine("Island", false);
        final Card counterspell = hand("Counterspell");
        settle();
        glows(counterspell);   // Island pays the filter's {W/U}: UU
    }

    /** Case 5: free mana abilities and #1109's life / tap-only shapes are unchanged. */
    @Test
    public void star5_regressions() {
        board(PhaseType.MAIN1, true);
        mine("Mox Sapphire", false);
        final Card consider = hand("Consider");
        settle();
        glows(consider);

        board(PhaseType.MAIN1, true);
        mine("Lotus Petal", false);
        final Card consider2 = hand("Consider");
        final Card snap = hand("Snapcaster Mage");
        settle();
        glows(consider2);
        doesNotGlow(snap);

        board(PhaseType.MAIN1, true);
        mine("Horizon Canopy", false);
        final Card growth = hand("Giant Growth");
        settle();
        glows(growth);   // {T}, pay 1 life: add {G} or {W}

        board(PhaseType.MAIN1, true);
        mine("Phyrexian Altar", false);
        mine("Grizzly Bears", false);
        final Card consider3 = hand("Consider");
        settle();
        assertNotEquals(HumanManaAffordability.assessPayable(me, spell(consider3)), Assessment.PROVEN_UNAFFORDABLE,
                "Phyrexian Altar sacrifices a creature for any colour");
        glows(consider3);

        board(PhaseType.MAIN1, true);
        mine("Devoted Druid", false);
        final Card wurm = hand("Garruk's Companion");   // {G}{G}: tap, untap with a -1/-1 counter, tap again
        settle();
        assertNotEquals(HumanManaAffordability.assessPayable(me, spell(wurm)), Assessment.PROVEN_UNAFFORDABLE,
                "Devoted Druid untaps itself");
        glows(wurm);
    }
}
