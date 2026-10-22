package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.ai.ComputerUtilMana;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.player.HumanManaAffordability;
import forge.player.HumanManaAffordability.Assessment;
import forge.player.HumanManaAffordability.Proof;

/**
 * glow-audit-1021: the castable glow ({@code value.payable}) on the owner's game of 2026-10-21 (21:52Z, jar ff3a3bf2),
 * where it was never false: 93 false glows in the kept part of the session.
 *
 * <ul>
 *   <li>83 of them: Urza, Lord High Artificer on the battlefield. Its mana ability costs "tap an untapped artifact you
 *       control" ({@code tapXType}), a cost part the payable proof did not model, so every proof gave up while it was
 *       out, with no artifact to tap.</li>
 *   <li>10 of them: a channel land in hand with no land in play. Its channel ability names its own {@code ReduceCost},
 *       which made the price unknown.</li>
 * </ul>
 *
 * <p>Each owner board asserts no glow AND that Forge's own payer ({@link ComputerUtilMana#canPayManaCost}) cannot pay
 * either: the ground truth. The true positives beside them keep glowing, and Forge's payer pays them.</p>
 *
 * <p>Then the wire's tri-state {@code payableProof}: per preview ability ({@link InteractivePreview}) and per control
 * ({@link InteractiveGuiGame#controlProof}), one case per state.</p>
 */
public class HumanManaAffordabilityGlowAuditTest extends AITest {

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

    private Card theirs(final String name) {
        final Card card = addCard(name, foe);
        card.setSickness(false);
        return card;
    }

    private Card hand(final String name) {
        return addCardToZone(name, me, ZoneType.Hand);
    }

    private void settle() {
        game.getAction().checkStateEffects(true);
    }

    /** The abilities the bridge offers on this card at priority (buildStatefulControls). */
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

    /** The offered non-mana abilities (the ones a glow would be about). */
    private List<SpellAbility> plays(final Card card) {
        return offered(card).stream().filter(a -> !a.isManaAbility()).toList();
    }

    private SpellAbility spell(final Card card) {
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        return sa;
    }

    private boolean forgePays(final SpellAbility sa) {
        return ComputerUtilMana.canPayManaCost(sa, me, 0, false);
    }

    /** No glow: offered with payable false (or not offered), every way proved, and Forge's payer agrees. */
    private void noGlow(final Card card) {
        assertNotEquals(flag(card), Boolean.TRUE, card.getName() + " must not glow");
        for (SpellAbility sa : plays(card)) {
            assertEquals(HumanManaAffordability.assessPayable(me, sa), Assessment.PROVEN_UNAFFORDABLE,
                    card.getName() + ": the payable proof of " + sa);
            assertFalse(forgePays(sa), card.getName() + ": Forge's payer must not pay " + sa + " either");
        }
    }

    /** Glows, and Forge's payer pays some way of it. */
    private void glows(final Card card) {
        assertEquals(flag(card), Boolean.TRUE, card.getName() + " is payable and offered");
        assertTrue(plays(card).stream().anyMatch(this::forgePays), card.getName() + ": Forge's payer pays it");
    }

    private SpellAbility activated(final Card card) {
        for (SpellAbility sa : card.getAllPossibleAbilities(me, true)) {
            if (!sa.isManaAbility() && !sa.isSpell() && !sa.isLandAbility()) return sa;
        }
        throw new AssertionError("no activated ability offered on " + card);
    }

    // ------------------------------------------------------------------ cause 1: the artifact-tapping mana ability

    /** r197, turn 13: every land tapped, Urza untapped with no artifact, the opponent's Thalia out, nothing floating. */
    @Test
    public void owner1021_r197_urzaWithNoArtifactAndEveryLandTapped() {
        board(PhaseType.MAIN1, false);
        mine("Otawara, Soaring City", true);
        mine("Urborg, Tomb of Yawgmoth", true);
        mine("Island", true);
        final Card urza = mine("Urza, Lord High Artificer", false);
        final Card search = hand("Frantic Search");
        final Card revealed = hand("Lórien Revealed");
        theirs("Thalia, Guardian of Thraben");
        theirs("Plains").setTapped(true);
        settle();
        noGlow(search);
        noGlow(revealed);
        noGlow(urza);
    }

