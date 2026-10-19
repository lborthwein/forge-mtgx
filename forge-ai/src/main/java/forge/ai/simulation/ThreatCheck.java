package forge.ai.simulation;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import forge.game.Game;
import forge.game.player.Player;

/**
 * Lane search-bones-1015, (d) both seats searched alike: inside a search's world copy, at the opponent's main-phase
 * priority, the same proposal source the searching seat uses (the forced-win solver's width generator) runs from the
 * opponent's seat, on that world's cards (their hidden hand and library are the world's sample, so the check is over
 * the belief worlds of their hand and library). A line it finds is verified in that position as it stands ({@link
 * ForcedWinSolver.Config#exact}): it must win with our seat as Forge AI and against an adversary in our seat countering
 * each of its spells in turn, so whatever our candidate kept (a counterspell and the mana for it) or took (their key
 * piece) is what the verification plays with. A verified line makes the world a proven loss for our candidate.
 *
 * <p>One instance per searching seat and decision stream; the affordance reader (the probes' cache by card name) is
 * shared by every play-out thread (its card lookup is synchronized).
 */
public final class ThreatCheck implements LookaheadSearch.OppCheck {
    private final ForcedWinSolver.Config cfg;
    private final ForcedWinModel.Reader reader = new ForcedWinModel.Reader();
    public final AtomicInteger checks = new AtomicInteger(), found = new AtomicInteger(), wins = new AtomicInteger(),
            errors = new AtomicInteger();
    public final AtomicLong nanos = new AtomicLong();
    public volatile String lastWin;

    /** {@code cfg}: the opponent solver's config ({@code exact}, {@code act}=false and no defence are forced). */
    public ThreatCheck(ForcedWinSolver.Config cfg) {
        cfg.exact = true;
        cfg.act = false;
        cfg.defense = false;
        cfg.likely = false;
        this.cfg = cfg;
        this.reader.seed = cfg.seed;
    }

    @Override
    public boolean theyWin(Game g, Player me) {
        Player opp = null;
        for (Player o : me.getOpponents()) {
            if (opp != null) {
                return false;
            }
            opp = o;
        }
        if (opp == null || g.isGameOver()) {
            return false;
        }
        final long t0 = System.nanoTime();
        checks.incrementAndGet();
        try {
            final ForcedWinSolver s = new ForcedWinSolver(opp, cfg, reader);
            final List<ForcedWinSolver.Proposal> props = s.propose(g);
            if (!props.isEmpty()) {
                found.incrementAndGet();
            }
            for (ForcedWinSolver.Proposal p : props) {
                if (p.proven(1)) {
                    wins.incrementAndGet();
                    lastWin = p.text;
                    return true;
                }
            }
            return false;
        } catch (RuntimeException | StackOverflowError e) {
            errors.incrementAndGet();
            return false;
        } finally {
            nanos.addAndGet(System.nanoTime() - t0);
        }
    }

    public String summary() {
        return "checks=" + checks.get() + " linesFound=" + found.get() + " theyWin=" + wins.get() + " errors=" + errors.get()
                + " ms=" + nanos.get() / 1_000_000 + (lastWin == null ? "" : " lastWin=" + lastWin);
    }
}
