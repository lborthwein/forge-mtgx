package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.stream.Collectors;

/** Native continuous priority, not host-selected actions. Synthetic development
 * fixtures isolate execution; they do not measure drafted-deck winrate. */
public final class CubeBreachExecutionSmoke {
    private static boolean improved;
    private static final java.util.Set<String> loaded = new java.util.HashSet<>();
    private static Card add(String name, Player p, ZoneType zone) {
        if (loaded.add(name)) StaticData.instance().attemptToLoadCard(name);
        Card c = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)), p);
        c.setGameTimestamp(p.getGame().getNextTimestamp()); p.getZone(zone).add(c); c.setSickness(false); return c;
    }
    private static String names(Player p, ZoneType z) {
        return p.getCardsIn(z).stream().map(Card::getName).collect(Collectors.joining(","));
    }
    private static boolean has(Player p, ZoneType z, String n) {
        return p.getCardsIn(z).stream().anyMatch(c -> c.getName().equals(n));
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void run(int seat, boolean hand, String control, boolean main2) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(new Deck()).setPlayer(s == seat && improved
                ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s)
                : GamePlayerUtil.createAiPlayer("Default-" + s, s, 0, null, "Default")));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR); rules.setAllowCheatShuffle(false);
        Game g = new Match(rules, players, "Breach native execution").createGame(); g.setAge(GameStage.Play);
        Player p = g.getPlayers().get(seat), opp = g.getPlayers().get(1 - seat);
        g.getPhaseHandler().setupFirstTurn(p, () -> g.getPhaseHandler().devModeSet(main2 ? PhaseType.MAIN2 : PhaseType.MAIN1, p));
        add("Lion's Eye Diamond", p, ZoneType.Battlefield);
        if (!control.equals("no-breach")) add("Underworld Breach", p, ZoneType.Battlefield);
        add("Island", p, ZoneType.Battlefield); add("Island", p, ZoneType.Battlefield); add("Mountain", p, ZoneType.Battlefield);
        add("Brain Freeze", p, hand ? ZoneType.Hand : ZoneType.Graveyard);
        int initialFuel = control.equals("short-fuel") ? 2 : 12;
        for (int i = 0; i < initialFuel; i++) add("Ponder", p, ZoneType.Graveyard);
        for (int i = 0; i < 30; i++) add("Island", p, ZoneType.Library);
        for (int i = 0; i < 60; i++) add("Forest", opp, ZoneType.Library);
        if (control.equals("null-rod")) add("Null Rod", opp, ZoneType.Battlefield);
        if (control.equals("rule-of-law")) add("Rule of Law", opp, ZoneType.Battlefield);
        if (control.equals("hexproof")) add("Leyline of Sanctity", opp, ZoneType.Battlefield);
        g.getAction().checkStateEffects(true); g.getTriggerHandler().resetActiveTriggers(); BenchRandomAudit.install(91800 + seat);
        Set<Integer> seenStack = new HashSet<>();
        int casts = 0, escapes = 0, copies = 0, ledActivations = 0, steps = 0;
        int lastOwn = 30, lastOpp = 60, lastExile = 0;
        boolean wasLedInPlay = true;
        while (!g.isGameOver() && g.getPhaseHandler().getTurn() <= 2 && steps++ < 640) {
            int manaBefore = p.getManaPool().totalMana();
            g.getPhaseHandler().mainLoopStep();
            for (var si : g.getStack()) {
                var sa = si.getSpellAbility();
                if (sa.isSpell() && sa.getHostCard().getName().equals("Brain Freeze") && seenStack.add(si.getId())) {
                    for (Player target : sa.getTargets().getTargetPlayers()) check(sa.canTarget(target), "illegal Freeze target");
                    if (sa.isCopied()) copies++;
                    else { casts++; if (sa.isEscape()) escapes++; }
                    System.out.println("CAST step=" + steps + " stackId=" + si.getId() + " copy=" + sa.isCopied()
                            + " escape=" + sa.isEscape() + " targets=" + sa.getTargets()
                            + " storm=" + g.getStack().getSpellsCastThisTurn().size());
                }
            }
            boolean ledInPlay = has(p, ZoneType.Battlefield, "Lion's Eye Diamond");
            if (wasLedInPlay && !ledInPlay && has(p, ZoneType.Graveyard, "Lion's Eye Diamond")
                    && p.getManaPool().totalMana() > manaBefore) ledActivations++;
            wasLedInPlay = ledInPlay;
            int own = p.getCardsIn(ZoneType.Library).size(), other = opp.getCardsIn(ZoneType.Library).size();
            int exiled = p.getCardsIn(ZoneType.Exile).size();
            if (own != lastOwn || other != lastOpp || exiled != lastExile) {
                System.out.println("STATE step=" + steps + " phase=" + g.getPhaseHandler().getPhase()
                        + " ownLibrary=" + own + " opponentLibrary=" + other + " exile=" + exiled
                        + " mana=" + p.getManaPool().totalMana() + " grave=" + names(p, ZoneType.Graveyard));
                if (improved && control.equals("none")) check(!has(p, ZoneType.Exile, "Brain Freeze")
                        && !has(p, ZoneType.Exile, "Lion's Eye Diamond"), "combo key spent as escape fuel");
            }
            lastOwn = own; lastOpp = other; lastExile = exiled;
        }
        boolean won = g.isGameOver() && p.hasWon();
        System.out.println("BREACH_RESULT arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " hand=" + hand
                + " main2=" + main2 + " control=" + control + " won=" + won + " casts=" + casts + " escapes=" + escapes + " copies=" + copies
                + " ledActivations=" + ledActivations + " ownLibrary=" + lastOwn + " opponentLibrary=" + lastOpp
                + " exiled=" + lastExile + " keysExiled=" + (has(p, ZoneType.Exile, "Brain Freeze")
                    || has(p, ZoneType.Exile, "Lion's Eye Diamond"))
                + " steps=" + steps + " outcome=" + g.getOutcome());
        check(steps < 640, "native priority loop exceeded bound");
        if (improved && control.equals("none")) {
            check(won && lastOpp == 0, "Breach native loop did not win");
            check(escapes > 1 && copies > 1 && ledActivations > 1 && lastOwn < 30 && lastExile >= 6,
                    "missing escape/self-mill/storm/mana mechanism");
        } else check(!won, "unexpected control win");
        if (control.equals("null-rod")) check(ledActivations == 0, "activated LED through Null Rod");
        if (control.equals("rule-of-law")) check(casts <= 1, "cast multiple Freezes through Rule of Law");
    }
    public static void main(String[] args) {
        try {
            String root = args[0]; improved = args[1].equals("improved");
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (p, m, v) -> switch (m.getName()) {
                        case "getAssetsDir" -> root + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> forge.ai.CubeComboAi.VERSION;
                        default -> throw new AssertionError(m.getName());
                    }));
            FModel.initialize(null, p -> { p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); p.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            for (int seat = 0; seat < 2; seat++) {
                run(seat, false, "none", false); run(seat, true, "none", false); run(seat, false, "none", true);
                run(seat, true, "no-breach", false); run(seat, false, "short-fuel", false);
                run(seat, false, "null-rod", false); run(seat, false, "rule-of-law", false); run(seat, false, "hexproof", false);
            }
            System.out.println("BREACH_SUITE_PASS"); System.exit(0);
        } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }
}
