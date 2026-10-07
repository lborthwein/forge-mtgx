package forge.bench.rl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Observation v2 (lane rl-obs-v2-1006): the vendored schema {@code rl/obs-v2.json} against the generated vocabulary and
 * the constants, the v1 prefix, Forge's enums against the frozen vocabularies, and the wire/2 DECIDE codec. No Forge
 * boot.
 */
public class RlObsV2SchemaTest {

    static JsonObject schema() throws Exception {
        return JsonParser.parseString(new String(Files.readAllBytes(RlWireSchemaTest.res().resolve("obs-v2.json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    public void schemaShaAndListsMatchTheVendoredJson() throws Exception {
        final byte[] b = Files.readAllBytes(RlWireSchemaTest.res().resolve("obs-v2.json"));
        Assert.assertEquals(CardIndex.sha256(b), RlSchemaV2.schemaSha());
        final JsonObject s = schema();
        Assert.assertEquals(s.get("schema").getAsString(), RlSchemaV2.SCHEMA);
        Assert.assertEquals(s.get("wire").getAsString(), RlSchemaV2.PROTO);
        Assert.assertEquals(s.get("L_MAX").getAsInt(), RlSchemaV2.L_MAX);
        Assert.assertEquals(s.get("R_MAX").getAsInt(), RlSchemaV2.R_MAX);
        Assert.assertEquals(s.get("F_MAX").getAsInt(), RlSchemaV2.F_MAX);
        final List<String> zones = new ArrayList<>();
        final JsonArray za = s.getAsJsonArray("zones");
        for (int i = 0; i < za.size(); i++) {
            Assert.assertEquals(za.get(i).getAsJsonObject().get("id").getAsInt(), i);
            zones.add(za.get(i).getAsJsonObject().get("name").getAsString());
        }
        Assert.assertEquals(zones, RlSchemaV2.ZONES);
        Assert.assertEquals(zones.get(RlSchemaV2.Z_O_SEEN), "o_seen");
        Assert.assertEquals(zones.get(RlSchemaV2.Z_PRIV_O_HAND), "priv_o_hand");
        Assert.assertEquals(zones.get(RlSchemaV2.Z_PRIV_O_LIB), "priv_o_lib");
        Assert.assertEquals(RlWireSchemaTest.strings(s.getAsJsonArray("attrs")), RlSchemaV2.ATTRS);
        Assert.assertEquals(RlWireSchemaTest.strings(s.getAsJsonArray("ctx")), RlSchemaV2.CTX);
        Assert.assertEquals(RlWireSchemaTest.strings(s.getAsJsonArray("bits")), RlSchemaV2.BITS);
        Assert.assertEquals(RlWireSchemaTest.strings(s.getAsJsonArray("rel_types")), RlSchemaV2.REL_TYPES);
        Assert.assertEquals(RlWireSchemaTest.strings(s.getAsJsonArray("facts")), RlSchemaV2.FACTS);
        Assert.assertEquals(RlWireSchemaTest.strings(s.getAsJsonArray("subtypes")), RlSchemaV2.SUBTYPES);
        Assert.assertEquals(RlSchemaV2.ATTRS.size(), RlSchemaV2.N_ATTR);
        Assert.assertEquals(RlSchemaV2.CTX.size(), RlSchemaV2.N_CTX);
        Assert.assertEquals(RlSchemaV2.BITS.size(), 64);
        // named indices
        Assert.assertEquals(RlSchemaV2.ATTRS.get(RlSchemaV2.A_ZONE_AGE), "zone_age/10");
        Assert.assertEquals(RlSchemaV2.ATTRS.get(RlSchemaV2.A_KNOWN_TO_OPP), "known_to_opp");
        Assert.assertEquals(RlSchemaV2.CTX.get(RlSchemaV2.C_LANDS_PLAYED_OPP), "lands_played_opp/2");
        Assert.assertEquals(RlSchemaV2.CTX.get(RlSchemaV2.C_ENERGY_U), "energy_user/10");
        Assert.assertEquals(RlSchemaV2.CTX.get(RlSchemaV2.C_RING_U), "ring_level_user/4");
        Assert.assertEquals(RlSchemaV2.CTX.get(RlSchemaV2.C_DAY), "day");
        Assert.assertEquals(RlSchemaV2.CTX.get(RlSchemaV2.C_LIFE_GAINED_U), "life_gained_user/10");
        Assert.assertEquals(RlSchemaV2.CTX.get(RlSchemaV2.C_DISCARDED_O), "discarded_opp/5");
        Assert.assertEquals(RlSchemaV2.CTX.get(RlSchemaV2.C_LANDS_PREV_O), "lands_played_prev_opp/2");
        Assert.assertEquals(RlSchemaV2.BITS.get(RlSchemaV2.B_ARTIFACT), "type_artifact");
        Assert.assertEquals(RlSchemaV2.BITS.get(RlSchemaV2.B_FOREST), "land_forest");
        Assert.assertEquals(RlSchemaV2.BITS.get(RlSchemaV2.B_RING_BEARER), "ring_bearer");
        Assert.assertEquals(RlSchemaV2.REL_TYPES.get(RlSchemaV2.R_TARGET), "TARGET");
        Assert.assertEquals(RlSchemaV2.REL_TYPES.get(RlSchemaV2.R_LINKED), "LINKED");
        Assert.assertEquals(RlSchemaV2.FACTS.get(RlSchemaV2.F_MODE0 + 7), "MODE:7");
        Assert.assertEquals(RlSchemaV2.FACTS.get(RlSchemaV2.F_PILE0 + 1), "PILE:1");
        Assert.assertEquals(RlSchemaV2.FACTS.get(RlSchemaV2.F_CHOSEN_COLOR_W + 4), "CHOSEN_COLOR:G");
        Assert.assertEquals(s.getAsJsonObject("fact_arg").get("CHOSEN_TYPE").getAsString(), "subtype");
    }

    /** v2 extends v1: zones 0-21, attrs 0-19, ctx 0-31, scal, kinds, modes and families are v1's. */
    @Test
    public void v1IsThePrefix() throws Exception {
        final JsonObject v1 = JsonParser.parseString(new String(Files.readAllBytes(RlWireSchemaTest.res()
                .resolve("obs-v1.json")), StandardCharsets.UTF_8)).getAsJsonObject();
        final JsonObject v2 = schema();
        Assert.assertEquals(RlSchemaV2.ZONES.subList(0, 22), RlSchema.ZONES.subList(0, 22));
        Assert.assertEquals(RlSchemaV2.ATTRS.subList(0, RlSchema.N_ATTR), RlSchema.ATTRS);
        Assert.assertEquals(RlSchemaV2.CTX.subList(0, RlSchema.N_CTX), RlSchema.CTX);
        for (String k : new String[] {"scal", "kinds", "modes", "families"}) {
            Assert.assertEquals(v2.get(k), v1.get(k), k);
        }
        for (String k : new String[] {"D_MAX", "C_MAX", "S_MAX", "P_MAX", "X_MAX"}) {
            Assert.assertEquals(v2.get(k).getAsInt(), v1.get(k).getAsInt(), k);
        }
        // the zones' embedding initialisation, and the critic-only zones are the tail
        final JsonArray za = v2.getAsJsonArray("zones");
        for (int i = 0; i < 22; i++) {
            Assert.assertEquals(za.get(i), v1.getAsJsonArray("zones").get(i));
        }
        Assert.assertEquals(za.get(22).getAsJsonObject().get("init_from").getAsInt(), 16);
        for (int i = 23; i < 26; i++) {
            Assert.assertTrue(za.get(i).getAsJsonObject().get("critic_only").getAsBoolean());
        }
    }

    /** The frozen vocabularies hold every value of the Forge enums this jar was built from. */
    @Test
    public void forgeEnumsAreInTheVocabulary() {
        for (forge.game.keyword.Keyword k : forge.game.keyword.Keyword.values()) {
            if (k != forge.game.keyword.Keyword.UNDEFINED) {
                Assert.assertTrue(RlSchemaV2.factId("KW:" + k.name()) > 0, "KW:" + k.name());
            }
        }
        for (forge.game.card.CounterEnumType c : forge.game.card.CounterEnumType.values()) {
            Assert.assertTrue(RlSchemaV2.factId("COUNTER:" + c.name()) > 0, "COUNTER:" + c.name());
        }
        for (forge.game.spellability.AlternativeCost a : forge.game.spellability.AlternativeCost.values()) {
            Assert.assertTrue(RlSchemaV2.factId("ALT:" + a.name()) > 0, "ALT:" + a.name());
        }
        for (forge.game.spellability.OptionalCost o : forge.game.spellability.OptionalCost.values()) {
            Assert.assertTrue(RlSchemaV2.factId("OPT:" + o.name()) > 0, "OPT:" + o.name());
        }
        for (String n : new String[] {"DESIG:The Monarch", "DESIG:The Initiative", "DESIG:The Ring", "DESIG:Undercity",
            "ROOM:Undercity/Forge", "ROOM:Lost Mine of Phandelver/Cave Entrance", "CAST_FROM:Graveyard"}) {
            Assert.assertTrue(RlSchemaV2.factId(n) > 0, n);
        }
        Assert.assertEquals(RlSchemaV2.subtypeId("Goblin") > 0, true);
        Assert.assertEquals(RlSchemaV2.subtypeId("Creature"), 3);
        Assert.assertEquals(RlSchemaV2.subtypeId("no such type"), 0);
    }

    /** A v2 frame: the v1 sample at v2 widths, plus every v2 array. */
    static RlWire.Decide sampleV2(final Random r, final boolean priv, final int nTeacher) {
        final RlWire.Decide v = RlWireSchemaTest.sampleDecide(r, priv, nTeacher);
        final RlWire.Decide d = RlWire.Decide.v2();
        d.gameUid = v.gameUid;
        d.decIdx = v.decIdx;
        d.seat = v.seat;
        d.family = v.family;
        d.mode = v.mode;
        d.flags = v.flags | (r.nextBoolean() ? RlWire.F_DROPPED_REFS : 0);
        d.minPick = v.minPick;
        d.maxPick = v.maxPick;
        d.turn = v.turn;
        d.L = Math.min(v.L + r.nextInt(30), RlSchemaV2.L_MAX);
        d.D = v.D;
        d.C = v.C;
        d.S = v.S;
        d.P = v.P;
        d.tokCard = new int[d.L];
        d.tokZone = new byte[d.L];
        d.tokAttr = new float[d.L * RlSchemaV2.N_ATTR];
        d.tokBits = new long[d.L];
        for (int i = 0; i < d.L; i++) {
            d.tokCard[i] = 1 + r.nextInt(36588);
            d.tokZone[i] = (byte) (1 + r.nextInt(22));
            d.tokBits[i] = r.nextLong();
        }
        for (int i = 0; i < d.tokAttr.length; i++) d.tokAttr[i] = r.nextFloat();
        d.deckCard = v.deckCard;
        d.deckCnt = v.deckCnt;
        d.scal = v.scal;
        for (int i = 0; i < RlSchemaV2.N_CTX; i++) d.ctx[i] = r.nextFloat();
        d.candKind = v.candKind;
        d.candTok = v.candTok;
        d.candCard = v.candCard;
        d.candTgt = v.candTgt;
        d.candSlot = v.candSlot;
        d.candNum = v.candNum;
        d.candAbility = v.candAbility;
        d.candFlags = v.candFlags;
        d.slotTok = v.slotTok;
        d.privCard = v.privCard;
        d.privZone = v.privZone.clone();
        for (int i = 0; i < d.privZone.length; i++) {
            d.privZone[i] = (byte) (d.privZone[i] + 1); // v2's critic zones are 23-25
        }
        d.privCnt = v.privCnt;
        d.teacher = v.teacher;
        d.R = r.nextInt(40);
        d.F = r.nextInt(80);
        d.Dr = r.nextInt(49);
        d.relSrc = new short[d.R];
        d.relDst = new short[d.R];
        d.relType = new byte[d.R];
        d.relArg = new byte[d.R];
        d.relNum = new short[d.R];
        for (int i = 0; i < d.R; i++) {
            d.relSrc[i] = (short) r.nextInt(d.L);
            d.relDst[i] = (short) (r.nextInt(d.L + 2) - 2);
            d.relType[i] = (byte) (1 + r.nextInt(5));
            d.relArg[i] = (byte) r.nextInt(8);
            d.relNum[i] = (short) (r.nextInt(10) - 1);
        }
        d.factTok = new short[d.F];
        d.factId = new short[d.F];
        d.factArg = new int[d.F];
        d.factNum = new short[d.F];
        for (int i = 0; i < d.F; i++) {
            d.factTok[i] = (short) r.nextInt(d.L);
            d.factId[i] = (short) (1 + r.nextInt(RlSchemaV2.FACTS.size() - 1));
            d.factArg[i] = r.nextInt(36589);
            d.factNum[i] = (short) (r.nextInt(30) - 1);
        }
        d.restCard = new int[d.Dr];
        d.restCnt = new byte[d.Dr];
        for (int i = 0; i < d.Dr; i++) {
            d.restCard[i] = 2 + 3 * i;
            d.restCnt[i] = (byte) (1 + r.nextInt(3));
        }
        return d;
    }

    static void assertSameV2(final RlWire.Decide a, final RlWire.Decide b) {
        Assert.assertEquals(b.version, 2);
        Assert.assertEquals(b.R, a.R);
        Assert.assertEquals(b.F, a.F);
        Assert.assertEquals(b.Dr, a.Dr);
        Assert.assertEquals(b.tokAttr, Arrays.copyOf(a.tokAttr, a.L * RlSchemaV2.N_ATTR));
        Assert.assertEquals(b.ctx, a.ctx);
        Assert.assertEquals(b.tokBits, a.tokBits);
        Assert.assertEquals(b.relSrc, a.relSrc);
        Assert.assertEquals(b.relDst, a.relDst);
        Assert.assertEquals(b.relType, a.relType);
        Assert.assertEquals(b.relArg, a.relArg);
        Assert.assertEquals(b.relNum, a.relNum);
        Assert.assertEquals(b.factTok, a.factTok);
        Assert.assertEquals(b.factId, a.factId);
        Assert.assertEquals(b.factArg, a.factArg);
        Assert.assertEquals(b.factNum, a.factNum);
        Assert.assertEquals(b.restCard, a.restCard);
        Assert.assertEquals(b.restCnt, a.restCnt);
        Assert.assertEquals(b.tokCard, a.tokCard);
        Assert.assertEquals(b.tokZone, a.tokZone);
        Assert.assertEquals(b.candKind, a.candKind);
        Assert.assertEquals(b.slotTok, a.slotTok);
        Assert.assertEquals(b.privCard, a.privCard);
        Assert.assertEquals(b.privZone, a.privZone);
        Assert.assertEquals(b.teacher, a.teacher);
        Assert.assertEquals(b.flags, a.flags);
    }

    @Test
    public void decideV2RoundTripAndHeader() throws Exception {
        final Random r = new Random(1006);
        for (int k = 0; k < 50; k++) {
            final RlWire.Decide d = sampleV2(r, k % 2 == 0, k % 3 == 0 ? 3 : 0);
            final byte[] p = RlWire.encodeDecide(d);
            Assert.assertEquals(p.length, RlWire.decideSize(d));
            assertSameV2(d, RlWire.decodeDecide(p, 2));
            // a v2 payload is not a v1 one
            Assert.assertThrows(IllegalArgumentException.class, () -> RlWire.decodeDecide(p, 1));
        }
        final RlWire.Decide d = sampleV2(r, true, 2);
        d.R = 5;
        d.F = 7;
        d.Dr = 3;
        d.relSrc = Arrays.copyOf(d.relSrc, 5);
        d.relDst = Arrays.copyOf(d.relDst, 5);
        d.relType = Arrays.copyOf(d.relType, 5);
        d.relArg = Arrays.copyOf(d.relArg, 5);
        d.relNum = Arrays.copyOf(d.relNum, 5);
        d.factTok = Arrays.copyOf(d.factTok, 7);
        d.factId = Arrays.copyOf(d.factId, 7);
        d.factArg = Arrays.copyOf(d.factArg, 7);
        d.factNum = Arrays.copyOf(d.factNum, 7);
        d.restCard = new int[] {5, 9, 11};
        d.restCnt = new byte[] {1, 2, 1};
        final byte[] p = RlWire.encodeDecide(d);
        final java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(p).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        Assert.assertEquals(b.getShort(16), (short) d.L);
        Assert.assertEquals(b.getInt(32), 2);
        Assert.assertEquals(b.getShort(36), (short) 5);
        Assert.assertEquals(b.getShort(38), (short) 7);
        Assert.assertEquals(b.getShort(40), (short) 3);
        for (int i = 42; i < 64; i++) Assert.assertEquals(b.get(i), 0);
        // tok_bits right after slot_tok
        final int slotEnd = 64 + d.L * (4 + 1 + RlSchemaV2.N_ATTR * 4) + d.D * 5 + (RlSchema.N_SCAL + RlSchemaV2.N_CTX) * 4
                + d.C * 17 + d.S * 2;
        Assert.assertEquals(b.getLong(slotEnd), d.tokBits[0]);
        final byte[] reserved = p.clone();
        reserved[50] = 1;
        Assert.assertThrows(IllegalArgumentException.class, () -> RlWire.decodeDecide(reserved, 2));
    }

    /** The v1 codec is untouched: a v1 frame's bytes are what they were (the golden frames test covers the bytes). */
    @Test
    public void v1FramesStayV1() {
        final RlWire.Decide d = RlWireSchemaTest.sampleDecide(new Random(7), true, 0);
        Assert.assertEquals(d.version, 1);
        final byte[] p = RlWire.encodeDecide(d);
        Assert.assertEquals(RlWire.decodeDecide(p).version, 1);
        Assert.assertEquals(RlWire.decodeDecide(p, 1).ctx.length, RlSchema.N_CTX);
        for (int i = 36; i < 64; i++) Assert.assertEquals(p[i], 0);
    }

    static List<String> strings(final JsonArray a) {
        final List<String> out = new ArrayList<>();
        for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }
}
