package forge.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;

import forge.bench.rl.RlSchemaV2;
import forge.bench.rl.RlWire;

/**
 * Observation v2 golden frames for the Python codec (lane rl-obs-v2-1006): {@code decide-v2-<k>.bin} (the whole frame,
 * length prefix included) and {@code .json} with Java's header fields and per-array sums, which
 * tools/ml/rl/tests/test_obs_v2.py checks after decoding the bytes itself.
 */
final class RlV2Golden {
    private RlV2Golden() {
    }

    static void write(final Path dir, final int k, final byte[] frame, final RlWire.Decide d) {
        try {
            Files.createDirectories(dir);
            final String base = String.format("decide-v2-%02d", k);
            Files.write(dir.resolve(base + ".bin"), frame);
            final JsonObject h = new JsonObject();
            h.addProperty("dec_idx", d.decIdx);
            h.addProperty("seat", d.seat);
            h.addProperty("family", d.family);
            h.addProperty("mode", d.mode);
            h.addProperty("flags", d.flags);
            h.addProperty("L", d.L);
            h.addProperty("D", d.D);
            h.addProperty("C", d.C);
            h.addProperty("S", d.S);
            h.addProperty("P", d.hasPriv() ? d.P : 0);
            h.addProperty("R", d.R);
            h.addProperty("F", d.F);
            h.addProperty("Dr", d.Dr);
            final JsonObject s = new JsonObject();
            s.addProperty("tok_card", sum(d.tokCard, d.L));
            double ta = 0;
            for (int i = 0; i < d.L * RlSchemaV2.N_ATTR; i++) ta += d.tokAttr[i];
            s.addProperty("tok_attr", ta);
            double cx = 0;
            for (float f : d.ctx) cx += f;
            s.addProperty("ctx", cx);
            double tb = 0;
            for (int i = 0; i < d.L; i++) tb += d.tokBits[i];
            s.addProperty("tok_bits", tb);
            s.addProperty("rel_src", sum(d.relSrc, d.R));
            s.addProperty("rel_dst", sum(d.relDst, d.R));
            s.addProperty("rel_type", sumU(d.relType, d.R));
            s.addProperty("rel_num", sum(d.relNum, d.R));
            s.addProperty("fact_tok", sum(d.factTok, d.F));
            double fi = 0;
            for (int i = 0; i < d.F; i++) fi += d.factId[i] & 0xffff;
            s.addProperty("fact_id", fi);
            s.addProperty("fact_arg", sum(d.factArg, d.F));
            s.addProperty("fact_num", sum(d.factNum, d.F));
            s.addProperty("rest_card", sum(d.restCard, d.Dr));
            s.addProperty("rest_cnt", sumU(d.restCnt, d.Dr));
            s.addProperty("cand_kind", sumU(d.candKind, d.C));
            final JsonObject o = new JsonObject();
            o.add("header", h);
            o.add("sums", s);
            Files.write(dir.resolve(base + ".json"), RlWire.canonical(o));
        } catch (IOException e) {
            System.err.println("[rlfake] golden dump failed: " + e);
        }
    }

    private static double sum(final int[] a, final int n) {
        double t = 0;
        for (int i = 0; i < n; i++) t += a[i];
        return t;
    }

    private static double sum(final short[] a, final int n) {
        double t = 0;
        for (int i = 0; i < n; i++) t += a[i];
        return t;
    }

    private static double sumU(final byte[] a, final int n) {
        double t = 0;
        for (int i = 0; i < n; i++) t += a[i] & 0xff;
        return t;
    }
}
