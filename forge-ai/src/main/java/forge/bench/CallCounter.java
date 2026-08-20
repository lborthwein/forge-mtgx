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
    private final Map<String, Integer> calls = new TreeMap<>();
    private final Map<String, Integer> delegatedRequested = new TreeMap<>();
    private final Map<String, Integer> delegatedRefused = new TreeMap<>();
    /**
     * Additive research instruments. Kept out of {@code calls} on purpose: that map is the
     * decision-surface measurement (controller entry points by name) and anything else in
     * it distorts {@code totalCalls}.
     */
    private final Map<String, Integer> instruments = new TreeMap<>();

    public synchronized void count(final String method) {
        calls.merge(method, 1, Integer::sum);
    }

    /** Count a named observation that is not a controller call. */
    public synchronized void instrument(final String name) {
        instruments.merge(name, 1, Integer::sum);
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
        calls.clear();
        delegatedRequested.clear();
        delegatedRefused.clear();
        instruments.clear();
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
