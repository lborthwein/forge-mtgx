package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.*;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityContinuous;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

/** Registered Adeline/Valki failure, native enumeration comparison, and exact
 * self/LKI scope controls. No game outcome or policy-strength assertion. */
public final class ProspectiveCdaScopeSmoke {
    private static int checks;
    private static void check(boolean ok,String why) { if(!ok)throw new AssertionError(why);checks++; }
    private static Card card(String name,Player p,ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        if(zone==ZoneType.Stack)p.getGame().getStackZone().add(c);else p.getZone(zone).add(c);
        c.setSickness(false);return c;
    }
    private static String semantic(SpellAbility a) {
        return a.getHostCard().getId()+"|"+a.getApi()+"|"+a.getCardStateName()+"|"+a.getPayCosts().toSimpleString()+"|"+a.isSpell()+"|"+a.canPlay();
    }
    private static void guard(Card prospective,boolean reject) throws Exception {
        var method=GameActionUtil.class.getDeclaredMethod("verifyProspectiveEnumerationHost",Card.class);method.setAccessible(true);
        try {
            method.invoke(null,prospective);
            check(!reject,"affected characteristic layer incorrectly admitted");
        } catch(InvocationTargetException failure) {
            check(reject && failure.getCause() instanceof IllegalStateException
                && failure.getCause().getMessage().contains("prospective face requires characteristic-layer simulation"),
                "unexpected prospective guard failure: "+failure.getCause());
        }
    }
    private static void run(int seat,ZoneType cdaZone,boolean probe) throws Exception {
        var players=List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("A",0,0,null,"Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("B",1,0,null,"Default")));
        var g=new Match(new GameRules(GameType.Constructed),players,"CDA scope").createGame();
        g.setAge(GameStage.Play);var actor=g.getPlayers().get(seat);var opponent=g.getPlayers().get(1-seat);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        var valki=card("Valki, God of Lies",opponent,ZoneType.Battlefield);
        var adeline=card("Adeline, Resplendent Cathar",actor,cdaZone);
        var cast=card("Palantír of Orthanc",actor,ZoneType.Hand);
        g.getAction().checkStaticAbilities();BenchRandomAudit.install(71953);
        var cda=adeline.getStaticAbilities().stream().filter(s->s.isCharacteristicDefining()).findFirst().orElseThrow();
        check(cda.checkConditions(forge.game.staticability.StaticAbilityMode.Continuous),"actual Adeline CDA active in "+cdaZone);
        var affected=StaticAbilityContinuous.getAffectedCards(cda,new CardCollection(valki));
        check(affected.size()==1 && affected.get(0).getId()==adeline.getId(),"native CDA affects only Adeline even with another pre-list card");
        // Independent normal API may write actors and allocate IDs. Those
        // expected writes occur BEFORE the audit purity snapshot.
        var all=List.of(valki,cast);
        var expected=new ArrayList<SpellAbility>();
        for(var c:all)expected.addAll(c.getAllPossibleAbilities(actor,false));
        var before=BenchMenuStateAudit.capture(g);var random=BenchRandomAudit.begin();
        var actual=BenchmarkAbilityEnumeration.spells(all,actor);
        check(expected.stream().map(ProspectiveCdaScopeSmoke::semantic).sorted().toList()
            .equals(actual.stream().map(ProspectiveCdaScopeSmoke::semantic).sorted().toList()),
            "read-only variants equal native enumeration with unrelated CDA");
        check(actual.stream().anyMatch(a->a.getHostCard().getId()==cast.getId()),"legal ordinary spell not dropped to avoid guard");
        BenchMenuStateAudit.assertUnchanged(before,g);BenchRandomAudit.assertUnchanged(random,"CDA-scoped enumeration");checks+=2;
        if(probe)return;
        var prospective=CardCopyService.getLKICopy(adeline);
        check(prospective!=adeline && prospective.getId()==adeline.getId(),"LKI has different reference but same physical identity");
        guard(prospective,true); // same-card CDA must not be ignored, even on stack
        cda.getMapParams().put("ExcludeZone",cdaZone.name());
        check(StaticAbilityContinuous.getAffectedCards(cda,CardCollection.EMPTY).isEmpty(),"native excluded-zone CDA affects nothing");
        guard(prospective,false);
        cda.getMapParams().remove("ExcludeZone");
        // An active non-CDA characteristic effect is still unresolved, even
        // when a separate unrelated CDA may now safely be ignored.
        valki.addStaticAbility("Mode$ Continuous | Affected$ Card | AffectedZone$ Battlefield,Hand,Stack,Exile | AddType$ Artifact");
        guard(CardCopyService.getLKICopy(cast),true);
        System.out.println("CDA_SCOPE seat="+seat+" zone="+cdaZone+" candidates="+actual.size());
    }

    private static void battlefieldAlternates(int seat, boolean ownPermanent, String name) throws Exception {
        var players=List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("A",0,0,null,"Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("B",1,0,null,"Default")));
        var g=new Match(new GameRules(GameType.Constructed),players,"Battlefield spell scope").createGame();
        g.setAge(GameStage.Play);var actor=g.getPlayers().get(seat);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        var permanent=card(name,g.getPlayers().get(ownPermanent?seat:1-seat),ZoneType.Battlefield);
        var adversary=card("Intrepid Adversary",actor,ZoneType.Battlefield);
        var monolith=card("Grim Monolith",actor,ZoneType.Battlefield);
        var hand=card("Valki, God of Lies",actor,ZoneType.Hand);
        var handLand=card("Forest",actor,ZoneType.Hand);
        var battlefieldLand=card("Mountain",actor,ZoneType.Battlefield);
        permanent.addStaticAbility("Mode$ OptionalCost | EffectZone$ All | ValidCard$ Card.Self | ValidSA$ Spell | Cost$ 1");
        g.getAction().checkStaticAbilities();BenchRandomAudit.install(71954);
        var printed=permanent.getFirstSpellAbility();printed.setActivatingPlayer(actor);
        check(!GameActionUtil.getOptionalCostValues(printed).isEmpty(),"optional cost genuinely present on battlefield spell");
        check(!printed.canPlay(true),"native optional cost cannot legalize battlefield spell");
        var all=List.of(permanent,monolith,hand,handLand,battlefieldLand);
        var expected=new ArrayList<SpellAbility>();
        for(var c:all)expected.addAll(c.getAllPossibleAbilities(actor,false));
        var landAbility=new forge.game.spellability.LandAbility(battlefieldLand,battlefieldLand.getCurrentState());
        landAbility.setActivatingPlayer(actor);
        var before=BenchMenuStateAudit.capture(g);var random=BenchRandomAudit.begin();
        var actual=BenchmarkAbilityEnumeration.spells(all,actor);
        check(expected.stream().map(ProspectiveCdaScopeSmoke::semantic).sorted().toList()
            .equals(actual.stream().map(ProspectiveCdaScopeSmoke::semantic).sorted().toList()),
            "battlefield spell rejection preserves native candidate multiset");
        check(actual.stream().noneMatch(a->a.isSpell() && a.getHostCard().isInPlay()),"no battlefield spell admitted");
        check(actual.stream().anyMatch(a->a.getHostCard().getId()==monolith.getId() && a.isActivatedAbility()
            && !a.isManaAbility()),"paid Monolith untap retained");
        check(actual.stream().filter(a->a.getHostCard().getId()==hand.getId() && a.isSpell()).count()==2,
            "both legal hand faces retained");
        check(actual.stream().anyMatch(a->a.getHostCard().getId()==handLand.getId() && a.isLandAbility()),
            "legal land play retained independently of Spell guard");
        check(landAbility.canPlayForEnumeration()==landAbility.canPlay(true),"battlefield LandAbility retains native behavior");
        BenchMenuStateAudit.assertUnchanged(before,g);BenchRandomAudit.assertUnchanged(random,"battlefield spell rejection");checks+=2;
        // Legal prospective faces still fail closed when their characteristics
        // require unsupported layer simulation. This is not an Affected bypass.
        adversary.addStaticAbility("Mode$ Continuous | Affected$ Card | AffectedZone$ Hand | AddType$ Artifact");
        guard(CardCopyService.getLKICopy(hand),true);
        System.out.println("BATTLEFIELD_SCOPE seat="+seat+" own="+ownPermanent+" card="+name);
    }
    /** A grant of PERMISSION is not a characteristic change. Shelldock Isle's
     * hideaway effect is a Continuous static whose only verb is MayLookAt, and
     * it used to refuse every prospective face in the game for lacking MayPlay
     * -- two of the twenty monoU games. Admitting it is scoped by the SAME
     * param whitelist MayPlay is scoped by, which is what this case pins:
     * permission alone is admitted, permission plus any characteristic verb is
     * still refused. */
    private static void permissionOnlyScope(int seat) throws Exception {
        for (String grant : List.of("MayLookAt$ EffectSourceController", "MayPlay$ True")) {
            var g = fixture(seat);
            var actor = g.getPlayers().get(seat);
            var hidden = card("Narset, Parter of Veils", actor, ZoneType.Exile);
            var effect = card("Island", actor, ZoneType.Command);
            effect.addStaticAbility("Mode$ Continuous | Affected$ Card.IsRemembered | AffectedZone$ Exile"
                    + " | EffectZone$ Command | " + grant + " | Description$ permission only");
            g.getAction().checkStaticAbilities();
            guard(hidden, false);
        }
        // Controls: the same permission carrying a characteristic verb, and a
        // static with no permission verb at all, both still refuse.
        for (String extra : List.of(" | AddPower$ 3", "")) {
            var g = fixture(seat);
            var actor = g.getPlayers().get(seat);
            var hidden = card("Narset, Parter of Veils", actor, ZoneType.Exile);
            var effect = card("Island", actor, ZoneType.Command);
            effect.addStaticAbility("Mode$ Continuous | Affected$ Card.IsRemembered | AffectedZone$ Exile"
                    + " | EffectZone$ Command" + (extra.isEmpty() ? "" : " | MayLookAt$ EffectSourceController")
                    + extra + " | Description$ control");
            g.getAction().checkStaticAbilities();
            guard(hidden, true);
        }
    }
    private static Game fixture(int seat) {
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("A",0,0,null,"Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("B",1,0,null,"Default")));
        var g = new Match(new GameRules(GameType.Constructed), players, "permission scope").createGame();
        g.setAge(GameStage.Play);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, g.getPlayers().get(seat));
        return g;
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},
                (p,m,v)->switch(m.getName()) {
                    case "getAssetsDir"->args[0]+"/forge-gui/";
                    case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                    case "getCurrentVersion"->"cda-scope-fixture";
                    default->throw new AssertionError("unexpected GUI: "+m.getName());
                }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            if(args.length>1 && args[1].equals("probe"))run(1,ZoneType.Stack,true);
            else if(args.length>1 && args[1].equals("battlefield-probe"))battlefieldAlternates(1,false,"Valki, God of Lies");
            else {
                for(int seat=0;seat<2;seat++)for(var zone:List.of(ZoneType.Stack,ZoneType.Battlefield))run(seat,zone,false);
                for(int seat=0;seat<2;seat++)for(boolean own:List.of(false,true))
                    for(String name:List.of("Valki, God of Lies","Embereth Shieldbreaker"))battlefieldAlternates(seat,own,name);
                for(int seat=0;seat<2;seat++)permissionOnlyScope(seat);
            }
            System.out.println("PASS "+checks+" prospective CDA scope checks; NOT CERTIFIED");System.exit(0);
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
