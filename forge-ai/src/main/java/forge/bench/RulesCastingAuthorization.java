package forge.bench;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardPlayOption;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

/** One internal announcement's original-zone permission. No wire constructor,
 * ambient flag or fid-only identity match. Existing strict assess remains strict. */
final class RulesCastingAuthorization implements AutoCloseable {
    private final Player payer;
    private final Game game;
    private final SpellAbility original;
    private final Card source;
    private final Zone zone;
    private final int position;
    private final CardPlayOption permission;
    private Card moved;
    private boolean bound,closed;

    private RulesCastingAuthorization(Player payer,SpellAbility selected){
        this.payer=payer;game=payer.getGame();original=selected;source=selected.getHostCard();
        zone=game.getZoneOf(source);position=zone==null?-1:zone.getCards().indexOf(source);permission=selected.getMayPlayOption();
        if(selected.getActivatingPlayer()!=payer||source.getGame()!=game||zone==null||position<0
                ||zone.getCards().get(position)!=source||!selected.canPlay()
                ||!RulesCostFeasibility.requirePayable(payer,selected))fail("original-zone action is not authorized");
        if(permission!=null&&source.mayPlay(payer).stream().noneMatch(option->option==permission))
            fail("original-zone play option is not the exact live permission");
    }
    static RulesCastingAuthorization capture(Player payer,SpellAbility selected){return new RulesCastingAuthorization(payer,selected);}
    void bind(Card before,Card returned,SpellAbility actual){
        if(closed||bound||before!=source||actual!=original||returned==null||actual.getHostCard()!=returned
                ||returned.getGame()!=game||actual.getActivatingPlayer()!=payer||actual.getMayPlayOption()!=permission)
            fail("announcement transformation identity mismatch");
        if(actual.isSpell()){
            if(!returned.isInZone(ZoneType.Stack)||returned.getCastFrom()!=zone
                    ||zone.getCards().stream().anyMatch(card->card==source)
                    ||game.getStackZone().getCards().stream().noneMatch(card->card==returned))
                fail("announcement did not move exact authorized source");
        }else if(returned!=source||game.getZoneOf(returned)!=zone
                ||position>=zone.size()||zone.getCards().get(position)!=source)fail("activation source changed");
        moved=returned;bound=true;
    }
    void require(Player actor,SpellAbility actual){
        if(closed||!bound||actor!=payer||actor.getGame()!=game||actual!=original
                ||actual.getActivatingPlayer()!=payer||actual.getHostCard()!=moved||moved.getGame()!=game
                ||actual.getMayPlayOption()!=permission)fail("stale or unrelated announcement authorization");
        if(actual.isSpell()&&(!moved.isInZone(ZoneType.Stack)||moved.getCastFrom()!=zone
                ||game.getStackZone().getCards().stream().noneMatch(card->card==moved)))fail("announced source left exact stack zone");
        if(!actual.isSpell()&&(game.getZoneOf(moved)!=zone||position>=zone.size()
                ||zone.getCards().get(position)!=source))fail("activation source left original position");
    }
    @Override public void close(){closed=true;}
    private static void fail(String reason){throw new RulesCostFeasibility.Unsupported(reason);}
}