    /** r214, turn 14: three lands untapped (Urborg makes each a Swamp), Urza untapped, no artifact, no red source. */
    @Test
    public void owner1021_r214_threeLandsNoRedSource() {
        board(PhaseType.MAIN1, true);
        mine("Otawara, Soaring City", false);
        mine("Urborg, Tomb of Yawgmoth", false);
        mine("Island", false);
        final Card urza = mine("Urza, Lord High Artificer", false);
        final Card breach = hand("Underworld Breach");
        final Card wheel = hand("Wheel of Fortune");
        final Card will = hand("Yawgmoth's Will");
        final Card search = hand("Frantic Search");
        final Card narset = hand("Narset, Parter of Veils");
        settle();
        noGlow(breach);
        noGlow(wheel);
        noGlow(urza);
        // The true positives of the same board.
        glows(will);
        glows(search);
        glows(narset);
        assertEquals(InteractiveGuiGame.controlProof(me, offered(narset)), Proof.AFFORDABLE,
                "{1}{U}{U}: two blue lands and a third land, matched colour by colour");
    }

    /** r239, turn 14: one land untapped and Urza: the {2} artifact and the {2}{B} spell cannot be paid. */
    @Test
    public void owner1021_r239_oneLandLeft() {
        board(PhaseType.MAIN1, true);
        mine("Otawara, Soaring City", true);
        mine("Urborg, Tomb of Yawgmoth", false);
        mine("Island", true);
        mine("Swamp", true);
        mine("Urza, Lord High Artificer", false);
        final Card monolith = hand("Grim Monolith");
        final Card will = hand("Yawgmoth's Will");
        settle();
        noGlow(monolith);
        noGlow(will);
    }

    /** With artifacts to tap, Urza's mana counts again: one per untapped artifact (the true positive). */
    @Test
    public void urzaTapsItsArtifactsForBlue() {
        board(PhaseType.MAIN1, true);
        mine("Island", true);
        mine("Urza, Lord High Artificer", false);
        mine("Sensei's Divining Top", false);
        final Card preordain = hand("Preordain");
        final Card narset = hand("Narset, Parter of Veils");
        settle();
        assertEquals(flag(preordain), Boolean.TRUE, "{U} from tapping the one artifact for Urza");
        // One artifact makes one blue: {1}{U}{U} stays unpayable (the Top's own {T} ability makes no mana).
        noGlow(narset);
        mine("Grim Monolith", false);
        mine("Mox Sapphire", false);
        settle();
        assertEquals(flag(narset), Boolean.TRUE, "three artifacts: three blue from Urza, more from the artifacts themselves");
    }

    // ------------------------------------------------------------------ cause 2: a channel land's own ReduceCost

    /** r5-r13, turns 1-2: no land in play, a channel land in hand (and nothing to return). */
    @Test
    public void owner1021_r5_channelWithNoLandInPlay() {
        board(PhaseType.UPKEEP, false);
        final Card otawara = hand("Otawara, Soaring City");
        hand("Dark Ritual");
        hand("Preordain");
        theirs("Grizzly Bears");
        settle();
        noGlow(otawara);
    }

