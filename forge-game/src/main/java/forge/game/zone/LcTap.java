package forge.game.zone;

import java.io.PrintWriter;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * sdcc-lctap-1112, diagnostics only ({@code -Dforge.ai.lcTap=FILE}; absent: one static boolean check per zone read,
 * nothing else). While Forge's stock AI answers an ask inside a followed proven line (armed by
 * PlayerControllerBridge.ask on its {@code forgeChoices} branch, disarmed at the next ask of that thread), every read of
 * the cards of another player's hand or of any library made inside the bridge's delegation to Forge AI (the first
 * PlayerControllerBridge frame was entered from PlayerControllerAi) is counted by (ask kind, bridge handler, whose
 * zone, zone, accessor, the frames up to the second forge.ai frame): READ = reads, ASK = asks that hit the site, OUT =
 * reads outside the delegation (stale arm), ARM = asks handed to Forge AI. Counts only: no card is recorded. Written to FILE.PID.tsv every 30 s and at exit.
 */
public final class LcTap {
    public static final boolean ON = System.getProperty("forge.ai.lcTap") != null;
    private static final ThreadLocal<Object[]> ARMED = new ThreadLocal<>(); // {kind, seat player id, sites seen}
    private static final ConcurrentHashMap<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();

    static {
        if (ON) {
            final Thread t = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(30_000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    dump();
                }
            }, "lctap-dump");
            t.setDaemon(true);
            t.start();
            Runtime.getRuntime().addShutdownHook(new Thread(LcTap::dump, "lctap-exit"));
        }
    }

    private LcTap() {
    }

    /** The bridge hands this ask to Forge AI. */
    public static void arm(final String kind, final int seatPlayerId) {
        if (ON) {
            ARMED.set(new Object[] {kind, seatPlayerId, new java.util.HashSet<String>()});
            bump("ARM\t" + kind);
        }
    }

    public static void disarm() {
        if (ON) {
            ARMED.remove();
        }
    }

    /** A read of {@code z}'s cards through {@code how} (get, getCards, iterator). */
    static void read(final Zone z, final String how) {
        final Object[] a = ARMED.get();
        if (a == null || BUSY.get() != null) {
            return;
        }
        final ZoneType t = z.zoneType;
        if (t != ZoneType.Hand && t != ZoneType.Library) {
            return;
        }
        BUSY.set(Boolean.TRUE);
        try {
            final forge.game.player.Player p = z.getPlayer();
            if (p == null) {
                return;
            }
            final boolean own = p.getId() == (Integer) a[1];
            if (own && t == ZoneType.Hand) {
                return;
            }
            final StackTraceElement[] st = new Throwable().getStackTrace();
            // the read counts only inside the bridge's delegation to Forge AI: the first PlayerControllerBridge frame
            // (from the read outwards) must have been entered from PlayerControllerAi (its super call)
            int b = -1;
            for (int i = 0; i < st.length; i++) {
                if (st[i].getClassName().equals("forge.bench.PlayerControllerBridge")) {
                    b = i;
                    break;
                }
            }
            if (b < 1 || !st[b - 1].getClassName().equals("forge.ai.PlayerControllerAi")) {
                bump("OUT\t" + a[0] + "\t" + (own ? "seat" : "opp") + "\t" + t + "\t" + how);
                return; // a stale arm (the line's own steps, the solver's copies) or the engine after the answer
            }
            final StringBuilder sb = new StringBuilder();
            int ai = 0, n = 0;
            for (int i = 0; i < b; i++) {
                final StackTraceElement e = st[i];
                final String c = e.getClassName();
                if (c.equals(LcTap.class.getName()) || c.equals(Zone.class.getName()) || c.startsWith("java.")) {
                    continue;
                }
                final boolean isAi = c.startsWith("forge.ai.");
                if (isAi) {
                    ai++;
                }
                if (n < 8 || (isAi && ai <= 2)) {
                    sb.append(sb.length() == 0 ? "" : " < ").append(c.substring(c.lastIndexOf('.') + 1)).append('.')
                            .append(e.getMethodName()).append(':').append(e.getLineNumber());
                }
                n++;
                if (ai >= 2) {
                    break;
                }
            }
            final String key = a[0] + "\t" + st[b].getMethodName() + "\t" + (own ? "seat" : "opp") + "\t" + t + "\t"
                    + how + "\t" + sb;
            bump("READ\t" + key);
            @SuppressWarnings("unchecked")
            final java.util.Set<String> seen = (java.util.Set<String>) a[2];
            if (seen.add(key)) {
                bump("ASK\t" + key); // asks (arms) that hit this site at least once
            }
        } finally {
            BUSY.remove();
        }
    }

    private static void bump(final String k) {
        COUNTS.computeIfAbsent(k, x -> new AtomicLong()).incrementAndGet();
    }

    private static synchronized void dump() {
        try {
            final String f = System.getProperty("forge.ai.lcTap") + "." + ProcessHandle.current().pid() + ".tsv";
            final Map<String, Long> m = new TreeMap<>();
            for (Map.Entry<String, AtomicLong> e : COUNTS.entrySet()) {
                m.put(e.getKey(), e.getValue().get());
            }
            try (PrintWriter w = new PrintWriter(f + ".tmp", "UTF-8")) {
                for (Map.Entry<String, Long> e : m.entrySet()) {
                    w.println(e.getValue() + "\t" + e.getKey());
                }
            }
            java.nio.file.Files.move(java.nio.file.Paths.get(f + ".tmp"), java.nio.file.Paths.get(f),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("[lctap] dump failed: " + e);
        }
    }
}
