package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.lang.reflect.Proxy;
import java.util.*;

/** Bounded diagnostic of catalogue family S using entirely native decisions.
 * No scripted action, host answer, or policy change. Repeated own turns are
 * recorded, not described as mathematical infinity or sampled strength. */
public final class CubeExtraTurnExecutionSmoke {
    private record Entry(String name, ZoneType zone) {}
    private static final Set<String> loaded = new HashSet<>();
    private static forge.item.PaperCard paper(String name) {
        if (loaded.add(name)) StaticData.instance().attemptToLoadCard(name);
        return Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), name);
    }
    private static Deck deck(List<Entry> entries) {
        Deck deck = new Deck();
        for (Entry entry : entries) deck.getMain().add(paper(entry.name()), 1);
        return deck;
    }
    private static void populate(Player player, List<Entry> entries) {
        for (Entry entry : entries) {
            Card card = Card.fromPaperCard(paper(entry.name()), player);
            card.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(entry.zone()).add(card); card.setSickness(false);
        }
    }
    private static void run(int seat, String engine, String turnSpell, String control, boolean candidate) {
        List<Entry> own = new ArrayList<>(), other = new ArrayList<>();
        own.add(new Entry(control.equals("no-witness") ? "Forest" : "Eternal Witness", ZoneType.Battlefield));
        own.add(new Entry(control.equals("no-engine") ? "Forest" : engine,
                engine.equals("Ephemerate") ? ZoneType.Hand : ZoneType.Battlefield));
        own.add(new Entry(control.equals("no-turn-spell") ? "Forest" : turnSpell, ZoneType.Hand));
        own.add(new Entry("Plains", ZoneType.Battlefield));
        for (int i = 0; i < 5; i++) own.add(new Entry("Island", ZoneType.Battlefield));
        for (int i = 0; i < 20; i++) own.add(new Entry("Forest", ZoneType.Library));
        while (own.size() < 40) own.add(new Entry("Forest", ZoneType.Exile));
        for (int i = 0; i < 30; i++) other.add(new Entry("Forest", ZoneType.Library));
        while (other.size() < 40) other.add(new Entry("Forest", ZoneType.Exile));
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat ? own : other)).setPlayer(candidate && s == seat
                ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s)
                : GamePlayerUtil.createAiPlayer("Default-" + s, s, 0, null, "Default")));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR); rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "Extra-turn native diagnostic").createGame(); game.setAge(GameStage.Play);
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        game.getPhaseHandler().setupFirstTurn(player, () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player));
        populate(player, own); populate(opponent, other); opponent.setLife(40, null);
        game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(830913L + seat);
        String key = "seat=" + seat + " engine=" + engine.replace(' ', '_') + " spell=" + turnSpell.replace(' ', '_')
                + " control=" + control + " candidate=" + candidate + " policy=" + forge.ai.CubeComboAi.VERSION;
        System.out.println("EXTRA_TURN_FIXTURE " + key);
        Set<Integer> seen = new HashSet<>();
        int steps = 0, turnCasts = 0, engineActions = 0, returns = 0, ownTurns = 0, opponentTurns = 0;
        int lastTurn = -1, streak = 0, longest = 0;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 12 && steps < 4000) {
            int turn = game.getPhaseHandler().getTurn();
            if (turn != lastTurn) {
                boolean ours = game.getPhaseHandler().getPlayerTurn() == player;
                if (ours) {ownTurns++; streak++; longest = Math.max(longest, streak);} else {opponentTurns++; streak = 0;}
                System.out.println("EXTRA_TURN_TURN turn=" + turn + " ours=" + ours + " hand="
                        + player.getCardsIn(ZoneType.Hand) + " graveyard=" + player.getCardsIn(ZoneType.Graveyard));
                lastTurn = turn;
            }
            steps++; game.getPhaseHandler().mainLoopStep();
            for (var item : game.getStack()) if (seen.add(item.getId())) {
                var ability = item.getSpellAbility(); if (ability.getActivatingPlayer() != player) continue;
                String name = ability.getHostCard().getName();
                if (ability.isSpell() && !ability.isCopied() && name.equals(turnSpell)) turnCasts++;
                if (name.equals(engine)) engineActions++;
                if (name.equals("Eternal Witness") && ability.getApi() == forge.game.ability.ApiType.ChangeZone) returns++;
                System.out.println("EXTRA_TURN_STACK turn=" + game.getPhaseHandler().getTurn() + " card=" + name
                        + " api=" + ability.getApi() + " targets=" + ability.getTargets());
            }
        }
        System.out.println("EXTRA_TURN_RESULT " + key + " steps=" + steps + " turnCasts=" + turnCasts
                + " engineActions=" + engineActions + " returns=" + returns + " ownTurns=" + ownTurns
                + " opponentTurns=" + opponentTurns + " longestOwnStreak=" + longest + " won=" + player.hasWon()
                + " gameOver=" + game.isGameOver() + " life=" + player.getLife() + " opponentLife=" + opponent.getLife()
                + " budgetExhausted=" + (steps >= 4000));
        if (steps >= 4000) throw new AssertionError("Diagnostic step budget exhausted: " + key);
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (p, m, v) -> switch (m.getName()) {
                case "getAssetsDir" -> args[0] + "/forge-gui/";
                case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                case "getCurrentVersion" -> "extra-turn-diagnostic-v1";
                default -> throw new AssertionError(m.getName()); }));
            FModel.initialize(null, p -> {p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); p.setPref(FPref.UI_LANGUAGE, "en-US"); return null;});
            boolean candidate = args.length < 2 || !args[1].equals("baseline");
            int cases = 0;
            for (int seat = 0; seat < 2; seat++) for (String engine : List.of("Ephemerate", "Soulherder", "Kiki-Jiki, Mirror Breaker"))
                for (String spell : List.of("Time Walk", "Time Warp")) for (String control : List.of("none", "no-witness", "no-engine", "no-turn-spell")) {
                    run(seat, engine, spell, control, candidate); cases++;
                }
            System.out.println("EXTRA_TURN_SUITE_COMPLETE cases=" + cases + " candidate=" + candidate);
        } catch (Throwable failure) {failure.printStackTrace(); System.exit(1);}
    }
}