    /** The lower bound keeps a payable channel glowing: {3}{U} less one per legendary creature. */
    @Test
    public void channelStillGlowsWhenPayable() {
        board(PhaseType.MAIN1, true);
        mine("Island", false);
        mine("Urza, Lord High Artificer", false);
        mine("Thalia, Guardian of Thraben", false);
        mine("Isamaru, Hound of Konda", false);
        final Card otawara = hand("Otawara, Soaring City");
        theirs("Grizzly Bears");
        settle();
        // Three legendary creatures: the channel costs {U}, one Island pays it.
        assertNotEquals(HumanManaAffordability.assessPayable(me, activated(otawara)), Assessment.PROVEN_UNAFFORDABLE);
        assertEquals(flag(otawara), Boolean.TRUE);
        assertTrue(forgePays(activated(otawara)), "Forge's payer pays the reduced channel");
    }

    // ------------------------------------------------------------------ other source shapes now counted

    /** A self-sacrificing source that also discards (Lion's Eye Diamond) counts: three mana, so {4} is proved out. */
    @Test
    public void discardingSourceCounts() {
        board(PhaseType.MAIN1, true);
        mine("Lion's Eye Diamond", false);
        final Card freeze = hand("Brain Freeze");
        final Card wheel = hand("Wheel of Fortune");
        settle();
        assertEquals(flag(freeze), Boolean.TRUE, "{1}{U} from three of one colour");
        assertEquals(flag(wheel), Boolean.TRUE, "{2}{R}");
        final Card big = hand("Lórien Revealed");
        settle();
        assertEquals(HumanManaAffordability.assessPayable(me, spell(big)), Assessment.PROVEN_UNAFFORDABLE,
                "{3}{U}{U} with three mana in reach");
        assertFalse(forgePays(spell(big)));
    }

    // ------------------------------------------------------------------ an untap ability usable later, not now

    /**
     * Owner report 2026-10-22T00-08-43, turn 7, the OPPONENT's end step: every land and the two-mana artifact tapped,
     * nothing floating; two Map tokens and a planeswalker whose +1 untaps a land (and whose mana trigger adds {G} per
     * Forest) untapped. A {1}{G} instant glowed: the untap made the proof unknown, though a loyalty ability cannot be
     * activated on the opponent's turn.
     */
    private Card nissaEndStepBoard() {
        board(PhaseType.END_OF_TURN, false);
        mine("Forest", true);
        mine("Tropical Island", true);
        mine("Mana Crypt", true);
        mine("Forest", true);
        addToken("c_a_map_sac_explore", me);
        addToken("c_a_map_sac_explore", me);
        final Card nissa = mine("Nissa, Who Shakes the World", false);
        nissa.setCounters(forge.game.card.CounterEnumType.LOYALTY, 5);
        hand("Forest");
        final Card asunder = hand("Tear Asunder");
        theirs("Plains").setTapped(true);
        theirs("Mountain").setTapped(true);
        settle();
        return asunder;
    }

    @Test
    public void owner1022_000843_loyaltyUntapOnTheOpponentsEndStep() {
        final Card asunder = nissaEndStepBoard();
        assertEquals(flag(asunder), Boolean.FALSE, "{1}{G} with nothing untapped that makes mana, now");
        noGlow(asunder);
        assertEquals(HumanManaAffordability.proof(me, offered(asunder).get(0)), Proof.UNAFFORDABLE);
        assertEquals(controlProof(asunder), Proof.UNAFFORDABLE);
    }

