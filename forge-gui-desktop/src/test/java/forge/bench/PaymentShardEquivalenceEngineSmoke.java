package forge.bench;

import com.google.common.eventbus.Subscribe;
import com.google.gson.*;
import forge.StaticData;
import forge.ai.ComputerUtil;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardProperty;
import forge.game.cost.Cost;
import forge.game.event.GameEventManaPool;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
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

/** Bounded payment-boundary differential, NOT a whole-game or strength claim.
 * Sources activate once in fixed order. Replays reuse those very same floating
 * Mana objects, not equal-color replacements. Only fixture restoration happens
 * between observations; production code is never reset or normalized.
 */
public final class PaymentShardEquivalenceEngineSmoke {
    private static int checks;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    public static final class Events {
        final List<String> rows = new ArrayList<>();
        @Subscribe public void event(forge.game.event.GameEvent event) {
            rows.add(event.getClass().getName() + ":" + event);
        }
    }
    private static Card card(String name, Player p, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var c = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)), p);
        c.setGameTimestamp(p.getGame().getNextTimestamp()); p.getZone(zone).add(c); c.setSickness(false); return c;
    }
    private static List<String> ids(List<Mana> values, IdentityHashMap<Mana,String> names) {
        return values.stream().map(m -> Objects.requireNonNull(names.get(m), "unknown physical mana")).toList();
    }
    private static List<Mana> pool(Player p) {
        var out = new ArrayList<Mana>(); for (var m : p.getManaPool()) out.add(m); return out;
    }
    private static Map<String,String> triggers(Game game) throws Exception {
        var out = new TreeMap<String,String>();
        for (String name : List.of("suppressedModes", "activeTriggers", "delayedTriggers", "thisTurnDelayedTriggers", "playerDefinedDelayedTriggers", "waitingTriggers")) {
            var field = game.getTriggerHandler().getClass().getDeclaredField(name); field.setAccessible(true);
            out.put(name, String.valueOf(field.get(game.getTriggerHandler())));
        }
        return out;
    }
    private static JsonObject answer(RulesPaymentDomain domain, List<Mana> wanted, List<Mana> present, boolean swap) {
        var request = domain.request(); var shards = request.getAsJsonObject("cost").getAsJsonArray("shards");
        check(shards.size() == 2, "fixture has exactly two ordinary shards");
        var out = new JsonObject(); out.addProperty("paymentVersion", RulesPaymentDomain.PAYMENT_VERSION);
        out.addProperty("x", 0);
        out.add("sourceOrder", new JsonArray()); out.addProperty("lifePaid", 0);
        var spend = new JsonArray();
        for (int i = 0; i < wanted.size(); i++) {
            int index = -1; for (int j = 0; j < present.size(); j++) if (present.get(j) == wanted.get(i)) index = j;
            if (index < 0) throw new AssertionError("missing physical token");
            var allocation = new JsonObject(); allocation.addProperty("token", "pool" + index);
            allocation.addProperty("shardIndex", swap ? 1 - i : i); spend.add(allocation);
        }
        out.add("spend", spend); return out;
    }
    private record Observation(Map<String,String> state, List<String> spent, List<String> remaining,
                               List<Integer> paidAbilities, List<String> events, Map<String,String> triggers,
                               JsonObject rng, String metadata, String remembered, String bill) {}
    private static Observation pay(Game game, SpellAbility sa, List<Mana> wanted, boolean swap,
                                   IdentityHashMap<Mana,String> names, Events events) throws Exception {
        Player p = sa.getActivatingPlayer(); var beforeRng = ((BenchRandomAudit.AuditedRandom) forge.util.MyRandom.getRandom()).snapshot();
        var domain = new RulesPaymentDomain(p, sa); var witness = domain.select(answer(domain, wanted, pool(p), swap));
        for (int i = 0; i < wanted.size(); i++) check(witness.allocations().get(i).token().floating() == wanted.get(i), "decoder retains exact object and consumption order " + i);
        var executor = new RulesPaymentExecutor(p, sa, witness); events.rows.clear();
        check(executor.pay(witness.cost(), new forge.game.cost.CostPartMana(witness.cost(), ""), sa, false), "actual executor completes selected payment");
        executor.assertPaid();
        // Independently exercise the same exact-shard cost primitive to retain
        // its final bookkeeping (the executor intentionally does not expose it).
        var bill = new ManaCostBeingPaid(witness.cost());
        for (int i = 0; i < wanted.size(); i++) check(bill.payExactShard(wanted.get(i), p.getManaPool(), witness.allocations().get(i).shard()), "cost primitive accepts same assignment " + i);
        check(bill.isPaid(), "no unpaid shards remain");
        AbilityUtils.handleRemembering(sa);
        var rng = ((BenchRandomAudit.AuditedRandom) forge.util.MyRandom.getRandom()).snapshot();
        check(beforeRng.equals(rng), "payment and remembering consume no recorded RNG");
        check(events.rows.size() == 2 && events.rows.stream().allMatch(s -> s.startsWith(GameEventManaPool.class.getName())), "actual event transcript is exactly two mana removals");
        var observation = new Observation(BenchMenuStateAudit.capture(game), ids(sa.getPayingMana(), names), ids(pool(p), names),
                sa.getPayingManaAbilities().stream().map(SpellAbility::getId).toList(), List.copyOf(events.rows), triggers(game), rng,
                sa.getAmountLifePaid() + "/" + sa.getXManaCostPaid() + "/" + sa.getSpendPhyrexianMana() + "/" + sa.getManaCostBeingPaid(),
                String.valueOf(sa.getHostCard().getRemembered()), bill.toString() + "/" + bill.getColorsPaid()
                        + "/" + bill.getSunburst() + "/" + bill.getXcounter() + "/" + bill.getXManaCostPaidByColor()
                        + "/" + bill.getUnpaidShards() + "/" + bill.getUnpaidColors() + "/" + bill.getGenericManaAmount());
        System.out.println("OBSERVATION " + new Gson().toJson(observation));
        return observation; // Captured before ANY restoration.
    }
    private static void restore(Player p, SpellAbility sa, List<Mana> all) {
        for (var m : pool(p)) p.getManaPool().removeMana(m);
        for (var m : all) p.getManaPool().addMana(m);
        sa.clearManaPaid(); sa.getPayingManaAbilities().clear(); sa.getHostCard().clearRemembered();
    }
    private static void fixture(boolean hybrid) throws Exception {
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0", 0, 0, null, "Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), players, "Shard equivalence fixture").createGame();
        game.setAge(GameStage.Play); var p = game.getPlayers().get(0); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        var sources = List.of(card(hybrid ? "Island" : "Plains", p, ZoneType.Battlefield), card("Plains", p, ZoneType.Battlefield), card("Plains", p, ZoneType.Battlefield));
        var sa = card("Wall of Omens", p, ZoneType.Hand).getFirstSpellAbility(); sa.setActivatingPlayer(p);
        if (hybrid) sa.setPayCosts(new Cost("1 W/U", false)); // Explicit synthetic ordinary bill, not printed-card claim.
        sa.getMapParams().put("RememberCostMana", "True");
        game.getAction().checkStateEffects(true);
        var tokens = new ArrayList<Mana>(); var names = new IdentityHashMap<Mana,String>(); var activationOrder = new ArrayList<Integer>();
        for (var source : sources) {
            var mana = source.getManaAbilities().get(0); mana.setActivatingPlayer(p);
            check(ComputerUtil.handlePlayingSpellAbility(p, mana, null), "fixture really activates source " + source.getId());
            var emitted = List.copyOf(mana.getManaPart().getLastManaProduced()); check(emitted.size() == 1, "one actual emitted token");
            var token = emitted.get(0); names.put(token, "source:" + source.getId() + ":0"); tokens.add(token); activationOrder.add(mana.getId());
        }
        check(pool(p).size() == 3 && sources.stream().allMatch(Card::isTapped), "fixed source activation order produced three physical tokens");
        System.out.println("SOURCE_ACTIVATION_ORDER " + activationOrder);
        // ManaPool iteration groups by color; decoder references its actual order.
        var events = new Events(); game.subscribeToEvents(events);
        var wanted = List.of(tokens.get(0), tokens.get(1));
        var first = pay(game, sa, wanted, false, names, events);
        restore(p, sa, tokens);
        var second = pay(game, sa, wanted, true, names, events);
        check(first.equals(second), "same exact consumed objects/order, swapped ordinary shards: identical observed payment boundary hybrid=" + hybrid);
        restore(p, sa, tokens);
        var subset = pay(game, sa, List.of(tokens.get(0), tokens.get(2)), false, names, events);
        check(!first.spent().equals(subset.spent()) && !first.remaining().equals(subset.remaining()), "different same-color source subset remains distinguishable");
        restore(p, sa, tokens);
        var reversed = pay(game, sa, List.of(tokens.get(1), tokens.get(0)), false, names, events);
        check(!first.spent().equals(reversed.spent()), "reversed consumption is not canonicalized");
        if (hybrid) {
            check(!first.remembered().equals(reversed.remembered()), "actual RememberCostMana consumer distinguishes U W versus W U");
            // Put the already-paid spell on the real stack for ActivationColor's
            // instance lookup. This separate negative control is after snapshots.
            game.getStack().addAndUnfreeze(sa);
            var stackAbility = game.getStack().getInstanceMatchingSpellAbilityID(sa).getSpellAbility();
            var white = sa.getHostCard();
            check(CardProperty.cardHasProperty(white, "SharesColorWith ActivationColor", p, white, sa), "actual ActivationColor sees reversed first white token");
            stackAbility.getPayingMana().clear(); stackAbility.getPayingMana().addAll(wanted);
            check(!CardProperty.cardHasProperty(white, "SharesColorWith ActivationColor", p, white, sa), "actual ActivationColor sees original first blue token");
        }
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "payment-shard-equivalence";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            BenchRandomAudit.install(91802); fixture(false); fixture(true);
            System.out.println("PASS " + checks + " payment-boundary checks. Bounded ordinary, unrestricted mana only; no whole-game equivalence or strength certification.");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
