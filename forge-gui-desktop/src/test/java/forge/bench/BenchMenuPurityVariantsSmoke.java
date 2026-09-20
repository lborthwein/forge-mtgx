package forge.bench;

import forge.StaticData;
import forge.ai.ComputerUtilAbility;
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
import forge.player.GamePlayerUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/** Real production no-op probes on optional costs, MDFCs and exile permissions. */
public final class BenchMenuPurityVariantsSmoke {
    private record Fixture(Game game, Player player, ByteArrayOutputStream wire) {}
    private static Fixture fixture() {
        var wire = new ByteArrayOutputStream();
        var session = new BenchSession(new JsonRpcChannel(new ByteArrayInputStream(new byte[0]), wire));
        var lobby = new LobbyPlayerBridge("Payer", null, session, BenchSession.Mode.BRIDGE, 0);
        lobby.setAiProfile("Default");
        var registered = List.of(new RegisteredPlayer(new Deck()).setPlayer(lobby),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), registered, "Menu purity fixture").createGame();
        var player = game.getPlayers().get(0);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player);
        return new Fixture(game, player, wire);
    }
    private static Card card(String name, Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var paper = FModel.getMagicDb().getCommonCards().getCard(name);
        if (paper == null) throw new AssertionError("Missing actual card " + name);
        var result = Card.fromPaperCard(paper, player);
        result.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(result);
        result.setSickness(false);
        return result;
    }
    private static void probe(Fixture f, String label) {
        var before = BenchMenuStateAudit.capture(f.game());
        BenchRandomAudit.install(8110);
        var randomBefore = BenchRandomAudit.begin();
        for (int i = 0; i < 3; i++) ((PlayerControllerBridge) f.player().getController()).probePriorityMenuPurity();
        BenchRandomAudit.assertUnchanged(randomBefore, label);
        BenchMenuStateAudit.assertUnchanged(before, f.game());
        if (f.wire().size() != 0) throw new AssertionError("No-op probe asked the host");
        System.out.println("PASS original fields/IDs/memory and no-RPC probe: " + label);
    }
    private static void assertTimestamp(long before, Fixture f, String label) {
        if (before != f.game().getTimestamp())
            throw new AssertionError("Read-only Prototype query advanced game timestamp: " + label);
        System.out.println("PASS Prototype timestamp " + before + " unchanged: " + label);
    }
    private static void optional() {
        var f = fixture();
        for (String name : List.of("Forest", "Forest", "Forest", "Mountain", "Plains")) card(name, f.player(), ZoneType.Battlefield);
        var host = card("Thornscape Battlemage", f.player(), ZoneType.Hand);
        f.game().getAction().checkStateEffects(true);
        var original = host.getFirstSpellAbility();
        original.setActivatingPlayer(f.game().getPlayers().get(1));
        original.getPipsToReduce().add("R");
        original.setXManaCostPaid(7);
        probe(f, "dual kicker with nonempty original pips, foreign actor and X");
        var before = BenchMenuStateAudit.capture(f.game());
        var abilities = BenchmarkAbilityEnumeration.spells(ComputerUtilAbility.getAvailableCards(f.game(), f.player()), f.player());
        var mana = abilities.stream().filter(a -> a.isSpell() && a.getHostCard() == host)
                .map(a -> a.getPayCosts().getTotalMana().toString()).collect(java.util.stream.Collectors.toSet());
        if (!mana.equals(java.util.Set.of("{2}{G}", "{2}{R}{G}", "{2}{W}{G}", "{2}{W}{R}{G}")))
            throw new AssertionError("Lost optional subsets: " + mana);
        BenchMenuStateAudit.assertUnchanged(before, f.game());
    }
    private static void modal() {
        var f = fixture();
        var pathway = card("Barkchannel Pathway", f.player(), ZoneType.Hand);
        card("Bala Ged Recovery", f.player(), ZoneType.Hand);
        for (int i = 0; i < 3; i++) card("Forest", f.player(), ZoneType.Battlefield);
        card("Savannah Lions", f.player(), ZoneType.Graveyard);
        f.game().getAction().checkStateEffects(true);
        probe(f, "MDFC land/land and spell/land");
        var before = BenchMenuStateAudit.capture(f.game());
        var lands = pathway.getAllPossibleAbilities(f.player(), true, null, true).stream().filter(a -> a.isLandAbility() && a.canPlay()).toList();
        if (lands.size() != 2 || lands.get(0).getCardStateName() == lands.get(1).getCardStateName())
            throw new AssertionError("Both Pathway faces must survive enumeration");
        BenchMenuStateAudit.assertUnchanged(before, f.game());
    }
    private static void alternateCosts() {
        var f = fixture();
        var host = card("Bringer of the Blue Dawn", f.player(), ZoneType.Hand);
        for (String name : List.of("Plains", "Island", "Swamp", "Mountain", "Forest")) card(name, f.player(), ZoneType.Battlefield);
        f.game().getAction().checkStateEffects(true);
        probe(f, "alternative ordinary mana cost retains distinct LKI candidates");
        var before = BenchMenuStateAudit.capture(f.game());
        var abilities = BenchmarkAbilityEnumeration.spells(ComputerUtilAbility.getAvailableCards(f.game(), f.player()), f.player());
        var costs = abilities.stream().filter(a -> a.isSpell() && a.getHostCard() == host)
                .map(a -> a.getPayCosts().getTotalMana().toString()).collect(java.util.stream.Collectors.toSet());
        if (!costs.equals(java.util.Set.of("{7}{U}{U}", "{W}{U}{B}{R}{G}"))) throw new AssertionError("Alternative IDs collapsed candidates: " + costs);
        long affordable = abilities.stream().filter(a -> a.isSpell() && a.getHostCard() == host)
                .filter(a -> RulesCostFeasibility.requirePayable(f.player(), a)).count();
        if (affordable != 1) throw new AssertionError("Only five-color alternate should be affordable");
        BenchMenuStateAudit.assertUnchanged(before, f.game());
    }
    private static void steelSeraph() {
        var f = fixture();
        var host = card("Steel Seraph", f.player(), ZoneType.Hand);
        for (int i = 0; i < 6; i++) card("Plains", f.player(), ZoneType.Battlefield);
        f.game().getAction().checkStateEffects(true);
        long timestamp = f.game().getTimestamp();
        probe(f, "Steel Seraph prototype spell variants");
        assertTimestamp(timestamp, f, "repeated no-op priority menu");
        var before = BenchMenuStateAudit.capture(f.game());
        var abilities = BenchmarkAbilityEnumeration.spells(ComputerUtilAbility.getAvailableCards(f.game(), f.player()), f.player());
        var seraph = abilities.stream().filter(a -> a.isSpell() && a.getHostCard() == host).toList();
        if (seraph.size() < 2 || seraph.stream().map(a -> a.getPayCosts().getTotalMana().toString()).distinct().count() < 2) throw new AssertionError("Steel Seraph normal/prototype variants missing");
        var prototype = seraph.stream().filter(a -> a.hasParam("Prototype")).findFirst().orElseThrow();
        var prospective = prototype.getAlternateHostForEnumeration(host);
        if (prospective == null || prospective == host || !prospective.isLKI()
                || prospective.getCMC() != 3 || prospective.getBasePower() != 3
                || prospective.getBaseToughness() != 3 || prospective.getColor() != forge.card.ColorSet.W
                || host.getCMC() != 6 || host.getBasePower() != 5 || host.getBaseToughness() != 4)
            throw new AssertionError("Prototype prospective characteristics or original host drifted");
        var lki = forge.game.card.CardCopyService.getLKICopy(host);
        var fromLki = prototype.getAlternateHostForEnumeration(lki);
        if (fromLki == lki || fromLki.getCMC() != 3 || lki.getCMC() != 6
                || lki.getBasePower() != 5 || lki.getColor() != forge.card.ColorSet.C)
            throw new AssertionError("Prototype mutated an input LKI host");
        var unsupported = prototype.copyForEnumeration(f.player());
        unsupported.putParam("SetPower", "DynamicPower");
        try {
            unsupported.getAlternateHostForEnumeration(host);
            throw new AssertionError("Dynamic Prototype P/T must refuse read-only enumeration");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().startsWith("BENCH_INTEGRITY_UNSUPPORTED:")) throw expected;
        }
        BenchMenuStateAudit.assertUnchanged(before, f.game());
        assertTimestamp(timestamp, f, "variant expansion and prospective host");
    }
    private static void prototypeCastingRestriction(String measure, String operator, String operand, boolean prototypeAllowed) {
        var f = fixture();
        var host = card("Steel Seraph", f.player(), ZoneType.Hand);
        for (int i = 0; i < 6; i++) card("Plains", f.player(), ZoneType.Battlefield);
        int restricted = 0;
        for (var ability : host.getSpellAbilities()) {
            if (!ability.isSpell()) continue;
            ability.getRestrictions().setSvarToCheck(measure);
            ability.getRestrictions().setSvarOperator(operator);
            ability.getRestrictions().setSvarOperand(operand);
            restricted++;
        }
        if (restricted < 2) throw new AssertionError("Both printed Seraph cast choices need the same restriction");
        f.game().getAction().checkStateEffects(true);
        long timestamp = f.game().getTimestamp();
        probe(f, "Steel Seraph casting restriction " + measure + " " + operator + operand);
        assertTimestamp(timestamp, f, "restricted no-op priority menu " + measure + operator + operand);
        var before = BenchMenuStateAudit.capture(f.game());
        var offered = BenchmarkAbilityEnumeration.spells(ComputerUtilAbility.getAvailableCards(f.game(), f.player()), f.player())
                .stream().filter(a -> a.isSpell() && a.getHostCard() == host).toList();
        if (offered.size() != 1 || offered.get(0).hasParam("Prototype") != prototypeAllowed)
            throw new AssertionError("Wrong Seraph variant survived casting restriction " + measure + " " + operator + operand
                    + ": " + offered.stream().map(a -> a.getPayCosts().getTotalMana().toString()).toList());
        BenchMenuStateAudit.assertUnchanged(before, f.game());
        assertTimestamp(timestamp, f, "restricted variant expansion " + measure + operator + operand);
    }
    private static void inactiveOptional() {
        var f = fixture();
        var host = card("Thornscape Battlemage", f.player(), ZoneType.Graveyard);
        card("Firebolt", f.player(), ZoneType.Graveyard);
        for (int i = 0; i < 5; i++) card("Mountain", f.player(), ZoneType.Battlefield);
        f.game().getAction().checkStateEffects(true);
        host.getFirstSpellAbility().getPipsToReduce().add("G");
        probe(f, "unplayable kicker fallback and legal graveyard flashback");
        var before = BenchMenuStateAudit.capture(f.game());
        var abilities = BenchmarkAbilityEnumeration.spells(ComputerUtilAbility.getAvailableCards(f.game(), f.player()), f.player());
        if (abilities.stream().noneMatch(a -> a.isSpell() && a.getHostCard().getName().equals("Firebolt") && a.canPlay()
                && a.getPayCosts().getTotalMana().toString().equals("{4}{R}"))) throw new AssertionError("Lost legal flashback variant");
        BenchMenuStateAudit.assertUnchanged(before, f.game());
    }
    private static void exile(String sourceName, boolean expectedLand) {
        var f = fixture();
        var source = card(sourceName, f.player(), ZoneType.Battlefield);
        if (sourceName.equals("Decadent Dragon")) source.setState(CardStateName.Secondary, false);
        var land = card("Mountain", f.game().getPlayers().get(1), ZoneType.Exile);
        land.turnFaceDown(true);
        source.addRemembered(land);
        var effect = forge.game.ability.AbilityFactory.getAbility(source.getSVar("DBEffect"), source);
        effect.setActivatingPlayer(f.player());
        forge.game.ability.AbilityUtils.resolve(effect);
        f.game().getAction().checkStateEffects(true);
        var direct = new forge.game.spellability.LandAbility(land, land.getState(CardStateName.Original));
        long timestamp = f.game().getTimestamp();
        int hiddenId = land.getHiddenId();
        probe(f, sourceName + " face-down exile permission");
        var before = BenchMenuStateAudit.capture(f.game());
        boolean actual = land.getAllPossibleAbilities(f.player(), true, null, true).stream()
                .anyMatch(a -> a.isLandAbility() && a.canPlayForEnumeration());
        if (actual != expectedLand) throw new AssertionError("Read-only permission drift " + sourceName);
        var faceUp = direct.getAlternateHostForEnumeration(land);
        if (faceUp == null || faceUp == land || !faceUp.isLKI() || faceUp.isFaceDown()
                || !faceUp.getName().equals("Mountain") || !land.isFaceDown()
                || land.getHiddenId() != hiddenId || f.game().getTimestamp() != timestamp)
            throw new AssertionError("Face-up projection changed hidden identity or timestamp " + sourceName);
        BenchMenuStateAudit.assertUnchanged(before, f.game());
        if (expectedLand) {
            f.player().setLandsPlayedThisTurn(1);
            long deniedTimestamp = f.game().getTimestamp();
            var deniedBefore = BenchMenuStateAudit.capture(f.game());
            var denied = land.getAllPossibleAbilities(f.player(), false, null, true);
            if (denied.stream().anyMatch(a -> a.isLandAbility()))
                throw new AssertionError("Spent land drop still offered a face-down land");
            BenchMenuStateAudit.assertUnchanged(deniedBefore, f.game());
            if (f.game().getTimestamp() != deniedTimestamp) throw new AssertionError("Denied read-only land advanced timestamp");
            probe(f, sourceName + " spent land-drop menu");
        }
    }
    private static void faceUpProjectionScope() {
        var f = fixture();
        var hidden = card("Savannah Lions", f.player(), ZoneType.Exile);
        hidden.turnFaceDown(true);
        var spell = hidden.getState(CardStateName.Original).getSpellAbilities().stream().findFirst().orElseThrow();
        var ordinary = card("Savannah Lions", f.player(), ZoneType.Hand);
        var ordinarySpell = ordinary.getFirstSpellAbility();
        long timestamp = f.game().getTimestamp();
        int hiddenId = hidden.getHiddenId();
        var before = BenchMenuStateAudit.capture(f.game());
        var prospective = spell.getAlternateHostForEnumeration(hidden);
        if (prospective == null || prospective == hidden || !prospective.isLKI()
                || prospective.isFaceDown() || !prospective.getName().equals("Savannah Lions")
                || ordinarySpell.getAlternateHostForEnumeration(ordinary) != null
                || !hidden.isFaceDown() || hidden.getHiddenId() != hiddenId
                || f.game().getTimestamp() != timestamp)
            throw new AssertionError("Read-only face-up projection escaped exile scope or changed hidden identity");
        BenchMenuStateAudit.assertUnchanged(before, f.game());
        var execution = spell.getAlternateHost(hidden);
        if (execution == null || execution.isFaceDown() || f.game().getTimestamp() != timestamp + 1
                || !hidden.isFaceDown() || hidden.getHiddenId() != hiddenId)
            throw new AssertionError("Execution face-up semantics changed");
        var guarded = card("Savannah Lions", f.player(), ZoneType.Exile);
        guarded.turnFaceDown(true);
        guarded.addFaceupCommand(() -> { throw new AssertionError("Read-only query ran face-up command"); });
        var guardedSpell = guarded.getState(CardStateName.Original).getSpellAbilities().stream().findFirst().orElseThrow();
        long guardedTimestamp = f.game().getTimestamp();
        try {
            guardedSpell.getAlternateHostForEnumeration(guarded);
            throw new AssertionError("Read-only projection accepted pending face-up command");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().startsWith("BENCH_INTEGRITY_UNSUPPORTED:")) throw expected;
        }
        if (!guarded.isFaceDown() || f.game().getTimestamp() != guardedTimestamp)
            throw new AssertionError("Rejected face-up projection changed original state");
        var merged = card("Savannah Lions", f.player(), ZoneType.Exile);
        merged.turnFaceDown(true);
        merged.setMergedCards(List.of(merged));
        var mergedSpell = merged.getState(CardStateName.Original).getSpellAbilities().stream().findFirst().orElseThrow();
        long mergedTimestamp = f.game().getTimestamp();
        try {
            mergedSpell.getAlternateHostForEnumeration(merged);
            throw new AssertionError("Read-only projection accepted merged card");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().startsWith("BENCH_INTEGRITY_UNSUPPORTED:")) throw expected;
        }
        if (!merged.isFaceDown() || f.game().getTimestamp() != mergedTimestamp)
            throw new AssertionError("Rejected merged projection changed original state");
        var mergedLand = card("Mountain", f.player(), ZoneType.Exile);
        mergedLand.turnFaceDown(true);
        mergedLand.setMergedCards(List.of(mergedLand));
        var landProjection = new forge.game.spellability.LandAbility(mergedLand, mergedLand.getState(CardStateName.Original));
        long mergedLandTimestamp = f.game().getTimestamp();
        try {
            landProjection.getAlternateHostForEnumeration(mergedLand);
            throw new AssertionError("Read-only land projection accepted merged card");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().startsWith("BENCH_INTEGRITY_UNSUPPORTED:")) throw expected;
        }
        if (!mergedLand.isFaceDown() || f.game().getTimestamp() != mergedLandTimestamp)
            throw new AssertionError("Rejected merged land projection changed original state");
        System.out.println("PASS detached face-up spell scope and unchanged execution timestamp");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "menu-purity-fixture";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            optional(); modal(); alternateCosts(); steelSeraph();
            prototypeCastingRestriction("Count$CardManaCost", "LE", "3", true);
            prototypeCastingRestriction("Count$CardManaCost", "GE", "6", false);
            prototypeCastingRestriction("Count$CardNumColors", "GE", "1", true);
            prototypeCastingRestriction("Count$CardNumColors", "EQ", "0", false);
            inactiveOptional(); exile("Thief of Sanity", false); exile("Decadent Dragon", true); faceUpProjectionScope();
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
