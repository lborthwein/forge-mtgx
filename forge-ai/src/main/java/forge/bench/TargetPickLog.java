package forge.bench;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.simulation.TargetPicks;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * target-picks-1119 ({@code targetLog=on}; null on the controller = off, and then nothing is recorded or computed): one
 * compact record per targeted pick of the policy seat (a spell, an ability or a trigger; one record per targeting part)
 * and the game's totals. The records wait here until the seat's next {@code [lookahead-decision]} line takes them
 * ({@link #drain}: its {@code targets} field); the totals go on the {@code [policy-seat]} end line ({@link #stats}).
 *
 * <p>A record: {@code t} turn, {@code o} origin (cast, trigger, playFromEffect, noStack, line), {@code card} the host
 * card, {@code by} the chooser, {@code legal} [our permanents, the opponents' permanents, players, other (stack, other
 * zones)], {@code side} the side of the targets held after the pick (own, opp, self, face, other, mixed, none),
 * {@code harm} ({@link TargetPicks#harm}; absent = not harmful), {@code g} the pick guard's action (retarget, decline,
 * declined, forced, kept, none-opposing, kept-unsure: a {@link TargetPicks#plainAsset} condition fails,
 * kept-variant: a search variant's own pick; absent = none), {@code line} (a followed solver line's turn). Choosers: {@code line} (a
 * followed solver line's own targets: an ENGINE step's recorded target, or the line's prepared one), {@code forge}
 * (Forge AI: a line's lineChoices=forge ask, a capped or unposed menu, a refused answer, or kept after a failed seat
 * ask), {@code policy} (the policy network's answer), {@code single} (one legal choice, answered by the seat itself),
 * {@code variant} (a search target variant forced), {@code guard} (ownHarmPicks retargeted it).
 */
public final class TargetPickLog {
    /** Records kept for one decision line at most; past it they are counted ({@code overflow}) and dropped. */
    public static final int CAP = 32;

    private JsonArray pending = new JsonArray();
    private final Map<String, Integer> by = new TreeMap<>(), origin = new TreeMap<>(), side = new TreeMap<>(),
            guard = new TreeMap<>();
    private int picks, harmful, harmfulOwn, mixed, mixedOwn, overflow, attached, variantArmed, variantApplied,
            variantMissed;

    /** Record one pick: the part {@code part} of {@code root}, its legal targets {@code legal} (null = not read). */
    public synchronized void pick(final Player me, final SpellAbility root, final SpellAbility part, final String o,
            final String chooser, final List<? extends GameEntity> legal, final int stackLegal, final String g,
            final boolean line) {
        final JsonObject r = new JsonObject();
        try {
            r.addProperty("t", me.getGame().getPhaseHandler().getTurn());
            r.addProperty("o", o);
            final Card host = root == null ? part.getHostCard() : root.getHostCard();
            r.addProperty("card", host == null ? "?" : host.getName());
            r.addProperty("by", chooser);
            int own = 0, opp = 0, players = 0, other = stackLegal;
            if (legal != null) {
                for (GameEntity e : legal) {
                    final String s = TargetPicks.side(me, e);
                    if ("own".equals(s)) {
                        own++;
                    } else if ("opp".equals(s)) {
                        opp++;
                    } else if (e instanceof Player) {
                        players++;
                    } else {
                        other++;
                    }
                }
            }
            final JsonArray lg = new JsonArray();
            lg.add(own);
            lg.add(opp);
            lg.add(players);
            lg.add(other);
            r.add("legal", lg);
            String sd = null;
            for (Object t : part.getTargets()) {
                final String s = t instanceof GameEntity ? TargetPicks.side(me, t) : "other";
                sd = sd == null ? s : sd.equals(s) ? sd : "mixed";
            }
            sd = sd == null ? "none" : sd;
            r.addProperty("side", sd);
            final String h = TargetPicks.harm(part);
            if (h != null) {
                r.addProperty("harm", h);
            }
            if (g != null) {
                r.addProperty("g", g);
            }
            if (line) {
                r.addProperty("line", true);
            }
            picks++;
            by.merge(chooser, 1, Integer::sum);
            origin.merge(o, 1, Integer::sum);
            side.merge(sd, 1, Integer::sum);
            if (g != null) {
                guard.merge(g, 1, Integer::sum);
            }
            if (h != null) {
                harmful++;
                if ("own".equals(sd) || "mixed".equals(sd)) {
                    harmfulOwn++;
                }
            }
            if (own > 0 && opp > 0) {
                mixed++;
                if ("own".equals(sd)) {
                    mixedOwn++;
                }
            }
        } catch (RuntimeException e) {
            r.addProperty("error", String.valueOf(e));
        }
        if (pending.size() < CAP) {
            pending.add(r);
        } else {
            overflow++;
        }
    }

    /** A search variant was armed on the seat / applied at the cast / not applied (the target was no longer legal). */
    public synchronized void variant(final String what) {
        switch (what) {
            case "armed": variantArmed++; break;
            case "applied": variantApplied++; break;
            default: variantMissed++; break;
        }
    }

    /** The records since the last drain (null = none), for a decision line's {@code targets} field. */
    public synchronized JsonArray drain() {
        if (pending.size() == 0) {
            return null;
        }
        final JsonArray out = pending;
        attached += out.size();
        pending = new JsonArray();
        return out;
    }

    /** The game's totals; the records no decision line took are its {@code tail}. */
    public synchronized JsonObject stats() {
        final JsonObject o = new JsonObject();
        o.addProperty("picks", picks);
        o.add("by", map(by));
        o.add("origin", map(origin));
        o.add("side", map(side));
        o.addProperty("harmful", harmful);
        o.addProperty("harmfulOwn", harmfulOwn);
        o.addProperty("mixedLegal", mixed);
        o.addProperty("mixedLegalOwn", mixedOwn);
        o.add("guard", map(guard));
        o.addProperty("variantArmed", variantArmed);
        o.addProperty("variantApplied", variantApplied);
        o.addProperty("variantMissed", variantMissed);
        o.addProperty("attached", attached);
        o.addProperty("overflow", overflow);
        if (pending.size() > 0) {
            o.add("tail", pending);
        }
        return o;
    }

    private static JsonObject map(final Map<String, Integer> m) {
        final JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }
}
