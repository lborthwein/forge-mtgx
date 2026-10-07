package forge.bench.rl;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Constants of the observation schema {@code mtgx-rl-obs/2} (lane rl-obs-v2-1006; ICR obs-v2-1006-schema, approved by
 * the orchestrator 10-06; implementation note N1). The lists come from the generated {@link RlVocabV2}; the schema
 * JSON is vendored as the test resource {@code rl/obs-v2.json} and RlWireSchemaTest asserts the sha and every list.
 * v2 is a superset of v1: zones 0-21, attrs 0-19, ctx 0-31, scal, kinds, modes and families are v1's.
 */
public final class RlSchemaV2 {
    private RlSchemaV2() {
    }

    public static final String SCHEMA = "mtgx-rl-obs/2";
    public static final String PROTO = "mtgx-rl-wire/2";
    public static final String SCHEMA_SHA = RlVocabV2.SCHEMA_SHA;

    public static final int L_MAX = 128;
    public static final int R_MAX = 256;
    public static final int F_MAX = 512;

    // ---- zones: v1's 0-21, then o_seen; the critic-only zones are renumbered to stay the tail
    public static final int Z_O_SEEN = 22, Z_PRIV_O_HAND = 23, Z_PRIV_U_LIB = 24, Z_PRIV_O_LIB = 25;
    public static final List<String> ZONES = Collections.unmodifiableList(Arrays.asList(RlVocabV2.ZONES));

    // ---- token attributes: v1's 20, then these
    public static final int A_ZONE_AGE = 20, A_KNOWN_TO_OPP = 21;
    public static final int N_ATTR = 22;
    public static final List<String> ATTRS = Collections.unmodifiableList(Arrays.asList(RlVocabV2.ATTRS));

    // ---- context: v1's 32, then these (normalisations in the names)
    public static final int N_CTX = 69;
    public static final int C_LANDS_PLAYED_OPP = 32, C_ENERGY_U = 33, C_ENERGY_O = 34, C_EXPERIENCE_U = 35,
            C_EXPERIENCE_O = 36, C_RAD_U = 37, C_RAD_O = 38, C_TICKETS_U = 39, C_TICKETS_O = 40, C_RING_U = 41,
            C_RING_O = 42, C_BLESSING_U = 43, C_BLESSING_O = 44, C_DUNGEONS_U = 45, C_DUNGEONS_O = 46, C_DAY = 47,
            C_NIGHT = 48, C_LIFE_GAINED_U = 49, C_LIFE_GAINED_O = 50, C_LIFE_LOST_U = 51, C_LIFE_LOST_O = 52,
            C_COMBAT_DMG_U = 53, C_COMBAT_DMG_O = 54, C_NONCOMBAT_DMG_U = 55, C_NONCOMBAT_DMG_O = 56, C_DRAWN_U = 57,
            C_DRAWN_O = 58, C_DISCARDED_U = 59, C_DISCARDED_O = 60, C_LIFE_LOST_PREV_U = 61, C_LIFE_LOST_PREV_O = 62,
            C_DRAWN_PREV_U = 63, C_DRAWN_PREV_O = 64, C_SPELLS_PREV_U = 65, C_SPELLS_PREV_O = 66,
            C_LANDS_PREV_U = 67, C_LANDS_PREV_O = 68;
    public static final List<String> CTX = Collections.unmodifiableList(Arrays.asList(RlVocabV2.CTX));

    // ---- tok_bits (u64): current characteristics of a visible token
    public static final int B_ARTIFACT = 0, B_BATTLE = 1, B_CREATURE = 2, B_ENCHANTMENT = 3, B_KINDRED = 4,
            B_LAND = 5, B_PLANESWALKER = 6, B_INSTANT = 7, B_SORCERY = 8, B_LEGENDARY = 9, B_BASIC = 10, B_SNOW = 11,
            B_W = 12, B_U = 13, B_B = 14, B_R = 15, B_G = 16, B_PLAINS = 17, B_ISLAND = 18, B_SWAMP = 19,
            B_MOUNTAIN = 20, B_FOREST = 21, B_BACK_FACE = 22, B_FLIPPED = 23, B_COPY = 24, B_PHASED_OUT = 25,
            B_NO_ABILITIES = 26, B_GRANTED_ABILITY = 27, B_TEXT_CHANGED = 28, B_MONSTROUS = 29, B_RENOWNED = 30,
            B_SUSPECTED = 31, B_GOADED = 32, B_RING_BEARER = 33;
    public static final List<String> BITS = Collections.unmodifiableList(Arrays.asList(RlVocabV2.BITS));

    // ---- relation types
    public static final int R_TARGET = 1, R_ATTACKS = 2, R_BLOCKS = 3, R_ATTACHED = 4, R_LINKED = 5;
    public static final int LINK_EXILED_WITH = 0, LINK_IMPRINTED = 1, LINK_ENCODED = 2, LINK_PAIRED = 3,
            LINK_HAUNTING = 4;
    public static final List<String> REL_TYPES = Collections.unmodifiableList(Arrays.asList(RlVocabV2.REL_TYPES));

    // ---- facts and subtypes
    public static final List<String> FACTS = Collections.unmodifiableList(Arrays.asList(RlVocabV2.FACTS));
    public static final List<String> SUBTYPES = Collections.unmodifiableList(Arrays.asList(RlVocabV2.SUBTYPES));
    private static final Map<String, Integer> FACT_ID = index(RlVocabV2.FACTS);
    private static final Map<String, Integer> SUBTYPE_ID = index(RlVocabV2.SUBTYPES);

    private static Map<String, Integer> index(final String[] xs) {
        final Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < xs.length; i++) {
            m.putIfAbsent(xs[i], i);
        }
        return Collections.unmodifiableMap(m);
    }

    /** The id of a fact name, or -1 when the vocabulary has no such fact. */
    public static int factId(final String name) {
        final Integer i = FACT_ID.get(name);
        return i == null ? -1 : i;
    }

    /** The id of a fact name that must exist (a fixed vocabulary entry). */
    static int fixed(final String name) {
        final int i = factId(name);
        if (i < 0) {
            throw new IllegalStateException("obs-v2 vocabulary has no fact " + name);
        }
        return i;
    }

    public static final int F_COPY_OF = fixed("COPY_OF"), F_CHOSEN_TYPE = fixed("CHOSEN_TYPE"),
            F_NAMED_CARD = fixed("NAMED_CARD"), F_CHOSEN_NUMBER = fixed("CHOSEN_NUMBER"),
            F_CHOSEN_PLAYER = fixed("CHOSEN_PLAYER"), F_CLASS_LEVEL = fixed("CLASS_LEVEL"), F_X = fixed("X"),
            F_MODE0 = fixed("MODE:0"), F_KICKED = fixed("KICKED"), F_ABILITY = fixed("ABILITY"),
            F_TRIGGERED = fixed("TRIGGERED"), F_PILE0 = fixed("PILE:0"), F_DESIG_OTHER = fixed("DESIG:OTHER"),
            F_KW_OTHER = fixed("KW:OTHER"), F_COUNTER_OTHER = fixed("COUNTER:OTHER"),
            F_CHOSEN_COLOR_W = fixed("CHOSEN_COLOR:W");
    public static final int N_MODE_FACTS = 8;

    /** The index of a subtype (Cavern of Souls' chosen type), 0 = unknown. */
    public static int subtypeId(final String t) {
        final Integer i = t == null ? null : SUBTYPE_ID.get(t);
        return i == null ? 0 : i;
    }

    public static String schemaSha() {
        return SCHEMA_SHA;
    }
}
