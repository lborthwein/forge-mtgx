package forge.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An explicit state machine (state-machines-1113): a named enum of states plus the transitions allowed between them.
 *
 * <p>The machine never changes behavior. A transition outside the table is logged on stderr ({@code [state] ILLEGAL
 * ...}, rate-limited per machine) and counted, and the caller's transition still happens, unless the JVM runs with
 * {@code -Dforge.state.strict=true} (tests do), where it throws {@link IllegalStateException}. Legal transitions print
 * {@code [state] <machine> <key> FROM->TO (why)} only when {@code -Dforge.state.trace} names the machine (a comma list,
 * or {@code all}). With tracing off a transition costs one table lookup and one volatile write.
 *
 * <p>Every machine registers itself, so {@link #registry()} lists them and {@link #toMermaid()} draws one: an agent or a
 * developer can see every explicit machine and its edges without reading the code that drives it. Lines carry state
 * names, keys and reasons only. Callers never pass card names or hidden information.
 */
public final class StateMachine<S extends Enum<S>> {
    /** {@code -Dforge.state.strict=true}: an illegal transition throws (tests). */
    public static final boolean STRICT = Boolean.getBoolean("forge.state.strict");
    private static final Set<String> TRACED = parse(System.getProperty("forge.state.trace"));
    private static final int ILLEGAL_LOG_LIMIT = 20;
    private static final List<StateMachine<?>> REGISTRY = new CopyOnWriteArrayList<>();

    private final String name;
    private final Class<S> type;
    private final EnumMap<S, EnumSet<S>> edges;
    private final String doc;
    /** Whether legal transitions of this machine are printed (fixed at construction; free when false). */
    public final boolean trace;
    private final AtomicLong illegal = new AtomicLong();

    private StateMachine(String name, Class<S> type, EnumMap<S, EnumSet<S>> edges, String doc) {
        this.name = name;
        this.type = type;
        this.edges = edges;
        this.doc = doc;
        this.trace = TRACED.contains("all") || TRACED.contains(name);
    }

    public static <S extends Enum<S>> Builder<S> builder(String name, Class<S> type) {
        return new Builder<>(name, type);
    }

    public String name() {
        return name;
    }

    public Class<S> type() {
        return type;
    }

    public String doc() {
        return doc;
    }

    /** Whether the table allows {@code from -> to}. A self-transition is allowed only when listed. */
    public boolean allowed(S from, S to) {
        final EnumSet<S> next = edges.get(from);
        return next != null && next.contains(to);
    }

    /** The states {@code from} may move to (a copy). */
    public Set<S> next(S from) {
        final EnumSet<S> n = edges.get(from);
        return n == null ? EnumSet.noneOf(type) : EnumSet.copyOf(n);
    }

    /** Illegal transitions seen so far in this process. */
    public long illegalCount() {
        return illegal.get();
    }

    /**
     * Check and trace one transition the caller is making anyway; returns {@code to}. Illegal: logged (first
     * {@value #ILLEGAL_LOG_LIMIT} per machine), counted, and thrown only under {@link #STRICT}.
     */
    public S check(Object key, S from, S to, String why) {
        if (!allowed(from, to)) {
            final long n = illegal.incrementAndGet();
            final String line = "[state] ILLEGAL " + name + " " + key + " " + from + "->" + to + (why == null ? "" : " (" + why + ")");
            if (STRICT) {
                throw new IllegalStateException(line);
            }
            if (n <= ILLEGAL_LOG_LIMIT) {
                System.err.println(line + (n == ILLEGAL_LOG_LIMIT ? " [further ILLEGAL lines of this machine suppressed]" : ""));
            }
        } else if (trace) {
            System.err.println("[state] " + name + " " + key + " " + from + "->" + to + (why == null ? "" : " (" + why + ")"));
        }
        return to;
    }

    /** A holder of one instance's current state (thread-safe reads; writers serialize themselves). */
    public Cell<S> cell(Object key, S initial) {
        return new Cell<>(this, key, initial);
    }

    /** Mermaid {@code stateDiagram-v2} of this machine. */
    public String toMermaid() {
        final StringBuilder sb = new StringBuilder("stateDiagram-v2\n");
        for (S from : type.getEnumConstants()) {
            final EnumSet<S> next = edges.get(from);
            if (next == null) {
                continue;
            }
            for (S to : next) {
                sb.append("    ").append(from.name()).append(" --> ").append(to.name()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Every machine constructed in this process, in construction order. */
    public static List<StateMachine<?>> registry() {
        return Collections.unmodifiableList(new ArrayList<>(REGISTRY));
    }

    private static Set<String> parse(String v) {
        if (v == null || v.isBlank()) {
            return Collections.emptySet();
        }
        final Set<String> s = new HashSet<>();
        for (String p : v.split(",")) {
            if (!p.isBlank()) {
                s.add(p.trim());
            }
        }
        return s;
    }

    public static final class Builder<S extends Enum<S>> {
        private final String name;
        private final Class<S> type;
        private final EnumMap<S, EnumSet<S>> edges;
        private String doc = "";

        private Builder(String name, Class<S> type) {
            this.name = name;
            this.type = type;
            this.edges = new EnumMap<>(type);
        }

        /** Allow {@code from -> each of to}. */
        @SafeVarargs
        public final Builder<S> edge(S from, S... to) {
            edges.computeIfAbsent(from, k -> EnumSet.noneOf(type)).addAll(Arrays.asList(to));
            return this;
        }

        /** Allow every state in {@code from} to move to {@code to}. */
        public Builder<S> fromAll(Iterable<S> from, S to) {
            for (S f : from) {
                edge(f, to);
            }
            return this;
        }

        public Builder<S> doc(String text) {
            this.doc = text;
            return this;
        }

        public StateMachine<S> build() {
            final StateMachine<S> m = new StateMachine<>(name, type, edges, doc);
            REGISTRY.add(m);
            return m;
        }
    }

    /** One instance's state under machine {@code m}. */
    public static final class Cell<S extends Enum<S>> {
        private final StateMachine<S> m;
        private final Object key;
        private volatile S state;

        private Cell(StateMachine<S> m, Object key, S initial) {
            this.m = m;
            this.key = key;
            this.state = initial;
        }

        public S get() {
            return state;
        }

        public boolean is(S s) {
            return state == s;
        }

        /** Move to {@code to} (checked and traced); returns the previous state. */
        public S go(S to, String why) {
            final S from = state;
            m.check(key, from, to, why);
            state = to;
            return from;
        }

        public StateMachine<S> machine() {
            return m;
        }

        @Override
        public String toString() {
            return m.name + "[" + key + "]=" + state;
        }
    }

    @Override
    public String toString() {
        return "StateMachine(" + name + ", " + type.getSimpleName() + ")";
    }

    /** For tests: the edges as an unmodifiable map. */
    public Map<S, Set<S>> edges() {
        final EnumMap<S, Set<S>> copy = new EnumMap<>(type);
        for (Map.Entry<S, EnumSet<S>> e : edges.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableSet(EnumSet.copyOf(e.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }
}
