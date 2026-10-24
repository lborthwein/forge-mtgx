/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2013  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.ai;

import forge.LobbyPlayer;
import forge.game.player.Player;
import forge.util.Aggregates;
import forge.util.FileUtil;
import forge.util.TextUtil;
import org.apache.commons.lang3.ArrayUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds default AI personality profile values in an enum.
 * Loads profile from the given text file when setProfile is called.
 * If a requested value is not loaded from a profile, default is returned.
 * 
 * @author Forge
 * @version $Id: AIProfile.java 20169 2013-03-08 08:24:17Z Agetian $
 */
public class AiProfileUtil {
    private static Map<String, Map<AiProps, String>> loadedProfiles = new HashMap<>();

    private static String AI_PROFILE_DIR;
    private static final String AI_PROFILE_EXT = ".ai";

    public static final String AI_PROFILE_RANDOM_MATCH = "Random (Every Match)";
    public static final String AI_PROFILE_RANDOM_DUEL = "Random (Every Game)";

    public enum AISideboardingMode {
        Off,
        AI,
        HumanForAI;

        public static AISideboardingMode normalizedValueOf(String value) {
            return valueOf(value.replace(" ", ""));
        }
    }

    private static AISideboardingMode aiSideboardingMode = AISideboardingMode.Off;

    public static AISideboardingMode getAISideboardingMode() {
        return aiSideboardingMode;
    }

    public static void setAiSideboardingMode(AISideboardingMode mode) {
        aiSideboardingMode = mode;
    }

    /** Builds an AI profile file name with full relative 
     * path based on the profile name. 
     * @param profileName the name of the profile.
     * @return the full relative path and file name for the given profile.
     */
    private static String buildFileName(final String profileName) {
        return TextUtil.concatNoSpace(AI_PROFILE_DIR, "/", profileName, AI_PROFILE_EXT);
    }
    
    /**
     * Load all profiles
     */
    public static final void loadAllProfiles(String aiProfileDir) {
        AI_PROFILE_DIR = aiProfileDir;

        loadedProfiles.clear();
        List<String> availableProfiles = getAvailableProfiles();
        for (String profile : availableProfiles) {
            loadedProfiles.put(profile, loadProfile(profile));
        }
        registerBuiltInProfiles();
    }

    /**
     * mtgx (lane combo-ai-port-1009): profiles defined in code as a shipped profile plus fixed keys. They need no file
     * under res/ai, so a res directory shared with older jars never gains a key those jars cannot parse
     * ({@code loadProfile} throws on an unknown key). Not in {@link #getAvailableProfiles()} (GUI lists and the random
     * profile draw are unchanged); harnesses accept them through {@link #isKnownProfile}. A file of the same name, if a
     * res directory ever ships one, wins.
     */
    public static final String CUBE_COMBO_PROFILE = "CubeCombo";

    /**
     * mtgx (lane doomsday-solver-1012): the cube combo seat with the general forced-win solver in front of its plans
     * (the lineage's Doomsday plan is replaced by the solver), and Forge AI with the solver alone.
     */
    public static final String CUBE_COMBO_SOLVER_PROFILE = "CubeComboSolver";
    public static final String FORCED_WIN_PROFILE = "ForcedWin";
    /** BVW research-only generator; existing profiles retain their prior behavior. */
    public static final String HONEST_FORCED_WIN_PROFILE = "HonestForcedWin";
    /**
     * bvw-step1-build-1024: the BVW generator of the step-1 lineage: HonestForcedWin on host-independent counter budgets
     * (no wall clock; caps calibrated on the step-0 pilot games, where the ms budgets bound 3 of 7,447 decisions on the
     * Studio) with adoptAttack (an adopted line whose first step is our attack is declared). HonestForcedWin is unchanged.
     */
    public static final String HONEST_FORCED_WIN_COUNTED_PROFILE = "HonestForcedWinCounted";
    /** bvw-step1-build-1024: the counted generator's solver spec (FORCED_WIN_SPEC). */
    public static final String HONEST_FORCED_WIN_COUNTED_SPEC = "hideOwnLibrary=true,defense=false,worlds=8,cycles=true,"
            + "threshold=1.0,budget=counter,engineSteps=6000,evalSteps=1750,adoptAttack=true";
    /** mtgx (lane solver-gaps-1015, Part B): Forge AI with likely-win lines alone (no forced-win search or defense). */
    public static final String LIKELY_WIN_PROFILE = "LikelyWin";

