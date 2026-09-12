package forge.ai;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Native Displacer Kitten replay plans. Library identities/order are not
 * inspected: Oracle availability comes from the player's own registered deck
 * minus known unavailable cards. Each draw, bounce and blink resolves normally.
 *
 * <p>Two loop shapes share one Kitten. The original route (catalogue family I)
 * is Teferi, Time Raveler's loyalty bounce of a replay rock, drawing out the
 * library for Thassa's Oracle, and v65 does not change one step of it. The v65
 * FAMILY routes are the ones whose restore is not a Teferi bounce, recognized
 * by printed property rather than by name:
 *
 * <ul>
 *   <li>{@code venser} - a partner whose MANDATORY enter-the-battlefield
 *       trigger returns a targeted card to its owner's hand and may target the
 *       STACK. Kitten's blink resolves before the spell that caused it, so the
 *       partner catches the loop object mid-cast and it never resolves; the
 *       only loop object that can pay for itself forever is therefore one that
 *       costs nothing ({@link #free}). Catalogue family M.</li>
 *   <li>{@code lurrus} - a partner granting a per-turn-limited permission to
 *       cast our own nonland permanents from our own GRAVEYARD. Kitten's blink
 *       replaces the partner's static abilities, so the per-turn cap starts
 *       over; the loop object is a free permanent that sacrifices itself for
 *       its own activated ability and so returns to the graveyard each
 *       iteration. Catalogue family N.</li>
 * </ul>
 *
 * <p>Both produce an unbounded count of spells cast this turn and nothing else
 * this plan will rely on, so the win is a life-shot outlet read off the
 * printed shape of a permanent we already control ({@link #findShot}), never a
 * card name. The whole forecast is own-visible: our own zones, our own life,
 * the opponent's public life and the public count of spells we have cast.</p> */
public final class CubeKittenPlan {
    private static final String KITTEN="Displacer Kitten", TEFERI="Teferi, Time Raveler", ORACLE="Thassa's Oracle";
    /** Printed-script keys a plain repeatable mana ability may carry. An ability
     * with any other key (conditions, activation limits, zones, sub-abilities)
     * is left uncounted rather than assumed harmless, so the predicate can only
     * ever be narrower than the script it reads. */
    private static final java.util.Set<String> MANA_KEYS=java.util.Set.of(
        "AB","Cost","Produced","Amount","Activation","PrecostDesc","StackDescription","SpellDescription");
    private final Player player;
    private int turn=-1, actions, failedTurn=-1, libraryBefore;
    private SpellAbility selected, pending;
    private boolean active;
    private String rockName;
    private long teferiBefore;
    /** v65 family-route state. All null/false outside a family route, so every
     * pre-v65 position takes exactly the pre-v65 path. */
    private String familyRoute, partnerName, loopName, outletName;
    private long partnerBefore;
    private boolean familyPartnerSeen, forecastLogged;
    /** Observability only: the token for the check that already declined the
     * most recent {@link #nextAction}. Never read by a decision, exactly like
     * {@link CubeDoomsdayPlan#declineReason}.
     *
     * <p>Grammar, one token per (turn, phase): {@code phase},
     * {@code stack-not-empty}, {@code cant-win}, {@code action-cap},
     * {@code failed-this-turn}, {@code stopped-no-progress},
     * {@code missing=<our own missing piece(s)>}, {@code no-known-oracle},
     * {@code mana:UU/<have>} where {@code <have>} is
     * {@link CubeComboAi#ownVisibleMana} (our own untapped lands plus our own
     * floating mana), {@code library-empty}, {@code cant-draw} or
     * {@code no-rock-route}. Our own library SIZE is own-visible; no token
     * reads library contents or order, and none names an opponent zone.</p>
     *
     * <p>v65 adds one further shape, {@code family:<route>:<token>}, emitted
     * ONLY when a family partner ({@link #stackBouncer},
     * {@link #graveyardPermission}) is on our own battlefield, so no position
     * without one can see its decline token change. Its tokens are
     * {@code multiplayer}, {@code no-outlet}, {@code outlet-untargetable},
     * {@code no-loop-object}, {@code no-loop-action}, {@code shot-unreachable}
     * and {@code outlet-unusable}; each names our own battlefield, our own hand,
     * our own graveyard, our own life or the public opposing life.</p> */
    private String decline="other check=kitten-plan";
    public String declineReason() {return decline;}
    private SpellAbility decline(String reason) {decline=reason;return null;}
    private String familyDecline="other check=kitten-family";
    private SpellAbility familyDecline(String reason) {familyDecline=reason;active=false;return null;}
    /** Names the first true clause of the opening guard, re-reading only the
     * same pure getters in the same order, after that guard has already
     * decided to decline. */
    private String gateReason() {
        var phase=player.getGame().getPhaseHandler();
        if(failedTurn==turn) return "failed-this-turn";
        if(actions>=200) return "action-cap";
        if(player.cantWin()) return "cant-win";
        if(!player.getGame().getStack().isEmpty()) return "stack-not-empty";
        return "phase";
    }
    /** Our own missing engine pieces, from our own battlefield only. */
    private String missingToken(Card kitten,Card teferi) {
        StringBuilder names=new StringBuilder();
        if(kitten==null) names.append(KITTEN.replace(' ','_'));
        if(teferi==null) {if(names.length()>0) names.append(';');names.append(TEFERI.replace(' ','_'));}
        return names.toString();
    }
    public CubeKittenPlan(Player player) {this.player=player;}

    private Card find(String name,ZoneType zone) {
        for(Card c:player.getCardsIn(zone)) if(!c.isFaceDown()&&c.getName().equals(name)) return c;
        return null;
    }
    private boolean knownOracle() {
        if(find(ORACLE,ZoneType.Hand)!=null) return true;
        return CubeComboAi.ownCopyOutside(player,ORACLE,
            ZoneType.Graveyard,ZoneType.Exile,ZoneType.Battlefield,ZoneType.Command,ZoneType.Stack);
    }
    private <T> T reserveBlue(SpellAbility sa,Supplier<T> task) {
        if(sa.getHostCard().getName().equals(ORACLE)) return task.get();
        List<Card> added=new ArrayList<>(); int count=0;
        var memory=AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL;
        for(Card c:player.getCardsIn(ZoneType.Battlefield)) {
            if(!c.isLand()||c.isTapped()) continue;
            boolean blue=c.getManaAbilities().stream().anyMatch(a->a.copy(player).canProduce("U"));
            if(!blue) continue;
            if(!AiCardMemory.isRememberedCard(player,c,memory)) {AiCardMemory.rememberCard(player,c,memory);added.add(c);}
            if(++count==2) break;
        }
        try{return task.get();}finally{for(Card c:added) AiCardMemory.forgetCard(player,c,memory);}
    }
    private boolean payable(SpellAbility sa) {
        return sa!=null&&CubeComboAi.canPlayNative(sa,player)&&reserveBlue(sa,()->CubeComboAi.canPayCost(sa,player,false));
    }
    private SpellAbility spell(Card card) {
        if(card==null) return null;
        for(SpellAbility original:card.getAllPossibleAbilities(player,false,null,true)) {
            SpellAbility sa=original.copy(player); if(sa.isSpell()&&payable(sa)) return sa;
        }
        return null;
    }
    private boolean target(SpellAbility sa,Card card) {
        sa.resetTargets(); if(!sa.canTarget(card)) return false; sa.getTargets().add(card);
        return sa.isTargetNumberValid()&&StaticAbilityMustTarget.meetsMustTargetRestriction(sa);
    }
    private SpellAbility bounce(Card teferi,Card rock) {
        for(SpellAbility original:teferi.getSpellAbilities()) {
            SpellAbility sa=original.copy(player);
            if(sa.getApi()==ApiType.ChangeZone&&"Hand".equals(sa.getParam("Destination"))&&target(sa,rock)&&payable(sa)) return sa;
        }
        return null;
    }
    private SpellAbility choose(SpellAbility sa) {selected=sa;actions++;active=true;return sa;}
    /** Diagnostic only, never read by a decision: how many actions the plan took
     * on a rock it selected by the printed property. Test-visible static, read
     * and reset reflectively by the fixture, exactly like
     * {@link CubeDoomsdayPlan#ritualBridges}. */
    static int planRockActions;
    /** Diagnostic only, never read by a decision: how many actions a v65 family
     * route took (loop actions plus the shot). Same reflective contract. */
    static int planFamilyActions;
    private SpellAbility chooseRock(String name,SpellAbility sa) {rockName=name;planRockActions++;return choose(sa);}
    private SpellAbility chooseFamily(String name,SpellAbility sa) {loopName=name;planFamilyActions++;return choose(sa);}

    /** Charge-style counters the rock can spend on the iteration we are about to
     * run: what Sunburst will give it when it next enters, which is the number
     * of distinct colours we can currently pay with, capped by its own cost.
     * No card names and no library reads. */
    private int countersAvailable(Card card,forge.game.card.CounterType type) {
        // Counters the rock will carry the next time it enters the battlefield,
        // not what it happens to hold halfway through an iteration: the loop
        // replays the rock every time round, and a card whose counters nothing
        // restores is read as entering with none.
        if(!card.hasKeyword(forge.game.keyword.Keyword.SUNBURST)) return 0;
        int colors=0;
        for(byte color:forge.card.MagicColor.WUBRG) {
            String shorthand=forge.card.MagicColor.toShortString(color);
            if(player.getManaPool().getAmountOfColor(color)>0) {colors++;continue;}
            for(Card source:player.getCardsIn(ZoneType.Battlefield)) {
                if(source.isTapped()) continue;
                if(source.getManaAbilities().stream().anyMatch(a->a.copy(player).canProduce(shorthand))) {colors++;break;}
            }
        }
        return Math.min(colors,card.getManaCost().getCMC());
    }

    /** Mana the artifact yields on one loop iteration once it re-enters the
     * battlefield untapped with its counters restored, read from the card's own
     * printed mana abilities. An ability whose cost is anything but tapping and
     * removing counters is not counted: the loop has to bounce the very same
     * permanent back, so it may not be sacrificed or exiled to pay. */
    private int perIteration(Card card) {
        int best=0;
        for(SpellAbility original:card.getManaAbilities()) {
            SpellAbility mana=original.copy(player);
            if(mana.getManaPart()==null||mana.getPayCosts()==null
                ||!MANA_KEYS.containsAll(mana.getMapParams().keySet())) continue;
            if(mana.getRestrictions().isMetalcraft()) {
                // Metalcraft has to hold at the moment of tapping, which is after
                // this card itself is on the battlefield. Our own artifacts only.
                int artifacts=card.isInPlay()?0:1;
                for(Card own:player.getCardsIn(ZoneType.Battlefield)) if(own.isArtifact()) artifacts++;
                if(artifacts<3) continue;
            } else if(mana.getMapParams().containsKey("Activation")) continue;
            int activations=-1; boolean readable=true;
            for(forge.game.cost.CostPart part:mana.getPayCosts().getCostParts()) {
                if(part instanceof forge.game.cost.CostTap) continue;
                if(part instanceof forge.game.cost.CostRemoveCounter remove) {
                    int each=remove.getAbilityAmount(mana);
                    int limit=each<1?0:countersAvailable(card,remove.counter)/each;
                    activations=activations<0?limit:Math.min(activations,limit);
                    continue;
                }
                readable=false;break;
            }
            if(!readable) continue;
            best=Math.max(best,(activations<0?1:activations)*mana.amountOfManaGenerated(true));
        }
        return best;
    }

    /** The printed property the replay loop needs, in place of a name list: a
     * noncreature artifact that, once it re-enters the battlefield, produces at
     * least the mana one iteration costs - which is replaying the rock itself,
     * every other step of the iteration being free. */
    private boolean rock(Card card) {
        return card.isArtifact()&&!card.isCreature()&&!card.isLand()
            &&perIteration(card)>=card.getManaCost().getCMC();
    }

    /** An "add one mana of any colour" rock is asked for a colour our floating
     * mana does not already hold. This is only a preference over a choice the
     * controller is entitled to make; it keeps a replay that counts the colours
     * spent (Sunburst) from losing counters. Colourless rocks never reach it. */
    private void broadenColor(SpellAbility sa) {
        var part=sa.getManaPart();
        if(part==null||!part.isAnyMana()) return;
        for(byte color:forge.card.MagicColor.WUBRG) {
            if(player.getManaPool().getAmountOfColor(color)==0) {
                part.setExpressChoice(forge.card.MagicColor.toShortString(color));
                return;
            }
        }
    }


    /** v60 - which single card, fetched from our own library, would complete
     * this plan's entry gate: Displacer Kitten and Teferi on our own
     * battlefield, an Oracle this plan can still reach, and a replay rock we
     * can already see. Empty unless exactly one of the two named engine halves
     * is missing.
     *
     * <p>The rock is a PRINTED PROPERTY of a live card ({@link #rock}), so it
     * can only be recognised on one we can already see - our own battlefield or
     * our own hand - and is therefore required to be present rather than ever
     * being named as the missing piece. {@link #knownOracle} is the plan's own
     * availability test (our own hand, else our own registered deck
     * composition minus our own visible zones); no library content or order is
     * read, and no opponent zone is touched. Entry gate only: the UU the plan
     * checks before it draws is its own business on the turn it acts.</p>
     *
     * <p>v65 deliberately leaves this on the Teferi route alone: a family
     * route's own missing half is a partner recognised by printed property on a
     * LIVE card, which a library fetch can no more name than it can the rock.</p> */
    static java.util.List<String> completingPieceNames(Player player) {
        CubeKittenPlan plan=new CubeKittenPlan(player);
        boolean kitten=plan.find(KITTEN,ZoneType.Battlefield)!=null;
        boolean teferi=plan.find(TEFERI,ZoneType.Battlefield)!=null;
        if(kitten==teferi||!plan.knownOracle()) return java.util.List.of();
        for(ZoneType zone:List.of(ZoneType.Battlefield,ZoneType.Hand))
            for(Card card:player.getCardsIn(zone))
                if(!card.isFaceDown()&&plan.rock(card)) return java.util.List.of(kitten?TEFERI:KITTEN);
        return java.util.List.of();
    }

    public SpellAbility nextAction() {
        var game=player.getGame();var phase=game.getPhaseHandler();
        if(turn!=phase.getTurn()) {turn=phase.getTurn();actions=0;active=false;selected=null;pending=null;rockName=null;
            familyRoute=null;partnerName=null;loopName=null;outletName=null;forecastLogged=false;}
        if(failedTurn==turn||actions>=200||player.cantWin()||!game.getStack().isEmpty()
                ||!(phase.is(PhaseType.MAIN1,player)||phase.is(PhaseType.MAIN2,player))) return decline(gateReason());
        decline="other check=kitten-plan";
        int library=player.getCardsIn(ZoneType.Library).size();
        Card kitten=find(KITTEN,ZoneType.Battlefield), teferi=find(TEFERI,ZoneType.Battlefield);
        if(pending!=null) {
            Card partner=find(partnerName,ZoneType.Battlefield);
            boolean stalled=pending.getHostCard().getName().equals(TEFERI) && library>=libraryBefore
                || pending.getHostCard().getName().equals(rockName)&&pending.isSpell()
                   && (teferi==null||teferi.getGameTimestamp()==teferiBefore)
                // A family iteration that did not blink its partner made no
                // progress either: the same public quantity, read on the
                // route's own partner.
                || familyRoute!=null&&pending.isSpell()&&pending.getHostCard().getName().equals(loopName)
                   && (partner==null||partner.getGameTimestamp()==partnerBefore);
            pending=null;
            if(stalled) {failedTurn=turn;active=false;System.err.println("CUBE_KITTEN_PLAN stopped-no-progress turn="+turn);return decline("stopped-no-progress");}
        }
        SpellAbility action=teferiRoute(kitten,teferi,library);
        if(action!=null) return action;
        // The Teferi route has already recorded its own token. A family route is
        // consulted only after that route declines, and may only change the
        // reported token when a family partner is on our own battlefield.
        String teferiToken=decline;
        if(kitten!=null) {
            SpellAbility family=familyAction();
            if(family!=null) return family;
            if(familyPartnerSeen) return decline("family:"+familyRoute+":"+familyDecline);
        }
        return decline(teferiToken);
    }

    /** Catalogue family I, unchanged since v48/v54: Teferi's loyalty bounce of a
     * replay rock, drawing our own library out for Thassa's Oracle. */
    private SpellAbility teferiRoute(Card kitten,Card teferi,int library) {
        if(kitten==null||teferi==null||!knownOracle()) {active=false;
            return decline(kitten==null||teferi==null?"missing="+missingToken(kitten,teferi):"no-known-oracle");}
        // Do not draw toward a finisher whose colored cost cannot currently
        // be funded. This tests resources only; it does not inspect Oracle's
        // position in the library or pretend it is presently castable.
        if(!CubeComboAi.canPayCost(new forge.game.cost.Cost("U U",false),
                teferi.getSpellAbilities().get(0).copy(player),player,false)) {active=false;
            return decline("mana:UU/"+CubeComboAi.ownVisibleMana(player));}
        // <=2 is conservative Oracle's own UU devotion, not an estimate of
        // hidden cards or an assertion that its trigger cannot be stopped.
        if(library<=2) {
            SpellAbility finish=spell(find(ORACLE,ZoneType.Hand));
            if(finish!=null) return choose(finish);
        }
        if(library==0||!player.canDrawAmount(1)) {active=false;return decline(library==0?"library-empty":"cant-draw");}
        List<String> rocks=new ArrayList<>();
        for(ZoneType zone:List.of(ZoneType.Battlefield,ZoneType.Hand))
            for(Card card:player.getCardsIn(zone))
                if(!card.isFaceDown()&&rock(card)&&!rocks.contains(card.getName())) rocks.add(card.getName());
        for(String name:rocks) {
            Card rock=find(name,ZoneType.Battlefield);
            if(rock!=null) {
                SpellAbility bounce=bounce(teferi,rock);
                if(bounce==null) continue;
                // Capture useful rock mana BEFORE returning it to hand.
                for(SpellAbility original:rock.getManaAbilities()) {
                    SpellAbility mana=original.copy(player);
                    if(payable(mana)) return chooseRock(name,mana);
                }
                // An ordinary replay must be affordable without consuming
                // Oracle's reserved blue sources. Current pool covers the
                // named rock's printed cost; actual cast checks follow.
                if(player.getManaPool().totalMana()>=rock.getManaCost().getCMC()) return chooseRock(name,bounce);
            }
            SpellAbility cast=spell(find(name,ZoneType.Hand));
            if(cast!=null) return chooseRock(name,cast);
        }
        active=false;return decline("no-rock-route");
    }

    // ----------------------------------------------------------------- v65 --

    /** One printed effect script, as its parameter map. */
    private java.util.Map<String,String> effect(Card card,String svar) {
        java.util.Map<String,String> map=new java.util.HashMap<>();
        if(svar.isEmpty()) return map;
        for(String piece:card.getSVar(svar).split("\\|")) {
            String[] pair=piece.trim().split("\\$",2);
            if(pair.length==2) map.put(pair[0].trim(),pair[1].trim());
        }
        return map;
    }
    private static int number(String text) {
        try {return Integer.parseInt(text.trim());} catch(RuntimeException unreadable) {return -1;}
    }

    /** Catalogue family M's partner, by shape: a MANDATORY enter-the-battlefield
     * trigger on this very card that returns a TARGETED card to its owner's
     * hand and whose origins include the STACK.
     *
     * <p>Mandatory matters. An optional "you may" return is decided by
     * {@code confirmTrigger}, which this plan does not own, so whether the
     * restore happens at all would not be ours to forecast; such a partner is
     * refused rather than assumed willing. The stack origin matters because
     * Kitten's blink resolves BEFORE the spell that triggered it, so the loop
     * object is still on the stack when the partner enters.</p> */
    private boolean stackBouncer(Card card) {
        for(var trigger:card.getTriggers()) {
            if(trigger.isSuppressed()||trigger.hasParam("OptionalDecider")) continue;
            if(!"ChangesZone".equals(trigger.getParam("Mode"))
                ||!"Battlefield".equals(trigger.getParamOrDefault("Destination",""))
                ||!"Card.Self".equals(trigger.getParamOrDefault("ValidCard",""))) continue;
            var script=effect(card,trigger.getParamOrDefault("Execute",""));
            if(!"ChangeZone".equals(script.get("DB"))||!"Hand".equals(script.getOrDefault("Destination",""))
                ||script.getOrDefault("ValidTgts","").isEmpty()) continue;
            if(List.of(script.getOrDefault("Origin","").split(",")).contains("Stack")) return true;
        }
        return false;
    }

    /** Catalogue family N's partner, by static shape, the same way v50's
     * {@code CubeTopPlan.permissions()} reads a top-of-library permission: a
     * live continuous {@code MayPlay} for our own nonland permanents in our own
     * GRAVEYARD, carrying a per-turn cap and no alternative cost this plan
     * cannot forecast.
     *
     * <p>The cap is what makes the blink worth anything. Forge counts it on the
     * static ability OBJECT ({@code StaticAbility.getMayPlayTurn}, identity by
     * id), and a permanent that leaves the battlefield is copied, so the
     * partner Kitten returns carries fresh statics and a fresh count. A
     * permission with no cap needs no Kitten at all and is not this route.</p> */
    private forge.game.staticability.StaticAbility graveyardPermission(Card card) {
        for(var st:card.getStaticAbilities()) {
            if(st.isSuppressed()||!"True".equals(st.getParamOrDefault("MayPlay",""))
                ||!st.hasParam("MayPlayLimit")
                ||st.hasParam("MayPlayAltManaCost")||st.hasParam("MayPlayWithoutManaCost")
                ||!st.checkConditions(forge.game.staticability.StaticAbilityMode.Continuous)
                ||!List.of(st.getParamOrDefault("AffectedZone","").split(",")).contains("Graveyard")) continue;
            String affected=st.getParamOrDefault("Affected","");
            if(affected.isEmpty()||java.util.Arrays.stream(affected.split(","))
                .anyMatch(alt->!alt.contains("YouOwn")||!alt.contains("nonLand"))) continue;
            return st;
        }
        return null;
    }

    /** The outlet, by printed shape on a permanent we already control: a
     * spell-cast trigger gaining us life equal to the spells WE have cast this
     * turn, beside an activated ability whose entire cost is one fixed life
     * payment and whose effect is a fixed amount of damage to any target. No
     * card name is read, and a permanent carrying only one of the two halves is
     * not an outlet. */
    private record Shot(Card card,int damage,int lifeCost) {}
    private boolean stormLifeGain(Card card) {
        for(var trigger:card.getTriggers()) {
            if(trigger.isSuppressed()||!"SpellCast".equals(trigger.getParam("Mode"))
                ||!"You".equals(trigger.getParamOrDefault("ValidActivatingPlayer",""))
                ||!List.of(trigger.getParamOrDefault("TriggerZones","").split(",")).contains("Battlefield")) continue;
            var script=effect(card,trigger.getParamOrDefault("Execute",""));
            if(!"GainLife".equals(script.get("DB"))||!"You".equals(script.getOrDefault("Defined",""))) continue;
            String amount=script.getOrDefault("LifeAmount","");
            if(!amount.isEmpty()&&"Count$ThisTurnCast_Card.YouCtrl".equals(card.getSVar(amount))) return true;
        }
        return false;
    }
    private Shot findShot() {
        for(Card card:player.getCardsIn(ZoneType.Battlefield)) {
            if(card.isFaceDown()||!stormLifeGain(card)) continue;
            for(SpellAbility original:card.getSpellAbilities()) {
                if(original.isSpell()||original.getApi()!=ApiType.DealDamage) continue;
                if(!"Any".equals(original.getParamOrDefault("ValidTgts",""))) continue;
                int damage=number(original.getParamOrDefault("NumDmg",""));
                var costs=original.getPayCosts();
                if(damage<1||costs==null||costs.getCostParts().size()!=1) continue;
                if(!(costs.getCostParts().get(0) instanceof forge.game.cost.CostPayLife pay)) continue;
                int life=number(pay.getAmount());
                if(life>0) return new Shot(card,damage,life);
            }
        }
        return null;
    }
    /** A fresh copy of the shot aimed at the opponent. TARGETING only: native
     * playability is deliberately NOT required here, because Forge reports a
     * life-payment ability as unplayable until the life is already there, and
     * getting the life there is the whole point of the loop. The full native
     * check is made at the moment the plan actually fires ({@code payable}),
     * which is the only moment it means anything. */
    private SpellAbility shotAbility(Shot shot,Player opponent) {
        for(SpellAbility original:shot.card().getSpellAbilities()) {
            if(original.isSpell()||original.getApi()!=ApiType.DealDamage) continue;
            SpellAbility sa=original.copy(player);
            sa.resetTargets();
            if(!sa.canTarget(opponent)) continue;
            sa.getTargets().add(opponent);
            if(sa.isTargetNumberValid()&&StaticAbilityMustTarget.meetsMustTargetRestriction(sa)) return sa;
        }
        return null;
    }

    /** Zero after Forge's own cost adjustment, with no X and no non-mana part.
     * A loop object bounced off the stack never resolves and so can never pay
     * for itself; one recast from the graveyard every iteration may not consume
     * mana either. Both are the same printed property. */
    private boolean free(SpellAbility sa) {
        if(sa.getPayCosts()==null) return false;
        var adjusted=forge.game.cost.CostAdjustment.adjust(sa.getPayCosts(),sa,false);
        if(adjusted==null) return false;
        for(var part:adjusted.getCostParts()) if(!(part instanceof forge.game.cost.CostPartMana)) return false;
        var mana=ComputerUtilMana.calculateManaCost(adjusted,sa,player,true,0,false);
        return mana!=null&&mana.getXcounter()==0&&mana.getConvertedManaCost()==0;
    }
    /** A free noncreature spell in our own hand. Noncreature is Displacer
     * Kitten's own printed condition, not a preference. */
    private SpellAbility freeHandSpell() {
        for(Card card:player.getCardsIn(ZoneType.Hand)) {
            if(card.isFaceDown()||card.isLand()||card.isCreature()) continue;
            for(SpellAbility original:card.getAllPossibleAbilities(player,false,null,true)) {
                SpellAbility sa=original.copy(player);
                if(sa.isSpell()&&free(sa)&&payable(sa)) return sa;
            }
        }
        return null;
    }
    /** A free cast of this graveyard card under that partner's own permission,
     * identified by the permanent that granted it, exactly as v50 does. */
    private SpellAbility permissionCast(Card card,Card partner) {
        for(SpellAbility original:card.getAllPossibleAbilities(player,false,null,true)) {
            SpellAbility sa=original.copy(player);
            if(!sa.isSpell()||sa.getHostCard().isCreature()) continue;
            if(sa.getMayPlay()==null||sa.getMayPlay().getHostCard()!=partner) continue;
            if(free(sa)&&payable(sa)) return sa;
        }
        return null;
    }
    /** The printed activated ability that puts the loop object back in our own
     * graveyard: cost parts drawn ONLY from tapping and sacrificing itself, and
     * at least one of the latter. Any other part - a discard, a life payment, a
     * counter - is a resource this plan does not model, so such a card is left
     * alone rather than assumed free. */
    private boolean sacrificeShape(SpellAbility sa) {
        if(sa.isSpell()||sa.getPayCosts()==null) return false;
        boolean sacrifice=false;
        for(forge.game.cost.CostPart part:sa.getPayCosts().getCostParts()) {
            if(part instanceof forge.game.cost.CostTap) continue;
            if(part instanceof forge.game.cost.CostSacrifice&&part.payCostFromSource()) {sacrifice=true;continue;}
            return false;
        }
        return sacrifice;
    }
    /** The PRINTED shape alone, asked of a card wherever it currently is. A
     * card sitting in our graveyard cannot activate anything, so requiring
     * native playability there would reject every loop object this route
     * exists for. */
    private boolean sacrificesItself(Card card) {
        if(card==null) return false;
        for(SpellAbility original:card.getSpellAbilities()) if(sacrificeShape(original)) return true;
        return false;
    }
    /** The same ability, on a card that is on the battlefield now and can pay. */
    private SpellAbility sacrificeAbility(Card card) {
        if(card==null) return null;
        for(SpellAbility original:card.getSpellAbilities()) {
            if(!sacrificeShape(original)) continue;
            SpellAbility sa=original.copy(player);
            if(payable(sa)) return sa;
        }
        return null;
    }

    /** The v65 routes. Returns null, having recorded nothing, when no family
     * partner is on our own battlefield at all - which is every pre-v65
     * position. */
    private SpellAbility familyAction() {
        familyPartnerSeen=false;
        Card partner=null; String route=null;
        for(Card card:player.getCardsIn(ZoneType.Battlefield)) {
            if(card.isFaceDown()||card.isLand()) continue;
            if(stackBouncer(card)) {partner=card;route="venser";break;}
        }
        if(partner==null) for(Card card:player.getCardsIn(ZoneType.Battlefield)) {
            if(card.isFaceDown()||card.isLand()) continue;
            if(graveyardPermission(card)!=null) {partner=card;route="lurrus";break;}
        }
        if(partner==null) return null;
        familyPartnerSeen=true; familyRoute=route; partnerName=partner.getName();
        // One public life total to aim at. A larger table needs a plan that
        // reasons about who is killed in what order; this one refuses instead.
        if(player.getOpponents().size()!=1) return familyDecline("multiplayer");
        Player opponent=player.getOpponents().get(0);
        Shot shot=findShot();
        if(shot==null) return familyDecline("no-outlet");
        outletName=shot.card().getName();
        SpellAbility fire=shotAbility(shot,opponent);
        if(fire==null) return familyDecline("outlet-untargetable");
        String name; SpellAbility loop;
        if(route.equals("venser")) {
            loop=freeHandSpell();
            if(loop==null) return familyDecline("no-loop-object");
            name=loop.getHostCard().getName();
        } else {
            // Mid-iteration the loop object is already on the battlefield and
            // owes us only its own sacrifice; otherwise it is recast.
            SpellAbility sacrifice=sacrificeAbility(find(loopName,ZoneType.Battlefield));
            if(sacrifice!=null) {name=loopName;loop=sacrifice;}
            else {
                name=null;loop=null;
                for(Card card:player.getCardsIn(ZoneType.Graveyard)) {
                    if(card.isFaceDown()||card.isLand()||!sacrificesItself(card)) continue;
                    SpellAbility cast=permissionCast(card,partner);
                    if(cast==null) continue;
                    name=card.getName();loop=cast;break;
                }
                if(loop==null) return familyDecline(loopName==null?"no-loop-object":"no-loop-action");
            }
        }
        // Forecast, before the first action of the loop: our own life, the
        // opponent's public life and the public count of the spells WE have
        // cast this turn. Nothing hidden, and nothing about the library.
        int perIteration=route.equals("venser")?1:2;
        int shots=Math.max(1,(int)Math.ceil(opponent.getLife()/(double)shot.damage()));
        long need=(long)shot.lifeCost()*shots+1L;
        int budget=(200-actions-shots)/perIteration;
        int alreadyCast=(int)player.getGame().getStack().getSpellsCastThisTurn().stream()
            .filter(s->s.getActivatingPlayer()==player).count();
        long life=player.getLife(); int casts=-1;
        for(int i=0;i<=Math.max(budget,0);i++) {
            if(life>=need) {casts=i;break;}
            life+=alreadyCast+i+1L;
        }
        if(casts<0) return familyDecline("shot-unreachable");
        // The shot is only asked to be natively payable on the pass it is
        // actually taken; before that the loop has not yet bought the life.
        if(casts==0&&!payable(fire)) return familyDecline("outlet-unusable");
        if(!forecastLogged) {
            forecastLogged=true;
            System.err.println("CUBE_KITTEN_FAMILY forecast turn="+turn+" route="+route
                +" partner="+partnerName.replace(' ','_')+" loop="+name.replace(' ','_')
                +" outlet="+outletName.replace(' ','_')+" casts="+casts+" shots="+shots
                +" need="+need+" life="+player.getLife()+" opponent="+opponent.getLife());
        }
        return chooseFamily(name,casts==0?fire:loop);
    }

    public boolean chooseBlink(SpellAbility sa) {
        if(!active||turn!=player.getGame().getPhaseHandler().getTurn()||sa.getActivatingPlayer()!=player) return false;
        if(familyRoute!=null) return chooseFamilyTarget(sa);
        if(!sa.getHostCard().getName().equals(KITTEN)||sa.getApi()!=ApiType.ChangeZone
            ||!"Exile".equals(sa.getParam("Destination"))) return false;
        Object cause=sa.getRootAbility().getTriggeringObject(forge.game.ability.AbilityKey.SpellAbility);
        if(selected==null||!selected.isSpell()||!selected.getHostCard().getName().equals(rockName)
            ||!(cause instanceof SpellAbility cast)||cast.getActivatingPlayer()!=player
            ||cast.getHostCard()!=selected.getHostCard()) return false;
        Card teferi=find(TEFERI,ZoneType.Battlefield);
        if(teferi==null||!target(sa,teferi)) return false;
        System.err.println("CUBE_KITTEN_PLAN blink-teferi turn="+turn+" target="+teferi.getId());
        return true;
    }

    /** The two target choices a family iteration owns: Kitten's own blink goes
     * to the partner, and the partner's own enter-the-battlefield return goes
     * to the loop object this plan just cast, matched by card IDENTITY and
     * required to still be on the stack. Everything else is left to the
     * ordinary chooser. */
    private boolean chooseFamilyTarget(SpellAbility sa) {
        if(sa.getApi()!=ApiType.ChangeZone||selected==null||!selected.isSpell()
            ||!selected.getHostCard().getName().equals(loopName)) return false;
        Card partner=find(partnerName,ZoneType.Battlefield);
        if(partner==null) return false;
        if(sa.getHostCard().getName().equals(KITTEN)&&"Exile".equals(sa.getParam("Destination"))) {
            Object cause=sa.getRootAbility().getTriggeringObject(forge.game.ability.AbilityKey.SpellAbility);
            if(!(cause instanceof SpellAbility cast)||cast.getActivatingPlayer()!=player
                ||cast.getHostCard()!=selected.getHostCard()||!target(sa,partner)) return false;
            System.err.println("CUBE_KITTEN_PLAN blink-partner turn="+turn+" route="+familyRoute+" target="+partner.getId());
            return true;
        }
        if(sa.getHostCard().getName().equals(partnerName)&&"Hand".equals(sa.getParam("Destination"))) {
            Card object=selected.getHostCard();
            if(object.getZone()==null||!object.getZone().is(ZoneType.Stack)||!target(sa,object)) return false;
            System.err.println("CUBE_KITTEN_PLAN return-loop turn="+turn+" card="+object.getName().replace(' ','_'));
            return true;
        }
        return false;
    }

    public boolean owns(SpellAbility sa) {return sa==selected;}
    public boolean play(SpellAbility sa) {
        libraryBefore=player.getCardsIn(ZoneType.Library).size();
        Card teferi=find(TEFERI,ZoneType.Battlefield);teferiBefore=teferi==null?-1:teferi.getGameTimestamp();
        Card partner=find(partnerName,ZoneType.Battlefield);partnerBefore=partner==null?-1:partner.getGameTimestamp();
        broadenColor(sa);
        boolean ok=reserveBlue(sa,()->ComputerUtil.handlePlayingSpellAbility(player,sa,null,current->new AiCostDecision(player,current,false)));
        if(ok&&!sa.isManaAbility()) pending=sa;
        if(!ok) {failedTurn=turn;active=false;}
        System.err.println("CUBE_KITTEN_PLAN "+(ok?"played":"native-payment-failed")+" turn="+turn+" card="+sa.getHostCard().getName()+" api="+sa.getApi()+" library="+libraryBefore);
        return ok;
    }
    public boolean waitingForOwnSpell() {
        var stack=player.getGame().getStack();if(!active||turn!=player.getGame().getPhaseHandler().getTurn()||stack.isEmpty()) return false;
        var top=stack.peekAbility();
        if(top==null||top.getActivatingPlayer()!=player) return false;
        // A family iteration puts the loop spell, Kitten's blink, the partner's
        // own return trigger and the outlet's spell-cast trigger on the stack in
        // one pass. All four are ours, and the plan waits for all four.
        if(familyRoute!=null) return true;
        return top.getHostCard().getName().equals(KITTEN)
            ||top.getHostCard().getName().equals(TEFERI)||top.getHostCard().getName().equals(ORACLE)
            ||top.getHostCard().getName().equals(rockName);
    }
}
