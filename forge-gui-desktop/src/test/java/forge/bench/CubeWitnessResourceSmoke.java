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
    /** Synchronous native event receipts. Read names only in own visible zones;
     * a known name can label a later move without revealing library contents. */
    public static final class ResourceObserver {
        private final Player owner;
        private final Map<Integer, String> known = new TreeMap<>();
        int step;
        ResourceObserver(Player owner) {
            this.owner = owner;
            for (ZoneType zone : new ZoneType[]{ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile})
                for (Card card : owner.getCardsIn(zone)) if (!card.isFaceDown()) known.put(card.getId(), card.getName());
        }
        @com.google.common.eventbus.Subscribe
        public void zone(forge.game.event.GameEventCardChangeZone event) {
            var card = event.card();
            if (card == null || !owner.getView().equals(card.getOwner())) return;
            ZoneType to = event.to() == null ? null : event.to().zoneType();
            if (to != null && List.of(ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile).contains(to)
                    && !card.isFaceDown()) known.put(card.getId(), card.getCurrentState().getName());
            String name = known.get(card.getId());
            if (name == null) return;
            System.out.println("WITNESS_EVENT_ZONE step=" + step + " id=" + card.getId() + " card=" + name.replace(' ', '_')
                    + " from=" + (event.from() == null ? null : event.from().zoneType()) + " to=" + to);
        }
        @com.google.common.eventbus.Subscribe
        public void tap(forge.game.event.GameEventCardTapped event) {
            var card = event.card();
            if (card == null || !owner.getView().equals(card.getOwner()) || !known.containsKey(card.getId())) return;
            System.out.println("WITNESS_EVENT_TAP step=" + step + " id=" + card.getId() + " card=" + known.get(card.getId()).replace(' ', '_')
                    + " tapped=" + event.tapped());
        }
    }
    private static String auxiliaryName(String control) {
        return control.equals("white-auxiliary") ? "Mother of Runes" : (control.equals("aux-shroud") || control.equals("partial-before-sac-shroud")) ? "Nimble Mongoose" : "Elvish Mystic";
    }
    private static List<Entry> layout(String engine, String control) {
        List<Entry> cards = new ArrayList<>();
        cards.add(new Entry(control.equals("no-kitten") ? "Forest" : KITTEN, ZoneType.Battlefield));
        cards.add(new Entry(control.equals("no-witness") ? "Forest" : PARTNER, ZoneType.Battlefield));
        cards.add(new Entry(control.equals("no-outlet") ? "Forest" : OUTLET, ZoneType.Battlefield));
        if (engine.equals("snap")) {
            cards.add(new Entry("Snap", control.equals("partial-after-snap") ? ZoneType.Graveyard : ZoneType.Hand));
            cards.add(new Entry(control.equals("no-petal") ? "Forest" : "Lotus Petal",
                    control.equals("partial-after-snap") ? ZoneType.Hand : control.startsWith("partial-before-sac") ? ZoneType.Battlefield : ZoneType.Graveyard));
            cards.add(new Entry(control.equals("no-auxiliary") || control.equals("opposing-creatures")
                    ? "Forest" : auxiliaryName(control), control.startsWith("partial-") ? ZoneType.Hand : ZoneType.Battlefield));
            cards.add(new Entry(control.equals("missing-blue") ? "Forest" : "Island", ZoneType.Battlefield));
            if (!List.of("one-land", "helm-one-land").contains(control)) cards.add(new Entry(
                    List.of("missing-blue", "partial-before-aux-short").contains(control) ? "Forest" : "Island", ZoneType.Battlefield));
            if (control.equals("partial-before-aux-funded")) cards.add(new Entry("Forest", ZoneType.Battlefield));
            if (control.equals("helm-one-land")) cards.add(new Entry("Helm of Awakening", ZoneType.Battlefield));
            if (control.equals("witness-shroud")) cards.add(new Entry("Lightning Greaves", ZoneType.Battlefield));
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
        for (int i = 0; i < (control.equals("short-library") ? 2 : 20); i++) cards.add(new Entry(control.equals("hidden-swamp") ? "Swamp" : control.equals("hidden-mountain") ? "Mountain" : "Forest", ZoneType.Library));
        while (cards.size() < 40) cards.add(new Entry("Forest", ZoneType.Exile));
        if (cards.size() != 40) throw new AssertionError("forty-card layout");
        return cards;
    }
    private static List<Entry> opposing(String control) {
        List<Entry> cards = new ArrayList<>();
        if (control.equals("opposing-creatures"))
            for (int i = 0; i < 4; i++) cards.add(new Entry("Grizzly Bears", ZoneType.Battlefield));
        if (control.equals("draw-cap")) cards.add(new Entry("Narset, Parter of Veils", ZoneType.Battlefield));
        String restriction = switch (control) {
            case "graveyard-shroud" -> "Ground Seal";
            case "no-etb" -> "Torpor Orb";
            case "no-life" -> "Sulfuric Vortex";
            case "protected-opponent" -> "Leyline of Sanctity";
            case "cast-cap" -> "Rule of Law";
            case "nonartifact-cap" -> "Ethersworn Canonist";
            case "activation-off" -> "Stony Silence";
            case "root-maze" -> "Root Maze";
            case "spell-tax" -> "Sphere of Resistance";
            case "activation-tax", "expensive-outlet" -> "Suppression Field";
            case "stasis" -> "Stasis";
            default -> null;
        };
        if (restriction != null) cards.add(new Entry(restriction, ZoneType.Battlefield));
        if (control.equals("expensive-outlet")) cards.add(new Entry(restriction, ZoneType.Battlefield));
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
                    + ":" + c.isTapped() + ":" + c.getView().isTapped() + ":" + c.getPlaneswalkerAbilityActivated() + ":" + c.getCounters(forge.game.card.CounterEnumType.LOYALTY) + ":" + c.getCastFrom() + ":" + c.getCastSA()).toList());
            state.put(zone.name() + "Abilities", player.getCardsIn(zone).stream().flatMap(c -> c.getSpellAbilities().stream())
                    .map(sa -> sa.getHostCard().getId() + ":" + sa.getActivatingPlayer() + ":" + sa.getTargets() + ":" + System.identityHashCode(sa.getTargets())
                            + ":" + (sa.getManaPart() == null ? "null" : sa.getManaPart().getExpressChoice())).toList());
        }
        state.put("librarySize", player.getCardsIn(ZoneType.Library).size());
        state.put("history", game.getStack().getSpellCardsCastThisTurn().stream().map(c -> c.getId() + ":" + c.getCastFrom()).toList());
        return state;
    }
    private static void probeInitial(Player player) {
        Map<String, Object> before = snapshot(player); String first = null;
        System.out.println("WITNESS_QUERY_BEGIN");
        var persistent = new forge.ai.CubeKittenPlan(player);
        for (int repeat = 0; repeat < 6; repeat++) {
            var action = (repeat < 3 ? new forge.ai.CubeKittenPlan(player) : persistent).nextAction();
            String choice = action == null ? "none" : action.getHostCard().getName().replace(' ', '_') + "/" + action.getApi();
            if (first == null) first = choice;
            if (!first.equals(choice)) throw new AssertionError("initial recurrence query is unstable");
            if (!before.equals(snapshot(player))) throw new AssertionError("initial recurrence query changed native state");
        }
        System.out.println("WITNESS_QUERY_END repeats=6 unchanged=true choice=" + first);
        if ("Snap/ChangeZone".equals(first)) {
            System.out.println("WITNESS_OWNERSHIP_BEGIN");
            var plan = new forge.ai.CubeKittenPlan(player);
            var selected = plan.nextAction();
            Card kitten = player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals(KITTEN)).findFirst().orElseThrow();
            Card witness = player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals(PARTNER)).findFirst().orElseThrow();
            var kt = kitten.getTriggers().stream().filter(t -> "SpellCast".equals(t.getParam("Mode"))).findFirst().orElseThrow();
            var blink = forge.game.ability.AbilityFactory.getAbility(kitten.getSVar(kt.getParam("Execute")), kitten);
            blink.setActivatingPlayer(player);
            if (plan.chooseBlink(blink)) throw new AssertionError("unowned missing-cause Kitten trigger");
            var alien = selected.copy(player.getOpponents().get(0));
            blink.setTriggeringObject(forge.game.ability.AbilityKey.SpellAbility, alien);
            if (plan.chooseBlink(blink)) throw new AssertionError("unowned opponent-cause Kitten trigger");
            blink.setTriggeringObject(forge.game.ability.AbilityKey.SpellAbility, witness.getSpellAbilities().get(0).copy(player));
            if (plan.chooseBlink(blink)) throw new AssertionError("unowned other-spell Kitten trigger");
            blink.setTriggeringObject(forge.game.ability.AbilityKey.SpellAbility, selected);
            if (!plan.chooseBlink(blink) || !blink.getTargets().getTargetCards().contains(witness)) throw new AssertionError("owned Kitten target missing");
            var wt = witness.getTriggers().stream().filter(t -> "ChangesZone".equals(t.getParam("Mode"))).findFirst().orElseThrow();
            var restore = forge.game.ability.AbilityFactory.getAbility(witness.getSVar(wt.getParam("Execute")), witness);
            restore.setActivatingPlayer(player);
            restore.setTriggeringObject(forge.game.ability.AbilityKey.Card, witness);
            if (plan.chooseBlink(restore) || plan.confirmFamilyTrigger(restore) != null) throw new AssertionError("stale Witness return acquired");
            if (!before.equals(snapshot(player))) throw new AssertionError("ownership probes changed native state");
            System.out.println("WITNESS_OWNERSHIP checks=6 unchanged=true");
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
        if (control.equals("witness-shroud")) {
            Card greaves = p.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals("Lightning Greaves")).findFirst().orElseThrow();
            Card body = p.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals(PARTNER)).findFirst().orElseThrow();
            greaves.attachToEntity(body, null, true);
        }
        for (Card card : p.getCardsIn(ZoneType.Battlefield)) {
            if (control.equals("no-ready-mana") && (card.isLand() || card.getName().equals(auxiliaryName(control)))) card.setTapped(true);
            if (control.equals("purity-tapped-land") && card.isLand()) { card.setTapped(true); break; }
        }
        game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(98800 + seat * 100 + (engine.equals("snap") ? 0 : 40) + (controls(engine).contains(control) ? controls(engine).indexOf(control) : 200 + boundaries().indexOf(control)));
        String key = "seat=" + seat + " engine=" + engine + " control=" + control;
        System.out.println("WITNESS_RESOURCE_FIXTURE " + key + " candidate=" + candidate + " policy=" + forge.ai.CubeComboAi.VERSION);
        if (candidate) probeInitial(p);
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
        ResourceObserver observer = new ResourceObserver(p); game.subscribeToEvents(observer);
        int turn = -1;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 3 && steps < 2400) {
            steps++; observer.step = steps; game.getPhaseHandler().mainLoopStep();
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
                    if (name.equals(auxiliaryName(control))) auxiliaryCasts++;
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
        for (ZoneType zone : new ZoneType[]{ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard})
            for (Card card : p.getCardsIn(zone)) if (!card.isFaceDown())
                System.out.println("WITNESS_FINAL_CARD zone=" + zone + " id=" + card.getId() + " card=" + card.getName().replace(' ', '_')
                        + " tapped=" + card.isTapped() + " sick=" + card.isSick());
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
    private static List<String> boundaries() {
        return List.of("missing-blue", "no-ready-mana", "aux-shroud", "witness-shroud", "graveyard-shroud", "no-etb",
                "no-life", "protected-opponent", "cast-cap", "nonartifact-cap", "activation-off", "root-maze", "spell-tax",
                "activation-tax", "expensive-outlet", "stasis", "helm-one-land", "white-auxiliary", "partial-after-snap",
                "partial-before-sac", "partial-before-aux-funded", "partial-before-aux-short", "hidden-swamp", "hidden-mountain", "purity-tapped-land", "partial-before-sac-shroud");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (p, m, v) -> switch (m.getName()) {
                case "getAssetsDir" -> args[0] + "/forge-gui/"; case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame" -> false;
                case "getCurrentVersion" -> "witness-resource-v1"; default -> throw new AssertionError(m.getName()); }));
            FModel.initialize(null, p -> {p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); p.setPref(FPref.UI_LANGUAGE, "en-US"); return null;});
            boolean candidate = args.length < 2 || !args[1].equals("baseline");
            int cases = 0;
            boolean boundary = args.length > 2 && args[2].equals("boundaries");
            for (int seat = 0; seat < 2; seat++)
                if (boundary) for (String control : boundaries()) {run(seat, "snap", control, candidate); cases++;}
                else for (String engine : List.of("snap", "frantic"))
                    for (String control : controls(engine)) {run(seat, engine, control, candidate); cases++;}
            System.out.println("WITNESS_RESOURCE_SUITE_COMPLETE cases=" + cases);
        } catch (Throwable t) {t.printStackTrace(); System.exit(1);}
    }
}
