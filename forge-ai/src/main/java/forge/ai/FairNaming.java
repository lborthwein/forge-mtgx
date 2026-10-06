package forge.ai;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import com.google.gson.JsonObject;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;

/**
 * Fair card naming (lane ai-misplays-1006, owner report 2026-10-06T01:51Z: "Did the AI name Oko with its Phyrexian
 * Revoker? How did it know I had that in my deck?"). One per-player option {@code fairNaming}, off|shadow|on, OFF by
 * default: with it off every decision is upstream Forge AI's.
 *
 * <p>Upstream Forge AI chooses a card name from cards a person at the table could not know:
 * <ul>
 * <li>{@link SpecialCardAi.PithingNeedle} (Phyrexian Revoker, Pithing Needle, Sorcerous Spyglass) scores every card the
 * opponent owns in every zone ({@link Player#getAllCards()}: library, hand, sideboard, ...) and first reads the
 * opponent deck's key-card list;</li>
 * <li>{@code AILogic$ MostProminentInHumanDeck} counts the opponent's library;</li>
 * <li>a NameCard without AILogic (Tamiyo, Collector of Tales) names the first nonland card an opponent owns in any
 * zone.</li>
 * </ul>
 * ON restricts the opponents' cards those choices may read to the ones this player has seen this game: visible to it
 * now ({@link #visible}: the face-up identity predicate the interactive host uses for a person), visible at one of its
 * earlier priority decisions this game ({@link #observe}), or revealed to it ({@link #observeRevealed}). The key-card
 * list (deck metadata) is not read. When no seen opponent card scores, the Pithing Needle logic falls back to its own
 * name for that case ({@link SpecialCardAi.PithingNeedle#chooseNonBattlefieldName}) instead of the least-bad of its own
 * cards. Its own cards, its own library and the card database stay readable: a player knows their own deck.
 *
 * <p>{@link AiFixes.Mode#SHADOW} decides as OFF and counts the naming decisions and the ones where the fair name
 * differs; ON decides fairly and counts the same. Counting never changes a decision. Copies of a game made for
 * look-ahead play-outs carry each player's mode and counters ({@link AiFixes#inherit}); a copy's own memory starts
 * from what is visible in the copy. {@code -Dforge.ai.fairNaming.log=true} prints one stderr line per counted decision.
 */
public final class FairNaming {
    private FairNaming() {
    }

    /** Per-player counts over one live game (naming decisions in the live game, and in its look-ahead copies). */
    public static final class Counters {
        int fired;
        int changed;
        int copyFired;
        /** The live game: its decisions count as live, any other game's (a play-out copy) as copy decisions. */
        Game game;

        public int fired() {
            return fired;
        }

        public int changed() {
            return changed;
        }

        public int copyFired() {
            return copyFired;
        }

        public JsonObject toJson(AiFixes.Mode mode) {
            JsonObject o = new JsonObject();
            o.addProperty("mode", mode.key());
            o.addProperty("fired", fired);
            o.addProperty("changed", changed);
            o.addProperty("copyFired", copyFired);
            return o;
        }
    }

    /** The deciding player's mode: its lobby player's option (OFF for a human, a remote seat, or no controller). */
    public static AiFixes.Mode mode(final Player p) {
        if (p == null || p.getController() == null) {
            return AiFixes.Mode.OFF;
        }
        final LobbyPlayer lp = p.getLobbyPlayer();
        return lp instanceof LobbyPlayerAi ? ((LobbyPlayerAi) lp).getFairNaming() : AiFixes.Mode.OFF;
    }

    /** Start counting this lobby player's naming decisions in {@code game} (a bench run's live game). */
    public static Counters count(final LobbyPlayerAi lp, final Game game) {
        final Counters c = new Counters();
        c.game = game;
        lp.setFairNamingCounters(c);
        return c;
    }

