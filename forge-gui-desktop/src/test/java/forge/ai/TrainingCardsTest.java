package forge.ai;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.card.CardEdition;
import forge.card.CardRules;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.model.FModel;

/**
 * Lane training-cards-fork-1028: the seven lesson-only cards of mtgx's Chapter 1 (design training-cards-1027), printed
 * only in the custom edition "Cube of Legends Training" (XCLT, Type=Other). One rules test per card: the vanilla
 * creatures' cost, types and stats; the removal's tapped-only target; the mass tap's toughness threshold; the pump's
 * end-of-turn duration. Every spell is a sorcery and its rules text is one sentence of 12 words or fewer (mtgx
 * mtga-ch1 parity, "Text length"). The isolation proof is {@link TrainingCardsIsolationTest}.
 */
public class TrainingCardsTest extends AITest {

    static final String EDITION = "XCLT";
    static final String[] NAMES = {"Rampart Sentry", "Temple Spearman", "Weary Foe's End", "Noonday Glare",
            "Steppe Gazelle", "Steppe Bull", "Wildborn Surge"};

    /** The card's only printing is in XCLT; returns its rules. */
    private static CardRules rules(final String name) {
        final List<PaperCard> prints = FModel.getMagicDb().getCommonCards().getAllCards(name);
        Assert.assertFalse(prints.isEmpty(), name + " is loaded");
        for (PaperCard p : prints) {
            Assert.assertEquals(p.getEdition(), EDITION, name + " is printed only in " + EDITION);
        }
        return prints.get(0).getRules();
    }

    /** A vanilla creature: cost, colour, one creature type, P/T, empty rules text, no keyword and no ability. */
    private void vanilla(final String name, final String cost, final byte color, final String subtype, final int power,
            final int toughness) {
        final CardRules r = rules(name);
        Assert.assertEquals(r.getManaCost().toString(), cost, name + " cost");
        Assert.assertEquals(r.getColor().getColor(), color, name + " colour");
        Assert.assertTrue(r.getType().isCreature(), name + " is a creature");
        Assert.assertEquals(r.getType().getCreatureTypes().size(), 1, name + " has one creature type");
        Assert.assertTrue(r.getType().hasSubtype(subtype), name + " is a " + subtype);
        Assert.assertEquals(r.getIntPower(), power, name + " power");
        Assert.assertEquals(r.getIntToughness(), toughness, name + " toughness");
        Assert.assertEquals(r.getOracleText(), "", name + " has no rules text");

        final Game game = initAndCreateGame();
        final Player me = game.getPlayers().get(1);
        final Card c = addCard(name, me);
        game.getAction().checkStateEffects(true);
        Assert.assertEquals(c.getNetPower(), power, name + " on the battlefield: power");
        Assert.assertEquals(c.getNetToughness(), toughness, name + " on the battlefield: toughness");
        Assert.assertEquals(c.getCMC(), r.getManaCost().getCMC(), name + " mana value");
        Assert.assertTrue(c.getKeywords().isEmpty(), name + " has no keyword");
        Assert.assertTrue(c.getManaAbilities().isEmpty(), name + " makes no mana");
        Assert.assertTrue(c.getTriggers().isEmpty(), name + " has no trigger");
        Assert.assertTrue(c.getStaticAbilities().isEmpty(), name + " has no static ability");
        for (SpellAbility sa : c.getSpellAbilities()) {
            Assert.assertTrue(sa.isSpell(), name + " has no activated ability: " + sa);
        }
    }

