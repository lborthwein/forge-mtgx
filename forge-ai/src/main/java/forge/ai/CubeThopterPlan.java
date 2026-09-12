package forge.ai;

import forge.StaticData;
import forge.card.CardEdition;
import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CardCopyService;
import forge.game.card.CardCollection;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTapType;
import forge.game.cost.PaymentDecision;
import forge.game.player.Player;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityDisableTriggers;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;
import forge.item.PaperToken;

/** Finite army-building plan, not an abstract infinite-combo shortcut or a
 * forced-win proof. Urza taps the recurring Sword, then Foundry spends that
 * mana and sacrifices Sword. Native triggers return/attach it. Every step
 * rechecks current legality and every resolution must make real progress. */
public final class CubeThopterPlan {
    private static final String URZA="Urza, Lord High Artificer", FOUNDRY="Thopter Foundry", SWORD="Sword of the Meek";
    private static final String TOKEN="u_1_1_a_thopter_flying";
    private final Player player;
    private int turn=-1, actions, failedTurn=-1, beforeTokens, beforeLife;
    private SpellAbility selected, pending;
    private Card paymentSword, paymentTap;
    /** Native sources forecast for the second assembly cast; held out of the
     * first cast's actual payment exactly as planTutor holds a tutor's reserve. */
    private CardCollection assemblyReserve;
    /** The one selection {@link #assemblyReserve} was forecast for. play()
     * applies the reserve only to this exact SpellAbility, so no later
     * selection can ever be paid under another cast's reservation. */
    private SpellAbility assemblyReserveFor;
    /** Diagnostic only, never read by any decision: how many hand-assembly
     * selections this policy has made, split by forecast label. The fixture
     * reads and resets these reflectively so one test source works against
     * either this policy build or an older matched-control build. */
    static int assemblySelections, assemblySameTurnForecasts, assemblyPartialForecasts;

    /** Observability only: the token for the check that already declined the
     * most recent {@link #nextAction}. Never read by a decision, exactly like
     * {@link CubeDoomsdayPlan#declineReason}.
     *
     * <p>Grammar, one token per (turn, phase): {@code phase},
     * {@code stack-not-empty}, {@code cant-win}, {@code action-cap},
     * {@code failed-this-turn}, {@code stopped-no-progress},
     * {@code missing=<our own missing piece(s)>},
     * {@code no-castable:<our own missing piece>}, {@code pool-not-empty},
     * {@code kiki-route-preferred}, {@code assembly-unsupported},
     * {@code assembly-unaffordable}, {@code sword-not-artifact},
     * {@code token-budget}, {@code engine-unusable}, {@code cost-mismatch},
     * {@code no-tap-payment} or {@code unpayable:engine}. Every piece name is
     * a card absent from OUR OWN battlefield or present in OUR OWN hand; no
     * token names an opponent zone.</p> */
    private String decline="other check=thopter-plan";
    public String declineReason() { return decline; }
    private SpellAbility decline(String reason) { decline=reason; return null; }
    /** Names the first true clause of the opening guard, re-reading only the
     * same pure getters in the same order, after that guard has already
     * decided to decline. */
    private String gateReason() {
        if(failedTurn==turn) return "failed-this-turn";
        if(actions>=160) return "action-cap";
        if(!player.getGame().getStack().isEmpty()) return "stack-not-empty";
        return "cant-win";
    }
    /** Our own missing pieces as one log token, from our own battlefield. */
    private static String missingToken(java.util.List<String> missing) {
        StringBuilder names=new StringBuilder();
        for(String name:missing) { if(names.length()>0) names.append(';'); names.append(name.replace(' ','_')); }
        return names.toString();
    }

    public CubeThopterPlan(Player player) { this.player=player; }

    /** Record a new selection. Any reserve forecast for a previous selection
     * is invalidated here, so play() can never apply a stale reservation. */
    private SpellAbility select(SpellAbility sa) {
        selected=sa; actions++; assemblyReserve=null; assemblyReserveFor=null;
        return sa;
    }