    private static void registerBuiltInProfiles() {
        final Map<AiProps, String> base = loadedProfiles.get("Default");
        if (base != null && !loadedProfiles.containsKey(CUBE_COMBO_PROFILE)) {
            final Map<AiProps, String> combo = new HashMap<>(base);
            combo.put(AiProps.CUBE_COMBO_PLANS, "true");
            loadedProfiles.put(CUBE_COMBO_PROFILE, combo);
        }
        if (base != null && !loadedProfiles.containsKey(CUBE_COMBO_SOLVER_PROFILE)) {
            final Map<AiProps, String> solver = new HashMap<>(base);
            solver.put(AiProps.CUBE_COMBO_PLANS, "true");
            solver.put(AiProps.FORCED_WIN_SOLVER, "true");
            loadedProfiles.put(CUBE_COMBO_SOLVER_PROFILE, solver);
        }
        if (base != null && !loadedProfiles.containsKey(FORCED_WIN_PROFILE)) {
            final Map<AiProps, String> alone = new HashMap<>(base);
            alone.put(AiProps.CUBE_COMBO_PLANS, "true");
            alone.put(AiProps.CUBE_COMBO_LINEAGE, "false");
            alone.put(AiProps.FORCED_WIN_SOLVER, "true");
            loadedProfiles.put(FORCED_WIN_PROFILE, alone);
        }
        if (base != null && !loadedProfiles.containsKey(LIKELY_WIN_PROFILE)) {
            final Map<AiProps, String> likely = new HashMap<>(base);
            likely.put(AiProps.CUBE_COMBO_PLANS, "true");
            likely.put(AiProps.CUBE_COMBO_LINEAGE, "false");
            likely.put(AiProps.LIKELY_WIN_LINES, "true");
            loadedProfiles.put(LIKELY_WIN_PROFILE, likely);
        }
        if (base != null && !loadedProfiles.containsKey(HONEST_FORCED_WIN_PROFILE)) {
            final Map<AiProps, String> honest = new HashMap<>(base);
            honest.put(AiProps.CUBE_COMBO_PLANS, "true");
            honest.put(AiProps.CUBE_COMBO_LINEAGE, "false");
            honest.put(AiProps.FORCED_WIN_SOLVER, "true");
            honest.put(AiProps.FORCED_WIN_HONEST, "true");
            honest.put(AiProps.FORCED_WIN_SPEC,
                    "hideOwnLibrary=true,defense=false,worlds=8,cycles=true,threshold=1.0");
            loadedProfiles.put(HONEST_FORCED_WIN_PROFILE, honest);
        }
        if (base != null && !loadedProfiles.containsKey(HONEST_FORCED_WIN_COUNTED_PROFILE)) {
            final Map<AiProps, String> counted = new HashMap<>(base);
            counted.put(AiProps.CUBE_COMBO_PLANS, "true");
            counted.put(AiProps.CUBE_COMBO_LINEAGE, "false");
            counted.put(AiProps.FORCED_WIN_SOLVER, "true");
            counted.put(AiProps.FORCED_WIN_HONEST, "true");
            counted.put(AiProps.FORCED_WIN_SPEC, HONEST_FORCED_WIN_COUNTED_SPEC);
            loadedProfiles.put(HONEST_FORCED_WIN_COUNTED_PROFILE, counted);
        }
    }

    /** A profile a seat may name: a shipped file, or a built-in profile (mtgx, {@link #registerBuiltInProfiles}). */
    public static boolean isKnownProfile(final String profile) {
        return getAvailableProfiles().contains(profile) || loadedProfiles.containsKey(profile);
    }

    /** True when this lobby player's AI profile turns on the cube combo policy ({@link AiProps#CUBE_COMBO_PLANS}). */
    public static boolean cubeComboPlans(final LobbyPlayer p) {
        return Boolean.parseBoolean(getAIProp(p, AiProps.CUBE_COMBO_PLANS));
    }
    
