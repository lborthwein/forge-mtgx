package forge.bench;

import java.util.Random;

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
        private long digest = 0xcbf29ce484222325L;
        private long purityChecks;

        public AuditedRandom(final long seed) { super(seed); }

        @Override
        protected synchronized int next(final int bits) {
            final int value = super.next(bits);
            draws++;
            digest = (digest ^ bits) * 0x100000001b3L;
            digest = (digest ^ Integer.toUnsignedLong(value)) * 0x100000001b3L;
            return value;
        }

        @Override
        public synchronized void setSeed(final long seed) {
            super.setSeed(seed);
            stateTouches++;
        }

        @Override
        public synchronized double nextGaussian() {
            // The cached second Gaussian consumes no new raw bits but DOES
            // mutate RNG state, so a raw-bit counter alone misses this case.
            stateTouches++;
            return super.nextGaussian();
        }

        synchronized JsonObject snapshot() {
            final JsonObject out = new JsonObject();
            out.addProperty("schema", "forge-bench-private-rng/1");
            out.addProperty("provider", "java.util.Random");
            out.addProperty("draws", draws);
            out.addProperty("stateTouches", stateTouches);
            out.addProperty("digest", Long.toUnsignedString(digest, 16));
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
