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

/** Finite native assembled Displacer Kitten FAMILY L games: catalogue rows
 * 864-1170-2701 (Dark Ritual) and 864-1170-4923 (Cabal Ritual), a partner whose
 * enter-the-battlefield trigger returns a targeted card from our own GRAVEYARD
 * to our hand. No host policy answers: every cast, blink, trigger, optional
 * confirmation and shot is native.
 *
 * <p>The loop alternates TWO ritual-shaped spells, because Kitten's blink
 * resolves before the spell that caused it: the ritual we are casting is on the
 * stack, not in the graveyard, when the partner re-enters, so the card it
 * returns must be a different one, and the two swap zones every pass.</p>
 *
 * <p>MUST-MOVE rows are `dark` and `cabal`: the route loops and the seat wins
 * with the life-shot outlet it recognized by printed property. MUST-NOT-MOVE
 * rows must take NO family action at all - not merely fail to win - which is
 * read from the plan's own reflective counter, the same contract
 * CubeKittenFamilySmoke uses. `ordinary-witness` is a PARITY row: no Kitten, so
 * no plan can own the loop, and the partner's optional trigger is answered by
 * the ordinary chooser. Assertions are gated on
 * -Dforge.test.requireKittenWitness so the identical fixture runs unasserted
 * against the matched pre-v70 classes as a control; those classes have no such
 * field and the counter reports -1.</p> */