    /** The same board in the owner's own main phase: the +1 can untap a Forest first, so no proof (it may be paid). */
    @Test
    public void loyaltyUntapInOwnMainPhaseStaysUnknown() {
        nissaEndStepBoard();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        final Card asunder = me.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getName().equals("Tear Asunder"))
                .findFirst().orElseThrow();
        settle();
        assertEquals(flag(asunder), Boolean.TRUE, "untap a Forest with the +1, tap it for {G}{G}");
        assertNotEquals(controlProof(asunder), Proof.UNAFFORDABLE);
    }

    /** Once the loyalty ability has been used this turn, it untaps nothing more: proved again in the main phase. */
    @Test
    public void loyaltyAlreadyUsedThisTurn() {
        nissaEndStepBoard();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        final Card nissa = me.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.getName().startsWith("Nissa")).findFirst().orElseThrow();
        nissa.addPlaneswalkerAbilityActivated();
        final Card asunder = me.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getName().equals("Tear Asunder"))
                .findFirst().orElseThrow();
        settle();
        assertEquals(flag(asunder), Boolean.FALSE);
    }

    /** The mana trigger on Forests matters once a Forest is untapped: then the price is not proved either way. */
    @Test
    public void manaTriggerWithAnUntappedForest() {
        nissaEndStepBoard();
        mine("Forest", false);
        final Card asunder = me.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getName().equals("Tear Asunder"))
                .findFirst().orElseThrow();
        settle();
        assertEquals(flag(asunder), Boolean.TRUE, "one Forest makes {G}{G} with the trigger");
        assertTrue(forgePays(offered(asunder).get(0)));
    }

    // ------------------------------------------------------------------ the unknown log never names a hidden card

    @Test
    public void unknownLogNamesOnlyPublicCards() {
        board(PhaseType.MAIN1, true);
        final Card inHand = hand("Tear Asunder");
        final Card inPlay = mine("Forest", false);
        settle();
        final String hidden = HumanManaAffordability.unknownLine("test", inHand);
        assertFalse(hidden.contains("Tear Asunder"), hidden);
        assertTrue(hidden.contains("\"card\":" + inHand.getId()), hidden);
        final String shown = HumanManaAffordability.unknownLine("test", inPlay);
        assertTrue(shown.contains("\"name\":\"Forest\""), shown);
        // And the live path: a hand card's proof that gives up prints its id, never its name.
        final java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        final java.io.PrintStream was = System.err;
        System.setErr(new java.io.PrintStream(err, true));
        try {
            HumanManaAffordability.unknown("glow-audit-hidden-" + inHand.getId(), inHand);
        } finally {
            System.setErr(was);
        }
        assertTrue(err.toString().contains("afford-unknown"), err.toString());
        assertFalse(err.toString().contains("Tear Asunder"), err.toString());
    }

    // ------------------------------------------------------------------ payableProof, per control

    private Proof controlProof(final Card card) {
        return InteractiveGuiGame.controlProof(me, offered(card));
    }

    @Test
    public void controlProofAffordable() {
        board(PhaseType.MAIN1, true);
        mine("Island", false);
        mine("Mountain", false);
        final Card search = hand("Frantic Search");
        settle();
        // {2}{U} needs three: only two (the plain proof hides it: no control; the per-ability proof says so).
        assertEquals(HumanManaAffordability.proof(me, spell(search)), Proof.UNAFFORDABLE);
        final Card consider = hand("Consider");
        settle();
        assertEquals(controlProof(consider), Proof.AFFORDABLE, "{U}: the Island");
        final Card freeze = hand("Brain Freeze");
        settle();
        assertEquals(controlProof(freeze), Proof.AFFORDABLE, "{1}{U}: Island for blue, Mountain for the generic");
    }

    /** The addendum's case: {U}{U} with an untapped Island and Mountain is never "affordable" (here: proved not). */
    @Test
    public void controlProofColourCorrect() {
        board(PhaseType.MAIN1, true);
        mine("Island", false);
        mine("Mountain", false);
        final Card counterspell = hand("Counterspell");
        settle();
        assertNotEquals(controlProof(counterspell), Proof.AFFORDABLE);
        assertEquals(HumanManaAffordability.proof(me, spell(counterspell)), Proof.UNAFFORDABLE);
        mine("Island", false);
        settle();
        assertEquals(controlProof(counterspell), Proof.AFFORDABLE, "two Islands");
    }

    @Test
    public void controlProofUnknown() {
        board(PhaseType.MAIN1, true);
        mine("Island", true);
        mine("Urza, Lord High Artificer", false);
        mine("Sensei's Divining Top", false);
        final Card preordain = hand("Preordain");
        settle();
        // Payable only through Urza's artifact tap: not a sure source, so not proved either way.
        assertEquals(flag(preordain), Boolean.TRUE);
        assertEquals(controlProof(preordain), Proof.UNKNOWN);
    }

    /** Offered (a Spirit Guide in hand leaves the plain proof unsure) but proved unpayable: red cannot pay {U}. */
    @Test
    public void controlProofUnaffordable() {
        board(PhaseType.MAIN1, true);
        mine("Island", true);
        hand("Simian Spirit Guide");
        final Card preordain = hand("Preordain");
        settle();
        assertEquals(flag(preordain), Boolean.FALSE);
        assertEquals(controlProof(preordain), Proof.UNAFFORDABLE);
    }

    /** Spec addendum 3: X is priced at max(XMin, 1) for "affordable"; X = 0 never makes it affordable. */
    @Test
    public void xIsPricedAtOneForAffordable() {
        board(PhaseType.MAIN1, true);
        mine("Island", true);
        final Card candelabra = mine("Candelabra of Tawnos", false);
        settle();
        final SpellAbility untap = activated(candelabra);
        assertNotEquals(HumanManaAffordability.proof(me, untap), Proof.AFFORDABLE, "{X},{T} with no mana: X = 0 is no proof");
        mine("Plains", false);
        settle();
        assertEquals(HumanManaAffordability.proof(me, activated(candelabra)), Proof.AFFORDABLE, "X = 1 from the Plains");
    }

    // ------------------------------------------------------------------ payableProof, per preview ability

    private JsonObject preview(final Card card) {
        final InteractivePreview.Seat view = new InteractivePreview.Seat() {
            @Override public String abilityLabel(final SpellAbility ability) { return String.valueOf(ability.getDescription()); }
            @Override public String text(final String text) { return String.valueOf(text); }
            @Override public boolean mayList(final Card c) { return true; }
            @Override public int seatOf(final Player player) { return game.getRegisteredPlayers().indexOf(player); }
        };
        final InteractivePreview.Pass pass = new InteractivePreview.Pass(Long.MAX_VALUE / 4);
        final List<SpellAbility> possible = card.getAllPossibleAbilities(me, true);
        return InteractivePreview.forCard(pass, me, card, possible, a -> true, view);
    }

    /** kind -> payableProof of each entry, in order ("land" entries carry none: "-"). */
    private List<String> proofs(final Card card) {
        final List<String> out = new ArrayList<>();
        for (JsonElement e : preview(card).getAsJsonArray("abilities")) {
            final JsonObject a = e.getAsJsonObject();
            out.add(a.get("kind").getAsString() + ":" + (a.has("payableProof") ? a.get("payableProof").getAsString() : "-"));
        }
        return out;
    }

    /** A manland's activation is judged by itself: its own mana ability is affordable, the animation is not proved. */
    @Test
    public void previewProofPerAbility() {
        board(PhaseType.MAIN1, true);
        mine("Island", true);
        final Card factory = mine("Mutavault", false);
        settle();
        final List<String> alone = proofs(factory);
        assertTrue(alone.contains("mana:affordable"), alone.toString());
        // {1}: only the Factory itself could pay, and the host is never counted as its own source.
        assertTrue(alone.contains("activated:unknown"), alone.toString());
        mine("Island", false);
        settle();
        final List<String> withIsland = proofs(factory);
        assertTrue(withIsland.contains("activated:affordable"), withIsland.toString());
    }

    @Test
    public void previewProofUnaffordable() {
        board(PhaseType.MAIN1, true);
        mine("Island", false);
        mine("Swamp", false);
        mine("Island", false);
        final Card urza = mine("Urza, Lord High Artificer", false);
        settle();
        final List<String> got = proofs(urza);
        assertTrue(got.contains("activated:unaffordable"), "{5} with three lands: " + got);
        final Card land = hand("Island");
        settle();
        assertEquals(proofs(land), List.of("land:-"), "a land play carries no proof");
    }
}
