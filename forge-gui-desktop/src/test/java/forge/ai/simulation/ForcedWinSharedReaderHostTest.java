package forge.ai.simulation;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;

import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.util.GameContext;

/**
 * shared-host-live-1113 (policy-seat audit): the defense's "JVM-wide" opponent reader ({@code sharedReader} without
 * {@code readerPerGame}) is per game in a multi-game host, so one game's probes never reach another's; a lone JVM keeps
 * its one static reader.
 */
public class ForcedWinSharedReaderHostTest {
    @AfterMethod(alwaysRun = true)
    public void stopHosting() {
        GameContext.stopHostingForTests();
    }

    @Test
    public void aLoneJvmKeepsItsOneReader() {
        final ForcedWinModel.Reader a = ForcedWinSolver.sharedOppReader();
        assertNotNull(a);
        assertSame(ForcedWinSolver.sharedOppReader(), a);
    }

    @Test
    public void hostedGamesEachGetTheirOwnReaderAndKeepIt() throws Exception {
        final ForcedWinModel.Reader lone = ForcedWinSolver.sharedOppReader();
        GameContext.enableHosting();
        final AtomicReference<ForcedWinModel.Reader> a1 = new AtomicReference<>();
        final AtomicReference<ForcedWinModel.Reader> a2 = new AtomicReference<>();
        final AtomicReference<ForcedWinModel.Reader> b1 = new AtomicReference<>();
        final Thread a = new Thread(() -> {
            GameContext.open("a");
            a1.set(ForcedWinSolver.sharedOppReader());
            a2.set(ForcedWinSolver.sharedOppReader());
        });
        final Thread b = new Thread(() -> {
            GameContext.open("b");
            b1.set(ForcedWinSolver.sharedOppReader());
        });
        a.start();
        a.join();
        b.start();
        b.join();
        assertNotNull(a1.get());
        assertSame(a2.get(), a1.get(), "one game keeps its reader across its defenders");
        assertNotSame(b1.get(), a1.get(), "another game never reads the first game's reader");
        assertNotSame(a1.get(), lone, "never the static reader in a host");
        assertNotSame(ForcedWinSolver.sharedOppReader(), lone, "a host thread with no game: a reader nobody shares");
    }
}
