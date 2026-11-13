package forge.gamemodes.match.input;

import forge.util.IHasForgeLog;
import forge.util.StateMachine;
import forge.gui.FThreads;
import forge.gui.error.BugReporter;
import forge.player.PlayerControllerHuman;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public abstract class InputSyncronizedBase extends InputBase implements InputSynchronized, IHasForgeLog {

    private static final long serialVersionUID = 8756177361251703052L;
    private final CountDownLatch cdlDone;

    /**
     * mtgx state-machines-1113 (survey M4-B1): why this input's latch was released. A release for game over carries
     * no answer, so a caller that loops on its input (TargetSelection) can read it as a cancel instead of asking again
     * on a prompt that nothing would ever release. The first release wins: a {@link #stop()} after a game-over release
     * keeps {@code GAME_OVER}, and a game over that lands while {@code stop()} runs, before the latch drops, wins too.
     */
    public enum Release { PENDING, STOPPED, GAME_OVER }

    /** The release table, traced under {@code -Dforge.state.trace=input-release}. */
    public static final StateMachine<Release> RELEASE_MACHINE = StateMachine.builder("input-release", Release.class)
            .edge(Release.PENDING, Release.STOPPED, Release.GAME_OVER)
            .doc("why an InputSyncronizedBase latch was released (first release wins)")
            .build();
    private static final AtomicReferenceFieldUpdater<InputSyncronizedBase, Release> RELEASE =
            AtomicReferenceFieldUpdater.newUpdater(InputSyncronizedBase.class, Release.class, "release");
    private volatile Release release = Release.PENDING;

    /** Why the latch was released ({@code PENDING} while it holds). */
    public final Release getRelease() {
        return release;
    }

    private void released(final Release why) {
        if (RELEASE.compareAndSet(this, Release.PENDING, why) && RELEASE_MACHINE.trace) {
            RELEASE_MACHINE.check(getClass().getSimpleName(), Release.PENDING, why, null);
        }
    }

    public InputSyncronizedBase(final PlayerControllerHuman controller) {
        super(controller);
        cdlDone = new CountDownLatch(1);
    }

    @Override
    public void awaitLatchRelease() {
        FThreads.assertExecutedByEdt(false);
        netLog.trace("awaitLatchRelease() starting on {}, thread = {}", this.getClass().getSimpleName(), Thread.currentThread().getName());
        try {
            cdlDone.await();
        } catch (final InterruptedException e) {
            BugReporter.reportException(e);
        }
        netLog.trace("awaitLatchRelease() UNBLOCKED on {}, thread = {}", this.getClass().getSimpleName(), Thread.currentThread().getName());
    }

    @Override
    public final void relaseLatchWhenGameIsOver() {
        released(Release.GAME_OVER);
        cdlDone.countDown();
    }

    public void showAndWait() {
        getController().getInputQueue().setInput(this);
        awaitLatchRelease();
    }

    @Override
    public final void stop() {
        netLog.trace("stop() called on {}, latch count before = {}", this.getClass().getSimpleName(), cdlDone.getCount());
        onStop();

        // ensure input won't accept any user actions.
        FThreads.invokeInEdtNowOrLater(this::setFinished);

        // thread irrelevant
        if (getController().getInputQueue().getInput() != null) {
            getController().getInputQueue().removeInput(InputSyncronizedBase.this);
        }
        released(Release.STOPPED);
        cdlDone.countDown();
        netLog.trace("stop() done, latch count after = {}", cdlDone.getCount());
    }

    protected void onStop() { }
}
