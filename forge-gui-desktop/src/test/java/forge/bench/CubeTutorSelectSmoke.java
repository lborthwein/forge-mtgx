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
import forge.game.mana.Mana;
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

/** Registered prepared positions for the library-selection decision of a tutor
 * when a family with a native plan - Breach or Storm - is exactly one named
 * card short of its entry gate. Covers both tutor destinations: a search to
 * hand (Demonic Tutor) and a search that writes the top of our own library
 * (Imperial Seal, Vampiric Tutor, Mystical Tutor).
 *
 * Diagnostic of native decisions only: the host supplies the board and nothing
 * else - every cast, target, trigger, payment and block is the native AI's.
 * These are exactly registered 40-card synthetic positions, not natural
 * openings, not a win rate and not a strength claim.
 *
 * Implements the MUST-MOVE / MUST-NOT-MOVE tables of
 * runs/2026-09-11-breach-natural-diagnosis/diagnosis.md section 3 candidate C1,
 * in the fixture style of CubeKikiTutorSmoke (which shipped diagnosis C1 of the
 * Kiki readout as cube-combo-execution-v45).
 */
public final class CubeTutorSelectSmoke {
    private static final String BREACH = "Underworld Breach", FREEZE = "Brain Freeze";
    private static final String LED = "Lion's Eye Diamond";
    private static final String WILL = "Yawgmoth's Will", TENDRILS = "Tendrils of Agony";
    private static final String FUEL = "Ponder";
    private static final String KIKI = "Kiki-Jiki, Mirror Breaker", BODY = "Pestermite";
    private static final String DOOMSDAY = "Doomsday", ORACLE = "Thassa's Oracle", TWIN = "Splinter Twin";
    private static final String DECOY = "Grave Titan", SPELL_DECOY = "Echo of Eons";
    private static final String MINDCENSOR = "Aven Mindcensor", BEAR = "Grizzly Bears";
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);

    /** diagnosis C1 MUST-MOVE. A Breach piece, a Storm piece, both halves of
     * each gate's asymmetry, and both tutor destinations. */
    private static final List<String> MUST_MOVE = List.of(
            "demonic:freeze-missing", "demonic:breach-missing",
            "demonic:tendrils-missing", "demonic:will-missing",
            "mystical:freeze-missing", "seal:breach-missing", "vamp:tendrils-missing");
    /** diagnosis C1 MUST-NOT-MOVE, the seven shapes a prepared position can
     * carry. The remaining three registered controls are receipts of another
     * kind and are named in registration.md section 4: Doomsday priority (the
     * preserved 44-row Doomsday suites plus the controller's branch order),
     * decider-not-us and the floating-mana / window condition (both controller
     * and predicate probes emitted below). */
    private static final List<String> MUST_NOT_MOVE = List.of(
            "demonic:kiki-priority", "mystical:colour-unreachable", "mystical:type-restricted",
            "demonic:two-short", "demonic:lethal-now", "demonic:search-limit", "demonic:gate-open");

    /** v63 C2, its own case set so every pre-existing arm of this suite stays
     * byte-identical. All four are HAND-destination searches (Demonic Tutor)
     * on boards where the completing piece is NOT castable this turn, which is
     * exactly the state the v57..v61 {@code feasibleHalfAfterSelection}
     * forecast refused and the native AI then answered with the most expensive
     * valid card (Grave Titan here; Vendilion Clique and Echo of Eons in the
     * measured games).
     *
     * <p>The divergence between the two destinations is deliberate and is the
     * whole content of C2: {@code mystical:colour-unreachable} above, a
     * search-to-top on the same Storm shape, KEEPS its v57 answer of "null",
     * because a card written to the top of our library is drawn on a later turn
     * through the ordinary draw and the selection is the whole decision, while
     * a card fetched to our own HAND is not spent by the selection at all.</p>
     *
     * <ul>
     * <li>{@code demonic:reach-tendrils} MUST-MOVE - Storm one short of
     *     Tendrils with no black source anywhere in the 40.</li>
     * <li>{@code demonic:reach-doomsday} MUST-MOVE - the Doomsday family one
     *     short, Thassa's Oracle in hand, no black source for {@code BBB}.</li>
     * <li>{@code demonic:reach-twin} MUST-MOVE, MAIN1 only - an untap body on
     *     our battlefield, no engine anywhere, and no red source for Splinter
     *     Twin's {@code 2UR}.</li>
     * <li>{@code demonic:reach-two-short} MUST-NOT-MOVE - both Storm halves in
     *     our library, so no family is exactly one piece short.</li>
     * </ul> */
    private static final List<String> HAND_REACH_MOVE =
            List.of("demonic:reach-tendrils", "demonic:reach-doomsday", "demonic:reach-twin");
    private static final List<String> HAND_REACH_HOLD = List.of("demonic:reach-two-short");
    private static final List<String> HAND_REACH =
            java.util.stream.Stream.concat(HAND_REACH_MOVE.stream(), HAND_REACH_HOLD.stream()).toList();

    private static boolean handReach(String control) { return HAND_REACH.contains(control); }
    private static boolean mustMove(String control) {
        return MUST_MOVE.contains(control) || HAND_REACH_MOVE.contains(control);
    }
    private static boolean mustNotMove(String control) {
        return MUST_NOT_MOVE.contains(control) || HAND_REACH_HOLD.contains(control);
    }

    private record Placement(String name, ZoneType zone) {}

    private static String tutorOf(String control) {
        return switch (control.split(":")[0]) {
            case "seal" -> "Imperial Seal";
            case "vamp" -> "Vampiric Tutor";
            case "mystical" -> "Mystical Tutor";
            default -> "Demonic Tutor";
        };
    }

    /** A search that writes the top of our own library rather than our hand.
     * Native ChangeZoneAi refuses to cast one of these before MAIN2, so their
     * MAIN1 arms are predicate receipts only - the same limit v45 recorded. */
    private static boolean searchToTop(String control) { return !control.startsWith("demonic:"); }

    private static String variant(String control) { return control.split(":", 2)[1]; }

    /** The plan piece this control is about, tracked by zone throughout. */
    private static String piece(String control) {
        return switch (variant(control)) {
            case "freeze-missing" -> FREEZE;
            case "breach-missing", "type-restricted" -> BREACH;
            case "will-missing" -> WILL;
            case "reach-doomsday" -> DOOMSDAY;
            case "reach-twin" -> TWIN;
            default -> TENDRILS;
        };
    }

    /** What the policy must select from the offered list, or "null". The Kiki
     * priority control is the one MUST-NOT-MOVE case with a non-null answer:
     * a Kiki pair and a Storm gate are both one short, and the faster route -
     * haste copies that can finish this very combat - must still win. */
    private static String expected(String control) {
        if (mustMove(control)) return piece(control);
        return variant(control).equals("kiki-priority") ? BODY : "null";
    }

    /** Two controls are MAIN1 facts by construction, and their MAIN2 arms are
     * recorded rather than asserted.
     *
     * `lethal-now`: after combat there is no attack left this turn, so
     * postponing nothing is not a failure - v45 scoped its own lethal control
     * the same way.
     *
     * `kiki-priority`: the Kiki route outranks a slower one *because its haste
     * copies can finish this very combat*, which is a MAIN1 fact -
     * needsMoreCopies is MAIN1-only, and v45's owner-decided destination-aware
     * gate keeps a hand-destination Kiki selection at MAIN1 for exactly that
     * reason. In MAIN2 no route is faster, the Kiki branch declines on its own
     * v45 gate, and a Storm piece is then a legitimate answer. This scoping was
     * decided after the MAIN2 row was first seen; see checkpoint.md. */
    private static boolean scopedToMain1(String control) {
        // `reach-twin` joins them for the same reason: kikiCompletingNames is
        // MAIN1-only, exactly as chooseKikiTutorPartner and needsMoreCopies are.
        return List.of("lethal-now", "kiki-priority", "reach-twin").contains(variant(control));
    }

    private static boolean asserted(String control, PhaseType phase) {
        return phase == PhaseType.MAIN1 || !scopedToMain1(control);
    }

    private static List<Placement> placements(boolean owner, String control) {
        List<Placement> result = new ArrayList<>();
        String variant = variant(control);
        if (!owner) {
            // The lethal control needs an opponent with nothing at all; every
            // other case keeps a real blocker so no ordinary attack is lethal.
            if (!variant.equals("lethal-now")) result.add(new Placement(BEAR, ZoneType.Battlefield));
            if (variant.equals("search-limit")) result.add(new Placement(MINDCENSOR, ZoneType.Battlefield));
            while (result.size() < 40) result.add(new Placement("Forest", ZoneType.Library));
            return result;
        }
        result.add(new Placement(tutorOf(control), ZoneType.Hand));
        // Eight untapped lands: enough for the tutor and the fetched piece on
        // the same turn. The colour census is the whole point of the
        // colour-unreachable case, so that variant registers no black source
        // anywhere in the 40 - its {2}{B}{B} finisher is provably uncastable -
        // and it is a Storm shape on purpose. The Breach family cannot carry
        // this control: with Underworld Breach on our battlefield a Lotus-type
        // in our graveyard escapes for {0} and makes any colour, which the
        // first run of this suite demonstrated in play.
        // v63 C2: every `reach-` variant is a board on which the completing
        // piece is provably uncastable this turn - no black source for
        // Tendrils' 2BB or Doomsday's BBB, no red source for Splinter Twin's
        // 2UR - which is the state v57..v61's castability forecast refused.
        // Exactly ONE black source on a `reach-` board: enough for Demonic
        // Tutor's own {1}{B}, never enough for Tendrils' {2}{B}{B} or
        // Doomsday's {B}{B}{B}. Zero would make the tutor itself uncastable and
        // there would be no in-game decision to read at all.
        int swamps = variant.equals("colour-unreachable") ? 0 : variant.startsWith("reach-") ? 1 : 4;
        boolean red = !variant.equals("reach-twin");
        String filler = "Island";
        for (int i = 0; i < 4; i++) result.add(new Placement(i < swamps ? "Swamp" : "Island", ZoneType.Battlefield));
        for (int i = 0; i < 3; i++) result.add(new Placement(filler, ZoneType.Battlefield));
        result.add(new Placement(red ? "Mountain" : "Island", ZoneType.Battlefield));
        List<String> library = new ArrayList<>();
        // The decoy goes in first, so it - not the piece - starts on top of the
        // library: it is the card Forge's own imperial_seal.txt says the
        // ordinary AI fetches, "the most expensive valid card in the library".
        // Mystical Tutor only ever offers instants and sorceries, so its decoy
        // has to be one, or its decline would be an empty offered list rather
        // than a real choice not taken.
        library.add(control.startsWith("mystical:") ? SPELL_DECOY : DECOY);
        switch (variant) {
            case "freeze-missing" -> {
                result.add(new Placement(BREACH, ZoneType.Battlefield));
                result.add(new Placement(LED, ZoneType.Battlefield));
                for (int i = 0; i < 12; i++) result.add(new Placement(FUEL, ZoneType.Graveyard));
                library.add(FREEZE);
            }
            case "colour-unreachable" -> { result.add(new Placement(WILL, ZoneType.Hand)); library.add(TENDRILS); }
            case "breach-missing", "type-restricted" -> {
                result.add(new Placement(FREEZE, ZoneType.Hand));
                result.add(new Placement(LED, ZoneType.Battlefield));
                for (int i = 0; i < 12; i++) result.add(new Placement(FUEL, ZoneType.Graveyard));
                library.add(BREACH);
            }
            // Both halves of the Storm gate in our library, so one selection
            // cannot open it. Deliberately a Storm shape with no Underworld
            // Breach on our battlefield: a Breach in play gives our whole
            // graveyard escape, and the first runs of this suite showed the
            // ordinary AI replaying a tutor out of it, which is a busy
            // position rather than a clean must-not-intervene reading.
            case "two-short" -> { library.add(WILL); library.add(TENDRILS); }
            case "tendrils-missing" -> { result.add(new Placement(WILL, ZoneType.Hand)); library.add(TENDRILS); }
            case "will-missing" -> { result.add(new Placement(TENDRILS, ZoneType.Hand)); library.add(WILL); }
            case "kiki-priority" -> {
                result.add(new Placement(KIKI, ZoneType.Battlefield));
                result.add(new Placement(WILL, ZoneType.Hand));
                library.add(BODY);
                library.add(TENDRILS);
            }
            case "lethal-now" -> {
                result.add(new Placement(BEAR, ZoneType.Battlefield));
                result.add(new Placement(WILL, ZoneType.Hand));
                library.add(TENDRILS);
            }
            case "search-limit" -> {
                result.add(new Placement(WILL, ZoneType.Hand));
                // Four cards ahead of it, so an opponent's search restriction
                // keeps the completing piece out of the offered list entirely.
                for (int i = 0; i < 3; i++) library.add(filler);
                library.add(TENDRILS);
            }
            case "gate-open" -> {
                result.add(new Placement(WILL, ZoneType.Hand));
                result.add(new Placement(TENDRILS, ZoneType.Hand));
                library.add(TENDRILS);
            }
            case "reach-tendrils" -> { result.add(new Placement(WILL, ZoneType.Hand)); library.add(TENDRILS); }
            // The Oracle sits in our own GRAVEYARD, a zone
            // CubeDoomsdayPlan.availableInOwnDeck already accepts, so the
            // ordinary AI cannot cast it as a body and close the gate between
            // the tutor's cast and its resolution.
            case "reach-doomsday" -> { result.add(new Placement(ORACLE, ZoneType.Graveyard)); library.add(DOOMSDAY); }
            case "reach-twin" -> { result.add(new Placement(BODY, ZoneType.Battlefield)); library.add(TWIN); }
            case "reach-two-short" -> { library.add(WILL); library.add(TENDRILS); }
            default -> throw new AssertionError("unknown variant " + variant);
        }
        for (String name : library) result.add(new Placement(name, ZoneType.Library));
        while (result.size() < 40) result.add(new Placement(filler, ZoneType.Library));
        return result;
    }

    private static Deck deck(boolean owner, String control) {
        Deck result = new Deck("Plan-aware tutor selection diagnostic");
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

    private static boolean inZone(Player player, ZoneType zone, String name) { return own(player, zone, name) != null; }

    private static String libraryTop(Player player) {
        var top = player.getCardsIn(ZoneType.Library, 1);
        return top.isEmpty() ? "empty" : top.getFirst().getName();
    }

    private static int selectionChanges(Player player) {
        return player.getController() instanceof forge.ai.CubeComboPlayerController c ? c.getComboSelectionChanges() : 0;
    }

    private static int tutorPlanCasts(Player player) {
        return player.getController() instanceof forge.ai.CubeComboPlayerController c ? c.getComboTutorPlanCasts() : 0;
    }

    /** The list the native search would offer us: our own library cards that
     * satisfy the tutor's own ChangeType. The type-restricted control rests on
     * this being the real filter and not a convenience. An opponent's search
     * restriction is modelled by truncating the same list to the top four,
     * which is what Aven Mindcensor does to our search in play. */
    private static CardCollection offered(Player player, SpellAbility search, String control) {
        String[] types = search.getParamOrDefault("ChangeType", "Card").split(",");
        CardCollection result = new CardCollection();
        int scanned = 0;
        boolean limited = variant(control).equals("search-limit");
        for (Card card : player.getCardsIn(ZoneType.Library)) {
            if (limited && scanned++ >= 4) break;
            if (card.isValid(types, player, search.getHostCard(), search)) result.add(card);
        }
        return result;
    }

    private static String named(Card card) { return card == null ? "null" : card.getName().replace(' ', '_'); }

    /** v63 C2's entry point, called reflectively so this same fixture source
     * compiles and runs against the frozen v61 classes, where the method does
     * not exist, and records {@code absent} instead of failing. That control
     * run is the differential evidence for the new case set. */
    private static String handTutorPiece(Player player, SpellAbility search, CardCollection choices) {
        try {
            var method = Class.forName("forge.ai.CubeComboAi").getMethod("chooseHandTutorPiece",
                    Player.class, SpellAbility.class, CardCollection.class);
            return named((Card) method.invoke(null, player, search, choices));
        } catch (ReflectiveOperationException | LinkageError absent) {
            return "absent";
        }
    }

    /** Deterministic, non-game receipts for the predicate table. The first
     * block is read-only on the live game: the snapshot is compared before and
     * after and the bench RNG boundary must not move. The controller-level and
     * mana-pool probes afterwards go through super() or touch the pool, so they
     * sit outside that window and run before the game loop; the intervention
     * they legitimately cause is returned so the fixture can subtract it. Only
     * the improved arm runs any of this. */
    private static int probeSelection(Player player, Player opponent, String control, PhaseType phase) {
        int probeChanges = 0;
        String key = "control=" + control + " phase=" + phase;
        try {
            var before = snapshot(player);
            var rng = BenchRandomAudit.begin();
            Card tutor = own(player, ZoneType.Hand, tutorOf(control));
            SpellAbility search = tutor == null || tutor.getSpellAbilities().isEmpty() ? null
                    : tutor.getSpellAbilities().get(0).copy(player);
            String selected = "no-probe", restricted = "no-probe", opponentOwned = "no-probe";
            String notInLibrary = "no-probe", foreignActor = "no-probe";
            CardCollection choices = search == null ? new CardCollection() : offered(player, search, control);
            if (search != null && !choices.isEmpty()) {
                selected = named(forge.ai.CubeComboAi.chooseTutorPartner(player, search, choices));
                // An opponent's search restriction: the very same position with
                // the completing piece absent from the offered list. The policy
                // may never assume a card it cannot see offered.
                CardCollection subset = new CardCollection();
                for (Card card : choices) if (!card.getName().equals(piece(control))) subset.add(card);
                restricted = subset.isEmpty() ? "no-probe"
                        : named(forge.ai.CubeComboAi.chooseTutorPartner(player, search, subset));
                // A card we do not own, offered on the same API.
                Card foreign = opponent.getCardsIn(ZoneType.Library).isEmpty() ? null
                        : opponent.getCardsIn(ZoneType.Library).getFirst();
                if (foreign != null)
                    opponentOwned = named(forge.ai.CubeComboAi.chooseTutorPartner(player, search, new CardCollection(foreign)));
                // A genuine piece of ours offered from a zone that is not our
                // own library: never selectable.
                Card outside = own(player, ZoneType.Hand, piece(control));
                if (outside == null) outside = own(player, ZoneType.Battlefield, BREACH);
                if (outside == null) outside = own(player, ZoneType.Hand, tutorOf(control));
                if (outside != null)
                    notInLibrary = named(forge.ai.CubeComboAi.chooseTutorPartner(player, search, new CardCollection(outside)));
                // A search that is not ours: the actor gate, not the decider one.
                SpellAbility theirs = tutor.getSpellAbilities().get(0).copy(opponent);
                foreignActor = named(forge.ai.CubeComboAi.chooseTutorPartner(player, theirs, choices));
            }
            // v63 C2. Printed only for the new case set, so every pre-existing
            // arm of this suite keeps its exact line shape. `selected` above is
            // chooseTutorPartner on its own, which still declines on these
            // boards: the layering is the receipt.
            if (handReach(control) && search != null && !choices.isEmpty()) {
                String reached = handTutorPiece(player, search, choices);
                CardCollection subset = new CardCollection();
                for (Card card : choices) if (!card.getName().equals(piece(control))) subset.add(card);
                String withheld = subset.isEmpty() ? "no-probe" : handTutorPiece(player, search, subset);
                SpellAbility theirSearch = tutor.getSpellAbilities().get(0).copy(opponent);
                String theirActor = handTutorPiece(player, theirSearch, choices);
                System.out.println("TUTOR_SELECT_PROPOSAL " + key + " name=hand-reach outcome=" + reached
                        + " partnerAlone=" + selected + " expected=" + expected(control).replace(' ', '_')
                        + " withheldSubset=" + withheld + " foreignActor=" + theirActor);
                if (!List.of("null", "absent").contains(theirActor))
                    throw new AssertionError("another player's search must never be steered: " + key);
                if (!List.of("null", "no-probe", "absent").contains(withheld))
                    throw new AssertionError("a withheld piece must not be assumed present: " + key);
                if (Boolean.getBoolean("forge.test.requirePlanTutorSelection") && asserted(control, phase)
                        && !reached.equals(expected(control).replace(' ', '_')))
                    throw new AssertionError("hand-reach: expected " + expected(control) + " got " + reached + " " + key);
            }
            if (!before.equals(snapshot(player)))
                throw new AssertionError("selection probe changed native state: " + before + " -> " + snapshot(player));
            BenchRandomAudit.assertUnchanged(rng, "plan-tutor-selection-preview");
            System.out.println("TUTOR_SELECT_PROPOSAL " + key + " name=offered outcome=" + selected
                    + " offeredCount=" + choices.size() + " expected=" + expected(control).replace(' ', '_')
                    + " restrictedSubset=" + restricted + " opponentOwned=" + opponentOwned
                    + " notInLibrary=" + notInLibrary + " foreignActor=" + foreignActor);
            if (!List.of("null", "no-probe").contains(opponentOwned))
                throw new AssertionError("a card we do not own must never be selected: " + key);
            if (!List.of("null", "no-probe").contains(notInLibrary))
                throw new AssertionError("a card outside our own library must never be selected: " + key);
            if (!List.of("null", "no-probe").contains(foreignActor))
                throw new AssertionError("another player's search must never be steered: " + key);
            if (Boolean.getBoolean("forge.test.requirePlanTutorSelection") && asserted(control, phase)) {
                // The new hand-reach controls are about the layer BELOW
                // chooseTutorPartner, which still declines on them by design;
                // their own assertion is in the hand-reach block above.
                String want = handReach(control) ? "null" : expected(control).replace(' ', '_');
                if (!selected.equals(want))
                    throw new AssertionError("predicate: expected " + want + " got " + selected + " " + key);
                // The offered-subset guarantee: with the piece withheld, the
                // Kiki route may still answer, but no plan piece may be invented.
                String allowed = variant(control).equals("kiki-priority") && !handReach(control)
                        ? BODY.replace(' ', '_') : "null";
                if (!List.of("no-probe", allowed).contains(restricted))
                    throw new AssertionError("a withheld piece must not be assumed present: " + restricted + " " + key);
            }
            probeChanges += probeController(player, opponent, control, phase, search, choices);
            probeFloatingMana(player, control, phase);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return probeChanges;
    }

    /** Controller-level receipts, outside the snapshot window because super()
     * runs the ordinary AI. The own-decider probe is the only reachable
     * MUST-MOVE receipt for Vampiric Tutor and for every MAIN1 arm of a
     * search-to-top, which native ChangeZoneAi will not cast before MAIN2. */
    private static int probeController(Player player, Player opponent, String control, PhaseType phase,
            SpellAbility search, CardCollection choices) {
        if (search == null || choices.isEmpty()
                || !(player.getController() instanceof forge.ai.CubeComboPlayerController c)) return 0;
        String key = "control=" + control + " phase=" + phase;
        ZoneType destination = searchToTop(control) ? ZoneType.Library : ZoneType.Hand;
        int changesBefore = c.getComboSelectionChanges();
        String outcome;
        try {
            outcome = named(c.chooseSingleCardForZoneChange(destination, List.of(ZoneType.Library),
                    search, new CardCollection(choices), null, "probe", false, opponent));
        } catch (RuntimeException failure) {
            outcome = "threw:" + failure.getClass().getSimpleName();
        }
        System.out.println("TUTOR_SELECT_PROPOSAL " + key + " name=opponent-decides outcome=" + outcome
                + " changesBefore=" + changesBefore + " changesAfter=" + c.getComboSelectionChanges());
        if (c.getComboSelectionChanges() != changesBefore)
            throw new AssertionError("another player's decision must never be intercepted: " + key);
        int ownBefore = c.getComboSelectionChanges();
        String ownDecider = named(c.chooseSingleCardForZoneChange(destination, List.of(ZoneType.Library),
                search, new CardCollection(choices), null, "probe", false, player));
        int delta = c.getComboSelectionChanges() - ownBefore;
        System.out.println("TUTOR_SELECT_PROPOSAL " + key + " name=own-decider destination=" + destination
                + " outcome=" + ownDecider + " changesDelta=" + delta);
        if (Boolean.getBoolean("forge.test.requirePlanTutorSelection") && asserted(control, phase)) {
            if (!expected(control).equals("null")) {
                if (!ownDecider.equals(expected(control).replace(' ', '_')))
                    throw new AssertionError("controller: expected " + expected(control) + " got " + ownDecider + " " + key);
            } else if (ownDecider.equals(piece(control).replace(' ', '_')))
                // A decline hands the decision back to super(), so the name that
                // comes out is the ordinary AI's own pick - the most expensive
                // valid card. What must never come out is the plan piece.
                throw new AssertionError("controller: a declined case still produced the plan piece: " + key);
            // Only a search that writes our own library top is a decision this
            // controller owns; a search to hand is answered inside native
            // ChangeZoneAi, which consults the same predicate and does not move
            // this counter. That asymmetry is registered, not incidental.
            int owned = searchToTop(control) && !expected(control).equals("null") ? 1 : 0;
            if (delta != owned)
                throw new AssertionError("controller ownership delta " + delta + " expected " + owned + " " + key);
        }
        return delta;
    }

    /** planTutor's mana-only discipline: floating mana declines the forecast
     * outright, so a tutor is never planned on resources already committed.
     * This probe adds one black mana to our own pool, reads the decision and
     * empties the pool again; the assertion afterwards is that the pool really
     * did return to empty before the game loop starts. */
    private static void probeFloatingMana(Player player, String control, PhaseType phase) {
        Card land = null;
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (card.isLand() && !card.getManaAbilities().isEmpty()) { land = card; break; }
        if (land == null) return;
        SpellAbility mana = land.getManaAbilities().get(0);
        String empty = forge.ai.CubeComboAi.planTutor(player) == null ? "null" : "plan";
        player.getManaPool().addManaNoEvent(new Mana(forge.card.MagicColor.BLACK, land, mana.getManaPart(), player));
        String floating = forge.ai.CubeComboAi.planTutor(player) == null ? "null" : "plan";
        player.getManaPool().clearPool(false);
        System.out.println("TUTOR_SELECT_PROPOSAL control=" + control + " phase=" + phase
                + " name=floating-mana emptyPool=" + empty + " floatingPool=" + floating
                + " poolRestored=" + player.getManaPool().totalMana());
        if (!floating.equals("null")) throw new AssertionError("planTutor must decline on floating mana: " + control);
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("probe left mana floating: " + control);
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
        Game game = new Match(rules, players, "native plan-aware tutor selection diagnostic").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, control);
        populate(opponent, false, control);
        String variant = variant(control), piece = piece(control);
        if (variant.equals("lethal-now")) opponent.setLife(1, null);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().setupFirstTurn(player, () -> game.getPhaseHandler().devModeSet(phase, player));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(52700 + seat * 100 + control.length());
        int probeChanges = improved ? probeSelection(player, opponent, control, phase) : 0;
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " phase=" + phase + " control=" + control;
        System.out.println("TUTOR_SELECT_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " infoPolicy=CLOSED_REPAIR registered=40 initialMana=0"
                + " tutor=" + tutorOf(control).replace(' ', '_') + " destination=" + (searchToTop(control) ? "Library" : "Hand")
                + " piece=" + piece.replace(' ', '_') + " expected=" + expected(control).replace(' ', '_')
                + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size()
                + " opponentLibrary=" + opponent.getCardsIn(ZoneType.Library).size());
        int bound = 3, steps = 0;
        boolean tutorCast = false, awaitingResolution = false;
        String topAtResolution = "not-resolved", pieceAtResolution = "not-resolved", previous = "";
        java.util.Set<Integer> stackIds = new java.util.HashSet<>();
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= bound && steps < 1400) {
            game.getPhaseHandler().mainLoopStep();
            steps++;
            for (var item : game.getStack()) if (stackIds.add(item.getId())) {
                var sa = item.getSpellAbility();
                if (sa.getActivatingPlayer() == player && sa.isSpell()) {
                    if (sa.getHostCard().getName().equals(tutorOf(control))) { tutorCast = true; awaitingResolution = true; }
                    System.out.println("TUTOR_SELECT_CAST " + key + " step=" + steps + " turn=" + game.getPhaseHandler().getTurn()
                            + " phaseNow=" + game.getPhaseHandler().getPhase() + " card=" + sa.getHostCard().getName().replace(' ', '_')
                            + " copied=" + sa.isCopied());
                }
            }
            // A tutor cast and resolved inside a single native step never
            // appears on the stack between steps, which is exactly what an
            // instant does. The resolved card is in our own graveyard, a public
            // zone, so use that as the second detector.
            if (!tutorCast && inZone(player, ZoneType.Graveyard, tutorOf(control))) {
                tutorCast = true; awaitingResolution = true;
            }
            // The stack emptying after the tutor was put on it is the moment its
            // search resolved. Detected through the stack, never through a card
            // object, because a zone change replaces the Card instance.
            if (awaitingResolution && game.getStack().isEmpty() && !game.getStack().hasSimultaneousStackEntries()) {
                awaitingResolution = false;
                topAtResolution = libraryTop(player).replace(' ', '_');
                // Library first: a control that deliberately holds a redundant
                // copy in hand must still read as "the library copy stayed".
                pieceAtResolution = inZone(player, ZoneType.Library, piece) ? "Library"
                        : inZone(player, ZoneType.Hand, piece) ? "Hand" : "elsewhere";
            }
            String state = "turn=" + game.getPhaseHandler().getTurn() + " currentPhase=" + game.getPhaseHandler().getPhase()
                    + " opponentLibrary=" + opponent.getCardsIn(ZoneType.Library).size()
                    + " opponentLife=" + opponent.getLife()
                    + " pieceInLibrary=" + inZone(player, ZoneType.Library, piece)
                    + " changes=" + (selectionChanges(player) - probeChanges);
            if (!state.equals(previous)) System.out.println("TUTOR_SELECT_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 1400) throw new AssertionError("native step budget exhausted " + key);
        int changes = selectionChanges(player) - probeChanges;
        boolean won = player.hasWon();
        System.out.println("TUTOR_SELECT_RESULT " + key + " won=" + won + " gameOver=" + game.isGameOver()
                + " turn=" + game.getPhaseHandler().getTurn() + " steps=" + steps
                + " changes=" + changes + " probeChanges=" + probeChanges + " tutorPlanCasts=" + tutorPlanCasts(player)
                + " tutorCast=" + tutorCast + " topAtResolution=" + topAtResolution
                + " pieceAtResolution=" + pieceAtResolution
                + " pieceInLibraryEnd=" + inZone(player, ZoneType.Library, piece)
                + " opponentLibrary=" + opponent.getCardsIn(ZoneType.Library).size()
                + " life=" + player.getLife() + " opponentLife=" + opponent.getLife());
        if (!improved || !Boolean.getBoolean("forge.test.requirePlanTutorSelection")) return;
        // Native ChangeZoneAi refuses to cast a search-to-top before MAIN2, and
        // the ordinary AI then holds an instant rather than casting it in MAIN2,
        // so those arms carry their receipt through the probes above. Recorded
        // as dropped in-game control components, exactly as v45 recorded them.
        boolean inGameReachable = !control.startsWith("vamp:")
                && !(searchToTop(control) && phase == PhaseType.MAIN1);
        // `asserted` scopes the two MAIN1-only shapes; no pre-v63 MUST-MOVE
        // control is MAIN1-only, so this clause changes none of them.
        if (mustMove(control) && inGameReachable && asserted(control, phase)) {
            if (!tutorCast) throw new AssertionError("MUST-MOVE: the native AI never cast the tutor: " + key);
            if (searchToTop(control)) {
                if (!topAtResolution.equals(piece.replace(' ', '_')))
                    throw new AssertionError("MUST-MOVE: the completing piece was not put on top: " + key + " top=" + topAtResolution);
            } else if (!pieceAtResolution.equals("Hand"))
                throw new AssertionError("MUST-MOVE: the completing piece was not fetched: " + key + " piece=" + pieceAtResolution);
        }
        // The one whole-route case: with the Breach and its engine already
        // visible, the fetched Brain Freeze is the plan's remaining half and
        // the native plan then executes the mill. One registered position, not
        // a win rate.
        if (control.equals("demonic:freeze-missing") && !won)
            throw new AssertionError("MUST-MOVE: the Breach plan did not finish after the fetch: " + key);
        if (mustNotMove(control)) {
            // Two controls are MAIN1 facts by construction; see scopedToMain1.
            boolean scoped = asserted(control, phase);
            // The receipt is the state at the moment the search resolved, not
            // at the end of the game: a card left in our library is drawn in
            // the ordinary way a few turns later, which is not an intervention.
            if (scoped && searchToTop(control) && changes != 0)
                throw new AssertionError("MUST-NOT-MOVE: this policy owned the selection: " + key + " changes=" + changes);
            if (scoped && !pieceAtResolution.equals("not-resolved") && !pieceAtResolution.equals("Library"))
                throw new AssertionError("MUST-NOT-MOVE: the plan piece was fetched: " + key + " piece=" + pieceAtResolution);
            if (scoped && searchToTop(control) && topAtResolution.equals(piece.replace(' ', '_')))
                throw new AssertionError("MUST-NOT-MOVE: the plan piece was put on top: " + key);
        }
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "plan-aware-tutor-selection-diagnostic-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (String name : List.of(BREACH, FREEZE, LED, WILL, TENDRILS, FUEL, KIKI, BODY, DECOY, SPELL_DECOY,
                    MINDCENSOR, BEAR, DOOMSDAY, ORACLE, TWIN, "Demonic Tutor", "Imperial Seal", "Vampiric Tutor",
                    "Mystical Tutor", "Island", "Swamp", "Mountain", "Forest"))
                StaticData.instance().attemptToLoadCard(name);
            List<String> cases = args.length > 2 ? switch (args[2]) {
                case "must-move" -> MUST_MOVE;
                case "must-not-move" -> MUST_NOT_MOVE;
                case "hand-reach" -> HAND_REACH;
                default -> java.util.stream.Stream.concat(MUST_MOVE.stream(), MUST_NOT_MOVE.stream()).toList();
            } : java.util.stream.Stream.concat(MUST_MOVE.stream(), MUST_NOT_MOVE.stream()).toList();
            for (int seat = 0; seat < 2; seat++) for (PhaseType phase : List.of(PhaseType.MAIN1, PhaseType.MAIN2))
                for (String control : cases) run(args[1].equals("improved"), seat, phase, control);
            System.out.println("TUTOR_SELECT_SUITE_COMPLETE cases=" + (4 * cases.size()));
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
