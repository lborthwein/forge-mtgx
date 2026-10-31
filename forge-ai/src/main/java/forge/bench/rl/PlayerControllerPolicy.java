package forge.bench.rl;

import java.util.List;

import forge.LobbyPlayer;
import forge.ai.simulation.LookaheadSearch;
import forge.bench.BenchSession;
import forge.bench.CallCounter;
import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Live play (lane live-sc-1009): the controller of {@link LobbyPlayerPolicy}. While the seat is healthy it is the read's
 * bridge seat (the policy and its look-ahead answer, Forge AI decides the rest). Once {@link RlLiveSeat} has degraded,
 * the bridge delegates every ask to Forge AI, and a priority decision goes through the live K8 look-ahead over Forge
 * AI's answer, as {@link forge.ai.simulation.PlayerControllerLookahead} does: the live K8 seat's behaviour for the rest
 * of the game. Only in the live game the seat was bound to.
 */
public class PlayerControllerPolicy extends PlayerControllerBridge {
    private final RlLiveSeat live;

    public PlayerControllerPolicy(final Game game, final Player p, final LobbyPlayer lp, final BenchSession session,
            final int seat, final CallCounter counters, final RlLiveSeat live) {
        super(game, p, lp, session, BenchSession.Mode.BRIDGE, seat, counters);
        this.live = live;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        final long t0 = System.nanoTime();
        final List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
        if (getGame() != live.liveGame()) {
            return chosen;
        }
        if (!live.degraded()) {
            // lane live-ai-quality-1030 (owner 2026-10-31, "extend to policy AI"): the policy's answer through the dud-cast
            // guard; null (dudCast off in the live K8 spec, the default) = the answer as chosen
            return guardPolicy(chosen);
        }
        final LookaheadSearch k8 = live.k8();
        if (k8 == null) {
            return guardPolicy(chosen);
        }
        // degraded (possibly during this very ask): `chosen` is Forge AI's own answer
        k8.noteForgeNanos(System.nanoTime() - t0);
        final List<SpellAbility> answer = k8.decide(this, chosen);
        // the degraded K8 search goes through the guard as the pure K8 seat's does (PlayerControllerLookahead)
        return k8.getConfig().dudCast == forge.ai.AiFixes.Mode.OFF ? answer
                : forge.ai.simulation.DudCast.guard(k8, this, answer);
    }

    /**
     * The guard over the healthy seat's answer (lane live-ai-quality-1030). On a veto the seat's decision line says so
     * ({@code "vetoed"}, {@code "playedBy"}) and its answerer marks the decision overridden (review of #159, S2-a); the
     * host's AI tape wraps this controller, so it records the answer returned here, the one played.
     */
    List<SpellAbility> guardPolicy(final List<SpellAbility> chosen) {
        final LookaheadSearch guard = live.dudGuard();
        if (guard == null) {
            return chosen;
        }
        final List<SpellAbility> played = forge.ai.simulation.DudCast.guard(guard, this, chosen);
        final boolean vetoed = played != chosen;
        live.guarded(getPlayer(), vetoed, !vetoed ? "policy" : played == null || played.isEmpty() ? "pass" : "forge", played);
        return played;
    }
}
