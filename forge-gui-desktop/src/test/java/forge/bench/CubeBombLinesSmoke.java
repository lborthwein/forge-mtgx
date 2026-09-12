package forge.bench;

import forge.StaticData;
import forge.card.CardEdition;
import forge.card.CardRules;
import forge.card.GamePieceType;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CounterType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.item.IPaperCard;
import forge.item.PaperToken;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Observation of native decisions from assembled "cheat a bomb" positions.
 *
 * Diagnostic of what Forge's Default AI does from an already-assembled board,
 * not a claim about whole-game strength and not a policy test. No action is
 * supplied by the host; setup is synthetic and exactly registered. Neither arm
 * carries a win assertion: the improved arm is the current cube-combo
 * controller (v50), which has no plan for any of these lines, so it is present
 * only as a do-no-harm reading against Default.
 *
 * Registered in 2026-09-12-bomb-lines-diagnosis/registration.md before any run.
 */
public final class CubeBombLinesSmoke {
    private static final String DEPTHS = "Dark Depths";
    private static final String STAGE = "Thespian's Stage";
    private static final String HEXMAGE = "Vampire Hexmage";
    private static final String SNEAK = "Sneak Attack";
    private static final String BREACH = "Through the Breach";
    private static final String SHOWTELL = "Show and Tell";
    private static final String EMRAKUL = "Emrakul, the Aeons Torn";
    private static final String GRISELBRAND = "Griselbrand";
    private static final String HOOF = "Craterhoof Behemoth";
    private static final String ORDER = "Natural Order";
    private static final String REANIMATE = "Reanimate";
    private static final String ANIMATE = "Animate Dead";
    private static final String ELVES = "Llanowar Elves";
    private static final String MARIT = "Marit Lage";
    // v55 conversion positions (design-v55-bomb-conversion.md, fixture table).
    private static final String BLIGHTSTEEL = "Blightsteel Colossus";
    private static final String ARCHON = "Archon of Cruelty";
    private static final String ATRAXA = "Atraxa, Grand Unifier";
    private static final String WURM = "Worldspine Wurm";
    private static final String ULAMOG = "Ulamog, the Ceaseless Hunger";
    private static final String FOUNDRY = "Retrofitter Foundry";
    private static final String BEAST = "Questing Beast";
    private static final String SPIDER = "Giant Spider";
    private static final String BEARS = "Grizzly Bears";
    /** v62 value payloads. Ashen Rider is the term 3 + term 4 card: its printed
     * script carries BOTH an enters trigger and a dies trigger executing the
     * same `DB$ ChangeZone | Origin$ Battlefield | Destination$ Exile`. */
    private static final String ASHEN = "Ashen Rider";
    /** v58: the token script whose printed body the blocker forecast has to
     * read. `c_1_1_a_servo` is what Retrofitter Foundry's {2}, {T} ability makes
     * and what its flying-Thopter ability sacrifices. */
    private static final String SERVO = "c_1_1_a_servo";
    /** v71 Tinker positions. Tinker is the library->battlefield cheat-in the
     * census names; Portal to Phyrexia and Blightsteel Colossus are the two
     * payloads its own printed script can reach; Juggernaut is the valueless
     * one (two printed STATIC abilities, no trigger at all, 5 power); the
     * Ornithopter is the expendable artifact the sacrifice is paid with and
     * Sensei's Divining Top the piece another plan owns. */
    private static final String TINKER = "Tinker";
    private static final String PORTAL = "Portal to Phyrexia";
    private static final String JUGGERNAUT = "Juggernaut";
    private static final String ORNITHOPTER = "Ornithopter";
    private static final String PETAL = "Lotus Petal";
    private static final String TOP = "Sensei's Divining Top";

    /** Prepared positions (a)-(g) of the owner's question. Each is legal and
     * already assembled: the only thing under observation is whether Default
     * takes the line. */
    private static final List<String> LINES = List.of(
            "depths-stage",            // (a) Depths + Stage on battlefield, {2} available
            "depths-hexmage",          // (b) Depths on battlefield, Hexmage in hand
            "depths-hexmage-bf",       // (b') Depths and Hexmage both on battlefield
            "sneak-emrakul",           // (c) Sneak Attack on battlefield, Emrakul in hand, R available
            "breach-emrakul",          // (d) Through the Breach and Emrakul in hand, 5 mana
            "showtell-emrakul",        // (e) Show and Tell and Emrakul in hand, opponent hand empty
            "reanimate-griselbrand",   // (f) Reanimate in hand, Griselbrand in graveyard
            "animate-emrakul",         // (f') Animate Dead in hand, Emrakul in graveyard
            "natural-order-hoof");     // (g) Natural Order in hand, Craterhoof in library, board of Elves

    /** Controls: the same position with the line illegal, blanked or answered
     * by a public permanent. `opp-hand` is not a block - it is the same (e)
     * position with a card in the opponent's hand, because Forge's hidden-origin
     * ChangeZone gate reads every defined player's origin zone. */
    private static final List<String> CONTROLS = List.of(
            "depths-stage:no-mana",           // cannot pay the Stage's {2}
            "depths-stage:wasteland",         // opponent can destroy a nonbasic
            "depths-hexmage:no-mana",         // cannot cast the Hexmage
            "sneak-emrakul:priest",           // Containment Priest exiles the cheated-in bomb
            "sneak-emrakul:karakas",          // Karakas answers the legendary bomb
            "sneak-emrakul:no-mana",          // cannot pay {R}
            "breach-emrakul:priest",
            "showtell-emrakul:opp-hand",      // opponent holds a permanent card
            "showtell-emrakul:priest",
            "reanimate-griselbrand:rip",      // Rest in Peace empties the graveyard
            "animate-emrakul:priest",
            "natural-order-hoof:torpor-orb"); // Craterhoof's ETB pump is blanked

    /** v52 MUST-MOVE: the four lines the design's D1-D4 are expected to move,
     * plus the two rows that must NOT regress (the `opp-hand` Show and Tell
     * Default already casts, and the own-turn Sneak Attack Default already
     * takes). Every name here is one of the positions above, so each row keeps
     * its diagnosis seed and its Default receipt. */
    private static final List<String> BOMBS = List.of(
            "depths-stage",
            "showtell-emrakul",
            "showtell-emrakul:opp-hand",
            "breach-emrakul",
            "sneak-emrakul");

    /** v52 MUST-NOT-MOVE: the design's controls plus the four the brief adds.
     * `wasteland-tapout` and `bridge` and `rip-empty` are new positions. */
    private static final List<String> GUARDS = List.of(
            "depths-stage:no-mana",
            "depths-stage:karakas",
            "depths-stage:wasteland",
            "depths-stage:wasteland-tapout",
            "sneak-emrakul:priest",
            "sneak-emrakul:karakas",
            "breach-emrakul:priest",
            "breach-emrakul:bridge",
            "showtell-emrakul:priest",
            "reanimate-griselbrand:rip-empty");

