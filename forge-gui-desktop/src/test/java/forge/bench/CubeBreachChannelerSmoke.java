package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Native observation only: no forced choices or strategy changes. */
public final class CubeBreachChannelerSmoke {
    private static final String BREACH = "Underworld Breach", FRANTIC = "Frantic Search";
    private static final String DRC = "Dragon's Rage Channeler", OUTLET = "Aetherflux Reservoir";
    private static final List<String> CASES = List.of("complete", "exact-library", "short-library",
            "no-breach", "no-channeler", "no-outlet", "short-fuel", "no-blue", "two-lands", "low-library");
    private static final List<String> BOUNDARIES = List.of("stun-one", "stun-all", "tapped",
            "draw-cap", "no-life", "protected", "cast-cap", "nonartifact-cap", "activation-off",
            "spell-tax", "activation-tax", "graveyard-replacement", "draw-replacement", "second-channeler",
            "helm", "life20-exact", "life20-short", "hidden-mountain", "prevent-damage");
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);
    private record Placement(String name, ZoneType zone, boolean tapped) { }
    private static void add(List<Placement> into, int count, String name, ZoneType zone) {
        for (int i = 0; i < count; i++) into.add(new Placement(name, zone, false));
    }
    private static List<Placement> own(String name) {
        List<Placement> result = new ArrayList<>();
        if (!name.equals("no-breach")) add(result, 1, BREACH, ZoneType.Battlefield);
        if (!name.equals("no-channeler")) add(result, 1, DRC, ZoneType.Battlefield);
        if (!name.equals("no-outlet")) add(result, 1, OUTLET, ZoneType.Battlefield);
        add(result, name.equals("two-lands") ? 2 : 3,
                name.equals("no-blue") ? "Plains" : "Island", ZoneType.Battlefield);
        add(result, 1, FRANTIC, ZoneType.Graveyard);
        add(result, name.equals("short-fuel") ? 2 : 3, "Forest", ZoneType.Graveyard);
        add(result, 2, "Forest", ZoneType.Hand);
        int library = switch (name) {
            case "exact-library" -> 15;
            case "short-library" -> 14;
            case "low-library" -> 3;
            case "life20-exact" -> 24;
            case "life20-short" -> 23;
            default -> 18;
        };
        if (name.equals("second-channeler")) add(result, 1, DRC, ZoneType.Battlefield);
        if (name.equals("draw-replacement")) add(result, 1, "Alhammarret's Archive", ZoneType.Battlefield);
        if (name.equals("helm")) add(result, 1, "Helm of Awakening", ZoneType.Battlefield);
        add(result, library, name.equals("hidden-mountain") ? "Mountain" : "Forest", ZoneType.Library);
        add(result, 40 - result.size(), "Forest", ZoneType.Exile);
        if (result.size() != 40) throw new AssertionError("own deck size");
        return result;
    }
    private static List<Placement> other(String name) {
        List<Placement> result = new ArrayList<>();
        String restriction = switch (name) {
            case "draw-cap" -> "Narset, Parter of Veils";
            case "no-life" -> "Sulfuric Vortex";
            case "protected" -> "Leyline of Sanctity";
            case "cast-cap" -> "Rule of Law";
            case "nonartifact-cap" -> "Ethersworn Canonist";
            case "activation-off" -> "Damping Matrix";
            case "spell-tax" -> "Thalia, Guardian of Thraben";
            case "activation-tax" -> "Suppression Field";
            case "graveyard-replacement" -> "Rest in Peace";
            case "prevent-damage" -> "Glacial Chasm";
            default -> null;
        };
        if (restriction != null) add(result, 1, restriction, ZoneType.Battlefield);
        add(result, 40 - result.size(), "Forest", ZoneType.Library);
        return result;
    }
    private static List<Placement> placements(boolean owner, String name) {
        return owner ? own(name) : other(name);
    }

    private static Deck deck(boolean owner, String name) {
        Deck result = new Deck("breach-channeler fixture");
        for (Placement p : placements(owner, name)) result.getMain().add(p.name(), 1);
        return result;
    }

    private static void populate(Player player, boolean owner, String name) {
        for (ZoneType z : ZoneType.values()) if (player.getZone(z) != null) player.getZone(z).removeAllCards(true);
        boolean stunned = false;
        for (Placement p : placements(owner, name)) {
            Card card = Card.fromPaperCard(Objects.requireNonNull(
                    FModel.getMagicDb().getCommonCards().getCard(p.name()), p.name()), player);
            card.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(p.zone()).add(card);
            card.setSickness(false);
            if (p.tapped() || owner && name.equals("tapped") && card.isLand() && p.zone() == ZoneType.Battlefield) card.setTapped(true);
            if (p.name().equals("Narset, Parter of Veils")) card.setCounters(forge.game.card.CounterEnumType.LOYALTY, 5);
            if (owner && card.isLand() && p.zone() == ZoneType.Battlefield
                    && (name.equals("stun-all") || name.equals("stun-one") && !stunned)) {
                card.setCounters(forge.game.card.CounterEnumType.STUN, 1); stunned = true;
            }
        }
        TreeMap<String, Integer> actual = new TreeMap<>(), registered = new TreeMap<>();
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) actual.merge(c.getName(), 1, Integer::sum);
        for (var c : player.getRegisteredPlayer().getDeck().getMain()) registered.merge(c.getKey().getName(), c.getValue(), Integer::sum);
        if (!actual.equals(registered) || actual.values().stream().mapToInt(Integer::intValue).sum() != 40)
            throw new AssertionError("registration mismatch " + name + " actual=" + actual + " registered=" + registered);
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("initial mana must be empty");
    }


    private static Map<String, Object> snapshot(Player player) {
        Map<String, Object> state = new LinkedHashMap<>(); Game game = player.getGame();
        state.put("timestamp", game.getTimestamp());
        state.put("rng", ((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot().toString());
        state.put("mana", java.util.stream.StreamSupport.stream(player.getManaPool().spliterator(), false).toList());
        state.put("conversion", java.util.stream.IntStream.range(0, 6)
                .map(i -> player.getManaPool().getPossibleColorUses((byte)(1 << i))).boxed().toList());
        state.put("snow", player.getManaPool().isSnowForColor());
        for (var memory : forge.ai.AiCardMemory.MemorySet.values())
            state.put(memory.name(), forge.ai.AiCardMemory.getMemorySet(player, memory).stream().map(Card::getId).sorted().toList());
        for (ZoneType zone : new ZoneType[]{ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile}) {
            state.put(zone.name(), player.getCardsIn(zone).stream().map(c -> c.getId() + ":" + c.getGameTimestamp()
                    + ":" + c.isTapped() + ":" + c.getView().isTapped() + ":" + c.getPlaneswalkerAbilityActivated() + ":" + c.getCounters() + ":" + c.getCastFrom() + ":" + c.getCastSA()).toList());
            state.put(zone.name() + "Abilities", player.getCardsIn(zone).stream().flatMap(c -> c.getSpellAbilities().stream())
                    .map(sa -> sa.getHostCard().getId() + ":" + sa.getActivatingPlayer() + ":" + sa.getTargets() + ":" + System.identityHashCode(sa.getTargets())
                            + ":" + (sa.getManaPart() == null ? "null" : sa.getManaPart().getExpressChoice())).toList());
        }
        state.put("librarySize", player.getCardsIn(ZoneType.Library).size());
        state.put("history", game.getStack().getSpellCardsCastThisTurn().stream().map(c -> c.getId() + ":" + c.getCastFrom()).toList());
        return state;
    }
    private static void probeInitial(Player player, String key) {
        Map<String, Object> before = snapshot(player);
        String first = null;
        var persistent = new forge.ai.CubeBreachPlan(player);
        for (int i = 0; i < 6; i++) {
            var plan = i < 3 ? new forge.ai.CubeBreachPlan(player) : persistent;
            var action = plan.nextAction();
            String choice = action == null ? "none" : action.getHostCard().getName().replace(' ', '_');
            if (i == 0) first = choice;
            else if (!first.equals(choice)) throw new AssertionError("query drift " + key);
            if (!before.equals(snapshot(player))) throw new AssertionError("initial query mutated native state/RNG " + key);
        }
        System.out.println("CHANNELER_QUERY " + key + " repeats=6 unchanged=true choice=" + first);
    }
    /** Observe the actual partition after native surveil, without reading library identities. */
    public static final class SurveilObserver {
        private final Player owner;
        private final String key;
        int step;
        Card lastMilled;
        boolean probed;
        Throwable probeFailure;
        @com.google.common.eventbus.Subscribe
        public void moved(forge.game.event.GameEventCardChangeZone event) {
            if (event.card() == null || !owner.getView().equals(event.card().getOwner())
                    || event.from() == null || event.to() == null
                    || event.from().zoneType() != ZoneType.Library || event.to().zoneType() != ZoneType.Graveyard) return;
            for (Card card : owner.getCardsIn(ZoneType.Graveyard))
                if (card.getId() == event.card().getId()) lastMilled = card;
        }
        SurveilObserver(Player owner, String key) { this.owner = owner; this.key = key; }
        @com.google.common.eventbus.Subscribe
        public void surveil(forge.game.event.GameEventSurveil event) {
            if (!owner.getView().equals(event.player())) return;
            if (!probed && event.toGraveyard() == 1 && lastMilled != null
                    && owner.getController() instanceof forge.ai.CubeComboPlayerController) {
                try { probeOwnership(); probed = true; }
                catch (Throwable failure) { probeFailure = failure; }
            }
            var resolving = owner.getGame().getStack().peekAbility();
            Object triggering = resolving == null ? null
                    : resolving.getTriggeringObject(forge.game.ability.AbilityKey.SpellAbility);
            var cast = triggering instanceof forge.game.spellability.SpellAbility value ? value : null;
            boolean castOnStack = false;
            if (cast != null) for (var item : owner.getGame().getStack())
                if (item.getSpellAbility() == cast) castOnStack = true;
            System.out.println("CHANNELER_SURVEIL " + key + " step=" + step
                    + " kept=" + event.toLibrary() + " milled=" + event.toGraveyard()
                    + " api=" + (resolving == null ? "none" : resolving.getApi())
                    + " source=" + (resolving == null ? "none" : resolving.getHostCard().getName().replace(' ', '_'))
                    + " actor=" + (resolving != null && resolving.getActivatingPlayer() == owner)
                    + " sourceId=" + (resolving == null ? -1 : resolving.getHostCard().getId())
                    + " sourceTimestamp=" + (resolving == null ? -1 : resolving.getHostCard().getGameTimestamp())
                    + " cast=" + (cast == null ? "none" : cast.getHostCard().getName().replace(' ', '_'))
                    + " castActor=" + (cast != null && cast.getActivatingPlayer() == owner)
                    + " castId=" + (cast == null ? -1 : cast.getHostCard().getId())
                    + " castTimestamp=" + (cast == null ? -1 : cast.getHostCard().getGameTimestamp())
                    + " castOnStack=" + castOnStack);
        }
        private void probeOwnership() throws ReflectiveOperationException {
            var controller = (forge.ai.CubeComboPlayerController) owner.getController();
            var before = snapshot(owner);
            var bf = controller.getClass().getDeclaredField("breachPlan"); bf.setAccessible(true);
            var breach = (forge.ai.CubeBreachPlan) bf.get(controller);
            // This card was just revealed by our real native surveil. Recreate
            // only that permitted offered object, never look up a hidden top.
            Card offeredCard = forge.game.card.CardCopyService.getLKICopy(lastMilled);
            offeredCard.setLastKnownZone(owner.getZone(ZoneType.Library));
            var offered = new forge.game.card.CardCollection(); offered.add(offeredCard);
            int checks = 0;
            for (int i = 0; i < 3; i++) {
                if (!breach.ownsChannelerSurveil(offered)) throw new AssertionError("real cause rejected");
                checks++;
            }
            if (breach.ownsChannelerSurveil(null)) throw new AssertionError("null offered"); checks++;
            if (breach.ownsChannelerSurveil(new forge.game.card.CardCollection())) throw new AssertionError("empty offered"); checks++;
            var wrongZone = new forge.game.card.CardCollection(); wrongZone.add(lastMilled);
            if (breach.ownsChannelerSurveil(wrongZone)) throw new AssertionError("graveyard offered"); checks++;
            Card foreign = forge.game.card.CardCopyService.getLKICopy(offeredCard);
            foreign.setOwner(owner.getOpponents().get(0));
            var wrongOwner = new forge.game.card.CardCollection(); wrongOwner.add(foreign);
            if (breach.ownsChannelerSurveil(wrongOwner)) throw new AssertionError("foreign offered"); checks++;
            var cf = breach.getClass().getDeclaredField("channelerPlan"); cf.setAccessible(true);
            Object plan = cf.get(breach);
            for (String name : List.of("castTimestamp", "sourceTimestamp", "turn", "sourceId", "castId")) {
                var field = plan.getClass().getDeclaredField(name); field.setAccessible(true);
                Object old = field.get(plan);
                try {
                    if (old instanceof Long value) field.setLong(plan, value + 1);
                    else field.setInt(plan, ((Integer) old) + 1);
                    if (breach.ownsChannelerSurveil(offered)) throw new AssertionError("stale " + name);
                    checks++;
                } finally { field.set(plan, old); }
            }
            var top = owner.getGame().getStack().peekAbility();
            var key = forge.game.ability.AbilityKey.SpellAbility;
            Object oldCause = top.getTriggeringObject(key);
            var cast = (forge.game.spellability.SpellAbility) oldCause;
            try {
                var copy = cast.copy(owner);
                top.setTriggeringObject(key, copy);
                if (breach.ownsChannelerSurveil(offered)) throw new AssertionError("unstacked copy"); checks++;
                copy.setActivatingPlayer(owner.getOpponents().get(0));
                if (breach.ownsChannelerSurveil(offered)) throw new AssertionError("wrong actor"); checks++;
                top.setTriggeringObject(key, null);
                if (breach.ownsChannelerSurveil(offered)) throw new AssertionError("missing cause"); checks++;
            } finally { top.setTriggeringObject(key, oldCause); }
            if (!breach.ownsChannelerSurveil(offered)) throw new AssertionError("restored cause rejected"); checks++;
            if (!before.equals(snapshot(owner))) throw new AssertionError("ownership probe changed native state/RNG");
            System.out.println("CHANNELER_OWNERSHIP " + key() + " checks=" + checks + " queries=3 unchanged=true");
        }
        private String key() { return key; }
    }
    private static forge.ai.LobbyPlayerAi defaultAi(int seat) {
        var lobby = new forge.ai.LobbyPlayerAi("Default-" + seat, null);
        lobby.setAiProfile("Default");
        return lobby;
    }
    private static void run(boolean improved, int seat, String name) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat, name)).setPlayer(
                improved && s == seat ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s) : defaultAi(s)));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "native breach-channeler diagnosis").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, name); populate(opponent, false, name);
        player.setLife(name.startsWith("life20-") ? 20 : 40, null);
        game.setAge(GameStage.Play);
        int startTurn = seat == 0 ? 1 : 2;
        game.getPhaseHandler().setupFirstTurn(seat == 0 ? player : opponent,
                () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, startTurn));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(987100L + 100L * seat + (CASES.contains(name) ? CASES.indexOf(name) : 1000 + BOUNDARIES.indexOf(name)));
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " case=" + name;
        System.out.println("CHANNELER_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION
                + " ownLife=" + player.getLife() + " registered=40 initialMana=0 startTurn=" + startTurn
                + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size());
        if (improved && BOUNDARIES.contains(name)) probeInitial(player, key);
        SurveilObserver observer = new SurveilObserver(player, key);
        game.subscribeToEvents(observer);
        Set<Integer> ids = new HashSet<>();
        int steps = 0, frantic = 0, escapes = 0, surveilTriggers = 0;
        String previous = "";
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= startTurn + 1 && steps < 900) {
            observer.step = steps + 1;
            game.getPhaseHandler().mainLoopStep(); steps++;
            if (observer.probeFailure != null) throw new AssertionError("synchronous ownership probe failed", observer.probeFailure);
            for (var item : game.getStack()) if (ids.add(item.getId())) {
                var sa = item.getSpellAbility();
                if (sa.getActivatingPlayer() != player) continue;
                String host = sa.getHostCard().getName();
                if (sa.isSpell() && host.equals(FRANTIC)) {
                    frantic++; if (sa.isEscape()) escapes++;
                }
                if (!sa.isSpell() && host.equals(DRC)) surveilTriggers++;
                System.out.println("CHANNELER_STACK " + key + " step=" + steps + " source="
                        + host.replace(' ', '_') + " api=" + sa.getApi() + " spell=" + sa.isSpell()
                        + " escape=" + sa.isEscape() + " sourceId=" + sa.getHostCard().getId()
                        + " timestamp=" + sa.getHostCard().getGameTimestamp());
            }
            String state = "turn=" + game.getPhaseHandler().getTurn() + " phase=" + game.getPhaseHandler().getPhase()
                    + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size()
                    + " ownHand=" + player.getCardsIn(ZoneType.Hand).size()
                    + " graveyard=" + player.getCardsIn(ZoneType.Graveyard).size()
                    + " exile=" + player.getCardsIn(ZoneType.Exile).size()
                    + " life=" + player.getLife() + " opponentLife=" + opponent.getLife()
                    + " mana=" + player.getManaPool().totalMana();
            if (!state.equals(previous)) System.out.println("CHANNELER_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 900) throw new AssertionError("native step budget exhausted " + key);
        System.out.println("CHANNELER_RESULT " + key + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " steps=" + steps + " turn=" + game.getPhaseHandler().getTurn()
                + " franticCasts=" + frantic + " escapes=" + escapes + " observedDrcTriggers=" + surveilTriggers
                + " ownLibrary=" + player.getCardsIn(ZoneType.Library).size()
                + " ownLife=" + player.getLife() + " opponentLife=" + opponent.getLife()
                + " outcome=" + game.getOutcome());
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "breach-channeler-diagnosis-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US"); return null;
            });
            for (String card : List.of(BREACH, FRANTIC, DRC, OUTLET, "Forest", "Plains", "Island", "Mountain", "Narset, Parter of Veils", "Sulfuric Vortex",
                    "Leyline of Sanctity", "Rule of Law", "Ethersworn Canonist", "Damping Matrix", "Thalia, Guardian of Thraben",
                    "Suppression Field", "Rest in Peace", "Glacial Chasm", "Alhammarret's Archive", "Helm of Awakening"))
                StaticData.instance().attemptToLoadCard(card);
            List<String> selected = args.length > 2 && args[2].equals("boundaries") ? BOUNDARIES : CASES;
            for (String name : selected) for (int seat = 0; seat < 2; seat++) run(args[1].equals("improved"), seat, name);
            System.out.println("CHANNELER_SUITE_COMPLETE cases=" + (selected.size() * 2));
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
