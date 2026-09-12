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

/** Finite native assembled Displacer Kitten FAMILY games: catalogue family M
 * (a partner whose mandatory enter-the-battlefield trigger returns the loop
 * object from the STACK) and family N (a partner granting a per-turn-limited
 * cast permission from our own graveyard, which the blink resets). No host
 * policy answers: every cast, blink, trigger and shot is native.
 *
 * <p>MUST-MOVE is the `none` control: the route loops and the seat wins with
 * the life-shot outlet it recognized by printed property. MUST-NOT-MOVE rows
 * must take NO family action at all - not merely fail to win - which is read
 * from the plan's own reflective counter, the same contract the property suite
 * uses for {@code planRockActions}. Assertions are gated on
 * -Dforge.test.requireKittenFamily so the identical fixture runs unasserted
 * against the matched pre-v65 classes as a control; those classes have no such
 * field and the counter reports -1.</p> */
public final class CubeKittenFamilySmoke {
    private record Entry(String name, ZoneType zone) {}
    private static final Set<String> loaded = new HashSet<>();
    private static final String KITTEN = "Displacer Kitten", OUTLET = "Aetherflux Reservoir";
    private static final List<String> MUST_NOT_MOVE =
        List.of("no-kitten", "no-partner", "no-outlet", "no-loop", "costly-loop", "discard-loop");
    /** -1 means the classes under test have no such counter at all, which is
     * what the matched pre-v65 control arm reports. */
    private static int planFamilyActions(boolean reset) {
        try {
            java.lang.reflect.Field field;
            try {field=forge.ai.CubeKittenPlan.class.getDeclaredField("planFamilyActions");}
            catch(NoSuchFieldException absent) {return -1;}
            field.setAccessible(true);
            int value=field.getInt(null);
            if(reset) field.setInt(null,0);
            return value;
        } catch(ReflectiveOperationException e) {throw new AssertionError(e);}
    }
    private static forge.item.PaperCard paper(String name) {
        if (loaded.add(name)) StaticData.instance().attemptToLoadCard(name);
        return Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name),name);
    }
    private static String partner(String route) {
        return route.equals("venser") ? "Venser, Shaper Savant" : "Lurrus of the Dream-Den";
    }
    /** The loop object's home zone is the route's own restore zone: family M
     * catches it on the stack and returns it to hand, family N recasts it out
     * of the graveyard. */
    private static ZoneType home(String route) {
        return route.equals("venser") ? ZoneType.Hand : ZoneType.Graveyard;
    }
    private static List<Entry> layout(String route, String control) {
        List<Entry> cards = new ArrayList<>();
        cards.add(new Entry(control.equals("no-kitten") ? "Forest" : KITTEN, ZoneType.Battlefield));
        cards.add(new Entry(control.equals("no-partner") ? "Forest" : partner(route), ZoneType.Battlefield));
        cards.add(new Entry(control.equals("no-outlet") ? "Forest" : OUTLET, ZoneType.Battlefield));
        // costly-loop: a noncreature the route can see but that is not free, so
        // the iteration would be net-negative. discard-loop: free to cast, but
        // its self-sacrifice also discards our hand, which is a resource the
        // plan's cost allow-list refuses to spend.
        String loop = switch (control) {
            case "no-loop" -> null;
            case "costly-loop" -> "Sol Ring";
            case "discard-loop" -> "Lion's Eye Diamond";
            default -> "Lotus Petal";
        };
        if (loop != null) cards.add(new Entry(loop, home(route)));
        cards.add(new Entry("Island", ZoneType.Battlefield));
        cards.add(new Entry("Island", ZoneType.Battlefield));
        cards.add(new Entry("Plains", ZoneType.Battlefield));
        cards.add(new Entry("Plains", ZoneType.Battlefield));
        for (int i = 0; i < 15; i++) cards.add(new Entry("Forest", ZoneType.Library));
        while (cards.size() < 40) cards.add(new Entry("Forest", ZoneType.Graveyard));
        return cards;
    }
    private static List<Entry> opposing() {
        List<Entry> cards = new ArrayList<>();
        for (int i = 0; i < 30; i++) cards.add(new Entry("Forest", ZoneType.Library));
        while (cards.size() < 40) cards.add(new Entry("Forest", ZoneType.Graveyard));
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
        }
    }
    private static void run(int seat, String route, String control, boolean candidate) {
        List<Entry> own = layout(route, control), other = opposing();
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat ? own : other)).setPlayer(candidate && s == seat
            ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s) : GamePlayerUtil.createAiPlayer("Default-" + s, s, 0, null, "Default")));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR); rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "Kitten family native fixture").createGame(); game.setAge(GameStage.Play);
        Player p = game.getPlayers().get(seat), opp = game.getPlayers().get(1 - seat);
        game.getPhaseHandler().setupFirstTurn(p, () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p));
        populate(p, own); populate(opp, other);
        game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(96700 + seat * 100 + route.length() + control.length());
        planFamilyActions(true);
        String key = "seat=" + seat + " route=" + route + " control=" + control;
        System.out.println("KITTEN_FAMILY_FIXTURE " + key + " candidate=" + candidate + " policy=" + forge.ai.CubeComboAi.VERSION);
        Set<Integer> seen = new HashSet<>(); Set<Long> partnerObjects = new HashSet<>();
        int steps = 0, casts = 0, blinks = 0, returns = 0, shots = 0, highestLife = p.getLife();
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 2 && steps < 900) {
            steps++; game.getPhaseHandler().mainLoopStep();
            highestLife = Math.max(highestLife, p.getLife());
            for (Card c : p.getCardsIn(ZoneType.Battlefield))
                if (c.getName().equals(partner(route))) partnerObjects.add(c.getGameTimestamp());
            for (var item : game.getStack()) if (seen.add(item.getId())) {
                var sa = item.getSpellAbility(); if (sa.getActivatingPlayer() != p) continue;
                String name = sa.getHostCard().getName();
                if (sa.isSpell() && !sa.isCopied()) casts++;
                if (name.equals(KITTEN) && sa.getApi() == forge.game.ability.ApiType.ChangeZone) blinks++;
                if (name.equals(partner(route)) && sa.getApi() == forge.game.ability.ApiType.ChangeZone) returns++;
                if (name.equals(OUTLET) && sa.getApi() == forge.game.ability.ApiType.DealDamage) shots++;
                System.out.println("KITTEN_FAMILY_STACK step=" + steps + " id=" + item.getId() + " card=" + name
                    + " api=" + sa.getApi() + " targets=" + sa.getTargets());
            }
        }
        int familyActions = planFamilyActions(true);
        System.out.println("KITTEN_FAMILY_RESULT " + key + " won=" + p.hasWon() + " steps=" + steps + " casts=" + casts
            + " blinks=" + blinks + " returns=" + returns + " shots=" + shots + " partnerObjects=" + partnerObjects.size()
            + " familyActions=" + familyActions + " life=" + p.getLife() + " highestLife=" + highestLife
            + " opponentLife=" + opp.getLife() + " budgetExhausted=" + (steps >= 900) + " outcome=" + game.getOutcome());
        // Exhausting the step budget is RECORDED, not asserted. The ordinary
        // Forge AI runs the family-M engine by itself and never converts it,
        // so a Default arm on an assembled board loops to the budget by
        // design; that is the differential this suite exists to show. A
        // candidate MUST-MOVE row that failed to convert is caught below by
        // its own win assertion instead.
        if (candidate && Boolean.getBoolean("forge.test.requireKittenFamily")) {
            if (control.equals("none") && (!p.hasWon() || familyActions < 2 || blinks < 3
                    || partnerObjects.size() < 3 || shots < 1))
                throw new AssertionError("Expected native Kitten family win: " + key);
            if (MUST_NOT_MOVE.contains(control) && (p.hasWon() || familyActions != 0))
                throw new AssertionError("Family control moved: " + key + " familyActions=" + familyActions);
        }
    }
    private static List<String> controls(String route) {
        List<String> controls = new ArrayList<>(List.of("none", "no-kitten", "no-partner", "no-outlet", "no-loop", "costly-loop"));
        // Lion's Eye Diamond is a legal family-M loop object (the spell is
        // bounced off the stack, so its activated ability is never paid), and
        // only a graveyard recursion route has to read that cost at all.
        if (route.equals("lurrus")) controls.add("discard-loop");
        return controls;
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (p, m, v) -> switch (m.getName()) {
                case "getAssetsDir" -> args[0] + "/forge-gui/"; case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame" -> false;
                case "getCurrentVersion" -> "kitten-family-v1"; default -> throw new AssertionError(m.getName()); }));
            FModel.initialize(null, p -> {p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); p.setPref(FPref.UI_LANGUAGE, "en-US"); return null;});
            boolean candidate = args.length < 2 || !args[1].equals("baseline");
            int cases = 0;
            for (int seat = 0; seat < 2; seat++)
                for (String route : List.of("venser", "lurrus"))
                    for (String control : controls(route)) {run(seat, route, control, candidate); cases++;}
            System.out.println("KITTEN_FAMILY_SUITE_COMPLETE cases=" + cases);
        } catch (Throwable t) {t.printStackTrace(); System.exit(1);}
    }
}
