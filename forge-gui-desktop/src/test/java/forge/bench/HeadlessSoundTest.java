package forge.bench;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * Lane block-search-1019: the non-GUI entry points read UI_ENABLE_SOUNDS and UI_ENABLE_MUSIC as false (Forge's sounds,
 * its crash alert's among them, played out of the Studio's speakers from bench JVMs), unless {@code -Dforge.sound=on}.
 */
public class HeadlessSoundTest extends AITest {

    @Test
    public void soundsAndMusicReadFalseUnlessForgeSoundIsOn() {
        final ForgePreferences prefs = FModel.getPreferences();
        final String was = System.getProperty("forge.sound");
        try {
            System.clearProperty("forge.sound");
            prefs.setPref(FPref.UI_ENABLE_SOUNDS, true);
            prefs.setPref(FPref.UI_ENABLE_MUSIC, true);
            HeadlessSound.silence(prefs);
            assertFalse(prefs.getPrefBoolean(FPref.UI_ENABLE_SOUNDS), "sounds off headless");
            assertFalse(prefs.getPrefBoolean(FPref.UI_ENABLE_MUSIC), "music off headless");

            System.setProperty("forge.sound", "on");
            prefs.setPref(FPref.UI_ENABLE_SOUNDS, true);
            prefs.setPref(FPref.UI_ENABLE_MUSIC, true);
            HeadlessSound.silence(prefs);
            assertTrue(prefs.getPrefBoolean(FPref.UI_ENABLE_SOUNDS), "-Dforge.sound=on keeps them");
            assertTrue(prefs.getPrefBoolean(FPref.UI_ENABLE_MUSIC));
        } finally {
            if (was == null) {
                System.clearProperty("forge.sound");
            } else {
                System.setProperty("forge.sound", was);
            }
            prefs.setPref(FPref.UI_ENABLE_SOUNDS, false);
            prefs.setPref(FPref.UI_ENABLE_MUSIC, false);
        }
    }

    /** A headless entry point's crash report is logged and counted; it opens no window (and so plays no alert). */
    @Test
    public void aReportedExceptionOpensNoWindow() {
        final boolean was = forge.gui.error.BugReporter.isLogOnly();
        try {
            HeadlessSound.silence(FModel.getPreferences());
            org.testng.Assert.assertTrue(forge.gui.error.BugReporter.isLogOnly());
            final int windows = java.awt.Window.getWindows().length;
            final int before = forge.gui.error.BugReporter.reportCount();
            forge.gui.error.BugReporter.reportException(new IllegalStateException("block-search-1019 test"), "a test report");
            forge.gui.error.BugReporter.reportBug("a test bug report");
            org.testng.Assert.assertEquals(forge.gui.error.BugReporter.reportCount(), before + 2, "both counted");
            org.testng.Assert.assertEquals(java.awt.Window.getWindows().length, windows, "no window was opened");
        } finally {
            forge.gui.error.BugReporter.setLogOnly(was);
            FModel.getPreferences().setPref(FPref.UI_ENABLE_SOUNDS, false);
            FModel.getPreferences().setPref(FPref.UI_ENABLE_MUSIC, false);
        }
    }
}
