package forge.ai;

import com.google.gson.JsonObject;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.Player;

/**
 * mtgx Forge AI fixes of 2026-09-28 (lane yardstick-0928), behind one per-player option {@code aiFixes0928}.
 *
 * <p>Forge Default is the yardstick opponent of every mtgx read, so these fixes are OFF by default: with the option off
 * every decision is the upstream (pre-fix) one. The option belongs to the deciding AI player (its
 * {@link LobbyPlayerAi}); copies of a game made for look-ahead play-outs carry each player's mode.
 * <ul>
 * <li>{@link Kind#CHOOSE_DESTROY} (fork #23, ChooseCardAi): a "choose a card, then destroy it" choice among an
 * opponent's cards picks among the destroyable ones (Chaos Defiler vs Blightsteel Colossus + Mana Crypt).</li>
 * <li>{@link Kind#BURN_FACE} (fork #24, DamageAiBase.shouldTgtP): after blockers are declared, burn that is lethal
 * together with the combat damage still to come goes to the face.</li>
 * <li>{@link Kind#BLOCKER_LEFT} (fork #24, ComputerUtilCombat.lifeThatWouldRemain): a blocked attacker whose blockers
 * all left combat deals no damage unless it has trample.</li>
 * </ul>
 * {@link Mode#SHADOW} decides exactly as {@link Mode#OFF} and counts the spots where a fix would have fired, in the live
 * game and (separately) in the player's look-ahead play-outs ({@link Counters}); {@link Mode#ON} applies the fixes and
 * counts the same spots. Counting never changes a decision.
 */
public final class AiFixes {
    private AiFixes() {
    }

    public enum Mode {
        OFF, SHADOW, ON;

        /** "off" | "shadow" | "on" (also "0"/"1", "false"/"true"). */
        public static Mode parse(String s) {
            if (s == null) {
                return OFF;
            }
            switch (s.trim().toLowerCase()) {
                case "": case "off": case "0": case "false": return OFF;
                case "shadow": return SHADOW;
                case "on": case "1": case "true": return ON;
                default: throw new IllegalArgumentException("aiFixes0928: expected off|shadow|on, got '" + s + "'");
            }
        }

        public String key() {
            return name().toLowerCase();
        }
    }

    public enum Kind {
        /** ChooseCardAi: the filter removed an indestructible option (fired) / the chosen card differs (changed). */
        CHOOSE_DESTROY,
        /** shouldTgtP: lethal with the combat damage to come (fired) / and life - burn >= 5, where upstream's
         * deterministic face rule would not go face (changed; upstream's hand-size odds may still go face). */
        BURN_FACE,
        /** lifeThatWouldRemain: a blocked non-trampler with no blockers left (fired = the predicted life differs). */
        BLOCKER_LEFT
    }

    /**
     * Per-player counts over one live game: spots in the live game itself, and spots inside that player's look-ahead
     * play-outs (copies of the game whose lobby players inherit the counters), which can change a searched decision.
     */
    public static final class Counters {
        final int[] fired = new int[Kind.values().length];
        final int[] changed = new int[Kind.values().length];
        final int[] copyFired = new int[Kind.values().length];
        /** The live game: its spots count as live, any other game's (a play-out copy) as copy spots. */
        Game game;

        public int fired(Kind k) {
            return fired[k.ordinal()];
        }

        public int changed(Kind k) {
            return changed[k.ordinal()];
        }

        public int copyFired(Kind k) {
            return copyFired[k.ordinal()];
        }

        public JsonObject toJson(Mode mode) {
            JsonObject o = new JsonObject();
            o.addProperty("mode", mode.key());
            o.addProperty("chooseDestroyFired", fired(Kind.CHOOSE_DESTROY));
            o.addProperty("chooseDestroyChanged", changed(Kind.CHOOSE_DESTROY));
            o.addProperty("burnFaceFired", fired(Kind.BURN_FACE));
            o.addProperty("burnFaceChanged", changed(Kind.BURN_FACE));
            o.addProperty("blockerLeftFired", fired(Kind.BLOCKER_LEFT));
            o.addProperty("copyChooseDestroyFired", copyFired(Kind.CHOOSE_DESTROY));
            o.addProperty("copyBurnFaceFired", copyFired(Kind.BURN_FACE));
            o.addProperty("copyBlockerLeftFired", copyFired(Kind.BLOCKER_LEFT));
            return o;
        }
    }

    /** The deciding player's mode: its lobby player's option (OFF for a human, a remote seat, or no controller). */
    public static Mode mode(final Player p) {
        if (p == null || p.getController() == null) {
            return Mode.OFF;
        }
        final LobbyPlayer lp = p.getLobbyPlayer();
        return lp instanceof LobbyPlayerAi ? ((LobbyPlayerAi) lp).getAiFixes0928() : Mode.OFF;
    }

    public static boolean on(final Player p) {
        return mode(p) == Mode.ON;
    }

    /** Record a spot where a fix fires (ON) or would have fired (SHADOW): live-game spots, or play-out copy spots. */
    public static void record(final Player p, final Kind kind, final boolean changed) {
        final LobbyPlayer lp = p.getLobbyPlayer();
        if (!(lp instanceof LobbyPlayerAi)) {
            return;
        }
        final Counters c = ((LobbyPlayerAi) lp).getAiFixesCounters();
        if (c == null) {
            return;
        }
        synchronized (c) {
            if (c.game != p.getGame()) {
                c.copyFired[kind.ordinal()]++;
                return;
            }
            c.fired[kind.ordinal()]++;
            if (changed) {
                c.changed[kind.ordinal()]++;
            }
        }
    }

    /** A copy's lobby player takes the original's mode and counters (look-ahead play-outs; see GameCopier). */
    public static void inherit(final LobbyPlayerAi from, final LobbyPlayerAi to) {
        to.setAiFixes0928(from.getAiFixes0928());
        to.setAiFixesCounters(from.getAiFixesCounters());
    }

    /** Start counting this lobby player's fix spots in {@code game} (a bench run's live game). */
    public static Counters count(final LobbyPlayerAi lp, final Game game) {
        final Counters c = new Counters();
        c.game = game;
        lp.setAiFixesCounters(c);
        return c;
    }
}
