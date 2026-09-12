package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.card.CardStateName;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.*;

/** Actual Expensive Taste resolution and seat visibility. No games or policy claims. */
public final class ExileKnownObservationEngineSmoke {
    private static int checks;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    private static Card card(String name, Player p, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var c = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)), p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        if (zone == ZoneType.Stack) p.getGame().getStackZone().add(c); else p.getZone(zone).add(c);
        c.setSickness(false); return c;
    }
    private static Game game(int actor) {
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0", 0, 0, null, "Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1", 1, 0, null, "Default")));
        var g = new Match(new GameRules(GameType.Constructed), players, "Exile observation fixture").createGame();
        g.setAge(GameStage.Play); g.getPhaseHandler().devModeSet(PhaseType.MAIN1, g.getPlayers().get(actor)); return g;
    }
    private static JsonArray exile(Game game, Player viewer, int owner) {
        return StateEncoder.encode(game, viewer).getAsJsonArray("players").get(owner).getAsJsonObject().getAsJsonArray("exile");
    }
    private static String semantic(SpellAbility a) {
        var permission = a.getMayPlayOption();
        return a.getHostCard().getId() + "|" + a.getApi() + "|" + a.getCardStateName() + "|" + a.getPayCosts().toSimpleString()
                + "|" + (permission == null ? "none" : permission.getHost().getId() + ":" + permission.getAbility().getId());
    }
    private static void actualEffect(int actorIndex) {
        var game = game(actorIndex); var actor = game.getPlayers().get(actorIndex); var owner = game.getPlayers().get(1 - actorIndex);
        var dragon = card("Decadent Dragon", actor, ZoneType.Hand); dragon.setState(CardStateName.Secondary, false);
        var first = card("Savannah Lions", owner, ZoneType.Library); var second = card("Forest", owner, ZoneType.Library);
        card("Mind Twist", owner, ZoneType.Library); card("Plains", actor, ZoneType.Battlefield);
        for (int i = 0; i < 3; i++) card("Swamp", actor, ZoneType.Battlefield);
        var unknown = card("Grief", owner, ZoneType.Exile); unknown.turnFaceDown(true);
        game.getAction().checkStateEffects(true);
        var actual = dragon.getSpellAbilities().stream().filter(a -> a.getApi() == ApiType.Dig).findFirst().orElseThrow();
        actual.setActivatingPlayer(actor); actual.getTargets().add(owner);
        check(actual.hasParam("WithMayLook") && actual.hasParam("ExileFaceDown") && actual.getSubAbility() != null,
                "actual printed Expensive Taste includes Dig look grant and permission chain actor=" + actorIndex);
        check(exile(game, actor, 1 - actorIndex).isEmpty() && exile(game, owner, 1 - actorIndex).isEmpty(), "pre-resolution unknown exile hidden to both seats");
        // Fixture Default AI handles only setup payment/reveal/order. This is
        // not a bridge-policy or strength test. Resolve the actual spell through
        // the engine so Adventure's post-resolution zone change is real too.
        check(forge.ai.ComputerUtil.handlePlayingSpellAbility(actor, actual, null), "engine casts actual Expensive Taste");
        check(game.getStack().size() == 1, "actual Adventure spell is on real stack");
        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);
        check(game.getStack().isEmpty() && actor.getCardsIn(ZoneType.Exile).stream().anyMatch(c -> c.getId() == dragon.getId() && !c.isFaceDown()),
                "real stack resolution exiles Adventure source face up and clears stack");
        var exiled = owner.getCardsIn(ZoneType.Exile).stream().filter(c -> c.getId() == first.getId() || c.getId() == second.getId()).toList();
        check(exiled.size() == 2 && owner.getCardsIn(ZoneType.Library).size() == 1, "actual effect moves exactly two top cards to exile");
        check(exiled.stream().allMatch(Card::isFaceDown), "actual effect leaves exiled cards face down");
        for (var c : exiled) {
            check(c.mayPlayerLook(actor) && c.getView().canBeShownTo(actor.getView()) && c.getView().canFaceDownBeShownTo(actor.getView()), "engine grants actor object and original-face visibility " + c.getId());
            check(!c.mayPlayerLook(owner) && !c.getView().canBeShownTo(owner.getView()) && !c.getView().canFaceDownBeShownTo(owner.getView()), "ownership does not grant opponent original-face visibility " + c.getId());
            var encoded = StateEncoder.encodeCard(c, actor.getView());
            check(encoded != null && encoded.get("faceDown").getAsBoolean() && encoded.get("name").getAsString().isEmpty(), "encoder preserves effective face-down characteristics " + c.getId());
            check(encoded.getAsJsonObject("knownFace").get("name").getAsString().equals(c.getState(CardStateName.Original).getName()), "actor knownFace names actual exiled original " + c.getId());
            check(StateEncoder.encodeCard(c, owner.getView()) == null, "direct opponent card encoding remains hidden " + c.getId());
        }
        // Independent ordinary engine enumeration precedes the read-only purity
        // snapshot: stock enumeration itself may write actors/allocate IDs.
        var nativeOptions = new ArrayList<SpellAbility>();
        for (var c : exiled) nativeOptions.addAll(c.getAllPossibleAbilities(actor, false).stream().filter(SpellAbility::isSpell).toList());
        var before = BenchMenuStateAudit.capture(game); var rng = BenchRandomAudit.begin();
        var actorState = StateEncoder.encode(game, actor); var opponentState = StateEncoder.encode(game, owner);
        check(exile(game, actor, 1 - actorIndex).size() == 2 && exile(game, owner, 1 - actorIndex).isEmpty(), "full state includes two known exiled objects only for authorized actor");
        for (var c : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) for (var st : c.getStaticAbilities())
            if (st.checkConditions(forge.game.staticability.StaticAbilityMode.Continuous))
                System.out.println("ACTIVE_STATIC " + c.getId() + "/" + c.getName() + " " + st.getMapParams());
        var options = BenchmarkAbilityEnumeration.spells(exiled, actor).stream().filter(SpellAbility::isSpell).toList();
        check(nativeOptions.stream().map(ExileKnownObservationEngineSmoke::semantic).sorted().toList()
                .equals(options.stream().map(ExileKnownObservationEngineSmoke::semantic).sorted().toList()),
                "read-only permission variants equal independent native one-pass variants");
        var lions = options.stream().filter(a -> a.getHostCard().getId() == first.getId()).findFirst().orElseThrow();
        check(lions.canPlay(), "real permission makes actor's Savannah Lions cast legal");
        check(StateEncoder.encodeSpellAbility(lions, actor.getView()).get("source").getAsString().equals("Savannah Lions"), "actor menu source identity agrees with known state");
        check(StateEncoder.encodeSpellAbility(lions, owner.getView()).get("source").getAsString().isEmpty(), "source identity is not granted to unauthorized menu recipient");
        BenchMenuStateAudit.assertUnchanged(before, game); BenchRandomAudit.assertUnchanged(rng, "exile observation and enumeration");
        check(StateEncoder.encodeCard(unknown, actor.getView()) == null && StateEncoder.encodeCard(unknown, owner.getView()) == null, "unrelated unknown exile remains hidden after actual effect");
        owner.getZone(ZoneType.Exile).remove(unknown);
        var replacement = card("Grave Titan", owner, ZoneType.Exile); replacement.turnFaceDown(true);
        game.getAction().checkStateEffects(true);
        check(actorState.equals(StateEncoder.encode(game, actor)) && opponentState.equals(StateEncoder.encode(game, owner)), "substituting unrelated hidden exile identity cannot change either observation");
        System.out.println("ACTUAL_EFFECT_ACTOR_STATE " + actorState);
        System.out.println("ACTUAL_EFFECT_OPPONENT_STATE " + opponentState);
        effectZoneControls(game, actor, exiled);
    }
    private static void effectZoneControls(Game game, Player actor, List<Card> exiled) {
        var effect = actor.getCardsIn(ZoneType.Command).stream().filter(c -> c.getStaticAbilities().stream()
                .anyMatch(st -> "Command".equals(st.getParam("EffectZone")))).findFirst().orElseThrow();
        var st = effect.getStaticAbilities().stream().filter(s -> "Command".equals(s.getParam("EffectZone"))).findFirst().orElseThrow();
        check(st.checkConditions(forge.game.staticability.StaticAbilityMode.Continuous), "actual Adventure EffectZone active in Command");
        // Keep the source in a static-ability source zone, but outside its own
        // parsed EffectZone, and recompute only static layers (no fixture SBA).
        actor.getZone(ZoneType.Command).remove(effect); actor.getZone(ZoneType.Exile).add(effect);
        game.getAction().checkStaticAbilities();
        check(!st.checkConditions(forge.game.staticability.StaticAbilityMode.Continuous), "EffectZone source restriction disables effect in Exile");
        var stillLegal = BenchmarkAbilityEnumeration.spells(exiled, actor);
        check(stillLegal.stream().anyMatch(a -> a.isSpell() && a.canPlay()), "inactive unrelated Adventure effect does not suppress Lions permission");
        st.getMapParams().put("AddType", "Artifact");
        check(!BenchmarkAbilityEnumeration.spells(exiled, actor).isEmpty(), "inactive characteristic-changing static remains inactive at read-only gate");
        actor.getZone(ZoneType.Exile).remove(effect); actor.getZone(ZoneType.Command).add(effect);
        check(st.checkConditions(forge.game.staticability.StaticAbilityMode.Continuous), "moving source back restores EffectZone activity");
        boolean rejected = false;
        try { BenchmarkAbilityEnumeration.spells(exiled, actor); }
        catch (IllegalStateException expected) { rejected = expected.getMessage().contains("prospective face requires characteristic-layer simulation"); }
        check(rejected, "active AddType characteristic mutation still fails closed");
        st.getMapParams().remove("AddType");
        game.getAction().checkStaticAbilities();
        check(!BenchmarkAbilityEnumeration.spells(exiled, actor).isEmpty(), "ordinary permission works again after explicit fixture fault removal");
    }
    private static void omittedDigControl() {
        var game = game(0); var actor = game.getPlayers().get(0); var owner = game.getPlayers().get(1);
        var source = card("Decadent Dragon", actor, ZoneType.Battlefield); source.setState(CardStateName.Secondary, false);
        var stolen = card("Savannah Lions", owner, ZoneType.Exile); stolen.turnFaceDown(true); source.addRemembered(stolen);
        var effect = AbilityFactory.getAbility(source.getSVar("DBEffect"), source); effect.setActivatingPlayer(actor);
        AbilityUtils.resolve(effect); game.getAction().checkStateEffects(true);
        var option = BenchmarkAbilityEnumeration.spells(List.of(stolen), actor).stream().filter(SpellAbility::isSpell).findFirst().orElseThrow();
        check(option.canPlay(), "permission-only synthetic control offers cast");
        check(!stolen.mayPlayerLook(actor), "skipping actual Dig also skips WithMayLook grant");
        check(StateEncoder.encodeCard(stolen, actor.getView()) == null && StateEncoder.encodeSpellAbility(option, actor.getView()).get("source").getAsString().isEmpty(),
                "encoder correctly withholds identity for malformed permission-only fixture");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "exile-known-observation";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            BenchRandomAudit.install(91803); omittedDigControl(); actualEffect(0); actualEffect(1);
            System.out.println("PASS " + checks + " actual-effect observation checks; no games or strength claims"); System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
