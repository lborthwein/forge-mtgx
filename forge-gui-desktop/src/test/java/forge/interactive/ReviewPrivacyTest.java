package forge.interactive;
import com.google.gson.*;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.game.ability.effects.CopyPermanentEffect;
import org.testng.annotations.Test;
import static org.testng.Assert.*;
public class ReviewPrivacyTest extends AITest {
 @Test public void zonelessHandMustFailClosed() {
  Game g=initAndCreateGame(); Player a=g.getRegisteredPlayers().get(0), b=g.getRegisteredPlayers().get(1);
  g.getAction().checkStateEffects(true); g.getTracker().freeze();
  try {
   Card c=addCardToZone("Ancestral Recall",b,ZoneType.Hand);
   assertNull(c.getView().getZone());
   assertNull(InteractiveGameEvents.publicIn(g,c.getView(),ZoneType.Hand,a.getView()));
   JsonObject v=InteractiveState.seatView(g,a,0);
   System.out.println("ZONELESS HAND: "+v);
   assertFalse(v.toString().contains("Ancestral Recall"),v.toString());
  } finally {g.getTracker().unfreeze();}
 }
 @Test public void faceDownOwnerMustGainNoFacts() {
  Game g=initAndCreateGame(); Player a=g.getRegisteredPlayers().get(0);
  Card c=addCard("Ainok Tracker",a); c.turnFaceDown(true); g.getAction().checkStateEffects(true);
  JsonObject v=InteractiveState.seatView(g,a,0);
  for(JsonElement p:v.getAsJsonArray("players")) for(JsonElement e:p.getAsJsonObject().getAsJsonArray("battlefield")) {
   JsonObject o=e.getAsJsonObject(); if(o.get("fid").getAsInt()==c.getId()) {
    System.out.println("OWN MORPH: "+o); assertFalse(o.has("isCopy"),o.toString());
   }
  }
 }
 @Test public void copiedTokenMustBeFlaggedAsCopy() {
  Game g=initAndCreateGame(); Player a=g.getRegisteredPlayers().get(0);
  Card c=addCard("Grizzly Bears",a);
  forge.game.spellability.SpellAbility sa=forge.game.ability.AbilityFactory.getAbility("AB$ CopyPermanent | Cost$ 0 | Defined$ Self",c);
  sa.setActivatingPlayer(a); new CopyPermanentEffect().resolve(sa);
  int copies=0;
  for(Card copy:a.getCardsIn(ZoneType.Battlefield)) if(copy.isToken()) {
   copies++; assertTrue(InteractiveCardFacts.isCopy(copy));
  }
  assertEquals(copies,1);
 }
}
