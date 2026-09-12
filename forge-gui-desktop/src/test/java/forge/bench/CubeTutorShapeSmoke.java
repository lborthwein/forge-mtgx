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
import forge.game.card.CounterType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
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

/** Registered prepared positions for the v57 tutor SHAPE widening: the tutors
 * census.md section 4.4 found the v56 policy refusing outright - a creature
 * whose enters trigger searches our own library, an activated search on a
 * permanent we control, and Wishclaw Talisman's search, whose SubAbility hands
 * the artifact to an opponent.
 *
 * Diagnostic of native decisions only: the host supplies the board and nothing
 * else - every cast, activation, target, trigger, payment and block is the
 * native AI's. These are exactly registered 40-card synthetic positions, not
 * natural openings, not a win rate and not a strength claim.
 *
 * Deliberately a NEW class rather than more cases in CubeTutorSelectSmoke: the
 * `select` merge group runs that class with `all`, so extending it would change
 * a merge group's receipts by construction. Board discipline, 40-card
 * registration, CLOSED_REPAIR, the RNG audit, both seats x both mains and both
 * arms are that fixture's, verbatim.
 */
public final class CubeTutorShapeSmoke {
    private static final String KIKI = "Kiki-Jiki, Mirror Breaker", BODY = "Pestermite";
    private static final String CONSCRIPTS = "Zealous Conscripts";
    private static final String BREACH = "Underworld Breach", FREEZE = "Brain Freeze";
    private static final String LED = "Lion's Eye Diamond", FUEL = "Ponder";
    private static final String URZA = "Urza, Lord High Artificer", FOUNDRY = "Thopter Foundry";
    private static final String SWORD = "Sword of the Meek";
    private static final String DEPTHS = "Dark Depths", STAGE = "Thespian's Stage";
    private static final String BEAR = "Grizzly Bears", TOP = "Sensei's Divining Top";
    private static final String WISHCLAW = "Wishclaw Talisman", MAP = "Expedition Map";
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);

    /** The shape is admitted AND the forecast fires: the tutor is planned, the
     * policy plays it, and the completing piece is the card fetched. */
    private static final List<String> MUST_MOVE = List.of(
            "recruiter:kiki", "imperial:pestermite", "stoneforge:sword",
            "spellseeker:freeze", "wishclaw:kiki-partner");
    /** Every registered refusal, one per reason the widening can refuse for. */
    private static final List<String> MUST_NOT_MOVE = List.of(
            "trinket:sword", "imperial:conscripts", "wishclaw:plan-piece",
            "vorinclex:changenum2", "map:depths", "recruiter:gate-open");

    private record Placement(String name, ZoneType zone) {}

    /** The card that carries the search - in our hand for a creature shape, on
     * our battlefield for an activated one. */
    private static String tutorOf(String control) {
        return switch (control.split(":")[0]) {
            case "recruiter" -> "Recruiter of the Guard";
            case "imperial" -> "Imperial Recruiter";
            case "stoneforge" -> "Stoneforge Mystic";
            case "spellseeker" -> "Spellseeker";
            case "trinket" -> "Trinket Mage";
            case "vorinclex" -> "Vorinclex";
            case "wishclaw" -> WISHCLAW;
            default -> MAP;
        };
    }

    /** Which of the three widened shapes this control exercises. */
    private static String shapeOf(String control) {
        return control.startsWith("wishclaw:") ? "control-transfer"
                : control.startsWith("map:") ? "activated" : "creature-etb";
    }

    private static boolean activated(String control) { return !shapeOf(control).equals("creature-etb"); }

    private static String variant(String control) { return control.split(":", 2)[1]; }

    /** The plan piece this control is about, tracked by zone throughout. */
    private static String piece(String control) {
        return switch (variant(control)) {
            case "kiki", "gate-open", "changenum2" -> KIKI;
            case "pestermite", "kiki-partner" -> BODY;
            case "sword" -> SWORD;
            case "freeze", "plan-piece" -> FREEZE;
            case "conscripts" -> CONSCRIPTS;
            default -> STAGE;
        };
    }

    /** What {@code chooseTutorPartner} must answer when handed the offered
     * list, or "null". This is the RESOLUTION hook's answer, which is not the
     * same question as whether planTutor will cast the tutor: for
     * `wishclaw:plan-piece` the hook legitimately names the Breach piece - it is
     * the same hook Demonic Tutor uses - and the refusal happens one level up,
     * in planTutor's control-transfer gate. */
    private static String expected(String control) {
        return MUST_MOVE.contains(control) || variant(control).equals("plan-piece") ? piece(control) : "null";
    }

    /** The decline token planTutor must report for a refusal, as the controller
     * prints it. Each registered refusal has its own reason and they are
     * deliberately all different - a case that refused for the wrong reason is
     * a failure, not a pass. */
    private static String expectedReason(String control) {
        // Keyed on the whole control, not the variant: `stoneforge:sword` and
        // `trinket:sword` are the same piece from two different carriers and
        // must not share an expectation.
        return switch (control) {
            // ChangeNum$ 2 is refused at the shape test, so no shape ever
            // matched and the token never leaves its initial value.
            case "vorinclex:changenum2" -> "other check=no-tutor-in-hand";
            // The shape IS admitted; the forecast then finds no piece it can
            // both fetch (printed ChangeType) and complete a route with.
            case "trinket:sword", "imperial:conscripts", "map:depths", "recruiter:gate-open"
                    -> "other check=no-partner-route";
            // Wishclaw's SubAbility gate: the fetch would open a gate but does
            // not win this turn, and the opponent gets the next activation.
            case "wishclaw:plan-piece" -> "subability:not-same-turn";
            default -> "plan";
        };
    }

    /** Does this board give planTutor a MAIN2 route at all? Its outer guard
     * admits our own MAIN2 only when a thopter piece or a Breach/Storm plan
     * piece is missing; the Kiki route is MAIN1-only, because its priority
     * rests on haste copies finishing THIS combat (chooseKikiTutorPartner keeps
     * v45's MAIN1 selection window and needsMoreCopies is MAIN1-only).
     *
     * On a board with neither, the MAIN2 arm is not "recorded": the ONLY
     * admissible outcome there is a decline naming `phase`, and that is
     * asserted. Only the case's own registered reason is a MAIN1 statement. */
    private static boolean main2Route(String control) {
        return List.of("stoneforge:sword", "trinket:sword", "spellseeker:freeze",
                "wishclaw:plan-piece").contains(control);
    }

    private static boolean asserted(String control, PhaseType phase) {
        return phase == PhaseType.MAIN1 || main2Route(control);
    }

    /** The thopter assembly is a multi-activation loop whose finish this suite
     * does not register; the Kiki and Breach routes are the ones whose win is
     * part of the registered claim. */
    private static boolean assertsWin(String control) {
        return List.of("recruiter:kiki", "imperial:pestermite", "spellseeker:freeze",
                "wishclaw:kiki-partner").contains(control);
    }

    private static List<Placement> placements(boolean owner, String control) {
        List<Placement> result = new ArrayList<>();
        String variant = variant(control);
        if (!owner) {
            // A real blocker on every board, so no ordinary attack of ours is
            // lethal and no selection can take credit for a game the ordinary
            // AI was about to win.
            result.add(new Placement(BEAR, ZoneType.Battlefield));
            while (result.size() < 40) result.add(new Placement("Forest", ZoneType.Library));
            return result;
        }
        // Ten untapped lands on every board: enough for the tutor and the
        // fetched piece on the same turn, in every colour these cases need.
        for (int i = 0; i < 4; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
        for (int i = 0; i < 3; i++) result.add(new Placement("Island", ZoneType.Battlefield));
        for (int i = 0; i < 2; i++) result.add(new Placement("Plains", ZoneType.Battlefield));
        result.add(new Placement("Swamp", ZoneType.Battlefield));
        // The search carrier: in hand for a creature shape, on the battlefield
        // for an activated one.
        result.add(new Placement(tutorOf(control), activated(control) ? ZoneType.Battlefield : ZoneType.Hand));
        List<String> library = new ArrayList<>();
        switch (variant) {
            // Recruiter of the Guard searches Creature.toughnessLE2; Kiki-Jiki
            // is 2/2, so the printed restriction admits it.
            case "kiki" -> {
                result.add(new Placement(BODY, ZoneType.Battlefield));
                library.add(BEAR);
                library.add(KIKI);
            }
            // Imperial Recruiter searches Creature.powerLE2; Pestermite is 2/1.
            // The engine is already on the battlefield and unsick, so the loop
            // runs the turn the body lands.
            case "pestermite", "kiki-partner" -> {
                result.add(new Placement(KIKI, ZoneType.Battlefield));
                library.add(BEAR);
                library.add(BODY);
            }
            // Stoneforge Mystic searches Card.Equipment; Sword of the Meek is
            // the thopter family's one missing piece here.
            case "sword" -> {
                result.add(new Placement(URZA, ZoneType.Battlefield));
                result.add(new Placement(FOUNDRY, ZoneType.Battlefield));
                // Trinket Mage searches Artifact.cmcLE1 and the Sword is {2},
                // so the same board is the printed-restriction negative. The
                // Top is a cmcLE1 artifact, so that case declines on a real
                // offered list rather than an empty one.
                library.add(TOP);
                library.add(SWORD);
            }
            // Spellseeker searches Instant.cmcLE2; Brain Freeze is {1}{U} and
            // is the Breach gate's missing half.
            case "freeze", "plan-piece" -> {
                result.add(new Placement(BREACH, ZoneType.Battlefield));
                result.add(new Placement(LED, ZoneType.Battlefield));
                for (int i = 0; i < 12; i++) result.add(new Placement(FUEL, ZoneType.Graveyard));
                library.add(FUEL);
                library.add(FREEZE);
            }
            // Imperial Recruiter cannot fetch a 3-power body: the printed
            // power restriction, not a policy choice, is what refuses here.
            case "conscripts" -> {
                result.add(new Placement(KIKI, ZoneType.Battlefield));
                library.add(BEAR);
                library.add(CONSCRIPTS);
            }
            // ChangeNum$ 2 keeps Vorinclex out of the shape entirely.
            case "changenum2" -> {
                result.add(new Placement(BODY, ZoneType.Battlefield));
                // A Forest, so Vorinclex's own ChangeType$ Forest offers a real
                // list and the refusal is the ChangeNum$ 2 shape test rather
                // than an empty search.
                library.add("Forest");
                library.add(BEAR);
                library.add(KIKI);
            }
            // Expedition Map's shape IS admitted; its ChangeType is Land, and
            // the Depths/Stage pair belongs to the bomb family, which
            // planTutor's planCompletingNames does not consult. Registered as
            // the honest limit of the widening.
            case "depths" -> {
                result.add(new Placement(DEPTHS, ZoneType.Battlefield));
                library.add("Forest");
                library.add(STAGE);
            }
            // Both Kiki halves already held: the gate is not one card short, so
            // there is nothing for any shape to fetch. The redundant library
            // copy keeps this a real decline on a real candidate.
            case "gate-open" -> {
                result.add(new Placement(BODY, ZoneType.Battlefield));
                result.add(new Placement(KIKI, ZoneType.Hand));
                library.add(BEAR);
                library.add(KIKI);
            }
            default -> throw new AssertionError("unknown variant " + variant);
        }
        for (String name : library) result.add(new Placement(name, ZoneType.Library));
        while (result.size() < 40) result.add(new Placement("Island", ZoneType.Library));
        return result;
    }

    private static Deck deck(boolean owner, String control) {
        Deck result = new Deck("Tutor shape diagnostic");
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
            // Wishclaw Talisman's wish counters arrive through an enters
            // replacement that a fixture placement never fires. The cost is
            // {1}, tap, remove a wish counter, so without them the activated
            // shape could not be paid for at all and the case would test
            // nothing. Three is the printed number.
            // Dark Depths is the same case: placed with no ice counters it is
            // immediately sacrificed for a 20/20, which is not the board this
            // control claims to set up. Ten is the printed number.
            String counter = p.name().equals(WISHCLAW) ? "WISH" : p.name().equals(DEPTHS) ? "ICE" : null;
            if (counter != null && p.zone() == ZoneType.Battlefield) {
                com.google.common.collect.Multiset<CounterType> counters = com.google.common.collect.HashMultiset.create();
                counters.add(CounterType.getType(counter), counter.equals("WISH") ? 3 : 10);
                card.setCounters(counters);
            }
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

    private static boolean inZone(Player player, ZoneType zone, String name) { return own(player, zone, name) != null; }

    private static int tutorPlanCasts(Player player) {
        return player.getController() instanceof forge.ai.CubeComboPlayerController c ? c.getComboTutorPlanCasts() : 0;
    }

    private static int selectionChanges(Player player) {
        return player.getController() instanceof forge.ai.CubeComboPlayerController c ? c.getComboSelectionChanges() : 0;
    }

    /** The search ability this control's carrier would put on the stack,
     * rebuilt here the same way the policy rebuilds it: for a creature, the
     * enters trigger's Execute SVar parsed through the native ability factory
     * (a detached parse, never Trigger.ensureAbility, which would cache onto
     * the live trigger); for a permanent, its activated ability. Read-only. */
    private static SpellAbility searchOf(Player player, String control) {
        Card carrier = activated(control) ? own(player, ZoneType.Battlefield, tutorOf(control))
                : own(player, ZoneType.Hand, tutorOf(control));
        if (carrier == null) return null;
        if (activated(control)) {
            for (SpellAbility ability : carrier.getSpellAbilities())
                if (ability.isActivatedAbility() && ability.getApi() == forge.game.ability.ApiType.ChangeZone
                        && "Library".equals(ability.getParam("Origin"))
                        && "Hand".equals(ability.getParam("Destination"))) return ability.copy(player);
            return null;
        }
        for (Trigger trigger : carrier.getTriggers()) {
            if (trigger.getMode() != TriggerType.ChangesZone
                    || !"Battlefield".equals(trigger.getParam("Destination"))) continue;
            String svar = trigger.getParam("Execute");
            if (svar == null || carrier.getSVar(svar) == null
                    || !carrier.getSVar(svar).contains("ChangeZone")) continue;
            SpellAbility search = forge.game.ability.AbilityFactory.getAbility(carrier, svar);
            if (search == null) continue;
            search.setActivatingPlayer(player);
            return search;
        }
        return null;
    }

    /** The list the native search would offer us: our own library cards that
     * satisfy the search's own printed ChangeType. The restricted negatives
     * rest on this being the real filter and not a convenience. */
    private static CardCollection offered(Player player, SpellAbility search) {
        String[] types = search.getParamOrDefault("ChangeType", "Card").split(",");
        CardCollection result = new CardCollection();
        for (Card card : player.getCardsIn(ZoneType.Library))
            if (card.isValid(types, player, search.getHostCard(), search)) result.add(card);
        return result;
    }

    private static String named(Card card) { return card == null ? "null" : card.getName().replace(' ', '_'); }

    /** Observability read of planTutor's own decline token, which the
     * controller prints as CUBE_PLAN_DECLINE family=kiki-tutor. Package-private
     * in forge.ai, so reflection; a pure read with no argument and no state. */
    private static String declineReason() {
        try {
            var method = Class.forName("forge.ai.CubeComboAi").getDeclaredMethod("lastTutorDecline");
            method.setAccessible(true);
            return String.valueOf(method.invoke(null));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Deterministic, non-game receipts. Read-only on the live game: the
     * snapshot is compared before and after and the bench RNG boundary must not
     * move. Only the improved arm runs any of this. */
    private static void probeShape(Player player, Player opponent, String control, PhaseType phase) {
        String key = "control=" + control + " phase=" + phase;
        try {
            var before = snapshot(player);
            var rng = BenchRandomAudit.begin();
            SpellAbility search = searchOf(player, control);
            String selected = "no-probe", restricted = "no-probe", opponentOwned = "no-probe";
            String notInLibrary = "no-probe", foreignActor = "no-probe";
            CardCollection choices = search == null ? new CardCollection() : offered(player, search);
            if (search != null && !choices.isEmpty()) {
                selected = named(forge.ai.CubeComboAi.chooseTutorPartner(player, search, choices));
                CardCollection subset = new CardCollection();
                for (Card card : choices) if (!card.getName().equals(piece(control))) subset.add(card);
                restricted = subset.isEmpty() ? "no-probe"
                        : named(forge.ai.CubeComboAi.chooseTutorPartner(player, search, subset));
                Card foreign = opponent.getCardsIn(ZoneType.Library).isEmpty() ? null
                        : opponent.getCardsIn(ZoneType.Library).getFirst();
                if (foreign != null)
                    opponentOwned = named(forge.ai.CubeComboAi.chooseTutorPartner(player, search, new CardCollection(foreign)));
                Card outside = own(player, ZoneType.Hand, piece(control));
                if (outside == null) outside = own(player, ZoneType.Battlefield, piece(control));
                if (outside == null) outside = own(player, ZoneType.Battlefield, "Mountain");
                if (outside != null)
                    notInLibrary = named(forge.ai.CubeComboAi.chooseTutorPartner(player, search, new CardCollection(outside)));
                SpellAbility theirs = search.copy(opponent);
                foreignActor = named(forge.ai.CubeComboAi.chooseTutorPartner(player, theirs, choices));
            }
            System.out.println("TUTOR_SHAPE_PROPOSAL " + key + " name=offered outcome=" + selected
                    + " offeredCount=" + choices.size() + " expected=" + expected(control).replace(' ', '_')
                    + " restrictedSubset=" + restricted + " opponentOwned=" + opponentOwned
                    + " notInLibrary=" + notInLibrary + " foreignActor=" + foreignActor);
            if (!List.of("null", "no-probe").contains(opponentOwned))
                throw new AssertionError("a card we do not own must never be selected: " + key);
            if (!List.of("null", "no-probe").contains(notInLibrary))
                throw new AssertionError("a card outside our own library must never be selected: " + key);
            if (!List.of("null", "no-probe").contains(foreignActor))
                throw new AssertionError("another player's search must never be steered: " + key);
            if (!before.equals(snapshot(player)))
                throw new AssertionError("shape probe changed native state: " + before + " -> " + snapshot(player));
            BenchRandomAudit.assertUnchanged(rng, "tutor-shape-preview");
            // The forecast itself, and the reason it gives when it declines.
            // Outside the snapshot window: planTutor prices two casts through
            // the native payment helpers, exactly as CubeTutorSelectSmoke keeps
            // its own planTutor probes outside that window.
            var plan = forge.ai.CubeComboAi.planTutor(player);
            String verdict = plan == null ? "null" : "plan";
            String reason = declineReason();
            System.out.println("TUTOR_SHAPE_PROPOSAL " + key + " name=plan-tutor verdict=" + verdict
                    + " reason=" + reason.replace(' ', '_')
                    + " partner=" + (plan == null ? "null" : plan.plannedPartner().replace(' ', '_'))
                    + " action=" + (plan == null ? "null" : plan.tutor().getHostCard().getName().replace(' ', '_'))
                    + " shape=" + shapeOf(control));
            if (Boolean.getBoolean("forge.test.requireTutorShape") && !asserted(control, phase)) {
                // MAIN2 on a board with no thopter or plan piece missing:
                // planTutor's own outer guard rejects the phase before any
                // shape is tested, and that is the whole admissible outcome.
                if (!verdict.equals("null") || !reason.equals("phase"))
                    throw new AssertionError("MAIN2 without a plan route must decline naming phase: "
                            + key + " verdict=" + verdict + " reason=" + reason);
            } else if (Boolean.getBoolean("forge.test.requireTutorShape")) {
                String want = expected(control).replace(' ', '_');
                // "no-probe" is an EMPTY offered list, which is a refusal the
                // printed ChangeType made before the policy was ever asked.
                if (!(want.equals("null") ? List.of("null", "no-probe").contains(selected) : selected.equals(want)))
                    throw new AssertionError("predicate: expected " + expected(control) + " got " + selected + " " + key);
                String allowedReason = expectedReason(control);
                if (allowedReason.equals("plan")) {
                    if (!verdict.equals("plan"))
                        throw new AssertionError("MUST-MOVE: planTutor declined: " + key + " reason=" + reason);
                    if (!plan.plannedPartner().equals(piece(control)))
                        throw new AssertionError("MUST-MOVE: planned the wrong piece: " + plan.plannedPartner() + " " + key);
                    if (!plan.tutor().getHostCard().getName().equals(tutorOf(control)))
                        throw new AssertionError("MUST-MOVE: planned the wrong action: " + key);
                } else {
                    if (!verdict.equals("null"))
                        throw new AssertionError("MUST-NOT-MOVE: planTutor produced a plan: " + key);
                    if (!reason.equals(allowedReason))
                        throw new AssertionError("refused for the wrong reason: expected '" + allowedReason
                                + "' got '" + reason + "' " + key);
                }
                // A withheld piece must never be invented.
                if (!List.of("no-probe", "null").contains(restricted))
                    throw new AssertionError("a withheld piece must not be assumed present: " + restricted + " " + key);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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
        Game game = new Match(rules, players, "native tutor shape diagnostic").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, control);
        populate(opponent, false, control);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().setupFirstTurn(player, () -> game.getPhaseHandler().devModeSet(phase, player));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(57700 + seat * 100 + control.length());
        String piece = piece(control);
        if (improved) probeShape(player, opponent, control, phase);
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " phase=" + phase + " control=" + control;
        System.out.println("TUTOR_SHAPE_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " infoPolicy=CLOSED_REPAIR registered=40 initialMana=0"
                + " tutor=" + tutorOf(control).replace(' ', '_') + " shape=" + shapeOf(control)
                + " piece=" + piece.replace(' ', '_') + " expected=" + expected(control).replace(' ', '_')
                + " expectedReason=" + expectedReason(control).replace(' ', '_')
                + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size()
                + " opponentLibrary=" + opponent.getCardsIn(ZoneType.Library).size());
        int bound = 4, steps = 0, planCasts = 0;
        boolean awaitingResolution = false;
        String pieceAtResolution = "not-resolved", previous = "";
        java.util.Set<Integer> stackIds = new java.util.HashSet<>();
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= bound && steps < 1800) {
            game.getPhaseHandler().mainLoopStep();
            steps++;
            for (var item : game.getStack()) if (stackIds.add(item.getId())) {
                var sa = item.getSpellAbility();
                if (sa.getActivatingPlayer() == player)
                    System.out.println("TUTOR_SHAPE_CAST " + key + " step=" + steps + " turn=" + game.getPhaseHandler().getTurn()
                            + " phaseNow=" + game.getPhaseHandler().getPhase() + " card=" + sa.getHostCard().getName().replace(' ', '_')
                            + " spell=" + sa.isSpell() + " copied=" + sa.isCopied());
            }
            // The policy's own counter is the shape-independent detector: it
            // increments only inside the TutorPlan branch of the controller, so
            // it fires for a creature spell and for an activated ability alike.
            if (tutorPlanCasts(player) > planCasts) { planCasts = tutorPlanCasts(player); awaitingResolution = true; }
            if (awaitingResolution && game.getStack().isEmpty() && !game.getStack().hasSimultaneousStackEntries()) {
                awaitingResolution = false;
                pieceAtResolution = inZone(player, ZoneType.Library, piece) ? "Library"
                        : inZone(player, ZoneType.Hand, piece) ? "Hand"
                        : inZone(player, ZoneType.Battlefield, piece) ? "Battlefield" : "elsewhere";
            }
            String state = "turn=" + game.getPhaseHandler().getTurn() + " currentPhase=" + game.getPhaseHandler().getPhase()
                    + " opponentLife=" + opponent.getLife()
                    + " pieceInLibrary=" + inZone(player, ZoneType.Library, piece)
                    + " planCasts=" + tutorPlanCasts(player);
            if (!state.equals(previous)) System.out.println("TUTOR_SHAPE_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 1800) throw new AssertionError("native step budget exhausted " + key);
        boolean won = player.hasWon();
        System.out.println("TUTOR_SHAPE_RESULT " + key + " won=" + won + " gameOver=" + game.isGameOver()
                + " turn=" + game.getPhaseHandler().getTurn() + " steps=" + steps
                + " tutorPlanCasts=" + tutorPlanCasts(player) + " changes=" + selectionChanges(player)
                + " pieceAtResolution=" + pieceAtResolution
                + " pieceInLibraryEnd=" + inZone(player, ZoneType.Library, piece)
                + " carrierZone=" + carrierZone(player, control)
                + " life=" + player.getLife() + " opponentLife=" + opponent.getLife());
        if (!improved || !Boolean.getBoolean("forge.test.requireTutorShape")) return;
        // A registered refusal, or a MAIN2 arm with no plan route: neither may
        // ever play a planned tutor, and that holds in BOTH phases.
        if (MUST_NOT_MOVE.contains(control) || !asserted(control, phase)) {
            if (tutorPlanCasts(player) != 0)
                throw new AssertionError("MUST-NOT-MOVE: the policy played a tutor: " + key);
            if (!pieceAtResolution.equals("not-resolved"))
                throw new AssertionError("MUST-NOT-MOVE: a planned tutor resolved: " + key);
            return;
        }
        if (tutorPlanCasts(player) < 1)
            throw new AssertionError("MUST-MOVE: the policy never played the widened tutor: " + key);
        if (!pieceAtResolution.equals("Hand") && !pieceAtResolution.equals("Battlefield"))
            throw new AssertionError("MUST-MOVE: the completing piece was not fetched: " + key
                    + " piece=" + pieceAtResolution);
        if (assertsWin(control) && !won)
            throw new AssertionError("MUST-MOVE: the route did not finish after the fetch: " + key);
    }

    /** Where the search carrier ended up - a receipt of Wishclaw's control
     * transfer and of Expedition Map's self-sacrifice, both of which are costs
     * this widening accepts deliberately. */
    private static String carrierZone(Player player, String control) {
        String name = tutorOf(control);
        for (ZoneType zone : ZONES) if (inZone(player, zone, name)) return zone.name();
        return "not-ours";
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "tutor-shape-diagnostic-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (String name : List.of(KIKI, BODY, CONSCRIPTS, BREACH, FREEZE, LED, FUEL, URZA, FOUNDRY, SWORD,
                    DEPTHS, STAGE, BEAR, TOP, WISHCLAW, MAP, "Recruiter of the Guard", "Imperial Recruiter",
                    "Stoneforge Mystic", "Spellseeker", "Trinket Mage", "Vorinclex",
                    "Island", "Swamp", "Mountain", "Plains", "Forest"))
                StaticData.instance().attemptToLoadCard(name);
            List<String> cases = args.length > 2 ? switch (args[2]) {
                case "must-move" -> MUST_MOVE;
                case "must-not-move" -> MUST_NOT_MOVE;
                default -> java.util.stream.Stream.concat(MUST_MOVE.stream(), MUST_NOT_MOVE.stream()).toList();
            } : java.util.stream.Stream.concat(MUST_MOVE.stream(), MUST_NOT_MOVE.stream()).toList();
            for (int seat = 0; seat < 2; seat++) for (PhaseType phase : List.of(PhaseType.MAIN1, PhaseType.MAIN2))
                for (String control : cases) run(args[1].equals("improved"), seat, phase, control);
            System.out.println("TUTOR_SHAPE_SUITE_COMPLETE cases=" + (4 * cases.size()));
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
