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

/** Observation of native decisions about the cube's engine Aura (Splinter
 * Twin) from prepared, exactly registered positions.
 *
 * The host supplies the board and nothing else: every cast, target, trigger,
 * block and attack is the native AI's. The treated arm is the cube-combo
 * controller under policy v53 (design-v53-twin-assembly.md, decisions D1 and
 * D2); the baseline arm is Forge's Default AI on the identical board. A
 * matched control arm runs this same source against the frozen v52 classes, so
 * "v53 moved the line" is separable from "the new fixture rows moved it".
 *
 * These are prepared positions, not natural games: no seed sweep, no win rate
 * and no playing-strength claim. Registered in
 * runs/2026-09-12-twin-assembly-v53/registration.md before any run; the
 * TWIN_RESULT rows are scored there by readout.mjs, not by this JVM.
 */
public final class CubeTwinAssemblySmoke {
    private static final String TWIN = "Splinter Twin";
    private static final String KIKI = "Kiki-Jiki, Mirror Breaker";
    private static final String PEST = "Pestermite";
    private static final String EXARCH = "Deceiver Exarch";
    private static final String CONSCRIPTS = "Zealous Conscripts";
    private static final String ANGEL = "Restoration Angel";
    private static final String PYRO = "Young Pyromancer";
    private static final String RECRUITER = "Imperial Recruiter";
    private static final String TRINKET = "Trinket Mage";

    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);

    /** The nine prepared cases of design-v53 section 3, in the design's order. */
    private static final List<String> CASES = List.of(
            "t1-partner-ready",
            "t2-partner-beside-decoy",
            "t3-hold",
            "t4-no-partner-parity",
            "t5-both-in-hand",
            "t6-kiki-parity",
            "t7-resto-not-partner",
            "t8-enemy-partner",
            "t9-hold-then-assemble");

    /** The design's seat-1 variants: the same positions from the seat that acts
     * after the opponent's turn 1. */
    private static final List<String> SEAT1 = List.of("t2-partner-beside-decoy", "t3-hold");

    /** design-v61 section 6: the five prepared cases of the conditioned hold,
     * in their own suite so the nine v53 cases keep their exact receipt shape
     * and the `twin` regression group stays comparable line for line. */
    private static final List<String> HOLD_CASES = List.of(
            "h1a-hold-castable",
            "h1b-release-uncastable",
            "h2a-hold-tutor",
            "h2b-tutor-cannot-fetch",
            "h2c-hold-then-assemble");

    /** A stable, collision-free audit seed index across both case lists: the
     * v53 cases keep the exact index they had, so their seeds - and therefore
     * their whole receipt - cannot move. */
    private static int caseIndex(String name) {
        return CASES.contains(name) ? CASES.indexOf(name) : 20 + HOLD_CASES.indexOf(name);
    }

    /** The Twin partner each case is about, for the receipt. A Twin partner is
     * an untap body; Restoration Angel is a Kiki partner only and t7 therefore
     * reports none. t8's Pestermite belongs to the opponent, so our own-zone
     * lookup reports it missing - which is the point of that row. */
    private static String partnerOf(String name) {
        return switch (name) {
            case "t1-partner-ready", "t6-kiki-parity", "t8-enemy-partner", "t9-hold-then-assemble" -> PEST;
            case "t2-partner-beside-decoy", "t3-hold" -> CONSCRIPTS;
            case "t5-both-in-hand" -> EXARCH;
            // v61: h2a and h2b register no Twin partner anywhere in the 40 -
            // h2a is the row that proves the hold decision does not read the
            // library, so "none" there is the point of the row.
            case "h1a-hold-castable", "h1b-release-uncastable", "h2c-hold-then-assemble" -> PEST;
            default -> "none";
        };
    }

    /** Turns this case is allowed to run for, counted from the prepared turn.
     * The design's default is "this turn and the opponent's answer"; t5 and t9
     * are the two cases whose line cannot complete before our next turn. */
    private static int extraTurns(String name) {
        // h2c needs the turn the tutor is cast on, the opponent's answer, and
        // our own next turn - the first on which the Aura and the fetched body
        // can both be paid for - with one spare pair of turns.
        if (name.equals("h2c-hold-then-assemble")) return 4;
        return name.equals("t5-both-in-hand") || name.equals("t9-hold-then-assemble") ? 2 : 1;
    }

    private record Placement(String name, ZoneType zone) {}

    private static List<Placement> placements(boolean owner, String name) {
        List<Placement> result = new ArrayList<>();
        if (!owner) {
            // A plain opponent: nothing but a library, except t8, where the
            // opponent owns the only Twin partner in the game.
            if (name.equals("t8-enemy-partner")) result.add(new Placement(PEST, ZoneType.Battlefield));
            while (result.size() < 40) result.add(new Placement("Forest", ZoneType.Library));
            return result;
        }
        switch (name) {
            case "t1-partner-ready" -> {
                result.add(new Placement(PEST, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                lands(result, 2, 2, 0);
            }
            case "t2-partner-beside-decoy" -> {
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(CONSCRIPTS, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                lands(result, 2, 2, 0);
            }
            case "t3-hold" -> {
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                // Conscripts costs {4}{R}: deliberately more than the four
                // lands can pay. D2 does not forecast castability, and this is
                // the registered form of that stated limitation.
                result.add(new Placement(CONSCRIPTS, ZoneType.Hand));
                lands(result, 2, 2, 0);
            }
            case "t4-no-partner-parity" -> {
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                lands(result, 2, 2, 0);
            }
            case "t5-both-in-hand" -> {
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                result.add(new Placement(EXARCH, ZoneType.Hand));
                // Seven lands: both halves are payable in one turn.
                lands(result, 3, 4, 0);
            }
            case "t6-kiki-parity" -> {
                result.add(new Placement(PEST, ZoneType.Battlefield));
                result.add(new Placement(KIKI, ZoneType.Hand));
                lands(result, 3, 2, 0);
            }
            case "t7-resto-not-partner" -> {
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                // A Plains so the Angel is a genuinely castable alternative:
                // the row must show the policy ignoring it, not the mana base.
                result.add(new Placement(ANGEL, ZoneType.Hand));
                lands(result, 2, 1, 1);
            }
            case "t8-enemy-partner" -> {
                result.add(new Placement(TWIN, ZoneType.Hand));
                lands(result, 2, 2, 0);
            }
            case "t9-hold-then-assemble" -> {
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                result.add(new Placement(PEST, ZoneType.Hand));
                lands(result, 2, 2, 0);
                // Added to the library first, so it is the top card and the
                // next draw is a known land rather than an unknown filler.
                result.add(new Placement("Island", ZoneType.Library));
            }
            // ---- design-v61, the conditioned hold. Splinter Twin is {2}{R}{R},
            // so every board that can cast the Aura has red; a Twin partner is
            // out of colour only when it is one of the two BLUE bodies on a
            // board with no blue, which is exactly h1b.
            case "h1a-hold-castable" -> {
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                result.add(new Placement(PEST, ZoneType.Hand));
                // Pestermite is {2}{U} against two Islands: in colour, and
                // CMC 3 <= 4 sources + 2. H1 keeps v60's hold.
                lands(result, 2, 2, 0);
            }
            case "h1b-release-uncastable" -> {
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                // The same hand and the same partner as h1a, on a board with no
                // blue source anywhere - not in play and not in hand. One
                // printed fact differs between the two rows.
                result.add(new Placement(PEST, ZoneType.Hand));
                lands(result, 4, 0, 0);
            }
            case "h2a-hold-tutor" -> {
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                // Imperial Recruiter searches Creature.powerLE2, which admits
                // Pestermite and Deceiver Exarch. NO Twin partner is registered
                // anywhere in these 40 cards: the hold must still fire, because
                // the decision reads the tutor's printed text and never the
                // library.
                result.add(new Placement(RECRUITER, ZoneType.Hand));
                lands(result, 2, 2, 0);
            }
            case "h2b-tutor-cannot-fetch" -> {
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                // Trinket Mage is the same shape of tutor with a ChangeType
                // that cannot name a creature at all (Artifact.cmcLE1).
                result.add(new Placement(TRINKET, ZoneType.Hand));
                lands(result, 2, 2, 0);
            }
            case "h2c-hold-then-assemble" -> {
                result.add(new Placement(PYRO, ZoneType.Battlefield));
                result.add(new Placement(TWIN, ZoneType.Hand));
                result.add(new Placement(RECRUITER, ZoneType.Hand));
                // Six lands: the tutor ({2}{R}) and the body it fetches ({2}{U})
                // are both payable on the prepared turn, and the Aura is not.
                lands(result, 3, 3, 0);
                // Added first, so it is the top card; the search itself is a
                // native library search and shuffles afterwards.
                result.add(new Placement(PEST, ZoneType.Library));
            }
            default -> throw new AssertionError("unknown case " + name);
        }
        while (result.size() < 40) result.add(new Placement("Forest", ZoneType.Library));
        return result;
    }

    private static void lands(List<Placement> result, int mountains, int islands, int plains) {
        for (int i = 0; i < mountains; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
        for (int i = 0; i < islands; i++) result.add(new Placement("Island", ZoneType.Battlefield));
        for (int i = 0; i < plains; i++) result.add(new Placement("Plains", ZoneType.Battlefield));
    }

    private static Deck deck(boolean owner, String name) {
        Deck result = new Deck("twin-assembly fixture");
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
            // Every prepared body has been under our control since before this
            // turn; summoning sickness is not what any of these rows measure.
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
     * so a reference captured at setup goes stale the moment the card is cast -
     * which is exactly when these rows stop being able to see it. */
    private static Card own(Game game, Player player, String name) {
        if (name.equals("none")) return null;
        for (ZoneType z : LOOKUP) for (Card c : game.getCardsIn(z))
            if (c.getOwner() == player && !c.isToken() && c.getName().equals(name)) return c;
        return null;
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

    /** The first value of a {@code key=} token on a CUBE_TWIN_* line, or
     * {@code unset} when the line does not carry that key - which is what the
     * matched v60 control's own line shape produces, and is recorded rather
     * than repaired. */
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
        Game game = new Match(rules, players, "native twin-assembly fixture").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, name);
        populate(opponent, false, name);
        game.setAge(GameStage.Play);
        // Seat 0 is prepared on its own turn 1. Seat 1 is prepared at its own
        // MAIN1 with the turn counter at 2, which is the design's "after the
        // opponent's turn 1" position; that turn is skipped, not played, so
        // neither player has drawn or untapped for it.
        int startTurn = seat == 0 ? 1 : 2;
        Player goesFirst = seat == 0 ? player : opponent;
        game.getPhaseHandler().setupFirstTurn(goesFirst,
                () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, startTurn));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(53000L + seat * 100L + caseIndex(name));
        holdLines = 0; releaseLines = 0;
        holdReason = "none"; holdSubject = "none"; releaseReason = "none"; releaseSubject = "none";
        int bound = startTurn + extraTurns(name);
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat
                + " phase=MAIN1 case=" + name;
        String partner = partnerOf(name);
        System.out.println("TWIN_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " infoPolicy=CLOSED_REPAIR registered=40 initialMana=0"
                + " startTurn=" + startTurn + " bound=" + bound
                + " partner=" + partner.replace(' ', '_'));
        Set<Integer> stackIds = new HashSet<>();
        int steps = 0, twinCasts = 0, kikiCasts = 0, partnerCasts = 0, copyActivations = 0;
        String castTarget = "none", firstAttached = "none", firstAttachedSide = "none";
        String previous = "";
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= bound && steps < 900) {
            game.getPhaseHandler().mainLoopStep();
            steps++;
            Card aura = own(game, player, TWIN);
            if (firstAttached.equals("none")) {
                firstAttached = attachedName(aura);
                firstAttachedSide = attachedSide(player, aura);
            }
            for (var item : game.getStack()) if (stackIds.add(item.getId())) {
                var sa = item.getSpellAbility();
                String host = sa.getHostCard().getName();
                boolean ours = sa.getActivatingPlayer() == player;
                if (ours && host.equals(TWIN) && sa.isSpell()) {
                    twinCasts++;
                    // The D1 receipt: the target the ordinary AI chose for the
                    // Aura, read off the stack item itself.
                    var targets = sa.getTargets().getTargetCards();
                    if (!targets.isEmpty()) castTarget = targets.get(0).getName().replace(' ', '_')
                            + (targets.get(0).getController() == player ? "/self" : "/opponent");
                }
                if (ours && host.equals(KIKI) && sa.isSpell()) kikiCasts++;
                if (ours && !partner.equals("none") && host.equals(partner) && sa.isSpell()) partnerCasts++;
                if (ours && sa.getApi() == forge.game.ability.ApiType.CopyPermanent && sa.isActivatedAbility())
                    copyActivations++;
                System.out.println("TWIN_STACK " + key + " step=" + steps + " source=" + host.replace(' ', '_')
                        + " api=" + sa.getApi() + " ours=" + ours + " trigger=" + sa.isTrigger()
                        + " spell=" + sa.isSpell() + " targets=" + sa.getTargets().getTargetCards().size()
                        + " costs=" + sa.getPayCosts());
            }
            String state = "turn=" + game.getPhaseHandler().getTurn()
                    + " currentPhase=" + game.getPhaseHandler().getPhase()
                    + " life=" + player.getLife() + " opponentLife=" + opponent.getLife()
                    + " twinZone=" + zoneOf(aura) + " twinOn=" + attachedName(aura)
                    + " partnerZone=" + zoneOf(own(game, player, partner))
                    + " partnerTokens=" + tokensNamed(player, partner)
                    + " creatures=" + player.getCreaturesInPlay().size()
                    + " mana=" + player.getManaPool().totalMana();
            if (!state.equals(previous)) System.out.println("TWIN_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 900) throw new AssertionError("native step budget exhausted " + key);
        Card aura = own(game, player, TWIN), engine = own(game, player, KIKI), body = own(game, player, partner);
        System.out.println("TWIN_RESULT " + key + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " steps=" + steps + " turns=" + game.getPhaseHandler().getTurn()
                + " holdLines=" + holdLines + " twinCasts=" + twinCasts + " kikiCasts=" + kikiCasts
                + " partnerCasts=" + partnerCasts + " copyActivations=" + copyActivations
                + " castTarget=" + castTarget
                + " attachedTo=" + attachedName(aura) + " attachedSide=" + attachedSide(player, aura)
                + " firstAttachedTo=" + firstAttached + " firstAttachedSide=" + firstAttachedSide
                + " twinZone=" + zoneOf(aura) + " twinStillInHand=" + (aura != null && aura.isInZone(ZoneType.Hand))
                + " kikiZone=" + zoneOf(engine)
                + " partner=" + partner.replace(' ', '_') + " partnerZone=" + zoneOf(body)
                + " partnerTokens=" + tokensNamed(player, partner)
                + " life=" + player.getLife() + " opponentLife=" + opponent.getLife());
        // v61: the conditioned-hold receipt, printed ONLY for the new cases, so
        // every TWIN_RESULT row of the v53 suite keeps its exact shape.
        if (HOLD_CASES.contains(name))
            System.out.println("TWIN_HOLD_RESULT " + key + " holdLines=" + holdLines
                    + " releaseLines=" + releaseLines + " holdReason=" + holdReason
                    + " holdSubject=" + holdSubject + " releaseReason=" + releaseReason
                    + " releaseSubject=" + releaseSubject);
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
            case "assembly" -> List.of("t1-partner-ready", "t2-partner-beside-decoy", "t5-both-in-hand",
                    "t6-kiki-parity", "t9-hold-then-assemble");
            case "parity" -> List.of("t3-hold", "t4-no-partner-parity", "t7-resto-not-partner", "t8-enemy-partner");
            case "hold" -> HOLD_CASES;
            default -> CASES;
        };
        for (String name : selected) rows.add(new Row(0, name));
        if (suite.equals("twin")) for (String name : SEAT1) rows.add(new Row(1, name));
        return rows;
    }

    public static void main(String[] args) {
        try {
            System.setErr(new CountingErr(System.err));
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "twin-assembly-native-fixture-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (String card : List.of(TWIN, KIKI, PEST, EXARCH, CONSCRIPTS, ANGEL, PYRO,
                    RECRUITER, TRINKET, "Forest", "Island", "Mountain", "Plains"))
                StaticData.instance().attemptToLoadCard(card);
            List<Row> rows = rows(args.length > 2 ? args[2] : "twin");
            for (Row row : rows) run(args[1].equals("improved"), row.seat(), row.name());
            System.out.println("TWIN_SUITE_COMPLETE cases=" + rows.size());
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
