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
        probe(f, sourceName + " face-down exile permission");
        var before = BenchMenuStateAudit.capture(f.game());
        boolean actual = land.getAllPossibleAbilities(f.player(), true, null, true).stream().anyMatch(a -> a.isLandAbility() && a.canPlay());
        if (actual != expectedLand) throw new AssertionError("Read-only permission drift " + sourceName);
        BenchMenuStateAudit.assertUnchanged(before, f.game());
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
            optional(); modal(); exile("Thief of Sanity", false); exile("Decadent Dragon", true);
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
