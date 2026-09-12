package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Observation of native decisions about the cube's two clone-class creatures
 * (Phyrexian Metamorph, Phantasmal Image) beside a Kiki/Twin engine, from
 * prepared, exactly registered positions.
 *
 * The host supplies the board and nothing else: every cast, copy choice,
 * target, trigger, block and attack is the native AI's. The treated arm is the
 * cube-combo controller under the tree's own policy label; the baseline arm is
 * Forge's Default AI on the identical board. A matched control arm runs this
 * same source against an older tree's frozen classes, so "the policy moved the
 * line" is separable from "the new fixture rows moved it".
 *
 * Cases c1..c6 are design-v67 section 6 and are scored against the frozen v61
 * classes (probe-379) in runs/2026-09-12-metamorph-v67. Cases c7..c12 are v69
 * and are scored against the frozen merged-v67 classes (probe-380) in
 * runs/2026-09-12-clone-fix-v69: the two Phantasmal Image hazard boards, the
 * Twin-hold parity row for an Image in hand, and the three clone-fetch tutor
 * rows. The v69 cases are APPENDED so every v67 case keeps its index and its
 * seed, and the hazard receipt is a separate CLONE_HAZARD_RESULT line so no
 * v67 row's fields move.
 *
 * These are prepared positions, not natural games: no seed sweep, no win rate
 * and no playing-strength claim. Registered in each increment's own
 * registration.md before its scored run; the rows are scored there by that
 * increment's readout, not by this JVM.
 */