    /** A sorcery whose rules text is the one sentence {@code text}; returns its spell ability from {@code me}'s hand. */
    private SpellAbility sorcery(final String name, final String cost, final String text, final Player me) {
        final CardRules r = rules(name);
        Assert.assertEquals(r.getManaCost().toString(), cost, name + " cost");
        Assert.assertTrue(r.getType().isSorcery(), name + " is a sorcery");
        Assert.assertEquals(r.getOracleText(), text, name + " rules text");
        Assert.assertEquals(text.split("\\.").length, 1, name + ": one sentence");
        Assert.assertTrue(text.split(" ").length <= 12, name + ": 12 words or fewer");

        final Card card = addCardToZone(name, me, ZoneType.Hand);
        final List<SpellAbility> spells = new java.util.ArrayList<>(card.getSpells());
        Assert.assertEquals(spells.size(), 1, name + " has one spell");
        final SpellAbility sa = spells.get(0);
        sa.setActivatingPlayer(me);
        return sa;
    }

    /** Sorcery timing: castable in its controller's main phase, not in the opponent's. */
    private void sorceryTiming(final Game game, final SpellAbility sa, final Player me, final Player opp) {
        Assert.assertTrue(sa.canPlay(), sa.getHostCard() + ": castable in its controller's main phase");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        Assert.assertFalse(sa.canPlay(), sa.getHostCard() + ": not castable in the opponent's turn");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
    }

    @Test
    public void rampartSentryIsAVanillaTwoTwoForTwo() {
        vanilla("Rampart Sentry", "{1}{W}", MagicColor.WHITE, "Soldier", 2, 2);
    }

    @Test
    public void templeSpearmanIsAVanillaThreeTwoForThree() {
        vanilla("Temple Spearman", "{2}{W}", MagicColor.WHITE, "Soldier", 3, 2);
    }

    @Test
    public void steppeGazelleIsAVanillaTwoTwoForTwo() {
        vanilla("Steppe Gazelle", "{1}{G}", MagicColor.GREEN, "Antelope", 2, 2);
    }

    @Test
    public void steppeBullIsAVanillaFourFourForFour() {
        vanilla("Steppe Bull", "{2}{G}{G}", MagicColor.GREEN, "Ox", 4, 4);
    }

    /** Destroy target tapped creature: a tapped creature (either side) is a legal target, an untapped one is not. */
    @Test
    public void wearyFoesEndDestroysOnlyATappedCreature() {
        final Game game = initAndCreateGame();
        final Player me = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final Card bull = addCard("Steppe Bull", opp);
        bull.setTapped(true);
        final Card gazelle = addCard("Steppe Gazelle", opp);
        final Card mine = addCard("Rampart Sentry", me);
        mine.setTapped(true);
        final SpellAbility sa = sorcery("Weary Foe's End", "{1}{W}", "Destroy target tapped creature.", me);
        sorceryTiming(game, sa, me, opp);

        Assert.assertTrue(sa.usesTargeting());
        Assert.assertTrue(sa.canTarget(bull), "a tapped opposing creature is a target");
        Assert.assertTrue(sa.canTarget(mine), "a tapped creature of mine is a target too");
        Assert.assertFalse(sa.canTarget(gazelle), "an untapped creature is not a target");
        Assert.assertFalse(sa.canTarget(opp), "a player is not a target");

        sa.getTargets().add(bull);
        AbilityUtils.resolve(sa);
        game.getAction().checkStateEffects(true);
        Assert.assertEquals(countCardsWithName(game, "Steppe Bull", ZoneType.Graveyard), 1, "the tapped target is destroyed");
        Assert.assertEquals(countCardsWithName(game, "Steppe Gazelle"), 1, "nothing else is touched");
        Assert.assertEquals(countCardsWithName(game, "Rampart Sentry"), 1);
    }

