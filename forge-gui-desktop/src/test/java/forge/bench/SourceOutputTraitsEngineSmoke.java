package forge.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.*;

/** Immutable production traits, actual static snow/sacrifice LKI, and fault
 * rejection. Development fixtures only; no benchmark matches. */
public final class SourceOutputTraitsEngineSmoke {
    private static int checks;
    private record Fixture(Game game, Player payer, Card owl, Card source, SpellAbility spell,
                           RulesPaymentDomain domain, RulesCostFeasibility.PaymentWitness witness) {}
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    private static Card card(String name, Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var card = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)), player);
        card.setGameTimestamp(player.getGame().getNextTimestamp()); player.getZone(zone).add(card); card.setSickness(false); return card;
    }
    private static Fixture fixture(String sourceName) {
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0", 0, 0, null, "Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), players, "Output trait fixture").createGame();
        var payer = game.getPlayers().get(0); game.setAge(GameStage.Play); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, payer);
        var owl = card("Rimefeather Owl", payer, ZoneType.Battlefield);
        var source = card(sourceName, payer, ZoneType.Battlefield); source.setCounters(CounterEnumType.ICE, 1);
        var spell = card("Lightning Bolt", payer, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(payer); spell.getTargets().add(game.getPlayers().get(1));
        game.getAction().checkStateEffects(true);
        check(source.isSnow(), "actual Rimefeather Owl makes " + sourceName + " a snow permanent");
        var before = BenchMenuStateAudit.capture(game); var rng = BenchRandomAudit.begin();
        var domain = new RulesPaymentDomain(payer, spell); var request = domain.request();
        var options = request.getAsJsonArray("sourceOptions"); String sourceId = null;
        for (var raw : options) {
            var option = raw.getAsJsonObject();
            if (option.get("fid").getAsInt() == source.getId()
                    && option.getAsJsonArray("output").get(0).getAsString().equals("R")) {
                sourceId = option.get("id").getAsString();
                check(option.get("snow").getAsBoolean(), "domain exposes captured snow production trait");
                break;
            }
        }
        if (sourceId == null) throw new AssertionError("Expected red source absent");
        var answer = new JsonObject(); answer.addProperty("paymentVersion", RulesPaymentDomain.PAYMENT_VERSION);
        answer.addProperty("x", 0);
        var order = new JsonArray(); order.add(sourceId); answer.add("sourceOrder", order);
        var spend = new JsonArray(); var allocation = new JsonObject(); allocation.addProperty("token", sourceId + ":0"); allocation.addProperty("shardIndex", 0); spend.add(allocation);
        answer.add("spend", spend); answer.addProperty("lifePaid", 0);
        var witness = domain.select(answer);
        BenchRandomAudit.assertUnchanged(rng, "source trait capture"); BenchMenuStateAudit.assertUnchanged(before, game);
        check(witness.sources().get(0).traits().snow(), "witness retains immutable snow expectation");
        return new Fixture(game, payer, owl, source, spell, domain, witness);
    }
    private static void execute(Fixture fixture, RulesCostFeasibility.PaymentWitness witness) {
        final RulesPaymentExecutor[] payment = {null};
        fixture.payer().dangerouslySetController(new forge.ai.PlayerControllerAi(fixture.game(), fixture.payer(), fixture.payer().getLobbyPlayer()) {
            @Override public boolean payManaCost(forge.card.mana.ManaCost cost, forge.game.cost.CostPartMana part, SpellAbility ability,
                    String prompt, forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
                if (payment[0] == null || matrix != null) throw new AssertionError("Unexpected callback");
                return payment[0].pay(cost, part, ability, effect);
            }
        });
        payment[0] = new RulesPaymentExecutor(fixture.payer(), fixture.spell(), witness);
        if (!forge.ai.ComputerUtil.handlePlayingSpellAbility(fixture.payer(), fixture.spell(), null, payment[0]::decisions))
            throw new AssertionError("Actual source trait cast failed");
        payment[0].assertPaid();
    }
    private static void actual(String sourceName) {
        var f = fixture(sourceName); execute(f, f.witness());
        check(f.spell().getHostCard().isInZone(ZoneType.Stack), "real " + sourceName + " payment enters stack");
        var emitted = List.copyOf(f.witness().sources().get(0).ability().getManaPart().getLastManaProduced());
        check(emitted.stream().allMatch(m -> f.witness().sources().get(0).traits().matches(m)), "actual output matches immutable trait snapshot");
        if (sourceName.equals("Black Lotus")) {
            check(f.source().isInZone(ZoneType.Graveyard), "actual Lotus sacrifice happened before mana production");
            check(emitted.size() == 3 && emitted.stream().allMatch(m -> m.isSnow() && m.getSourceCard().isSnow()),
                    "sacrificed static-snow Lotus produces snow mana from real source LKI");
            f.game().getAction().checkStateEffects(true);
            check(!f.game().getCardState(f.source(), null).isSnow(), "actual graveyard Lotus no longer receives permanent-only snow effect");
        }
    }
    private static void staleStatic() {
        var f = fixture("Mountain");
        f.game().getAction().moveToGraveyard(f.owl(), null); f.game().getAction().checkStateEffects(true);
        check(!f.source().isSnow(), "removing real Owl changes current source snow status");
        check(f.witness().sources().get(0).traits().snow()
                        && f.domain().request().getAsJsonArray("sourceOptions").get(0).getAsJsonObject().get("snow").getAsBoolean(),
                "stored witness and request do not re-read changed live source as expected traits");
        boolean rejected = false;
        try { execute(f, f.witness()); }
        catch (RulesCostFeasibility.Unsupported expected) { rejected = expected.getMessage().contains("persistence/combat/snow traits changed"); }
        check(rejected && f.spell().getPayingMana().isEmpty(), "actual changed production traits invalidate before consuming payment mana");
    }
    private static void mutation(String trait) {
        var f = fixture("Mountain"); var original = f.witness().sources().get(0); var t = original.traits();
        var mutated = new RulesCostFeasibility.OutputTraits(trait.equals("persistent") || t.persistent(), trait.equals("combat") || t.combat(), !trait.equals("snow") && t.snow());
        var changed = new RulesCostFeasibility.SourceChoice(original.ability(), original.choice(), original.output(), original.life(), mutated);
        var allocations = f.witness().allocations().stream().map(a -> new RulesCostFeasibility.Allocation(a.shard(),
                new RulesCostFeasibility.Token(a.token().color(), null, changed, a.token().outputIndex()))).toList();
        var witness = new RulesCostFeasibility.PaymentWitness(f.witness().cost(), List.of(changed), allocations, f.witness().life());
        boolean rejected = false;
        try { execute(f, witness); }
        catch (RulesCostFeasibility.Unsupported expected) { rejected = expected.getMessage().contains("persistence/combat/snow traits changed"); }
        check(rejected && f.spell().getPayingMana().isEmpty(), "forged " + trait + " expectation invalidates before consuming payment mana");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "source-output-traits-fixture";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            BenchRandomAudit.install(91802);
            actual("Mountain"); actual("Black Lotus"); staleStatic();
            mutation("persistent"); mutation("combat"); mutation("snow");
            System.out.println("PASS " + checks + " source-output trait development checks; no full-game claim"); System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
