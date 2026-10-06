package forge.bench;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.ai.AITest;
import forge.bench.rl.CardIndex;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlKnowledge;
import forge.bench.rl.RlSchema;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;

/**
 * Observation v1 witness scenarios (interfaces.md Appendix B.2; lane rl-r0-b5-1006): seat knowledge comes only from
 * what the seat observed. Each scenario builds a position by hand, runs the Forge actions the effect runs (the same
 * controller calls, so the bridge's knowledge hooks fire), and reads the featurizer's zones 16 / 17 / 18.
 */
public class RlKnowledgeTest extends AITest {

    static final String[] NAMES = {"Lightning Bolt", "Counterspell", "Brainstorm", "Ponder", "Thoughtseize",
            "Dark Ritual", "Llanowar Elves", "Swords to Plowshares", "Island", "Swamp", "Mountain", "Forest", "Plains",
            "Ancestral Recall", "Black Lotus", "Time Walk", "Sol Ring", "Mox Pearl", "Mox Sapphire", "Mox Jet"};

    static CardIndex index() {
        final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
        for (int i = 0; i < NAMES.length; i++) {
            sb.append(NAMES[i]).append('\t').append(i + 2).append('\n');
        }
        return CardIndex.of(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A two-seat game with bridge seats (NULL mode: Forge decides) and the knowledge tracker installed. */
    static final class Fixture {
        final Game game;
        final Player p0, p1;
        final RlKnowledge know;
        final RlFeaturizer feat;

        Fixture() {
            this(index());
        }

        Fixture(final CardIndex ix) {
            final BenchSession session = new BenchSession(new JsonRpcChannel(InputStream.nullInputStream(),
                    OutputStream.nullOutputStream()));
            final List<RegisteredPlayer> players = Lists.newArrayList();
            for (int s = 0; s < 2; s++) {
                final LobbyPlayerBridge lp = new LobbyPlayerBridge("Seat" + s, null, session, BenchSession.Mode.NULL, s);
                players.add(new RegisteredPlayer(new Deck()).setPlayer(lp));
            }
            final GameRules rules = new GameRules(GameType.Constructed);
            final Match match = new Match(rules, players, "rl-knowledge");
            game = new Game(players, rules, match);
            p0 = game.getRegisteredPlayers().get(0);
            p1 = game.getRegisteredPlayers().get(1);
            game.setAge(GameStage.Play);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p0);
            game.getPhaseHandler().onStackResolved();
            session.setLiveGame(game);
            know = new RlKnowledge(game);
            know.attach();
            session.setKnowledgeObserver(know);
            feat = new RlFeaturizer(ix);
            feat.setKnowledge(know);
        }

        RlFeaturizer.Obs obs(final Player seat) {
            return feat.observe(game, seat, 0, true);
        }
    }

    private Card add(final Player p, final ZoneType z, final String name) {
        return addCardToZone(name, p, z);
    }

    /** (card index, lib_pos*10) of every token in {@code zone}, in token order. */
    static List<int[]> zone(final RlFeaturizer.Obs o, final int zone) {
        final List<int[]> out = new ArrayList<>();
        for (int i = 0; i < o.L; i++) {
            if (o.tokZone[i] == zone) {
                out.add(new int[] {o.tokCard[i],
                        Math.round(o.tokAttr[i * RlSchema.N_ATTR + RlSchema.A_LIB_POS] * 10f)});
            }
        }
        return out;
    }

    static int idx(final String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return i + 2;
            }
        }
        return 1;
    }

    private void fillLib(final Player p, final int n) {
        for (int i = 0; i < n; i++) {
            add(p, ZoneType.Library, NAMES[i % NAMES.length]);
        }
    }

    @Test
    public void nothingIsKnownWithoutAnObservation() {
        final Fixture f = new Fixture();
        fillLib(f.p0, 12);
        fillLib(f.p1, 12);
        add(f.p1, ZoneType.Hand, "Lightning Bolt");
        final RlFeaturizer.Obs o = f.obs(f.p0);
        Assert.assertEquals(zone(o, RlSchema.Z_U_LIB_KNOWN).size(), 0);
        Assert.assertEquals(zone(o, RlSchema.Z_O_LIB_KNOWN).size(), 0);
        Assert.assertEquals(zone(o, RlSchema.Z_O_HAND_KNOWN).size(), 0);
    }

    @Test
    public void ponderKnowledgeSurvivesOneDrawWithShiftedPositions() {
        final Fixture f = new Fixture();
        fillLib(f.p0, 12);
        // Ponder's RearrangeTopOfLibrary: look at the top three, put them back in any order (no shuffle here), draw
        final CardCollection top = f.p0.getTopXCardsFromLibrary(3);
        final List<Card> ordered = Lists.newArrayList(f.p0.getController().orderMoveToZoneList(top, ZoneType.Library,
                null));
        for (Card c : ordered) {
            f.game.getAction().moveToLibrary(c, 0, null);
        }
        final List<Card> lib = Lists.newArrayList(f.p0.getCardsIn(ZoneType.Library));
        List<int[]> z = zone(f.obs(f.p0), RlSchema.Z_U_LIB_KNOWN);
        Assert.assertEquals(z.size(), 3, "three looked-at cards known");
        for (int i = 0; i < 3; i++) {
            Assert.assertEquals(z.get(i)[0], idx(lib.get(i).getName()));
            Assert.assertEquals(z.get(i)[1], i, "position " + i);
        }
        // the other seat learnt nothing (it did not look)
        Assert.assertEquals(zone(f.obs(f.p1), RlSchema.Z_O_LIB_KNOWN).size(), 0);
        f.p0.drawCard();
        z = zone(f.obs(f.p0), RlSchema.Z_U_LIB_KNOWN);
        Assert.assertEquals(z.size(), 2, "the drawn card left the library");
        Assert.assertEquals(z.get(0)[0], idx(lib.get(1).getName()));
        Assert.assertEquals(z.get(0)[1], 0, "positions shifted up by one");
        Assert.assertEquals(z.get(1)[0], idx(lib.get(2).getName()));
        Assert.assertEquals(z.get(1)[1], 1);
    }

    @Test
    public void shuffleClearsZones17And18() {
        final Fixture f = new Fixture();
        fillLib(f.p0, 12);
        fillLib(f.p1, 12);
        final CardCollection top = f.p0.getTopXCardsFromLibrary(2);
        f.p0.getController().arrangeForScry(top); // the look (Forge's scry calls exactly this)
        // the other library: its top card revealed to everyone
        f.game.getAction().reveal(f.p1.getTopXCardsFromLibrary(1), ZoneType.Library, f.p1, false, "test");
        RlFeaturizer.Obs o = f.obs(f.p0);
        Assert.assertEquals(zone(o, RlSchema.Z_U_LIB_KNOWN).size(), 2);
        Assert.assertEquals(zone(o, RlSchema.Z_O_LIB_KNOWN).size(), 1);
        f.p0.shuffle(null);
        f.p1.shuffle(null);
        o = f.obs(f.p0);
        Assert.assertEquals(zone(o, RlSchema.Z_U_LIB_KNOWN).size(), 0, "own shuffle clears zone 17");
        Assert.assertEquals(zone(o, RlSchema.Z_O_LIB_KNOWN).size(), 0, "opponent shuffle clears zone 18");
    }

    @Test
    public void scryToBottomLeavesTheTopSegment() {
        final Fixture f = new Fixture();
        fillLib(f.p0, 12);
        final CardCollection top = f.p0.getTopXCardsFromLibrary(2);
        f.p0.getController().arrangeForScry(top); // look at the top two
        final Card bottom = top.get(0);
        final Card kept = top.get(1);
        f.game.getAction().moveToLibrary(bottom, -1, null); // scry: the first one to the bottom
        final int size = f.p0.getCardsIn(ZoneType.Library).size();
        final List<int[]> z = zone(f.obs(f.p0), RlSchema.Z_U_LIB_KNOWN);
        Assert.assertEquals(z.size(), 2);
        Assert.assertEquals(z.get(0)[0], idx(kept.getName()));
        Assert.assertEquals(z.get(0)[1], 0, "the kept card is the top card");
        Assert.assertEquals(z.get(1)[0], idx(bottom.getName()));
        Assert.assertEquals(z.get(1)[1], size - 1, "the bottomed card is at the bottom, not in the top segment");
    }

    @Test
    public void anotherSeatsRearrangementForgetsTheOrder() {
        final Fixture f = new Fixture();
        fillLib(f.p1, 12);
        f.game.getAction().reveal(f.p1.getTopXCardsFromLibrary(2), ZoneType.Library, f.p1, false, "test");
        Assert.assertEquals(zone(f.obs(f.p0), RlSchema.Z_O_LIB_KNOWN).size(), 2);
        // the opponent looks at its top three and puts them back in an order only it knows
        final CardCollection top = f.p1.getTopXCardsFromLibrary(3);
        for (Card c : Lists.newArrayList(f.p1.getController().orderMoveToZoneList(top, ZoneType.Library, null))) {
            f.game.getAction().moveToLibrary(c, 0, null);
        }
        Assert.assertEquals(zone(f.obs(f.p0), RlSchema.Z_O_LIB_KNOWN).size(), 0);
        Assert.assertEquals(zone(f.obs(f.p1), RlSchema.Z_U_LIB_KNOWN).size(), 3);
    }

    @Test
    public void thoughtseizeRevealedCardsLeaveWhenCastOrDiscarded() {
        final Fixture f = new Fixture();
        fillLib(f.p1, 12);
        final Card bolt = add(f.p1, ZoneType.Hand, "Lightning Bolt");
        final Card ritual = add(f.p1, ZoneType.Hand, "Dark Ritual");
        final Card spell = add(f.p1, ZoneType.Hand, "Counterspell");
        // Thoughtseize (Discard, Mode RevealYouChoose): the hand is revealed to the caster only
        f.game.getAction().revealTo(new CardCollection(f.p1.getCardsIn(ZoneType.Hand)), f.p0);
        List<int[]> z = zone(f.obs(f.p0), RlSchema.Z_O_HAND_KNOWN);
        Assert.assertEquals(z.size(), 3);
        // the other seat (the hand's owner) sees its own hand as zone 1, never 16
        Assert.assertEquals(zone(f.obs(f.p1), RlSchema.Z_O_HAND_KNOWN).size(), 0);
        f.game.getAction().moveToGraveyard(bolt, null); // discarded
        z = zone(f.obs(f.p0), RlSchema.Z_O_HAND_KNOWN);
        Assert.assertEquals(z.size(), 2, "the discarded card left zone 16");
        f.game.getAction().moveToStack(spell, null); // cast
        z = zone(f.obs(f.p0), RlSchema.Z_O_HAND_KNOWN);
        Assert.assertEquals(z.size(), 1, "the cast card left zone 16");
        Assert.assertEquals(z.get(0)[0], idx(ritual.getName()));
        f.p1.drawCard(); // a new, unknown card joins the hand
        Assert.assertEquals(zone(f.obs(f.p0), RlSchema.Z_O_HAND_KNOWN).size(), 1);
        // the privileged block (critic) holds the hidden hand only: the drawn card, not the known one
        final RlFeaturizer.Obs o = f.obs(f.p0);
        int handPriv = 0;
        for (int i = 0; i < o.P; i++) {
            if (o.privZone[i] == RlSchema.Z_PRIV_O_HAND) {
                handPriv += o.privCnt[i];
            }
        }
        Assert.assertEquals(handPriv, 1);
    }

    @Test
    public void publicCardReturnedToHandIsKnown() {
        final Fixture f = new Fixture();
        final Card c = add(f.p1, ZoneType.Battlefield, "Llanowar Elves");
        f.game.getAction().moveToHand(c, null); // bounced: everyone saw it
        final List<int[]> z = zone(f.obs(f.p0), RlSchema.Z_O_HAND_KNOWN);
        Assert.assertEquals(z.size(), 1);
        Assert.assertEquals(z.get(0)[0], idx("Llanowar Elves"));
        Assert.assertTrue(f.know.seenOpponentNames(0).contains("Llanowar Elves"));
        // hand -> library unseen (the opponent tucks a card from hand): the identity is forgotten
        f.game.getAction().moveToLibrary(c, 0, null);
        Assert.assertEquals(zone(f.obs(f.p0), RlSchema.Z_O_LIB_KNOWN).size(), 0);
    }

    @Test
    public void ownPutBackIsKnownAtItsPosition() {
        final Fixture f = new Fixture();
        fillLib(f.p0, 8);
        final Card a = add(f.p0, ZoneType.Hand, "Ancestral Recall");
        f.game.getAction().moveToLibrary(a, 0, null); // Brainstorm's put-back: our own card, on top
        final List<int[]> z = zone(f.obs(f.p0), RlSchema.Z_U_LIB_KNOWN);
        Assert.assertEquals(z.size(), 1);
        Assert.assertEquals(z.get(0)[0], idx("Ancestral Recall"));
        Assert.assertEquals(z.get(0)[1], 0);
        // the opponent saw a card go back, not which
        Assert.assertEquals(zone(f.obs(f.p1), RlSchema.Z_O_LIB_KNOWN).size(), 0);
    }

    /**
     * Clarification C3: a face of a multi-face card resolves to the FULL card's entry. A table where faces have
     * entries of their own (as Insectile Aberration, Petty Theft and Tibalt do in cards-ext) proves the full name wins.
     */
    @Test
    public void faceNamesResolveToTheFullCard() {
        final String tsv = "<pad>\t0\n<unk>\t1\nFire // Ice\t100\nDelver of Secrets\t101\nInsectile Aberration\t102\n"
                + "Valki, God of Lies\t103\nTibalt, Cosmic Impostor\t104\nBrazen Borrower\t105\nPetty Theft\t106\n";
        final CardIndex ix = CardIndex.of(tsv.getBytes(StandardCharsets.UTF_8));
        final Fixture f = new Fixture();
        final Object[][] cases = {
            {"Fire // Ice", forge.card.CardStateName.LeftSplit, "Fire", 100},
            {"Fire // Ice", forge.card.CardStateName.RightSplit, "Ice", 100},
            {"Delver of Secrets", forge.card.CardStateName.Backside, "Insectile Aberration", 101},
            {"Valki, God of Lies", forge.card.CardStateName.Backside, "Tibalt, Cosmic Impostor", 103},
            {"Brazen Borrower", forge.card.CardStateName.Secondary, "Petty Theft", 105},
        };
        for (Object[] k : cases) {
            final Card c = add(f.p0, ZoneType.Hand, (String) k[0]);
            c.setState((forge.card.CardStateName) k[1], true);
            Assert.assertEquals(c.getName(), k[2], "the face is " + k[2]);
            Assert.assertEquals(RlFeaturizer.resolveCard(ix, c), ((Integer) k[3]).intValue(), (String) k[2]);
            // and the full card itself
            c.setState(forge.card.CardStateName.Original, true);
            Assert.assertEquals(RlFeaturizer.resolveCard(ix, c), ((Integer) k[3]).intValue(), (String) k[0]);
        }
        // Java/Python parity on the real table (tools/ml/rl/cardindex.py lookup with forge-faces.json, 10-05):
        // Fire 22884, Tibalt 16153, Insectile Aberration 3701, Petty Theft 2063
        final String real = System.getProperty("rl.cardIndex");
        if (real != null) {
            try {
                final CardIndex ri = CardIndex.load(java.nio.file.Paths.get(real));
                final Object[][] parity = {{"Fire // Ice", forge.card.CardStateName.LeftSplit, 22884},
                    {"Valki, God of Lies", forge.card.CardStateName.Backside, 16153},
                    {"Delver of Secrets", forge.card.CardStateName.Backside, 3701},
                    {"Brazen Borrower", forge.card.CardStateName.Secondary, 2063}};
                for (Object[] k : parity) {
                    final Card c = add(f.p0, ZoneType.Hand, (String) k[0]);
                    c.setState((forge.card.CardStateName) k[1], true);
                    Assert.assertEquals(RlFeaturizer.resolveCard(ri, c), ((Integer) k[2]).intValue(),
                            "parity with cardindex.py for " + c.getName());
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    public void eventTailNewestFirstPerController() {
        final Fixture f = new Fixture();
        Assert.assertTrue(f.know.tail().isEmpty());
        final Card ritual = add(f.p1, ZoneType.Hand, "Dark Ritual");
        final forge.game.spellability.SpellAbility rsa = ritual.getFirstSpellAbility();
        rsa.setActivatingPlayer(f.p1);
        f.game.getStack().add(rsa);
        final Card recall = add(f.p0, ZoneType.Hand, "Sol Ring");
        final forge.game.spellability.SpellAbility ssa = recall.getFirstSpellAbility();
        ssa.setActivatingPlayer(f.p0);
        f.game.getStack().add(ssa);
        final RlFeaturizer.Obs o = f.obs(f.p0);
        final List<int[]> mine = zone(o, RlSchema.Z_U_EVENT);
        final List<int[]> theirs = zone(o, RlSchema.Z_O_EVENT);
        Assert.assertEquals(mine.size(), 1);
        Assert.assertEquals(theirs.size(), 1);
        Assert.assertEquals(mine.get(0)[0], idx("Sol Ring"));
        Assert.assertEquals(theirs.get(0)[0], idx("Dark Ritual"));
        // newest first: Sol Ring has event_age 0, Dark Ritual 1/16
        for (int i = 0; i < o.L; i++) {
            final float age = o.tokAttr[i * RlSchema.N_ATTR + RlSchema.A_EVENT_AGE];
            if (o.tokZone[i] == RlSchema.Z_U_EVENT) {
                Assert.assertEquals(age, 0f);
            } else if (o.tokZone[i] == RlSchema.Z_O_EVENT) {
                Assert.assertEquals(age, 1f / 16f);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ C4'

    /** Resolve a walker's emblem ability (loyalty ability or ETB SVar) and return the emblem in its command zone. */
    private Card emblem(final Player p, final String walker, final forge.card.CardStateName state) {
        final Card w = add(p, ZoneType.Battlefield, walker);
        if (state != null) {
            w.setState(state, true);
        }
        forge.game.spellability.SpellAbility sa = null;
        for (forge.game.spellability.SpellAbility a : w.getSpellAbilities()) {
            if (a.getApi() == forge.game.ability.ApiType.Effect
                    && a.getParamOrDefault("Name", "").startsWith("Emblem")) {
                sa = a;
            }
        }
        if (sa == null && w.hasSVar("Emblem")) {
            sa = forge.game.ability.AbilityFactory.getAbility(w.getSVar("Emblem"), w);
        }
        Assert.assertNotNull(sa, walker + ": its emblem ability");
        sa.setActivatingPlayer(p);
        forge.game.ability.AbilityUtils.resolve(sa);
        Card found = null;
        for (Card c : p.getCardsIn(ZoneType.Command)) {
            if (c.isEmblem() && c.getEffectSource() == w) {
                found = c;
            }
        }
        Assert.assertNotNull(found, walker + ": the emblem is in the command zone");
        return found;
    }

    private Card dungeon(final Fixture f, final Player p, final String script) {
        final Card d = forge.game.card.CardFactory.getCard(forge.StaticData.instance().getAllTokens().getToken(script), p,
                f.game);
        d.setGamePieceType(forge.card.GamePieceType.DUNGEON);
        f.game.getAction().moveToCommand(d, null);
        return d;
    }

    /**
     * Clarification C4': emblems resolve to their own "&lt;walker&gt; Emblem" row, else to the walker (C3: full card
     * first); designations and dungeons by exact name (The Initiative and Undercity stay &lt;unk&gt; and are counted);
     * a Forge "X's Effect" host in the event tail resolves to its source card. Emblems, designations and effects never
     * enter the opponent seen-cards set (NAME).
     */
    @Test
    public void emblemsDesignationsAndEffectHostsResolve() {
        final String tsv = "<pad>\t0\n<unk>\t1\n"
                + "Chandra, Torch of Defiance\t10\nChandra, Torch of Defiance Emblem\t11\n"
                + "Elspeth, Knight-Errant\t12\nElspeth, Knight-Errant Emblem\t13\n"
                + "Koth of the Hammer\t14\n" // no emblem row: the walker
                + "Valki, God of Lies\t16\nTibalt, Cosmic Impostor\t17\nTibalt, Cosmic Impostor Emblem\t18\n"
                + "The Monarch\t20\nLost Mine of Phandelver\t21\nForth Eorlingas!\t22\n";
        final CardIndex ix = CardIndex.of(tsv.getBytes(StandardCharsets.UTF_8));
        // the same walkers without any emblem row: each falls back to its walker's full card
        final CardIndex walkersOnly = CardIndex.of(("<pad>\t0\n<unk>\t1\nChandra, Torch of Defiance\t10\n"
                + "Elspeth, Knight-Errant\t12\nKoth of the Hammer\t14\nValki, God of Lies\t16\n"
                + "Tibalt, Cosmic Impostor\t17\n").getBytes(StandardCharsets.UTF_8));
        final Fixture f = new Fixture(ix);
        final Card chandra = emblem(f.p0, "Chandra, Torch of Defiance", null);
        final Card elspeth = emblem(f.p0, "Elspeth, Knight-Errant", null);
        final Card koth = emblem(f.p1, "Koth of the Hammer", null);
        final Card tibalt = emblem(f.p1, "Valki, God of Lies", forge.card.CardStateName.Backside);
        Assert.assertEquals(chandra.getName(), "Emblem \u2014 Chandra, Torch of Defiance");
        Assert.assertEquals(tibalt.getName(), "Emblem \u2014 Tibalt, Cosmic Impostor");
        final Object[][] cases = {{chandra, 11, 10}, {elspeth, 13, 12}, {koth, 14, 14}, {tibalt, 18, 16}};
        for (Object[] k : cases) {
            final Card e = (Card) k[0];
            Assert.assertEquals(RlFeaturizer.resolveCard(ix, e), ((Integer) k[1]).intValue(), e.getName());
            Assert.assertEquals(RlFeaturizer.resolveCard(walkersOnly, e), ((Integer) k[2]).intValue(),
                    e.getName() + " without an emblem row (Tibalt: the full card Valki, as C3)");
            // the converter's name-only rule gives the same answer
            Assert.assertEquals(RlFeaturizer.resolveEmblem(ix, e.getName(), null), ((Integer) k[1]).intValue());
        }

        // designations and dungeons, by exact name
        f.game.getAction().becomeMonarch(f.p1, "CN2");
        dungeon(f, f.p0, "lost_mine_of_phandelver");
        f.game.getAction().takeInitiative(f.p0, "CLB");
        dungeon(f, f.p1, "undercity");

        final RlFeaturizer.Obs o = f.obs(f.p0);
        final List<Integer> command = new ArrayList<>();
        for (int[] t : zone(o, RlSchema.Z_COMMAND)) {
            command.add(t[0]);
        }
        java.util.Collections.sort(command);
        // 4 emblems (11, 13, 14, 18), the monarch 20, Lost Mine 21, The Initiative and Undercity <unk> (1)
        Assert.assertEquals(command, java.util.Arrays.asList(1, 1, 11, 13, 14, 18, 20, 21));
        Assert.assertEquals(new java.util.TreeSet<>(o.commandUnknown),
                new java.util.TreeSet<>(java.util.Arrays.asList("The Initiative", "Undercity")));

        // an "X's Effect" holding a delayed trigger: the event tail shows the source card
        final Card forth = add(f.p1, ZoneType.Hand, "Forth Eorlingas!");
        final forge.game.spellability.SpellAbility mk = forge.game.ability.AbilityFactory.getAbility(
                forth.getSVar("DBEffect"), forth);
        mk.setActivatingPlayer(f.p1);
        forge.game.ability.AbilityUtils.resolve(mk);
        Card effect = null;
        for (Card c : f.p1.getCardsIn(ZoneType.Command)) {
            if (c.getName().endsWith("'s Effect")) {
                effect = c;
            }
        }
        Assert.assertNotNull(effect, "Forth Eorlingas!'s Effect in the command zone");
        final forge.game.spellability.SpellAbility trig = effect.getTriggers().iterator().next().ensureAbility();
        Assert.assertSame(trig.getHostCard(), effect);
        trig.setActivatingPlayer(f.p1);
        f.game.getStack().add(trig);
        final RlFeaturizer.Obs o2 = f.obs(f.p0);
        final List<int[]> theirs = zone(o2, RlSchema.Z_O_EVENT);
        Assert.assertEquals(theirs.size(), 1);
        Assert.assertEquals(theirs.get(0)[0], 22, "the effect's trigger shows as Forth Eorlingas!");
        // and is not a command-zone token
        Assert.assertEquals(zone(o2, RlSchema.Z_COMMAND).size(), 8);

        // nothing but real cards in the seen-cards set
        for (int s = 0; s < 2; s++) {
            for (String n : f.know.opponentSeen(s)) {
                Assert.assertFalse(n.startsWith("Emblem") || n.startsWith("The ") || n.endsWith("Effect")
                        || n.equals("Undercity") || n.equals("Lost Mine of Phandelver"), "seen " + n);
            }
        }

        // Java/Python parity on the real table (C4' rule; 10-05 rows)
        final String real = System.getProperty("rl.cardIndex");
        if (real != null) {
            try {
                final CardIndex ri = CardIndex.load(java.nio.file.Paths.get(real));
                Assert.assertEquals(RlFeaturizer.resolveCard(ri, chandra), 19846);
                Assert.assertEquals(RlFeaturizer.resolveCard(ri, elspeth), 22073);
                Assert.assertEquals(RlFeaturizer.resolveCard(ri, koth), 26221);
                Assert.assertEquals(RlFeaturizer.resolveCard(ri, tibalt), 34322);
                for (Card c : f.p1.getCardsIn(ZoneType.Command)) {
                    if (c.getName().equals("The Monarch")) {
                        Assert.assertEquals(RlFeaturizer.resolveCard(ri, c), 34018);
                    }
                }
                for (Card c : f.p0.getCardsIn(ZoneType.Command)) {
                    if (c.getName().equals("Lost Mine of Phandelver")) {
                        Assert.assertEquals(RlFeaturizer.resolveCard(ri, c), 8869);
                    }
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
