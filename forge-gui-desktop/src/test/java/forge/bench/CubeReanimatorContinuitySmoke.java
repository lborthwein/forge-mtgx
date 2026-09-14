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

/** Prepared diagnostic only: all choices after setup belong to native controllers.
 * No sampled games, forced payoff, or opponent hidden information. */
public final class CubeReanimatorContinuitySmoke {
    private record Entry(String name, ZoneType zone) {}
    private static final String TROLL = "Troll of Khazad-dûm";
    private static forge.item.PaperCard paper(String name) {
        StaticData.instance().attemptToLoadCard(name);
        return Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), name);
    }
    private static Deck deck(List<Entry> entries) {
        Deck d = new Deck();
        for (Entry e : entries) d.getMain().add(paper(e.name()), 1);
        if (entries.size() != 40) throw new AssertionError("registered deck size");
        return d;
    }
    private static void populate(Player p, List<Entry> entries) {
        for (Entry e : entries) {
            Card c = Card.fromPaperCard(paper(e.name()), p);
            c.setGameTimestamp(p.getGame().getNextTimestamp());
            p.getZone(e.zone()).add(c); c.setSickness(false);
        }
    }
    private static List<Entry> own(int lands, String mode) {
        List<Entry> out = new ArrayList<>();
        if (!mode.equals("ready")) out.add(new Entry("Entomb", ZoneType.Hand));
        if (!mode.equals("no-return")) out.add(new Entry("Corpse Dance", ZoneType.Hand));
        out.add(new Entry(TROLL, mode.equals("ready") ? ZoneType.Graveyard : ZoneType.Library));
        for (int i = 0; i < lands; i++) out.add(new Entry("Swamp", ZoneType.Battlefield));
        while (out.size() < 40) out.add(new Entry("Swamp", ZoneType.Library));
        return out;
    }
    private static List<Entry> opposing(boolean tax) {
        List<Entry> out = new ArrayList<>();
        if (tax) out.add(new Entry("Thalia, Guardian of Thraben", ZoneType.Battlefield));
        while (out.size() < 40) out.add(new Entry("Forest", ZoneType.Library));
        return out;
    }
    private static void run(boolean candidate, int seat, int lands, boolean tax, boolean endstep, String mode, int index) {
        BenchRandomAudit.install(999100 + index);
        List<Entry> own = own(lands, mode), other = opposing(tax);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat ? own : other)).setPlayer(candidate && s == seat
                ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s)
                : GamePlayerUtil.createAiPlayer("Default-" + s, s, 0, null, "Default")));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "Reanimator continuity diagnostic").createGame();
        game.setAge(GameStage.Play);
        Player p = game.getPlayers().get(seat), opp = game.getPlayers().get(1 - seat);
        Player active = endstep ? opp : p;
        game.getPhaseHandler().setupFirstTurn(active, () -> game.getPhaseHandler().devModeSet(endstep ? PhaseType.END_OF_TURN : PhaseType.MAIN1, active));
        populate(p, own); populate(opp, other);
        game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        String key = "arm=" + (candidate ? "improved" : "baseline") + " seat=" + seat + " lands=" + lands
                + " tax=" + tax + " endstep=" + endstep + " mode=" + mode;
        System.out.println("CONTINUITY_FIXTURE " + key + " seed=" + (999100 + index) + " policy=" + forge.ai.CubeComboAi.VERSION);
        Set<Integer> seen = new HashSet<>();
        int searches = 0, returns = 0, firstSearch = -1, firstReturn = -1, steps = 0;
        boolean trollInPlay = false;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 4 && steps < 1200) {
            steps++; game.getPhaseHandler().mainLoopStep();
            for (var item : game.getStack()) if (seen.add(item.getId())) {
                var a = item.getSpellAbility();
                if (a.getActivatingPlayer() != p || !a.isSpell()) continue;
                String name = a.getHostCard().getName();
                if (name.equals("Entomb")) { searches++; if (firstSearch < 0) firstSearch = game.getPhaseHandler().getTurn(); }
                if (name.equals("Entomb") || name.equals("Corpse Dance")) System.out.println("CONTINUITY_PAID " + key
                        + " turn=" + game.getPhaseHandler().getTurn() + " phase=" + game.getPhaseHandler().getPhase()
                        + " card=" + name.replace(' ', '_') + " buyback=" + a.isBuyback() + " paidMana=" + a.getPayingMana().size()
                        + " cost=" + a.getPayCosts() + " ownGraveyard=" + p.getCardsIn(ZoneType.Graveyard).stream().map(Card::getName).toList());
            }
            boolean present = p.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c.getName().equals(TROLL));
            if (present && !trollInPlay) {
                returns++; if (firstReturn < 0) firstReturn = game.getPhaseHandler().getTurn();
                System.out.println("CONTINUITY_RETURN " + key + " turn=" + game.getPhaseHandler().getTurn() + " phase=" + game.getPhaseHandler().getPhase());
            }
            trollInPlay = present;
        }
        if (steps >= 1200) throw new AssertionError("continuation budget " + key);
        System.out.println("CONTINUITY_RESULT " + key + " searches=" + searches + " returns=" + returns
                + " firstSearch=" + firstSearch + " firstReturn=" + firstReturn + " steps=" + steps);
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (p, m, v) -> switch (m.getName()) {
                case "getAssetsDir" -> args[0] + "/forge-gui/";
                case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                case "getCurrentVersion" -> "reanimator-continuity-diagnostic";
                default -> throw new AssertionError(m.getName()); }));
            FModel.initialize(null, p -> { p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); p.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            boolean candidate = !args[1].equals("baseline"); int cases = 0;
            for (int seat = 0; seat < 2; seat++) for (int lands : new int[]{4, 6, 8})
                for (boolean tax : new boolean[]{false, true}) for (boolean endstep : new boolean[]{false, true})
                    for (String mode : List.of("search", "ready", "no-return")) run(candidate, seat, lands, tax, endstep, mode, cases++);
            System.out.println("CONTINUITY_SUITE_COMPLETE cases=" + cases);
        } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }
}
