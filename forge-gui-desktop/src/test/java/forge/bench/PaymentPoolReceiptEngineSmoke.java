package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.ai.PlayerControllerAi;
import forge.card.mana.ManaAtom;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.cost.*;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.*;

/** Real cost/source resolution with deliberate receipt-boundary corruption.
 * --baseline records which faults the old executor accepts, not a passing gate.
 * No controller AI method is used to choose or execute a payment.
 */
public final class PaymentPoolReceiptEngineSmoke {
    private static int checks;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label); checks++;
    }
    private static Card card(String name, Player p, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var c = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)), p);
        c.setGameTimestamp(p.getGame().getNextTimestamp()); p.getZone(zone).add(c); c.setSickness(false); return c;
    }
    public static final class SpendFault {
        final Player payer; final Card source; final SpellAbility ability; final boolean[] fired;
        SpendFault(Player payer, Card source, SpellAbility ability, boolean[] fired) {
            this.payer=payer; this.source=source; this.ability=ability; this.fired=fired;
        }
        @com.google.common.eventbus.Subscribe public void event(forge.game.event.GameEventManaPool event) {
            if (!fired[0] && event.mode()==forge.game.event.EventValueChangeType.Removed) {
                fired[0]=true;
                payer.getManaPool().addManaNoEvent(new Mana((byte)ManaAtom.GREEN, source, ability.getManaPart(), payer));
            }
        }
    }
    private static void run(int seat, String sourceName, String fault, boolean baseline) {
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("A", 0, 0, null, "Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("B", 1, 0, null, "Default")));
        var g = new Match(new GameRules(GameType.Constructed), players, "Pool receipt fixture").createGame();
        g.setAge(GameStage.Play); var p = g.getPlayers().get(seat); var opponent = g.getPlayers().get(1-seat);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        var source = card(sourceName, p, ZoneType.Battlefield);
        var other = card("Island", p, ZoneType.Battlefield); other.setTapped(true);
        var manaAbility = source.getManaAbilities().get(0);
        var spell = card("Lightning Bolt", p, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(p); spell.getTargets().add(opponent);
        var prior = new Mana((byte)ManaAtom.COLORLESS, other, other.getManaAbilities().get(0).getManaPart(), p);
        p.getManaPool().addMana(prior);
        final boolean[] injected = {false};
        var part = new AbilityManaPart(manaAbility, manaAbility.getMapParams()) {
            @Override public Collection<Mana> getLastManaProduced() {
                var actual = new ArrayList<>(super.getLastManaProduced());
                if (injected[0] || actual.isEmpty() || fault.equals("none") || fault.equals("spend-extra")) return actual;
                injected[0] = true;
                switch (fault) {
                    case "extra" -> p.getManaPool().addMana(new Mana((byte)ManaAtom.GREEN, source, this, p));
                    case "extra-occurrence" -> p.getManaPool().addMana(actual.get(0));
                    case "removed-floating" -> check(p.getManaPool().removeMana(prior), "remove prior token");
                    case "equal-replaced-floating" -> {
                        check(p.getManaPool().removeMana(prior), "replace prior token");
                        var replacement = new Mana(prior.getColor(), other, prior.getManaAbility(), p);
                        check(prior != replacement && prior.equals(replacement), "equal does not mean same token");
                        p.getManaPool().addMana(replacement);
                    }
                    case "opponent-extra" -> opponent.getManaPool().addMana(new Mana((byte)ManaAtom.BLUE, other, this, opponent));
                    case "foreign-producer", "wrong-recipient", "wrong-ability" -> {
                        check(p.getManaPool().removeMana(actual.get(0)), "replace emitted token");
                        var replacement = new Mana(actual.get(0).getColor(), fault.equals("foreign-producer") ? other : source,
                                fault.equals("wrong-ability") ? new AbilityManaPart(manaAbility, manaAbility.getMapParams()) : this,
                                fault.equals("wrong-recipient") ? opponent : p);
                        p.getManaPool().addMana(replacement); actual.set(0, replacement);
                    }
                    default -> throw new AssertionError("unknown injection " + fault);
                }
                return actual;
            }
        };
        manaAbility.setManaPart(part);
        g.getAction().checkStateEffects(true); g.getTriggerHandler().resetActiveTriggers();
        var before = BenchMenuStateAudit.capture(g); var random = BenchRandomAudit.begin();
        var domain = new RulesPaymentDomain(p, spell);
        var option = domain.request().getAsJsonArray("sourceOptions").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(o -> o.get("fid").getAsInt()==source.getId() && o.getAsJsonArray("output").get(0).getAsString().equals("R"))
                .findFirst().orElseThrow();
        var answer = new JsonObject(); answer.addProperty("paymentVersion", RulesPaymentDomain.PAYMENT_VERSION);
        answer.addProperty("x", 0); answer.addProperty("lifePaid", 0);
        var order = new JsonArray(); order.add(option.get("id").getAsString()); answer.add("sourceOrder", order);
        var allocation = new JsonObject(); allocation.addProperty("token", option.get("id").getAsString()+":0"); allocation.addProperty("shardIndex", 0);
        var spend = new JsonArray(); spend.add(allocation); answer.add("spend", spend);
        var witness = domain.select(answer);
        BenchMenuStateAudit.assertUnchanged(before, g); BenchRandomAudit.assertUnchanged(random, "pool receipt quote");
        if (fault.equals("spend-extra")) g.subscribeToEvents(new SpendFault(p, source, manaAbility, injected));
        final RulesPaymentExecutor[] executor = {null};
        p.dangerouslySetController(new PlayerControllerAi(g, p, p.getLobbyPlayer()) {
            @Override public byte chooseColor(String message, SpellAbility actual, forge.card.ColorSet colors) {
                return executor[0].chooseSourceColor(actual, colors);
            }
            @Override public boolean payManaCost(forge.card.mana.ManaCost cost, CostPartMana costPart, SpellAbility sa,
                    String prompt, forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
                check(matrix==null && executor[0]!=null, "only owned payment callback");
                return executor[0].pay(cost, costPart, sa, effect);
            }
        });
        executor[0] = new RulesPaymentExecutor(p, spell, witness);
        boolean rejected = false;
        try {
            check(new CostPayment(spell.getPayCosts(), spell).payComputerCosts(executor[0].decisions(spell)), "real cost execution");
            executor[0].assertPaid();
        } catch (RulesCostFeasibility.Unsupported failure) {
            if (!failure.getMessage().contains("exact receipt") && !failure.getMessage().contains("producer/recipient")) throw failure;
            rejected = true;
        }
        if (fault.equals("none")) {
            check(!rejected, "ordinary production preserved");
            check(spell.getPayingMana().size()==1, "exact selected spend");
            var emitted = new ArrayList<>(part.getLastManaProduced());
            check(p.getManaPool().totalMana()==emitted.size(), "surplus and prior mana retained");
            if (sourceName.equals("Black Lotus")) {
                check(source.isInZone(ZoneType.Graveyard), "native Lotus sacrifice");
                check(emitted.size()==3 && emitted.get(0)==emitted.get(1) && emitted.get(1)==emitted.get(2), "native repeated object is three mana units");
                check(emitted.get(0).getSourceCard().getId()==source.getId(), "LKI producer identity retained");
            }
        } else {
            check(injected[0], "fault executed");
            if (!baseline) {
                check(rejected, "corruption must invalidate: " + fault);
                check(spell.getPayingMana().isEmpty(), "reject before spending selected bill: " + fault);
            }
        }
        System.out.println("POOL_RECEIPT seat="+seat+" source="+sourceName+" fault="+fault+" rejected="+rejected);
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (p,m,v) -> switch(m.getName()) {
                        case "getAssetsDir" -> args[0]+"/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "pool-receipt-fixture";
                        default -> throw new AssertionError("unexpected GUI: "+m.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            BenchRandomAudit.install(91802);
            boolean baseline=args.length>1 && args[1].equals("--baseline");
            for (int seat=0; seat<2; seat++) {
                run(seat,"Mountain","none",baseline); run(seat,"Black Lotus","none",baseline);
                for (String fault : List.of("extra","extra-occurrence","removed-floating","equal-replaced-floating",
                        "opponent-extra","foreign-producer","wrong-recipient","wrong-ability","spend-extra")) run(seat,"Mountain",fault,baseline);
            }
            System.out.println((baseline ? "BASELINE characterization " : "PASS ")+checks+" pool receipt checks; no whole-game claim");
            System.exit(0);
        } catch(Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
