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

/** Registered counter interventions: the fixture opponent selects a native Disallow.
 * Prepared DFC face/zone placement is explicit, not a cast or transform claim. */
public final class CubeHarnfelTopInterruptionSmoke {
    private static final String BIRGI = "Birgi, God of Storytelling", HARNFEL = "Harnfel, Horn of Bounty";
    private static final String TOP = "Sensei's Divining Top", HELM = "Helm of Awakening", OUTLET = "Aetherflux Reservoir";
    private static final List<String> CASES = List.of("complete", "counter-draw", "counter-dig", "counter-cast", "shuffle-after-draw", "remove-after-dig");
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
            if (control.startsWith("counter-")) { add(out, 3, "Island", ZoneType.Battlefield); add(out, 1, "Disallow", ZoneType.Hand); }
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
    private static Object childValue(Player player, String name) {
        try {
            var field = forge.ai.CubeComboPlayerController.class.getDeclaredField("topPlan"); field.setAccessible(true);
            Object parent = field.get(player.getController());
            var childField = parent.getClass().getDeclaredField("harnfelLoop"); childField.setAccessible(true);
            Object child = childField.get(parent); var value = child.getClass().getDeclaredField(name); value.setAccessible(true);
            return value.get(child);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void observe(Player player, String key, int step) {
        try {
            var method = CubeHarnfelTopObservationSmoke.class.getDeclaredMethod("observe", Player.class, String.class, int.class);
            method.setAccessible(true); method.invoke(null, player, key, step);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static final class CounterLobby extends forge.ai.LobbyPlayerAi {
        CounterLobby(int seat) { super("Counter-" + seat, null); setAiProfile("Default"); }
        @Override public Player createIngamePlayer(Game game, int id) {
            Player player = new Player(getName(), game, id);
            player.setFirstController(new CounterController(game, player, this)); return player;
        }
    }
    private static final class CounterController extends forge.ai.PlayerControllerAi {
        private forge.game.spellability.SpellAbility choice, target;
        private boolean attempted, resolved;
        private String control, key;
        CounterController(Game game, Player player, forge.LobbyPlayer lobby) { super(game, player, lobby); }
        @Override public List<forge.game.spellability.SpellAbility> chooseSpellAbilityToPlay() {
            if (!attempted && !getGame().getStack().isEmpty()) {
                var pending = getGame().getStack().peekAbility();
                boolean match = switch(control) {
                    case "counter-draw" -> pending.getApi() == forge.game.ability.ApiType.Draw && pending.getHostCard().getName().equals(TOP);
                    case "counter-dig" -> pending.getApi() == forge.game.ability.ApiType.Dig && pending.getHostCard().getName().equals(HARNFEL);
                    case "counter-cast" -> pending.isSpell() && pending.getHostCard().getName().equals(TOP)
                            && pending.getHostCard().getCastFrom() != null && pending.getHostCard().getCastFrom().getZoneType() == ZoneType.Exile;
                    default -> false;
                };
                if (match && !pending.isCopied() && pending.getActivatingPlayer() != getPlayer()) {
                    for (Card card : getPlayer().getCardsIn(ZoneType.Hand)) if (card.getName().equals("Disallow")) {
                        var action = card.getSpellAbilities().get(0).copy(getPlayer());
                        if (!action.canTarget(pending)) continue;
                        action.resetTargets(); action.getTargets().add(pending);
                        if (!forge.ai.CubeComboAi.canPlayNative(action, getPlayer()) || !forge.ai.CubeComboAi.canPayCost(action, getPlayer(), false)) continue;
                        target = pending; choice = action; return List.of(action);
                    }
                }
            }
            // This registered intervention holds its one counter for the stated
            // target; it cannot spend it opportunistically on another action.
            return null;
        }
        @Override public boolean playChosenSpellAbility(forge.game.spellability.SpellAbility action) {
            if (action != choice) return super.playChosenSpellAbility(action);
            if (getGame().getPhaseHandler().getPriorityPlayer() != getPlayer()) throw new AssertionError("counter without native priority");
            if (childValue(target.getActivatingPlayer(), "pending") != target) throw new AssertionError("counter target not exact owned stack instance");
            boolean played = super.playChosenSpellAbility(action); attempted = true;
            if (!played) throw new AssertionError("counter native payment failed");
            System.out.println("HARNFEL_COUNTER_PLAY " + key + " turn=" + getGame().getPhaseHandler().getTurn()
                    + " phase=" + getGame().getPhaseHandler().getPhase() + " nativePriority=true owned=true played=true target=" + target.getApi()
                    + " original=" + (target.getOriginalAbility() != null) + " tappedIslands="
                    + getPlayer().getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isLand() && c.isTapped()).count());
            return true;
        }
        void report() {
            if (!attempted || resolved || !getGame().getStack().isEmpty()) return;
            if (!getGame().getCardState(choice.getHostCard()).isInZone(ZoneType.Graveyard)) throw new AssertionError("counter did not resolve");
            if (target.isSpell() && !getGame().getCardState(target.getHostCard()).isInZone(ZoneType.Graveyard)) throw new AssertionError("countered Top not in graveyard");
            resolved = true;
            System.out.println("HARNFEL_COUNTER_RESOLVED " + key + " turn=" + getGame().getPhaseHandler().getTurn()
                    + " counterZone=Graveyard stackEmpty=true target=" + target.getApi());
        }
    }
    private static forge.ai.LobbyPlayerAi ai(int seat) {
        var ai = new forge.ai.LobbyPlayerAi("Default-" + seat, null); ai.setAiProfile("Default"); return ai;
    }
    private static void run(boolean improved, boolean observed, int seat, String control) {
        List<RegisteredPlayer> entries = new ArrayList<>();
        for (int s = 0; s < 2; s++) entries.add(new RegisteredPlayer(deck(s == seat, control)).setPlayer(
                s == seat ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s) : control.startsWith("counter-") ? new CounterLobby(s) : ai(s)));
        GameRules rules = new GameRules(GameType.Constructed); rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, entries, "Harnfel observation").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1-seat);
        player.setLife(20, null); opponent.setLife(20, null); populate(player, true, control); populate(opponent, false, control);
        game.setAge(GameStage.Play); int start = seat == 0 ? 1 : 2;
        game.getPhaseHandler().setupFirstTurn(seat == 0 ? player : opponent,
                () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, start));
        game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(988400L + 100L*seat + CASES.indexOf(control));
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("nonzero initial mana");
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " case=" + control;
        System.out.println("HARNFEL_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " observed=" + observed + " registered=40 initialMana=0 startTurn=" + start
                + " face=" + (control.equals("front-face") ? "Birgi" : "Harnfel") + " librarySize=" + player.getCardsIn(ZoneType.Library).size());
        if (opponent.getController() instanceof CounterController counter) { counter.control = control; counter.key = key; }
        boolean intervened = false;
        Set<Integer> seen = new HashSet<>(); int steps = 0, topCasts = 0, exileCasts = 0, harnfelActivations = 0, shots = 0;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= start && steps < 1000) {
            if (!intervened && game.getStack().isEmpty() && (control.equals("shuffle-after-draw") || control.equals("remove-after-dig"))) {
                var pending = (forge.game.spellability.SpellAbility)childValue(player, "pending");
                Card visible = (Card)childValue(player, "visibleTop");
                Card actual = visible == null ? null : game.getCardState(visible, null);
                if (pending != null && actual != null && (control.equals("shuffle-after-draw") && pending.getApi() == forge.game.ability.ApiType.Draw && actual.isInZone(ZoneType.Library)
                        || control.equals("remove-after-dig") && pending.getApi() == forge.game.ability.ApiType.Dig && actual.isInZone(ZoneType.Exile))) {
                    int library = player.getCardsIn(ZoneType.Library).size();
                    if (control.equals("shuffle-after-draw")) player.shuffle(null);
                    else game.getAction().moveToGraveyard(actual, null);
                    if (!Boolean.TRUE.equals(childValue(player, "disrupted"))) throw new AssertionError("intervention did not invalidate owned memory");
                    if (player.getCardsIn(ZoneType.Library).size() != library) throw new AssertionError("intervention changed library size");
                    intervened = true;
                    System.out.println("HARNFEL_INTERVENTION " + key + " step=" + steps + " turn=" + game.getPhaseHandler().getTurn()
                            + " phase=" + game.getPhaseHandler().getPhase() + " nativeAction=true disrupted=true librarySize=" + library
                            + " knownObjectZone=" + game.getCardState(visible, null).getZone().getZoneType());
                }
            }
            if (observed) observe(player, key, steps);
            game.getPhaseHandler().mainLoopStep(); steps++;
            if (opponent.getController() instanceof CounterController counter) counter.report();
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
        if ((control.equals("shuffle-after-draw") || control.equals("remove-after-dig")) && !intervened) throw new AssertionError("required intervention not reached");
        if (opponent.getController() instanceof CounterController counter && (!counter.attempted || !counter.resolved)) throw new AssertionError("required native counter not reached " + key);
        if (control.equals("complete") != player.hasWon()) throw new AssertionError("native interruption endpoint " + key);
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
            System.out.println("HARNFEL_SUITE_COMPLETE cases=12");
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