public final class CubeCloneKikiSmoke {
    private static final String KIKI = "Kiki-Jiki, Mirror Breaker";
    private static final String TWIN = "Splinter Twin";
    private static final String PEST = "Pestermite";
    private static final String METAMORPH = "Phyrexian Metamorph";
    private static final String IMAGE = "Phantasmal Image";
    private static final String PYRO = "Young Pyromancer";
    /** The decoy body: a 4/4 flier the ordinary AI's getBestAI prefers to a
     * 2/1 Pestermite, so a row where the policy takes the Pestermite is a row
     * where the policy, not the absence of an alternative, made the choice. */
    private static final String DECOY = "Timeless Dragon";
    /** v69 tutor rows. The library decoy is the card the ORDINARY AI fetches:
     * Demonic Tutor's own script says the native AI takes the most expensive
     * valid card, and Recruiter of the Guard's toughness-2 list has to hold a
     * creature its evaluation prefers to a printed 0/0. */
    private static final String DEMONIC = "Demonic Tutor";
    private static final String RECRUITER = "Recruiter of the Guard";
    private static final String GIFT = "Steelshaper's Gift";
    private static final String SWORD = "Sword of the Meek";
    private static final String LIBRARY_DECOY = "Grave Titan";

    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);

    /** design-v67 section 6, then v69's six, each in its own design's order. */
    private static final List<String> CASES = List.of(
            "c1-kiki-clone-beside-decoy",
            "c2-twin-clone-beside-decoy",
            "c3-twin-hold-clone",
            "c4-image-refused",
            "c5-no-body-parity",
            "c6-no-engine-parity",
            // v69, appended so every v67 row keeps its case index and its seed.
            "c7-kiki-image-hazard",
            "c8-twin-image-hazard",
            "c9-twin-hold-image-parity",
            "c10-tutor-demonic-clone",
            "c11-tutor-recruiter-clone",
            "c12-tutor-shape-negative");

    private static int caseIndex(String name) { return CASES.indexOf(name); }

    /** The clone-class card this case is about, for the receipt and for the
     * zone lookup that follows it through its own copy choice. */
    private static String cloneOf(String name) {
        return name.equals("c4-image-refused") || name.equals("c9-twin-hold-image-parity")
                ? IMAGE : METAMORPH;
    }

    /** v69 - the SECOND clone-class card a hazard row carries, or "none". On
     * those boards the ordinary AI enters a Phantasmal Image as a copy of the
     * opponent's untap body; that permanent is then named Pestermite and still
     * carries the sacrifice clause, and the row is about the policy NOT
     * steering onto it. */
    private static String hazardOf(String name) {
        return name.equals("c7-kiki-image-hazard") || name.equals("c8-twin-image-hazard")
                ? IMAGE : "none";
    }

    /** Turns this case is allowed to run for, counted from the prepared turn.
     * The default is "this turn, the opponent's answer, and our own next turn",
     * because native Forge casts a creature spell in MAIN2 while both the Kiki
     * loop (needsMoreCopies) and the Twin loop are MAIN1 decisions: the clone
     * lands the turn it is cast and the engine runs on the next one. c2 adds a
     * spare pair of turns for the Aura it must also deploy; c3 is a decision
     * row about the hold itself and deliberately never reaches our next turn. */
    private static int extraTurns(String name) {
        return switch (name) {
            case "c2-twin-clone-beside-decoy" -> 3;
            case "c3-twin-hold-clone", "c9-twin-hold-image-parity" -> 1;
            // The two hazard rows deploy across turns on purpose: the ordinary
            // AI casts the most expensive affordable creature first, so on a
            // one-turn board the cheap Image would always be the LAST body to
            // enter and would never be the one a battlefield scan meets first.
            // Their land schedule (three lands, then one draw per turn) makes
            // the Image the only affordable spell on the prepared turn.
            case "c7-kiki-image-hazard" -> 6;
            case "c8-twin-image-hazard" -> 4;
            // c11's carrier is a CREATURE, so native Forge casts it in MAIN2
            // and the fetched clone is cast on our NEXT turn; the engine then
            // needs the turn after that. Two extra turns, and nothing else
            // about the row, differ from c10.
            case "c11-tutor-recruiter-clone" -> 4;
            default -> 2;
        };
    }

    private record Placement(String name, ZoneType zone) {}

    private static List<Placement> placements(boolean owner, String name) {
        List<Placement> result = new ArrayList<>();
        if (!owner) {
            // The opponent's public board is the only place a body to copy
            // exists in every case but c5, which is the row that proves the
            // policy declines when there is none.
            switch (name) {
                case "c5-no-body-parity" -> result.add(new Placement(DECOY, ZoneType.Battlefield));
                // The v69 rows keep the opponent's board to ONE body. That is
                // what makes the Phantasmal Image's own copy choice forced -
                // CloneAi filters the offered list to
                // `Permanent.YouDontCtrl,Permanent.nonLegendary` first, and a
                // legendary Kiki we control is not in it - so the hazard is
                // created by the ordinary AI without the fixture steering it,
                // and it is what gives the fetched clone a body to copy.
                case "c3-twin-hold-clone", "c7-kiki-image-hazard", "c8-twin-image-hazard",
                     "c9-twin-hold-image-parity", "c10-tutor-demonic-clone",
                     "c11-tutor-recruiter-clone", "c12-tutor-shape-negative" ->
                        result.add(new Placement(PEST, ZoneType.Battlefield));
                default -> {
                    result.add(new Placement(PEST, ZoneType.Battlefield));
                    result.add(new Placement(DECOY, ZoneType.Battlefield));
                }
            }
            while (result.size() < 40) result.add(new Placement("Forest", ZoneType.Library));
            return result;
        }
        switch (name) {
            case "c1-kiki-clone-beside-decoy" -> {
                result.add(new Placement(KIKI, ZoneType.Battlefield));
                result.add(new Placement(METAMORPH, ZoneType.Hand));
                // Five Islands: {3}{U/P} is payable in blue, and the Kiki that
                // completes the loop is already on the battlefield.
                lands(result, 0, 5, 0);
            }
            case "c2-twin-clone-beside-decoy" -> {
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                result.add(new Placement(METAMORPH, ZoneType.Hand));
                // Eight lands: {2}{R}{R} and {3}{U/P} are both payable on the
                // prepared turn, which is what makes the row about the copy
                // choice rather than about mana.
                lands(result, 4, 4, 0);
            }
            case "c3-twin-hold-clone" -> {
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                result.add(new Placement(METAMORPH, ZoneType.Hand));
                // Four lands: the Aura is payable and the clone is payable, but
                // not both, so the row is about the hold decision and the only
                // creature the Aura could otherwise reach is the decoy we own.
                lands(result, 2, 2, 0);
            }
            case "c4-image-refused" -> {
                result.add(new Placement(KIKI, ZoneType.Battlefield));
                // The same board as c1 with ONE printed fact changed: this
                // clone's copy carries "when this creature becomes the target
                // of a spell or ability, sacrifice it".
                result.add(new Placement(IMAGE, ZoneType.Hand));
                lands(result, 0, 5, 0);
            }
            case "c5-no-body-parity" -> {
                result.add(new Placement(KIKI, ZoneType.Battlefield));
                result.add(new Placement(METAMORPH, ZoneType.Hand));
                lands(result, 0, 5, 0);
            }
            case "c6-no-engine-parity" -> {
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(METAMORPH, ZoneType.Hand));
                lands(result, 0, 5, 0);
            }
            case "c7-kiki-image-hazard" -> {
                // The Kiki hazard board. Three lands on the prepared turn buy
                // the Image ({1}{U}) and nothing else, so the Image is the
                // FIRST body on our battlefield; the fourth land buys the
                // Metamorph a turn later and the fifth buys Kiki, which has
                // haste and can act the turn it lands. Kiki's own battlefield
                // scan therefore meets the Image first: v67 targets it, the
                // Image sacrifices itself and the ability is countered; v69
                // must skip it and take the Metamorph.
                result.add(new Placement(KIKI, ZoneType.Hand));
                result.add(new Placement(IMAGE, ZoneType.Hand));
                result.add(new Placement(METAMORPH, ZoneType.Hand));
                lands(result, 1, 2, 0);
                // The land drops, on top of our own library in draw order. No
                // land is in hand on the prepared turn, which is what caps that
                // turn at three mana.
                for (String land : List.of("Mountain", "Mountain", "Island", "Mountain"))
                    result.add(new Placement(land, ZoneType.Library));
            }
            case "c8-twin-image-hazard" -> {
                // The same hazard under the engine Aura, and the Twin decision
                // is the harsher one: an Aura spent on the Image is destroyed
                // with it the first time the token's untap trigger targets it.
                // Three lands buy the Image alone on the prepared turn; the
                // fourth, two turns later, is the first turn the Aura is
                // payable at all, and that is the turn the hold decides.
                result.add(new Placement(TWIN, ZoneType.Hand));
                result.add(new Placement(IMAGE, ZoneType.Hand));
                result.add(new Placement(METAMORPH, ZoneType.Hand));
                lands(result, 1, 2, 0);
                for (String land : List.of("Mountain", "Island", "Mountain"))
                    result.add(new Placement(land, ZoneType.Library));
            }
            case "c9-twin-hold-image-parity" -> {
                // c3 with the one printed fact changed: the clone in hand is an
                // Image, so cloneAsPartner refuses it and no hold fires.
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                result.add(new Placement(IMAGE, ZoneType.Hand));
                lands(result, 2, 2, 0);
            }
            case "c10-tutor-demonic-clone" -> {
                // The fetch. Kiki is live, no untap body is own-visible, and
                // the only body on any battlefield is the opponent's - which
                // is exactly what cloneAsPartner tests.
                result.add(new Placement(KIKI, ZoneType.Battlefield));
                result.add(new Placement(DEMONIC, ZoneType.Hand));
                result.add(new Placement(LIBRARY_DECOY, ZoneType.Library));
                result.add(new Placement(METAMORPH, ZoneType.Library));
                lands(result, 0, 5, 0, 3);
            }
            case "c11-tutor-recruiter-clone" -> {
                // The same fetch through a printed ChangeType that DOES admit a
                // clone: Recruiter of the Guard searches Creature.toughnessLE2
                // and Phyrexian Metamorph is a printed 0/0.
                result.add(new Placement(KIKI, ZoneType.Battlefield));
                result.add(new Placement(RECRUITER, ZoneType.Hand));
                result.add(new Placement(PYRO, ZoneType.Library));
                result.add(new Placement(METAMORPH, ZoneType.Library));
                lands(result, 0, 5, 4);
            }
            case "c12-tutor-shape-negative" -> {
                // The ChangeType negative, same sorcery shape as c10:
                // Steelshaper's Gift searches Equipment and can never name a
                // clone, so the offered list holds no clone to steer toward and
                // the Metamorph stays in the library. The Sword keeps the
                // offered list non-empty, so this is a real decline rather than
                // an empty search.
                result.add(new Placement(KIKI, ZoneType.Battlefield));
                result.add(new Placement(GIFT, ZoneType.Hand));
                result.add(new Placement(SWORD, ZoneType.Library));
                result.add(new Placement(METAMORPH, ZoneType.Library));
                lands(result, 0, 5, 2);
            }
            default -> throw new AssertionError("unknown case " + name);
        }
        while (result.size() < 40) result.add(new Placement("Forest", ZoneType.Library));
        return result;
    }

    private static void lands(List<Placement> result, int mountains, int islands, int plains) {
        lands(result, mountains, islands, plains, 0);
    }

    /** v69 adds the swamp column for the Demonic Tutor row. The four are
     * appended in this fixed order, so every v67 board's placement list - and
     * therefore its battlefield order and its registered 40 - is unchanged. */
    private static void lands(List<Placement> result, int mountains, int islands, int plains, int swamps) {
        for (int i = 0; i < mountains; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
        for (int i = 0; i < islands; i++) result.add(new Placement("Island", ZoneType.Battlefield));
        for (int i = 0; i < plains; i++) result.add(new Placement("Plains", ZoneType.Battlefield));
        for (int i = 0; i < swamps; i++) result.add(new Placement("Swamp", ZoneType.Battlefield));
    }

    private static Deck deck(boolean owner, String name) {
        Deck result = new Deck("clone-kiki fixture");
        for (Placement p : placements(owner, name)) result.getMain().add(p.name(), 1);
        return result;
    }

    private static void populate(Player player, boolean owner, String name) {
        for (ZoneType z : ZoneType.values()) if (player.getZone(z) != null) player.getZone(z).removeAllCards(true);
        for (Placement p : placements(owner, name)) {
            Card card = Card.fromPaperCard(Objects.requireNonNull(
                    FModel.getMagicDb().getCommonCards().getCard(p.name()), p.name()), player);
            card.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(p.zone()).add(card);
            // Every prepared body has been under its controller since before
            // this turn; summoning sickness is not what any of these rows
            // measure. The clone still enters this turn and is sick when it
            // does, which is why c2 needs our next turn.
            card.setSickness(false);
        }
        TreeMap<String, Integer> actual = new TreeMap<>(), registered = new TreeMap<>();
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) actual.merge(c.getName(), 1, Integer::sum);
        for (var c : player.getRegisteredPlayer().getDeck().getMain()) registered.merge(c.getKey().getName(), c.getValue(), Integer::sum);
        if (!actual.equals(registered) || actual.values().stream().mapToInt(Integer::intValue).sum() != 40)
            throw new AssertionError("registration mismatch " + name + " actual=" + actual + " registered=" + registered);
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("initial mana must be empty");
    }

    /** Every zone a tracked card can be in, including the shared stack. */
    private static final List<ZoneType> LOOKUP = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Stack, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Library, ZoneType.Command);

    /** The one non-token card of this name this player owns, found afresh every
     * time it is needed. Forge replaces the Card object on every zone change,
     * so a reference captured at setup goes stale the moment the card is cast. */
    private static Card own(Game game, Player player, String name) {
        if (name.equals("none")) return null;
        for (ZoneType z : LOOKUP) for (Card c : game.getCardsIn(z))
            if (c.getOwner() == player && !c.isToken() && c.getName().equals(name)) return c;
        return null;
    }

    /** The clone-class card this row tracks, before OR after it has entered as
     * a copy. A clone that has made its choice no longer answers to its printed
     * name -- Forge's copy effect renames it, which is the whole reason the
     * policy needs no battlefield special case -- so it is found by its printed
     * name first and by {@code isCloned()} second. These fixtures register
     * exactly one card that can ever be cloned, so the second lookup is
     * unambiguous. */
    private static Card clone(Game game, Player player, String printed) {
        Card byName = own(game, player, printed);
        if (byName != null) return byName;
        for (ZoneType z : LOOKUP) for (Card c : game.getCardsIn(z))
            if (c.getOwner() == player && !c.isToken() && c.isCloned() && marks(c, printed)) return c;
        return null;
    }

    /** v69 - which of TWO clones on one board this is, by the type its own
     * printed copy effect adds: Phantasmal Image's clone reads
     * {@code AddTypes$ Illusion}, Phyrexian Metamorph's {@code AddTypes$
     * Artifact}. Both then answer to the copied body's NAME, so the name is no
     * longer enough to tell them apart. This is a fixture-side observation of
     * printed card text; it reads nothing the policy reads. On the v67 boards
     * only one clone exists and this marks the same card the name lookup did,
     * which is why those six rows stay byte-identical. */
    private static boolean marks(Card card, String printed) {
        return printed.equals(IMAGE) ? card.getType().hasSubtype("Illusion") : card.isArtifact();
    }

    private static String zoneOf(Card card) {
        return card == null || card.getZone() == null ? "missing" : card.getZone().getZoneType().name();
    }

    private static String attachedName(Card aura) {
        if (aura == null || !aura.isInPlay() || aura.getAttachedTo() == null) return "none";
        return aura.getAttachedTo().getName().replace(' ', '_');
    }

    private static String attachedSide(Player player, Card aura) {
        if (aura == null || !aura.isInPlay() || aura.getAttachedTo() == null) return "none";
        return aura.getAttachedTo().getController() == player ? "self" : "opponent";
    }

    private static int tokensNamed(Player player, String name) {
        if (name.equals("none")) return 0;
        return (int) player.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.isToken() && c.getName().equals(name)).count();
    }

    /** stderr lines the policy printed during the current row. CUBE_* lines are
     * observability only and are never read by a decision; counting them here
     * is a test-side read of the log this JVM is already writing, and every
     * line still reaches the real stderr unchanged. */
    private static int holdLines, releaseLines;
    private static String holdReason = "none", holdSubject = "none",
            releaseReason = "none", releaseSubject = "none";

    private static String tokenOf(String line, String... keys) {
        for (String part : line.trim().split(" "))
            for (String key : keys) if (part.startsWith(key + "=")) return part.substring(key.length() + 1);
        return "unset";
    }

    private static final class CountingErr extends PrintStream {
        CountingErr(PrintStream sink) { super(sink, true); }
        @Override public void println(String line) {
            if (line != null && line.startsWith("CUBE_TWIN_HOLD")) {
                holdLines++;
                if (holdReason.equals("none")) {
                    holdReason = tokenOf(line, "reason");
                    holdSubject = tokenOf(line, "partner", "tutor", "partnerInHand");
                }
            }
            if (line != null && line.startsWith("CUBE_TWIN_RELEASE")) {
                releaseLines++;
                if (releaseReason.equals("none")) {
                    releaseReason = tokenOf(line, "reason");
                    releaseSubject = tokenOf(line, "partner");
                }
            }
            super.println(line);
        }
    }

    private static void run(boolean improved, int seat, String name) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat, name)).setPlayer(
                improved && s == seat ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s) : defaultAi(s)));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "native clone-kiki fixture").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, name);
        populate(opponent, false, name);
        game.setAge(GameStage.Play);
        int startTurn = seat == 0 ? 1 : 2;
        Player goesFirst = seat == 0 ? player : opponent;
        game.getPhaseHandler().setupFirstTurn(goesFirst,
                () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, startTurn));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(67000L + seat * 100L + caseIndex(name));
        holdLines = 0; releaseLines = 0;
        holdReason = "none"; holdSubject = "none"; releaseReason = "none"; releaseSubject = "none";
        int bound = startTurn + extraTurns(name);
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat
                + " phase=MAIN1 case=" + name;
        String printed = cloneOf(name);
        System.out.println("CLONE_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " infoPolicy=CLOSED_REPAIR registered=40 initialMana=0"
                + " startTurn=" + startTurn + " bound=" + bound
                + " clone=" + printed.replace(' ', '_'));
        Set<Integer> stackIds = new HashSet<>();
        int steps = 0, cloneCasts = 0, twinCasts = 0, copyActivations = 0;
        String firstCopied = "none", firstAttached = "none", firstAttachedSide = "none";
        String previous = "";
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= bound && steps < 900) {
            game.getPhaseHandler().mainLoopStep();
            steps++;
            Card aura = own(game, player, TWIN), tracked = clone(game, player, printed);
            if (firstAttached.equals("none")) {
                firstAttached = attachedName(aura);
                firstAttachedSide = attachedSide(player, aura);
            }
            // The copy choice, read off the clone itself the first step after
            // it has made one. This is the D-receipt of this increment.
            if (firstCopied.equals("none") && tracked != null && tracked.isCloned())
                firstCopied = tracked.getName().replace(' ', '_');
            for (var item : game.getStack()) if (stackIds.add(item.getId())) {
                var sa = item.getSpellAbility();
                String host = sa.getHostCard().getName();
                boolean ours = sa.getActivatingPlayer() == player;
                if (ours && host.equals(printed) && sa.isSpell()) cloneCasts++;
                if (ours && host.equals(TWIN) && sa.isSpell()) twinCasts++;
                if (ours && sa.getApi() == forge.game.ability.ApiType.CopyPermanent && sa.isActivatedAbility())
                    copyActivations++;
                System.out.println("CLONE_STACK " + key + " step=" + steps + " source=" + host.replace(' ', '_')
                        + " api=" + sa.getApi() + " ours=" + ours + " trigger=" + sa.isTrigger()
                        + " spell=" + sa.isSpell() + " targets=" + sa.getTargets().getTargetCards().size()
                        + " costs=" + sa.getPayCosts());
            }
            String state = "turn=" + game.getPhaseHandler().getTurn()
                    + " currentPhase=" + game.getPhaseHandler().getPhase()
                    + " life=" + player.getLife() + " opponentLife=" + opponent.getLife()
                    + " cloneZone=" + zoneOf(tracked)
                    + " cloneName=" + (tracked == null ? "missing" : tracked.getName().replace(' ', '_'))
                    + " twinZone=" + zoneOf(aura) + " twinOn=" + attachedName(aura)
                    + " bodyTokens=" + tokensNamed(player, PEST)
                    + " creatures=" + player.getCreaturesInPlay().size()
                    + " mana=" + player.getManaPool().totalMana();
            if (!state.equals(previous)) System.out.println("CLONE_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 900) throw new AssertionError("native step budget exhausted " + key);
        Card aura = own(game, player, TWIN), engine = own(game, player, KIKI),
                tracked = clone(game, player, printed);
        String copied = tracked != null && tracked.isCloned() ? tracked.getName().replace(' ', '_') : "none";
        System.out.println("CLONE_RESULT " + key + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " steps=" + steps + " turns=" + game.getPhaseHandler().getTurn()
                + " cloneCasts=" + cloneCasts + " cloneZone=" + zoneOf(tracked)
                + " cloneCopied=" + copied + " firstCopied=" + firstCopied
                + " copyActivations=" + copyActivations
                + " bodyTokens=" + tokensNamed(player, PEST)
                + " twinCasts=" + twinCasts + " twinZone=" + zoneOf(aura)
                + " twinStillInHand=" + (aura != null && aura.isInZone(ZoneType.Hand))
                + " attachedTo=" + attachedName(aura) + " attachedSide=" + attachedSide(player, aura)
                + " firstAttachedTo=" + firstAttached + " firstAttachedSide=" + firstAttachedSide
                + " kikiZone=" + zoneOf(engine)
                + " creatures=" + player.getCreaturesInPlay().size()
                + " life=" + player.getLife() + " opponentLife=" + opponent.getLife());
        System.out.println("CLONE_HOLD_RESULT " + key + " holdLines=" + holdLines
                + " releaseLines=" + releaseLines + " holdReason=" + holdReason
                + " holdSubject=" + holdSubject + " releaseReason=" + releaseReason
                + " releaseSubject=" + releaseSubject);
        // v69: a SEPARATE receipt line, printed only by a hazard row, so every
        // v67 row's CLONE_RESULT and CLONE_HOLD_RESULT keep their exact fields.
        String hazardName = hazardOf(name);
        if (!hazardName.equals("none")) {
            Card hazard = clone(game, player, hazardName);
            System.out.println("CLONE_HAZARD_RESULT " + key + " hazard=" + hazardName.replace(' ', '_')
                    + " hazardZone=" + zoneOf(hazard)
                    + " hazardName=" + (hazard == null ? "missing" : hazard.getName().replace(' ', '_'))
                    + " hazardCloned=" + (hazard != null && hazard.isCloned())
                    + " hazardAttached=" + (hazard != null && hazard.isInPlay()
                        && hazard.isEnchanted())
                    + " bodyZone=" + zoneOf(tracked)
                    + " bodyName=" + (tracked == null ? "missing" : tracked.getName().replace(' ', '_')));
        }
    }

    private static forge.ai.LobbyPlayerAi defaultAi(int seat) {
        var lobby = new forge.ai.LobbyPlayerAi("Default-" + seat, null);
        lobby.setAiProfile("Default");
        return lobby;
    }

    private record Row(int seat, String name) {}

    private static List<Row> rows(String suite) {
        List<Row> rows = new ArrayList<>();
        List<String> selected = switch (suite) {
            case "wins" -> List.of("c1-kiki-clone-beside-decoy", "c2-twin-clone-beside-decoy");
            case "parity" -> List.of("c4-image-refused", "c5-no-body-parity", "c6-no-engine-parity");
            case "hazard" -> List.of("c7-kiki-image-hazard", "c8-twin-image-hazard",
                    "c9-twin-hold-image-parity");
            case "fetch" -> List.of("c10-tutor-demonic-clone", "c11-tutor-recruiter-clone",
                    "c12-tutor-shape-negative");
            default -> CASES;
        };
        for (String name : selected) rows.add(new Row(0, name));
        return rows;
    }

    public static void main(String[] args) {
        try {
            System.setErr(new CountingErr(System.err));
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "clone-kiki-native-fixture-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (String card : List.of(KIKI, TWIN, PEST, METAMORPH, IMAGE, PYRO, DECOY,
                    DEMONIC, RECRUITER, GIFT, SWORD, LIBRARY_DECOY,
                    "Forest", "Island", "Mountain", "Plains", "Swamp"))
                StaticData.instance().attemptToLoadCard(card);
            List<Row> rows = rows(args.length > 2 ? args[2] : "clone");
            for (Row row : rows) run(args[1].equals("improved"), row.seat(), row.name());
            System.out.println("CLONE_SUITE_COMPLETE cases=" + rows.size());
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
