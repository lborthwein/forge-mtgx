package forge.bench;

import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;

/**
 * Lane block-search-1019: the non-GUI entry points (the interactive server, the bench and RL actor mains, the review
 * replay, which runs the interactive server) never play Forge's sounds or music. They run headful on the Studio with a
 * fresh {@code user.home} and no {@code forge.preferences}, so UI_ENABLE_SOUNDS and UI_ENABLE_MUSIC defaulted to true and
 * Forge's sound effects (its crash alert's among them) played out of the host's speakers. {@code -Dforge.sound=on} keeps
 * the preferences as they are. A preference only: no game logic reads it. The same call makes BugReporter log only
 * (the crash alert's sound came from its dialog in a headful JVM).
 */
public final class HeadlessSound {
    private HeadlessSound() {
    }

    /**
     * Sounds and music off in {@code prefs} unless {@code -Dforge.sound=on}; and always, crash and bug reports only
     * logged ({@link forge.gui.error.BugReporter#setLogOnly}: no dialog, no alert sound, no Sentry upload).
     */
    public static void silence(final ForgePreferences prefs) {
        forge.gui.error.BugReporter.setLogOnly(true);
        if ("on".equals(System.getProperty("forge.sound"))) {
            return;
        }
        prefs.setPref(FPref.UI_ENABLE_SOUNDS, false);
        prefs.setPref(FPref.UI_ENABLE_MUSIC, false);
    }
}
