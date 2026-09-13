package forge.bench;

import forge.StaticData;
import forge.card.CardStateName;
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
import java.lang.reflect.Proxy;
import java.util.*;

/** Observation only: the native controllers choose every game action.
 * Prepared DFC face/zone placement is explicit, not a cast or transform claim. */
public final class CubeHarnfelTopObservationSmoke {
    private static final String BIRGI = "Birgi, God of Storytelling", HARNFEL = "Harnfel, Horn of Bounty";
    private static final String TOP = "Sensei's Divining Top", HELM = "Helm of Awakening", OUTLET = "Aetherflux Reservoir";
    private static final List<String> CASES = List.of("complete", "top-hand", "empty-hand", "front-face",
            "no-helm", "draw-blocked", "life-replaced", "library-short", "activation-blocked", "no-outlet");
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);
    private record Placement(String name, ZoneType zone) { }
    private static void add(List<Placement> out, int n, String name, ZoneType zone) {
        for (int i = 0; i < n; i++) out.add(new Placement(name, zone));
    }
    private static List<Placement> placements(boolean own, String control) {
        List<Placement> out = new ArrayList<>();
        if (own) {
            add(out, 1, BIRGI, ZoneType.Battlefield);
            add(out, 1, TOP, control.equals("top-hand") ? ZoneType.Hand : ZoneType.Battlefield);
            if (!control.equals("no-helm")) add(out, 1, HELM, ZoneType.Battlefield);
            if (!control.equals("no-outlet")) add(out, 1, OUTLET, ZoneType.Battlefield);
            add(out, 4, "Mountain", ZoneType.Battlefield);
            if (!control.equals("empty-hand")) add(out, 1, "Forest", ZoneType.Hand);
        } else {
            String blocker = switch (control) {
                case "draw-blocked" -> "Omen Machine";
                case "life-replaced" -> "Tainted Remedy";
                case "activation-blocked" -> "Damping Matrix";
                default -> null;
            };
            if (blocker != null) add(out, 1, blocker, ZoneType.Battlefield);
        }
        if (own && control.equals("library-short")) {
            add(out, 3, "Forest", ZoneType.Library);
            add(out, 40 - out.size(), "Forest", ZoneType.Exile);
        } else add(out, 40 - out.size(), "Forest", ZoneType.Library);
        if (out.size() != 40) throw new AssertionError("registered fixture size");
        return out;
    }
    private static Deck deck(boolean own, String control) {
        Deck deck = new Deck("Harnfel native observation");
        for (Placement p : placements(own, control)) deck.getMain().add(p.name(), 1);
        return deck;
    }
    private static void populate(Player player, boolean own, String control) {
        for (ZoneType z : ZoneType.values()) if (player.getZone(z) != null) player.getZone(z).removeAllCards(true);
        for (Placement p : placements(own, control)) {
            Card card = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(p.name()), p.name()), player);
            card.setGameTimestamp(player.getGame().getNextTimestamp());
            if (p.name().equals(BIRGI) && !control.equals("front-face")) {
                card.setState(CardStateName.Backside, true);
                if (!card.getName().equals(HARNFEL)) throw new AssertionError("wrong prepared MDFC face");
            }
            player.getZone(p.zone()).add(card); card.setSickness(false);
        }
        Map<String,Integer> actual = new TreeMap<>(), registered = new TreeMap<>();
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) actual.merge(c.getPaperCard().getName(), 1, Integer::sum);
        for (var entry : player.getRegisteredPlayer().getDeck().getMain()) registered.merge(entry.getKey().getName(), entry.getValue(), Integer::sum);
        if (!actual.equals(registered) || actual.values().stream().mapToInt(Integer::intValue).sum() != 40)
            throw new AssertionError("registered paper-card identity mismatch " + control);
    }
    private static Object snapshot(Player player) {
        try {
            var method = CubeTopTutorAvailabilitySmoke.class.getDeclaredMethod("snapshot", Player.class);
            method.setAccessible(true); return method.invoke(null, player);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static String available(Player player) {
        List<String> result = new ArrayList<>();
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || !(card.getName().equals(TOP) || card.getName().equals(HARNFEL))) continue;
            for (var original : card.getSpellAbilities()) {
                if (!original.isActivatedAbility()) continue;
                var action = original.copy(player);
                result.add(card.getName().replace(' ', '_') + ":" + action.getApi()
                        + ":legal=" + forge.ai.CubeComboAi.canPlayNative(action, player)
                        + ":payable=" + forge.ai.CubeComboAi.canPayCost(action, player, false));
            }
        }
        return String.join(";", result);
    }
    private record SavedField(Object owner, java.lang.reflect.Field field, Object value) {}
    private static void savePlanner(Object plan, List<SavedField> saved) throws ReflectiveOperationException {
        for (var field : plan.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true); Object value = field.get(plan);
            if (java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                if (value != null && value.getClass().getName().matches("forge\\.ai\\.Cube(?:TopKitten|HarnfelTop)Plan")) savePlanner(value, saved);
            } else saved.add(new SavedField(plan, field, value));
        }
    }
    private static Object listeners(Game game) throws ReflectiveOperationException {
        var events = Game.class.getDeclaredField("events"); events.setAccessible(true);
        Object bus = events.get(game);
        var registry = bus.getClass().getDeclaredField("subscribers"); registry.setAccessible(true);
        Object holder = registry.get(bus);
        var subscribers = holder.getClass().getDeclaredField("subscribers"); subscribers.setAccessible(true);
        Map<?, ?> original = (Map<?, ?>)subscribers.get(holder);
        Map<Object, Object> copy = new java.util.HashMap<>();
        for (var entry : original.entrySet()) copy.put(entry.getKey(), Set.copyOf((java.util.Collection<?>)entry.getValue()));
        return copy;
    }
    private static void actualPlan(Player player, String key, int step) {
        if (!(player.getController() instanceof forge.ai.CubeComboPlayerController)) return;
        try {
            var field = forge.ai.CubeComboPlayerController.class.getDeclaredField("topPlan"); field.setAccessible(true);
            Object plan = field.get(player.getController()); List<SavedField> saved = new ArrayList<>(); savePlanner(plan, saved);
            Object before = snapshot(player), beforeListeners = listeners(player.getGame()); String first = null;
            for (int i = 0; i < 3; i++) {
                String receipt;
                try {
                    var action = (forge.game.spellability.SpellAbility)plan.getClass().getMethod("nextAction").invoke(plan);
                    receipt = action == null ? "none" : action.getHostCard().getId() + ":" + action.getApi() + ":" + action.getTargets();
                } finally {
                    for (var value : saved) value.field().set(value.owner(), value.value());
                }
                if (first == null) first = receipt;
                else if (!first.equals(receipt)) throw new AssertionError("actual plan query drift " + key);
                if (!before.equals(snapshot(player)) || !beforeListeners.equals(listeners(player.getGame())))
                    throw new AssertionError("actual plan query changed native state/RNG/listeners " + key);
            }
            System.out.println("HARNFEL_PLAN_QUERY " + key + " step=" + step + " repeats=3 unchanged=true listenersUnchanged=true action=" + first);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void observe(Player player, String key, int step) {
        if (!player.getGame().getStack().isEmpty() || !player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player)) return;
        actualPlan(player, key, step);
        Object before = snapshot(player); String first = available(player);
        for (int i = 0; i < 2; i++) if (!first.equals(available(player))) throw new AssertionError("query not repeatable");
        if (!before.equals(snapshot(player))) throw new AssertionError("query changed native state/RNG");
        System.out.println("HARNFEL_AVAILABLE " + key + " step=" + step + " repeats=3 unchanged=true actions=" + first);
    }
    private static forge.ai.LobbyPlayerAi ai(int seat) {
        var ai = new forge.ai.LobbyPlayerAi("Default-" + seat, null); ai.setAiProfile("Default"); return ai;
    }
    private static void run(boolean improved, boolean observed, int seat, String control) {
        List<RegisteredPlayer> entries = new ArrayList<>();
        for (int s = 0; s < 2; s++) entries.add(new RegisteredPlayer(deck(s == seat, control)).setPlayer(
                improved && s == seat ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s) : ai(s)));
        GameRules rules = new GameRules(GameType.Constructed); rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, entries, "Harnfel observation").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1-seat);
        player.setLife(20, null); opponent.setLife(20, null); populate(player, true, control); populate(opponent, false, control);
        game.setAge(GameStage.Play); int start = seat == 0 ? 1 : 2;
        game.getPhaseHandler().setupFirstTurn(seat == 0 ? player : opponent,
                () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, start));
        game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(988100L + 100L*seat + CASES.indexOf(control));
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("nonzero initial mana");
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " case=" + control;
        System.out.println("HARNFEL_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " observed=" + observed + " registered=40 initialMana=0 startTurn=" + start
                + " face=" + (control.equals("front-face") ? "Birgi" : "Harnfel") + " librarySize=" + player.getCardsIn(ZoneType.Library).size());
        Set<Integer> seen = new HashSet<>(); int steps = 0, topCasts = 0, exileCasts = 0, harnfelActivations = 0, shots = 0;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= start + 2 && steps < 1000) {
            if (observed) observe(player, key, steps);
            game.getPhaseHandler().mainLoopStep(); steps++;
            for (var item : game.getStack()) if (seen.add(item.getId())) {
                var action = item.getSpellAbility(); if (action.getActivatingPlayer() != player) continue;
                String name = action.getHostCard().getName();
                if (name.equals(TOP) && action.isSpell() && !action.isCopied()) {
                    topCasts++; if (action.getHostCard().getCastFrom() != null && action.getHostCard().getCastFrom().getZoneType() == ZoneType.Exile) exileCasts++;
                }
                if (name.equals(HARNFEL) && action.isActivatedAbility()) harnfelActivations++;
                if (name.equals(OUTLET) && action.isActivatedAbility()) shots++;
                var grant = action.getMayPlay();
                System.out.println("HARNFEL_STACK " + key + " step=" + steps + " source=" + name.replace(' ', '_')
                        + " api=" + action.getApi() + " spell=" + action.isSpell() + " copied=" + action.isCopied()
                        + " castFrom=" + action.getHostCard().getCastFrom() + " permission=" + (grant != null)
                        + " ownPermission=" + (grant != null && grant.getHostCard().getController() == player));
            }
        }
        if (steps >= 1000) throw new AssertionError("native step budget exhausted " + key);
        System.out.println("HARNFEL_RESULT " + key + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " steps=" + steps + " turn=" + game.getPhaseHandler().getTurn() + " topCasts=" + topCasts
                + " exileCasts=" + exileCasts + " harnfelActivations=" + harnfelActivations + " shots=" + shots
                + " life=" + player.getLife() + " opponentLife=" + opponent.getLife() + " librarySize=" + player.getCardsIn(ZoneType.Library).size());
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "harnfel-observation-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> { preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); preferences.setPref(FPref.UI_LANGUAGE,"en-US"); return null; });
            Set<String> names = new LinkedHashSet<>();
            for (String control : CASES) for (boolean own : List.of(false,true)) for (Placement p : placements(own,control)) names.add(p.name());
            for (String name : names) StaticData.instance().attemptToLoadCard(name);
            for (String control : CASES) for (int seat = 0; seat < 2; seat++) run(args[1].equals("improved"),args[2].equals("observed"),seat,control);
            System.out.println("HARNFEL_SUITE_COMPLETE cases=20");
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