    /** v55 conversion cases (registration.md, suite `conversion`). B1-B10 of
     * design-v55-bomb-conversion.md plus B5b, the 789 s1 position the R2 gate
     * must KEEP: one untapped land cannot pay the Foundry's {2}, so no extra
     * blocker exists and the attack is lethal. Each case is a new position, so
     * none of them can disturb a preserved row. */
    private static final List<String> CONVERSION = List.of(
            "breach-blightsteel",          // B1 no public blocker, 11 infect = 10 poison
            "breach-blightsteel:ground",   // B2 a 2/2 blocker: trample leaves 9 poison
            "breach-choice",               // B3 no own-visible payload is lethal
            "breach-choice:low-life",      // B3b a flier WOULD be lethal; the chooser takes Blightsteel
            "breach-reach",                // B4 the reach blocker stops the flier
            "breach-foundry",              // B5 an instant-speed token maker with mana up
            "breach-foundry:one-land",     // B5b the same maker without the mana
            "sat-archon",                  // B6 R4's position, recorded
            "sat-atraxa-low-life",         // B7 R4's position at 3 life, recorded
            "sat-beater-parity",           // B8 Show and Tell must not move
            "depths-sequence",             // B9 R6 land-drop sequencing
            "depths-sequence:half",        // B10 half the pair: R6 must not fire
            // v58 blocker-forecast cases. APPENDED, so every preserved row of
            // this suite keeps its position in the seat/phase loop as well as
            // its own seed.
            "breach-foundry:thopter",      // C1 the sacrifice IS payable and the token FLIES
            "breach-foundry:no-servo",     // C2 nothing to sacrifice: no flier, the line converts
            "breach-foundry:tapped-out");  // C3 the mana half is unpayable: no flier either

    /** v56 payload cases (registration.md, suite `payload`). P1-P7 of
     * design-v56-bomb-payload.md. P1, P3, P4 and P5 are positions the v55
     * conversion suite already registered and are re-used verbatim - P1 is the
     * design's `breach-pick-flier` board exactly (opponent at 7 behind one
     * untapped 4/4 with neither flying nor reach; Through the Breach,
     * Blightsteel Colossus and Griselbrand in hand; five lands) - so the only
     * new positions are the three the design adds. */
    private static final List<String> PAYLOAD = List.of(
            "breach-choice:low-life",      // P1 pick the flier the board cannot block
            "breach-pick-infect",          // P2 keep the infect payload when it is the lethal one
            "sat-archon",                  // P3 an ETB that changes the board beats a bigger beater
            "sat-atraxa-low-life",         // P4 a flying lifelink/deathtouch body while we are behind
            "sat-beater-parity",           // P5 nothing to prefer: the ordinary chooser keeps the pick
            "sneak-choice",                // P6 an ordinary cheat-in the plan does not own
            "sat-opp-hand");               // P7 the opponent's own Show and Tell choice

    /** v62 value cases (registration.md, suite `value`). V1-V9 of
     * design-v62-breach-value.md. Five are NEW positions; V5 and V7 re-use the
     * v55 conversion positions verbatim, because their whole point is parity
     * with a row v55 already registered. The design lists V7 and V8 as two rows
     * of the same board (`breach-blightsteel`, opponent at 20 with no
     * permanents, hand Through the Breach + Blightsteel Colossus), so that
     * position appears ONCE and answers both. */
    private static final List<String> VALUE = List.of(
            "value-annihilator",           // V1 annihilator 6 against four public permanents
            "value-ashen",                 // V2 an ETB and a dies trigger, two exiles
            "value-ashen:reanimate",       // V3 the same, plus the reanimation follow-up
            "value-wurm",                  // V4 the dies trigger leaves three 5/5 tokens
            "breach-blightsteel:ground",   // V5 v55 B2 parity: still declines, new token
            "value-griselbrand",           // V6 pay 7 life, draw 7: the cards outlive the body
            "breach-blightsteel",          // V7/V8 v55 B1 parity: lethal, unchanged
            "value-sneak-choice",          // V9 ordinary Sneak activation, RECORDED
            // Amendment 1: term 5's own witness and its two negatives. A
            // reanimation follow-up is worth nothing behind a body that cannot
            // reach a graveyard and stay there, and four of the cube's biggest
            // bodies cannot.
            "value-ulamog",                // V10 the same board WITHOUT the follow-up: declines
            "value-ulamog:reanimate",      // V11 term 5 ALONE passes the gate
            "value-blightsteel:reanimate");// V12 the shuffle replacement blocks term 5

    /** v71 Tinker cases (registration.md, suite `tinker`). Every one is a NEW
     * base, so no preserved row of any suite can move. T1/T1b are the
     * MUST-MOVE pair and T1b is the library-ORDER control: the same
     * composition with the payload thirty cards deeper must produce the same
     * decision. T7 is the census's claim (c), recorded rather than asserted -
     * `CubeBombPlan` is said to admit and prefer Portal under Show and Tell
     * already. */
    private static final List<String> TINKER_CASES = List.of(
            "tinker-portal",          // T1 the library holds Portal; the opponent has three creatures
            "tinker-portal:deep",     // T1b the same composition, the payload 30 cards deeper
            "tinker-blightsteel",     // T2 Blightsteel AND Portal in the library: which is fetched
            "tinker-vanilla",         // T3 the library's only artifact is a vanilla beater
            "tinker-sac-safe",        // T4 an expendable artifact beside the piece
            "tinker-sac-choice",      // T5 only a spent Petal beside the piece
            "tinker-no-sac",          // T6 no artifact on our battlefield at all
            "showtell-portal");       // T7 the census's claim (c), RECORDED

    /** Registered starting life. 20 everywhere else, and no preserved row ever
     * calls setLife. */
    private static int ownLife(String control) {
        return control.equals("sat-atraxa-low-life") ? 3 : 20;
    }

