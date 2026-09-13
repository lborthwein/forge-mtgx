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
 * No scripted action or host answer. Explicit public fixture interventions are
 * labelled separately. Repeated own turns are
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
    private static Map<String, Object> visibleSnapshot(Player player) {
        Map<String, Object> state = new LinkedHashMap<>();
        Game game = player.getGame();
        state.put("timestamp", game.getTimestamp());
        state.put("rng", ((BenchRandomAudit.AuditedRandom) forge.util.MyRandom.getRandom()).snapshot().toString());
        state.put("mana", player.getManaPool().totalMana());
        state.put("manaObjects", java.util.stream.StreamSupport.stream(player.getManaPool().spliterator(), false).toList());
        for (var memory : forge.ai.AiCardMemory.MemorySet.values())
            state.put(memory.name(), forge.ai.AiCardMemory.getMemorySet(player, memory).stream().map(Card::getId).sorted().toList());
        for (ZoneType zone : new ZoneType[]{ZoneType.Hand, ZoneType.Graveyard, ZoneType.Battlefield, ZoneType.Exile}) {
            state.put(zone.name(), player.getCardsIn(zone).stream()
                    .map(c -> c.getId() + ":" + c.getGameTimestamp() + ":" + c.isTapped() + ":" + c.getCastFrom()
                            + ":" + c.getCastSA()).toList());
            state.put(zone.name() + "Abilities", player.getCardsIn(zone).stream().flatMap(c -> c.getSpellAbilities().stream())
                    .map(a -> a.getHostCard().getId() + ":" + a.getActivatingPlayer() + ":" + a.getTargets()
                            + ":" + (a.getManaPart() == null ? "null" : a.getManaPart().getExpressChoice())).toList());
        }
        state.put("history", game.getStack().getSpellCardsCastThisTurn().stream()
                .map(c -> c.getId() + ":" + c.getCastFrom() + ":" + c.getController()).toList());
        state.put("stack", java.util.stream.StreamSupport.stream(game.getStack().spliterator(), false)
                .map(a -> a.getId() + ":" + a.getSpellAbility().getTargets()).toList());
        return state;
    }
    private static void checkPurity(Player player, forge.game.spellability.SpellAbility ability, boolean candidate) {
        while (ability instanceof forge.game.trigger.WrappedAbility wrapper) ability = wrapper.getWrappedAbility();
        var query = ability.copy(player);
        query.resetTargets();
        var choices = new forge.game.card.CardCollection(player.getCardsIn(ZoneType.Graveyard));
        Card ordinary = choices.stream().filter(c -> c.getName().equals("Time Walk")).findFirst().orElseThrow();
        Map<String, Object> before = visibleSnapshot(player);
        for (int repeat = 0; repeat < 3; repeat++) {
            Card preferred = forge.ai.CubeExtraTurnPlan.preferRecurrence(player, query, choices, ordinary);
            if (candidate ? preferred == null || !preferred.getName().equals("Ephemerate") : preferred != null)
                throw new AssertionError("unexpected preference in purity query");
            if (!before.equals(visibleSnapshot(player))) throw new AssertionError("recurrence query changed native state");
        }
        System.out.println("EXTRA_TURN_PURITY queries=3 unchanged=true candidate=" + candidate);
    }
    private static void run(int seat, String engine, String turnSpell, String control, boolean candidate) {
        List<Entry> own = new ArrayList<>(), other = new ArrayList<>();
        own.add(new Entry(control.equals("no-witness") ? "Forest" : "Eternal Witness", ZoneType.Battlefield));
        own.add(new Entry(control.equals("no-engine") ? "Forest" : engine,
                engine.equals("Ephemerate") ? ZoneType.Hand : ZoneType.Battlefield));
        own.add(new Entry(control.equals("no-turn-spell") ? "Forest" : turnSpell, ZoneType.Hand));
        if (control.equals("conversion")) own.add(new Entry("Dauthi Voidwalker", ZoneType.Battlefield));
        own.add(new Entry("Plains", ZoneType.Battlefield));
        for (int i = 0; i < 5; i++) own.add(new Entry(i == 0 && control.endsWith("two-white") ? "Plains" : "Island", ZoneType.Battlefield));
        for (int i = 0; i < 20; i++) own.add(new Entry("Forest", ZoneType.Library));
        while (own.size() < 40) own.add(new Entry("Forest", ZoneType.Exile));
        if (control.equals("conversion")) other.add(new Entry("Old One Eye", ZoneType.Battlefield));
        if (control.startsWith("tax-")) other.add(new Entry("Sphere of Resistance", ZoneType.Exile));
        if (control.equals("cast-cap")) other.add(new Entry("Rule of Law", ZoneType.Exile));
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
        populate(player, own); populate(opponent, other); opponent.setLife(control.equals("conversion") ? 20 : 40, null);
        if (control.equals("conversion")) player.setLife(4, null);
        game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(830913L + seat);
        String key = "seat=" + seat + " engine=" + engine.replace(' ', '_') + " spell=" + turnSpell.replace(' ', '_')
                + " control=" + control + " candidate=" + candidate + " policy=" + forge.ai.CubeComboAi.VERSION;
        System.out.println("EXTRA_TURN_FIXTURE " + key);
        Set<Integer> seen = new HashSet<>();
        int steps = 0, turnCasts = 0, engineActions = 0, returns = 0, ownTurns = 0, opponentTurns = 0;
        int lastTurn = -1, streak = 0, longest = 0;
        boolean intervention = false;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 12 && steps < 4000) {
            int turn = game.getPhaseHandler().getTurn();
            if (turn != lastTurn) {
                boolean ours = game.getPhaseHandler().getPlayerTurn() == player;
                if (ours) {ownTurns++; streak++; longest = Math.max(longest, streak);} else {opponentTurns++; streak = 0;}
                System.out.println("EXTRA_TURN_TURN turn=" + turn + " ours=" + ours + " hand="
                        + player.getCardsIn(ZoneType.Hand) + " graveyard=" + player.getCardsIn(ZoneType.Graveyard));
                lastTurn = turn;
            }
            // Explicit public-state fixture controls at the first real rebound
            // upkeep. Cast/trigger history is untouched; no target is scripted.
            if (!intervention && turn == 2 && game.getPhaseHandler().getPhase() == PhaseType.UPKEEP
                    && game.getPhaseHandler().getPlayerTurn() == player && !control.equals("none")
                    && !control.startsWith("no-") && !control.equals("conversion") && !control.equals("purity")) {
                int islandsKept = switch (control) {
                    case "mana-two" -> 1;
                    case "mana-three" -> 2;
                    case "missing-blue" -> 0;
                    case "tax-short" -> 4;
                    case "tax-short-two-white" -> 3;
                    default -> 5;
                };
                int islands = 0;
                for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
                    if (card.getName().equals("Island") && ++islands > islandsKept) card.setTapped(true);
                    if (card.getName().equals("Plains") && control.equals("missing-white")) card.setTapped(true);
                }
                for (Card card : new ArrayList<Card>(opponent.getCardsIn(ZoneType.Exile)))
                    if (card.getName().equals("Sphere of Resistance") || card.getName().equals("Rule of Law"))
                        game.getAction().moveToPlay(card, null, forge.game.ability.AbilityKey.newMap());
                game.getAction().checkStateEffects(true);
                intervention = true;
                System.out.println("EXTRA_TURN_INTERVENTION control=" + control + " turn=" + turn);
            }
            steps++; game.getPhaseHandler().mainLoopStep();
            for (var item : game.getStack()) if (seen.add(item.getId())) {
                var ability = item.getSpellAbility(); if (ability.getActivatingPlayer() != player) continue;
                String name = ability.getHostCard().getName();
                if (ability.isSpell() && !ability.isCopied() && name.equals(turnSpell)) turnCasts++;
                if (name.equals(engine)) engineActions++;
                if (name.equals("Eternal Witness") && ability.getApi() == forge.game.ability.ApiType.ChangeZone) {
                    returns++;
                    if (control.equals("purity") && game.getPhaseHandler().getTurn() == 2
                            && game.getPhaseHandler().getPhase() == PhaseType.UPKEEP)
                        checkPurity(player, ability, candidate);
                    if (control.startsWith("tax-") && game.getPhaseHandler().getPhase() == PhaseType.UPKEEP) {
                        System.out.println("EXTRA_TURN_RESOURCES turn=" + game.getPhaseHandler().getTurn()
                                + " lands=" + player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isLand)
                                .map(c -> c.getName().replace(' ', '_') + ":" + (c.isTapped() ? "tapped" : "ready"))
                                .collect(java.util.stream.Collectors.joining(";")));
                    }
                }
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
            if (args.length > 2 && args[2].equals("extended")) {
                for (int seat = 0; seat < 2; seat++) for (String control : List.of(
                        "tax-funded-two-white", "tax-short-two-white", "purity")) {
                    run(seat, "Ephemerate", "Time Walk", control, candidate); cases++;
                }
                System.out.println("EXTRA_TURN_EXTENDED_COMPLETE cases=" + cases + " candidate=" + candidate);
                return;
            } else if (args.length > 2 && args[2].equals("guards")) {
                for (int seat = 0; seat < 2; seat++) for (String control : List.of("mana-two", "mana-three", "missing-white",
                        "missing-blue", "tax-funded", "tax-short", "cast-cap", "conversion")) {
                    run(seat, "Ephemerate", "Time Walk", control, candidate); cases++;
                }
                System.out.println("EXTRA_TURN_GUARDS_COMPLETE cases=" + cases + " candidate=" + candidate);
                return;
            }
            for (int seat = 0; seat < 2; seat++) for (String engine : List.of("Ephemerate", "Soulherder", "Kiki-Jiki, Mirror Breaker"))
                for (String spell : List.of("Time Walk", "Time Warp")) for (String control : List.of("none", "no-witness", "no-engine", "no-turn-spell")) {
                    run(seat, engine, spell, control, candidate); cases++;
                }
            System.out.println("EXTRA_TURN_SUITE_COMPLETE cases=" + cases + " candidate=" + candidate);
        } catch (Throwable failure) {failure.printStackTrace(); System.exit(1);}
    }
}
