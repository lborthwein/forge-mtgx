package forge.bench.rl;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Constants mirroring the observation schema {@code mtgx-rl-obs/1} ({@code schema/obs-v1.json}, interfaces.md
 * Appendix A; lane rl-r0-b1-1005). The schema JSON itself is vendored byte for byte as the test resource
 * {@code forge-ai/src/test/resources/rl/obs-v1.json}; a test asserts that its sha256 is {@link #schemaSha()} and that
 * every constant here equals the JSON. Nothing in this package runs unless {@code RlActorBench} runs.
 */
public final class RlSchema {
    private RlSchema() {
    }

    public static final String SCHEMA = "mtgx-rl-obs/1";
    /** sha256 of the vendored obs-v1.json bytes (Appendix A plus a trailing newline). */
    public static final String SCHEMA_SHA = "24eee76e8ac4b1a275f66517f3cd21b3ff86703f133901a8828d216c800766ab";

    public static String schemaSha() {
        return SCHEMA_SHA;
    }

    public static final int L_MAX = 96;
    public static final int D_MAX = 48;
    public static final int C_MAX = 128;
    public static final int S_MAX = 32;
    public static final int P_MAX = 128;
    public static final int X_MAX = 15;

    // ---- zones (ids 0-11 = pol-2M-n1 data.ZONES)
    public static final int Z_PAD = 0, Z_U_HAND = 1, Z_U_LAND = 2, Z_U_CRE = 3, Z_U_OTH = 4, Z_O_LAND = 5, Z_O_CRE = 6,
            Z_O_OTH = 7, Z_U_GONE = 8, Z_O_GONE = 9, Z_U_DECK = 10, Z_MULL_HAND = 11, Z_U_EXILE = 12, Z_O_EXILE = 13,
            Z_U_STACK = 14, Z_O_STACK = 15, Z_O_HAND_KNOWN = 16, Z_U_LIB_KNOWN = 17, Z_O_LIB_KNOWN = 18,
            Z_COMMAND = 19, Z_U_EVENT = 20, Z_O_EVENT = 21, Z_PRIV_O_HAND = 22, Z_PRIV_U_LIB = 23,
            Z_PRIV_O_LIB = 24;
    public static final List<String> ZONES = Collections.unmodifiableList(Arrays.asList("pad", "u_hand", "u_land",
            "u_cre", "u_oth", "o_land", "o_cre", "o_oth", "u_gone", "o_gone", "u_deck", "mull_hand", "u_exile",
            "o_exile", "u_stack", "o_stack", "o_hand_known", "u_lib_known", "o_lib_known", "command", "u_event",
            "o_event", "priv_o_hand", "priv_u_lib", "priv_o_lib"));

    // ---- token attributes (N_ATTR = 20)
    public static final int A_TAPPED = 0, A_SICK = 1, A_IS_TOKEN = 2, A_FACE_DOWN = 3, A_ATTACKING = 4,
            A_BLOCKING = 5, A_POWER = 6, A_TOUGHNESS = 7, A_DAMAGE = 8, A_PLUS1 = 9, A_MINUS1 = 10, A_LOYALTY = 11,
            A_OTHER_COUNTERS = 12, A_ATTACHED = 13, A_ENTERED_THIS_TURN = 14, A_IS_ABILITY = 15, A_STACK_POS = 16,
            A_LIB_POS = 17, A_EVENT_AGE = 18, A_CONTROLLER_DIFFERS_OWNER = 19;
    public static final List<String> ATTRS = Collections.unmodifiableList(Arrays.asList("tapped", "sick", "is_token",
            "face_down", "attacking", "blocking", "power/10", "toughness/10", "damage/10", "plus1_counters/5",
            "minus1_counters/5", "loyalty/10", "other_counters/5", "attached", "entered_this_turn", "is_ability",
            "stack_pos/10", "lib_pos/10", "event_age/16", "controller_differs_owner"));
    public static final int N_ATTR = 20;

    // ---- scalars (N_SCAL = 18, pol-2M-n1 data.SCALARS)
    public static final List<String> SCAL = Collections.unmodifiableList(Arrays.asList("turn", "userActive", "onPlay",
            "lifeUser", "lifeOpp", "handUser", "handOpp", "manaUser", "manaOpp", "mullUser", "mullOpp", "gameNumber",
            "mullK", "fmt0", "fmt1", "fmt2", "fmt3", "fmt4"));
    public static final int N_SCAL = 18;

    // ---- context (N_CTX = 32)
    public static final List<String> CTX = Collections.unmodifiableList(Arrays.asList("step_untap", "step_upkeep",
            "step_draw", "step_main1", "step_begin_combat", "step_attackers", "step_blockers", "step_damage",
            "step_end_combat", "step_main2", "step_end", "step_cleanup", "i_have_priority", "stack_size/5",
            "poison_user/10", "poison_opp/10", "library_user/40", "library_opp/40", "lands_played/2",
            "land_plays_left/2", "pool_W/5", "pool_U/5", "pool_B/5", "pool_R/5", "pool_G/5", "pool_C/5",
            "spells_cast_user/5", "spells_cast_opp/5", "monarch_user", "monarch_opp", "initiative_user",
            "initiative_opp"));
    public static final int N_CTX = 32;
    public static final int C_STEP0 = 0, C_PRIORITY = 12, C_STACK = 13, C_POISON_U = 14, C_POISON_O = 15,
            C_LIB_U = 16, C_LIB_O = 17, C_LANDS_PLAYED = 18, C_LAND_PLAYS_LEFT = 19, C_POOL0 = 20,
            C_SPELLS_U = 26, C_SPELLS_O = 27, C_MONARCH_U = 28, C_MONARCH_O = 29, C_INITIATIVE_U = 30,
            C_INITIATIVE_O = 31;

    // ---- candidate kinds
    public static final int K_PAD = 0, K_PASS = 1, K_CAST = 2, K_ACTIVATE = 3, K_LAND = 4, K_CARD = 5, K_PLAYER = 6,
            K_STACK = 7, K_YES = 8, K_NO = 9, K_KEEP = 10, K_MULLIGAN = 11, K_PLAY_FIRST = 12, K_DRAW_FIRST = 13,
            K_NONE = 14, K_DEFENDER = 15, K_ATTACKER = 16, K_NUMBER = 17, K_MODE = 18;
    public static final List<String> KINDS = Collections.unmodifiableList(Arrays.asList("pad", "PASS", "CAST",
            "ACTIVATE", "LAND", "CARD", "PLAYER", "STACK", "YES", "NO", "KEEP", "MULLIGAN", "PLAY_FIRST", "DRAW_FIRST",
            "NONE", "DEFENDER", "ATTACKER", "NUMBER", "MODE"));

    // ---- decision modes
    public static final int M_PAD = 0, M_SINGLE = 1, M_SUBSET = 2, M_ASSIGN = 3, M_PERMUTE = 4;
    public static final List<String> MODES = Collections.unmodifiableList(Arrays.asList("pad", "SINGLE", "SUBSET",
            "ASSIGN", "PERMUTE"));

    // ---- families (ids from 1; Phase A = 1..7)
    public static final int F_PRIORITY = 1, F_TARGETS = 2, F_ATTACK = 3, F_BLOCK = 4, F_MULLIGAN = 5,
            F_MULLIGAN_BOTTOM = 6, F_START_PLAYER = 7, F_ENTITY = 8, F_CARDS = 9, F_MODE = 10, F_CONFIRM = 11,
            F_NUMBER = 12, F_OPTIONAL_COSTS = 13, F_SCRY = 14, F_ORDER = 15, F_DISCARD_FROM = 16, F_COST_CARDS = 17,
            F_PILE = 18, F_SURVEIL = 19, F_PUT_ON_TOP = 20, F_OPTIONAL_TRIGGER = 21, F_PAY_TO_PREVENT = 22,
            F_NAME = 23, F_COLOR = 24;
    /** Family names by id; index 0 is unused. */
    public static final List<String> FAMILIES = Collections.unmodifiableList(Arrays.asList(null, "PRIORITY",
            "TARGETS", "ATTACK", "BLOCK", "MULLIGAN", "MULLIGAN_BOTTOM", "START_PLAYER", "ENTITY", "CARDS", "MODE",
            "CONFIRM", "NUMBER", "OPTIONAL_COSTS", "SCRY", "ORDER", "DISCARD_FROM", "COST_CARDS", "PILE", "SURVEIL",
            "PUT_ON_TOP", "OPTIONAL_TRIGGER", "PAY_TO_PREVENT", "NAME", "COLOR"));
    public static final int N_FAMILIES = 24;

    public static boolean isPhaseA(final int family) {
        return family >= F_PRIORITY && family <= F_START_PLAYER;
    }

    public static String familyName(final int family) {
        return family > 0 && family <= N_FAMILIES ? FAMILIES.get(family) : "UNKNOWN";
    }

    /**
     * The family of one bridge ask (Appendix A, column "ask"), or 0 for a kind no family names (orderBlockers,
     * assignDamage: Forge-decided mechanics, counted by method).
     */
    public static int familyOf(final String kind, final String method) {
        if (kind == null) {
            return 0;
        }
        switch (kind) {
            case "priority":
                return F_PRIORITY;
            case "targets":
                return F_TARGETS;
            case "attackers":
                return F_ATTACK;
            case "blockers":
                return F_BLOCK;
            case "mulligan":
                return F_MULLIGAN;
            case "startingPlayer":
                return F_START_PLAYER;
            case "cardsChoice":
                return "tuckCardsViaMulligan".equals(method) ? F_MULLIGAN_BOTTOM : F_CARDS;
            case "zoneChange":
                return F_CARDS;
            case "entityChoice":
                return F_ENTITY;
            case "mode":
                return F_MODE;
            case "confirm":
                return F_CONFIRM;
            case "number":
            case "keywordCost":
                return F_NUMBER;
            case "optionalCosts":
                return F_OPTIONAL_COSTS;
            case "scry":
                return F_SCRY;
            case "orderZone":
                return F_ORDER;
            default:
                return 0;
        }
    }
}
