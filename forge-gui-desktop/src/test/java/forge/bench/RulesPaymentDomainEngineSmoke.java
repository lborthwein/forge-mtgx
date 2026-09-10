package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.card.mana.ManaAtom;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.mana.Mana;
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

/** Actual engine payment-domain development fixtures; no benchmark matches. */
public final class RulesPaymentDomainEngineSmoke {
    private static int checks;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    private static Game game() {
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0", 0, 0, null, "Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), players, "Compact payment fixture").createGame();
        game.setAge(GameStage.Play); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        return game;
    }
    private static Card card(String name, Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var paper = Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name));
        var card = Card.fromPaperCard(paper, player); card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card); card.setSickness(false); return card;
    }
    private static SpellAbility spell(String name, Player player) {
        var ability = card(name, player, ZoneType.Hand).getFirstSpellAbility(); ability.setActivatingPlayer(player);
        if (ability.usesTargeting()) ability.getTargets().add(player.getGame().getPlayers().get(1));
        player.getGame().getAction().checkStateEffects(true); return ability;
    }
    private static JsonObject answer(List<String> order, List<String> tokens, int life) {
        var answer = new JsonObject(); answer.addProperty("paymentVersion", RulesPaymentDomain.PAYMENT_VERSION);
        var sourceOrder = new JsonArray(); for (String id : order) sourceOrder.add(id); answer.add("sourceOrder", sourceOrder);
        var spend = new JsonArray();
        for (int i = 0; i < tokens.size(); i++) {
            var allocation = new JsonObject(); allocation.addProperty("token", tokens.get(i)); allocation.addProperty("shardIndex", i); spend.add(allocation);
        }
        answer.add("spend", spend); answer.addProperty("lifePaid", life); return answer;
    }
    private static List<String> ids(JsonObject request) {
        var ids = new ArrayList<String>(); for (var item : request.getAsJsonArray("sourceOptions")) ids.add(item.getAsJsonObject().get("id").getAsString()); return ids;
    }
    private static void rejected(RulesPaymentDomain domain, JsonObject answer, String label) {
        boolean rejected = false;
        try { domain.select(answer); } catch (RulesCostFeasibility.Unsupported expected) { rejected = true; }
        check(rejected, label);
    }
    private static JsonObject fromLegacy(JsonObject domainRequest, JsonObject legacy) {
        var answer = new JsonObject(); answer.addProperty("paymentVersion", RulesPaymentDomain.PAYMENT_VERSION);
        answer.add("sourceOrder", legacy.getAsJsonArray("sources").deepCopy());
        answer.add("lifePaid", legacy.get("lifePaid"));
        var shards = domainRequest.getAsJsonObject("cost").getAsJsonArray("shards");
        var used = new boolean[shards.size()]; var spend = new JsonArray();
        for (var raw : legacy.getAsJsonArray("spend")) {
            var allocation = raw.getAsJsonObject(); int index = -1;
            for (int i = 0; i < shards.size(); i++) if (!used[i] && shards.get(i).equals(allocation.get("shard"))) { index = i; break; }
            if (index < 0) throw new AssertionError("Legacy allocation does not cover same bill");
            used[index] = true; var out = new JsonObject(); out.add("token", allocation.get("token")); out.addProperty("shardIndex", index); spend.add(out);
        }
        answer.add("spend", spend); return answer;
    }
    private static void legacyEquivalence(String spellName, String... sources) {
        var game = game(); var player = game.getPlayers().get(0);
        for (String source : sources) card(source, player, ZoneType.Battlefield);
        var ability = spell(spellName, player);
        var before = BenchMenuStateAudit.capture(game); var rng = BenchRandomAudit.begin();
        var old = new RulesPaymentChoices(player, ability); var domain = new RulesPaymentDomain(player, ability);
        var oldRequest = old.request(); var request = domain.request();
        var oldMenu = oldRequest.getAsJsonArray("menu");
        for (int i = 0; i < oldMenu.size(); i++) {
            var legacy = oldMenu.get(i).getAsJsonObject();
            var oldAnswer = new JsonObject(); oldAnswer.addProperty("choice", i); oldAnswer.add("sourceOrder", legacy.getAsJsonArray("sources"));
            var expected = old.select(oldAnswer); var actual = domain.select(fromLegacy(request, legacy));
            if (!actual.equals(expected)) throw new AssertionError("Compact decoder changed legacy payment witness " + i);
        }
        BenchRandomAudit.assertUnchanged(rng, "compact payment differential"); BenchMenuStateAudit.assertUnchanged(before, game);
        check(oldMenu.size() > 0, "all " + oldMenu.size() + " eager witnesses preserved exactly for " + spellName + " " + List.of(sources));
    }
    private static void floatingProvenance() {
        var game = game(); var player = game.getPlayers().get(0);
        var source = card("Mountain", player, ZoneType.Battlefield); source.setTapped(true);
        var first = new Mana((byte) ManaAtom.RED, source, source.getManaAbilities().get(0).getManaPart(), player);
        var second = new Mana((byte) ManaAtom.RED, source, source.getManaAbilities().get(0).getManaPart(), player);
        player.getManaPool().addMana(first); player.getManaPool().addMana(second);
        var domain = new RulesPaymentDomain(player, spell("Lightning Bolt", player));
        var a = domain.select(answer(List.of(), List.of("pool0"), 0));
        var b = domain.select(answer(List.of(), List.of("pool1"), 0));
        check(a.allocations().get(0).token().floating() != b.allocations().get(0).token().floating(), "equal-color floating token provenance remains individually selectable");
        rejected(domain, answer(List.of(), List.of("pool2"), 0), "unadvertised floating token refused");
        var request = domain.request(); request.getAsJsonArray("pool").remove(0);
        check(domain.request().getAsJsonArray("pool").size() == 2, "host cannot mutate retained domain snapshot");
    }
    private static void validation() {
        var game = game(); var player = game.getPlayers().get(0);
        card("Mountain", player, ZoneType.Battlefield); card("Mountain", player, ZoneType.Battlefield);
        var domain = new RulesPaymentDomain(player, spell("Incinerate", player));
        var ids = ids(domain.request()); var valid = answer(ids, List.of(ids.get(0) + ":0", ids.get(1) + ":0"), 0);
        check(domain.select(valid).allocations().size() == 2, "all indexed cost shards must be covered");
        rejected(domain, answer(ids, List.of(ids.get(0) + ":0", ids.get(0) + ":0"), 0), "duplicate physical token refused");
        rejected(domain, answer(List.of(ids.get(0)), List.of(ids.get(0) + ":0", ids.get(1) + ":0"), 0), "output from unselected source refused");
        var duplicateShard = valid.deepCopy(); duplicateShard.getAsJsonArray("spend").get(1).getAsJsonObject().addProperty("shardIndex", 0);
        rejected(domain, duplicateShard, "duplicate shard coverage refused");
        var negative = valid.deepCopy(); negative.getAsJsonArray("spend").get(0).getAsJsonObject().addProperty("shardIndex", -1);
        rejected(domain, negative, "negative shard index refused");
        var stringIndex = valid.deepCopy(); stringIndex.getAsJsonArray("spend").get(0).getAsJsonObject().addProperty("shardIndex", "0");
        rejected(domain, stringIndex, "string-coerced shard index refused");
        var overflow = valid.deepCopy(); overflow.getAsJsonArray("spend").get(0).getAsJsonObject().addProperty("shardIndex", 2147483648L);
        rejected(domain, overflow, "overflowing shard index refused");
        var missing = valid.deepCopy(); missing.getAsJsonArray("spend").remove(0); rejected(domain, missing, "partial payment refused");
        var delegate = valid.deepCopy(); delegate.addProperty("delegate", true); rejected(domain, delegate, "delegation refused");
        var old = valid.deepCopy(); old.addProperty("choice", 0); rejected(domain, old, "legacy plan index cannot masquerade as symbolic payment");
        var version = valid.deepCopy(); version.addProperty("paymentVersion", "wrong"); rejected(domain, version, "wrong symbolic protocol version refused");
        var badLife = valid.deepCopy(); badLife.addProperty("lifePaid", 1); rejected(domain, badLife, "incorrect action/source life total refused");

        var game2 = game(); var payer2 = game2.getPlayers().get(0); payer2.setLife(1, null);
        card("Mana Confluence", payer2, ZoneType.Battlefield); card("Mana Confluence", payer2, ZoneType.Battlefield);
        var lifeDomain = new RulesPaymentDomain(payer2, spell("Lightning Bolt", payer2));
        var red = new ArrayList<String>(); var oneGroup = new ArrayList<String>();
        for (var raw : lifeDomain.request().getAsJsonArray("sourceOptions")) {
            var option = raw.getAsJsonObject();
            if (option.get("choice").getAsString().equals("R")) red.add(option.get("id").getAsString());
            if (option.get("group").getAsString().equals("g0")) oneGroup.add(option.get("id").getAsString());
        }
        check(lifeDomain.select(answer(List.of(red.get(0)), List.of(red.get(0) + ":0"), 1)).totalLife() == 1, "exact shared-life budget equality remains legal");
        rejected(lifeDomain, answer(red, List.of(red.get(0) + ":0"), 2), "extra activation cannot overspend shared life");
        rejected(lifeDomain, answer(oneGroup.subList(0, 2), List.of(oneGroup.get(0) + ":0"), 2), "mutually exclusive color choices from one source refused");
        String white = oneGroup.get(0);
        rejected(lifeDomain, answer(List.of(white), List.of(white + ":0"), 1), "wrong token color refused");
    }
    private static void zeroAndLifeOnly() {
        var game = game(); var player = game.getPlayers().get(0);
        card("Platinum Angel", player, ZoneType.Battlefield); player.setLife(-1, null);
        var memnite = spell("Memnite", player);
        check(new RulesPaymentDomain(player, memnite).select(answer(List.of(), List.of(), 0)).totalLife() == 0,
                "zero-cost payment remains legal at negative life with Platinum Angel");
        player.setLife(2, null); card("Bolas's Citadel", player, ZoneType.Battlefield);
        var sol = card("Sol Ring", player, ZoneType.Library);
        game.getAction().checkStateEffects(true);
        var alternative = GameActionUtil.getAlternativeCosts(sol.getFirstSpellAbility().copyForEnumeration(player), player, false, true).stream()
                .filter(a -> a.getMayPlayOption() != null && a.getPayCosts().getCostParts().stream().anyMatch(p -> p instanceof forge.game.cost.CostPayLife))
                .findFirst().orElseThrow();
        var domain = new RulesPaymentDomain(player, alternative);
        check(domain.select(answer(List.of(), List.of(), 1)).life() == 1, "life-only alternative retains action life separate from source life");
        rejected(domain, answer(List.of(), List.of(), 0), "life-only alternative cannot omit life payment");
    }
    private static void pooledTraits(int selected) {
        var game = game(); var player = game.getPlayers().get(0);
        var ordinary = card("Mountain", player, ZoneType.Battlefield); ordinary.setTapped(true);
        var snow = card("Snow-Covered Mountain", player, ZoneType.Battlefield); snow.setTapped(true);
        var radha = card("Grand Warlord Radha", player, ZoneType.Battlefield);
        var roku = card("Avatar Roku, Firebender", player, ZoneType.Battlefield);
        var persistentAbility = forge.game.ability.AbilityFactory.getAbility(radha.getSVar("TrigMana"), radha);
        var combatAbility = forge.game.ability.AbilityFactory.getAbility(roku.getSVar("TrigMana"), roku);
        var tokens = List.of(new Mana((byte) ManaAtom.RED, ordinary, ordinary.getManaAbilities().get(0).getManaPart(), player),
                new Mana((byte) ManaAtom.RED, snow, snow.getManaAbilities().get(0).getManaPart(), player),
                new Mana((byte) ManaAtom.RED, radha, persistentAbility.getManaPart(), player),
                new Mana((byte) ManaAtom.RED, roku, combatAbility.getManaPart(), player));
        for (var token : tokens) player.getManaPool().addMana(token);
        var ability = spell("Lightning Bolt", player);
        var before = BenchMenuStateAudit.capture(game); var rng = BenchRandomAudit.begin();
        var domain = new RulesPaymentDomain(player, ability); var pool = domain.request().getAsJsonArray("pool");
        check(pool.size() == 4, "ordinary, snow, persistent, combat floating tokens all retained");
        for (int i = 0; i < pool.size(); i++) {
            var item = pool.get(i).getAsJsonObject();
            if (item.get("snow").getAsBoolean() != tokens.get(i).isSnow()
                    || item.get("persistent").getAsBoolean() != tokens.get(i).isPersistentMana()
                    || item.get("combat").getAsBoolean() != tokens.get(i).isCombatMana())
                throw new AssertionError("Floating token traits not represented exactly");
        }
        check(!tokens.get(0).isSnow() && tokens.get(1).isSnow() && tokens.get(2).isPersistentMana()
                && tokens.get(3).isCombatMana(), "actual pinned mana objects have distinct snow/persistence/combat semantics");
        var witness = domain.select(answer(List.of(), List.of("pool" + selected), 0));
        check(witness.allocations().get(0).token().floating() == tokens.get(selected), "host-selected trait-bearing floating token identity retained " + selected);
        BenchRandomAudit.assertUnchanged(rng, "floating traits domain"); BenchMenuStateAudit.assertUnchanged(before, game);
        final RulesPaymentExecutor[] payment = {null};
        player.dangerouslySetController(new forge.ai.PlayerControllerAi(game, player, player.getLobbyPlayer()) {
            @Override public boolean payManaCost(forge.card.mana.ManaCost toPay, forge.game.cost.CostPartMana part, SpellAbility actual,
                    String prompt, forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
                if (payment[0] == null || matrix != null) throw new AssertionError("Unexpected floating payment callback");
                return payment[0].pay(toPay, part, actual, effect);
            }
        });
        payment[0] = new RulesPaymentExecutor(player, ability, witness);
        if (!forge.ai.ComputerUtil.handlePlayingSpellAbility(player, ability, null, payment[0]::decisions)) throw new AssertionError("Actual floating cast failed");
        payment[0].assertPaid();
        if (ability.getPayingMana().size() != 1 || ability.getPayingMana().get(0) != tokens.get(selected)) throw new AssertionError("Spent a different floating identity");
        for (int i = 0; i < tokens.size(); i++) {
            boolean present = false;
            for (var remaining : player.getManaPool()) if (remaining == tokens.get(i)) present = true;
            if (present == (i == selected)) throw new AssertionError("Unexpected floating token removed/retained");
        }
        check(player.getManaPool().totalMana() == 3, "actual executor consumes only requested floating identity " + selected);
    }
    private static void largeDomainAndExecution(boolean surplus) {
        var game = game(); var player = game.getPlayers().get(0); var lands = new ArrayList<Card>();
        for (int i = 0; i < 20; i++) lands.add(card("Mountain", player, ZoneType.Battlefield));
        var spell = spell("Lightning Bolt", player);
        boolean oldBound = false;
        try { new RulesPaymentChoices(player, spell); }
        catch (RulesCostFeasibility.Unsupported expected) { oldBound = expected.getMessage().contains("complete payment"); }
        check(oldBound, "20 ordinary sources reproduce eager payment blow-up");
        var before = BenchMenuStateAudit.capture(game); var rng = BenchRandomAudit.begin();
        var domain = new RulesPaymentDomain(player, spell); var request = domain.request(); var ids = ids(request);
        check(ids.size() == 20 && !request.has("menu") && request.toString().length() < 20_000,
                "complete 20-source domain is linear-sized and has no eager plan list");
        Collections.reverse(ids);
        var requested = answer(surplus ? ids : List.of(ids.get(0)), List.of(ids.get(0) + ":0"), 0);
        var witness = domain.select(requested);
        check(witness.sources().size() == (surplus ? 20 : 1) && witness.sources().get(0).ability().getHostCard() == lands.get(19),
                "host-selected late source and exact source ordering retained; surplus=" + surplus);
        BenchRandomAudit.assertUnchanged(rng, "compact domain and validation"); BenchMenuStateAudit.assertUnchanged(before, game);
        final RulesPaymentExecutor[] payment = {null};
        player.dangerouslySetController(new forge.ai.PlayerControllerAi(game, player, player.getLobbyPlayer()) {
            @Override public boolean payManaCost(forge.card.mana.ManaCost toPay, forge.game.cost.CostPartMana part, SpellAbility ability,
                    String prompt, forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
                if (payment[0] == null || matrix != null) throw new AssertionError("Unexpected payment callback");
                return payment[0].pay(toPay, part, ability, effect);
            }
        });
        payment[0] = new RulesPaymentExecutor(player, spell, witness);
        if (!forge.ai.ComputerUtil.handlePlayingSpellAbility(player, spell, null, payment[0]::decisions)) throw new AssertionError("Actual cast failed");
        payment[0].assertPaid();
        check(spell.getHostCard().isInZone(ZoneType.Stack) && lands.get(19).isTapped()
                        && player.getManaPool().totalMana() == (surplus ? 19 : 0)
                        && lands.stream().filter(Card::isTapped).count() == (surplus ? 20 : 1),
                "actual executor obeys compact witness including deliberately surplus taps=" + surplus);
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "compact-payment-fixture";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            BenchRandomAudit.install(91801);
            legacyEquivalence("Lightning Bolt", "Mountain", "Mountain");
            legacyEquivalence("Incinerate", "Mountain", "Mountain", "Mountain");
            legacyEquivalence("Lightning Bolt", "Black Lotus");
            legacyEquivalence("Lightning Bolt", "Mana Confluence", "Mountain");
            floatingProvenance(); validation(); zeroAndLifeOnly();
            for (int selected = 0; selected < 4; selected++) pooledTraits(selected);
            largeDomainAndExecution(false); largeDomainAndExecution(true);
            System.out.println("PASS " + checks + " compact-domain development checks; production hookup and whole-game certification remain separate");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
