package forge.bench;

import java.util.Random;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.google.gson.JsonObject;

import forge.util.MyRandom;

/**
 * Benchmark-only, private RNG evidence. The inherited java.util.Random
 * algorithm and Gaussian cache produce exactly the ordinary seeded stream;
 * this records consumption, never predicts, rewinds or repairs it.
 *
 * Scope: MyRandom in the sequential benchmark JVM. This does not audit other
 * RNG providers, AI memory, thread scheduling, or cross-engine equivalence.
 * Audit data MUST NOT be added to player observations: even a draw cursor can
 * leak hidden chance. Checkpoints go only to the private worker stderr log.
 */
public final class BenchRandomAudit {
    private BenchRandomAudit() { }

    public static void install(final long seed) {
        MyRandom.setRandom(new AuditedRandom(seed));
    }

    public static final class AuditedRandom extends Random {
        private static final long serialVersionUID = 1L;
        private long draws;
        private long stateTouches;
        // No field initializer: Random's constructor calls our setSeed before
        // subclass initializers. Its initial seed must remain in the transcript.
        private MessageDigest transcript;
        private long purityChecks;

        public AuditedRandom(final long seed) { super(seed); }

        @Override
        protected synchronized int next(final int bits) {
            final int value = super.next(bits);
            draws++;
            record((byte) 1, bits, Integer.toUnsignedLong(value));
            return value;
        }

        @Override
        public synchronized void setSeed(final long seed) {
            super.setSeed(seed);
            stateTouches++;
            record((byte) 2, seed, 0);
        }

        @Override
        public synchronized double nextGaussian() {
            // The cached second Gaussian consumes no new raw bits but DOES
            // mutate RNG state, so a raw-bit counter alone misses this case.
            stateTouches++;
            final double value = super.nextGaussian();
            record((byte) 3, Double.doubleToRawLongBits(value), 0);
            return value;
        }

        /** Fixed-width, domain-tagged private transcript; no RNG calls. */
        private void record(final byte kind, final long first, final long second) {
            if (transcript == null) {
                try { transcript = MessageDigest.getInstance("SHA-256"); }
                catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
            }
            transcript.update(kind);
            for (int shift = 56; shift >= 0; shift -= 8) transcript.update((byte) (first >>> shift));
            for (int shift = 56; shift >= 0; shift -= 8) transcript.update((byte) (second >>> shift));
        }

        synchronized JsonObject snapshot() {
            final JsonObject out = new JsonObject();
            out.addProperty("schema", "forge-bench-private-rng/2");
            out.addProperty("provider", "java.util.Random");
            out.addProperty("draws", draws);
            out.addProperty("stateTouches", stateTouches);
            out.addProperty("digestAlgorithm", "sha256-seed-bits-gaussian-v1");
            try {
                out.addProperty("digest", HexFormat.of().formatHex(((MessageDigest) transcript.clone()).digest()));
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException("Read-only SHA-256 snapshot unavailable", e);
            }
            out.addProperty("purityChecks", purityChecks);
            return out;
        }
    }

    public static final class Token {
        private final AuditedRandom source;
        private final long draws;
        private final long stateTouches;

        private Token(final AuditedRandom source) {
            this.source = source;
            synchronized (source) {
                this.draws = source.draws;
                this.stateTouches = source.stateTouches;
            }
        }
    }

    public static Token begin() {
        if (!(MyRandom.getRandom() instanceof AuditedRandom)) {
            throw failure("purity guard requires the benchmark RNG recorder");
        }
        return new Token((AuditedRandom) MyRandom.getRandom());
    }

    public static void assertUnchanged(final Token before, final String scope) {
        if (MyRandom.getRandom() != before.source) throw failure(scope + " replaced MyRandom provider");
        synchronized (before.source) {
            if (before.draws != before.source.draws || before.stateTouches != before.source.stateTouches) {
                throw failure(scope + " consumed or reset RNG: draws " + before.draws + " -> "
                        + before.source.draws + ", stateTouches " + before.stateTouches + " -> " + before.source.stateTouches);
            }
            before.source.purityChecks++;
        }
    }

    private static IllegalStateException failure(final String detail) {
        final String message = "BENCH_INTEGRITY_FAILURE RNG_PURITY: " + detail;
        System.err.println("[bench] " + message);
        System.err.flush();
        return new IllegalStateException(message);
    }

    /** Read-only sidecar. Never modifies the message or sends RNG data to host policy. */
    public static void checkpoint(final JsonObject message) {
        if (!(MyRandom.getRandom() instanceof AuditedRandom)) return;
        final String type = message.has("type") ? message.get("type").getAsString() : "";
        if (!"hello".equals(type) && !"ask".equals(type) && !"event".equals(type) && !"result".equals(type)) return;
        final JsonObject out = ((AuditedRandom) MyRandom.getRandom()).snapshot();
        out.addProperty("boundary", type);
        for (final String field : new String[] { "game", "seat", "id", "kind" }) {
            if (message.has(field)) out.add(field, message.get(field).deepCopy());
        }
        if (message.has("event") && message.get("event").isJsonObject()
                && message.getAsJsonObject("event").has("kind")) {
            out.add("eventKind", message.getAsJsonObject("event").get("kind").deepCopy());
        }
        System.err.println("[bench-rng] " + out);
        System.err.flush();
    }
}
