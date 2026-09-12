package forge.bench;

import forge.StaticData;
import forge.ai.AiCardMemory;
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
import forge.util.MyRandom;
import java.util.List;
import java.util.Random;
import static forge.bench.RulesCostFeasibility.Status.*;

/** Actual pinned card scripts, no matches or AI decisions. */
public final class RulesCostFeasibilityEngineSmoke {
    private static int checks;
    private static Game game() {
        var registered = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Payer", 0, 0, null, "Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), registered, "Rules cost fixture").createGame();
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        return game;
    }
    private static Card card(String name, Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var paper = FModel.getMagicDb().getCommonCards().getCard(name);
        if (paper == null) throw new AssertionError("Missing pinned card " + name);
        var card = Card.fromPaperCard(paper, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        card.setSickness(false);
        return card;
    }
    static String state(Game game) {
        var out = new StringBuilder();
        try {
            var sequence = SpellAbility.class.getDeclaredField("maxId");
            sequence.setAccessible(true);
            out.append("abilityIdSequence=").append(sequence.getInt(null));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        for (var player : game.getPlayers()) {
            out.append(player.getLife()).append('/').append(player.getManaPool().totalMana());
            for (var set : AiCardMemory.MemorySet.values()) out.append(set).append(AiCardMemory.getMemorySet(player, set));
        }
        for (var card : game.getCardsInGame()) {
            out.append('|').append(card.getId()).append(':').append(card.getZone()).append(':').append(card.isTapped()).append(':').append(card.getCounters());
            for (var sa : card.getSpellAbilities()) out.append(';').append(sa.getId()).append(':').append(sa.getActivatingPlayer())
                    .append(':').append(sa.getPayCosts()).append(':').append(sa.getPayCosts() == null ? null : sa.getPayCosts().getTotalMana())
                    .append(':').append(sa.getXManaCostPaid()).append(':').append(sa.getTargets()).append(':').append(sa.getTargetingPlayer())
                    .append(':').append(sa.getPipsToReduce()).append(':').append(sa.getPayingMana()).append(':').append(sa.getOptionalCosts())
                    .append(':').append(new java.util.TreeMap<>(sa.getMapParams())).append(':').append(new java.util.TreeMap<>(sa.getSVars()))
                    .append(':').append(new com.google.gson.Gson().toJson(sa.getRestrictions()));
        }
        return out.toString();
    }
    private static void verify(Player player, SpellAbility sa, RulesCostFeasibility.Status expected, String label) {
        sa.setActivatingPlayer(player);
        String before = state(player.getGame());
        Random previous = MyRandom.getRandom();
        MyRandom.setRandom(new Random(1) { @Override protected int next(int bits) { throw new AssertionError("Feasibility consumed game RNG"); } });
        try {
            for (int i = 0; i < 3; i++) {
                var actual = RulesCostFeasibility.assess(player, sa);
                if (actual.status() != expected) throw new AssertionError(label + ": " + actual + " expected " + expected);
                if (!before.equals(state(player.getGame()))) throw new AssertionError(label + " mutated state/memory");
            }
        } finally { MyRandom.setRandom(previous); }
        checks++;
        System.out.println("PASS " + label + " " + expected);
    }
    private static void spell(String name, String[] lands, String modifier, RulesCostFeasibility.Status expected) {
        var game = game(); var player = game.getPlayers().get(0);
        for (String land : lands) {
            var source = card(land, player, ZoneType.Battlefield);
            for (var set : AiCardMemory.MemorySet.values()) AiCardMemory.rememberCard(player, source, set);
        }
        if (modifier != null) card(modifier, player, ZoneType.Battlefield);
        var host = card(name, player, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        verify(player, host.getFirstSpellAbility(), expected, name + " " + List.of(lands) + " " + modifier);
    }
    private static void castOnlyVersusPlay(String sourceName, boolean mayPlayLand) {
        var game = game(); var player = game.getPlayers().get(0);
        var source = card(sourceName, player, ZoneType.Battlefield);
        if (sourceName.equals("Decadent Dragon")) source.setState(forge.card.CardStateName.Secondary, false);
        var land = card("Mountain", game.getPlayers().get(1), ZoneType.Exile);
        land.turnFaceDown(true);
        source.addRemembered(land);
        String script = source.getSVar("DBEffect");
        if (script.isEmpty()) throw new AssertionError("Missing actual permission script for " + sourceName);
        var effect = forge.game.ability.AbilityFactory.getAbility(script, source);
        effect.setActivatingPlayer(player);
        forge.game.ability.AbilityUtils.resolve(effect);
        game.getAction().checkStateEffects(true);
        var possible = land.getAllPossibleAbilities(player, true);
        boolean offered = possible.stream().anyMatch(a -> a.isLandAbility() && a.canPlay());
        if (offered != mayPlayLand) throw new AssertionError("Actual " + sourceName + " script face-down exiled Mountain: "
                + "expected land permission=" + mayPlayLand + " offered=" + offered + " menu=" + possible);
        checks++;
        System.out.println("PASS " + sourceName + " cast-only versus play permission " + offered);
    }
    private static void executeWitness(String spellName, String sourceName, int expectedFloating, boolean alternate) {
        var game = game(); var player = game.getPlayers().get(0);
        final RulesPaymentExecutor[] payment = {null};
        player.dangerouslySetController(new forge.ai.PlayerControllerAi(game, player, player.getLobbyPlayer()) {
            @Override public byte chooseColor(String message, SpellAbility actual, forge.card.ColorSet colors) {
                return payment[0].chooseSourceColor(actual, colors);
            }
            @Override public boolean payManaCost(forge.card.mana.ManaCost toPay, forge.game.cost.CostPartMana part,
                    SpellAbility sa, String prompt, forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
                if (payment[0] == null || matrix != null) throw new AssertionError("Unexpected payment callback");
                return payment[0].pay(toPay, part, sa, effect);
            }
        });
        var source = card(sourceName, player, ZoneType.Battlefield);
        var other = alternate ? card("Island", player, ZoneType.Battlefield) : null;
        var spell = card(spellName, player, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        if (spell.usesTargeting()) spell.getTargets().add(game.getPlayers().get(1));
        game.getAction().checkStateEffects(true);
        for (var set : AiCardMemory.MemorySet.values()) AiCardMemory.rememberCard(player, source, set);
        var choices = new RulesPaymentChoices(player, spell);
        var request = choices.request();
        int wantedFid = alternate ? other.getId() : source.getId();
        var optionIds = new java.util.HashSet<String>();
        for (var option : request.getAsJsonArray("sourceOptions")) if (option.getAsJsonObject().get("fid").getAsInt() == wantedFid)
            optionIds.add(option.getAsJsonObject().get("id").getAsString());
        var menu = request.getAsJsonArray("menu");
        int selected = -1;
        for (int i = 0; i < menu.size(); i++) {
            var selectedSources = menu.get(i).getAsJsonObject().getAsJsonArray("sources");
            if (selectedSources.size() == 1 && optionIds.contains(selectedSources.get(0).getAsString())) { selected = i; break; }
        }
        if (selected < 0) throw new AssertionError("Explicit requested source absent from complete menu");
        if (alternate && menu.size() != 4) throw new AssertionError("Two one-output sources paying {1} should retain four plans");
        if (sourceName.equals("Black Lotus") && menu.size() != 1) throw new AssertionError("Same-activation repeated R tokens are interchangeable");
        var answer = new com.google.gson.JsonObject();
        answer.addProperty("choice", selected);
        answer.add("sourceOrder", menu.get(selected).getAsJsonObject().getAsJsonArray("sources").deepCopy());
        payment[0] = new RulesPaymentExecutor(player, spell, choices.select(answer));
        var random = MyRandom.getRandom();
        MyRandom.setRandom(new Random(1) { @Override protected int next(int bits) { throw new AssertionError("Payment execution used random"); } });
        try {
            if (!forge.ai.ComputerUtil.handlePlayingSpellAbility(player, spell, null, payment[0]::decisions))
                throw new AssertionError("Selected spell failed to enter stack");
            payment[0].assertPaid();
        } finally { MyRandom.setRandom(random); }
        if (!spell.getHostCard().isInZone(ZoneType.Stack) || player.getManaPool().totalMana() != expectedFloating)
            throw new AssertionError("Actual stack/mana state differs from witness");
        if (alternate && (source.isTapped() || !other.isTapped())) throw new AssertionError("Ignored explicit alternate payment source");
        if(sourceName.equals("Mana Vault") && (!source.isTapped() || !source.isInZone(ZoneType.Battlefield)
                || source.getReplacementEffects().stream().noneMatch(r->r.getMode()==forge.game.replacement.ReplacementType.Untap)))
            throw new AssertionError("Mana Vault payment must tap the source and preserve its untap restriction");
        if (sourceName.equals("Black Lotus")) {
            var emitted = List.copyOf(source.getManaAbilities().get(0).getManaPart().getLastManaProduced());
            if (emitted.size() != 3) throw new AssertionError("Lotus did not emit three actual mana objects");
            for (var mana : emitted) if (!mana.equals(emitted.get(0)) || mana.getManaAbility() != emitted.get(0).getManaAbility()
                    || mana.isRestricted() || mana.triggersWhenSpent() || mana.addsCounters(spell)
                    || mana.addsKeywords(spell) || mana.addsNoCounterMagic(spell))
                throw new AssertionError("Within-activation token equivalence does not hold");
        }
        for (var set : AiCardMemory.MemorySet.values()) if (!AiCardMemory.getMemorySet(player, set).contains(source))
            throw new AssertionError("Execution cleared AI reservation memory " + set);
        checks++;
        System.out.println("PASS executed " + spellName + " from " + sourceName + " alternate=" + alternate + " floating=" + expectedFloating);
    }
    private static void paymentSurfaceChecks() {
        var game = game(); var player = game.getPlayers().get(0);
        var first = card("Mountain", player, ZoneType.Battlefield);
        var second = card("Mountain", player, ZoneType.Battlefield);
        var spell = card("Lightning Bolt", player, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        var choices = new RulesPaymentChoices(player, spell);
        var request = choices.request();
        if (request.getAsJsonArray("menu").size() != 4) throw new AssertionError("Different producer provenance was collapsed");
        var invalid = new com.google.gson.JsonObject();
        invalid.addProperty("choice", 0);
        invalid.add("sourceOrder", new com.google.gson.JsonArray());
        try { choices.select(invalid); throw new AssertionError("Missing selected source order accepted"); }
        catch (RulesCostFeasibility.Unsupported expected) { }
        invalid.addProperty("choice", -1);
        try { choices.select(invalid); throw new AssertionError("Negative payment choice accepted"); }
        catch (RulesCostFeasibility.Unsupported expected) { }
        first.setTapped(true); second.setTapped(true);
        var manaAbility = first.getManaAbilities().get(0);
        player.getManaPool().addMana(new forge.game.mana.Mana((byte) forge.card.mana.ManaAtom.RED, first, manaAbility.getManaPart(), player));
        player.getManaPool().addMana(new forge.game.mana.Mana((byte) forge.card.mana.ManaAtom.RED, first, manaAbility.getManaPart(), player));
        var floating = new RulesPaymentChoices(player, spell).request();
        if (floating.getAsJsonArray("pool").size() != 2 || floating.getAsJsonArray("menu").size() != 2
                || floating.getAsJsonArray("menu").get(0).getAsJsonObject().getAsJsonArray("spend").get(0).getAsJsonObject().get("token")
                    .equals(floating.getAsJsonArray("menu").get(1).getAsJsonObject().getAsJsonArray("spend").get(0).getAsJsonObject().get("token")))
            throw new AssertionError("Floating token identities collapsed");
        checks++;
        System.out.println("PASS complete payment provenance, invalid-answer and floating-token checks");
    }

    private static void untapScopeChecks() {
        for(int seat=0;seat<2;seat++) {
            var game=game();var payer=game.getPlayers().get(seat);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1,payer);
            var vault=card("Mana Vault",payer,ZoneType.Battlefield);
            var spell=card("Sol Ring",payer,ZoneType.Hand).getFirstSpellAbility();
            if(vault.getReplacementEffects().stream().noneMatch(r->r.getMode()==forge.game.replacement.ReplacementType.Untap))
                throw new AssertionError("actual Mana Vault lacks untap replacement");
            verify(payer,spell,PAYABLE,"untap replacement does not affect tap payment seat="+seat);
            vault.setTapped(true);
            verify(payer,spell,UNPAYABLE,"irrelevant untap replacement does not manufacture untapped mana seat="+seat);
            var untap=forge.game.ability.AbilityFactory.getAbility("AB$ Mana | Cost$ Q | Produced$ C | Amount$ 1",vault);
            verify(payer,untap,UNSUPPORTED,"actual untap cost remains outside admitted payment scope seat="+seat);
        }
    }
    private static void reductionScopeChecks() {
        for (int seat = 0; seat < 2; seat++) {
            var game = game(); var player = game.getPlayers().get(seat);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player);
            var host = card("Basalt Monolith", player, ZoneType.Battlefield);
            host.setTapped(true);
            var zirda = card("Zirda, the Dawnwaker", player, ZoneType.Battlefield);
            // Literal costs: generic floor, colored requirements, and free costs.
            String[] costs = {"4", "3", "2", "1", "0", "2 W", "1 W", "W"};
            String[] expected = {"2", "1", "1", "1", "0", "W", "W", "W"};
            game.getAction().checkStateEffects(true);
            for (int i = 0; i < costs.length; i++) {
                var sa = forge.game.ability.AbilityFactory.getAbility("AB$ Untap | Cost$ " + costs[i], host);
                sa.setActivatingPlayer(player);
                String before = state(game);
                var rng = MyRandom.getRandom();
                MyRandom.setRandom(new Random(1) { @Override protected int next(int bits) { throw new AssertionError("Reducer query consumed RNG"); } });
                forge.game.cost.CostAdjustment.BenchmarkManaPrice price;
                try { price = forge.game.cost.CostAdjustment.benchmarkManaPrice(sa); }
                finally { MyRandom.setRandom(rng); }
                if (price == null || !before.equals(state(game))) throw new AssertionError("Missing/mutating reduction quote");
                var want = new forge.card.mana.ManaCost(expected[i]);
                if (!RulesPaymentExecutor.sameManaCost(price.payable(), want)) throw new AssertionError("Reduction floor/colored cost: " + costs[i] + " got " + price);
                var nativeCost = new forge.game.mana.ManaCostBeingPaid(price.beforeReduction());
                if (!forge.game.cost.CostAdjustment.adjust(nativeCost, sa, player, null, false, false)
                        || !RulesPaymentExecutor.sameManaCost(nativeCost.isPaid() ? forge.card.mana.ManaCost.ZERO : nativeCost.toManaCost(), price.payable()))
                    throw new AssertionError("Stock/controlled reduction disagree");
                checks++;
            }
            // The reducer must not affect spells, mana abilities, or opponents.
            var spell = card("Sol Ring", player, ZoneType.Hand).getFirstSpellAbility(); spell.setActivatingPlayer(player);
            if (forge.game.cost.CostAdjustment.benchmarkManaCost(spell).getGenericCost() != 1) throw new AssertionError("Zirda discounted a spell");
            var other = card("Basalt Monolith", game.getPlayers().get(1-seat), ZoneType.Battlefield);
            var otherUntap = other.getSpellAbilities().stream().filter(a -> a.getApi() == forge.game.ability.ApiType.Untap).findFirst().orElseThrow();
            otherUntap.setActivatingPlayer(other.getController());
            if (forge.game.cost.CostAdjustment.benchmarkManaCost(otherUntap).getGenericCost() != 3) throw new AssertionError("Zirda discounted opponent");
            var manaAbility = forge.game.ability.AbilityFactory.getAbility("AB$ Mana | Cost$ 3 | Produced$ C | Amount$ 1", host);
            manaAbility.setActivatingPlayer(player);
            if (forge.game.cost.CostAdjustment.benchmarkManaCost(manaAbility).getGenericCost() != 3) throw new AssertionError("Zirda discounted mana ability");
            checks += 3;
            // Joint X/floor pricing must stay unsupported, not use unexpanded X.
            var x = forge.game.ability.AbilityFactory.getAbility("AB$ Untap | Cost$ X", host);
            x.setActivatingPlayer(player); x.setXManaCostPaid(4); x.setSVar("X", "Count$xPaid");
            if (forge.game.cost.CostAdjustment.benchmarkManaCost(x) != null) throw new AssertionError("Unproven X reduction accepted");
            checks++;
            var extra = card("Sol Ring", player, ZoneType.Battlefield);
            extra.addStaticAbility("Mode$ ReduceCost | ValidCard$ Card | ValidSpell$ Activated.!ManaAbility | Activator$ You | Amount$ 1 | MinMana$ 1");
            var fixed = forge.game.ability.AbilityFactory.getAbility("AB$ Untap | Cost$ 4", host);
            fixed.setActivatingPlayer(player);
            for (boolean reverse : new boolean[]{false, true}) {
                player.dangerouslySetController(new forge.ai.PlayerControllerAi(game, player, player.getLobbyPlayer()) {
                    @Override public forge.game.staticability.StaticAbility chooseSingleStaticAbility(List<forge.game.staticability.StaticAbility> options) {
                        return options.get(reverse ? options.size()-1 : 0);
                    }
                });
                var price = forge.game.cost.CostAdjustment.benchmarkManaPrice(fixed);
                if (price == null || price.payable().getGenericCost() != 1) throw new AssertionError("Joint same-floor reduction failed");
                var nativeCost = new forge.game.mana.ManaCostBeingPaid(price.beforeReduction());
                if (!forge.game.cost.CostAdjustment.adjust(nativeCost, fixed, player, null, false, false)
                        || nativeCost.getGenericManaAmount() != 1) throw new AssertionError("Native reduction order differs");
                checks++;
            }
            extra.addStaticAbility("Mode$ ReduceCost | ValidCard$ Card | ValidSpell$ Activated.!ManaAbility | Activator$ You | Amount$ 1");
            if (forge.game.cost.CostAdjustment.benchmarkManaPrice(fixed) != null) throw new AssertionError("Distinct floors require explicit handling");
            checks++;
        }
    }
    private static void reductionPaymentBindingChecks() {
        for (int seat = 0; seat < 2; seat++) for (boolean changedReducer : new boolean[]{false, true}) {
            var game = game(); var player = game.getPlayers().get(seat);
            var host = card("Basalt Monolith", player, ZoneType.Battlefield); host.setTapped(true);
            var zirda = card("Zirda, the Dawnwaker", player, ZoneType.Battlefield);
            var sa = host.getSpellAbilities().stream().filter(a -> a.getApi() == forge.game.ability.ApiType.Untap).findFirst().orElseThrow();
            sa.setActivatingPlayer(player);
            game.getAction().checkStateEffects(true);
            for (int n = 0; n < 3; n++) player.getManaPool().addMana(new forge.game.mana.Mana(
                    (byte)forge.card.mana.ManaAtom.COLORLESS, host, host.getManaAbilities().get(0).getManaPart(), player));
            var choices = new RulesPaymentChoices(player, sa);
            var answer = new com.google.gson.JsonObject(); answer.addProperty("choice", 0);
            answer.add("sourceOrder", choices.request().getAsJsonArray("menu").get(0).getAsJsonObject().getAsJsonArray("sources").deepCopy());
            var payment = new RulesPaymentExecutor(player, sa, choices.select(answer));
            if (changedReducer) zirda.setController(game.getPlayers().get(1-seat), game.getNextTimestamp());
            var before = state(game);
            try {
                payment.pay(new forge.card.mana.ManaCost(changedReducer ? "3" : "1"), sa.getPayCosts().getCostMana(), sa, false);
                throw new AssertionError("Changed reducer or forged incoming quote accepted");
            } catch (RulesCostFeasibility.Unsupported expected) { }
            if (!before.equals(state(game))) throw new AssertionError("Rejected payment mutated game");
            checks++;
        }
        for (int seat = 0; seat < 2; seat++) {
            var game = game(); var player = game.getPlayers().get(seat);
            var host = card("Basalt Monolith", player, ZoneType.Battlefield); host.setTapped(true);
            host.addStaticAbility("Mode$ ReduceCost | ValidCard$ Card | ValidSpell$ Activated.!ManaAbility | Activator$ You | Amount$ 3");
            var sa = host.getSpellAbilities().stream().filter(a -> a.getApi() == forge.game.ability.ApiType.Untap).findFirst().orElseThrow();
            sa.setActivatingPlayer(player);
            verify(player, sa, PAYABLE, "reduced-to-zero is payable, not absent mana cost seat=" + seat);
            var choices = new RulesPaymentChoices(player, sa);
            var answer = new com.google.gson.JsonObject(); answer.addProperty("choice", 0);
            answer.add("sourceOrder", new com.google.gson.JsonArray());
            var payment = new RulesPaymentExecutor(player, sa, choices.select(answer));
            if (!payment.pay(new forge.card.mana.ManaCost("3"), sa.getPayCosts().getCostMana(), sa, false)) throw new AssertionError("Zero reduction payment failed");
            payment.assertPaid();
            if (player.getManaPool().totalMana() != 0 || !host.isTapped()) throw new AssertionError("Zero payment altered pool/untap effect");
            checks++;
        }
    }
    private static void selfSacrificeScopeChecks() {
        for (int seat=0;seat<2;seat++) {
            var game=game();var player=game.getPlayers().get(seat);
            var source=card("Basalt Monolith",player,ZoneType.Battlefield);
            for (String cost : new String[]{"1 Sac<1/CARDNAME>", "T Sac<2/CARDNAME>", "T Sac<1/Artifact>"}) {
                var sa=forge.game.ability.AbilityFactory.getAbility("AB$ Untap | Cost$ "+cost,source);
                verify(player,sa,UNSUPPORTED,"unmodeled pre-sacrifice mana or non-exact sacrifice "+cost+" seat="+seat);
            }
            var fetch=card("Fabled Passage",player,ZoneType.Battlefield);
            var ability=fetch.getSpellAbilities().stream().filter(a -> a.getApi()==forge.game.ability.ApiType.ChangeZone).findFirst().orElseThrow();ability.setActivatingPlayer(player);
            var result=RulesCostFeasibility.assess(player,ability);
            if(result.status()!=PAYABLE)throw new AssertionError("Fabled Passage must have a witness: "+result.reason());
            var payment=new RulesPaymentExecutor(player,ability,result.witness());
            var before=state(game);
            payment.pay(forge.card.mana.ManaCost.ZERO,new forge.game.cost.CostPartMana(forge.card.mana.ManaCost.ZERO,null),ability,false);
            try {payment.assertPaid();throw new AssertionError("Skipped sacrifice falsely certified as paid");}
            catch(RulesCostFeasibility.Unsupported expected) {checks++;}
            if(!before.equals(state(game)))throw new AssertionError("Skipped-cost fault injection changed game");
        }
    }
    public static void main(String[] args) {
        try {
            // No screen/window needed to load card scripts. Unexpected GUI calls fail
            // the fixture; none may choose a gameplay action or synthesize an answer.
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                    new Class<?>[]{IGuiBase.class}, (proxy, method, values) -> {
                        return switch (method.getName()) {
                            case "getAssetsDir" -> args[0] + "/forge-gui/";
                            case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                            case "getCurrentVersion" -> "rules-fixture";
                            default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                        };
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            spell("Lightning Bolt", new String[]{}, null, UNPAYABLE);
            spell("Lightning Bolt", new String[]{"Plains"}, null, UNPAYABLE);
            spell("Lightning Bolt", new String[]{"Mountain"}, null, PAYABLE);
            spell("Lightning Bolt", new String[]{"Mountain"}, "Nether Void", PAYABLE);
            spell("Lightning Bolt", new String[]{"Mountain"}, "Sphere of Resistance", UNPAYABLE);
            spell("Lightning Bolt", new String[]{"Mountain", "Plains"}, "Sphere of Resistance", PAYABLE);
            spell("Lightning Bolt", new String[]{"Black Lotus"}, null, PAYABLE);
            spell("Esper Charm", new String[]{"Black Lotus"}, null, UNPAYABLE);
            spell("Lightning Bolt", new String[]{"Mana Confluence"}, null, PAYABLE);
            spell("Lightning Bolt", new String[]{"Mountain"}, "Mana Reflection", UNSUPPORTED);
            spell("Mana Vault", new String[]{"Plains"}, null, PAYABLE);
            spell("Mana Vault", new String[]{}, null, UNPAYABLE);
            untapScopeChecks();
            reductionScopeChecks();
            reductionPaymentBindingChecks();
            selfSacrificeScopeChecks();
            spell("Dismember", new String[]{"Swamp"}, null, UNSUPPORTED);
            var wallGame = game(); var wallPlayer = wallGame.getPlayers().get(0);
            var wall = card("Wall of Roots", wallPlayer, ZoneType.Battlefield);
            wall.setTapped(true);
            wall.setSickness(true);
            verify(wallPlayer, card("Llanowar Elves", wallPlayer, ZoneType.Hand).getFirstSpellAbility(),
                    UNSUPPORTED, "Tapped/sick non-tap mana source cannot be silently omitted");
            var game = game(); var player = game.getPlayers().get(0);
            var liliana = card("Liliana of the Veil", player, ZoneType.Battlefield);
            liliana.setCounters(CounterEnumType.LOYALTY, 2);
            var minus = liliana.getSpellAbilities().stream().filter(a -> a.getPayCosts().toString().startsWith("-2")).findFirst().orElseThrow();
            verify(player, minus, PAYABLE, "Liliana may spend last two loyalty");
            liliana.setCounters(CounterEnumType.LOYALTY, 1);
            verify(player, minus, UNPAYABLE, "Liliana may not overspend loyalty");
            var bolt = card("Lightning Bolt", player, ZoneType.Hand).getFirstSpellAbility();
            card("Mountain", player, ZoneType.Battlefield);
            var ward = card("Phyrexian Fleshgorger", game.getPlayers().get(1), ZoneType.Battlefield);
            bolt.getTargets().add(ward);
            verify(player, bolt, PAYABLE, "Targeting Ward does not add a casting cost");
            var battlemage = card("Thornscape Battlemage", player, ZoneType.Hand).getFirstSpellAbility();
            battlemage.setActivatingPlayer(player);
            var options = GameActionUtil.getOptionalCostValues(battlemage.copy());
            var variants = BenchmarkOptionalCosts.variants(battlemage, options, player);
            if (options.size() != 2 || variants.size() != 3
                    || variants.stream().map(a -> a.getPayCosts().getTotalMana().toString()).distinct().count() != 3)
                throw new AssertionError("Both singleton kicker subsets and combined kicker must survive: options=" + options
                        + " variants=" + variants.stream().map(a -> a.getPayCosts().getTotalMana().toString()).toList());
            checks++;
            System.out.println("PASS actual dual-kicker subsets " + variants.stream().map(a -> a.getPayCosts().getTotalMana().toString()).toList());
            try {
                var unsupported = card("Dismember", player, ZoneType.Hand).getFirstSpellAbility();
                unsupported.setActivatingPlayer(player);
                RulesCostFeasibility.requirePayable(player, unsupported);
                throw new AssertionError("Unknown was accepted");
            } catch (RulesCostFeasibility.Unsupported expected) { checks++; }
            executeWitness("Lightning Bolt", "Mountain", 0, false);
            executeWitness("Lightning Bolt", "Black Lotus", 2, false);
            executeWitness("Sol Ring", "Plains", 0, true);
            executeWitness("Mana Vault", "Plains", 0, false);
            executeWitness("Sol Ring", "Mana Vault", 2, false);
            paymentSurfaceChecks();
            castOnlyVersusPlay("Thief of Sanity", false);
            castOnlyVersusPlay("Decadent Dragon", true);
            System.out.println("PASS all " + checks + " rules-feasibility checks");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