    private Card find(String name) {
        for(Card card:player.getCardsIn(ZoneType.Battlefield))
            if(!card.isFaceDown() && name.equals(card.getName()))return card;
        return null;
    }
    private SpellAbility ability(Card card, ApiType api) {
        if(card!=null)for(SpellAbility original:card.getSpellAbilities())
            if(original.getApi()==api)return original.copy(player);
        return null;
    }
    private int tokens() {
        return (int)player.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c->c.isToken() && c.getType().hasSubtype("Thopter") && c.getNetPower()>0).count();
    }

    /** Use native token rules without allocating a live card ID, choosing
     * random artwork, or modifying the game's token-edition pin cache. */
    private Card preview() {
        var rules=StaticData.instance().getAllTokens().getRules().get(TOKEN);
        if(rules==null)return null;
        PaperToken paper=new PaperToken(rules,CardEdition.UNKNOWN,TOKEN,"","") {
            @Override public String getImageKey(boolean alternate) { return getImageKey(0); }
        };
        Card card=CardFactory.getCard(paper,player,-1,player.getGame());
        card.setLastKnownZone(player.getZone(ZoneType.Battlefield));
        ComputerUtilCard.applyStaticContPT(player.getGame(),card,null);
        return card;
    }

    private boolean recursionAvailable(Card sword) {
        Card token=preview();
        if(token==null || token.getNetPower()!=1 || token.getNetToughness()!=1)return false;
        var event=AbilityKey.mapFromCard(token);
        event.put(AbilityKey.Origin,"None"); event.put(AbilityKey.Destination,"Battlefield");
        for(var trigger:sword.getTriggers()) {
            if(trigger.isSuppressed() || !"Graveyard".equals(trigger.getParam("TriggerZones"))
                    || !"Battlefield".equals(trigger.getParam("Destination")))continue;
            boolean disabled=false;
            for(Card source:player.getGame().getCardsIn(java.util.List.of(ZoneType.Battlefield,ZoneType.Command))) {
                if(source.isFaceDown())continue;
                for(var st:source.getStaticAbilities())
                    if(st.checkConditions(StaticAbilityMode.DisableTriggers)
                            && StaticAbilityDisableTriggers.isDisabled(st,trigger,event))disabled=true;
                // A public graveyard replacement invalidates the recurring
                // resource. Fail closed for matching replacements rather than
                // assume their optional or conditional result is favourable.
                for(var re:source.getReplacementEffects())
                    if(!re.isSuppressed() && "Moved".equals(re.getParam("Event"))
                            && "Graveyard".equals(re.getParam("Destination"))
                            && re.zonesCheck(source.getZone()) && re.requirementsCheck(player.getGame())
                            && re.matchesValidParam("ValidCard",sword))return false;
            }
            if(!disabled)return true;
        }
        return false;
    }

    /** Missing pieces, from our current visible battlefield, in a fixed
     * enumeration order that is not a play order. */
    static java.util.List<String> missingPieces(Player player) {
        CubeThopterPlan plan=new CubeThopterPlan(player);
        java.util.List<String> missing=new java.util.ArrayList<>();
        for(String name:java.util.List.of(URZA,FOUNDRY,SWORD))if(plan.find(name)==null)missing.add(name);
        return missing;
    }

    /** Exactly one missing piece, from our current visible battlefield. */
    static String missingPiece(Player player) {
        java.util.List<String> missing=missingPieces(player);
        return missing.size()==1?missing.get(0):null;
    }

    /** Negative-ID CardFactory previews omit intrinsic scripts. Restore just
     * the printed variables, abilities and triggers this forecast inspects,
     * from our registered card's public rules, using native parsers. Not
     * restored: replacement effects, static abilities and intrinsic keywords
     * (the preview Sword has no Equip); no check here may read those. This is
     * still detached: never insert it into a zone or execute its abilities.
     * Returns false, declining the forecast, for anything that is not a
     * detached preview of one of the three pieces. */
    static boolean addAssemblyPreviewRules(Card card,forge.item.PaperCard paper) {
        if(card.getId()!=-1 || !java.util.List.of(URZA,FOUNDRY,SWORD).contains(card.getName()))return false;
        var face=paper.getRules().getMainPart();
        for(var variable:face.getVariables())card.setSVar(variable.getKey(),variable.getValue());
        for(String trigger:face.getTriggers())card.addTrigger(forge.game.trigger.TriggerHandler.parseTrigger(trigger,card,true,card.getCurrentState()));
        forge.game.card.CardFactoryUtil.addAbilityFactoryAbilities(card,face.getAbilities());
        return true;
    }

    /** The selection caller supplies only the cards the effect legally reveals.
     * This method never enumerates library contents or their order. */
    static Card chooseAssemblyCard(Player player,CardCollection legalChoices) {
        var phase=player.getGame().getPhaseHandler();
        if(!(phase.is(PhaseType.MAIN1,player)||phase.is(PhaseType.MAIN2,player))||player.cantWin())return null;
        String missing=missingPiece(player);
        if(missing==null)return null;
        CubeThopterPlan plan=new CubeThopterPlan(player);
        for(Card hand:player.getCardsIn(ZoneType.Hand))
            if(!hand.isFaceDown() && hand.getName().equals(missing)
                    && CubeComboAi.feasiblePartnerAfterSelection(player,hand))return null;
        for(Card choice:legalChoices)
            if(choice.getOwner()==player && !choice.isFaceDown() && choice.isInZone(ZoneType.Library)
                    && choice.getName().equals(missing)) {
                boolean supported=plan.supportsAssembly(choice);
                boolean feasible=supported && CubeComboAi.feasiblePartnerAfterSelection(player,choice);
                if(feasible)return choice;
            }
        return null;
    }

    /** Public native restrictions on the completed engine, before spending a
     * cast/selection on it. Detached LKI gives activation checks the future
     * battlefield zone; actual casts/activations still pass full native gates. */
    private boolean supportsAssembly(Card piece) { return supportsAssembly(java.util.List.of(piece)); }

    /** Resolve each piece from the supplied previews first, then from our
     * visible battlefield, and run every public gate on that configuration. */
    private boolean supportsAssembly(java.util.Collection<Card> previews) {
        Card urza=null,foundry=null,sword=null;
        for(Card piece:previews) {
            if(URZA.equals(piece.getName()))urza=piece;
            else if(FOUNDRY.equals(piece.getName()))foundry=piece;
            else if(SWORD.equals(piece.getName()))sword=piece;
        }
        if(urza==null)urza=find(URZA);
        if(foundry==null)foundry=find(FOUNDRY);
        if(sword==null)sword=find(SWORD);
        if(urza==null || foundry==null || sword==null || !sword.isArtifact() || sword.isToken()
                || !recursionAvailable(sword))return false;
        SpellAbility mana=ability(urza,ApiType.Mana),make=ability(foundry,ApiType.Token);
        if(mana==null || make==null || !TOKEN.equals(make.getParam("TokenScript")))return false;
        Card futureSword=CardCopyService.getLKICopy(sword);
        futureSword.setLastKnownZone(player.getZone(ZoneType.Battlefield));
        // canBeSacrificedBy also requires a live card identity, so it cannot
        // validate a detached future piece. Use the native static predicate
        // here; actual execution still requires the complete live-card check.
        for(Card source:player.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            if(source.isFaceDown() || !source.getView().canBeShownTo(player.getView()))continue;
            for(var st:source.getStaticAbilities())
                if(st.checkConditions(StaticAbilityMode.CantSacrifice)
                        && forge.game.staticability.StaticAbilityCantSacrifice.applyCantSacrificeAbility(st,futureSword,make,false))return false;
        }
        for(SpellAbility sa:java.util.List.of(mana,make)) {
            Card future=CardCopyService.getLKICopy(sa.getHostCard());
            future.setLastKnownZone(player.getZone(ZoneType.Battlefield));
            if(sa.isSuppressed() || future.isDetained() || !sa.checkRestrictions(future,player))return false;
            for(Card source:player.getGame().getCardsIn(java.util.List.of(ZoneType.Battlefield,ZoneType.Command))) {
                if(source.isFaceDown())continue;
                for(var st:source.getStaticAbilities())
                    if(st.hasParam("RemoveAllAbilities") && st.checkConditions(StaticAbilityMode.Continuous)
                            && st.matchesValidParam("Affected",future))return false;
            }
        }
        var cost=ComputerUtilMana.calculateManaCost(make.getPayCosts(),make,player,true,0,false);
        return cost.getConvertedManaCost()==1 && cost.getGenericManaAmount()==1
                && mana.getPayCosts().getCostParts().size()==1
                && mana.getPayCosts().getCostParts().get(0) instanceof CostTapType tap
                && tap.getAbilityAmount(mana)==1 && "Artifact".equals(tap.getType());
    }

    private SpellAbility assembleFromHand() {
        var phase=player.getGame().getPhaseHandler();
        if(!(phase.is(PhaseType.MAIN1,player)||phase.is(PhaseType.MAIN2,player)))return decline("phase");
        java.util.List<String> missingAll=missingPieces(player);
        if(missingAll.size()==2)return assembleTwoFromHand(missingAll);
        if(missingAll.size()!=1)return decline("missing="+missingToken(missingAll));
        String missing=missingAll.get(0);
        for(Card card:player.getCardsIn(ZoneType.Hand)) {
            if(card.isFaceDown() || !card.getName().equals(missing) || !supportsAssembly(card))continue;
            var original=card.getSpellPermanent();
            if(original==null)continue;
            SpellAbility cast=original.copy(player);
            if(CubeComboAi.canPlayNative(cast,player) && CubeComboAi.canPayCost(cast,player,false)) {
                select(cast);assemblySelections++;
                System.err.println("CUBE_THOPTER_ASSEMBLY select-hand card="+card.getName());
                return cast;
            }
        }
        return decline("no-castable:"+missing.replace(' ','_'));
    }

    /** Whether a card carries the artifact-tap mana ability the engine relies
     * on. A property of the printed rules, not a card-name play order. */
    private static boolean carriesArtifactTapMana(Card card) {
        for(SpellAbility sa:card.getSpellAbilities())
            if(sa.getApi()==ApiType.Mana && sa.getPayCosts()!=null && sa.getPayCosts().getCostParts().size()==1
                    && sa.getPayCosts().getCostParts().get(0) instanceof CostTapType tap && "Artifact".equals(tap.getType()))return true;
        return false;
    }

    /** Every colored shard of the cast's mana cost can be produced by some
     * mana source we control. Distinguishes "not enough lands yet" (a later
     * turn can pay) from "this color is unobtainable" (the plan cannot
     * complete). Quantity is not forecast here; each actual cast still passes
     * native payment. */
    private boolean colorCompletable(SpellAbility cast) {
        // Native source discovery sets ability actors; the payment probe restores them.
        CardCollection sources=CubeComboAi.probePayment(player,()->ComputerUtilMana.getAvailableManaSources(player,false));
        for(var shard:cast.getPayCosts().getTotalMana()) {
            if(shard.isGeneric() || shard.getColorMask()==0)continue;
            java.util.Set<String> colors=new java.util.HashSet<>();
            for(byte color:forge.card.MagicColor.WUBRG)if((shard.getColorMask()&color)!=0)colors.add(forge.card.MagicColor.toLongString(color));
            boolean producible=false;
            for(Card source:sources)if(source.canProduceColorMana(colors)) { producible=true; break; }
            if(!producible)return false;
        }
        return true;
    }

    /** Two pieces missing, both in hand. Every piece must be a mana-only
     * permanent that is castable now or at least color-completable from our
     * mana sources; at least one must be castable now; the completed engine
     * must pass every public gate; the pool must be empty so the
     * disjoint-source forecast is meaningful; a ready Kiki route in MAIN1
     * keeps priority. When both are castable now, forecast both orders with
     * native disjoint sources and cast the first piece of a same-turn order;
     * a public cast-count prohibition rules the same-turn order out, exactly as
     * it does for planTutor's two casts. Otherwise cast one castable piece and
     * re-evaluate on the next decision. No sequence is pre-committed. */
    private SpellAbility assembleTwoFromHand(java.util.List<String> missing) {
        if(missing.size()!=2)return decline("missing="+missingToken(missing));
        if(!player.getManaPool().isEmpty() || CubeComboAi.hasImmediateKikiRoute(player))
            return decline(player.getManaPool().isEmpty()?"kiki-route-preferred":"pool-not-empty");
        java.util.List<Card> pieces=new java.util.ArrayList<>();
        java.util.List<SpellAbility> casts=new java.util.ArrayList<>();
        boolean[] payable=new boolean[2];
        for(String name:missing) {
            Card found=null;
            for(Card card:player.getCardsIn(ZoneType.Hand))
                if(!card.isFaceDown() && card.getName().equals(name)) { found=card; break; }
            if(found==null || found.getSpellPermanent()==null)return decline("missing="+missingToken(missing));
            SpellAbility cast=found.getSpellPermanent().copy(player);
            if(!CubeComboAi.manaOnly(cast) || !CubeComboAi.canPlayNative(cast,player))return decline("assembly-unaffordable");
            payable[pieces.size()]=CubeComboAi.canPayCost(cast,player,false);
            if(!payable[pieces.size()] && !colorCompletable(cast))return decline("assembly-unaffordable");
            pieces.add(found); casts.add(cast);
        }
        if(!payable[0] && !payable[1])return decline("assembly-unaffordable");
        if(!supportsAssembly(pieces))return decline("assembly-unsupported");
        int preferred=carriesArtifactTapMana(pieces.get(1)) && !carriesArtifactTapMana(pieces.get(0))?1:0;
        if(!payable[preferred])preferred=1-preferred;
        if(payable[0] && payable[1])for(int first:new int[]{preferred,1-preferred}) {
            SpellAbility a=casts.get(first),b=casts.get(1-first);
            if(!CubeComboAi.castFitsAfter(player,a,b))continue;
            var cost=ComputerUtilMana.calculateManaCost(b.getPayCosts(),b,player,true,0,false);
            CardCollection reserve=CubeComboAi.getManaSourcesToPayCost(cost,b,player,false);
            if(reserve!=null && CubeComboAi.withReservedSources(player,reserve,
                    ()->CubeComboAi.canPlayNative(a,player) && CubeComboAi.canPayCost(a,player,false))) {
                select(a);assemblySelections++;assemblySameTurnForecasts++;
                assemblyReserve=reserve;assemblyReserveFor=a;
                System.err.println("CUBE_THOPTER_ASSEMBLY select-hand2 card="+pieces.get(first).getName()+" forecast=same-turn");
                return a;
            }
        }
        SpellAbility a=casts.get(preferred);
        select(a);assemblySelections++;assemblyPartialForecasts++;
        System.err.println("CUBE_THOPTER_ASSEMBLY select-hand2 card="+pieces.get(preferred).getName()+" forecast=partial");
        return a;
    }

    public SpellAbility nextAction() {
        var game=player.getGame();
        int currentTurn=game.getPhaseHandler().getTurn();
        if(currentTurn!=turn) { turn=currentTurn; actions=0; selected=null; pending=null; assemblyReserve=null; assemblyReserveFor=null; }
        if(failedTurn==turn || actions>=160 || !game.getStack().isEmpty() || player.cantWin())return decline(gateReason());
        decline="other check=thopter-plan";
        if(pending!=null) {
            boolean failed=tokens()<=beforeTokens || find(SWORD)==null || player.getLife()<beforeLife;
            pending=null;
            if(failed) {
                failedTurn=turn;
                System.err.println("CUBE_THOPTER_PLAN stopped-no-progress turn="+turn);
                return decline("stopped-no-progress");
            }
        }
        Card urza=find(URZA),foundry=find(FOUNDRY),sword=find(SWORD);
        if(urza==null || foundry==null || sword==null)return assembleFromHand();
        if(!sword.isArtifact() || sword.isToken())return decline("sword-not-artifact");
        long budget=0;
        for(Player opponent:player.getOpponents())
            budget+=2L+Math.max(0,opponent.getLife())+opponent.getCreaturesInPlay().size();
        if(tokens()>=Math.min(64,budget))return decline("token-budget");
        SpellAbility make=ability(foundry,ApiType.Token),mana=ability(urza,ApiType.Mana);
        if(make==null || mana==null || !TOKEN.equals(make.getParam("TokenScript"))
                || !CubeComboAi.canPlayNative(make,player) || mana.isSuppressed() || !mana.isLegalAfterStack()
                || !sword.canBeSacrificedBy(make,false) || !recursionAvailable(sword))return decline("engine-unusable");
        var cost=ComputerUtilMana.calculateManaCost(make.getPayCosts(),make,player,true,0,false);
        if(cost.getConvertedManaCost()!=1 || cost.getGenericManaAmount()!=1)return decline("cost-mismatch");
        if(mana.getPayCosts().getCostParts().size()!=1
                || !(mana.getPayCosts().getCostParts().get(0) instanceof CostTapType tap)
                || tap.getAbilityAmount(mana)!=1 || !"Artifact".equals(tap.getType()))return decline("cost-mismatch");
        paymentSword=sword;
        paymentTap=sword.canTap()?sword:foundry.isArtifact() && foundry.canTap()?foundry:null;
        SpellAbility next;
        if(player.getManaPool().totalMana()<1) {
            if(paymentTap==null)return decline("no-tap-payment");
            next=mana;
        } else next=make;
        if(!CubeComboAi.canPlayNative(next,player) || !CubeComboAi.canPayCost(next,player,false))return decline("unpayable:engine");
        return select(next);
    }

    public boolean owns(SpellAbility sa) { return sa==selected; }
    public boolean play(SpellAbility sa) {
        beforeTokens=tokens(); beforeLife=player.getLife();
        int beforeMana=player.getManaPool().totalMana();
        CardCollection reserve=sa==assemblyReserveFor && assemblyReserve!=null?assemblyReserve:new CardCollection();
        assemblyReserve=null; assemblyReserveFor=null;
        boolean played=CubeComboAi.withReservedSources(player,reserve,()->ComputerUtil.handlePlayingSpellAbility(player,sa,null,current->new AiCostDecision(player,current,false) {
            @Override public PaymentDecision visit(CostTapType cost) {
                if(sa.getApi()==ApiType.Mana && current==sa && cost.getAbilityAmount(current)==1
                        && "Artifact".equals(cost.getType()) && paymentTap!=null && paymentTap.canTap()
                        && paymentTap.isInZone(ZoneType.Battlefield) && paymentTap.getController()==player
                        && paymentTap.isValid(cost.getType().split(";"),player,current.getHostCard(),current))return PaymentDecision.card(paymentTap);
                return super.visit(cost);
            }
            @Override public PaymentDecision visit(CostSacrifice cost) {
                if(sa.getApi()==ApiType.Token && current==sa && cost.getAbilityAmount(current)==1
                        && "Artifact.!token".equals(cost.getType()) && paymentSword!=null
                        && paymentSword.isInZone(ZoneType.Battlefield) && paymentSword.getController()==player
                        && paymentSword.isValid(cost.getType().split(";"),player,current.getHostCard(),current)
                        && paymentSword.canBeSacrificedBy(current,false))return PaymentDecision.card(paymentSword);
                return super.visit(cost);
            }
        }));
        if(!played || sa.isManaAbility() && player.getManaPool().totalMana()<=beforeMana)failedTurn=turn;
        if(played && sa.getApi()==ApiType.Token)pending=sa;
        System.err.println("CUBE_THOPTER_PLAN "+(played?"played":"native-payment-failed")
                +" turn="+turn+" api="+sa.getApi()+" tokens="+tokens()+" mana="+player.getManaPool().totalMana());
        return played;
    }
    public boolean waitingForOwnSpell() {
        var stack=player.getGame().getStack();
        if(selected==null || turn!=player.getGame().getPhaseHandler().getTurn() || stack.isEmpty())return false;
        var top=stack.peekAbility();
        return top!=null && top.getActivatingPlayer()==player
                && (FOUNDRY.equals(top.getHostCard().getName()) || SWORD.equals(top.getHostCard().getName()) || URZA.equals(top.getHostCard().getName()));
    }
}
