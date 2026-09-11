package forge.bench;

import forge.StaticData;
import forge.ai.LobbyPlayerCubeComboAi;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.*;

/** Development execution probe with explicit Default / CubeCombo native AI.
 * Direct execution mode supplies the board and resolves the stack, never AI
 * targets or choices. Native-turn mode delegates priority, triggers and combat
 * to PhaseHandler.mainLoopStep and requires a real terminal win. These selected
 * assembled positions are NOT an opening-to-terminal full-game winrate test. */
public final class CubeComboExecutionSmoke {
    private static boolean improved;
    private static boolean nativeTurn;
    private static String control = "none";
    private static String assembly = "none";
    private static final java.util.Set<String> loaded = new java.util.HashSet<>();
    private static String selectionSpell="Ponder";
    private static Card card(String name, Player p, ZoneType zone) {
        if (loaded.add(name)) StaticData.instance().attemptToLoadCard(name);
        Card c = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), name), p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(zone).add(c);
        c.setSickness(false);
        return c;
    }

    private static void run(int seat, String partner, boolean twin, boolean decoy) {
        var players = new ArrayList<RegisteredPlayer>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(new Deck())
                .setPlayer(improved && s == seat ? new LobbyPlayerCubeComboAi("CubeCombo-" + s)
                        : GamePlayerUtil.createAiPlayer("Default-" + s, s, 0, null, "Default")));
        var rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        var game = new Match(rules, players, "Combo execution probe").createGame();
        Player p = game.getPlayers().get(seat);
        game.setAge(GameStage.Play);
        if (!nativeTurn) game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        for (Player player : game.getPlayers()) {
            for (int i = 0; i < 10; i++) card("Island", player, ZoneType.Library);
        }
        Card piece = card(partner, p, assembly.equals("tutor") ? ZoneType.Library
                : assembly.equals("cast") ? ZoneType.Hand : ZoneType.Battlefield);
        if (!assembly.equals("none")) {
            for (int i = 0; i < 3; i++) card("Island", p, ZoneType.Battlefield);
            if (assembly.equals("tutor")) {
                card("Demonic Tutor", p, ZoneType.Hand);
                card("Grave Titan", p, ZoneType.Library);
                for (int i = 0; i < 2; i++) card("Swamp", p, ZoneType.Battlefield);
            }
        }
        Card source;
        if (twin) {
            Card aura = card("Splinter Twin", p, ZoneType.Battlefield);
            aura.attachToEntity(piece, null);
            source = piece;
        } else source = card("Kiki-Jiki, Mirror Breaker", p, ZoneType.Battlefield);
        if (decoy) card("Grave Titan", p, ZoneType.Battlefield);
        if (control.equals("torpor")) card("Torpor Orb", p, ZoneType.Battlefield);
        if (control.equals("totem")) card("Cursed Totem", p, ZoneType.Battlefield);
        if (control.equals("shroud")) card("Lightning Greaves", p, ZoneType.Battlefield).attachToEntity(piece, null);
        card("Grizzly Bears", game.getPlayers().get(1 - seat), ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(49100 + seat);
        if (nativeTurn) {
            game.getPhaseHandler().setupFirstTurn(p, () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p));
            int steps = 0;
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (!game.isGameOver() && game.getPhaseHandler().getTurn() == 1) {
                if (++steps > 1000 || System.nanoTime() > deadline) throw new AssertionError("Native turn budget exceeded");
                game.getPhaseHandler().mainLoopStep();
                if (Boolean.getBoolean("forge.combo.debug")) System.out.println("NATIVE_STEP n=" + steps + " phase=" + game.getPhaseHandler().getPhase()
                        + " priority=" + game.getPhaseHandler().getPriorityPlayer() + " stack=" + game.getStack()
                        + " board=" + p.getCardsIn(ZoneType.Battlefield).stream()
                        .map(c -> c.getName() + ":" + c.isTapped()).toList());
            }
            boolean won = game.isGameOver() && p.hasWon();
            if (!assembly.equals("none")) System.out.println("ASSEMBLY_END stage=" + assembly
                    + " hand=" + p.getCardsIn(ZoneType.Hand) + " graveyard=" + p.getCardsIn(ZoneType.Graveyard)
                    + " battlefield=" + p.getCardsIn(ZoneType.Battlefield));
            System.out.println("NATIVE_RESULT improved=" + improved + " seat=" + seat + " partner=" + partner
                    + " twin=" + twin + " decoy=" + decoy + " control=" + control + " assembly=" + assembly + " firstTurnWon=" + won + " steps=" + steps
                    + " opponentLife=" + game.getRegisteredPlayers().get(1 - seat).getLife());
            if (improved && control.equals("none") && !won) throw new AssertionError("Assembled combo did not win native turn");
            if (!control.equals("none") && won) throw new AssertionError("Negative control unexpectedly won");
            return;
        }
        int played = 0;
        for (; played < 70; played++) {
            var choices = p.getController().chooseSpellAbilityToPlay();
            if (choices == null || choices.isEmpty()) break;
            if (choices.size() != 1) throw new AssertionError("Unexpected multiple action batch");
            var action = choices.get(0);
            if (improved && !action.canPlay()) throw new AssertionError("AI selected an illegal activation");
            if (improved && !twin && action.getTargets().getTargetCards().stream()
                    .anyMatch(c -> !c.getName().equals(partner))) throw new AssertionError("AI copied the decoy");
            System.out.println("DECISION seat=" + seat + " partner=" + partner + " twin=" + twin
                    + " decoy=" + decoy + " step=" + played + " action=" + action + " targets=" + action.getTargets());
            if (!p.getController().playChosenSpellAbility(action)) throw new AssertionError("Native play rejected");
            int depth = 0;
            do {
                game.getStack().addAllTriggeredAbilitiesToStack();
                if (!game.getStack().isEmpty()) game.getStack().resolveStack();
                game.getAction().checkStateEffects(true);
                if (++depth > 64) throw new AssertionError("Stack did not settle");
            } while (!game.getStack().isEmpty() || game.getStack().hasSimultaneousStackEntries());
        }
        long tokens = p.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.isToken() && c.getName().equals(partner)).count();
        long expected = partner.equals("Pestermite") ? 13 : 23;
        if (improved && (tokens != expected || played >= 70 || source.isTapped())) {
            throw new AssertionError("Improved combo failed to execute and terminate: tokens=" + tokens + " actions=" + played);
        }
        System.out.println("RESULT improved=" + improved + " seat=" + seat + " partner=" + partner + " twin=" + twin + " decoy=" + decoy
                + " actions=" + played + " partnerTokens=" + tokens + " sourceTapped=" + source.isTapped());
    }

    /** The selection spell and every subsequent decision run through native AI.
     * Vary the revealed order independently of seed; no host-selected cards. */
    private static void selection(int seat, String body, int rotation, int seed, String variant) {
        String handPartner=variant.equals("expensive-hand")?"Restoration Angel":variant.equals("castable-hand")
                ?(body.equals("Pestermite")?"Deceiver Exarch":"Pestermite"):null;
        boolean tutor=selectionSpell.equals("Demonic Tutor");
        boolean multiple=variant.startsWith("multiple-");
        List<String> own = new ArrayList<>(List.of("Kiki-Jiki, Mirror Breaker", selectionSpell, body, "Grave Titan"));
        if(multiple){own.add(2,"Restoration Angel");own.add("Plains");}
        if(handPartner!=null)own.add(handPartner);
        if(tutor){own.add("Swamp");own.add("Swamp");}
        while(own.size()<40)own.add("Island");
        List<String> other = new ArrayList<>(List.of("Grizzly Bears"));
        String hate=variant.equals("taxed")?"Sphere of Resistance":variant.equals("totem")?"Cursed Totem"
                :variant.equals("cast-limit")?"Rule of Law":variant.equals("search-limit")?"Aven Mindcensor":null;
        if(hate!=null) other.add(hate);
        while(other.size()<40) other.add("Forest");
        var players = new ArrayList<RegisteredPlayer>();
        for(int s=0;s<2;s++) {
            Deck deck=new Deck();
            for(String name:s==seat?own:other) {
                if(loaded.add(name)) StaticData.instance().attemptToLoadCard(name);
                deck.getMain().add(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name),name));
            }
            players.add(new RegisteredPlayer(deck).setPlayer(improved&&s==seat
                    ?new LobbyPlayerCubeComboAi("CubeCombo-"+s)
                    :GamePlayerUtil.createAiPlayer("Default-"+s,s,0,null,"Default")));
        }
        var rules=new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        var game=new Match(rules,players,"Revealed combo selection").createGame();
        Player p=game.getPlayers().get(seat),opponent=game.getPlayers().get(1-seat);
        game.setAge(GameStage.Play);
        card("Kiki-Jiki, Mirror Breaker",p,variant.equals("missing-engine")?ZoneType.Exile:ZoneType.Battlefield);
        card(selectionSpell,p,ZoneType.Hand);
        if(handPartner!=null)card(handPartner,p,ZoneType.Hand);
        List<String> top=new ArrayList<>(List.of(body,"Grave Titan","Island"));
        boolean outsideLook=variant.equals("hidden-partner")||variant.equals("search-limit");
        if(outsideLook||variant.equals("exiled-partner"))top.set(0,"Island");
        Collections.rotate(top,rotation);
        for(String name:top) card(name,p,ZoneType.Library);
        int mana=variant.equals("unaffordable")||variant.equals("multiple-short")?1
                :variant.equals("multiple-tight")?3:multiple?4:tutor?3:4;
        for(int i=0;i<mana;i++) card("Island",p,ZoneType.Battlefield);
        if(tutor)for(int i=0;i<2;i++)card("Swamp",p,ZoneType.Battlefield);
        if(multiple)card("Plains",p,ZoneType.Battlefield);
        for(int i=0;i<35-mana-(tutor?2:0)-(handPartner!=null?1:0)
                -((outsideLook||variant.equals("exiled-partner"))?1:0)-(multiple?2:0);i++)card("Island",p,ZoneType.Library);
        if(multiple)card("Restoration Angel",p,ZoneType.Library);
        if(outsideLook)card(body,p,ZoneType.Library);
        if(variant.equals("exiled-partner"))card(body,p,ZoneType.Exile);
        card("Grizzly Bears",opponent,ZoneType.Battlefield);
        if(hate!=null)card(hate,opponent,ZoneType.Battlefield);
        for(int i=0;i<(hate==null?39:38);i++)card("Forest",opponent,ZoneType.Library);
        game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(seed);
        game.getPhaseHandler().setupFirstTurn(p,()->game.getPhaseHandler().devModeSet(PhaseType.MAIN1,p));
        int steps=0;Set<Integer> seen=new HashSet<>();List<String> casts=new ArrayList<>();
        List<String> castPhases=new ArrayList<>();
        long deadline=System.nanoTime()+30_000_000_000L;
        while(!game.isGameOver()&&game.getPhaseHandler().getTurn()==1) {
            if(++steps>1000||System.nanoTime()>deadline)throw new AssertionError("Selection turn budget");
            game.getPhaseHandler().mainLoopStep();
            for(var item:game.getStack()) {
                var sa=item.getSpellAbility();
                if(sa.getActivatingPlayer()==p&&sa.isSpell()&&seen.add(item.getId())) {
                    casts.add(sa.getHostCard().getName());
                    castPhases.add(sa.getHostCard().getName()+"@"+game.getPhaseHandler().getPhase());
                }
            }
        }
        boolean won=game.isGameOver()&&p.hasWon();
        int changed=p.getController() instanceof forge.ai.CubeComboPlayerController c?c.getComboSelectionChanges():0;
        int tutorCasts=p.getController() instanceof forge.ai.CubeComboPlayerController c?c.getComboTutorPlanCasts():0;
        System.out.println("SELECTION_RESULT improved="+improved+" source="+selectionSpell.replace(' ','_')+" seat="+seat+" body="+body+" rotation="+rotation
                +" seed="+seed+" control="+variant+" won="+won+" changed="+changed+" casts="+casts+" hand="+p.getCardsIn(ZoneType.Hand)
                +" castPhases="+castPhases+" tutorPlanCasts="+tutorCasts);
        boolean needSelection=variant.equals("none")||variant.equals("expensive-hand")
                ||multiple&&!variant.equals("multiple-short");
        boolean canFinish=needSelection||variant.equals("castable-hand");
        if(improved&&canFinish&&(!won||(needSelection&&!casts.contains(body))
                ||changed!=(needSelection&&!tutor?1:0)))throw new AssertionError("Revealed affordable partner not converted to native win");
        if(!canFinish&&won)throw new AssertionError("Selection negative control won");
        if(!needSelection&&changed!=0)throw new AssertionError("Selection must-not-intervene control changed");
        if(!canFinish&&tutorCasts!=0)throw new AssertionError("Tutor must-not-intervene control started a plan");
    }

    public static void main(String[] args) {
        try {
            improved = args.length > 1 && args[1].equals("improved");
            nativeTurn = args.length > 2 && args[2].equals("native-turn");
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                    new Class<?>[]{IGuiBase.class}, (p, m, v) -> switch (m.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "cube-combo-execution-probe-v1";
                        default -> throw new AssertionError(m.getName());
                    }));
            FModel.initialize(null, p -> {
                p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                p.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            String requestedSuite = args.length > 3 ? args[3] : "positives";
            if(requestedSuite.equals("tutor-multiple")) {
                selectionSpell="Demonic Tutor";
                for(int seat=0;seat<2;seat++)for(String body:List.of("Pestermite","Deceiver Exarch"))
                    for(int rotation=0;rotation<3;rotation++)for(int seed:List.of(917,1831,4057))
                        for(String variant:List.of("multiple-partners","multiple-tight","multiple-short"))
                            selection(seat,body,rotation,seed,variant);
                System.out.println("TUTOR_MULTIPLE_SUITE_COMPLETE");System.exit(0);
            }
            if(requestedSuite.equals("tutor-controls")) {
                selectionSpell="Demonic Tutor";
                for(int seat=0;seat<2;seat++)for(String body:List.of("Pestermite","Deceiver Exarch"))
                    for(int seed:List.of(917,1831,4057))
                        for(String variant:List.of("missing-engine","unaffordable","taxed","totem","cast-limit","search-limit","exiled-partner"))
                            selection(seat,body,0,seed,variant);
                System.out.println("TUTOR_CONTROLS_SUITE_COMPLETE");System.exit(0);
            }
            if(requestedSuite.equals("selection-hand")) {
                for(int seat=0;seat<2;seat++)for(String spell:List.of("Ponder","Demonic Tutor"))
                    for(String body:List.of("Pestermite","Deceiver Exarch"))for(String variant:List.of("expensive-hand","castable-hand","none")) {
                        selectionSpell=spell; selection(seat,body,0,917,variant);
                    }
                System.out.println("SELECTION_HAND_SUITE_COMPLETE");System.exit(0);
            }
            if(requestedSuite.equals("selection")) {
                for(int seat=0;seat<2;seat++)for(String body:List.of("Pestermite","Deceiver Exarch"))
                    for(int rotation=0;rotation<3;rotation++)for(int seed:List.of(917,1831,4057))
                        for(String variant:List.of("none","missing-engine","unaffordable","taxed","totem","hidden-partner"))
                            selection(seat,body,rotation,seed,variant);
                System.out.println("SELECTION_SUITE_COMPLETE"); System.exit(0);
            }
            for (String suite : requestedSuite.equals("all") ? List.of("positives", "extended", "assembly", "controls") : List.of(requestedSuite)) {
            assembly = "none";
            control = "none";
            if (suite.equals("assembly")) {
                for (int seat = 0; seat < 2; seat++) for (String stage : List.of("cast", "tutor")) {
                    assembly = stage;
                    run(seat, "Pestermite", false, false);
                }
            } else if (suite.equals("controls")) {
                for (int seat = 0; seat < 2; seat++) {
                    for (String name : List.of("torpor", "totem", "shroud", "missing-piece")) {
                        control = name;
                        run(seat, name.equals("missing-piece") ? "Grizzly Bears" : "Pestermite", false, false);
                    }
                }
            } else for (int seat = 0; seat < 2; seat++)
                for (String partner : suite.equals("extended")
                        ? List.of("Restoration Angel", "Zealous Conscripts") : List.of("Pestermite", "Deceiver Exarch"))
                    for (boolean twin : List.of(false, true))
                        for (boolean decoy : List.of(false, true)) {
                            if (twin && partner.equals("Restoration Angel")) continue;
                            run(seat, partner, twin, decoy);
                        }
            }
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
