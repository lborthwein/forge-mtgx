package forge.game.phase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.common.collect.Multiset;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * mtgx fork (lane jar-glow-valki-1027; owner game forge-mvr55yi8, 2026-10-27, owner: "fix it for the generalized
 * case"): an AI player never repeats an action that brought the game back, unchanged, to the state it was chosen in.
 *
 * <p>The loop it stops: on the owner's turn, with Nissa, Ascended Animist on the stack, the AI seat answered every
 * priority by activating Valki, God of Lies at X = 0 with no card exiled with Valki. The ability resolved and did nothing,
 * the owner passed, and the AI was back at exactly the same priority with exactly the same choice, which it made again;
 * Nissa never resolved. Any no-op the AI likes (an ability that chooses nothing, a pump of +0/+0, an effect that undoes
 * itself) can do this, under any chooser: Forge AI, the look-ahead, the policy seat, a host over the bridge.
 *
 * <p>The rule, at the one place every AI priority decision passes ({@link PhaseHandler#mainLoopStep}): when an AI player
 * chooses an action, the guard remembers it with a fingerprint of the game state it was chosen in. When the same player
 * later chooses the same action ({@link #actionKey}: the host card, the ability's description and X) in a state with the
 * same fingerprint, the action led back to where it started -- it and everything after it changed nothing -- so the
 * player passes instead (always legal, and the same outcome as the action that changes nothing). Each action is tried
 * at most once per distinct state; a loop through it is cut at its second turn. Only an action whose play changed the
 * state is remembered: a failed attempt that was rolled back (Forge AI retries those itself, up to its own bound) is not.
 * A free loop that does change something, e.g. the storm count of a recast loop, is left to the guards that bound it
 * (AiRecastGuard, preventRunAwayActivations).
 *
 * <p>The fingerprint ({@link #fingerprint}) is the game state as play sees it: turn, step, the active and priority
 * players, each player's life, counters, mana pool, zones (card ids in order) and this turn's lands played, spells cast
 * (a storm count is progress), cards drawn and life gained and lost, each permanent's controller, tapped,
 * sick, phased and face-down flags, counters, damage, power, toughness, state, attachments and choices, the stack
 * (source, activator, description, targets), combat, the monarch and the initiative. It leaves out what an action
 * changes without progress: stack instance ids, timestamps, the log, and counts of abilities activated. A state it
 * cannot read gives no fingerprint, and then the action is allowed.
 *
 * <p>A game where no AI player returns to a state with the action it chose there decides exactly as without the guard.
 * It holds its memory for the current turn only, draws no random numbers, creates no ids and changes nothing in the
 * game. A human player is never guarded. System property {@code forge.ai.noProgressGuard=off} turns it off (reproduces
 * the loop). The first stop of an action in a turn writes one {@code [ai-no-progress]} line to stderr; it names the
 * card only when the card is face up in a public zone.
 */
public final class NoProgressGuard {
    private static volatile boolean enabled = !"off".equals(System.getProperty("forge.ai.noProgressGuard"));

    private int turn = Integer.MIN_VALUE;
    /** fingerprint (with the player) -> the actions that player chose in that state, this turn. */
    private final Map<String, Set<String>> chosenIn = new HashMap<>();
    private final Set<String> logged = new HashSet<>();
    private int stops;

    /** Tests and benches only. */
    public static void setEnabled(final boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** How many actions this guard turned into a pass (tests, diagnostics). */
    public int stops() {
        return stops;
    }

    /** The action {@link #check} let through, remembered by {@link #played} once it is actually played. */
    private Player pendingPlayer;
    private String pendingFp;
    private String pendingKey;

    /**
     * {@code chosen}, or null (pass) when {@code player} played the same action in this same state before. Call only for
     * an AI player's non-pass choice, then {@link #played} if the action was played (an action that failed to be played
     * and was rolled back is not remembered: the AI may try it again, as before).
     */
    public List<SpellAbility> check(final Game game, final Player player, final List<SpellAbility> chosen) {
        pendingPlayer = null;
        pendingFp = null;
        pendingKey = null;
        if (!enabled || chosen == null || chosen.isEmpty() || chosen.get(0) == null) {
            return chosen;
        }
        final int now = game.getPhaseHandler().getTurn();
        if (now != turn) {
            turn = now;
            chosenIn.clear();
            logged.clear();
        }
        final String fp;
        try {
            fp = "P" + player.getId() + "\n" + fingerprint(game);
        } catch (RuntimeException unreadable) {
            return chosen;
        }
        final String key = actionKey(chosen);
        final Set<String> tried = chosenIn.get(fp);
        if (tried == null || !tried.contains(key)) {
            pendingPlayer = player;
            pendingFp = fp;
            pendingKey = key;
            return chosen;
        }
        stops++;
        if (logged.add(key)) {
            System.err.println(logLine(game, player, chosen.get(0)));
        }
        return null;
    }

    /**
     * {@code player} played the action {@link #check} just let through: remember it with the state it was chosen in,
     * if playing it changed that state (put it on the stack, paid a cost, moved a card). An attempt that changed
     * nothing -- a payment or target choice that failed and was rolled back; Forge AI's controller reports it played
     * either way -- is not remembered, so Forge's own retry behaviour is untouched.
     */
    public void played(final Game game, final Player player) {
        try {
            if (pendingKey != null && player != null && player.equals(pendingPlayer)
                    && !pendingFp.equals("P" + player.getId() + "\n" + fingerprint(game))) {
                chosenIn.computeIfAbsent(pendingFp, k -> new HashSet<>()).add(pendingKey);
            }
        } catch (RuntimeException unreadable) {
            // not remembered: the action stays allowed
        } finally {
            pendingPlayer = null;
            pendingFp = null;
            pendingKey = null;
        }
    }

    /** The action: each chosen ability's host card id, description and announced X. */
    static String actionKey(final List<SpellAbility> chosen) {
        final StringBuilder sb = new StringBuilder();
        for (SpellAbility sa : chosen) {
            final Card host = sa.getHostCard();
            sb.append(host == null ? -1 : host.getId()).append('|').append(sa.getDescription())
                    .append("|X=").append(sa.getXManaCostPaid()).append(sa.isSpell() ? "|spell" : "|ability").append(';');
        }
        return sb.toString();
    }

    static String logLine(final Game game, final Player player, final SpellAbility sa) {
        final Card host = sa.getHostCard();
        final StringBuilder sb = new StringBuilder("[ai-no-progress] {\"player\":").append(player.getId())
                .append(",\"turn\":").append(game.getPhaseHandler().getTurn())
                .append(",\"phase\":\"").append(game.getPhaseHandler().getPhase()).append('"')
                .append(",\"card\":").append(host == null ? -1 : host.getId());
        if (host != null && !host.isFaceDown() && (host.isInZone(ZoneType.Battlefield) || host.isInZone(ZoneType.Graveyard)
                || host.isInZone(ZoneType.Stack) || host.isInZone(ZoneType.Exile))) {
            sb.append(",\"name\":\"").append(host.getName().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(",\"why\":\"the state it was chosen in came back unchanged: pass\"}").toString();
    }

    /** The game state as play sees it (see the class comment). Reads only. */
    public static String fingerprint(final Game game) {
        final StringBuilder sb = new StringBuilder();
        final PhaseHandler ph = game.getPhaseHandler();
        sb.append("T").append(ph.getTurn()).append(' ').append(ph.getPhase())
                .append(" A").append(id(ph.getPlayerTurn())).append(" PR").append(id(ph.getPriorityPlayer()))
                .append(" M").append(id(game.getMonarch())).append(" I").append(id(game.getHasInitiative())).append('\n');
        for (Player p : game.getPlayers()) {
            sb.append("P").append(p.getId()).append(" life=").append(p.getLife()).append(" lost=").append(p.hasLost())
                    .append(" lands=").append(p.getLandsPlayedThisTurn()).append(" cast=").append(p.getSpellsCastThisTurn())
                    .append(" drawn=").append(p.getNumDrawnThisTurn()).append(" gained=").append(p.getLifeGainedThisTurn())
                    .append(" lostLife=").append(p.getLifeLostThisTurn()).append(" c=").append(counters(p))
                    .append(" pool=[");
            for (var mana : p.getManaPool()) {
                sb.append(mana).append(',');
            }
            sb.append("]\n");
            for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile,
                    ZoneType.Command, ZoneType.Sideboard}) {
                sb.append(' ').append(z).append('=');
                for (Card c : p.getCardsIn(z)) {
                    sb.append(c.getId()).append(c.isFaceDown() ? "d" : "").append(',');
                }
                sb.append('\n');
            }
            sb.append(" bf=");
            for (Card c : p.getCardsIn(ZoneType.Battlefield, false)) {
                sb.append(c.getId()).append('{').append(c.getName()).append(' ').append(c.getCurrentStateName())
                        .append(" ctl").append(id(c.getController()))
                        .append(c.isTapped() ? " T" : " U").append(c.isSick() ? " sick" : "")
                        .append(c.isPhasedOut() ? " phased" : "").append(c.isFaceDown() ? " down" : "")
                        .append(" c").append(counters(c)).append(" d").append(c.getDamage());
                if (c.isCreature()) {
                    sb.append(' ').append(c.getNetPower()).append('/').append(c.getNetToughness());
                }
                final GameEntity on = c.getEntityAttachedTo();
                if (on != null) {
                    sb.append(" on").append(on instanceof Card ? "C" + ((Card) on).getId() : "P" + on.getId());
                }
                final List<Integer> chosenCards = new ArrayList<>();
                for (Card x : c.getChosenCards()) {
                    chosenCards.add(x.getId());
                }
                if (!chosenCards.isEmpty()) {
                    sb.append(" ch").append(chosenCards);
                }
                if (c.getNamedCard() != null && !c.getNamedCard().isEmpty()) {
                    sb.append(" nm").append(c.getNamedCard());
                }
                if (c.getChosenType() != null && !c.getChosenType().isEmpty()) {
                    sb.append(" ty").append(c.getChosenType());
                }
                sb.append(" r").append(c.getRememberedCount()).append("},");
            }
            sb.append('\n');
        }
        sb.append("S=");
        for (SpellAbilityStackInstance si : game.getStack()) {
            sb.append(id(si.getSourceCard())).append(':').append(id(si.getActivatingPlayer())).append(':')
                    .append(si.getStackDescription());
            if (si.getTargetChoices() != null) {
                sb.append(" ->");
                for (Object o : si.getTargetChoices()) {
                    sb.append(o instanceof GameEntity ? ((GameEntity) o).getId() : String.valueOf(o)).append(',');
                }
            }
            sb.append(';');
        }
        sb.append('\n');
        final Combat combat = ph.getCombat();
        if (combat != null) {
            sb.append("C=");
            for (Card a : combat.getAttackers()) {
                sb.append(a.getId()).append('>').append(id(combat.getDefenderByAttacker(a))).append('[');
                for (Card b : combat.getBlockers(a)) {
                    sb.append(b.getId()).append(',');
                }
                sb.append("],");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String id(final Object o) {
        if (o == null) {
            return "-";
        }
        if (o instanceof Card) {
            return "C" + ((Card) o).getId();
        }
        if (o instanceof GameEntity) {
            return String.valueOf(((GameEntity) o).getId());
        }
        return String.valueOf(o);
    }

    private static String counters(final GameEntity e) {
        final Multiset<CounterType> m = e.getCounters();
        if (m == null || m.isEmpty()) {
            return "{}";
        }
        final Map<String, Integer> sorted = new TreeMap<>();
        for (Multiset.Entry<CounterType> en : m.entrySet()) {
            sorted.put(String.valueOf(en.getElement()), en.getCount());
        }
        return sorted.toString();
    }
}
