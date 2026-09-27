package forge.gamemodes.match.input;

import java.lang.reflect.Field;

import org.mockito.Mockito;
import org.testng.annotations.Test;

import forge.gui.interfaces.IGuiGame;
import forge.player.PlayerControllerHuman;

/**
 * InputLockUI's delayed "waiting for actions" update checks that the lock is still the active input on the timer
 * thread, then runs on the EDT. If the human's next input (e.g. the mulligan confirm) was shown in between, the stale
 * update must not reset that input's buttons (seen in the interactive bridge: "InputConfirmMulligan exposed no human
 * controls" at the opening mulligan when the AI's mulligan took more than 500 ms).
 */
public class InputLockUIStaleUpdateTest {

    private static Runnable edtUpdate(InputLockUI lock) throws Exception {
        Field f = InputLockUI.class.getDeclaredField("showMessageFromEdt");
        f.setAccessible(true);
        return (Runnable) f.get(lock);
    }

    @Test
    public void staleUpdateLeavesTheNextInputsButtonsAlone() throws Exception {
        InputQueue queue = Mockito.mock(InputQueue.class);
        PlayerControllerHuman controller = Mockito.mock(PlayerControllerHuman.class);
        IGuiGame gui = Mockito.mock(IGuiGame.class);
        Mockito.when(controller.getGui()).thenReturn(gui);
        InputLockUI lock = new InputLockUI(queue, controller);
        Mockito.when(queue.getInput()).thenReturn(Mockito.mock(Input.class)); // another input is now active

        edtUpdate(lock).run();

        Mockito.verify(gui, Mockito.never()).updateButtons(Mockito.any(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyBoolean(), Mockito.anyBoolean(), Mockito.anyBoolean());
        Mockito.verify(gui, Mockito.never()).showPromptMessage(Mockito.any(), Mockito.anyString());
    }

    @Test
    public void activeLockStillShowsWaiting() throws Exception {
        InputQueue queue = Mockito.mock(InputQueue.class);
        PlayerControllerHuman controller = Mockito.mock(PlayerControllerHuman.class);
        IGuiGame gui = Mockito.mock(IGuiGame.class);
        Mockito.when(controller.getGui()).thenReturn(gui);
        Mockito.when(controller.mayAutoPass()).thenReturn(false);
        InputLockUI lock = new InputLockUI(queue, controller);
        Mockito.when(queue.getInput()).thenReturn(lock);

        edtUpdate(lock).run();

        Mockito.verify(gui).updateButtons(null, "", "", false, false, false);
        Mockito.verify(gui).showPromptMessage(Mockito.isNull(), Mockito.anyString());
    }
}
