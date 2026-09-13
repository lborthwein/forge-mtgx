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

/** Native diagnosis of Witness/Kitten resource loops. Prepared zones only;
 * all later casts, costs, targets, triggers and discards are ordinary/native.
 * No win assertion: diagnosis must establish whether the frozen AI executes
 * the listed route before any strategy change is designed. */
public final class CubeWitnessResourceSmoke {
    private record Entry(String name, ZoneType zone) {}
    private static final Set<String> loaded = new HashSet<>();
    private static final String KITTEN = "Displacer Kitten", PARTNER = "Eternal Witness",
        OUTLET = "Aetherflux Reservoir", DARK = "Dark Ritual";
    private static forge.item.PaperCard paper(String name) {
        if (loaded.add(name)) StaticData.instance().attemptToLoadCard(name);
        return Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name),name);
    }
    private static List<Entry> layout(String engine, String control) {
        List<Entry> cards = new ArrayList<>();
        cards.add(new Entry(control.equals("no-kitten") ? "Forest" : KITTEN, ZoneType.Battlefield));
        cards.add(new Entry(control.equals("no-witness") ? "Forest" : PARTNER, ZoneType.Battlefield));
        cards.add(new Entry(control.equals("no-outlet") ? "Forest" : OUTLET, ZoneType.Battlefield));
        if (engine.equals("snap")) {
            cards.add(new Entry("Snap", ZoneType.Hand));
            cards.add(new Entry(control.equals("no-petal") ? "Forest" : "Lotus Petal", ZoneType.Graveyard));
            cards.add(new Entry(control.equals("no-auxiliary") || control.equals("opposing-creatures")
                    ? "Forest" : "Elvish Mystic", ZoneType.Battlefield));
            cards.add(new Entry("Island", ZoneType.Battlefield));
            if (!control.equals("one-land")) cards.add(new Entry("Island", ZoneType.Battlefield));
        } else {
            cards.add(new Entry(control.equals("no-ritual") ? "Forest" : DARK, ZoneType.Hand));
            cards.add(new Entry("Frantic Search", ZoneType.Graveyard));
            cards.add(new Entry(control.equals("no-blue") ? "Swamp" : "Island", ZoneType.Battlefield));
            cards.add(new Entry("Swamp", ZoneType.Battlefield));
            cards.add(new Entry("Swamp", ZoneType.Battlefield));
            if (control.equals("discard-pressure")) {
                cards.add(new Entry("Black Lotus", ZoneType.Hand));
                cards.add(new Entry("Ancestral Recall", ZoneType.Hand));
            }
        }
        for (int i = 0; i < (control.equals("short-library") ? 2 : 20); i++) cards.add(new Entry("Forest", ZoneType.Library));
        while (cards.size() < 40) cards.add(new Entry("Forest", ZoneType.Exile));
        if (cards.size() != 40) throw new AssertionError("forty-card layout");
        return cards;
    }
    private static List<Entry> opposing(String control) {
        List<Entry> cards = new ArrayList<>();
        if (control.equals("opposing-creatures"))
            for (int i = 0; i < 4; i++) cards.add(new Entry("Grizzly Bears", ZoneType.Battlefield));
        if (control.equals("draw-cap")) cards.add(new Entry("Narset, Parter of Veils", ZoneType.Battlefield));
        for (int i = 0; i < 30; i++) cards.add(new Entry("Forest", ZoneType.Library));
        while (cards.size() < 40) cards.add(new Entry("Forest", ZoneType.Exile));
        return cards;
    }
    private static Deck deck(List<Entry> entries) {
        Deck deck = new Deck(); for (Entry e : entries) deck.getMain().add(paper(e.name()), 1); return deck;
    }
    private static void populate(Player player, List<Entry> entries) {
        for (Entry e : entries) {
            Card c = Card.fromPaperCard(paper(e.name()), player);
            c.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(e.zone()).add(c); c.setSickness(false);
            if (c.getName().equals("Narset, Parter of Veils")) c.setCounters(forge.game.card.CounterEnumType.LOYALTY, 5);
        }
    }
    private static void run(int seat, String engine, String control, boolean candidate) {
        List<Entry> own = layout(engine, control), other = opposing(control);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat ? own : other)).setPlayer(candidate && s == seat
            ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s) : GamePlayerUtil.createAiPlayer("Default-" + s, s, 0, null, "Default")));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR); rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "Witness resource native diagnosis").createGame(); game.setAge(GameStage.Play);
        Player p = game.getPlayers().get(seat), opp = game.getPlayers().get(1 - seat);
        game.getPhaseHandler().setupFirstTurn(p, () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p));
        populate(p, own); populate(opp, other); p.setLife(20, null); opp.setLife(40, null);
        game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(98800 + seat * 100 + (engine.equals("snap") ? 0 : 40) + controls(engine).indexOf(control));
        String key = "seat=" + seat + " engine=" + engine + " control=" + control;
        System.out.println("WITNESS_RESOURCE_FIXTURE " + key + " candidate=" + candidate + " policy=" + forge.ai.CubeComboAi.VERSION);
        Set<Integer> seen = new HashSet<>(); Set<Long> partnerObjects = new HashSet<>();
        int steps = 0, casts = 0, blinks = 0, witnessEtb = 0, shots = 0, highestLife = p.getLife();
        Map<Integer, ZoneType> priorZones = new TreeMap<>();
        Map<Integer, Long> priorWitness = new TreeMap<>();
        Map<Integer, Boolean> priorLandTap = new TreeMap<>();
        for (ZoneType zone : new ZoneType[]{ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile})
            for (Card c : p.getCardsIn(zone)) {
                priorZones.put(c.getId(), zone);
                if (zone == ZoneType.Battlefield && c.getName().equals(PARTNER)) priorWitness.put(c.getId(), c.getGameTimestamp());
                if (zone == ZoneType.Battlefield && c.isLand()) priorLandTap.put(c.getId(), c.isTapped());
            }
        int witnessEntries = 0, graveyardReturns = 0, auxiliaryCasts = 0, snapCasts = 0, franticCasts = 0;
        int turn = -1;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 3 && steps < 2400) {
            steps++; game.getPhaseHandler().mainLoopStep();
            highestLife = Math.max(highestLife, p.getLife());
            if (turn != game.getPhaseHandler().getTurn()) {
                turn = game.getPhaseHandler().getTurn();
                System.out.println("WITNESS_RESOURCE_TURN turn=" + turn + " own=" + (game.getPhaseHandler().getPlayerTurn() == p));
            }
            for (ZoneType zone : new ZoneType[]{ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile})
                for (Card c : p.getCardsIn(zone)) {
                    ZoneType before = priorZones.put(c.getId(), zone);
                    if (before == ZoneType.Hand && zone == ZoneType.Graveyard)
                        System.out.println("WITNESS_RESOURCE_HAND_TO_YARD step=" + steps + " card=" + c.getName().replace(' ', '_') + " id=" + c.getId());
                    if (zone == ZoneType.Battlefield && c.isLand()) {
                        Boolean priorTap = priorLandTap.put(c.getId(), c.isTapped());
                        if (priorTap != null && priorTap != c.isTapped())
                            System.out.println("WITNESS_RESOURCE_LAND step=" + steps + " card=" + c.getName().replace(' ', '_') + " id=" + c.getId() + " tapped=" + c.isTapped());
                    }
                    if (before == ZoneType.Graveyard && zone == ZoneType.Hand) {
                        graveyardReturns++;
                        System.out.println("WITNESS_RESOURCE_RETURN step=" + steps + " card=" + c.getName().replace(' ', '_') + " id=" + c.getId());
                    }
                    if (zone == ZoneType.Battlefield && c.getName().equals(PARTNER)) {
                        Long beforeStamp = priorWitness.put(c.getId(), c.getGameTimestamp());
                        partnerObjects.add(c.getGameTimestamp());
                        if (beforeStamp != null && beforeStamp != c.getGameTimestamp()) {
                            witnessEntries++;
                            System.out.println("WITNESS_RESOURCE_ENTRY step=" + steps + " card=Eternal_Witness timestamp=" + c.getGameTimestamp());
                        }
                    }
                }
            for (var item : game.getStack()) if (seen.add(item.getId())) {
                var sa = item.getSpellAbility(); if (sa.getActivatingPlayer() != p) continue;
                String name = sa.getHostCard().getName();
                if (sa.isSpell() && !sa.isCopied()) {
                    casts++;
                    if (name.equals("Elvish Mystic")) auxiliaryCasts++;
                    if (name.equals("Snap")) snapCasts++;
                    if (name.equals("Frantic Search")) franticCasts++;
                }
                if (name.equals(KITTEN) && sa.getApi() == forge.game.ability.ApiType.ChangeZone) blinks++;
                if (name.equals(PARTNER) && sa.getApi() == forge.game.ability.ApiType.ChangeZone) witnessEtb++;
                if (name.equals(OUTLET) && sa.getApi() == forge.game.ability.ApiType.DealDamage) shots++;
                System.out.println("WITNESS_RESOURCE_STACK step=" + steps + " id=" + item.getId() + " card=" + name
                    + " api=" + sa.getApi() + " targets=" + sa.getTargets());
            }
        }
        System.out.println("WITNESS_RESOURCE_RESULT " + key + " candidate=" + candidate + " policy=" + forge.ai.CubeComboAi.VERSION
            + " won=" + p.hasWon() + " gameOver=" + game.isGameOver() + " steps=" + steps + " casts=" + casts
            + " snapCasts=" + snapCasts + " franticCasts=" + franticCasts + " auxiliaryCasts=" + auxiliaryCasts
            + " kittenTriggers=" + blinks + " witnessTriggers=" + witnessEtb + " witnessEntries=" + witnessEntries
            + " graveyardReturns=" + graveyardReturns + " shots=" + shots + " partnerObjects=" + partnerObjects.size()
            + " life=" + p.getLife() + " highestLife=" + highestLife + " opponentLife=" + opp.getLife()
            + " librarySize=" + p.getCardsIn(ZoneType.Library).size() + " budgetExhausted=" + (steps >= 2400));
    }
    private static List<String> controls(String engine) {
        return engine.equals("snap")
            ? List.of("none", "no-auxiliary", "no-petal", "no-kitten", "no-witness", "no-outlet", "one-land", "opposing-creatures")
            : List.of("none", "short-library", "no-blue", "no-kitten", "no-witness", "no-outlet", "draw-cap", "discard-pressure", "no-ritual");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (p, m, v) -> switch (m.getName()) {
                case "getAssetsDir" -> args[0] + "/forge-gui/"; case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame" -> false;
                case "getCurrentVersion" -> "witness-resource-v1"; default -> throw new AssertionError(m.getName()); }));
            FModel.initialize(null, p -> {p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); p.setPref(FPref.UI_LANGUAGE, "en-US"); return null;});
            boolean candidate = args.length < 2 || !args[1].equals("baseline");
            int cases = 0;
            for (int seat = 0; seat < 2; seat++)
                for (String engine : List.of("snap", "frantic"))
                    for (String control : controls(engine)) {run(seat, engine, control, candidate); cases++;}
            System.out.println("WITNESS_RESOURCE_SUITE_COMPLETE cases=" + cases);
        } catch (Throwable t) {t.printStackTrace(); System.exit(1);}
    }
}