    /**
     * The name to use: OFF calls only {@code upstream} (upstream Forge AI, unchanged); SHADOW and ON call both, count the
     * decision, and return the upstream name (SHADOW) or the fair one (ON).
     */
    public static String decide(final Player ai, final String what, final Supplier<String> upstream, final Supplier<String> fair) {
        final AiFixes.Mode mode = mode(ai);
        if (mode == AiFixes.Mode.OFF) {
            return upstream.get();
        }
        final String u = upstream.get();
        final String f = fair.get();
        record(ai, what, u, f, mode);
        return mode == AiFixes.Mode.ON ? f : u;
    }

    private static void record(final Player ai, final String what, final String upstream, final String fair, final AiFixes.Mode mode) {
        final boolean changed = !String.valueOf(upstream).equals(String.valueOf(fair));
        boolean live = true;
        final LobbyPlayer lp = ai.getLobbyPlayer();
        final Counters c = lp instanceof LobbyPlayerAi ? ((LobbyPlayerAi) lp).getFairNamingCounters() : null;
        if (c != null) {
            synchronized (c) {
                live = c.game == null || c.game == ai.getGame();
                if (live) {
                    c.fired++;
                    if (changed) {
                        c.changed++;
                    }
                } else {
                    c.copyFired++;
                }
            }
        }
        if (Boolean.getBoolean("forge.ai.fairNaming.log")) {
            System.err.println("[fair-naming] mode=" + mode.key() + " live=" + live + " player=" + ai.getName()
                    + " turn=" + ai.getGame().getPhaseHandler().getTurn() + " for=" + what
                    + " upstream=\"" + upstream + "\" fair=\"" + fair + "\" changed=" + changed);
        }
    }

    /** May {@code ai} see {@code c}'s face-up identity now (the interactive host's predicate for a person's seat)? */
    public static boolean visible(final Card c, final Player ai) {
        final CardView v = c.getView();
        final PlayerView pv = ai.getView();
        return v != null && pv != null && v.canBeShownTo(pv) && v.canFaceDownBeShownTo(pv);
    }

    /** Names of opponents' cards {@code ai} has seen this game (its controller's memory; empty for other controllers). */
    static Set<String> seen(final Player ai) {
        return ai.getController() instanceof PlayerControllerAi
                ? ((PlayerControllerAi) ai.getController()).getFairNamingSeen() : new HashSet<>();
    }

    /** Remember the opponents' cards {@code ai} can see now (called at each of its priority decisions; OFF: nothing). */
    public static void observe(final Player ai) {
        if (mode(ai) == AiFixes.Mode.OFF) {
            return;
        }
        final Set<String> seen = seen(ai);
        for (Player opp : ai.getOpponents()) {
            for (Card c : opp.getAllCards()) {
                if (visible(c, ai)) {
                    seen.add(c.getName());
                }
            }
        }
    }

    /** Remember opponents' cards revealed to {@code ai} (OFF: nothing). */
    public static void observeRevealed(final Player ai, final Iterable<Card> cards) {
        if (mode(ai) == AiFixes.Mode.OFF || cards == null) {
            return;
        }
        final Set<String> seen = seen(ai);
        for (Card c : cards) {
            if (c != null && (ai.isOpponentOf(c.getOwner()) || ai.isOpponentOf(c.getController()))) {
                seen.add(c.getName());
            }
        }
    }

    /** {@code opp}'s cards (every zone, as {@link Player#getAllCards()}) that {@code ai} sees now or has seen this game. */
    public static CardCollection known(final Player ai, final Player opp) {
        final Set<String> seen = seen(ai);
        final CardCollection out = new CardCollection();
        for (Card c : opp.getAllCards()) {
            if (visible(c, ai) || seen.contains(c.getName())) {
                out.add(c);
            }
        }
        return out;
    }

    /** The cards of {@code cards} (an opponent's) that {@code ai} sees now or has seen this game, as {@link #known(Player, Player)}. */
    public static CardCollection knownAmong(final Player ai, final CardCollectionView cards) {
        final Set<String> seen = seen(ai);
        final CardCollection out = new CardCollection();
        for (Card c : cards) {
            if (visible(c, ai) || seen.contains(c.getName())) {
                out.add(c);
            }
        }
        return out;
    }
}
