package forge.bench;

import com.google.gson.JsonObject;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.List;
import java.util.Objects;

/** Actual card-script SpellAbility chains, before any game-playing benchmark. */
public final class PriorityActivationChainSmoke {
    private static int checks;
    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
        checks++;
    }
    private static Card card(String name, forge.game.player.Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var paper = Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name));
        var card = Card.fromPaperCard(paper, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        card.setSickness(false);
        return card;
    }
    private static void witness(String name, forge.game.player.Player player) {
        var card = card(name, player, ZoneType.Battlefield);
        for (var sa : card.getSpellAbilities()) {
            if (!sa.isActivatedAbility() || sa.isManaAbility()) continue;
            sa.setActivatingPlayer(player);
            var option = StateEncoder.encodePriorityAbilityWithTargetDomains(sa, player.getView());
            var identity = option.getAsJsonObject("activationIdentity");
            check(identity != null, "identity present for " + name);
            check("priority-activation-identity-v5".equals(identity.get("version").getAsString()), "v5 for " + name);
            check("intrinsic-fixed".equals(identity.get("kind").getAsString()), "fixed for " + name);
            check(identity.get("text").getAsString().equals(option.get("description").getAsString()), "same full menu description");
            check(!identity.get("text").getAsString().equals(sa.getOriginalDescription()), "subability chain extends root");
            if (name.equals("Mishra's Bauble")) {
                check(identity.get("text").getAsString().contains("Draw a card at the beginning of the next turn's upkeep."), "Bauble delayed draw tail");
            }
            var row = new JsonObject();
            row.addProperty("source", name);
            row.add("option", option);
            System.out.println("CHAIN_ACTIVATION_CASE " + row);
        }
    }
    /** A real source-paid, named literal counter cost. This is deliberately
     * separate from the v5 chain witnesses: it exercises the v6 structural
     * cost identity and emits the exact option consumed by the TS fixture. */
    private static void sourceCounterRefuses(forge.game.spellability.SpellAbility sa,
            List<forge.game.cost.CostPart> replacement, String label) {
        var parts = sa.getPayCosts().getCostParts();
        var original = new java.util.ArrayList<>(parts);
        try {
            parts.clear();
            parts.addAll(replacement);
            var identity = PriorityActivationIdentity.encode(sa);
            check("unsupported".equals(identity.get("kind").getAsString()), label);
        } finally {
            parts.clear();
            parts.addAll(original);
        }
    }
    private static void sourceCounterWitness(forge.game.player.Player player, forge.game.player.Player opponent) {
        var wave = card("Parallax Wave", player, ZoneType.Battlefield);
        var fade = forge.game.card.CounterType.getType("FADE");
        wave.addCounterInternal(fade, 1, player, false, new forge.game.GameEntityCounterTable(), forge.game.ability.AbilityKey.newMap());
        card("Grizzly Bears", opponent, ZoneType.Battlefield);
        player.getGame().getAction().checkStateEffects(true);
        for (var sa : wave.getSpellAbilities()) {
            if (!sa.isActivatedAbility() || sa.isManaAbility()) continue;
            sa.setActivatingPlayer(player);
            var before = BenchMenuStateAudit.capture(player.getGame());
            var option = StateEncoder.encodePriorityAbilityWithTargetDomains(sa, player.getView());
            BenchMenuStateAudit.assertUnchanged(before, player.getGame());
            var identity = option.getAsJsonObject("activationIdentity");
            check(identity != null, "source-counter identity present");
            check("priority-activation-identity-v6".equals(identity.get("version").getAsString()), "Parallax uses v6");
            check("intrinsic-source-counter".equals(identity.get("kind").getAsString()), "Parallax is source-counter");
            check("fade".equals(identity.get("counter").getAsString()), "Parallax counter kind is fade");
            check(identity.get("amount").getAsInt() == 1, "Parallax counter amount is one");
            check(identity.getAsJsonArray("modes").size() == 0, "Parallax has no modes");
            check(identity.get("text").getAsString().equals(option.get("description").getAsString()), "Parallax identity equals menu chain");
            check(true, "Parallax priority encoding is menu-pure");
            // A state-derived SVar may currently resolve to one, but it is not
            // a literal cost token and must never be emitted as v6.
            wave.setSVar("CounterCostAmount", "Count$CardCounters.FADE");
            var dynamic = new forge.game.cost.CostRemoveCounter("SVar$CounterCostAmount", fade,
                    "CARDNAME", null, List.of(ZoneType.Battlefield), false);
            check(dynamic.convertAmount() == null, "state-derived source counter is not literal");
            check(dynamic.getAbilityAmount(sa) == 1, "state-derived source counter resolves in fixture state");
            sourceCounterRefuses(sa, List.of(dynamic), "state-derived source counter cost refuses v6");
            sourceCounterRefuses(sa, List.of(new forge.game.cost.CostRemoveCounter("1", fade,
                    "CARDNAME", null, List.of(ZoneType.Battlefield), false), new forge.game.cost.CostTap()),
                    "mixed source-counter and tap cost refuses v6");
            sourceCounterRefuses(sa, List.of(new forge.game.cost.CostRemoveCounter("1", fade,
                    "Creature.YouCtrl", null, List.of(ZoneType.Battlefield), false)),
                    "non-source counter cost refuses v6");
            sourceCounterRefuses(sa, List.of(new forge.game.cost.CostRemoveCounter("1", fade,
                    "CARDNAME", null, List.of(ZoneType.Battlefield), true)),
                    "one-or-more source counter cost refuses v6");
            var row = new JsonObject(); row.addProperty("source", "Parallax Wave"); row.add("option", option);
            System.out.println("COUNTER_ACTIVATION_CASE " + row);
        }
    }
    private static void specialized(String name, ZoneType zone, String kind, String version,
            forge.game.player.Player player) {
        var source = card(name, player, zone);
        boolean found = false;
        for (var sa : source.getSpellAbilities()) {
            if (!sa.isActivatedAbility()) continue;
            var identity = PriorityActivationIdentity.encode(sa);
            if (!kind.equals(identity.get("kind").getAsString())) continue;
            check(version.equals(identity.get("version").getAsString()), name + " kept " + version);
            found = true;
        }
        check(found, name + " specialized identity present");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (p,m,v) -> switch (m.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "activation-chain";
                        default -> throw new AssertionError(m.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            var registered = List.of(
                    new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Actor", 0, 0, null, "Default")),
                    new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Other", 1, 0, null, "Default")));
            var game = new Match(new GameRules(GameType.Constructed), registered, "Activation chain fixture").createGame();
            var actor = game.getPlayers().get(0);
            game.setAge(GameStage.Play);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, actor);
            witness("Mishra's Bauble", actor);
            witness("Currency Converter", actor);
            sourceCounterWitness(actor, game.getPlayers().get(1));
            specialized("Generous Ent", ZoneType.Hand, "intrinsic-hand-discard", "priority-activation-identity-v2", actor);
            specialized("Pack Rat", ZoneType.Battlefield, "intrinsic-discard", "priority-activation-identity-v3", actor);
            specialized("Student of Warfare", ZoneType.Battlefield, "intrinsic-level-up", "priority-activation-identity-v4", actor);
            System.out.println("PASS " + checks + " activation chain checks; NOT CERTIFIED");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace(); System.exit(1);
        }
    }
}