    /**
     * Load a single profile.
     * @param profileName a profile to load.
     */
    private static Map<AiProps, String> loadProfile(final String profileName) {
        Map<AiProps, String> profileMap = new HashMap<>();

        List<String> lines = FileUtil.readFile(buildFileName(profileName));
        for (String line : lines) {
            if (line.startsWith("#") || (line.length() == 0)) {
                continue;
            }

            final String[] split = line.split("=");

            if (split.length == 2) {
                profileMap.put(AiProps.valueOf(split[0]), split[1]);
            } else if (split.length == 1 && line.endsWith("=")) {
                profileMap.put(AiProps.valueOf(split[0]), "");
            }
        }

        return profileMap;
    }

    public static String getProperty(final Player p, final AiProps propName) {
        String prop = AiProfileUtil.getAIProp(p.getLobbyPlayer(), propName);

        if (prop == null || prop.isEmpty()) {
            // TODO if p is human try to predict some values from previous plays or something
            return propName.getDefault();
        }

        return prop;
    }
    public static int getIntProperty(final Player p, final AiProps propName) {
        return Integer.parseInt(getProperty(p, propName));
    }
    public static boolean getBoolProperty(final Player p, final AiProps propName) {
        return Boolean.parseBoolean(getProperty(p, propName));
    }

    /**
     * Returns an AI property value for the current profile.
     * 
     * @param fp0 an AI property.
     * @return String
     */
    public static String getAIProp(final LobbyPlayer p, final AiProps fp0) {
        String val = null;
        if (!(p instanceof LobbyPlayerAi))
            return "";
        String profile = ((LobbyPlayerAi) p).getAiProfile();
       
        if (loadedProfiles.get(profile) != null) {
            val = loadedProfiles.get(profile).get(fp0);
        }
        if (val == null) { val = fp0.getDefault(); }

        return val;
    }

    /**
     * Returns an array of strings containing all available profiles.
     * @return ArrayList<String> - an array of strings containing all 
     * available profiles.
     */
    public static List<String> getAvailableProfiles() {
        final List<String> availableProfiles = new ArrayList<>();

        final File dir = new File(AI_PROFILE_DIR);
        final String[] children = dir.list();
        if (children == null) {
            System.err.println("AIProfile > can't find AI profile directory!");
        } else {
            for (String child : children) {
                if (child.endsWith(AI_PROFILE_EXT)) {
                    availableProfiles.add(child.substring(0, child.length() - AI_PROFILE_EXT.length()));
                }
            }
        }

        return availableProfiles;
    }
    
    /**
     * Returns an array of strings containing all available profiles including 
     * the special "Random" profiles.
     * @return ArrayList<String> - an array list of strings containing all 
     * available profiles including special random profile tags.
     */
    public static List<String> getProfilesDisplayList() {
        final List<String> availableProfiles = new ArrayList<>();
        availableProfiles.add(AI_PROFILE_RANDOM_MATCH);
        availableProfiles.add(AI_PROFILE_RANDOM_DUEL);
        availableProfiles.addAll(getAvailableProfiles());

        return availableProfiles;
    }
    
    public static String[] getProfilesArray() {
        return getProfilesDisplayList().toArray(ArrayUtils.EMPTY_STRING_ARRAY);
    }

    /**
     * Returns a random personality from the currently available ones.
     * @return String - a string containing a random profile from all the
     * currently available ones.
     */
    public static String getRandomProfile() {
        return Aggregates.random(getAvailableProfiles());
    }

    /**
     * Simple class test facility for AiProfileUtil.
     *-/
    public static void selfTest() {
        final LobbyPlayer activePlayer = Singletons.getControl().getPlayer().getLobbyPlayer();
        System.out.println(String.format("Current profile = %s", activePlayer.getAiProfile()));
        ArrayList<String> profiles = getAvailableProfiles();
        System.out.println(String.format("Available profiles: %s", profiles));
        if (profiles.size() > 0) {
            System.out.println(String.format("Loading all profiles..."));
            loadAllProfiles();
            System.out.println(String.format("Setting profile %s...", profiles.get(0)));
            activePlayer.setAiProfile(profiles.get(0));
            for (AiProps property : AiProps.values()) {
                System.out.println(String.format("%s = %s", property, getAIProp(activePlayer, property)));
            }
            String randomProfile = getRandomProfile();
            System.out.println(String.format("Loading random profile %s...", randomProfile));
            activePlayer.setAiProfile(randomProfile);
            for (AiProps property : AiProps.values()) {
                System.out.println(String.format("%s = %s", property, getAIProp(activePlayer, property)));
            }
        }
    }
    */
}
