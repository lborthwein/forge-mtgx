package forge.bench;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.bench.rl.CardIndex;
import forge.bench.rl.RlCandidates;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlSchema;
import forge.bench.rl.RlSchemaV2;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.zone.ZoneType;

/**
 * Observation v2 witnesses W1-W12 (lane rl-obs-v2-1006; ICR obs-v2-1006-schema §3): each new field on a position
 * built by hand, through the same Forge calls the effects make, read from the v2 featurizer. W13 (the hand rule) is
 * RlHandAmbiguityTest, which v2 shares.
 */
public class RlObsV2Test extends AITest {

    static final String[] NAMES = {"Lightning Bolt", "Counterspell", "Grizzly Bears", "Serra Angel", "Forked Bolt",
        "Jace, the Mind Sculptor", "Kolaghan's Command", "Blaze", "Pacifism", "Bonesplitter", "Curse of the Pierced Heart",
        "Flight", "Humility", "Blood Moon", "Volcanic Island", "Clone", "Delver of Secrets", "Pithing Needle",
        "Cavern of Souls", "Painter's Servant", "Exalted Angel", "Saw It Coming", "Llanowar Elves", "Island",
        "Fact or Fiction", "Ancestral Recall", "Brainstorm", "Oblivion Ring", "Dark Ritual", "History of Benalia",
        "Chrome Mox", "Mox Pearl", "Swamp", "Mountain", "Forest", "Plains"};

    static CardIndex index() {
        final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
        for (int i = 0; i < NAMES.length; i++) {
            sb.append(NAMES[i]).append('\t').append(i + 2).append('\n');
        }
        return CardIndex.of(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static int idx(final String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return i + 2;
            }
        }
        return 1;
    }

    /** A v2 fixture: RlKnowledgeTest's two bridge seats, the featurizer and tracker in v2. */
    static RlKnowledgeTest.Fixture v2() {
        final RlKnowledgeTest.Fixture f = new RlKnowledgeTest.Fixture(index());
        f.feat.setVersion(2);
        f.know.v2 = true;
        return f;
    }

    private Card add(final Player p, final ZoneType z, final String name) {
        return addCardToZone(name, p, z);
    }

    private static RlFeaturizer.Obs obs(final RlKnowledgeTest.Fixture f, final Player seat) {
        final RlFeaturizer.Obs o = f.feat.observe(f.game, seat, 0, true);
        Assert.assertEquals(o.version, 2);
        Assert.assertEquals(o.ctx.length, RlSchemaV2.N_CTX);
        return o;
    }

    /** Relations of the frame as {src, dst, type, arg, num}. */
    private static List<int[]> rels(final RlFeaturizer.Obs o) {
        final List<int[]> out = new ArrayList<>();
        for (int i = 0; i < o.R; i++) {
            out.add(new int[] {o.relSrc[i], o.relDst[i], o.relType[i], o.relArg[i], o.relNum[i]});
        }
        return out;
    }

    private static boolean hasRel(final RlFeaturizer.Obs o, final int src, final int dst, final int type) {
        for (int[] r : rels(o)) {
            if (r[0] == src && r[1] == dst && r[2] == type) {
                return true;
            }
        }
        return false;
    }

