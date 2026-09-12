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

/** Registered prepared positions for the v60 COMPLETING-PIECE widening: the
 * tutor plan may now fetch the one missing piece of every plan family, not only
 * Breach and Storm, and the ordinary-AI-initiated Wishclaw search is gated on
 * the same same-turn-win test v57 applies inside {@code planTutor}.
 *
 * One MUST-MOVE per family with an admitted tutor in hand and the route
 * executable after the fetch; one MUST-NOT-MOVE per new family where TWO roles
 * are missing, so the family names nothing; and the Wishclaw
 * ordinary-activation parity row. Registered in
 * {@code runs/2026-09-12-completing-pieces-v60/registration.md} section 4.
 *
 * Diagnostic of native decisions only: the host supplies the board and nothing
 * else - every cast, activation, target, trigger, payment and block is the
 * native AI's. These are exactly registered 40-card synthetic positions, not
 * natural openings, not a win rate and not a strength claim.
 *
 * Board discipline, 40-card registration, CLOSED_REPAIR, the RNG audit, the
 * pre/post snapshot, both seats x both mains and both arms are
 * {@link CubeTutorShapeSmoke}'s, verbatim. A NEW class, for that fixture's own
 * stated reason: the `shape` group runs CubeTutorShapeSmoke with `all`, so
 * extending it would change a group's receipts by construction. */