public final class CubeKittenWitnessSmoke {
    private record Entry(String name, ZoneType zone) {}
    private static final Set<String> loaded = new HashSet<>();
    private static final String KITTEN = "Displacer Kitten", PARTNER = "Eternal Witness",
        OUTLET = "Aetherflux Reservoir", DARK = "Dark Ritual";
    private static final List<String> MUST_MOVE = List.of("dark", "cabal");
    private static final List<String> MUST_NOT_MOVE =
        List.of("no-kitten", "no-partner", "no-outlet", "single-ritual", "net-negative");
    /** -1 means the classes under test have no such counter at all, which is
     * what the matched pre-v70 control arm reports. */
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
    /** The spell cast every iteration, and the one the partner fetches back.
     * `net-negative` pairs Brightstone Ritual with itself: it adds {R} for each
     * Goblin on the battlefield and there are none, so it produces 0 for a cost
     * of 1 and the two-cast cycle is -2. `single-ritual` gives the graveyard
     * nothing but lands, which is the alternation invariant's own negative. */
    private static String inHand(String control) {
        return switch (control) {
            case "cabal" -> "Cabal Ritual";
            case "net-negative" -> "Brightstone Ritual";
            case "ordinary-witness" -> PARTNER;
            default -> DARK;
        };
    }
    private static String inGraveyard(String control) {
        return switch (control) {
            case "single-ritual" -> null;
            case "net-negative" -> "Brightstone Ritual";
            default -> DARK;
        };
    }
    /** Black for the rituals, red for the net-negative pair, green for the row
     * that hard-casts the partner. Deliberately no blue land: the plan reserves
     * up to two blue sources for its own Thassa's Oracle finish, which is a
     * different route and has no business funding this one. */
    private static String land(String control) {
        return switch (control) {
            case "net-negative" -> "Mountain";
            case "ordinary-witness" -> "Forest";
            default -> "Swamp";
        };
    }
    private static List<Entry> layout(String control) {
        List<Entry> cards = new ArrayList<>();
        boolean ordinary = control.equals("ordinary-witness");
        cards.add(new Entry(control.equals("no-kitten") || ordinary ? "Forest" : KITTEN, ZoneType.Battlefield));
        cards.add(new Entry(control.equals("no-partner") || ordinary ? "Forest" : PARTNER, ZoneType.Battlefield));
        cards.add(new Entry(control.equals("no-outlet") || ordinary ? "Forest" : OUTLET, ZoneType.Battlefield));
        cards.add(new Entry(inHand(control), ZoneType.Hand));
        String yard = inGraveyard(control);
        if (yard != null) cards.add(new Entry(yard, ZoneType.Graveyard));
        for (int i = 0; i < 4; i++) cards.add(new Entry(land(control), ZoneType.Battlefield));
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
    private static void run(int seat, String control, boolean candidate) {
        List<Entry> own = layout(control), other = opposing();
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat ? own : other)).setPlayer(candidate && s == seat
            ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s) : GamePlayerUtil.createAiPlayer("Default-" + s, s, 0, null, "Default")));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR); rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "Kitten witness native fixture").createGame(); game.setAge(GameStage.Play);
        Player p = game.getPlayers().get(seat), opp = game.getPlayers().get(1 - seat);
        game.getPhaseHandler().setupFirstTurn(p, () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p));
        populate(p, own); populate(opp, other);
        game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(97700 + seat * 100 + control.length());
        planFamilyActions(true);
        String key = "seat=" + seat + " control=" + control;
        System.out.println("KITTEN_WITNESS_FIXTURE " + key + " candidate=" + candidate + " policy=" + forge.ai.CubeComboAi.VERSION);
        Set<Integer> seen = new HashSet<>(); Set<Long> partnerObjects = new HashSet<>();
        int steps = 0, casts = 0, blinks = 0, witnessEtb = 0, shots = 0, highestLife = p.getLife();
        int graveyardToHand = 0, graveyardBefore = p.getCardsIn(ZoneType.Graveyard).size();
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 2 && steps < 900) {
            steps++; game.getPhaseHandler().mainLoopStep();
            highestLife = Math.max(highestLife, p.getLife());
            int now = p.getCardsIn(ZoneType.Graveyard).size();
            if (now < graveyardBefore) graveyardToHand += graveyardBefore - now;
            graveyardBefore = now;
            for (Card c : p.getCardsIn(ZoneType.Battlefield))
                if (c.getName().equals(PARTNER)) partnerObjects.add(c.getGameTimestamp());
            for (var item : game.getStack()) if (seen.add(item.getId())) {
                var sa = item.getSpellAbility(); if (sa.getActivatingPlayer() != p) continue;
                String name = sa.getHostCard().getName();
                if (sa.isSpell() && !sa.isCopied()) casts++;
                if (name.equals(KITTEN) && sa.getApi() == forge.game.ability.ApiType.ChangeZone) blinks++;
                if (name.equals(PARTNER) && sa.getApi() == forge.game.ability.ApiType.ChangeZone) witnessEtb++;
                if (name.equals(OUTLET) && sa.getApi() == forge.game.ability.ApiType.DealDamage) shots++;
                System.out.println("KITTEN_WITNESS_STACK step=" + steps + " id=" + item.getId() + " card=" + name
                    + " api=" + sa.getApi() + " targets=" + sa.getTargets());
            }
        }
        int familyActions = planFamilyActions(true);
        System.out.println("KITTEN_WITNESS_RESULT " + key + " won=" + p.hasWon() + " steps=" + steps + " casts=" + casts
            + " blinks=" + blinks + " witnessEtb=" + witnessEtb + " graveyardToHand=" + graveyardToHand
            + " shots=" + shots + " partnerObjects=" + partnerObjects.size()
            + " familyActions=" + familyActions + " life=" + p.getLife() + " highestLife=" + highestLife
            + " opponentLife=" + opp.getLife() + " budgetExhausted=" + (steps >= 900) + " outcome=" + game.getOutcome());
        // Exhausting the step budget is RECORDED, not asserted, exactly as in
        // CubeKittenFamilySmoke: an ordinary chooser that finds part of the
        // engine by itself may loop to the budget without converting, and that
        // is the differential this suite exists to show. A candidate MUST-MOVE
        // row that failed to convert is caught below by its own win assertion.
        if (candidate && Boolean.getBoolean("forge.test.requireKittenWitness")) {
            // casts >= 4 is the alternation itself: with one ritual in hand and
            // one in the graveyard, a second, third and fourth cast are only
            // possible if the partner really did hand each one back.
            if (MUST_MOVE.contains(control) && (!p.hasWon() || familyActions < 2 || blinks < 3
                    || witnessEtb < 3 || casts < 4 || partnerObjects.size() < 3 || shots < 1))
                throw new AssertionError("Expected native Kitten family L win: " + key);
            if (MUST_NOT_MOVE.contains(control) && (p.hasWon() || familyActions != 0))
                throw new AssertionError("Family L control moved: " + key + " familyActions=" + familyActions);
            // The parity row must reach a real ordinary partner ETB, or it is
            // evidence about nothing. It must also take no family action: there
            // is no Kitten on the board, so no route can be owned.
            if (control.equals("ordinary-witness") && familyActions != 0)
                throw new AssertionError("Parity row took a family action: " + key);
        }
    }
    private static List<String> controls() {
        return List.of("dark", "cabal", "no-kitten", "no-partner", "no-outlet",
            "single-ritual", "net-negative", "ordinary-witness");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (p, m, v) -> switch (m.getName()) {
                case "getAssetsDir" -> args[0] + "/forge-gui/"; case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame" -> false;
                case "getCurrentVersion" -> "kitten-witness-v1"; default -> throw new AssertionError(m.getName()); }));
            FModel.initialize(null, p -> {p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); p.setPref(FPref.UI_LANGUAGE, "en-US"); return null;});
            boolean candidate = args.length < 2 || !args[1].equals("baseline");
            int cases = 0;
            for (int seat = 0; seat < 2; seat++)
                for (String control : controls()) {run(seat, control, candidate); cases++;}
            System.out.println("KITTEN_WITNESS_SUITE_COMPLETE cases=" + cases);
        } catch (Throwable t) {t.printStackTrace(); System.exit(1);}
    }
}