    /** Tap all creatures your opponents control with toughness 2 or less: toughness counts, power does not. */
    @Test
    public void noondayGlareTapsOpposingCreaturesWithToughnessTwoOrLess() {
        final Game game = initAndCreateGame();
        final Player me = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final Card sentry = addCard("Rampart Sentry", opp); // 2/2: tapped
        final Card spearman = addCard("Temple Spearman", opp); // 3/2: tapped (power 3 does not matter)
        final Card big = addCard("Steppe Gazelle", opp);
        big.setCounters(CounterEnumType.P1P1, 1); // 3/3: toughness 3 stays untapped
        final Card bull = addCard("Steppe Bull", opp); // 4/4: untapped
        final Card mine = addCard("Steppe Gazelle", me); // mine, 2/2: untapped
        game.getAction().checkStateEffects(true);
        Assert.assertEquals(big.getNetToughness(), 3);
        final SpellAbility sa = sorcery("Noonday Glare", "{2}{W}",
                "Tap all creatures your opponents control with toughness 2 or less.", me);
        sorceryTiming(game, sa, me, opp);
        Assert.assertFalse(sa.usesTargeting(), "it targets nothing");

        AbilityUtils.resolve(sa);
        Assert.assertTrue(sentry.isTapped(), "opposing toughness 2: tapped");
        Assert.assertTrue(spearman.isTapped(), "opposing 3/2: tapped");
        Assert.assertFalse(big.isTapped(), "opposing toughness 3: untapped");
        Assert.assertFalse(bull.isTapped(), "opposing toughness 4: untapped");
        Assert.assertFalse(mine.isTapped(), "my own creature: untapped");
    }

    /** Target creature gets +4/+4 until end of turn: it lasts through the end step and is gone the next turn. */
    @Test
    public void wildbornSurgeLastsUntilEndOfTurn() {
        final Game game = initAndCreateGame();
        final Player me = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        fillLibrary(me, 5);
        fillLibrary(opp, 5);
        final Card gazelle = addCard("Steppe Gazelle", me);
        final Card theirs = addCard("Rampart Sentry", opp);
        final SpellAbility sa = sorcery("Wildborn Surge", "{1}{G}", "Target creature gets +4/+4 until end of turn.", me);
        sorceryTiming(game, sa, me, opp);
        Assert.assertTrue(sa.canTarget(gazelle), "my creature is a target");
        Assert.assertTrue(sa.canTarget(theirs), "an opposing creature is a target");
        Assert.assertFalse(sa.canTarget(opp), "a player is not a target");

        sa.getTargets().add(gazelle);
        AbilityUtils.resolve(sa);
        Assert.assertEquals(gazelle.getNetPower(), 6);
        Assert.assertEquals(gazelle.getNetToughness(), 6);
        Assert.assertEquals(theirs.getNetPower(), 2, "only the target grows");

        playUntilPhase(game, PhaseType.END_OF_TURN);
        Assert.assertSame(game.getPhaseHandler().getPlayerTurn(), me);
        Assert.assertEquals(gazelle.getNetPower(), 6, "still +4/+4 in the end step");
        Assert.assertEquals(gazelle.getNetToughness(), 6);

        playUntilNextTurn(game);
        Assert.assertFalse(game.isGameOver());
        Assert.assertSame(game.getPhaseHandler().getPlayerTurn(), opp);
        Assert.assertEquals(gazelle.getNetPower(), 2, "gone after its turn's cleanup");
        Assert.assertEquals(gazelle.getNetToughness(), 2);
    }

    /** The edition file: XCLT is Type=Other and lists exactly the seven cards. */
    @Test
    public void theEditionListsExactlyTheSevenCards() {
        final CardEdition ed = FModel.getMagicDb().getEditions().get(EDITION);
        Assert.assertNotNull(ed, "XCLT is loaded");
        Assert.assertEquals(ed.getName(), "Cube of Legends Training");
        Assert.assertEquals(ed.getType(), CardEdition.Type.OTHER);
        final java.util.Set<String> listed = new java.util.TreeSet<>();
        for (CardEdition.EditionEntry e : ed.getCards()) {
            listed.add(e.name());
        }
        Assert.assertEquals(listed, new java.util.TreeSet<>(List.of(NAMES)));
        for (String n : NAMES) {
            rules(n);
        }
    }
}
