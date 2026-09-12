package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.ai.AiCardMemory;
import forge.ai.CubeComboAi;
import forge.ai.LobbyPlayerCubeComboAi;
import forge.card.MagicColor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** v59 R3. The standing guarantee the ordinary-path divergence diagnosis asks
 * for: where no policy gate fires, the treated seat's ORDINARY answer is
 * identical to Default's - same chosen ability, same seeded-RNG consumption,
 * same live state - and where a gate does fire, it fires exactly where it is
 * registered to.
 *
 * <p>Every position is run TWICE in one JVM: once with the observed seat
 * controlled by {@link LobbyPlayerCubeComboAi} (the `treated` arm) and once with
 * the identical seat controlled by a plain Default {@code LobbyPlayerAi} (the
 * `default` arm). {@link BenchRandomAudit#install} is called per position after
 * game creation, so setup RNG is excluded and both arms start on the same
 * stream. Two observations per position, each on its own freshly built game so
 * neither perturbs the other:
 *
 * <ul>
 * <li>{@code IDENTITY_DECIDE} - one call to
 * {@code PlayerController.chooseSpellAbilityToPlay()} at the prepared priority,
 * with the chosen ability's shape, the RNG draw delta over that one call, and
 * the diff of a live-state map taken before and after.</li>
 * <li>{@code IDENTITY_PLAY} - the same position stepped to turn &lt;= 3 / 200
 * steps, recording every stack item as step|turn|phase|source|api|ours plus the
 * total draw count. This is what makes "activates at the same STEP as Default" a
 * checkable claim rather than a turn-level one.</li>
 * </ul>
 *
 * <p>Prepared positions only. No seed sampling, no panel, no win rate and no
 * playing-strength claim. Nothing here reads a hidden zone: every input is the
 * acting seat's own battlefield and hand, its own registered deck, and both
 * public life totals.</p> */
public final class CubeComboOrdinaryIdentitySmoke {

    private static final String SNEAK = "Sneak Attack";
    private static final String SHOWTELL = "Show and Tell";
    private static final String EMRAKUL = "Emrakul, the Aeons Torn";

    private record Case(String name, PhaseType phase, boolean ownTurn) { }

    /** C1 purity: Show and Tell plus a bomb in hand and NO blue source in the
     * registered deck, so the bomb family forecasts and declines on a real
     * payment probe - and neither ordinary-AI veto shape is present (no
     * {@code AILogic$ BeforeCombat} ability anywhere, no zero-counter-payoff
     * permanent). C2: v52's own `sneak-emrakul` owner board at OUR upkeep, the
     * phase Default acted at in 9 of the 11 diagnosed D4 games. C3: the same
     * board on the OPPONENT'S upkeep, the position v52 D4 was registered for. */
    private static List<Case> cases() {
        // A METHOD, not a static field: naming a PhaseType constant in a static
        // initializer loads that enum -- and its Localizer lookup -- before
        // FModel.initialize has a resource bundle.
        return List.of(
                new Case("showtell-nomana", PhaseType.MAIN1, true),
                new Case("sneak-upkeep", PhaseType.UPKEEP, true),
                new Case("sneak-opponent-upkeep", PhaseType.UPKEEP, false));
    }

    private record Placement(String name, ZoneType zone) { }

    private static List<Placement> placements(String control) {
        List<Placement> result = new ArrayList<>();
        switch (control) {
            case "showtell-nomana" -> {
                // Amendment 1. Two MOUNTAINS, not two Islands: the library is
                // Forests, so with Islands the seat reached {2}{U} on turn 3 by
                // land drops alone and the D1 bomb plan cast Show and Tell - a
                // DESIGNED plan action, not the ordinary-path identity this case
                // is built to measure. With no blue source anywhere in the
                // registered deck the payment probe declines on every pass and
                // the position stays plan-free for the whole bound.
                result.add(new Placement(SHOWTELL, ZoneType.Hand));
                result.add(new Placement(EMRAKUL, ZoneType.Hand));
                for (int i = 0; i < 2; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
            }
            case "sneak-upkeep", "sneak-opponent-upkeep" -> {
                result.add(new Placement(SNEAK, ZoneType.Battlefield));
                result.add(new Placement(EMRAKUL, ZoneType.Hand));
                for (int i = 0; i < 4; i++) result.add(new Placement("Mountain", ZoneType.Battlefield));
            }
            default -> throw new AssertionError("unknown case " + control);
        }
        while (result.size() < 40) result.add(new Placement("Forest", ZoneType.Library));
        return result;
    }

    private static List<Placement> opponentPlacements() {
        List<Placement> result = new ArrayList<>();
        while (result.size() < 40) result.add(new Placement("Forest", ZoneType.Library));
        return result;
    }

    private static Deck deck(List<Placement> placements) {
        Deck result = new Deck("ordinary-identity diagnostic");
        for (Placement p : placements) result.getMain().add(p.name(), 1);
        return result;
    }

    private static void populate(Player player, List<Placement> placements) {
        for (ZoneType z : ZoneType.values()) if (player.getZone(z) != null) player.getZone(z).removeAllCards(true);
        for (Placement p : placements) {
            Card card = Card.fromPaperCard(
                    Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(p.name())), player);
            card.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(p.zone()).add(card);
            card.setSickness(false);
        }
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("initial mana must be empty");
    }

    // --------------------------------------------------------------- receipts

    private static String targetList(SpellAbility sa) {
        TargetChoices targets = sa.getTargets();
        if (targets == null || targets.isEmpty()) return "[]";
        List<String> names = new ArrayList<>();
        for (GameObject object : targets) names.add(name(object));
        return names.toString();
    }

    private static String name(GameObject object) {
        if (object instanceof Card card) return "C" + card.getId();
        if (object instanceof Player player) return "P" + player.getId();
        if (object instanceof SpellAbility sa) return "S" + sa.getId();
        return String.valueOf(object);
    }

    /** Host name, API, printed cost and current targets - the "same object
     * shape" the diagnosis asks the identity assertion to compare. */
    private static String shape(SpellAbility sa) {
        if (sa == null) return "null";
        Card host = sa.getHostCard();
        return (host == null ? "?" : host.getName().replace(' ', '_'))
                + "|" + sa.getApi() + "|" + sa.getPayCosts() + "|" + targetList(sa);
    }

    private static String shapes(List<SpellAbility> chosen) {
        if (chosen == null) return "none";
        List<String> out = new ArrayList<>();
        for (SpellAbility sa : chosen) out.add(shape(sa));
        return out.toString();
    }

    /** {@code CubeComboProbePuritySmoke}'s live-state map, plus a `targets`
     * entry this increment adds so the same map also measures v59 R2. */
    private static Map<String, Object> state(Player p) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var set : AiCardMemory.MemorySet.values())
            result.put(set.name(), AiCardMemory.getMemorySet(p, set).stream().map(Card::getId).sorted().toList());
        result.put("mana", p.getManaPool().totalMana());
        result.put("snowForColor", p.getManaPool().isSnowForColor());
        result.put("conversion", Arrays.asList(
                p.getManaPool().getPossibleColorUses(MagicColor.WHITE),
                p.getManaPool().getPossibleColorUses(MagicColor.BLUE),
                p.getManaPool().getPossibleColorUses(MagicColor.BLACK),
                p.getManaPool().getPossibleColorUses(MagicColor.RED),
                p.getManaPool().getPossibleColorUses(MagicColor.GREEN)));
        result.put("board", p.getCardsIn(ZoneType.Battlefield).stream().map(c -> c.getId() + ":" + c.isTapped()).toList());
        result.put("express", forge.game.card.CardCollection
                .combine(p.getCardsIn(ZoneType.Battlefield), p.getCardsIn(ZoneType.Hand)).stream()
                .flatMap(c -> c.getManaAbilities().stream())
                .map(a -> a.getHostCard().getId() + ":" + a.getManaPart().getExpressChoice()).toList());
        result.put("actors", forge.game.card.CardCollection
                .combine(p.getCardsIn(ZoneType.Battlefield), p.getCardsIn(ZoneType.Hand)).stream()
                .flatMap(c -> c.getSpellAbilities().stream())
                .map(a -> a.getHostCard().getId() + ":"
                        + (a.getActivatingPlayer() == null ? "null" : a.getActivatingPlayer().getId())).toList());
        result.put("targets", forge.game.card.CardCollection
                .combine(p.getCardsIn(ZoneType.Battlefield), p.getCardsIn(ZoneType.Hand)).stream()
                .flatMap(c -> c.getSpellAbilities().stream())
                .map(a -> a.getHostCard().getId() + ":" + a.getApi() + ":" + targetList(a)).toList());
        result.put("life", p.getLife());
        return result;
    }

    private static List<String> diff(Map<String, Object> before, Map<String, Object> after) {
        List<String> changed = new ArrayList<>();
        for (String key : before.keySet())
            if (!before.get(key).equals(after.get(key))) changed.add(key + ":" + before.get(key) + "->" + after.get(key));
        return changed;
    }

    private static long draws() {
        return ((BenchRandomAudit.AuditedRandom) forge.util.MyRandom.getRandom())
                .snapshot().get("draws").getAsLong();
    }

    // ------------------------------------------------------------- the arena

    /** Seat 0 is always the observed seat, so the only difference between the
     * two arms is which controller that seat has. */
    private static Game build(Case scenario, boolean treated) {
        List<Placement> mine = placements(scenario.name());
        List<Placement> theirs = opponentPlacements();
        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(deck(mine)).setPlayer(
                treated ? new LobbyPlayerCubeComboAi("Combo-0") : defaultAi(0)));
        players.add(new RegisteredPlayer(deck(theirs)).setPlayer(defaultAi(1)));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "ordinary-identity diagnostic").createGame();
        Player me = game.getPlayers().get(0), opponent = game.getPlayers().get(1);
        populate(me, mine);
        populate(opponent, theirs);
        game.setAge(GameStage.Play);
        Player active = scenario.ownTurn() ? me : opponent;
        game.getPhaseHandler().setupFirstTurn(active,
                () -> game.getPhaseHandler().devModeSet(scenario.phase(), active));
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        return game;
    }

    /** One fixed seed per case, so the two arms of a case share a stream and two
     * cases never do. */
    private static int seed(Case scenario) { return 20959 + scenario.name().length(); }

    private static void decide(Case scenario, boolean treated) {
        Game game = build(scenario, treated);
        Player me = game.getPlayers().get(0);
        BenchRandomAudit.install(seed(scenario));
        Map<String, Object> before = state(me);
        long start = draws();
        List<SpellAbility> chosen = me.getController().chooseSpellAbilityToPlay();
        long delta = draws() - start;
        System.out.println("IDENTITY_DECIDE case=" + scenario.name() + " arm=" + arm(treated)
                + " policy=" + CubeComboAi.VERSION
                + " turn=" + game.getPhaseHandler().getTurn()
                + " phase=" + game.getPhaseHandler().getPhase()
                + " ourTurn=" + game.getPhaseHandler().isPlayerTurn(me)
                + " draws=" + delta
                + " chosen=" + shapes(chosen)
                + " changed=" + diff(before, state(me)));
    }

    private static void play(Case scenario, boolean treated) {
        Game game = build(scenario, treated);
        Player me = game.getPlayers().get(0), opponent = game.getPlayers().get(1);
        BenchRandomAudit.install(seed(scenario));
        long start = draws();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        List<String> actions = new ArrayList<>();
        int steps = 0;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 3 && steps < 200) {
            game.getPhaseHandler().mainLoopStep();
            steps++;
            for (var item : game.getStack()) if (seen.add(item.getId())) {
                SpellAbility sa = item.getSpellAbility();
                actions.add(steps + "|" + game.getPhaseHandler().getTurn() + "|" + game.getPhaseHandler().getPhase()
                        + "|" + sa.getHostCard().getName().replace(' ', '_') + "|" + sa.getApi()
                        + "|" + (sa.getActivatingPlayer() == me));
            }
        }
        System.out.println("IDENTITY_PLAY case=" + scenario.name() + " arm=" + arm(treated)
                + " policy=" + CubeComboAi.VERSION
                + " steps=" + steps + " turns=" + game.getPhaseHandler().getTurn()
                + " draws=" + (draws() - start)
                + " life=" + me.getLife() + " opponentLife=" + opponent.getLife()
                + " emrakulZone=" + zoneOf(me, EMRAKUL)
                + " actions=" + actions);
    }

    private static String zoneOf(Player player, String name) {
        for (ZoneType z : List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Library,
                ZoneType.Graveyard, ZoneType.Exile))
            for (Card c : player.getCardsIn(z)) if (c.getName().equals(name)) return z.name();
        return "missing";
    }

    private static String arm(boolean treated) { return treated ? "treated" : "default"; }

    // ------------------------------------------------- C1b, v59 R2 measured

    /** The probe-window target guarantee, measured directly and mirroring
     * {@code CubeComboProbePuritySmoke}'s nested/exceptional checks. Reported as
     * a receipt rather than thrown, so the matched v58 control runs to completion
     * and the difference is visible in the log instead of in an exit code. Its
     * own throwaway game: the in-place mutation it performs is deliberately not
     * restored on the control arm. */
    private static void probeTargets() {
        Case purity = cases().get(0);
        Game game = build(purity, true);
        Player me = game.getPlayers().get(0);
        BenchRandomAudit.install(seed(purity));
        Card land = me.getCardsIn(ZoneType.Battlefield).get(0);
        SpellAbility live = land.getSpellAbilities().get(0);
        TargetChoices original = live.getTargets();

        // Shape A: detach-and-add - what all seven policy mutation sites do.
        CubeComboAi.probePayment(me, () -> { live.resetTargets(); live.getTargets().add(me); return null; });
        boolean detach = live.getTargets() == original && live.getTargets().isEmpty();

        // Shape B: a bare in-place add on the very object the probe snapshotted.
        CubeComboAi.probePayment(me, () -> { live.getTargets().add(me); return null; });
        boolean inPlace = live.getTargets() == original && live.getTargets().isEmpty();

        // Shape C: the exceptional exit.
        boolean onThrow;
        try {
            CubeComboAi.probePayment(me, () -> {
                live.resetTargets(); live.getTargets().add(me);
                throw new IllegalArgumentException("intentional target exception");
            });
            throw new AssertionError("Expected target probe exception");
        } catch (IllegalArgumentException expected) {
            if (!"intentional target exception".equals(expected.getMessage())) throw expected;
            onThrow = live.getTargets() == original && live.getTargets().isEmpty();
        }

        System.out.println("IDENTITY_PROBE case=probe-targets arm=treated policy=" + CubeComboAi.VERSION
                + " targetsRestored=" + detach
                + " targetsRestoredInPlace=" + inPlace
                + " targetsRestoredOnThrow=" + onThrow
                + " residue=" + targetList(live));
        // Leave nothing behind regardless of arm; this game is discarded anyway.
        live.setTargets(original);
        original.removeAll(new ArrayList<>(original));
    }

    private static forge.ai.LobbyPlayerAi defaultAi(int seat) {
        var lobby = new forge.ai.LobbyPlayerAi("Default-" + seat, null);
        lobby.setAiProfile("Default");
        return lobby;
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                    new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "cube-combo-ordinary-identity-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (String name : List.of(SNEAK, SHOWTELL, EMRAKUL, "Forest", "Island", "Mountain"))
                StaticData.instance().attemptToLoadCard(name);
            BenchRandomAudit.install(0); // replaced per position; never a game seed.
            List<Case> scenarios = cases();
            for (Case scenario : scenarios) for (boolean treated : new boolean[]{true, false}) {
                decide(scenario, treated);
                play(scenario, treated);
            }
            probeTargets();
            System.out.println("IDENTITY_SUITE_COMPLETE cases=" + scenarios.size()
                    + " policy=" + CubeComboAi.VERSION);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
