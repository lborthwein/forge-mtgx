package forge.bench;

import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;

/**
 * Lane block-search-1019: the non-GUI entry points (the interactive server, the bench and RL actor mains, the review
 * replay, which runs the interactive server) never play Forge's sounds or music. They run headful on the Studio with a
 * fresh {@code user.home} and no {@code forge.preferences}, so UI_ENABLE_SOUNDS and UI_ENABLE_MUSIC defaulted to true and
 * Forge's sound effects (its crash alert's among them) played out of the host's speakers. {@code -Dforge.sound=on} keeps
 * the preferences as they are. A preference only: no game logic reads it.
 */
public final class HeadlessSound {
    private HeadlessSound() {
    }

    /** Sounds and music off in {@code prefs} unless {@code -Dforge.sound=on}. */
    public static void silence(final ForgePreferences prefs) {
        if ("on".equals(System.getProperty("forge.sound"))) {
            return;
        }
        prefs.setPref(FPref.UI_ENABLE_SOUNDS, false);
        prefs.setPref(FPref.UI_ENABLE_MUSIC, false);
    }
}
