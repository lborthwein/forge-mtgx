package forge.bench.rl;

import forge.card.mana.ManaCostShard;

/**
 * Constants of the observation schema {@code mtgx-rl-obs/3} (lane r4-payment-1015; mtgx
 * {@code tools/ml/rl/schema_v3_gen.py}, vendored as the test resource {@code rl/obs-v3.json}). v3 is v2 plus one
 * decision family and one candidate flag; every v2 list and width is unchanged, the frames keep v2's layout
 * (wire/2), and only the schema sha differs, so a v2 tape, shard or checkpoint stays v2:
 * <ul>
 *   <li>family 25 MANA_SOURCE (SINGLE): which source pays one contested shard of a real payment; candidate 0 is AUTO
 *       (Forge AI pays the rest of this cost), then one candidate per materially different source
 *       ({@link RlManaChoice});</li>
 *   <li>cand_flags bit 4 {@link #FLAG_FORGE_DEFAULT}: the candidate does what Forge AI would do (MANA_SOURCE: AUTO).</li>
 * </ul>
 */
public final class RlSchemaV3 {
    private RlSchemaV3() {
    }

    public static final String SCHEMA = "mtgx-rl-obs/3";
    /** The frames are v2's. */
    public static final String PROTO = RlSchemaV2.PROTO;
    /** sha256 of the vendored obs-v3.json bytes. */
    public static final String SCHEMA_SHA = "c8b10cdf4717c64925150437b4278e8c4143e5058673aaed0a7c92f89dcfe763";

    public static final int F_MANA_SOURCE = 25;
    public static final String MANA_SOURCE = "MANA_SOURCE";
    /** cand_flags bit 4: the candidate does what Forge AI would do (MANA_SOURCE: AUTO, "Forge AI pays the rest"). */
    public static final int FLAG_FORGE_DEFAULT = 16;

    /** cand_num codes of a source candidate: the shard being paid (obs-v3.json {@code mana_shards}). */
    public static final int SH_W = 0, SH_U = 1, SH_B = 2, SH_R = 3, SH_G = 4, SH_C = 5, SH_GENERIC = 6, SH_HYBRID = 7,
            SH_PHYREXIAN = 8, SH_TWO_GENERIC = 9, SH_SNOW = 10, SH_X = 11, SH_OTHER = 12;

    public static String schemaSha() {
        return SCHEMA_SHA;
    }

    /** The schema sha of observation version 1, 2 or 3. */
    public static String shaOf(final int version) {
        switch (version) {
            case 3:
                return SCHEMA_SHA;
            case 2:
                return RlSchemaV2.schemaSha();
            default:
                return RlSchema.schemaSha();
        }
    }

    /** The wire protocol of observation version 1, 2 or 3 (v3's frames are v2's). */
    public static String protoOf(final int version) {
        return version >= 2 ? RlWire.PROTO_V2 : RlWire.PROTO;
    }

    /** The frame layout (1 or 2) of observation version 1, 2 or 3. */
    public static int layoutOf(final int version) {
        return version >= 2 ? 2 : 1;
    }

    /** The observation version (1, 2 or 3) of a schema sha; 1 for an unknown or missing sha (the v1 default). */
    public static int versionOfSha(final String sha) {
        if (SCHEMA_SHA.equals(sha)) {
            return 3;
        }
        if (RlSchemaV2.schemaSha().equals(sha)) {
            return 2;
        }
        return 1;
    }

    /** The cand_num code of a shard. */
    public static int shardCode(final ManaCostShard s) {
        if (s == null) {
            return SH_OTHER;
        }
        if (s == ManaCostShard.GENERIC) {
            return SH_GENERIC;
        }
        if (s == ManaCostShard.X || s == ManaCostShard.COLORED_X) {
            return SH_X;
        }
        if (s.isSnow()) {
            return SH_SNOW;
        }
        if (s.isPhyrexian()) {
            return SH_PHYREXIAN;
        }
        if (s.isOr2Generic()) {
            return SH_TWO_GENERIC;
        }
        if (s == ManaCostShard.COLORLESS) {
            return SH_C;
        }
        if (s.isMonoColor()) {
            if (s.isWhite()) {
                return SH_W;
            }
            if (s.isBlue()) {
                return SH_U;
            }
            if (s.isBlack()) {
                return SH_B;
            }
            if (s.isRed()) {
                return SH_R;
            }
            if (s.isGreen()) {
                return SH_G;
            }
        }
        if (s.isMultiColor()) {
            return SH_HYBRID;
        }
        return SH_OTHER;
    }
}
