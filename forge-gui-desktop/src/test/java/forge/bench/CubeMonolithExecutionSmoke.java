package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.GameLossReason;
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

/** Source-only finite native Monolith loop fixture. Candidate mode is default; pass "default" for Default-vs-Default. */
public final class CubeMonolithExecutionSmoke {
    private static final int STEP_LIMIT = 500;
    private static final List<String> NAMES = List.of("Basalt Monolith", "Grim Monolith", "Kinnan, Bonder Prodigy", "Zirda, the Dawnwaker",
            "Walking Ballista", "Island", "Mountain", "Forest", "Null Rod", "Cursed Totem", "Leyline of Sanctity", "Solemnity");
    private static void loadOnce() { for (final String name : NAMES) StaticData.instance().attemptToLoadCard(name); }
    private static Card add(final String name, final Player player, final ZoneType zone) {
        final Card card = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), name), player);
        card.setGameTimestamp(player.getGame().getNextTimestamp()); player.getZone(zone).add(card); card.setSickness(false); return card;
    }
    private static boolean has(final Player p, final ZoneType z, final String n) { return p.getCardsIn(z).stream().anyMatch(c -> c.getName().equals(n)); }
    private static int total(final Player p) { int n = 0; for (final ZoneType z : List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Library, ZoneType.Exile)) n += p.getCardsIn(z).size(); return n; }
    private static String version() { try { return (String) forge.ai.CubeComboAi.class.getField("VERSION").get(null); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } }
    private static Game game(final int seat, final boolean candidate) {
        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(new Deck()).setPlayer(candidate && s == seat ? new forge.ai.LobbyPlayerCubeComboAi("CubeCombo-" + s) : GamePlayerUtil.createAiPlayer("Default-" + s, s, 0, null, "Default")));
        final GameRules rules = new GameRules(GameType.Constructed); rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR); rules.setAllowCheatShuffle(false);
        final Game game = new Match(rules, players, "finite Monolith native fixture").createGame(); game.setAge(GameStage.Play);
        final Player active = game.getPlayers().get(seat); game.getPhaseHandler().setupFirstTurn(active, () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, active)); return game;
    }
    private static void populate(final Player p, final Player opp, final String pair, final boolean ballistaBattlefield, final String control) {
        final boolean grim = pair.startsWith("grim"), kinnan = pair.endsWith("kinnan");
        add(grim ? "Grim Monolith" : "Basalt Monolith", p, ZoneType.Battlefield);
        if (!control.equals("absent-enabler")) add(kinnan ? "Kinnan, Bonder Prodigy" : "Zirda, the Dawnwaker", p, ZoneType.Battlefield);
        add("Island", p, ZoneType.Battlefield); add("Mountain", p, ZoneType.Battlefield);
        final boolean hasOutlet = !control.equals("no-outlet");
        final Card ballista = add(hasOutlet ? "Walking Ballista" : "Island", p, ballistaBattlefield ? ZoneType.Battlefield : ZoneType.Hand);
        if (hasOutlet && ballistaBattlefield) ballista.setCounters(CounterEnumType.P1P1, 1);
        for (int i = 0; i < 11; i++) add("Island", p, ZoneType.Graveyard);
        for (int i = 0; i < 24 + (control.equals("absent-enabler") ? 1 : 0); i++) add("Forest", p, ZoneType.Library);
        if (control.equals("null-rod")) add("Null Rod", opp, ZoneType.Battlefield);
        if (control.equals("cursed-totem")) add("Cursed Totem", opp, ZoneType.Battlefield);
        if (control.equals("hexproof")) add("Leyline of Sanctity", opp, ZoneType.Battlefield);
        if (control.equals("solemnity")) add("Solemnity", opp, ZoneType.Battlefield);
        final int grave = control.equals("null-rod") || control.equals("cursed-totem") || control.equals("hexproof") || control.equals("solemnity") ? 9 : 10;
        for (int i = 0; i < grave; i++) add("Forest", opp, ZoneType.Graveyard);
        for (int i = 0; i < 30; i++) add("Forest", opp, ZoneType.Library);
        if (total(p) != 40 || total(opp) != 40) throw new AssertionError("exact-40 fixture totals own=" + total(p) + " opp=" + total(opp));
    }
    private static void run(final int seat, final String pair, final boolean ballistaBattlefield, final String control, final boolean candidate) {
        final Game game = game(seat, candidate); final Player p = game.getPlayers().get(seat), opp = game.getPlayers().get(1 - seat);
        populate(p, opp, pair, ballistaBattlefield, control); game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers(); BenchRandomAudit.install(95300 + seat * 100 + pair.length() + control.length());
        final String monolith = pair.startsWith("grim") ? "Grim Monolith" : "Basalt Monolith";
        final Set<Integer> stackIds = new HashSet<>(); int monolithMana = 0, untaps = 0, ballistaCasts = 0, ballistaShots = 0, peakMana = 0, steps = 0, firstTurnSteps = -1;
        int nativeMana = 0, nativeUntaps = 0, nativeShots = 0, nativeAdds = 0, castX = -1, peakCounters = 0;
        boolean monolithTapped = p.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals(monolith)).findFirst().map(Card::isTapped).orElse(false);
        int lastCounters = p.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals("Walking Ballista")).findFirst().map(c -> c.getCounters(CounterEnumType.P1P1)).orElse(0);
        System.out.println("MONOLITH_FIXTURE seat=" + seat + " pair=" + pair + " ballista=" + (ballistaBattlefield ? "BattlefieldOneCounter" : "Hand") + " control=" + control + " controller=" + (candidate ? "CubeCombo" : "Default") + " policy=" + (candidate ? version() : "Default") + " ownCards=" + total(p) + " oppCards=" + total(opp));
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 2 && steps < STEP_LIMIT) {
            final int step = ++steps, manaBefore = p.getManaPool().totalMana(); game.getPhaseHandler().mainLoopStep(); peakMana = Math.max(peakMana, p.getManaPool().totalMana());
            if (game.getPhaseHandler().getTurn() == 1) {
                final var history = game.getStack().getAbilityActivatedThisTurn();
                nativeMana = Math.max(nativeMana, (int) history.stream().filter(a -> a.getActivatingPlayer() == p && a.getHostCard().getName().equals(monolith) && a.isManaAbility()).count());
                nativeUntaps = Math.max(nativeUntaps, (int) history.stream().filter(a -> a.getActivatingPlayer() == p && a.getHostCard().getName().equals(monolith) && a.getApi() == forge.game.ability.ApiType.Untap).count());
                nativeShots = Math.max(nativeShots, (int) history.stream().filter(a -> a.getActivatingPlayer() == p && a.getHostCard().getName().equals("Walking Ballista") && a.getApi() == forge.game.ability.ApiType.DealDamage).count());
                nativeAdds = Math.max(nativeAdds, (int) history.stream().filter(a -> a.getActivatingPlayer() == p && a.getHostCard().getName().equals("Walking Ballista") && a.getApi() == forge.game.ability.ApiType.PutCounter).count());
                if (control.equals("null-rod") && nativeMana + nativeUntaps + nativeShots + nativeAdds != 0) throw new AssertionError("Null Rod activation");
                if (control.equals("cursed-totem") && nativeShots + nativeAdds != 0) throw new AssertionError("Cursed Totem activation");
            }
            final Card mono = p.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals(monolith)).findFirst().orElse(null);
            if (mono != null && !monolithTapped && mono.isTapped() && p.getManaPool().totalMana() > manaBefore) { monolithMana++; System.out.println("MONOLITH_MANA step=" + step + " mana=" + p.getManaPool().totalMana()); }
            if (mono != null && monolithTapped && !mono.isTapped()) { untaps++; System.out.println("MONOLITH_UNTAP step=" + step + " mana=" + p.getManaPool().totalMana()); }
            monolithTapped = mono != null && mono.isTapped();
            for (final var item : game.getStack()) if (stackIds.add(item.getId())) {
                final var sa = item.getSpellAbility(); if (sa.isSpell() && sa.getHostCard().getName().equals("Walking Ballista") && !sa.isCopied()) { ballistaCasts++; castX = sa.getXManaCostPaid(); }
                if (control.equals("hexproof") && sa.getHostCard().getName().equals("Walking Ballista") && com.google.common.collect.Iterables.contains(sa.getTargets().getTargetPlayers(), opp)) throw new AssertionError("Illegal hexproof target");
                System.out.println("MONOLITH_STACK step=" + step + " id=" + item.getId() + " card=" + sa.getHostCard().getName() + " copied=" + sa.isCopied() + " targets=" + sa.getTargets());
            }
            final int counters = p.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals("Walking Ballista")).findFirst().map(c -> c.getCounters(CounterEnumType.P1P1)).orElse(0);
            peakCounters = Math.max(peakCounters, counters);
            if (counters < lastCounters) ballistaShots += lastCounters - counters; lastCounters = counters;
            if (firstTurnSteps < 0 && game.getPhaseHandler().getTurn() > 1) firstTurnSteps = steps;
        }
        if (firstTurnSteps < 0) firstTurnSteps = steps;
        final boolean lifeTerminal = opp.getOutcome() != null && opp.getOutcome().lossState == GameLossReason.LifeReachedZero;
        System.out.println("MONOLITH_RESULT seat=" + seat + " pair=" + pair + " ballista=" + (ballistaBattlefield ? "Battlefield" : "Hand") + " control=" + control + " steps=" + steps + " firstTurnSteps=" + firstTurnSteps + " won=" + p.hasWon() + " lifeTerminal=" + lifeTerminal + " monolithMana=" + monolithMana + " untaps=" + untaps + " peakMana=" + peakMana + " ballistaCasts=" + ballistaCasts + " ballistaCounters=" + lastCounters + " ballistaShots=" + ballistaShots + " opponentLife=" + opp.getLife());
        System.out.println("MONOLITH_NATIVE seat=" + seat + " pair=" + pair + " ballista=" + (ballistaBattlefield ? "Battlefield" : "Hand") + " control=" + control + " manaActivations=" + nativeMana + " untapActivations=" + nativeUntaps + " shots=" + nativeShots + " addActivations=" + nativeAdds + " castX=" + castX + " peakCounters=" + peakCounters);
        if (steps >= STEP_LIMIT) throw new AssertionError("Monolith loop exceeded bound");
        if (candidate && control.equals("none") && !pair.equals("grim-kinnan")
                && (!p.hasWon() || !lifeTerminal || nativeUntaps < 2 || nativeShots != 20 || peakCounters < 20 || !ballistaBattlefield && castX != 20))
            throw new AssertionError("Expected native loop and Ballista lethal");
        if ((!control.equals("none") || pair.equals("grim-kinnan")) && p.hasWon()) throw new AssertionError("Unexpected negative-control win");
    }
    public static void main(final String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (p,m,v) -> switch (m.getName()) { case "getAssetsDir" -> args[0] + "/forge-gui/"; case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false; case "getCurrentVersion" -> "cube-monolith-execution-smoke-v1"; default -> throw new AssertionError(m.getName()); }));
            FModel.initialize(null, p -> { p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); p.setPref(FPref.UI_LANGUAGE, "en-US"); return null; }); loadOnce(); final boolean candidate = args.length < 2 || !args[1].equals("default") && !args[1].equals("baseline");
            for (int seat = 0; seat < 2; seat++) for (final String pair : List.of("basalt-kinnan", "basalt-zirda", "grim-zirda", "grim-kinnan")) for (final boolean bf : List.of(false, true)) for (final String control : List.of("none", "absent-enabler", "null-rod", "cursed-totem", "hexproof", "no-outlet", "solemnity")) run(seat, pair, bf, control, candidate);
            System.out.println("MONOLITH_SUITE_COMPLETE");
        } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }
}
