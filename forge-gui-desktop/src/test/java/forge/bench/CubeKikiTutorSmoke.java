package forge.bench;

import forge.StaticData;
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
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** Registered prepared positions for the library-selection decision of a
 * "search your library, then put that card on top" tutor (Imperial Seal,
 * Vampiric Tutor) and for the revealed-order decision of Ponder, with a Kiki
 * pair one card short. Diagnostic of native decisions only: the host supplies
 * the board and nothing else — every cast, target, trigger and block is the
 * native AI's. These are exactly registered 40-card synthetic positions, not
 * natural openings, not a win rate and not a strength claim.
 *
 * Implements the MUST-MOVE / MUST-NOT-MOVE tables of
 * runs/2026-09-11-kiki-natural-diagnosis/diagnosis.md section 3 candidate C1.
 */
public final class CubeKikiTutorSmoke {
    private static final String KIKI = "Kiki-Jiki, Mirror Breaker";
    private static final String TWIN = "Splinter Twin";
    private static final String BODY = "Pestermite";
    private static final String SECOND_BODY = "Zealous Conscripts";
    private static final String SPARE_BODY = "Deceiver Exarch";
    private static final String DECOY = "Grave Titan";
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);

    /** diagnosis C1 MUST-MOVE M1..M6 */
    private static final List<String> MUST_MOVE = List.of(
            "seal:twin-hand", "seal:kiki-hand", "seal:kiki-board",
            "ponder:kiki-hand", "vamp:kiki-hand", "seal:two-bodies");
    /** diagnosis C1 MUST-NOT-MOVE N1, N2, N3, N7 (N4/N6 are probes, N5 is the
     * preserved Doomsday suite, N8 is the baseline arm of every case). */
    private static final List<String> MUST_NOT_MOVE = List.of(
            "seal:no-half", "seal:both-halves", "seal:no-red", "seal:lethal-now");

    private record Placement(String name, ZoneType zone) {}

    private static String tutorOf(String control) {
        return switch (control.split(":")[0]) {
            case "ponder" -> "Ponder";
            case "vamp" -> "Vampiric Tutor";
            default -> "Imperial Seal";
        };
    }

    private static String variant(String control) { return control.split(":", 2)[1]; }

    private static List<Placement> placements(boolean owner, String control) {
        List<Placement> result = new ArrayList<>();
        String variant = variant(control);
        if (!owner) {
            // The lethal control needs an opponent with nothing at all; every
            // other case keeps a real blocker so no ordinary attack is lethal.
            if (!variant.equals("lethal-now")) result.add(new Placement("Grizzly Bears", ZoneType.Battlefield));
            while (result.size() < 40) result.add(new Placement("Forest", ZoneType.Library));
            return result;
        }
        result.add(new Placement(tutorOf(control), ZoneType.Hand));
        // Ten untapped lands: enough to cast the tutor now and both halves on a
        // later turn. The colour census is the whole point of the no-red case,
        // so that variant has no red source anywhere in the registered 40.
        boolean red = !variant.equals("no-red");
        for (int i = 0; i < 4; i++) result.add(new Placement(red ? "Mountain" : "Island", ZoneType.Battlefield));
        for (int i = 0; i < 4; i++) result.add(new Placement("Island", ZoneType.Battlefield));
        for (int i = 0; i < 2; i++) result.add(new Placement("Swamp", ZoneType.Battlefield));
        switch (variant) {
            case "twin-hand" -> result.add(new Placement(TWIN, ZoneType.Hand));
            case "kiki-hand", "two-bodies", "no-red" -> result.add(new Placement(KIKI, ZoneType.Hand));
            case "kiki-board", "both-halves", "lethal-now" -> result.add(new Placement(KIKI, ZoneType.Battlefield));
            case "no-half" -> { } // no engine half exists anywhere in the 40
            default -> throw new AssertionError("unknown variant " + variant);
        }
        // The decoy goes in first, so it - not the body - starts on top of the
        // library. The card native Forge's own imperial_seal.txt says the
        // ordinary AI will fetch: "the most expensive valid card in the
        // library". For Ponder this is also what makes the revealed order wrong
        // to begin with, so an unchanged order is a real decline, not a no-op.
        result.add(new Placement(DECOY, ZoneType.Library));
        result.add(new Placement(BODY, variant.equals("both-halves") ? ZoneType.Hand : ZoneType.Library));
        if (variant.equals("two-bodies")) result.add(new Placement(SECOND_BODY, ZoneType.Library));
        // The complete-pair control keeps a redundant, interchangeable and
        // payable body in the library, so declining there is a real decline on
        // a real pair-completing candidate rather than an empty offered list.
        if (variant.equals("both-halves")) result.add(new Placement(SPARE_BODY, ZoneType.Library));
        while (result.size() < 40) result.add(new Placement("Island", ZoneType.Library));
        return result;
    }

    private static Deck deck(boolean owner, String control) {
        Deck result = new Deck("Kiki tutor selection diagnostic");
        for (Placement p : placements(owner, control)) result.getMain().add(p.name(), 1);
        return result;
    }

    private static void populate(Player player, boolean owner, String control) {
        for (ZoneType z : ZoneType.values()) if (player.getZone(z) != null) player.getZone(z).removeAllCards(true);
        for (Placement p : placements(owner, control)) {
            Card card = Card.fromPaperCard(Objects.requireNonNull(
                    FModel.getMagicDb().getCommonCards().getCard(p.name()), p.name()), player);
            card.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(p.zone()).add(card);
            card.setSickness(false);
        }
        TreeMap<String, Integer> actual = new TreeMap<>(), registered = new TreeMap<>();
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) actual.merge(c.getName(), 1, Integer::sum);
        for (var c : player.getRegisteredPlayer().getDeck().getMain()) registered.merge(c.getKey().getName(), c.getValue(), Integer::sum);
        if (!actual.equals(registered) || actual.values().stream().mapToInt(Integer::intValue).sum() != 40)
            throw new AssertionError("registration mismatch");
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("initial mana must be empty");
    }

    private static Card own(Player player, ZoneType zone, String name) {
        for (Card card : player.getCardsIn(zone)) if (card.getName().equals(name)) return card;
        return null;
    }

    private static String libraryTop(Player player) {
        var top = player.getCardsIn(ZoneType.Library, 1);
        return top.isEmpty() ? "empty" : top.getFirst().getName();
    }

    private static String zoneOf(Player player, String name) {
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) if (c.getName().equals(name)) return z.name();
        return "missing";
    }

    private static int selectionChanges(Player player) {
        return player.getController() instanceof forge.ai.CubeComboPlayerController c ? c.getComboSelectionChanges() : 0;
    }

    /** Deterministic, non-game receipts for the predicate table. The first
     * block is read-only on the live game: the snapshot is compared before and
     * after and the bench RNG boundary must not move. The two controller-level
     * probes afterwards go through super(), which runs the ordinary AI, so they
     * sit outside that window; they run before the game loop and their effect
     * on the intervention counter is returned so the fixture can subtract it.
     * Only the improved arm runs these. */
    private static int probePredicate(Player player, Player opponent, String control, PhaseType phase) {
        int probeChanges = 0;
        try {
            var before = snapshot(player);
            var rng = BenchRandomAudit.begin();
            Card tutor = own(player, ZoneType.Hand, tutorOf(control));
            Card body = own(player, ZoneType.Library, BODY);
            if (body == null) body = own(player, ZoneType.Library, SPARE_BODY);
            SpellAbility search = tutor == null || tutor.getSpellAbilities().isEmpty() ? null
                    : tutor.getSpellAbilities().get(0).copy(player);
            String selected = "no-probe", opponentOwned = "no-probe", notInLibrary = "no-probe";
            if (search != null && body != null) {
                Card pick = forge.ai.CubeComboAi.chooseTutorPartner(player, search, new CardCollection(body));
                selected = pick == null ? "null" : pick.getName();
                // N6: a card we do not own, offered on the same API.
                Card foreign = opponent.getCardsIn(ZoneType.Library).isEmpty() ? null
                        : opponent.getCardsIn(ZoneType.Library).getFirst();
                if (foreign != null) {
                    Card pickForeign = forge.ai.CubeComboAi.chooseTutorPartner(player, search, new CardCollection(foreign));
                    opponentOwned = pickForeign == null ? "null" : pickForeign.getName();
                }
                // N6: a genuine Kiki half of ours, offered from a zone that is
                // not our library. It must not be selected.
                Card outside = own(player, ZoneType.Hand, KIKI);
                if (outside == null) outside = own(player, ZoneType.Hand, TWIN);
                if (outside == null) outside = own(player, ZoneType.Hand, BODY);
                if (outside == null) outside = own(player, ZoneType.Battlefield, KIKI);
                if (outside != null) {
                    Card pickOutside = forge.ai.CubeComboAi.chooseTutorPartner(player, search, new CardCollection(outside));
                    notInLibrary = pickOutside == null ? "null" : pickOutside.getName();
                }
            }
            if (!before.equals(snapshot(player)))
                throw new AssertionError("selection probe changed native state: " + before + " -> " + snapshot(player));
            BenchRandomAudit.assertUnchanged(rng, "kiki-selection-preview");
            System.out.println("KIKI_SELECT_PROBE control=" + control + " phase=" + phase
                    + " selected=" + selected + " opponentOwned=" + opponentOwned + " notInLibrary=" + notInLibrary);
            if (!opponentOwned.equals("no-probe") && !opponentOwned.equals("null"))
                throw new AssertionError("N6: a card we do not own must never be selected: " + control);
            if (!notInLibrary.equals("no-probe") && !notInLibrary.equals("null"))
                throw new AssertionError("N6: a card outside our own library must never be selected: " + control);
            if (Boolean.getBoolean("forge.test.requireKikiSelection")) {
                // P1: the hand-aware positive. Matched v42 returns null here.
                if (variant(control).equals("kiki-hand") && !selected.equals(BODY))
                    throw new AssertionError("P1: an engine half in hand must complete the pair: " + control + " got " + selected);
                // P5: the lethal gate declines; the same shape without lethality selects.
                if (variant(control).equals("lethal-now") && phase == PhaseType.MAIN1 && !selected.equals("null"))
                    throw new AssertionError("P5: an available ordinary win must not be postponed: " + selected);
                if (variant(control).equals("kiki-board") && phase == PhaseType.MAIN1 && !selected.equals(BODY))
                    throw new AssertionError("P5 reference: the non-lethal battlefield case must select: " + selected);
                if (variant(control).equals("no-half") && !selected.equals("null"))
                    throw new AssertionError("N1: no half means no selection: " + selected);
                if (variant(control).equals("both-halves") && !selected.equals("null"))
                    throw new AssertionError("N2: a complete pair must not consume the selection: " + selected);
                if (variant(control).equals("no-red") && !selected.equals("null"))
                    throw new AssertionError("N3: an uncastable pair must not consume the selection: " + selected);
            }
            // N4, outside the snapshot window because super() runs the ordinary
            // AI for another decider: our hook must not fire at all.
            if (search != null && body != null && player.getController() instanceof forge.ai.CubeComboPlayerController c) {
                int changesBefore = c.getComboSelectionChanges();
                String outcome;
                try {
                    Card foreignDecider = c.chooseSingleCardForZoneChange(ZoneType.Library, List.of(ZoneType.Library),
                            search, new CardCollection(body), null, "probe", false, opponent);
                    outcome = foreignDecider == null ? "null" : foreignDecider.getName();
                } catch (RuntimeException failure) {
                    outcome = "threw:" + failure.getClass().getSimpleName();
                }
                System.out.println("KIKI_SELECT_PROBE control=" + control + " phase=" + phase
                        + " name=opponent-decides outcome=" + outcome
                        + " changesBefore=" + changesBefore + " changesAfter=" + c.getComboSelectionChanges());
                if (c.getComboSelectionChanges() != changesBefore)
                    throw new AssertionError("N4: another player's decision must never be intercepted: " + control);
                // The controller-level receipt for this tutor's ability shape,
                // with ourselves as the decider. Vampiric Tutor is an instant
                // that native AI holds rather than casts in a prepared main
                // phase, so for that card this is the only reachable receipt.
                if (!control.startsWith("ponder:")) {
                    int ownBefore = c.getComboSelectionChanges();
                    Card pick = c.chooseSingleCardForZoneChange(ZoneType.Library, List.of(ZoneType.Library),
                            search, new CardCollection(body), null, "probe", false, player);
                    String ownDecider = pick == null ? "null" : pick.getName();
                    probeChanges += c.getComboSelectionChanges() - ownBefore;
                    System.out.println("KIKI_SELECT_PROBE control=" + control + " phase=" + phase
                            + " name=own-decider outcome=" + ownDecider
                            + " changesDelta=" + (c.getComboSelectionChanges() - ownBefore));
                    // The offered list is a single card here, so the returned
                    // name alone cannot distinguish an override from the
                    // ordinary AI's own pick; the counter delta can.
                    int delta = c.getComboSelectionChanges() - ownBefore;
                    if (Boolean.getBoolean("forge.test.requireKikiSelection")) {
                        if (MUST_MOVE.contains(control) && (delta != 1 || !ownDecider.equals(BODY)))
                            throw new AssertionError("MUST-MOVE: the controller did not own the search selection: "
                                    + control + " got " + ownDecider + " delta " + delta);
                        boolean lethalMain2 = variant(control).equals("lethal-now") && phase != PhaseType.MAIN1;
                        if (MUST_NOT_MOVE.contains(control) && !lethalMain2 && delta != 0)
                            throw new AssertionError("MUST-NOT-MOVE: the controller touched the choice: " + control);
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return probeChanges;
    }

    private static java.util.Map<String, Object> snapshot(Player player) throws ReflectiveOperationException {
        var result = new java.util.LinkedHashMap<String, Object>();
        var field = Game.class.getDeclaredField("cardIdCounter");
        field.setAccessible(true);
        result.put("nextCardId", field.getInt(player.getGame()));
        result.put("timestamp", player.getGame().getTimestamp());
        result.put("mana", player.getManaPool().totalMana());
        for (var memory : forge.ai.AiCardMemory.MemorySet.values())
            result.put(memory.name(), forge.ai.AiCardMemory.getMemorySet(player, memory).stream().map(Card::getId).sorted().toList());
        result.put("library", player.getCardsIn(ZoneType.Library).stream().map(Card::getId).toList());
        result.put("hand", player.getCardsIn(ZoneType.Hand).stream().map(Card::getId).sorted().toList());
        result.put("board", player.getCardsIn(ZoneType.Battlefield).stream()
                .map(c -> c.getId() + ":" + c.isTapped()).toList());
        result.put("actors", player.getCardsIn(ZoneType.Battlefield).stream().flatMap(c -> c.getSpellAbilities().stream())
                .map(sa -> sa.getHostCard().getId() + ":" + (sa.getActivatingPlayer() == null ? "null" : sa.getActivatingPlayer().getId())).toList());
        return result;
    }

    private static void run(boolean improved, int seat, PhaseType phase, String control) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat, control)).setPlayer(
                improved && s == seat ? new forge.ai.LobbyPlayerCubeComboAi("CubeCombo-" + s)
                        : forge.player.GamePlayerUtil.createAiPlayer("Default-" + s, s, 0, null, "Default")));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "native Kiki tutor selection diagnostic").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, control);
        populate(opponent, false, control);
        String variant = variant(control);
        if (variant.equals("lethal-now")) opponent.setLife(1, null);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().setupFirstTurn(player, () -> game.getPhaseHandler().devModeSet(phase, player));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(51900 + seat * 100 + control.length());
        int probeChanges = improved ? probePredicate(player, opponent, control, phase) : 0;
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " phase=" + phase + " control=" + control;
        System.out.println("KIKI_SELECT_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " infoPolicy=CLOSED_REPAIR registered=40 initialMana=0 tutor=" + tutorOf(control).replace(' ', '_')
                + " engineStart=" + zoneOf(player, variant.equals("twin-hand") ? TWIN : KIKI)
                + " bodyStart=" + zoneOf(player, BODY));
        // Our turns are 1, 3 and 5: tutor on turn 1, draw the fetched card on
        // turn 3, cast both halves and attack on turn 3 at the earliest.
        int bound = 5, steps = 0;
        boolean tutorCast = false, awaitingResolution = false;
        String topAtResolution = "not-resolved", engineZoneAtResolution = "not-resolved", previous = "";
        java.util.Set<Integer> stackIds = new java.util.HashSet<>();
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= bound && steps < 900) {
            game.getPhaseHandler().mainLoopStep();
            steps++;
            for (var item : game.getStack()) if (stackIds.add(item.getId())) {
                var sa = item.getSpellAbility();
                if (sa.getActivatingPlayer() == player && sa.isSpell()) {
                    if (sa.getHostCard().getName().equals(tutorOf(control))) { tutorCast = true; awaitingResolution = true; }
                    System.out.println("KIKI_SELECT_CAST " + key + " step=" + steps + " turn=" + game.getPhaseHandler().getTurn()
                            + " phaseNow=" + game.getPhaseHandler().getPhase() + " card=" + sa.getHostCard().getName());
                }
            }
            // The stack emptying after the tutor was put on it is the moment its
            // search has resolved: the selected card is then on top of our own
            // library. Detected through the stack, never through a card object,
            // because a zone change replaces the Card instance.
            if (awaitingResolution && game.getStack().isEmpty() && !game.getStack().hasSimultaneousStackEntries()) {
                awaitingResolution = false;
                topAtResolution = libraryTop(player);
                engineZoneAtResolution = zoneOf(player, variant.equals("twin-hand") ? TWIN : KIKI);
            }
            String state = "turn=" + game.getPhaseHandler().getTurn() + " currentPhase=" + game.getPhaseHandler().getPhase()
                    + " life=" + player.getLife() + " opponentLife=" + opponent.getLife()
                    + " bodyZone=" + zoneOf(player, BODY) + " changes=" + (selectionChanges(player) - probeChanges);
            if (!state.equals(previous)) System.out.println("KIKI_SELECT_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 900) throw new AssertionError("native step budget exhausted " + key);
        // Interventions made in play only: the pre-game controller probes above
        // legitimately move the same counter and are subtracted here.
        int changes = selectionChanges(player) - probeChanges;
        boolean won = player.hasWon();
        String bodyEnd = zoneOf(player, BODY);
        System.out.println("KIKI_SELECT_RESULT " + key + " won=" + won + " gameOver=" + game.isGameOver()
                + " turn=" + game.getPhaseHandler().getTurn() + " steps=" + steps
                + " changes=" + changes + " probeChanges=" + probeChanges + " tutorCast=" + tutorCast + " topAtResolution=" + topAtResolution.replace(' ', '_')
                + " engineAtResolution=" + engineZoneAtResolution + " bodyEnd=" + bodyEnd
                + " life=" + player.getLife() + " opponentLife=" + opponent.getLife());
        if (!improved) return;
        if (!Boolean.getBoolean("forge.test.requireKikiSelection")) return;
        if (MUST_MOVE.contains(control)) {
            // Vampiric Tutor is an instant: native ChangeZoneAi will not cast a
            // search-to-top before MAIN2 and the ordinary AI then holds the
            // instant, so its in-game cast is not reachable from a prepared
            // main phase. Its MUST-MOVE receipt is the own-decider controller
            // probe above; the in-game numbers are recorded, not asserted.
            boolean inGameCastReachable = !control.startsWith("vamp:");
            if (inGameCastReachable && !tutorCast)
                throw new AssertionError("MUST-MOVE: the native AI never cast the tutor: " + key);
            if (inGameCastReachable && changes < 1)
                throw new AssertionError("MUST-MOVE: no selection was owned: " + key);
            if (inGameCastReachable && !control.startsWith("ponder:") && !topAtResolution.equals(BODY))
                throw new AssertionError("MUST-MOVE: the pair-completing card was not selected: " + key + " top=" + topAtResolution);
            if (!List.of("Hand", "Battlefield", "Graveyard", "Exile").contains(bodyEnd))
                throw new AssertionError("MUST-MOVE: the selected body never left the library: " + key + " bodyEnd=" + bodyEnd);
            if (!won) throw new AssertionError("MUST-MOVE: the pair was not completed inside the bound: " + key);
        }
        if (MUST_NOT_MOVE.contains(control)) {
            boolean lethalArm = variant.equals("lethal-now");
            // The lethal gate is MAIN1-scoped by construction: in MAIN2 no attack
            // is left this turn, so the MAIN2 arm of this control is recorded and
            // is deliberately not a must-not-intervene case.
            if ((!lethalArm || phase == PhaseType.MAIN1) && changes != 0)
                throw new AssertionError("MUST-NOT-MOVE: this policy touched the choice: " + key + " changes=" + changes);
            if ((!lethalArm || phase == PhaseType.MAIN1) && topAtResolution.equals(BODY))
                throw new AssertionError("MUST-NOT-MOVE: the ordinary choice was not preserved: " + key);
        }
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "kiki-tutor-selection-diagnostic-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (String name : List.of(KIKI, TWIN, BODY, SECOND_BODY, SPARE_BODY, DECOY, "Imperial Seal", "Vampiric Tutor",
                    "Ponder", "Mountain", "Island", "Swamp", "Forest", "Grizzly Bears"))
                StaticData.instance().attemptToLoadCard(name);
            List<String> cases = args.length > 2 ? switch (args[2]) {
                case "must-move" -> MUST_MOVE;
                case "must-not-move" -> MUST_NOT_MOVE;
                default -> java.util.stream.Stream.concat(MUST_MOVE.stream(), MUST_NOT_MOVE.stream()).toList();
            } : java.util.stream.Stream.concat(MUST_MOVE.stream(), MUST_NOT_MOVE.stream()).toList();
            for (int seat = 0; seat < 2; seat++) for (PhaseType phase : List.of(PhaseType.MAIN1, PhaseType.MAIN2))
                for (String control : cases) run(args[1].equals("improved"), seat, phase, control);
            System.out.println("KIKI_SELECT_SUITE_COMPLETE cases=" + (4 * cases.size()));
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