    private static int relNum(final RlFeaturizer.Obs o, final int src, final int dst, final int type) {
        for (int[] r : rels(o)) {
            if (r[0] == src && r[1] == dst && r[2] == type) {
                return r[4];
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Facts on token {@code tok} as {id, arg, num}. */
    private static List<int[]> facts(final RlFeaturizer.Obs o, final int tok) {
        final List<int[]> out = new ArrayList<>();
        for (int i = 0; i < o.F; i++) {
            if (o.factTok[i] == tok) {
                out.add(new int[] {o.factId[i] & 0xffff, o.factArg[i], o.factNum[i]});
            }
        }
        return out;
    }

    private static int[] fact(final RlFeaturizer.Obs o, final int tok, final String name) {
        final int id = RlSchemaV2.factId(name);
        Assert.assertTrue(id > 0, name);
        for (int[] q : facts(o, tok)) {
            if (q[0] == id) {
                return q;
            }
        }
        return null;
    }

    private static boolean bit(final RlFeaturizer.Obs o, final int tok, final int b) {
        return (o.tokBits[tok] >>> b & 1L) != 0;
    }

    private static int pos(final RlFeaturizer.Obs o, final Card c) {
        final int p = o.pos(c);
        Assert.assertTrue(p >= 0, "no token for " + c);
        return p;
    }

    private static int stackPos(final RlFeaturizer.Obs o, final SpellAbility sa, final RlKnowledgeTest.Fixture f) {
        for (SpellAbilityStackInstance si : f.game.getStack()) {
            if (si.getSpellAbility() == sa) {
                final Integer p = o.posByStackId.get(si.getId());
                Assert.assertNotNull(p, "no stack token for " + sa);
                return p;
            }
        }
        Assert.fail("not on the stack: " + sa);
        return -1;
    }

    private static List<Integer> zone(final RlFeaturizer.Obs o, final int z) {
        final List<Integer> out = new ArrayList<>();
        for (int i = 0; i < o.L; i++) {
            if (o.tokZone[i] == z) {
                out.add(o.tokCard[i]);
            }
        }
        Collections.sort(out);
        return out;
    }

    private static SpellAbility cast(final RlKnowledgeTest.Fixture f, final Player p, final Card c,
            final Object... targets) {
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        if (targets.length > 0) {
            if (sa.getTargets() == null) {
                sa.setTargets(new TargetChoices());
            }
            for (Object t : targets) {
                sa.getTargets().add((forge.game.GameObject) t);
            }
        }
        f.game.getStack().add(sa);
        return sa;
    }

    // ------------------------------------------------------------------------------------------------ W1, W2

    /** W1: stack items point to their targets: a creature, a player, a spell; divided amounts ride on the relation. */
    @Test
    public void w1StackTargets() {
        final RlKnowledgeTest.Fixture f = v2();
        final Card bears = add(f.p0, ZoneType.Battlefield, "Grizzly Bears");
        final SpellAbility bolt = cast(f, f.p1, add(f.p1, ZoneType.Hand, "Lightning Bolt"), bears);
        final SpellAbility bolt2 = cast(f, f.p1, add(f.p1, ZoneType.Hand, "Lightning Bolt"), f.p0);
        final SpellAbility counter = cast(f, f.p0, add(f.p0, ZoneType.Hand, "Counterspell"), bolt);
        final RlFeaturizer.Obs o = obs(f, f.p0);
        final int b = pos(o, bears), s1 = stackPos(o, bolt, f), s2 = stackPos(o, bolt2, f),
                sc = stackPos(o, counter, f);
        Assert.assertTrue(hasRel(o, s1, b, RlSchemaV2.R_TARGET), "Bolt -> the creature: " + str(o));
        Assert.assertTrue(hasRel(o, s2, -2, RlSchemaV2.R_TARGET), "Bolt -> me (seat 0): " + str(o));
        Assert.assertTrue(hasRel(o, sc, s1, RlSchemaV2.R_TARGET), "Counterspell -> Bolt's stack token: " + str(o));
        // the other seat sees the player target as its opponent
        final RlFeaturizer.Obs o1 = obs(f, f.p1);
        Assert.assertTrue(hasRel(o1, stackPos(o1, bolt2, f), -3, RlSchemaV2.R_TARGET));
        // the event tail points too: the newest tail tokens are Counterspell, then Bolt, then Bolt
        int tailBolt = -1;
        for (int i = 0; i < o.L; i++) {
            if (o.tokZone[i] == RlSchema.Z_O_EVENT && o.tokCard[i] == idx("Lightning Bolt")
                    && hasRel(o, i, b, RlSchemaV2.R_TARGET)) {
                tailBolt = i;
            }
        }
        Assert.assertTrue(tailBolt >= 0, "a tail Bolt points at the creature: " + str(o));
        // divided: Forked Bolt, 1 and 1
        final RlKnowledgeTest.Fixture g = v2();
        final Card e1 = add(g.p0, ZoneType.Battlefield, "Llanowar Elves");
        final Card e2 = add(g.p0, ZoneType.Battlefield, "Grizzly Bears");
        final Card fb = add(g.p1, ZoneType.Hand, "Forked Bolt");
        final SpellAbility fsa = fb.getFirstSpellAbility();
        fsa.setActivatingPlayer(g.p1);
        if (fsa.getTargets() == null) {
            fsa.setTargets(new TargetChoices());
        }
        fsa.getTargets().add(e1);
        fsa.getTargets().add(e2);
        fsa.getTargets().addDividedAllocation(e1, 1);
        fsa.getTargets().addDividedAllocation(e2, 1);
        g.game.getStack().add(fsa);
        final RlFeaturizer.Obs og = obs(g, g.p0);
        final int sf = stackPos(og, fsa, g);
        Assert.assertTrue(fsa.isDividedAsYouChoose(), "Forked Bolt divides");
        Assert.assertEquals(relNum(og, sf, pos(og, e1), RlSchemaV2.R_TARGET), 1, str(og));
        Assert.assertEquals(relNum(og, sf, pos(og, e2), RlSchemaV2.R_TARGET), 1, str(og));
    }

    /** W2: X and chosen modes are facts on the stack token (and the tail token). */
    @Test
    public void w2XAndModes() {
        final RlKnowledgeTest.Fixture f = v2();
        final Card blaze = add(f.p1, ZoneType.Hand, "Blaze");
        final SpellAbility bsa = blaze.getFirstSpellAbility();
        bsa.setActivatingPlayer(f.p1);
        bsa.setXManaCostPaid(3);
        if (bsa.getTargets() == null) {
            bsa.setTargets(new TargetChoices());
        }
        bsa.getTargets().add(f.p0);
        f.game.getStack().add(bsa);
        RlFeaturizer.Obs o = obs(f, f.p0);
        final int[] x = fact(o, stackPos(o, bsa, f), "X");
        Assert.assertNotNull(x, "X on Blaze: " + facts(o, stackPos(o, bsa, f)).size());
        Assert.assertEquals(x[2], 3);
        // a charm: Kolaghan's Command, modes 2 and 4 (indices 1 and 3) chosen
        final Card kc = add(f.p1, ZoneType.Hand, "Kolaghan's Command");
        final SpellAbility root = kc.getFirstSpellAbility();
        root.setActivatingPlayer(f.p1);
        final List<forge.game.spellability.AbilitySub> choices = root.getAdditionalAbilityList("Choices");
        Assert.assertTrue(choices.size() >= 4, "Kolaghan's Command has four modes");
        final List<forge.game.spellability.AbilitySub> chosen = new ArrayList<>(List.of(choices.get(1), choices.get(3)));
        root.setChosenList(chosen);
        f.game.getStack().add(root);
        o = obs(f, f.p0);
        final int k = stackPos(o, root, f);
        Assert.assertNotNull(fact(o, k, "MODE:1"), "mode 1 chosen");
        Assert.assertNotNull(fact(o, k, "MODE:3"), "mode 3 chosen");
        Assert.assertNull(fact(o, k, "MODE:0"));
        Assert.assertNull(fact(o, k, "MODE:2"));
    }

    // ------------------------------------------------------------------------------------------------ W3, W4

    /** W3: attackers point to the player or planeswalker they attack; blockers to the attacker they block. */
    @Test
    public void w3Combat() {
        final RlKnowledgeTest.Fixture f = v2();
        final Card a1 = add(f.p1, ZoneType.Battlefield, "Grizzly Bears");
        final Card a2 = add(f.p1, ZoneType.Battlefield, "Serra Angel");
        final Card jace = add(f.p0, ZoneType.Battlefield, "Jace, the Mind Sculptor");
        final Card b1 = add(f.p0, ZoneType.Battlefield, "Llanowar Elves");
        final Card b2 = add(f.p0, ZoneType.Battlefield, "Grizzly Bears");
        final Combat c = new Combat(f.p1);
        c.addAttacker(a1, f.p0);
        c.addAttacker(a2, jace);
        c.addBlocker(a1, b1);
        c.addBlocker(a1, b2);
        f.game.getPhaseHandler().setCombat(c);
        final RlFeaturizer.Obs o = obs(f, f.p0);
        Assert.assertTrue(hasRel(o, pos(o, a1), -2, RlSchemaV2.R_ATTACKS), "attacks me: " + str(o));
        Assert.assertTrue(hasRel(o, pos(o, a2), pos(o, jace), RlSchemaV2.R_ATTACKS), "attacks Jace: " + str(o));
        Assert.assertTrue(hasRel(o, pos(o, b1), pos(o, a1), RlSchemaV2.R_BLOCKS), "double block: " + str(o));
        Assert.assertTrue(hasRel(o, pos(o, b2), pos(o, a1), RlSchemaV2.R_BLOCKS), "double block: " + str(o));
        Assert.assertFalse(hasRel(o, pos(o, b1), pos(o, a2), RlSchemaV2.R_BLOCKS));
        final RlFeaturizer.Obs o1 = obs(f, f.p1);
        Assert.assertTrue(hasRel(o1, pos(o1, a1), -3, RlSchemaV2.R_ATTACKS), "from the attacker's side: -3");
    }

    /** W4: auras and equipment point to what they are attached to, a curse to its player. */
    @Test
    public void w4Attachments() {
        final RlKnowledgeTest.Fixture f = v2();
        final Card bears = add(f.p0, ZoneType.Battlefield, "Grizzly Bears");
        final Card paci = add(f.p1, ZoneType.Battlefield, "Pacifism");
        final Card split = add(f.p0, ZoneType.Battlefield, "Bonesplitter");
        final Card curse = add(f.p1, ZoneType.Battlefield, "Curse of the Pierced Heart");
        paci.attachToEntity(bears, null);
        split.attachToEntity(bears, null);
        curse.attachToEntity(f.p0, null);
        final RlFeaturizer.Obs o = obs(f, f.p0);
        Assert.assertTrue(hasRel(o, pos(o, paci), pos(o, bears), RlSchemaV2.R_ATTACHED), str(o));
        Assert.assertTrue(hasRel(o, pos(o, split), pos(o, bears), RlSchemaV2.R_ATTACHED), str(o));
        Assert.assertTrue(hasRel(o, pos(o, curse), -2, RlSchemaV2.R_ATTACHED), str(o));
        Assert.assertTrue(hasRel(obs(f, f.p1), pos(obs(f, f.p1), curse), -3, RlSchemaV2.R_ATTACHED));
    }

    // ------------------------------------------------------------------------------------------------ W5

    /** W5: current characteristics: printed keywords, a granted keyword, lost abilities, a copy, a back face, Blood Moon. */
    @Test
    public void w5CurrentCharacteristics() {
        final RlKnowledgeTest.Fixture f = v2();
        final Card angel = add(f.p0, ZoneType.Battlefield, "Serra Angel");
        final Card bears = add(f.p0, ZoneType.Battlefield, "Grizzly Bears");
        final Card elves = add(f.p1, ZoneType.Battlefield, "Llanowar Elves");
        RlFeaturizer.Obs o = obs(f, f.p0);
        int a = pos(o, angel), b = pos(o, bears);
        Assert.assertNotNull(fact(o, a, "KW:FLYING"));
        Assert.assertNotNull(fact(o, a, "KW:VIGILANCE"));
        Assert.assertTrue(bit(o, a, RlSchemaV2.B_CREATURE) && bit(o, a, RlSchemaV2.B_W));
        Assert.assertFalse(bit(o, a, RlSchemaV2.B_NO_ABILITIES));
        Assert.assertNull(fact(o, b, "KW:FLYING"));
        Assert.assertTrue(bit(o, pos(o, elves), RlSchemaV2.B_G));
        // Flight on the bears: flying, granted
        final Card flight = add(f.p0, ZoneType.Battlefield, "Flight");
        flight.attachToEntity(bears, null);
        f.game.getAction().checkStaticAbilities();
        o = obs(f, f.p0);
        Assert.assertNotNull(fact(o, pos(o, bears), "KW:FLYING"), "granted flying shows");
        // Humility: every creature loses its abilities
        add(f.p1, ZoneType.Battlefield, "Humility");
        f.game.getAction().checkStaticAbilities();
        o = obs(f, f.p0);
        a = pos(o, angel);
        Assert.assertNull(fact(o, a, "KW:FLYING"), "Humility: the angel lost flying");
        Assert.assertTrue(bit(o, a, RlSchemaV2.B_NO_ABILITIES), "Humility: no abilities");
        Assert.assertEquals(o.tokCard[a], idx("Serra Angel"), "the printed card stays the token's card");

        final RlKnowledgeTest.Fixture g = v2();
        // Blood Moon: a nonbasic land is a Mountain
        final Card volc = add(g.p1, ZoneType.Battlefield, "Volcanic Island");
        RlFeaturizer.Obs og = obs(g, g.p0);
        Assert.assertTrue(bit(og, pos(og, volc), RlSchemaV2.B_ISLAND) && bit(og, pos(og, volc), RlSchemaV2.B_MOUNTAIN));
        add(g.p0, ZoneType.Battlefield, "Blood Moon");
        g.game.getAction().checkStaticAbilities();
        og = obs(g, g.p0);
        Assert.assertFalse(bit(og, pos(og, volc), RlSchemaV2.B_ISLAND), "Blood Moon: no longer an Island");
        Assert.assertTrue(bit(og, pos(og, volc), RlSchemaV2.B_MOUNTAIN));
        // Clone copying the angel: copy bit, COPY_OF the angel, the token's card stays Clone
        final Card src = add(g.p1, ZoneType.Battlefield, "Serra Angel");
        final Card clone = add(g.p0, ZoneType.Battlefield, "Clone");
        clone.addCloneState(forge.game.card.CardFactory.getCloneStates(src, clone, clone.getFirstSpellAbility()),
                g.game.getNextTimestamp());
        clone.updateStateForView();
        og = obs(g, g.p0);
        final int c = pos(og, clone);
        Assert.assertTrue(bit(og, c, RlSchemaV2.B_COPY), "copy bit");
        final int[] copy = fact(og, c, "COPY_OF");
        Assert.assertNotNull(copy, "COPY_OF");
        Assert.assertEquals(copy[1], idx("Serra Angel"));
        Assert.assertEquals(og.tokCard[c], idx("Clone"), "D5: the printed card");
        Assert.assertNotNull(fact(og, c, "KW:FLYING"), "a copy of the angel flies");
        // Delver transformed: back face, flying
        final Card delver = add(g.p0, ZoneType.Battlefield, "Delver of Secrets");
        og = obs(g, g.p0);
        Assert.assertFalse(bit(og, pos(og, delver), RlSchemaV2.B_BACK_FACE));
        delver.setState(forge.card.CardStateName.Backside, true);
        delver.setBackSide(true);
        og = obs(g, g.p0);
        Assert.assertTrue(bit(og, pos(og, delver), RlSchemaV2.B_BACK_FACE), "back face up");
        Assert.assertNotNull(fact(og, pos(og, delver), "KW:FLYING"), "Insectile Aberration flies");
        Assert.assertEquals(og.tokCard[pos(og, delver)], idx("Delver of Secrets"), "C3: the full card");
    }

    // ------------------------------------------------------------------------------------------------ W6, W7, W8

    /** W6: a named card, a chosen type, a chosen colour; a secret number reaches only its controller. */
    @Test
    public void w6Choices() {
        final RlKnowledgeTest.Fixture f = v2();
        final Card needle = add(f.p1, ZoneType.Battlefield, "Pithing Needle");
        needle.addNamedCard("Jace, the Mind Sculptor");
        final Card cavern = add(f.p1, ZoneType.Battlefield, "Cavern of Souls");
        cavern.setChosenType("Goblin");
        final Card painter = add(f.p1, ZoneType.Battlefield, "Painter's Servant");
        painter.setChosenColors(List.of("blue"));
        painter.setChosenNumber(3, true); // secret
        final RlFeaturizer.Obs o = obs(f, f.p0);
        final int[] named = fact(o, pos(o, needle), "NAMED_CARD");
        Assert.assertNotNull(named, "Pithing Needle's name");
        Assert.assertEquals(named[1], idx("Jace, the Mind Sculptor"));
        final int[] ty = fact(o, pos(o, cavern), "CHOSEN_TYPE");
        Assert.assertNotNull(ty);
        Assert.assertEquals(ty[1], RlSchemaV2.subtypeId("Goblin"));
        Assert.assertTrue(ty[1] > 0);
        Assert.assertNotNull(fact(o, pos(o, painter), "CHOSEN_COLOR:U"));
        Assert.assertNull(fact(o, pos(o, painter), "CHOSEN_NUMBER"), "a secret number is not shown to the opponent");
        final RlFeaturizer.Obs o1 = obs(f, f.p1);
        final int[] n = fact(o1, pos(o1, painter), "CHOSEN_NUMBER");
        Assert.assertNotNull(n, "its controller knows its secret number");
        Assert.assertEquals(n[2], 3);
    }

    private Card dungeon(final RlKnowledgeTest.Fixture f, final Player p, final String script) {
        final Card d = forge.game.card.CardFactory.getCard(forge.StaticData.instance().getAllTokens().getToken(script), p,
                f.game);
        d.setGamePieceType(forge.card.GamePieceType.DUNGEON);
        f.game.getAction().moveToCommand(d, null);
        return d;
    }

    /** W7: designations by stable ids, a dungeon's room, the ring level, the monarch. */
    @Test
    public void w7Designations() {
        final RlKnowledgeTest.Fixture f = v2();
        f.game.getAction().takeInitiative(f.p0, "CLB");
        f.game.getAction().becomeMonarch(f.p1, "CN2");
        f.p1.incrementRingTemptedYou();
        f.p1.incrementRingTemptedYou();
        Card uc = null;
        for (Card c : f.p0.getCardsIn(ZoneType.Command)) {
            if ("Undercity".equals(c.getName())) {
                uc = c;
            }
        }
        if (uc == null) { // taking the initiative ventures into Undercity; make sure one is there
            uc = dungeon(f, f.p0, "undercity");
        }
        if (uc.getCurrentRoom() == null || uc.getCurrentRoom().isEmpty()) {
            uc.setCurrentRoom("Forge");
        }
        final RlFeaturizer.Obs o = obs(f, f.p0);
        boolean initiative = false, undercity = false, room = false, monarch = false;
        for (int i = 0; i < o.L; i++) {
            if (o.tokZone[i] != RlSchema.Z_COMMAND) {
                continue;
            }
            for (int[] q : facts(o, i)) {
                final String n = RlSchemaV2.FACTS.get(q[0]);
                initiative |= n.equals("DESIG:The Initiative");
                undercity |= n.equals("DESIG:Undercity");
                room |= n.startsWith("ROOM:Undercity/");
                monarch |= n.equals("DESIG:The Monarch");
            }
        }
        Assert.assertTrue(initiative, "The Initiative has a stable id: " + str(o));
        Assert.assertTrue(undercity, "Undercity has a stable id: " + str(o));
        Assert.assertTrue(room, "Undercity's current room: " + str(o));
        Assert.assertTrue(monarch, "The Monarch: " + str(o));
        Assert.assertEquals(o.ctx[RlSchemaV2.C_RING_O], 0.5f, "ring level 2 of 4 (opponent)");
        Assert.assertEquals(o.ctx[RlSchemaV2.C_RING_U], 0f);
        // a dungeon without the initiative, with a room set
        final RlKnowledgeTest.Fixture g = v2();
        final Card lm = dungeon(g, g.p1, "lost_mine_of_phandelver");
        lm.setCurrentRoom("Goblin Lair");
        final RlFeaturizer.Obs og = obs(g, g.p0);
        final int t = pos(og, lm);
        Assert.assertNotNull(fact(og, t, "DESIG:Lost Mine of Phandelver"));
        Assert.assertNotNull(fact(og, t, "ROOM:Lost Mine of Phandelver/Goblin Lair"));
    }

    /** W8: player counters in ctx; counters on cards by type, with counts. */
    @Test
    public void w8Counters() {
        final RlKnowledgeTest.Fixture f = v2();
        f.p0.setCounters(CounterEnumType.ENERGY, 3, null, false);
        f.p1.setCounters(CounterEnumType.EXPERIENCE, 2, null, false);
        f.p1.setCounters(CounterEnumType.RAD, 4, null, false);
        f.p0.setCounters(CounterEnumType.TICKET, 1, null, false);
        final Card bears = add(f.p0, ZoneType.Battlefield, "Grizzly Bears");
        bears.setCounters(CounterEnumType.P1P1, 2);
        final Card saga = add(f.p1, ZoneType.Battlefield, "History of Benalia");
        saga.setCounters(CounterEnumType.LORE, 2);
        final RlFeaturizer.Obs o = obs(f, f.p0);
        Assert.assertEquals(o.ctx[RlSchemaV2.C_ENERGY_U], 0.3f, 1e-6);
        Assert.assertEquals(o.ctx[RlSchemaV2.C_EXPERIENCE_O], 0.2f, 1e-6);
        Assert.assertEquals(o.ctx[RlSchemaV2.C_RAD_O], 0.4f, 1e-6);
        Assert.assertEquals(o.ctx[RlSchemaV2.C_TICKETS_U], 0.1f, 1e-6);
        final int[] p1 = fact(o, pos(o, bears), "COUNTER:P1P1");
        Assert.assertNotNull(p1);
        Assert.assertEquals(p1[2], 2);
        final int[] lore = fact(o, pos(o, saga), "COUNTER:LORE");
        Assert.assertNotNull(lore, "lore counters are their own type");
        Assert.assertEquals(lore[2], 2);
    }

    // ------------------------------------------------------------------------------------------------ W9, W10

    /** W9: own face-down cards show their identity to their controller only; the opponent sees <unk>. */
    @Test
    public void w9OwnFaceDown() {
        final RlKnowledgeTest.Fixture f = v2();
        final Card morph = add(f.p1, ZoneType.Battlefield, "Exalted Angel");
        morph.turnFaceDown(true);
        final Card fore = add(f.p1, ZoneType.Hand, "Saw It Coming");
        final Card ex = f.game.getAction().exile(fore, null, null);
        ex.turnFaceDown(true);
        ex.addMayLookFaceDownExile(f.p1);
        final RlFeaturizer.Obs own = obs(f, f.p1);
        Assert.assertEquals(own.tokCard[pos(own, morph)], idx("Exalted Angel"), "the controller sees its morph");
        Assert.assertEquals(own.tokAttr[pos(own, morph) * RlSchemaV2.N_ATTR + RlSchema.A_FACE_DOWN], 1f);
        Assert.assertEquals(own.tokCard[pos(own, ex)], idx("Saw It Coming"), "and its foretold card");
        final RlFeaturizer.Obs opp = obs(f, f.p0);
        Assert.assertEquals(opp.tokCard[pos(opp, morph)], CardIndex.UNK, "the opponent sees <unk>");
        Assert.assertEquals(opp.tokCard[pos(opp, ex)], CardIndex.UNK);
        Assert.assertTrue(facts(opp, pos(opp, ex)).isEmpty() || noIdentityFacts(opp, pos(opp, ex)));
        // the identity is there to leak (the mutant that resolves it would differ)
        Assert.assertNotEquals(RlFeaturizer.lookupCard(f.feat.index(), morph), CardIndex.UNK);
        // event tail: a face-down cast is the caster's to see
        final RlKnowledgeTest.Fixture g = v2();
        final Card angel = add(g.p1, ZoneType.Hand, "Exalted Angel");
        SpellAbility m = null;
        for (SpellAbility sa : angel.getSpellAbilities()) {
            if (sa.isCastFaceDown()) {
                m = sa;
            }
        }
        Assert.assertNotNull(m);
        m.setActivatingPlayer(g.p1);
        angel.setSplitStateToPlayAbility(m);
        g.game.getStack().add(m);
        Assert.assertEquals(zone(obs(g, g.p1), RlSchema.Z_U_EVENT), List.of(idx("Exalted Angel")));
        Assert.assertEquals(zone(obs(g, g.p0), RlSchema.Z_O_EVENT), List.of(CardIndex.UNK));
    }

    private static boolean noIdentityFacts(final RlFeaturizer.Obs o, final int tok) {
        for (int[] q : facts(o, tok)) {
            final String n = RlSchemaV2.FACTS.get(q[0]);
            if (n.equals("COPY_OF") || n.equals("NAMED_CARD")) {
                return false;
            }
        }
        return true;
    }

    /** W10: per-turn history counts in ctx; zone age on graveyard cards. */
    @Test
    public void w10History() {
        final RlKnowledgeTest.Fixture f = v2();
        for (int i = 0; i < 6; i++) {
            add(f.p0, ZoneType.Library, "Island");
        }
        f.p1.loseLife(3, false, false, null);
        f.p0.drawCard();
        f.p0.drawCard();
        final Card d = add(f.p0, ZoneType.Hand, "Dark Ritual");
        f.p0.discard(d, null, false, null);
        final Card old = add(f.p1, ZoneType.Graveyard, "Lightning Bolt");
        old.setTurnInZone(f.game.getPhaseHandler().getTurn() - 3);
        final RlFeaturizer.Obs o = obs(f, f.p0);
        Assert.assertEquals(o.ctx[RlSchemaV2.C_LIFE_LOST_O], 0.3f, 1e-6);
        Assert.assertEquals(o.ctx[RlSchemaV2.C_LIFE_LOST_U], 0f);
        Assert.assertEquals(o.ctx[RlSchemaV2.C_DRAWN_U], 0.4f, 1e-6);
        Assert.assertEquals(o.ctx[RlSchemaV2.C_DISCARDED_U], 0.2f, 1e-6);
        Card gd = null;
        for (Card c : f.p0.getCardsIn(ZoneType.Graveyard)) {
            gd = c;
        }
        Assert.assertNotNull(gd);
        Assert.assertEquals(o.tokAttr[pos(o, gd) * RlSchemaV2.N_ATTR + RlSchemaV2.A_ZONE_AGE], 0f, "discarded now");
        Assert.assertEquals(o.tokAttr[pos(o, old) * RlSchemaV2.N_ATTR + RlSchemaV2.A_ZONE_AGE], 0.3f, 1e-6);
    }

    // ------------------------------------------------------------------------------------------------ W11, W12

    /** W11: o_seen holds opponent cards seen face up and hidden now, identity only; never one never seen. */
    @Test
    public void w11OpponentSeen() {
        final RlKnowledgeTest.Fixture f = v2();
        for (int i = 0; i < 6; i++) {
            add(f.p1, ZoneType.Library, "Island");
        }
        final Card elves = add(f.p1, ZoneType.Battlefield, "Llanowar Elves");
        final Card neverSeen = add(f.p1, ZoneType.Hand, "Ancestral Recall");
        f.game.getAction().moveToHand(elves, null); // bounced: seen, and known in the hand (zone 16)
        RlFeaturizer.Obs o = obs(f, f.p0);
        Assert.assertEquals(zone(o, RlSchema.Z_O_HAND_KNOWN), List.of(idx("Llanowar Elves")));
        Assert.assertTrue(zone(o, RlSchemaV2.Z_O_SEEN).isEmpty(), "shown in zone 16, so not in o_seen");
        // Brainstorm's put-back: the known hand blurs (W13), the elves move to o_seen
        final Card back = f.p1.getCardsIn(ZoneType.Hand).get(0);
        f.game.getAction().moveToLibrary(back, 0, null);
        o = obs(f, f.p0);
        Assert.assertTrue(zone(o, RlSchema.Z_O_HAND_KNOWN).isEmpty());
        Assert.assertEquals(zone(o, RlSchemaV2.Z_O_SEEN), List.of(idx("Llanowar Elves")), "remembered");
        Assert.assertFalse(zone(o, RlSchemaV2.Z_O_SEEN).contains(idx("Ancestral Recall")), "never seen");
        // identity only: no bits, no facts, no relation on an o_seen token
        for (int i = 0; i < o.L; i++) {
            if (o.tokZone[i] == RlSchemaV2.Z_O_SEEN) {
                Assert.assertEquals(o.tokBits[i], 0L);
                Assert.assertTrue(facts(o, i).isEmpty());
                for (int[] r : rels(o)) {
                    Assert.assertTrue(r[0] != i && r[1] != i);
                }
            }
        }
        // a known library card shuffled away is remembered too
        final RlKnowledgeTest.Fixture g = v2();
        for (int i = 0; i < 6; i++) {
            add(g.p1, ZoneType.Library, "Island");
        }
        final Card top = add(g.p1, ZoneType.Library, "Brainstorm");
        g.p1.getZone(ZoneType.Library).remove(top);
        g.p1.getZone(ZoneType.Library).add(top, 0);
        g.game.getAction().reveal(new CardCollection(top), ZoneType.Library, g.p1, false, "test");
        Assert.assertEquals(zone(obs(g, g.p0), RlSchema.Z_O_LIB_KNOWN), List.of(idx("Brainstorm")));
        g.p1.shuffle(null);
        final RlFeaturizer.Obs og = obs(g, g.p0);
        Assert.assertTrue(zone(og, RlSchema.Z_O_LIB_KNOWN).isEmpty());
        Assert.assertEquals(zone(og, RlSchemaV2.Z_O_SEEN), List.of(idx("Brainstorm")));
        // a seen card that is public again leaves o_seen
        f.game.getAction().moveToGraveyard(elves, null);
        Assert.assertTrue(zone(obs(f, f.p0), RlSchemaV2.Z_O_SEEN).isEmpty());
        Assert.assertTrue(neverSeen.isInZone(ZoneType.Hand));
    }

    /** W12: piles: every face-up pile member carries its pile; the candidates carry the pile index. */
    @Test
    public void w12Piles() {
        final RlKnowledgeTest.Fixture f = v2();
        for (int i = 0; i < 6; i++) {
            add(f.p1, ZoneType.Library, "Island");
        }
        final List<Card> five = new ArrayList<>();
        for (String n : new String[] {"Ancestral Recall", "Brainstorm", "Counterspell", "Mox Pearl", "Dark Ritual"}) {
            final Card c = add(f.p1, ZoneType.Library, n);
            f.p1.getZone(ZoneType.Library).remove(c);
            f.p1.getZone(ZoneType.Library).add(c, 0);
            five.add(c);
        }
        f.game.getAction().reveal(new CardCollection(five), ZoneType.Library, f.p1, false, "Fact or Fiction");
        final CardCollection pileA = new CardCollection(five.subList(0, 2));
        final CardCollection pileB = new CardCollection(five.subList(2, 5));
        final Card fof = add(f.p1, ZoneType.Hand, "Fact or Fiction");
        final RlCandidates.Menu m = RlCandidates.build(f.game, f.p1, "chooseCardsPile", "pile", new JsonObject(),
                new Object[] {fof.getFirstSpellAbility(), pileA, pileB, "False"});
        Assert.assertNotNull(m);
        Assert.assertEquals(m.family, RlSchema.F_PILE);
        final RlFeaturizer.Obs o = f.feat.observe(f.game, f.p1, 0, true, m);
        m.bind(o, f.feat, f.p1);
        Assert.assertEquals(m.ability[0], 0);
        Assert.assertEquals(m.ability[1], 1);
        for (Card c : pileA) {
            Assert.assertNotNull(fact(o, pos(o, c), "PILE:0"), "pile A member " + c);
        }
        for (Card c : pileB) {
            Assert.assertNotNull(fact(o, pos(o, c), "PILE:1"), "pile B member " + c);
        }
        // no pile facts outside a PILE ask
        final RlFeaturizer.Obs plain = obs(f, f.p1);
        for (Card c : five) {
            Assert.assertNull(fact(plain, pos(plain, c), "PILE:0"));
            Assert.assertNull(fact(plain, pos(plain, c), "PILE:1"));
        }
        // face-down piles: sizes only
        final RlCandidates.Menu hidden = RlCandidates.build(f.game, f.p1, "chooseCardsPile", "pile", new JsonObject(),
                new Object[] {fof.getFirstSpellAbility(), pileA, pileB, "True"});
        final RlFeaturizer.Obs oh = f.feat.observe(f.game, f.p1, 0, true, hidden);
        for (Card c : five) {
            Assert.assertNull(fact(oh, pos(oh, c), "PILE:0"));
            Assert.assertNull(fact(oh, pos(oh, c), "PILE:1"));
        }
    }

    /** O1: the own remaining multiset is the decklist minus own visible cards (no deck in the fixture: empty). */
    @Test
    public void o1RestIsEmptyWithoutADeck() {
        final RlKnowledgeTest.Fixture f = v2();
        final RlFeaturizer.Obs o = obs(f, f.p0);
        Assert.assertEquals(o.D, 0);
        Assert.assertEquals(o.Dr, 0);
    }

    static String str(final RlFeaturizer.Obs o) {
        final StringBuilder sb = new StringBuilder("rels[");
        for (int[] r : rels(o)) {
            sb.append(java.util.Arrays.toString(r)).append(' ');
        }
        sb.append("] L=").append(o.L);
        return sb.toString();
    }
}
