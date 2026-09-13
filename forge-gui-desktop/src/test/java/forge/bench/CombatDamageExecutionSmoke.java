package forge.bench;

import com.google.gson.*;
import forge.game.card.*;
import forge.game.combat.*;
import forge.game.phase.PhaseType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.util.*;
import static forge.bench.CombatDeclarationExecutionSmoke.*;

/** Actual Combat assignment/application plus strict transport fault injection.
 * Constructed development worlds, never a measured playing-strength panel. */
public final class CombatDamageExecutionSmoke {
    static int checks;
    static void verify(boolean ok, String why) { if (!ok) throw new AssertionError(why); checks++; }
    static JsonObject map(Object... entries) {
        JsonObject out = new JsonObject();
        for (int i = 0; i < entries.length; i += 2) out.addProperty(String.valueOf(entries[i]), (Number) entries[i+1]);
        return out;
    }
    static JsonObject coverage(C c) {
        return c.controller().getCounters().toJson().getAsJsonObject("controllerCoverage")
                .getAsJsonObject("methods").getAsJsonObject("assignCombatDamage");
    }
    static Combat setup(C c, Card[] sources, Card[] blockers, Card walker) {
        Combat combat = ready(c, true);
        for (Card source : sources) {
            combat.addAttacker(source, walker == null ? c.other() : walker);
            for (Card blocker : blockers) combat.addBlocker(source, blocker);
            combat.setBlocked(source, blockers.length > 0);
        }
        combat.orderBlockersForDamageAssignment(); combat.orderAttackersForDamageAssignment();
        c.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, c.actor(), false);
        return combat;
    }
    static void scenario(int seat, String kind) {
        C c = context(seat);
        Card source = card(c, c.actor(), "Grizzly Bears"); source.setBasePower(4);
        Card blocker = card(c, c.other(), "Palace Guard"); blocker.setBaseToughness(3);
        boolean shared = kind.startsWith("shared"), walkerCase = kind.startsWith("walker");
        boolean trample = !Set.of("nontrample", "modern", "zero").contains(kind);
        if (trample) source.addIntrinsicKeyword(walkerCase ? "Trample:Planeswalker" : "Trample");
        if (kind.equals("deathtouch")) source.addIntrinsicKeyword("Deathtouch");
        Card second = shared ? card(c, c.actor(), "Grizzly Bears") : null;
        if (second != null) { second.setBasePower(4); second.addIntrinsicKeyword("Trample"); }
        Card secondBlocker = kind.equals("modern") ? card(c, c.other(), "Grizzly Bears") : null;
        Card walker = walkerCase ? card(c, c.other(), "Jace, the Mind Sculptor") : null;
        if (walkerCase) { source.setBasePower(9); blocker.setBaseToughness(2); }
        if (kind.equals("zero")) source.setBasePower(0);
        Combat combat = setup(c, second == null ? new Card[]{source} : new Card[]{source,second},
                secondBlocker == null ? new Card[]{blocker} : new Card[]{blocker,secondBlocker}, walker);
        int[] asks = {0};
        c.host().before = () -> {
            asks[0]++; c.host().asks = 0; // The helper's guard is per RPC; this world deliberately has two.
            var ask = c.host().ask;
            verify(ask.get("kind").getAsString().equals("assignDamage"), "only explicit damage prompt");
            verify(ask.get("combatDamageVersion").getAsString().equals("host-combat-damage-v1"), "native context version");
            int id = ask.getAsJsonObject("attacker").get("fid").getAsInt();
            JsonObject allocation;
            if (shared) allocation = map(blocker.getId(), kind.equals("shared-bad") ? 1 : id == source.getId() ? 2 : 1,
                    -1, kind.equals("shared-bad") ? 3 : id == source.getId() ? 2 : 3);
            else if (walkerCase) allocation = map(blocker.getId(), 2, walker.getId(), kind.equals("walker-bad") ? 4 : 5,
                    -1, kind.equals("walker-bad") ? 3 : 2);
            else if (kind.equals("modern")) allocation = map(blocker.getId(), 1, secondBlocker.getId(), 3);
            else if (kind.equals("deathtouch")) allocation = map(blocker.getId(), 1, -1, 3);
            else if (kind.equals("nontrample") || kind.equals("insufficient")) allocation = map(blocker.getId(), 2, -1, 2);
            else allocation = map(blocker.getId(), 3, -1, 1);
            if (kind.equals("string")) allocation.addProperty(String.valueOf(blocker.getId()), "3");
            if (kind.equals("fraction")) allocation.addProperty(String.valueOf(blocker.getId()), 3.5);
            if (kind.equals("overflow")) allocation.addProperty(String.valueOf(blocker.getId()), 4294967299L);
            if (kind.equals("negative-key")) { allocation.remove("-1"); allocation.addProperty("-2", 1); }
            if (kind.equals("unknown")) { allocation.remove(String.valueOf(blocker.getId())); allocation.addProperty("999999", 3); }
            if (kind.equals("stale")) source.setGameTimestamp(c.game().getNextTimestamp());
            if (kind.equals("changed")) blocker.setBaseToughness(4);
            c.host().payload = new JsonObject(); c.host().payload.add("assign", allocation);
            if (kind.equals("shared-repeat-defer") || kind.equals("shared-defer") && asks[0] == 1) {
                c.host().payload.remove("assign"); c.host().payload.addProperty("defer", true);
            }
            if (kind.equals("delegate")) c.host().payload.addProperty("delegate", true);
        };
        if (kind.equals("eof")) c.host().eof = true;
        boolean bad = !Set.of("normal", "shared", "shared-defer", "deathtouch", "modern", "walker", "zero").contains(kind);
        try {
            combat.assignCombatDamage(false);
            verify(!bad, "bad assignment accepted: " + kind);
            if (!kind.equals("zero")) {
                verify(coverage(c).get("host").getAsInt() == (kind.equals("shared-defer") ? 3 : shared ? 2 : 1), "ownership waits for aggregate receipt");
                verify(coverage(c).get("unclassified").getAsInt() == 0, "complete damage calls accounted");
            }
            verify(c.other().getLife() == 20, "assignment does not itself deal damage");
            combat.dealAssignedDamage();
            int expected = shared ? 15 : kind.equals("deathtouch") ? 17 : walkerCase ? 18
                    : kind.equals("normal") ? 19 : 20;
            verify(c.other().getLife() == expected, "native dealt exact defender amount " + kind);
            verify(c.session().integrityFailure(c.game()) == null, "valid game not failed");
        } catch (RuntimeException failure) {
            verify(bad, "unexpected failure " + kind + ": " + failure);
            verify(c.session().integrityFailure(c.game()) != null, "invalid game permanently latched");
            verify(c.other().getLife() == 20, "no damage dealt after rejected allocation");
            verify(coverage(c).get("host").getAsInt() == 0, "invalid aggregate never certified as host completion");
            for (Card card : c.game().getCardsIn(forge.game.zone.ZoneType.Battlefield))
                verify(card.getTotalAssignedDamage() == 0, "rejection restores assigned-damage maps");
            // Even accidental damage application after the failure must have no
            // newly assigned rows to apply. The session remains invalid regardless.
            combat.dealAssignedDamage();
            verify(c.other().getLife() == 20 && c.actor().getLife() == 20, "rejection restores native damage table");
        }
    }
    static void staleScope(int seat) {
        C c = context(seat); Card a = card(c,c.actor(),"Grizzly Bears"), b=card(c,c.other(),"Grizzly Bears");
        setup(c,new Card[]{a},new Card[]{b},null);
        try { c.controller().assignCombatDamage(a,new CardCollection(b),null,2,c.other(),true); throw new AssertionError("unscoped call accepted"); }
        catch(RuntimeException expected) { verify(c.session().integrityFailure(c.game()) != null,"unscoped call latches"); }
        verify(c.host().asks == 0,"no unscoped ask");
    }
    static void blocker(int seat, String kind) {
        C c = context(seat); Card a=card(c,c.other(),"Grizzly Bears"), b=card(c,c.actor(),"Grizzly Bears");
        final boolean nonPositive = kind.equals("zero") || kind.equals("negative");
        b.setBasePower(kind.equals("zero") ? 0 : kind.equals("negative") ? -1 : 4);
        b.addIntrinsicKeyword("Trample");
        Combat combat=ready(c,false);combat.addAttacker(a,c.actor());combat.addBlocker(a,b);combat.setBlocked(a,true);
        combat.orderBlockersForDamageAssignment();combat.orderAttackersForDamageAssignment();
        c.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE,c.other(),false);
        c.host().before=()->{
            verify(!c.host().ask.get("allowExcessToDefender").getAsBoolean(),"blocking trample has no defender permission");
            c.host().payload.add("assign",kind.equals("bad")?map(a.getId(),2,-1,2):kind.equals("zero")?map():map(a.getId(),4));
        };
        try { combat.assignCombatDamage(false);verify(!kind.equals("bad"),"blocker sentinel accepted");
            if (nonPositive) {
                verify(c.host().asks == 0,"CR 510.1a: non-positive blocker makes no allocation ask");
                verify(coverage(c) == null,"no damage invocation exists to classify");
                verify(c.session().integrityFailure(c.game()) == null,"non-positive blocker is rules-valid");
            } else {
                verify(coverage(c).get("host").getAsInt()==1,"blocking allocation completed");
            }
            verify(a.getTotalAssignedDamage()==(nonPositive?0:4),"native exact blocker assignment");
        } catch(RuntimeException failure) {
            verify(kind.equals("bad"),"unexpected blocker failure "+failure);
            verify(c.session().integrityFailure(c.game())!=null,"blocker failure latched");
            verify(a.getTotalAssignedDamage()==0 && b.getTotalAssignedDamage()==0,"blocker rejection restores both seats");
        }
    }
    static void previousWalkerDamage(int seat) {
        C c=context(seat);Card first=card(c,c.actor(),"Grizzly Bears"),second=card(c,c.actor(),"Grizzly Bears");
        first.setBasePower(1);second.setBasePower(8);second.addIntrinsicKeyword("Trample:Planeswalker");
        Card walker=card(c,c.other(),"Jace, the Mind Sculptor");
        Combat combat=setup(c,new Card[]{first,second},new Card[]{},walker);
        c.host().before=()->{
            verify(c.host().ask.getAsJsonObject("remainingLethal").get(String.valueOf(walker.getId())).getAsInt()==4,
                    "prompt counts earlier unblocked assignment to planeswalker");
            c.host().payload.add("assign",map(walker.getId(),4,-1,4));
        };
        combat.assignCombatDamage(false);combat.dealAssignedDamage();
        verify(c.other().getLife()==16,"planeswalker aggregate receives lethal before four through");
    }
    static String stock(int seat, String mode, boolean audit) {
        C c = context(seat,mode); Card a=card(c,c.actor(),"Grizzly Bears"),b=card(c,c.other(),"Grizzly Bears");
        verify(!c.game().getRules().auditsCombatDamage(c.game()), "ordinary clients default to no audit");
        if (audit) BenchMain.configureCombatDamageAudit(c.game().getRules(), c.session());
        a.setBasePower(4);a.addIntrinsicKeyword("Trample");Combat combat=setup(c,new Card[]{a},new Card[]{b},null);
        BenchRandomAudit.install(91613);combat.assignCombatDamage(false);combat.dealAssignedDamage();
        verify(c.host().asks==0,"stock control has no host asks");
        verify(c.session().integrityFailure(c.game()) == null, "legal stock allocation remains valid");
        return c.actor().getLife()+":"+c.other().getLife()+":"+a.getDamage()+":"+b.getDamage()+":"
                +((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot();
    }
    static Combat injectNativeAllocation(C c, String fault) {
        Card a=card(c,c.actor(),"Grizzly Bears"),b=card(c,c.other(),"Palace Guard");
        a.setBasePower(4); a.addIntrinsicKeyword("Trample"); b.setBaseToughness(3);
        var nativeController = new forge.ai.PlayerControllerAi(c.game(), c.actor(), c.actor().getLobbyPlayer()) {
            @Override public Map<Card,Integer> assignCombatDamage(Card source, CardCollectionView recipients,
                    CardCollectionView remaining, int amount, forge.game.GameEntity defender, boolean overrideOrder) {
                if (fault.equals("defer")) return null;
                Map<Card,Integer> result = new LinkedHashMap<>();
                result.put(b, fault.equals("total") ? 1 : 2); result.put(null, 2);
                return result;
            }
        };
        c.actor().dangerouslySetController(nativeController);
        Card[] attackers = fault.equals("defer") ? new Card[]{a,card(c,c.actor(),"Grizzly Bears")} : new Card[]{a};
        return setup(c,attackers,new Card[]{b},null);
    }
    static void stockFailure(int seat, String fault) {
        C c=context(seat,"native");
        BenchMain.configureCombatDamageAudit(c.game().getRules(),c.session());
        Combat combat=injectNativeAllocation(c,fault);
        boolean threw=false;
        try { combat.assignCombatDamage(false); }
        catch (IllegalStateException expected) { threw=true; }
        verify(threw,"illegal native allocation is rejected: "+fault);
        verify(c.session().integrityFailure(c.game()) != null,"native failure permanently latched");
        for(Card card:c.game().getCardsIn(forge.game.zone.ZoneType.Battlefield))
            verify(card.getTotalAssignedDamage()==0,"native failure restores assignments from both seats");
        combat.dealAssignedDamage();
        verify(c.actor().getLife()==20 && c.other().getLife()==20,"native failure never deals partial damage");
        // Simulate a lower layer swallowing the error and declaring either seat
        // the winner. The production result guard must reject BOTH outcomes.
        for(int winner=0;winner<2;winner++) {
            JsonObject outcome=new JsonObject();outcome.addProperty("winner",winner);
            outcome.addProperty("crashed",false);outcome.addProperty("reason","AllOpponentsLost");
            BenchMain.guardIntegrityOutcome(c.session(),c.game(),outcome);
            verify(outcome.get("crashed").getAsBoolean()
                    && outcome.get("aborted").getAsString().equals("InstrumentError"),"stock error cannot become either seat's scored win");
        }
    }
    static void searchScope(int seat) {
        C live=context(seat,"native"), copy=context(seat,"native");
        BenchMain.configureCombatDamageAudit(live.game().getRules(),live.session());
        copy.game().getRules().setCombatDamageAudit(live.game().getRules().getCombatDamageAudit());
        verify(!copy.game().getRules().auditsCombatDamage(copy.game()),"shared observer excludes a foreign/search game");
        // Native behavior is intentionally unmodified outside the live game.
        injectNativeAllocation(copy,"lethal").assignCombatDamage(false);
        copy.game().getRules().getCombatDamageAudit().failed(copy.game(),new IllegalStateException("foreign failure"));
        verify(live.session().integrityFailure(live.game())==null,"foreign game cannot poison the live result");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()) {
                case "getAssetsDir"->args[0]+"/forge-gui/";
                case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                case "getCurrentVersion"->"combat-damage-fixture";
                default->throw new AssertionError("unexpected GUI "+m.getName());
            }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            for(int seat=0;seat<2;seat++) {
                for(String kind:List.of("normal","nontrample","insufficient","shared","shared-bad","shared-defer","shared-repeat-defer","modern","deathtouch",
                        "walker","walker-bad","zero","string","fraction","overflow","negative-key","unknown","stale","changed","delegate","eof")) scenario(seat,kind);
                staleScope(seat);
                for(String kind:List.of("normal","bad","zero","negative")) blocker(seat,kind);
                previousWalkerDamage(seat);
                String baseline=stock(seat,"native",false);
                for(String mode:List.of("native","NULL")) for(boolean audit:List.of(false,true))
                    verify(baseline.equals(stock(seat,mode,audit)),"native/null audit on/off exact damage and RNG transcript control");
                for(String fault:List.of("total","lethal","defer")) stockFailure(seat,fault);
                searchScope(seat);
            }
            System.out.println("PASS "+checks+" combat damage execution checks; NOT CERTIFIED for strength");System.exit(0);
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
