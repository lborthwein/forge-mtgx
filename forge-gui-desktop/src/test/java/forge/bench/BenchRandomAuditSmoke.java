package forge.bench;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import com.google.gson.JsonObject;
import forge.util.MyRandom;

/** No matches, card loading, GUI or model. Uses the actual pinned MyRandom/channel classes. */
public final class BenchRandomAuditSmoke {
    private static int checks;
    private static void check(final boolean ok, final String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    private static void expectFailure(final BenchRandomAudit.Token token, final String label) {
        try { BenchRandomAudit.assertUnchanged(token, label); throw new AssertionError("guard accepted " + label); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("BENCH_INTEGRITY_FAILURE RNG_PURITY"), label); }
    }

    public static void main(final String[] args) {
        for (final long seed : new long[] {0, 1, -1, 95600, Long.MAX_VALUE, Long.MIN_VALUE}) {
            final Random reference = new Random(seed);
            final Random audited = new BenchRandomAudit.AuditedRandom(seed);
            for (int i = 0; i < 1000; i++) {
                check(reference.nextInt() == audited.nextInt(), "int stream");
                check(reference.nextInt(37) == audited.nextInt(37), "bounded int stream");
                check(reference.nextLong() == audited.nextLong(), "long stream");
                check(reference.nextDouble() == audited.nextDouble(), "double stream");
                check(reference.nextFloat() == audited.nextFloat(), "float stream");
                check(reference.nextBoolean() == audited.nextBoolean(), "boolean stream");
                check(reference.nextGaussian() == audited.nextGaussian(), "Gaussian/cache stream");
                check(reference.nextInt(-20, 300) == audited.nextInt(-20, 300), "range stream");
                check(reference.nextLong(100000000000L) == audited.nextLong(100000000000L), "bounded long stream");
                byte[] a = new byte[i % 19], b = new byte[i % 19];
                reference.nextBytes(a); audited.nextBytes(b);
                check(Arrays.equals(a, b), "bytes stream");
                if (i == 501) { reference.setSeed(seed + 3); audited.setSeed(seed + 3); }
            }
        }

        final BenchRandomAudit.AuditedRandom seedA = new BenchRandomAudit.AuditedRandom(17);
        final BenchRandomAudit.AuditedRandom seedB = new BenchRandomAudit.AuditedRandom(18);
        final BenchRandomAudit.AuditedRandom repeatA = new BenchRandomAudit.AuditedRandom(17);
        check(seedA.snapshot().get("digest").equals(repeatA.snapshot().get("digest")), "same initial seed same transcript");
        check(!seedA.snapshot().get("digest").equals(seedB.snapshot().get("digest")), "initial seed is recorded before first draw");
        seedA.setSeed(19); repeatA.setSeed(20);
        check(!seedA.snapshot().get("digest").equals(repeatA.snapshot().get("digest")), "reset argument recorded even without later draw");
        check(seedA.snapshot().equals(seedA.snapshot()), "checkpoint does not finalize or mutate transcript");
        check(seedA.snapshot().get("digest").getAsString().matches("[0-9a-f]{64}"), "SHA-256 transcript width");
        final BenchRandomAudit.AuditedRandom gaussian = new BenchRandomAudit.AuditedRandom(99);
        gaussian.nextGaussian();
        final JsonObject beforeCached = gaussian.snapshot();
        gaussian.nextGaussian();
        check(beforeCached.get("draws").equals(gaussian.snapshot().get("draws")), "cached Gaussian adds no raw bits");
        check(!beforeCached.get("digest").equals(gaussian.snapshot().get("digest")), "cached Gaussian changes transcript");

        BenchRandomAudit.install(99);
        final Random reference = new Random(99);
        BenchRandomAudit.Token before = BenchRandomAudit.begin();
        check(MyRandom.getRandom().nextInt() == reference.nextInt(), "injected draw");
        expectFailure(before, "injected menu draw");
        check(MyRandom.getRandom().nextInt() == reference.nextInt(), "failure does not rewind or hide the draw");

        BenchRandomAudit.install(99);
        MyRandom.getRandom().nextGaussian();
        before = BenchRandomAudit.begin();
        final long rawBefore = ((BenchRandomAudit.AuditedRandom) MyRandom.getRandom()).snapshot().get("draws").getAsLong();
        MyRandom.getRandom().nextGaussian();
        check(rawBefore == ((BenchRandomAudit.AuditedRandom) MyRandom.getRandom()).snapshot().get("draws").getAsLong(), "cached Gaussian fixture consumes no raw bits");
        expectFailure(before, "cached Gaussian mutation");

        before = BenchRandomAudit.begin();
        MyRandom.getRandom().setSeed(99);
        expectFailure(before, "seed reset");
        before = BenchRandomAudit.begin();
        final Random replacement = new Random(99);
        MyRandom.setRandom(replacement);
        expectFailure(before, "provider replacement");
        check(MyRandom.getRandom() == replacement, "replacement is not silently restored");

        BenchRandomAudit.install(99);
        before = BenchRandomAudit.begin();
        final JsonObject message = new JsonObject();
        message.addProperty("type", "ask"); message.addProperty("game", "g1");
        message.addProperty("kind", "priority"); message.addProperty("id", 1);
        final String original = message.toString();
        final ByteArrayOutputStream wire = new ByteArrayOutputStream(), audit = new ByteArrayOutputStream();
        final PrintStream stderr = System.err;
        try {
            System.setErr(new PrintStream(audit, true, StandardCharsets.UTF_8));
            new JsonRpcChannel(new ByteArrayInputStream(new byte[0]), wire).send(message);
        } finally { System.setErr(stderr); }
        BenchRandomAudit.assertUnchanged(before, "sidecar logging");
        check(original.equals(message.toString()), "message is immutable to recorder");
        check((original + System.lineSeparator()).equals(wire.toString(StandardCharsets.UTF_8)), "policy wire has no RNG fields or audit records");
        check(audit.toString(StandardCharsets.UTF_8).contains("[bench-rng] "), "private audit receipt exists");
        check(audit.toString(StandardCharsets.UTF_8).contains("\"game\":\"g1\""), "audit has semantic boundary join");
        check(MyRandom.getRandom().nextInt() == new Random(99).nextInt(), "logging consumes zero RNG");
        System.out.println("PASS " + checks + " RNG stream/purity/private-sidecar checks");
    }
}