    private static int opponentLife(String control) {
        return switch (control) {
            case "breach-choice:low-life", "breach-reach" -> 7;
            case "breach-foundry", "breach-foundry:one-land",
                 "breach-foundry:thopter", "breach-foundry:no-servo",
                 "breach-foundry:tapped-out" -> 6;
            default -> 20;
        };
    }

    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);
    private record Placement(String name, ZoneType zone) {}

    /** The bases suite `conversion` owns. Membership, not the variant switch,
     * decides the opponent's board for these positions. */
    private static final Set<String> CONVERSION_BASES = new HashSet<>(List.of(
            "breach-blightsteel", "breach-choice", "breach-reach", "breach-foundry",
            "sat-archon", "sat-atraxa-low-life", "sat-beater-parity", "depths-sequence",
            // v56 adds its three new bases to the same by-base opponent switch,
            // so no preserved control's placements are touched here either.
            "breach-pick-infect", "sneak-choice", "sat-opp-hand",
            // v62 adds its five new bases to the same by-base opponent switch,
            // so no preserved control's placements are touched here either.
            "value-annihilator", "value-ashen", "value-wurm", "value-griselbrand",
            "value-sneak-choice", "value-ulamog", "value-blightsteel"));

    /** v71: the bases suite `tinker` owns. Same by-base opponent switch the
     * conversion suite uses, kept separate so no preserved control's
     * placements are touched. */
    private static final Set<String> TINKER_BASES = new HashSet<>(List.of(
            "tinker-portal", "tinker-blightsteel", "tinker-vanilla",
            "tinker-sac-safe", "tinker-sac-choice", "tinker-no-sac", "showtell-portal"));

    private static String base(String control) { return control.split(":")[0]; }
    private static String variant(String control) { return control.contains(":") ? control.split(":", 2)[1] : ""; }

    /** The card whose arrival on the battlefield is the point of the line. */
    private static String payoff(String control) {
        return switch (base(control)) {
            case "depths-stage", "depths-hexmage", "depths-hexmage-bf", "depths-sequence" -> MARIT;
            case "reanimate-griselbrand" -> GRISELBRAND;
            case "natural-order-hoof" -> HOOF;
            case "breach-blightsteel", "breach-choice", "breach-pick-infect" -> BLIGHTSTEEL;
            case "breach-reach", "breach-foundry" -> GRISELBRAND;
            case "sat-archon", "sat-opp-hand" -> ARCHON;
            case "sat-atraxa-low-life" -> ATRAXA;
            case "sat-beater-parity", "value-wurm" -> WURM;
            case "value-ashen" -> ASHEN;
            case "value-griselbrand" -> GRISELBRAND;
            case "value-ulamog" -> ULAMOG;
            case "value-blightsteel" -> BLIGHTSTEEL;
            // v71: the card whose arrival on OUR battlefield is the point of
            // the Tinker line. For `tinker-blightsteel` that is the bigger of
            // the two library artifacts, which is what claim (b) is about.
            case "tinker-blightsteel" -> BLIGHTSTEEL;
            case "tinker-vanilla" -> JUGGERNAUT;
            case "tinker-portal", "tinker-sac-safe", "tinker-sac-choice",
                 "tinker-no-sac", "showtell-portal" -> PORTAL;
            default -> EMRAKUL;
        };
    }

    private static List<Placement> placements(boolean owner, String control) {
        List<Placement> result = new ArrayList<>();
        String base = base(control), variant = variant(control);
        boolean noMana = variant.equals("no-mana");
        if (owner) {
            switch (base) {
                case "depths-stage" -> {
                    result.add(new Placement(DEPTHS, ZoneType.Battlefield));
                    result.add(new Placement(STAGE, ZoneType.Battlefield));
                    // `wasteland-tapout` is the design's SOFT Wasteland decline:
                    // the same opponent answer, but only two Forests, so paying
                    // the Stage's {2} leaves us with no mana at all.
                    int forests = noMana ? 0 : variant.equals("wasteland-tapout") ? 2 : 4;
                    for (int i = 0; i < forests; i++) result.add(new Placement("Forest", ZoneType.Battlefield));
                }
                case "depths-hexmage" -> {
                    result.add(new Placement(DEPTHS, ZoneType.Battlefield));
                    result.add(new Placement(HEXMAGE, ZoneType.Hand));
                    if (!noMana) for (int i = 0; i < 4; i++) result.add(new Placement("Swamp", ZoneType.Battlefield));
                }
                case "depths-hexmage-bf" -> {
                    result.add(new Placement(DEPTHS, ZoneType.Battlefield));
                    result.add(new Placement(HEXMAGE, ZoneType.Battlefield));
                    for (int i = 0; i < 2; i++) result.add(new Placement("Swamp", ZoneType.Battlefield));
                }
                case "sneak-emrakul" -> {
                    result.add(new Placement(SNEAK, ZoneType.Battlefield));
                    result.add(new Placement(EMRAKUL, ZoneType.Hand));
                    if (!noMana) for (int i = 0; i < 4; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                }
                case "breach-emrakul" -> {
                    result.add(new Placement(BREACH, ZoneType.Hand));
                    result.add(new Placement(EMRAKUL, ZoneType.Hand));
                    if (!noMana) for (int i = 0; i < 5; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                }
                case "showtell-emrakul" -> {
                    result.add(new Placement(SHOWTELL, ZoneType.Hand));
                    result.add(new Placement(EMRAKUL, ZoneType.Hand));
                    if (!noMana) for (int i = 0; i < 4; i++) result.add(new Placement("Island", ZoneType.Battlefield));
                }
                case "reanimate-griselbrand" -> {
                    result.add(new Placement(REANIMATE, ZoneType.Hand));
                    // `rip-empty` is the corrected graveyard-hate control. The
                    // original `:rip` placed Rest in Peace straight onto the
                    // battlefield, so its "exile all graveyards" ETB trigger
                    // never ran and the graveyard was never emptied - the
                    // invalid control the diagnosis recorded. This models the
                    // state AFTER that trigger resolves: the hoser in play and
                    // the reanimation target already exiled.
                    result.add(new Placement(GRISELBRAND,
                            variant.equals("rip-empty") ? ZoneType.Exile : ZoneType.Graveyard));
                    if (!noMana) for (int i = 0; i < 4; i++) result.add(new Placement("Swamp", ZoneType.Battlefield));
                }
                case "animate-emrakul" -> {
                    result.add(new Placement(ANIMATE, ZoneType.Hand));
                    result.add(new Placement(EMRAKUL, ZoneType.Graveyard));
                    if (!noMana) for (int i = 0; i < 4; i++) result.add(new Placement("Swamp", ZoneType.Battlefield));
                }
                case "natural-order-hoof" -> {
                    result.add(new Placement(ORDER, ZoneType.Hand));
                    result.add(new Placement(HOOF, ZoneType.Library));
                    for (int i = 0; i < 5; i++) result.add(new Placement(ELVES, ZoneType.Battlefield));
                    if (!noMana) for (int i = 0; i < 4; i++) result.add(new Placement("Forest", ZoneType.Battlefield));
                }
                // ---------------------------------------- v55 conversion positions
                case "breach-blightsteel" -> {
                    result.add(new Placement(BREACH, ZoneType.Hand));
                    result.add(new Placement(BLIGHTSTEEL, ZoneType.Hand));
                    for (int i = 0; i < 5; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                }
                case "breach-choice" -> {
                    result.add(new Placement(BREACH, ZoneType.Hand));
                    result.add(new Placement(BLIGHTSTEEL, ZoneType.Hand));
                    result.add(new Placement(GRISELBRAND, ZoneType.Hand));
                    for (int i = 0; i < 5; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                }
                case "breach-reach", "breach-foundry" -> {
                    result.add(new Placement(BREACH, ZoneType.Hand));
                    result.add(new Placement(GRISELBRAND, ZoneType.Hand));
                    for (int i = 0; i < 5; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                }
                case "sat-archon" -> {
                    result.add(new Placement(SHOWTELL, ZoneType.Hand));
                    result.add(new Placement(ULAMOG, ZoneType.Hand));
                    result.add(new Placement(ARCHON, ZoneType.Hand));
                    for (int i = 0; i < 3; i++) result.add(new Placement("Island", ZoneType.Battlefield));
                }
                case "sat-atraxa-low-life" -> {
                    result.add(new Placement(SHOWTELL, ZoneType.Hand));
                    result.add(new Placement(WURM, ZoneType.Hand));
                    result.add(new Placement(ATRAXA, ZoneType.Hand));
                    for (int i = 0; i < 4; i++) result.add(new Placement("Island", ZoneType.Battlefield));
                }
                case "sat-beater-parity" -> {
                    result.add(new Placement(SHOWTELL, ZoneType.Hand));
                    result.add(new Placement(WURM, ZoneType.Hand));
                    for (int i = 0; i < 4; i++) result.add(new Placement("Island", ZoneType.Battlefield));
                }
                // ------------------------------------- v56 payload positions
                case "breach-pick-infect" -> {
                    // P2. The converse of P1: no public blocker at all, so the
                    // infect payload IS the lethal one and R3 must keep it.
                    result.add(new Placement(BREACH, ZoneType.Hand));
                    result.add(new Placement(GRISELBRAND, ZoneType.Hand));
                    result.add(new Placement(BLIGHTSTEEL, ZoneType.Hand));
                    for (int i = 0; i < 5; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                }
                case "sneak-choice" -> {
                    // P6. An ORDINARY cheat-in with two payloads in hand. The
                    // bomb plan proposes no Sneak Attack activation (D4 is a
                    // veto arm and breachAction takes spells only), so this
                    // resolution-time choice is not one the hook may answer.
                    result.add(new Placement(SNEAK, ZoneType.Battlefield));
                    result.add(new Placement(EMRAKUL, ZoneType.Hand));
                    result.add(new Placement(GRISELBRAND, ZoneType.Hand));
                    for (int i = 0; i < 4; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                }
                case "sat-opp-hand" -> {
                    // P7. sat-archon's hand against an opponent who also has
                    // permanents to put in, so the log carries BOTH choices.
                    result.add(new Placement(SHOWTELL, ZoneType.Hand));
                    result.add(new Placement(ULAMOG, ZoneType.Hand));
                    result.add(new Placement(ARCHON, ZoneType.Hand));
                    for (int i = 0; i < 3; i++) result.add(new Placement("Island", ZoneType.Battlefield));
                }
                // ------------------------------------- v62 value positions
                case "value-annihilator" -> {
                    // V1. Emrakul's printed `K:Annihilator:6` against four
                    // public permanents. 15 power cannot kill from 20, so v61
                    // declines; the permanents the attack takes are the value.
                    result.add(new Placement(BREACH, ZoneType.Hand));
                    result.add(new Placement(EMRAKUL, ZoneType.Hand));
                    for (int i = 0; i < 5; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                }
                case "value-ashen" -> {
                    // V2/V3. Ashen Rider exiles a permanent as it enters and a
                    // second one when Through the Breach's end-step SACRIFICE
                    // (`AtEOT$ Sacrifice`, not an exile) fires its dies trigger.
                    // `:reanimate` adds the follow-up spell and the sixth land
                    // that pays for it after the untap.
                    result.add(new Placement(BREACH, ZoneType.Hand));
                    result.add(new Placement(ASHEN, ZoneType.Hand));
                    if (variant.equals("reanimate")) result.add(new Placement(REANIMATE, ZoneType.Hand));
                    for (int i = 0; i < 5; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                    if (variant.equals("reanimate")) result.add(new Placement("Swamp", ZoneType.Battlefield));
                }
                case "value-wurm" -> {
                    // V4. The dies trigger leaves three 5/5 trample tokens on
                    // OUR battlefield after the body is sacrificed.
                    result.add(new Placement(BREACH, ZoneType.Hand));
                    result.add(new Placement(WURM, ZoneType.Hand));
                    for (int i = 0; i < 5; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                }
                case "value-griselbrand" -> {
                    // V6. Pay 7 life, draw 7. Nothing about the attack is
                    // lethal; the seven cards stay after the sacrifice.
                    result.add(new Placement(BREACH, ZoneType.Hand));
                    result.add(new Placement(GRISELBRAND, ZoneType.Hand));
                    for (int i = 0; i < 5; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                }
                case "value-ulamog", "value-blightsteel" -> {
                    // V10/V11/V12. Term 5's witness and its two negatives, on one
                    // board: an opponent whose blocker has VIGILANCE, so it never
                    // taps and the attack is never lethal on any turn - the only
                    // thing that can pass the gate is the reanimation follow-up.
                    //
                    // Ulamog, the Ceaseless Hunger is the clean witness the cube
                    // holds: its exile is `T:Mode$ SpellCast`, a CAST trigger the
                    // Breach never fires, so terms 3 and 4 are zero; it has no
                    // annihilator, no activated draw, and - unlike Emrakul,
                    // Ulamog the Infinite Gyre, Worldspine Wurm and Blightsteel
                    // Colossus - no graveyard-to-library shuffle. There is no
                    // trigger-less 7+ power creature in the cube at all.
                    //
                    // Blightsteel Colossus is the negative: same 11 power, same
                    // follow-up in hand, but `R:Event$ Moved | Destination$
                    // Graveyard | ValidCard$ Card.Self` shuffles it away before
                    // any reanimation could reach it.
                    result.add(new Placement(BREACH, ZoneType.Hand));
                    result.add(new Placement(base.equals("value-ulamog") ? ULAMOG : BLIGHTSTEEL, ZoneType.Hand));
                    if (variant.equals("reanimate")) result.add(new Placement(REANIMATE, ZoneType.Hand));
                    for (int i = 0; i < 5; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                    if (variant.equals("reanimate")) result.add(new Placement("Swamp", ZoneType.Battlefield));
                }
                case "value-sneak-choice" -> {
                    // V9. An ORDINARY Sneak Attack activation with two payloads
                    // in hand. RECORDED, not asserted: this increment does not
                    // widen `ownsPayloadChoice`, so the plan owns no choice here
                    // and the ordinary AI's pick stands (registered deviation).
                    result.add(new Placement(SNEAK, ZoneType.Battlefield));
                    result.add(new Placement(EMRAKUL, ZoneType.Hand));
                    result.add(new Placement(BLIGHTSTEEL, ZoneType.Hand));
                    for (int i = 0; i < 4; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
                }
                case "depths-sequence" -> {
                    // Both halves in hand, three ordinary lands already down and
                    // two ordinary lands in hand competing for the drops. This
                    // is 787 s0's shape: draw was never the problem, sequencing
                    // was.
                    result.add(new Placement(DEPTHS, ZoneType.Hand));
                    if (!variant.equals("half")) result.add(new Placement(STAGE, ZoneType.Hand));
                    result.add(new Placement("Island", ZoneType.Hand));
                    if (!variant.equals("half")) result.add(new Placement("Mountain", ZoneType.Hand));
                    for (int i = 0; i < 3; i++) result.add(new Placement("Forest", ZoneType.Battlefield));
                }
                // ------------------------------------- v71 Tinker positions
                case "tinker-portal", "tinker-sac-safe", "tinker-sac-choice", "tinker-no-sac" -> {
                    // T1/T4/T5/T6. One Tinker, three Islands for its {2}{U},
                    // and Portal to Phyrexia as the library's ONLY artifact, so
                    // the fetch itself has no choice to make and the row is
                    // about the CAST. The battlefield artifact is what changes:
                    //   tinker-portal    an expendable Ornithopter alone
                    //   tinker-sac-safe  the Ornithopter beside a plan piece
                    //   tinker-sac-choice  a Lotus Petal beside that piece -
                    //     the Petal is excluded BY NAME from tinker.txt's own
                    //     `Artifact.cmcEQ0+...+!namedLotus Petal` preference
                    //     tier, so the native payment reaches the {1} tier and
                    //     the piece is what it would take
                    //   tinker-no-sac    no artifact at all: the cost is unpayable
                    result.add(new Placement(TINKER, ZoneType.Hand));
                    if (!base.equals("tinker-no-sac"))
                        result.add(new Placement(base.equals("tinker-sac-choice") ? PETAL : ORNITHOPTER,
                                ZoneType.Battlefield));
                    if (base.equals("tinker-sac-safe") || base.equals("tinker-sac-choice"))
                        result.add(new Placement(TOP, ZoneType.Battlefield));
                    for (int i = 0; i < 3; i++) result.add(new Placement("Island", ZoneType.Battlefield));
                    // T1b: the SAME library composition with the payload thirty
                    // cards deeper. A decision that reads composition and not
                    // order must be identical on both rows.
                    if (variant.equals("deep"))
                        for (int i = 0; i < 30; i++) result.add(new Placement("Forest", ZoneType.Library));
                    result.add(new Placement(PORTAL, ZoneType.Library));
                }
                case "tinker-blightsteel" -> {
                    // T2. Both payloads in the library. Census claim (b) is that
                    // Forge's own hidden-origin chooser takes the most expensive
                    // artifact, which is Blightsteel Colossus (12) over Portal
                    // to Phyrexia (9).
                    result.add(new Placement(TINKER, ZoneType.Hand));
                    result.add(new Placement(ORNITHOPTER, ZoneType.Battlefield));
                    for (int i = 0; i < 3; i++) result.add(new Placement("Island", ZoneType.Battlefield));
                    result.add(new Placement(BLIGHTSTEEL, ZoneType.Library));
                    result.add(new Placement(PORTAL, ZoneType.Library));
                }
                case "tinker-vanilla" -> {
                    // T3. The library's only artifact is a vanilla beater: two
                    // printed STATIC abilities, no trigger, 5 power, and a
                    // public 2/2 to block it. Nothing here clears a value floor.
                    result.add(new Placement(TINKER, ZoneType.Hand));
                    result.add(new Placement(ORNITHOPTER, ZoneType.Battlefield));
                    for (int i = 0; i < 3; i++) result.add(new Placement("Island", ZoneType.Battlefield));
                    result.add(new Placement(JUGGERNAUT, ZoneType.Library));
                }
                case "showtell-portal" -> {
                    // T7. The census's claim (c): Show and Tell with Portal to
                    // Phyrexia in HAND, against an opponent with three creatures
                    // for Portal's enters trigger to take.
                    result.add(new Placement(SHOWTELL, ZoneType.Hand));
                    result.add(new Placement(PORTAL, ZoneType.Hand));
                    for (int i = 0; i < 4; i++) result.add(new Placement("Island", ZoneType.Battlefield));
                }
                default -> throw new AssertionError("unknown line " + base);
            }
        } else if (CONVERSION_BASES.contains(base)) {
            // v55: the conversion positions put the whole opponent board here,
            // by base rather than by variant, so no preserved control's
            // placements are touched.
            switch (base) {
                case "breach-blightsteel" -> {
                    if (variant.equals("ground")) result.add(new Placement(BEARS, ZoneType.Battlefield));
                }
                case "breach-choice" -> result.add(new Placement(BEAST, ZoneType.Battlefield));
                case "breach-reach" -> result.add(new Placement(SPIDER, ZoneType.Battlefield));
                case "breach-foundry" -> {
                    result.add(new Placement(FOUNDRY, ZoneType.Battlefield));
                    // v58: `tapped-out` is the mana half of the Thopter cost on
                    // its own - the Servo is there, the {1} is not. Every other
                    // variant keeps v55's land counts exactly.
                    int forests = variant.equals("one-land") ? 1 : variant.equals("tapped-out") ? 0 : 2;
                    for (int i = 0; i < forests; i++) result.add(new Placement("Forest", ZoneType.Battlefield));
                }
                case "sat-archon" -> result.add(new Placement(BEAST, ZoneType.Battlefield));
                case "sat-atraxa-low-life" -> {
                    for (int i = 0; i < 2; i++) result.add(new Placement(BEARS, ZoneType.Battlefield));
                }
                case "sat-opp-hand" -> {
                    // P7: two permanents in the OPPONENT'S hand, so their own
                    // Show and Tell choice is a real choice and is visible in
                    // the receipt as whichever one reaches their battlefield.
                    result.add(new Placement(SPIDER, ZoneType.Hand));
                    result.add(new Placement(BEARS, ZoneType.Hand));
                }
                // ------------------------------------- v62 value positions
                case "value-annihilator" -> {
                    // V1: four public permanents, two of them blockers. Emrakul
                    // flies, so neither Bears can actually block it - what the
                    // board is for is annihilator's sacrifice count.
                    for (int i = 0; i < 2; i++) result.add(new Placement(BEARS, ZoneType.Battlefield));
                    for (int i = 0; i < 2; i++) result.add(new Placement("Forest", ZoneType.Battlefield));
                }
                case "value-ashen" -> {
                    // V2/V3: two NONLAND permanents, one of them a blocker.
                    // Retrofitter Foundry is the non-blocker: with no land
                    // beside it, its {2},{T} is unpayable on turn 1, and it is
                    // a legal target for both of Ashen Rider's exiles.
                    result.add(new Placement(BEARS, ZoneType.Battlefield));
                    result.add(new Placement(FOUNDRY, ZoneType.Battlefield));
                }
                case "value-wurm" -> {
                    for (int i = 0; i < 3; i++) result.add(new Placement(BEARS, ZoneType.Battlefield));
                }
                // V6: one blocker that can block a flier. Giant Spider's printed
                // reach is what makes the Griselbrand attack worth nothing, so
                // term 6 is the only thing that can pass the gate.
                case "value-griselbrand" -> result.add(new Placement(SPIDER, ZoneType.Battlefield));
                case "value-sneak-choice" -> {
                    // V9: four public permanents and no blocker at all.
                    for (int i = 0; i < 4; i++) result.add(new Placement("Forest", ZoneType.Battlefield));
                }
                // V10-V12: Questing Beast's printed VIGILANCE is what makes this
                // a permanent decline rather than v55 Amendment 2's one-turn
                // one - it attacks and stays a blocker, exactly as it does in
                // the v55 `breach-choice` control.
                case "value-ulamog", "value-blightsteel" -> result.add(new Placement(BEAST, ZoneType.Battlefield));
                // breach-pick-infect and sneak-choice give the opponent no
                // board at all: an empty public battlefield is the position.
                default -> { }
            }
        } else if (TINKER_BASES.contains(base)) {
            // v71: three creatures wherever the payload's enters trigger is
            // what the row is about (Portal to Phyrexia makes each opponent
            // sacrifice three), one blocker where the question is whether a
            // vanilla beater is worth fetching, and nothing at all where the
            // row is about the fetch itself.
            switch (base) {
                case "tinker-portal", "tinker-sac-safe", "tinker-sac-choice", "tinker-no-sac" -> {
                    for (int i = 0; i < 3; i++) result.add(new Placement(BEARS, ZoneType.Battlefield));
                }
                case "showtell-portal" -> {
                    for (int i = 0; i < 3; i++) result.add(new Placement(BEARS, ZoneType.Battlefield));
                    // v52 2(e): Forge's hidden-origin gate refuses Show and Tell
                    // when any named player's origin zone is EMPTY, so the
                    // opponent is given a permanent to put in. This is the same
                    // `opp-hand` shape the v52 suite already registers.
                    result.add(new Placement(SPIDER, ZoneType.Hand));
                }
                case "tinker-vanilla" -> result.add(new Placement(BEARS, ZoneType.Battlefield));
                default -> { }
            }
        } else {
            String permanent = switch (variant) {
                case "priest" -> "Containment Priest";
                case "karakas" -> "Karakas";
                case "wasteland" -> "Wasteland";
                case "rip", "rip-empty" -> "Rest in Peace";
                case "torpor-orb" -> "Torpor Orb";
                case "wasteland-tapout" -> "Wasteland";
                case "bridge" -> "Ensnaring Bridge";
                default -> null;
            };
            if (permanent != null) {
                result.add(new Placement(permanent, ZoneType.Battlefield));
                // Karakas and Wasteland are the opponent's own answer: give them nothing
                // else, so the only public decision is whether the answer is used.
                if (variant.equals("priest")) for (int i = 0; i < 2; i++) result.add(new Placement("Plains", ZoneType.Battlefield));
            }
            if (variant.equals("opp-hand")) result.add(new Placement("Grizzly Bears", ZoneType.Hand));
        }
        while (result.size() < 40) result.add(new Placement("Forest", ZoneType.Library));
        return result;
    }

    /** v58: board state that is NOT a registered deck card. The Servo the
     * Foundry's flying-Thopter ability sacrifices is a token, and a token has no
     * paper card to register, so these are placed AFTER the 40-card assertion in
     * {@link #populate} and no preserved case's `registered=40` line moves.
     * Opponent side only; every other case gets an empty list. */
    private static List<String> tokens(boolean owner, String control) {
        if (owner) return List.of();
        return switch (control) {
            case "breach-foundry:thopter", "breach-foundry:tapped-out" -> List.of(SERVO);
            default -> List.of();
        };
    }

    /** One token onto a battlefield, built straight from the token's RULES with
     * a fixed unknown edition. Deliberately NOT
     * {@code TokenDb.getToken(script)}, which picks an art variant with
     * {@code Aggregates.random} - the fixture must not consume the shared RNG
     * while it is setting a position up. */
    private static Card token(Player player, String script) {
        CardRules rules = Objects.requireNonNull(
                StaticData.instance().getAllTokens().getRules().get(script), script);
        PaperToken paper = new PaperToken(rules, CardEdition.UNKNOWN, script, "", IPaperCard.NO_ARTIST_NAME);
        Card card = CardFactory.getCard(paper, player, player.getGame());
        card.setGamePieceType(GamePieceType.TOKEN);
        return card;
    }

    private static Deck deck(boolean owner, String control) {
        Deck result = new Deck("bomb-lines diagnostic");
        for (Placement p : placements(owner, control)) result.getMain().add(p.name(), 1);
        return result;
    }

    private static void populate(Player player, boolean owner, String control) {
        for (ZoneType z : ZoneType.values()) if (player.getZone(z) != null) player.getZone(z).removeAllCards(true);
        for (Placement p : placements(owner, control)) {
            Card card = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(p.name())), player);
            card.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(p.zone()).add(card);
            card.setSickness(false);
            // Direct zone placement bypasses GameAction.moveTo, so Dark Depths'
            // etbCounter replacement never runs. Without this the land arrives
            // with zero ice counters and its own state trigger makes Marit Lage
            // for free, which is not the position under observation.
            if (p.name().equals(DEPTHS) && p.zone() == ZoneType.Battlefield)
                card.addCounterInternal(CounterType.getType("ICE"), 10, player, false, null, null);
        }
        TreeMap<String, Integer> actual = new TreeMap<>(), registered = new TreeMap<>();
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) actual.merge(c.getName(), 1, Integer::sum);
        for (var c : player.getRegisteredPlayer().getDeck().getMain()) registered.merge(c.getKey().getName(), c.getValue(), Integer::sum);
        if (!actual.equals(registered) || actual.values().stream().mapToInt(Integer::intValue).sum() != 40)
            throw new AssertionError("registration mismatch " + control + " actual=" + actual + " registered=" + registered);
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("initial mana must be empty");
        for (String script : tokens(owner, control)) {
            Card card = token(player, script);
            card.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(ZoneType.Battlefield).add(card);
            card.setSickness(false);
        }
    }

    private static String zoneOf(Player player, String name) {
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) if (c.getName().equals(name)) return z.name();
        return "missing";
    }

    private static int countOnBattlefield(Player player, String name) {
        return (int) player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals(name)).count();
    }

    /** Lowest ice count across every Dark Depths we control; -1 when we control none. */
    private static int minIce(Player player) {
        int best = -1;
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) if (c.getName().equals(DEPTHS)) {
            int ice = c.getCounters(CounterType.getType("ICE"));
            best = best < 0 ? ice : Math.min(best, ice);
        }
        return best;
    }

    /** Static read of each piece's AI deck hint. `AI:RemoveDeck:All` makes
     * ComputerUtilCard.isCardRemAIDeck true, and AiController.getSpellAbilityToPlay
     * drops every ability of such a host from the AI's own candidate list. */
    private static void printHints(String key, Player player) {
        StringBuilder line = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (ZoneType z : List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Library))
            for (Card c : player.getCardsIn(z)) {
                if (c.isBasicLand() || !seen.add(c.getName())) continue;
                line.append(' ').append(c.getName().replace(' ', '_')).append("=remAIDeck:")
                        .append(forge.ai.ComputerUtilCard.isCardRemAIDeck(c))
                        .append(",remRandom:").append(c.getRules() != null && c.getRules().getAiHints().getRemRandomDecks());
            }
        System.out.println("BOMB_HINT " + key + line);
    }

    /** The opponent's PUBLIC non-land battlefield, sorted - which is where P7
     * reads the opponent's own Show and Tell choice from. A fixture read of a
     * public zone after the game; no policy code sees it. */
    private static String publicPermanents(Player opponent) {
        List<String> names = new ArrayList<>();
        for (Card c : opponent.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand()) continue;
            names.add(c.isFaceDown() ? "face-down" : c.getName().replace(' ', '_'));
        }
        java.util.Collections.sort(names);
        return String.join(";", names);
    }

    /** Our own battlefield tokens, which is where v62's term 4 receipt lives:
     * Worldspine Wurm's dies trigger leaves three 5/5 bodies behind after the
     * Breach's end-step sacrifice has taken the Wurm itself. */
    private static int ownTokens(Player player) {
        return (int) player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).count();
    }

    /** v71: our own battlefield artifacts, sorted. The Tinker row's whole
     * sacrifice question is which of these is gone at the end, so it is read
     * from the zone before and after, exactly the way every other receipt in
     * this fixture is read. */
    private static List<String> ownArtifacts(Player player) {
        List<String> names = new ArrayList<>();
        for (Card c : player.getCardsIn(ZoneType.Battlefield))
            if (c.isArtifact()) names.add(c.isFaceDown() ? "face-down" : c.getName().replace(' ', '_'));
        java.util.Collections.sort(names);
        return names;
    }

    /** Public creature count, for Portal to Phyrexia's enters trigger. */
    private static int creatures(Player player) {
        return (int) player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isCreature).count();
    }

    /** Our own graveyard, sorted - where the sacrificed artifact and the spent
     * Tinker both end up. */
    private static String ownGraveyard(Player player) {
        List<String> names = new ArrayList<>();
        for (Card c : player.getCardsIn(ZoneType.Graveyard)) names.add(c.getName().replace(' ', '_'));
        java.util.Collections.sort(names);
        return String.join(";", names);
    }

    /** How many artifact cards our own library still holds. Read AFTER the game
     * by the fixture, never by policy code. */
    private static int libraryArtifacts(Player player) {
        return (int) player.getCardsIn(ZoneType.Library).stream().filter(Card::isArtifact).count();
    }

    private static void run(boolean improved, int seat, PhaseType phase, String control, boolean conversion,
            boolean payload, boolean value, boolean tinker) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat, control)).setPlayer(
                improved && s == seat ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s) : defaultAi(s)));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "native bomb-lines diagnostic").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, control);
        populate(opponent, false, control);
        // v55: three conversion positions turn on a life total (a lethal forecast
        // is a statement about the opponent's life). Only those rows call
        // setLife at all, so no preserved row can move.
        if (ownLife(control) != 20) player.setLife(ownLife(control), null);
        if (opponentLife(control) != 20) opponent.setLife(opponentLife(control), null);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().setupFirstTurn(player, () -> game.getPhaseHandler().devModeSet(phase, player));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(20912 + seat * 100 + control.length());
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " phase=" + phase + " control=" + control;
        System.out.println("BOMB_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " infoPolicy=CLOSED_REPAIR registered=40 initialMana=0 payoff=" + payoff(control).replace(' ', '_')
                + " startIce=" + minIce(player));
        printHints(key, player);
        // v71: the printed shape of every artifact this position makes a
        // decision about, read straight off the Card the policy reads. The
        // expendability rule (no activated ability other than a mana ability,
        // no printed trigger) and the payload tier are both statements about
        // these numbers, so the fixture prints them rather than asserting a
        // rule it cannot see. Tinker suite only.
        if (tinker) for (ZoneType zone : List.of(ZoneType.Battlefield, ZoneType.Library))
            for (Card c : player.getCardsIn(zone)) {
                if (!c.isArtifact()) continue;
                int activated = 0, manaAbilities = 0;
                for (var sa : c.getSpellAbilities()) {
                    if (!sa.isActivatedAbility()) continue;
                    activated++;
                    if (sa.isManaAbility()) manaAbilities++;
                }
                System.out.println("BOMB_SHAPE " + key + " zone=" + zone + " card=" + c.getName().replace(' ', '_')
                        + " cmc=" + c.getCMC() + " triggers=" + c.getTriggers().size()
                        + " statics=" + c.getStaticAbilities().size()
                        + " replacements=" + c.getReplacementEffects().size()
                        + " activated=" + activated + " manaAbilities=" + manaAbilities
                        + " token=" + c.isToken());
            }
        Set<Integer> stackIds = new HashSet<>();
        int steps = 0, stageClones = 0, depthsRemovals = 0, hexmageRemovals = 0, sneakActivations = 0;
        int breachCasts = 0, showTellCasts = 0, reanimations = 0, naturalOrders = 0, karakasBounces = 0, wastelands = 0;
        // v52 D4's registered receipt: our own AILogic$ BeforeCombat cheat-in
        // resolved while it was NOT our turn, so the body can never attack and
        // the end-step trigger throws it away. Default shows 1 on every MAIN2
        // sneak row; the guarded arm must show 0.
        int offTurnCheatIns = 0;
        int lowestIce = minIce(player), maxMarit = 0;
        // v55 R6 receipt: the turn each half of the Depths route reached our
        // battlefield from our hand. Read from zones, not from plan internals,
        // so both arms are measured the same way. -1 = never played.
        int depthsLandTurn = -1, stageLandTurn = -1;
        // v55: the TURN each conversion line actually happened on. R2 is a gate
        // on a turn's attack, so "declined while the blocker was up, cast once
        // it had tapped to attack" is a different receipt from "never cast",
        // and only a turn can tell them apart.
        int breachTurn = -1, showtellTurn = -1, maritTurn = -1;
        // v71 tinker receipts, computed ONLY for the tinker suite so that no
        // preserved suite's step loop does anything new.
        int tinkerCasts = 0, tinkerTurn = -1;
        List<String> artifactsStart = tinker ? ownArtifacts(player) : List.of();
        int oppCreaturesStart = tinker ? creatures(opponent) : -1;
        // v56 P7, Amendment 3: the FIRST non-land permanent to reach the
        // opponent's public battlefield, and the turn it did. That is their own
        // Show and Tell put-in, read where the choice is made rather than at the
        // end of the game - Archon of Cruelty's trigger makes them sacrifice it,
        // so the end-state board is a read of OUR payload, not of their choice.
        String oppFirstPermanent = "none";
        int oppFirstTurn = -1;
        // v62 value receipts. Read from zones after each step, exactly the way
        // the v55/v56 receipts above are read, and computed ONLY for the value
        // suite so that no preserved suite's step loop does anything new.
        int oppPermanentsStart = value ? opponent.getCardsIn(ZoneType.Battlefield).size() : -1;
        int oppPermanentsMin = oppPermanentsStart, maxOwnHand = value ? player.getCardsIn(ZoneType.Hand).size() : -1;
        int maxOwnTokens = value ? ownTokens(player) : -1;
        String previous = "";
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 3 && steps < 600) {
            game.getPhaseHandler().mainLoopStep();
            steps++;
            if (value) {
                oppPermanentsMin = Math.min(oppPermanentsMin, opponent.getCardsIn(ZoneType.Battlefield).size());
                maxOwnHand = Math.max(maxOwnHand, player.getCardsIn(ZoneType.Hand).size());
                maxOwnTokens = Math.max(maxOwnTokens, ownTokens(player));
            }
            int ice = minIce(player);
            if (ice >= 0) lowestIce = lowestIce < 0 ? ice : Math.min(lowestIce, ice);
            if (depthsLandTurn < 0 && countOnBattlefield(player, DEPTHS) > 0)
                depthsLandTurn = game.getPhaseHandler().getTurn();
            if (stageLandTurn < 0 && countOnBattlefield(player, STAGE) > 0)
                stageLandTurn = game.getPhaseHandler().getTurn();
            if (maritTurn < 0 && countOnBattlefield(player, MARIT) > 0)
                maritTurn = game.getPhaseHandler().getTurn();
            if (oppFirstTurn < 0) {
                String seen = publicPermanents(opponent);
                if (!seen.isEmpty()) { oppFirstPermanent = seen; oppFirstTurn = game.getPhaseHandler().getTurn(); }
            }
            maxMarit = Math.max(maxMarit, countOnBattlefield(player, MARIT));
            for (var item : game.getStack()) if (stackIds.add(item.getId())) {
                var sa = item.getSpellAbility();
                String host = sa.getHostCard().getName();
                boolean ours = sa.getActivatingPlayer() == player;
                // API-precise from v52: once the Stage copies Dark Depths both
                // permanents answer to the same NAME, so the counters read the
                // ability instead. Both were already exact for the diagnosis
                // receipts (every Default row recorded stageClones=0 and the
                // only activated ability Dark Depths has is the counter removal).
                if (ours && host.equals(STAGE) && sa.getApi() == forge.game.ability.ApiType.Clone) stageClones++;
                if (ours && sa.getApi() == forge.game.ability.ApiType.RemoveCounter && sa.isActivatedAbility()
                        && sa.getHostCard().getName().equals(DEPTHS)) depthsRemovals++;
                if (ours && "BeforeCombat".equals(sa.getParam("AILogic"))
                        && !game.getPhaseHandler().isPlayerTurn(player)) offTurnCheatIns++;
                if (ours && host.equals(HEXMAGE) && sa.isActivatedAbility()) hexmageRemovals++;
                if (ours && host.equals(SNEAK) && sa.isActivatedAbility()) sneakActivations++;
                if (ours && host.equals(BREACH) && sa.isSpell()) {
                    breachCasts++;
                    if (breachTurn < 0) breachTurn = game.getPhaseHandler().getTurn();
                }
                if (ours && host.equals(SHOWTELL) && sa.isSpell()) {
                    showTellCasts++;
                    if (showtellTurn < 0) showtellTurn = game.getPhaseHandler().getTurn();
                }
                if (ours && host.equals(TINKER) && sa.isSpell()) {
                    tinkerCasts++;
                    if (tinkerTurn < 0) tinkerTurn = game.getPhaseHandler().getTurn();
                }
                if (ours && (host.equals(REANIMATE) || host.equals(ANIMATE)) && sa.isSpell()) reanimations++;
                if (ours && host.equals(ORDER) && sa.isSpell()) naturalOrders++;
                if (!ours && host.equals("Karakas") && sa.isActivatedAbility()) karakasBounces++;
                if (!ours && host.equals("Wasteland") && sa.isActivatedAbility()) wastelands++;
                System.out.println("BOMB_STACK " + key + " step=" + steps + " source=" + host.replace(' ', '_')
                        + " api=" + sa.getApi() + " ours=" + ours + " trigger=" + sa.isTrigger()
                        + " spell=" + sa.isSpell() + " costs=" + sa.getPayCosts());
            }
            String state = "turn=" + game.getPhaseHandler().getTurn() + " currentPhase=" + game.getPhaseHandler().getPhase()
                    + " life=" + player.getLife() + " opponentLife=" + opponent.getLife()
                    + " ice=" + ice + " depthsOnBf=" + countOnBattlefield(player, DEPTHS)
                    + " marit=" + countOnBattlefield(player, MARIT)
                    + " payoffZone=" + zoneOf(player, payoff(control))
                    + " mana=" + player.getManaPool().totalMana();
            if (!state.equals(previous)) System.out.println("BOMB_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 600) throw new AssertionError("native step budget exhausted " + key);
        System.out.println("BOMB_RESULT " + key + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " steps=" + steps + " turns=" + game.getPhaseHandler().getTurn()
                + " stageClones=" + stageClones + " depthsRemovals=" + depthsRemovals
                + " hexmageRemovals=" + hexmageRemovals + " sneakActivations=" + sneakActivations
                + " breachCasts=" + breachCasts + " showTellCasts=" + showTellCasts
                + " reanimations=" + reanimations + " naturalOrders=" + naturalOrders
                + " karakasBounces=" + karakasBounces + " wastelands=" + wastelands
                + " offTurnCheatIns=" + offTurnCheatIns
                + " lowestIce=" + lowestIce + " maxMarit=" + maxMarit
                + " payoffZone=" + zoneOf(player, payoff(control))
                + " emrakulZone=" + zoneOf(player, EMRAKUL)
                + " life=" + player.getLife() + " opponentLife=" + opponent.getLife());
        // v55: one extra line, emitted for the conversion cases ONLY, so that
        // BOMB_RESULT keeps exactly the fields v52 registered and every
        // preserved row stays byte-comparable.
        if (conversion)
            System.out.println("BOMB_CONV " + key + " depthsLandTurn=" + depthsLandTurn
                    + " stageLandTurn=" + stageLandTurn + " maritTurn=" + maritTurn
                    + " breachTurn=" + breachTurn + " showtellTurn=" + showtellTurn
                    + " oppPoison=" + opponent.getPoisonCounters()
                    + " blightsteelZone=" + zoneOf(player, BLIGHTSTEEL)
                    + " griselbrandZone=" + zoneOf(player, GRISELBRAND)
                    + " archonZone=" + zoneOf(player, ARCHON)
                    + " atraxaZone=" + zoneOf(player, ATRAXA)
                    + " ulamogZone=" + zoneOf(player, ULAMOG)
                    + " wurmZone=" + zoneOf(player, WURM)
                    + " breachZone=" + zoneOf(player, BREACH)
                    + " showtellZone=" + zoneOf(player, SHOWTELL));
        // v56: one more line, emitted for the payload cases ONLY, so that both
        // BOMB_RESULT and the v55 BOMB_CONV line keep exactly the fields their
        // own increment registered and every preserved row stays byte-
        // comparable. `oppPermanents` is what makes P7 checkable: it is the
        // opponent's own Show and Tell choice, read from their PUBLIC board.
        if (payload)
            System.out.println("BOMB_PICK " + key
                    + " oppFirstPermanent=[" + oppFirstPermanent + "] oppFirstTurn=" + oppFirstTurn
                    + " oppPermanents=[" + publicPermanents(opponent) + "]"
                    + " oppHandSize=" + opponent.getCardsIn(ZoneType.Hand).size()
                    + " ownPermanents=[" + publicPermanents(player) + "]");
        // v62: one more line, emitted for the value cases ONLY, so BOMB_RESULT,
        // the v55 BOMB_CONV line and the v56 BOMB_PICK line all keep exactly the
        // fields their own increment registered and every preserved row of every
        // other suite stays byte-comparable.
        if (value)
            System.out.println("BOMB_VALUE " + key
                    + " oppPermanentsStart=" + oppPermanentsStart
                    + " oppPermanentsMin=" + oppPermanentsMin
                    + " oppPermanentsEnd=" + opponent.getCardsIn(ZoneType.Battlefield).size()
                    + " oppExileEnd=" + opponent.getCardsIn(ZoneType.Exile).size()
                    + " maxOwnTokens=" + maxOwnTokens
                    + " ownTokensEnd=" + ownTokens(player)
                    + " maxOwnHand=" + maxOwnHand
                    + " ownHandEnd=" + player.getCardsIn(ZoneType.Hand).size()
                    + " ashenZone=" + zoneOf(player, ASHEN)
                    + " reanimateZone=" + zoneOf(player, REANIMATE)
                    + " oppPermanents=[" + publicPermanents(opponent) + "]"
                    + " ownPermanents=[" + publicPermanents(player) + "]");
        // v71: one more line, emitted for the tinker cases ONLY, so BOMB_RESULT
        // and every earlier increment's own line keep exactly the fields they
        // registered and every preserved row of every other suite stays
        // byte-comparable. `sacrificed` is the difference between our own
        // battlefield artifacts before and after - the only place the cost's
        // choice is visible.
        if (tinker) {
            List<String> remaining = ownArtifacts(player), sacrificed = new ArrayList<>(artifactsStart);
            for (String name : remaining) sacrificed.remove(name);
            System.out.println("BOMB_TINKER " + key
                    + " tinkerCasts=" + tinkerCasts + " tinkerTurn=" + tinkerTurn
                    + " artifactsStart=[" + String.join(";", artifactsStart) + "]"
                    + " sacrificed=[" + String.join(";", sacrificed) + "]"
                    + " portalZone=" + zoneOf(player, PORTAL)
                    + " blightsteelZone=" + zoneOf(player, BLIGHTSTEEL)
                    + " juggernautZone=" + zoneOf(player, JUGGERNAUT)
                    + " topZone=" + zoneOf(player, TOP)
                    + " tinkerZone=" + zoneOf(player, TINKER)
                    + " libraryArtifacts=" + libraryArtifacts(player)
                    + " oppCreaturesStart=" + oppCreaturesStart
                    + " oppCreaturesEnd=" + creatures(opponent)
                    + " ownPermanents=[" + publicPermanents(player) + "]"
                    + " oppPermanents=[" + publicPermanents(opponent) + "]"
                    + " ownGraveyard=[" + ownGraveyard(player) + "]");
        }
    }

    private static forge.ai.LobbyPlayerAi defaultAi(int seat) {
        var lobby = new forge.ai.LobbyPlayerAi("Default-" + seat, null);
        lobby.setAiProfile("Default");
        return lobby;
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "bomb-lines-native-diagnostic-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (String name : List.of(DEPTHS, STAGE, HEXMAGE, SNEAK, BREACH, SHOWTELL, EMRAKUL, GRISELBRAND,
                    HOOF, ORDER, REANIMATE, ANIMATE, ELVES, "Containment Priest", "Karakas", "Wasteland",
                    "Rest in Peace", "Torpor Orb", "Ensnaring Bridge", BEARS,
                    BLIGHTSTEEL, ARCHON, ATRAXA, WURM, ULAMOG, FOUNDRY, BEAST, SPIDER,
                    "Forest", "Island", "Mountain", "Swamp", "Plains",
                    // v62, APPENDED so no previously loaded name changes place.
                    ASHEN,
                    // v71, APPENDED for the same reason.
                    TINKER, PORTAL, JUGGERNAUT, ORNITHOPTER, PETAL, TOP))
                StaticData.instance().attemptToLoadCard(name);
            List<String> cases = args.length > 2 ? switch (args[2]) {
                case "lines" -> LINES;
                case "controls" -> CONTROLS;
                case "depths" -> List.of("depths-stage", "depths-hexmage", "depths-hexmage-bf");
                case "cheat-in" -> List.of("sneak-emrakul", "breach-emrakul", "showtell-emrakul");
                case "reanimator" -> List.of("reanimate-griselbrand", "animate-emrakul");
                case "order" -> List.of("natural-order-hoof");
                case "bombs" -> BOMBS;
                case "guards" -> GUARDS;
                case "conversion" -> CONVERSION;
                case "payload" -> PAYLOAD;
                case "value" -> VALUE;
                case "tinker" -> TINKER_CASES;
                default -> { var all = new ArrayList<>(LINES); all.addAll(CONTROLS); yield all; }
            } : LINES;
            boolean payload = args.length > 2 && args[2].equals("payload");
            boolean value = args.length > 2 && args[2].equals("value");
            boolean tinkerSuite = args.length > 2 && args[2].equals("tinker");
            // BOMB_CONV carries the turn of each gated action, which every
            // payload case and every value case needs too, so the v55 line is
            // emitted for all three suites; BOMB_PICK is the payload suite's own
            // and BOMB_VALUE is the value suite's own.
            boolean conversion = payload || value || args.length > 2 && args[2].equals("conversion");
            for (int seat = 0; seat < 2; seat++) for (PhaseType phase : List.of(PhaseType.MAIN1, PhaseType.MAIN2))
                for (String control : cases) run(args[1].equals("improved"), seat, phase, control, conversion, payload, value, tinkerSuite);
            System.out.println("BOMB_SUITE_COMPLETE cases=" + (4 * cases.size()));
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
