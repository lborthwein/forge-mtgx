package forge.ai;

import java.util.Set;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import org.tinylog.Logger;

public class LobbyPlayerAi extends LobbyPlayer implements IGameEntitiesFactory {

    private String aiProfile = "";
    private boolean rotateProfileEachGame;
    private AIOption option;
    /** mtgx Forge AI fixes of 2026-09-28 ({@link AiFixes}); OFF (upstream Forge AI) by default. */
    private AiFixes.Mode aiFixes0928 = AiFixes.Mode.OFF;
    private AiFixes.Counters aiFixesCounters;
    /** mtgx fair card naming of 2026-10-06 ({@link FairNaming}); OFF (upstream Forge AI) by default. */
    private AiFixes.Mode fairNaming = AiFixes.Mode.OFF;
    private FairNaming.Counters fairNamingCounters;

    public LobbyPlayerAi(String name, Set<AIOption> options) {
        super(name);
        if (options != null && !options.isEmpty()) {
            option = options.iterator().next();
        }
    }

    public void setAiProfile(String profileName) {
        Logger.debug("[AI Preferences] " + name + " using profile " + profileName);
        aiProfile = profileName;
    }
    public String getAiProfile() {
        return aiProfile;
    }

    public AiFixes.Mode getAiFixes0928() {
        return aiFixes0928;
    }

    public void setAiFixes0928(AiFixes.Mode mode) {
        this.aiFixes0928 = mode == null ? AiFixes.Mode.OFF : mode;
    }

    public AiFixes.Counters getAiFixesCounters() {
        return aiFixesCounters;
    }

    void setAiFixesCounters(AiFixes.Counters counters) {
        this.aiFixesCounters = counters;
    }

    public AiFixes.Mode getFairNaming() {
        return fairNaming;
    }

    public void setFairNaming(AiFixes.Mode mode) {
        this.fairNaming = mode == null ? AiFixes.Mode.OFF : mode;
    }

    public FairNaming.Counters getFairNamingCounters() {
        return fairNamingCounters;
    }

    void setFairNamingCounters(FairNaming.Counters counters) {
        this.fairNamingCounters = counters;
    }

    public void setRotateProfileEachGame(boolean rotateProfileEachGame) {
        this.rotateProfileEachGame = rotateProfileEachGame;
    }

    private PlayerControllerAi createControllerFor(Player ai) {
        PlayerControllerAi result = new PlayerControllerAi(ai.getGame(), ai, this);
        result.getAi().setUseSimulation(option);
        return result;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return createControllerFor(slave);
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player ai = new Player(getName(), game, id);
        ai.setFirstController(createControllerFor(ai));

        if (rotateProfileEachGame) {
            setAiProfile(AiProfileUtil.getRandomProfile());
        }
        return ai;
    }

    @Override
    public void hear(LobbyPlayer player, String message) { /* Local AI is deaf. */ }
}