public final class CubeCompletingPiecesSmoke {
    private static final String KIKI = "Kiki-Jiki, Mirror Breaker", BODY = "Pestermite";
    private static final String TWIN = "Splinter Twin";
    private static final String URZA = "Urza, Lord High Artificer", FOUNDRY = "Thopter Foundry";
    private static final String SWORD = "Sword of the Meek";
    private static final String DEPTHS = "Dark Depths", STAGE = "Thespian's Stage";
    private static final String BASALT = "Basalt Monolith", BALLISTA = "Walking Ballista";
    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final String KITTEN = "Displacer Kitten", TEFERI = "Teferi, Time Raveler";
    private static final String ORACLE = "Thassa's Oracle", ROCK = "Sol Ring";
    private static final String TOP = "Sensei's Divining Top", CITADEL = "Bolas's Citadel";
    private static final String RESERVOIR = "Aetherflux Reservoir";
    private static final String DOOM = "Doomsday";
    private static final String BREACH = "Underworld Breach", FREEZE = "Brain Freeze";
    private static final String LED = "Lion's Eye Diamond", FUEL = "Ponder";
    private static final String WISHCLAW = "Wishclaw Talisman", DEMONIC = "Demonic Tutor";
    private static final String RECRUITER = "Recruiter of the Guard", BEAR = "Grizzly Bears";
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);

    /** The family is exactly one role short, the tutor is planned, the policy
     * plays it, and the completing piece is the card fetched. */
    private static final List<String> MUST_MOVE = List.of(
            "kiki:engine", "kiki:twin", "kiki:partner", "thopter:foundry", "bomb:stage",
            "monolith:untapper", "kitten:kitten", "top:permission", "doomsday:doomsday");
    /** Two roles short in each new family, so the family names nothing; plus the
     * Wishclaw ordinary-activation parity row. */
    private static final List<String> MUST_NOT_MOVE = List.of(
            "monolith:two-missing", "kitten:two-missing", "top:two-missing",
            "bomb:two-missing", "doomsday:no-oracle", "wishclaw:ordinary");

    private record Placement(String name, ZoneType zone) {}

    private static String variant(String control) { return control.split(":", 2)[1]; }
    private static String family(String control) { return control.split(":", 2)[0]; }

    /** The card that carries the search: in our hand for the two spell/creature
     * shapes, on our battlefield for the activated one. */
    private static String tutorOf(String control) {
        return switch (control) {
            case "kiki:partner" -> RECRUITER;
            case "wishclaw:ordinary" -> WISHCLAW;
            default -> DEMONIC;
        };
    }

    private static String shapeOf(String control) {
        return switch (control) {
            case "kiki:partner" -> "creature-etb";
            case "wishclaw:ordinary" -> "control-transfer";
            default -> "plain-spell";
        };
    }

    private static boolean activated(String control) { return shapeOf(control).equals("control-transfer"); }

    /** The plan piece this control is about, tracked by zone throughout. On a
     * MUST-NOT-MOVE row it is the piece that must NOT be fetched. */
    private static String piece(String control) {
        return switch (control) {
            case "kiki:engine" -> KIKI;
            case "kiki:twin" -> TWIN;
            case "kiki:partner" -> BODY;
            case "thopter:foundry" -> FOUNDRY;
            case "bomb:stage", "bomb:two-missing" -> STAGE;
            case "monolith:untapper", "monolith:two-missing" -> KINNAN;
            case "kitten:kitten", "kitten:two-missing" -> KITTEN;
            case "top:permission", "top:two-missing" -> CITADEL;
            case "doomsday:doomsday", "doomsday:no-oracle" -> DOOM;
            default -> FREEZE;
        };
    }

    /** What {@code chooseTutorPartner} must answer when handed the offered
     * list, or "null".
     *
     * `wishclaw:ordinary` is the v60 row: under v57 this hook steered an
     * ordinary-AI-initiated Wishclaw search toward the Breach piece with no
     * same-turn test at all - v57's own section 8 named that as its first
     * limitation - and under v60 the same test planTutor applies to its own
     * control-transfer plan gates the hook too, so the answer is null. The
     * matched v57 control is expected to answer Brain Freeze on this very row,
     * and that pair is the whole differential for R6. */
    private static String expected(String control) {
        return MUST_MOVE.contains(control) ? piece(control) : "null";
    }

    /** The decline token planTutor must report for a refusal, as the controller
     * prints it. A case that refuses for the WRONG reason fails the suite. */
    private static String expectedReason(String control) {
        if (MUST_MOVE.contains(control)) return "plan";
        // The shape IS admitted (Wishclaw's activated search passes every cost
        // and shape test); the fetch would open the Breach gate but does not win
        // this turn, and the opponent gets the next activation.
        if (control.equals("wishclaw:ordinary")) return "subability:not-same-turn";
        // The plain-spell shape IS admitted; the forecast then finds no name
        // that any family reports as its one completing piece, because every
        // one of these boards is TWO roles short.
        return "other check=no-partner-route";
    }

    /** Does this board give planTutor a MAIN2 route at all? v60 deliberately
     * keeps that outer guard pinned to v45/v57's set - a thopter piece or a
     * Breach/Storm piece - so only these two controls have one. On every other
     * control the MAIN2 arm's only admissible outcome is a decline naming
     * exactly `phase`, and that is asserted rather than recorded. */
    private static boolean main2Route(String control) {
        return List.of("thopter:foundry", "wishclaw:ordinary").contains(control);
    }

    private static boolean asserted(String control, PhaseType phase) {
        return phase == PhaseType.MAIN1 || main2Route(control);
    }

    /** Only the two routes whose finish is a single haste loop are asserted to
     * end the game. Every other family's finish is a multi-action loop or a
     * later-turn route this suite does not register; `kiki:twin`'s win is
     * recorded, not asserted, because it additionally depends on the ordinary
     * AI attaching the Aura, which is v53's decision and not v60's. */
    private static boolean assertsWin(String control) {
        return List.of("kiki:engine", "kiki:partner").contains(control);
    }

    /** Twelve untapped lands on every board, coloured for that control's two
     * casts: the tutor and the piece it fetches, on the same turn, with the
     * disjoint-source reserve planTutor takes between them. */
    private static List<String> lands(String control) {
        return switch (control) {
            case "kiki:engine", "kiki:twin" -> List.of("Mountain", "Mountain", "Mountain", "Mountain", "Mountain",
                    "Swamp", "Swamp", "Swamp", "Swamp", "Island", "Island", "Island");
            case "kiki:partner" -> List.of("Plains", "Plains", "Plains", "Plains",
                    "Island", "Island", "Island", "Island", "Mountain", "Mountain", "Mountain", "Mountain");
            case "thopter:foundry" -> List.of("Swamp", "Swamp", "Swamp", "Swamp", "Swamp",
                    "Island", "Island", "Island", "Island", "Plains", "Plains", "Plains");
            case "monolith:untapper", "monolith:two-missing" -> List.of("Swamp", "Swamp", "Swamp", "Swamp", "Swamp",
                    "Forest", "Forest", "Forest", "Forest", "Island", "Island", "Island");
            case "kitten:kitten", "kitten:two-missing" -> List.of("Swamp", "Swamp", "Swamp", "Swamp", "Swamp",
                    "Island", "Island", "Island", "Island", "Island", "Mountain", "Mountain");
            case "top:permission", "top:two-missing", "doomsday:doomsday", "doomsday:no-oracle" ->
                    List.of("Swamp", "Swamp", "Swamp", "Swamp", "Swamp", "Swamp", "Swamp", "Swamp",
                            "Island", "Island", "Island", "Island");
            case "wishclaw:ordinary" -> List.of("Island", "Island", "Island", "Island", "Island",
                    "Swamp", "Swamp", "Swamp", "Swamp", "Mountain", "Mountain", "Mountain");
            default -> List.of("Swamp", "Swamp", "Swamp", "Swamp", "Swamp",
                    "Island", "Island", "Island", "Island", "Mountain", "Mountain", "Mountain");
        };
    }

    /** Our own battlefield, besides the lands and besides the search carrier. */
    private static List<String> board(String control) {
        return switch (control) {
            // One Kiki half own-visible, the other in the library. `engine` is
            // the v42 legacy pass's own case and is expected to move under the
            // matched v57 control too; `twin` is the name that pass cannot
            // reach, because chooseKikiTutorPartner's engine fetch test is
            // Kiki-Jiki by name.
            case "kiki:engine", "kiki:twin" -> List.of(BODY);
            case "kiki:partner" -> List.of(KIKI);
            // Exactly one thopter piece missing from our own battlefield.
            case "thopter:foundry" -> List.of(URZA, SWORD);
            // The Depths route is one land-clone half short. Both halves are
            // LANDS, so the fetched piece is played as a land drop.
            case "bomb:stage" -> List.of(DEPTHS);
            case "bomb:two-missing" -> List.of();
            // Outlet and engine own-visible, no untapper.
            case "monolith:untapper" -> List.of(BASALT, BALLISTA);
            case "monolith:two-missing" -> List.of(BASALT);
            case "kitten:kitten" -> List.of(TEFERI, ROCK);
            case "kitten:two-missing" -> List.of(ROCK);
            case "top:permission" -> List.of(TOP, RESERVOIR);
            case "top:two-missing" -> List.of(TOP);
            case "doomsday:doomsday", "doomsday:no-oracle" -> List.of();
            // v57's wishclaw board with the plan carrier removed: the Breach
            // gate is one Brain Freeze short and the only search in this
            // position is the Talisman's own, whose SubAbility hands it to an
            // opponent.
            default -> List.of(BREACH, LED);
        };
    }

    /** Our own library, besides the padding. The completing piece is always
     * here, together with at least one card the search legally offers that is
     * NOT the piece, so every refusal is a refusal on a real offered list. */
    private static List<String> library(String control) {
        return switch (control) {
            case "kiki:engine" -> List.of(BEAR, KIKI);
            case "kiki:twin" -> List.of(BEAR, TWIN);
            case "kiki:partner" -> List.of(BEAR, BODY);
            case "thopter:foundry" -> List.of(BEAR, FOUNDRY);
            case "bomb:stage" -> List.of(BEAR, STAGE);
            case "bomb:two-missing" -> List.of(BEAR, DEPTHS, STAGE);
            case "monolith:untapper" -> List.of(BEAR, KINNAN);
            case "monolith:two-missing" -> List.of(BEAR, KINNAN, BALLISTA);
            case "kitten:kitten" -> List.of(BEAR, KITTEN, ORACLE);
            case "kitten:two-missing" -> List.of(BEAR, KITTEN, TEFERI, ORACLE);
            case "top:permission" -> List.of(BEAR, CITADEL);
            case "top:two-missing" -> List.of(BEAR, CITADEL, RESERVOIR);
            case "doomsday:doomsday" -> List.of(BEAR, DOOM, ORACLE);
            // No Thassa's Oracle anywhere in the 40, so availableInOwnDeck is
            // false and the family names nothing even though everything else
            // about the gate is one card short.
            case "doomsday:no-oracle" -> List.of(BEAR, DOOM);
            default -> List.of(FUEL, FREEZE);
        };
    }

    private static List<Placement> placements(boolean owner, String control) {
        List<Placement> result = new ArrayList<>();
        if (!owner) {
            // A real blocker on every board, so no ordinary attack of ours is
            // lethal and no selection can take credit for a game the ordinary
            // AI was about to win.
            result.add(new Placement(BEAR, ZoneType.Battlefield));
            while (result.size() < 40) result.add(new Placement("Forest", ZoneType.Library));
            return result;
        }
        for (String land : lands(control)) result.add(new Placement(land, ZoneType.Battlefield));
        result.add(new Placement(tutorOf(control), activated(control) ? ZoneType.Battlefield : ZoneType.Hand));
        for (String name : board(control)) result.add(new Placement(name, ZoneType.Battlefield));
        // Graveyard fuel for the Breach gate of the Wishclaw parity row, which
        // is what makes that gate exactly one Brain Freeze short.
        if (control.equals("wishclaw:ordinary"))
            for (int i = 0; i < 12; i++) result.add(new Placement(FUEL, ZoneType.Graveyard));
        for (String name : library(control)) result.add(new Placement(name, ZoneType.Library));
        while (result.size() < 40) result.add(new Placement("Island", ZoneType.Library));
        if (result.size() != 40) throw new AssertionError("board overflows 40 for " + control);
        return result;
    }

    private static Deck deck(boolean owner, String control) {
        Deck result = new Deck("Completing piece diagnostic");
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
            // Both of these counters arrive through an enters replacement that a
            // fixture placement never fires, and both are load-bearing: without
            // the wish counters Wishclaw's cost is unpayable and the row tests
            // nothing, and without the ice counters Dark Depths is sacrificed
            // for a 20/20 on turn one, which is not the board this control sets
            // up. Three and ten are the printed numbers. CubeTutorShapeSmoke
            // handles both the same way.
            // Walking Ballista and Teferi are the same case for a different
            // reason: a 0/0 artifact creature and a 0-loyalty planeswalker are
            // both destroyed by the state-based actions the harness runs right
            // after populate, so without their printed entry counters neither
            // board is the board its control claims to set up. One +1/+1
            // counter is the least that keeps the Ballista alive and is
            // deliberately far short of lethal; four is Teferi's printed
            // starting loyalty.
            String counter = p.name().equals(WISHCLAW) ? "WISH" : p.name().equals(DEPTHS) ? "ICE"
                    : p.name().equals(BALLISTA) ? "P1P1" : p.name().equals(TEFERI) ? "LOYALTY" : null;
            if (counter != null && p.zone() == ZoneType.Battlefield) {
                com.google.common.collect.Multiset<CounterType> counters = com.google.common.collect.HashMultiset.create();
                counters.add(CounterType.getType(counter), switch (counter) {
                    case "WISH" -> 3;
                    case "ICE" -> 10;
                    case "LOYALTY" -> 4;
                    default -> 1;
                });
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

    /** The search ability this control's carrier would put on the stack, rebuilt
     * here the way the policy rebuilds it: a hand spell's own ability for the
     * plain shape; for a creature, the enters trigger's Execute SVar parsed
     * through the native ability factory (a detached parse, never
     * Trigger.ensureAbility, which would cache onto the live trigger); for a
     * permanent, its activated ability. Read-only. */
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
        if (shapeOf(control).equals("plain-spell")) {
            for (SpellAbility ability : carrier.getSpellAbilities()) {
                if (!ability.isSpell() || ability.getApi() != forge.game.ability.ApiType.ChangeZone
                        || !"Library".equals(ability.getParam("Origin"))
                        || !"Hand".equals(ability.getParam("Destination"))) continue;
                SpellAbility search = ability.copy(player);
                search.setActivatingPlayer(player);
                return search;
            }
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
     * satisfy the search's own printed ChangeType. */
    private static CardCollection offered(Player player, SpellAbility search) {
        String[] types = search.getParamOrDefault("ChangeType", "Card").split(",");
        CardCollection result = new CardCollection();
        for (Card card : player.getCardsIn(ZoneType.Library))
            if (card.isValid(types, player, search.getHostCard(), search)) result.add(card);
        return result;
    }

    private static String named(Card card) { return card == null ? "null" : card.getName().replace(' ', '_'); }

    /** Observability read of planTutor's own decline token, which the controller
     * prints as CUBE_PLAN_DECLINE family=kiki-tutor. Package-private in
     * forge.ai, so reflection; a pure read with no argument and no state. */
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
    private static void probePieces(Player player, Player opponent, String control, PhaseType phase) {
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
                if (outside == null) outside = own(player, ZoneType.Battlefield, "Island");
                if (outside == null) outside = own(player, ZoneType.Battlefield, "Swamp");
                if (outside != null)
                    notInLibrary = named(forge.ai.CubeComboAi.chooseTutorPartner(player, search, new CardCollection(outside)));
                SpellAbility theirs = search.copy(opponent);
                foreignActor = named(forge.ai.CubeComboAi.chooseTutorPartner(player, theirs, choices));
            }
            System.out.println("COMPLETING_PIECES_PROPOSAL " + key + " name=offered outcome=" + selected
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
                throw new AssertionError("piece probe changed native state: " + before + " -> " + snapshot(player));
            BenchRandomAudit.assertUnchanged(rng, "completing-pieces-preview");
            // The forecast itself, and the reason it gives when it declines.
            // Outside the snapshot window: planTutor prices two casts through
            // the native payment helpers.
            var plan = forge.ai.CubeComboAi.planTutor(player);
            String verdict = plan == null ? "null" : "plan";
            String reason = declineReason();
            System.out.println("COMPLETING_PIECES_PROPOSAL " + key + " name=plan-tutor verdict=" + verdict
                    + " reason=" + reason.replace(' ', '_')
                    + " partner=" + (plan == null ? "null" : plan.plannedPartner().replace(' ', '_'))
                    + " action=" + (plan == null ? "null" : plan.tutor().getHostCard().getName().replace(' ', '_'))
                    + " family=" + family(control) + " shape=" + shapeOf(control));
            if (!Boolean.getBoolean("forge.test.requireCompletingPieces")) return;
            if (!asserted(control, phase)) {
                // MAIN2 on a board with no thopter and no Breach/Storm piece
                // missing: planTutor's own outer guard rejects the phase before
                // any shape is tested, and that is the whole admissible outcome.
                if (!verdict.equals("null") || !reason.equals("phase"))
                    throw new AssertionError("MAIN2 without a plan route must decline naming phase: "
                            + key + " verdict=" + verdict + " reason=" + reason);
                return;
            }
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

    /** Where the search carrier ended up - a receipt of Wishclaw's control
     * transfer, which is a cost this policy accepts deliberately. */
    private static String carrierZone(Player player, String control) {
        String name = tutorOf(control);
        for (ZoneType zone : ZONES) if (inZone(player, zone, name)) return zone.name();
        return "not-ours";
    }

    private static void run(boolean improved, int seat, PhaseType phase, String control) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat, control)).setPlayer(
                improved && s == seat ? new forge.ai.LobbyPlayerCubeComboAi("CubeCombo-" + s)
                        : forge.player.GamePlayerUtil.createAiPlayer("Default-" + s, s, 0, null, "Default")));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "native completing piece diagnostic").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, control);
        populate(opponent, false, control);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().setupFirstTurn(player, () -> game.getPhaseHandler().devModeSet(phase, player));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(60600 + seat * 100 + control.length());
        String piece = piece(control);
        if (improved) probePieces(player, opponent, control, phase);
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " phase=" + phase + " control=" + control;
        System.out.println("COMPLETING_PIECES_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " infoPolicy=CLOSED_REPAIR registered=40 initialMana=0"
                + " tutor=" + tutorOf(control).replace(' ', '_') + " shape=" + shapeOf(control)
                + " family=" + family(control) + " variant=" + variant(control)
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
                    System.out.println("COMPLETING_PIECES_CAST " + key + " step=" + steps + " turn=" + game.getPhaseHandler().getTurn()
                            + " phaseNow=" + game.getPhaseHandler().getPhase() + " card=" + sa.getHostCard().getName().replace(' ', '_')
                            + " spell=" + sa.isSpell() + " copied=" + sa.isCopied());
            }
            // The policy's own counter is the shape-independent detector: it
            // increments only inside the TutorPlan branch of the controller.
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
            if (!state.equals(previous)) System.out.println("COMPLETING_PIECES_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 1800) throw new AssertionError("native step budget exhausted " + key);
        boolean won = player.hasWon();
        System.out.println("COMPLETING_PIECES_RESULT " + key + " won=" + won + " gameOver=" + game.isGameOver()
                + " turn=" + game.getPhaseHandler().getTurn() + " steps=" + steps
                + " tutorPlanCasts=" + tutorPlanCasts(player) + " changes=" + selectionChanges(player)
                + " pieceAtResolution=" + pieceAtResolution
                + " pieceInLibraryEnd=" + inZone(player, ZoneType.Library, piece)
                + " carrierZone=" + carrierZone(player, control)
                + " life=" + player.getLife() + " opponentLife=" + opponent.getLife());
        if (!improved) {
            // Default-arm parity: the Default AI is not enabled(), so every hook
            // returns at its first guard and no plan can ever be played.
            if (tutorPlanCasts(player) != 0 || selectionChanges(player) != 0)
                throw new AssertionError("Default arm played a planned tutor: " + key);
            return;
        }
        if (!Boolean.getBoolean("forge.test.requireCompletingPieces")) return;
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
            throw new AssertionError("MUST-MOVE: the policy never played the tutor: " + key);
        if (!pieceAtResolution.equals("Hand") && !pieceAtResolution.equals("Battlefield"))
            throw new AssertionError("MUST-MOVE: the completing piece was not fetched: " + key
                    + " piece=" + pieceAtResolution);
        if (assertsWin(control) && !won)
            throw new AssertionError("MUST-MOVE: the route did not finish after the fetch: " + key);
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "completing-pieces-diagnostic-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (String name : List.of(KIKI, BODY, TWIN, URZA, FOUNDRY, SWORD, DEPTHS, STAGE, BASALT, BALLISTA,
                    KINNAN, KITTEN, TEFERI, ORACLE, ROCK, TOP, CITADEL, RESERVOIR, DOOM, BREACH, FREEZE, LED,
                    FUEL, WISHCLAW, DEMONIC, RECRUITER, BEAR,
                    "Island", "Swamp", "Mountain", "Plains", "Forest"))
                StaticData.instance().attemptToLoadCard(name);
            List<String> cases = args.length > 2 ? switch (args[2]) {
                case "must-move" -> MUST_MOVE;
                case "must-not-move" -> MUST_NOT_MOVE;
                default -> java.util.stream.Stream.concat(MUST_MOVE.stream(), MUST_NOT_MOVE.stream()).toList();
            } : java.util.stream.Stream.concat(MUST_MOVE.stream(), MUST_NOT_MOVE.stream()).toList();
            for (int seat = 0; seat < 2; seat++) for (PhaseType phase : List.of(PhaseType.MAIN1, PhaseType.MAIN2))
                for (String control : cases) run(args[1].equals("improved"), seat, phase, control);
            System.out.println("COMPLETING_PIECES_SUITE_COMPLETE cases=" + (4 * cases.size()));
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
