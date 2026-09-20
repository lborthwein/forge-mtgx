/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.bench;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonObject;

/**
 * Per-seat decision-surface instrumentation.
 *
 * <p>{@code calls} counts every {@code PlayerController} entry point by method name.
 * {@code delegatedRequested} counts answers where the host explicitly said
 * {@code delegate:true}; {@code delegatedRefused} counts answers the JVM rejected as
 * illegal/unparseable and therefore handed to Forge's own AI. The two are kept apart
 * because only the second is a bridge defect.
 */
public final class CallCounter {
    private static final List<String> HOST_ANSWER_INSTRUMENTS = List.of(
            "hostAnswer.missingOrDelegated", "hostAnswer.refused", "hostAnswer.stockFallbackBlocked");
    public enum Ownership { HOST, FORCED, RULES, STOCK, UNCLASSIFIED }
    private final Map<String, java.util.EnumMap<Ownership, Integer>> coverage = new TreeMap<>();
    private String controllerMode;
    private long generation;
    private long nextInvocation;
    /** Unclassified or stock-owned calls, retained until reset. This makes a
     * successful outer rules operation insufficient to hide an unknown child. */
    private final java.util.NavigableSet<Long> untrustedInvocations = new java.util.TreeSet<>();

    /** One callback, not a mutable last-call slot. Nested invocations retain
     * their own ownership; exceptions leave callbacks unclassified. */
    public final class Invocation {
        private final String method;
        private final long born;
        private final long id;
        private boolean classified;
        private Invocation(String method, long id) { this.method = method; this.born = generation; this.id = id; }
        public void classify(Ownership owner) {
            synchronized (CallCounter.this) {
                if (owner == null || owner == Ownership.UNCLASSIFIED || classified || born != generation)
                    throw new IllegalStateException("Invalid, repeated or stale controller ownership classification");
                final var buckets = coverage.get(method);
                if (buckets == null || buckets.get(Ownership.UNCLASSIFIED) < 1)
                    throw new IllegalStateException("Controller ownership receipt lacks counted invocation");
                buckets.merge(Ownership.UNCLASSIFIED, -1, Integer::sum);
                buckets.merge(owner, 1, Integer::sum);
                if (owner != Ownership.STOCK) untrustedInvocations.remove(id);
                classified = true;
            }
        }

        /** Call only after a rules-only wrapper successfully executes. Nested
         * calls have their own receipts. An unknown/stock child leaves this
         * wrapper unclassified; a parent receipt never erases that evidence.
         * Unrelated concurrent later calls can only make this conservative. */
        public boolean classifyRulesIfChildrenAccounted() {
            synchronized (CallCounter.this) {
                if (born != generation || classified)
                    throw new IllegalStateException("Stale or repeated rules ownership receipt");
                if (untrustedInvocations.higher(id) != null) return false;
                classify(Ownership.RULES);
                return true;
            }
        }
    }

    public synchronized void configureControllerMode(String mode) {
        if (mode == null || !java.util.Set.of("bridge", "null", "null-probe").contains(mode)
                || controllerMode != null && !controllerMode.equals(mode))
            throw new IllegalArgumentException("Controller coverage mode is invalid or changed");
        controllerMode = mode;
    }
    private final Map<String, Integer> calls = new TreeMap<>();
    private final Map<String, Integer> delegatedRequested = new TreeMap<>();
    private final Map<String, Integer> delegatedRefused = new TreeMap<>();
    /**
     * Additive research instruments. Kept out of {@code calls} on purpose: that map is the
     * decision-surface measurement (controller entry points by name) and anything else in
     * it distorts {@code totalCalls}.
     */
    private final Map<String, Integer> instruments = new TreeMap<>();

    public CallCounter() {
        resetInstruments();
    }

    private void resetInstruments() {
        for (String name : HOST_ANSWER_INSTRUMENTS) {
            instruments.put(name, 0);
        }
    }

    public synchronized void count(final String method) {
        beginCall(method); // Legacy instrumentation does not establish ownership.
    }

    public synchronized Invocation beginCall(final String method) {
        if (method == null || method.isBlank()) throw new IllegalArgumentException("Missing controller method");
        calls.merge(method, 1, Integer::sum);
        final var buckets = coverage.computeIfAbsent(method, ignored -> {
            final var result = new java.util.EnumMap<Ownership, Integer>(Ownership.class);
            for (Ownership owner : Ownership.values()) result.put(owner, 0);
            return result;
        });
        buckets.merge(Ownership.UNCLASSIFIED, 1, Integer::sum);
        long id = ++nextInvocation;
        untrustedInvocations.add(id);
        return new Invocation(method, id);
    }

    /** Count a named observation that is not a controller call. */
    public synchronized void instrument(final String name) {
        instrument(name, 1);
    }

    public synchronized void instrument(final String name, final int n) {
        if (n > 0) {
            instruments.merge(name, n, Integer::sum);
        }
    }

    public synchronized void delegateRequested(final String method) {
        delegatedRequested.merge(method, 1, Integer::sum);
    }

    public synchronized void delegateRefused(final String method, final String why) {
        delegatedRefused.merge(method, 1, Integer::sum);
        JsonRpcChannel.log("refusal in " + method + ": " + why);
    }

    public synchronized int totalCalls() {
        int n = 0;
        for (int v : calls.values()) {
            n += v;
        }
        return n;
    }

    public synchronized int total(final Map<String, Integer> m) {
        int n = 0;
        for (int v : m.values()) {
            n += v;
        }
        return n;
    }

    public synchronized void reset() {
        generation++;
        untrustedInvocations.clear();
        nextInvocation = 0;
        coverage.clear();
        calls.clear();
        delegatedRequested.clear();
        delegatedRefused.clear();
        instruments.clear();
        resetInstruments();
    }

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("calls", mapToJson(calls));
        o.add("delegatedRequested", mapToJson(delegatedRequested));
        o.add("delegatedRefused", mapToJson(delegatedRefused));
        o.add("instruments", mapToJson(instruments));
        o.addProperty("totalCalls", totalCalls());
        o.addProperty("totalDelegatedRequested", total(delegatedRequested));
        o.addProperty("totalDelegatedRefused", total(delegatedRefused));
        if (controllerMode != null) {
            final var evidence = new JsonObject();
            evidence.addProperty("schema", "forge-controller-coverage/1");
            evidence.addProperty("mode", controllerMode);
            final var methods = new JsonObject();
            for (var entry : coverage.entrySet()) {
                final var buckets = new JsonObject();
                for (Ownership owner : Ownership.values())
                    buckets.addProperty(owner.name().toLowerCase(java.util.Locale.ROOT), entry.getValue().get(owner));
                methods.add(entry.getKey(), buckets);
            }
            evidence.add("methods", methods);
            o.add("controllerCoverage", evidence);
        }
        return o;
    }

    private static JsonObject mapToJson(final Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            o.addProperty(e.getKey(), e.getValue());
        }
        return o;
    }
}
