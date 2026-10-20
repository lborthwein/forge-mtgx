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
}
