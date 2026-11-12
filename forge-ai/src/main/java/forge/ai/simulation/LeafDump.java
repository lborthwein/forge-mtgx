package forge.ai.simulation;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * Diagnostics only (lane sdcc-evokevalue-1112; off unless {@code -Dforge.rl.leafDump=FILE}): one JSON line per
 * value-leaf observation of a searched decision, with the state it was taken on (turn, phase, the stack, both seats'
 * zones) and the encoded observation itself, so that any value head can be read on exactly the search's leaves.
 * {@code -Dforge.rl.leafDump.all=true} also writes the seat's observation at every earlier priority of the play-out
 * (the candidate's own resolution included). Never read by the search: decisions are unchanged.
 */
public final class LeafDump {
    public static final String FILE = System.getProperty("forge.rl.leafDump");
    public static final boolean ON = FILE != null && !FILE.isEmpty();
    public static final boolean ALL = ON && Boolean.getBoolean("forge.rl.leafDump.all");
    private static final AtomicLong SEQ = new AtomicLong();
    private static Writer out;
    /** The live decision the play-outs belong to (set on the decision thread at the start of a searched decision). */
    public static volatile long decision = -1;
    public static volatile String tag = "";

    private LeafDump() {
    }

    private static JsonArray names(final Player p, final ZoneType z) {
        final JsonArray a = new JsonArray();
        for (Card c : p.getCardsIn(z)) {
            a.add(c.getName() + (z == ZoneType.Battlefield && c.isTapped() ? " (T)" : "")
                    + (z == ZoneType.Battlefield && c.isSick() ? " (sick)" : ""));
        }
        return a;
    }

    public static JsonObject state(final Game g, final Player me) {
        final JsonObject o = new JsonObject();
        o.addProperty("turn", g.getPhaseHandler().getTurn());
        o.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
        final Player ap = g.getPhaseHandler().getPlayerTurn();
        o.addProperty("active", ap == null ? -1 : g.getRegisteredPlayers().indexOf(ap));
        o.addProperty("seat", g.getRegisteredPlayers().indexOf(me));
        final JsonArray st = new JsonArray();
        for (SpellAbilityStackInstance si : g.getStack()) {
            String d;
            try {
                d = si.getSourceCard().getName() + " :: " + (si.isTrigger() ? "trigger" : si.isSpell() ? "spell" : "ability")
                        + " :: " + si.getStackDescription();
            } catch (RuntimeException e) {
                d = "?";
            }
            st.add(d.length() > 160 ? d.substring(0, 160) : d);
        }
        o.add("stack", st);
        for (Player p : g.getRegisteredPlayers()) {
            final JsonObject q = new JsonObject();
            q.addProperty("life", p.getLife());
            q.addProperty("handN", p.getCardsIn(ZoneType.Hand).size());
            q.addProperty("libN", p.getCardsIn(ZoneType.Library).size());
            if (p == me) {
                q.add("hand", names(p, ZoneType.Hand));
            }
            q.add("bf", names(p, ZoneType.Battlefield));
            int cr = 0, pw = 0, ld = 0;
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                cr += c.isCreature() ? 1 : 0;
                pw += c.isPlaneswalker() ? 1 : 0;
                ld += c.isLand() ? 1 : 0;
            }
            q.addProperty("creatures", cr);
            q.addProperty("planeswalkers", pw);
            q.addProperty("lands", ld);
            q.add("gy", names(p, ZoneType.Graveyard));
            q.add("exile", names(p, ZoneType.Exile));
            o.add(p == me ? "me" : "opp", q);
        }
        return o;
    }

    /** kind: "root" (the live position), "pre" (a play-out priority before the leaf), "leaf", "late" (leaf at the cap). */
    public static void write(final String kind, final String label, final long worldSeed, final int step, final Game g,
            final Player me, final Object payload) {
        try {
            final JsonObject o = new JsonObject();
            o.addProperty("seq", SEQ.getAndIncrement());
            o.addProperty("decision", decision);
            o.addProperty("tag", tag);
            o.addProperty("kind", kind);
            o.addProperty("label", label);
            o.addProperty("world", Long.toHexString(worldSeed));
            o.addProperty("step", step);
            o.add("state", state(g, me));
            if (payload instanceof byte[]) {
                o.addProperty("payload", Base64.getEncoder().encodeToString((byte[]) payload));
            }
            synchronized (LeafDump.class) {
                if (out == null) {
                    out = new FileWriter(FILE, true);
                }
                out.write(o.toString());
                out.write('\n');
                out.flush();
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[leafdump] " + e);
        }
    }
}
