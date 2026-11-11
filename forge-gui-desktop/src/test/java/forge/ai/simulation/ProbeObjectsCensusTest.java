package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * sdcc-probeobj-1111: a replay of the census receipts. The cards below are those with a
 * {@code probe-fail ... why=not played} receipt in the 240 census games (run census-a; tokens left out), and the four
 * most frequent cards learned with no ability at all for a sacrifice cost. Each is learned in a bare position (lands in
 * play, a spread of card types in our library and graveyard, nothing on the opponent's side) with the key off and on.
 * Counted per ability: unplayable off, and of those playable on. Test data only.
 */
public class ProbeObjectsCensusTest extends AITest {

    static final String CARDS = "Abrade|Abrupt Decay|Aetherflux Reservoir|Aether Spellbomb|Animate Dead|Arbor Elf|"
            + "Ashiok, Nightmare Weaver|Batterskull|Bitter Triumph|Bloodtithe Harvester|Bone Shards|Boo|Boseiju, Who Endures|"
            + "Cathar Commando|Chandra, Torch of Defiance|Counterspell|Dack Fayden|Damn|Daretti, Ingenious Iconoclast|Daze|"
            + "Deathrite Shaman|Dismember|Duress|Eagles of the North|Elspeth, Knight-Errant|Elspeth, Sun's Champion|"
            + "Emry, Lurker of the Loch|Ephemerate|Fatal Push|Flame Slash|Force of Negation|Force of Will|Fractured Identity|"
            + "From the Catacombs|Galvanic Discharge|Garruk Wildspeaker|Get Lost|Giver of Runes|Glen Elendra Archmage|"
            + "Glimmer Lens|Goblin Welder|Go for the Throat|Grist, the Hunger Tide|Jace, Telepath Unbound|"
            + "Jace, the Mind Sculptor|Jace, Wielder of Mysteries|Kaito Shizuki|Kaldra Compleat|Karakas|"
            + "Kiki-Jiki, Mirror Breaker|Koth of the Hammer|Lavaspur Boots|Life // Death|Liliana, Death's Majesty|"
            + "Liliana of the Veil|Lion Sash|Loran of the Third Path|Maelstrom Pulse|Mana Drain|Mana Leak|Mana Tithe|"
            + "Memory Lapse|Miscalculation|Mizzium Mortars|Nettlecyst|Nissa, Ascended Animist|Nissa, Who Shakes the World|"
            + "No More Lies|Oko, Thief of Crowns|Oona's Prowler|Otawara, Soaring City|Oust|Path to Exile|Persist|Petty Theft|"
            + "Reanimate|Red Elemental Blast|Reflection of Kiki-Jiki|Regrowth|Remand|Reprieve|Saheeli, Sublime Artificer|"
            + "Scavenging Ooze|Sheoldred, the Apocalypse|Skullclamp|Snap|Snuff Out|Spell Pierce|Splinter Twin|Stern Scolding|"
            + "Strip Mine|Sword of the Meek|Swords to Plowshares|Tamiyo, Collector of Tales|Tear Asunder|"
            + "Teferi, Hero of Dominaria|The Scarab God|Thespian's Stage|The Wandering Emperor|Timeless Dragon|"
            + "Touch the Spirit Realm|Tough Cookie|Treachery|Trumpeting Carnosaur|Umezawa's Jitte|Unearth|Unholy Heat|"
            + "Utopia Sprawl|Vindicate|Wasteland|Wild Growth|Winds of Abandon|Woodfall Primus|Wrenn and Six|"
            + "Zealous Conscripts|Zirda, the Dawnwaker";
    /** Learned with no ability in the census ({@code hand=[] bf=[]}). */
    static final String EMPTY = "Tinker|Natural Order|Crop Rotation|Fire Covenant";

    static final String LIBRARY = "Grizzly Bears;Llanowar Elves;Memnite;Sol Ring;Seal of Fire;Forest;Island;Mountain;Swamp;"
            + "Plains;Lightning Bolt;Divination;Tropical Island;Serra Angel;Isamaru, Hound of Konda;Rancor";
    static final String GRAVEYARD = "Runeclaw Bear;Ponder;Wastes";

    private static String frame(final String card) {
        return ForcedWinSolverTest.frame(card, "Island;Mountain;Swamp;Forest;Plains", 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + LIBRARY)
                .replace("humangraveyard=", "humangraveyard=" + GRAVEYARD);
    }

    private static String key(final boolean bf, final Abil a) {
        return (bf ? "bf|" : "hand|") + a.kind + "|" + a.desc + "|" + a.targetVariant;
    }

    private static java.util.Map<String, Abil> abilities(final ACard t) {
        final java.util.Map<String, Abil> out = new java.util.LinkedHashMap<>();
        for (Abil a : t.fromHand) {
            out.putIfAbsent(key(false, a), a);
        }
        for (Abil a : t.onBattlefield) {
            out.putIfAbsent(key(true, a), a);
        }
        return out;
    }

    @Test
    public void receiptsReplay() {
        int cards = 0, failedOff = 0, learnedOn = 0, stillFailed = 0, withNeed = 0, usable = 0, missing = 0;
        int emptyOff = 0, emptyLearned = 0, stack = 0, loyalty = 0;
        final long t0 = System.nanoTime();
        int probesOff = 0, probesOn = 0;
        for (String name : (CARDS + "|" + EMPTY).split("\\|")) {
            final boolean empty = ("|" + EMPTY + "|").contains("|" + name + "|");
            final ACard off, on;
            try {
                final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame(name));
                final Player me = g.getPlayers().get(0);
                final Card c = ForcedWinSolverTest.inZone(me, ZoneType.Hand, name.contains(" // ") ? name.substring(0, name.indexOf(" // ")) : name);
                final Card card = c != null ? c : me.getCardsIn(ZoneType.Hand).isEmpty() ? null : me.getCardsIn(ZoneType.Hand).get(0);
                if (card == null) {
                    missing++;
                    System.out.println("PROBEOBJ-CENSUS " + name + " missing");
                    continue;
                }
                final ForcedWinModel.Reader r0 = new ForcedWinModel.Reader();
                off = r0.card(g, me, card);
                final ForcedWinModel.Reader r1 = new ForcedWinModel.Reader();
                r1.probeObjects = true;
                on = r1.card(g, me, card);
                probesOff += r0.probeStats.probes;
                probesOn += r1.probeStats.probes;
            } catch (RuntimeException | Error e) {
                missing++;
                System.out.println("PROBEOBJ-CENSUS " + name + " failed " + e);
                continue;
            }
            cards++;
            final java.util.Map<String, Abil> a0 = abilities(off), a1 = abilities(on);
            if (empty) {
                emptyOff += a0.isEmpty() ? 1 : 0;
                boolean any = false;
                for (Abil a : a1.values()) {
                    any |= !a.unplayable && a.obj != null;
                }
                emptyLearned += a0.isEmpty() && any ? 1 : 0;
                System.out.println("PROBEOBJ-CENSUS " + name + " off=" + a0.values() + " on=" + a1.values());
                continue;
            }
            for (java.util.Map.Entry<String, Abil> e : a0.entrySet()) {
                if (!e.getValue().unplayable) {
                    continue;
                }
                failedOff++;
                final Abil a = a1.get(e.getKey());
                final boolean learned = a != null && !a.unplayable;
                learnedOn += learned ? 1 : 0;
                stillFailed += learned ? 0 : 1;
                withNeed += learned && a.obj != null ? 1 : 0;
                usable += learned && a.obj != null && a.obj.anyUsable() ? 1 : 0;
                final Abil o = e.getValue();
                final String why = learned ? "" : off.counter && o.kind == ForcedWinModel.AbilKind.SPELL ? "stack-target"
                        : o.loyalty && o.loyaltyDelta < -off.startLoyalty ? "loyalty-short" : o.targetVariant != null ? "player-target"
                        : "other";
                stack += why.equals("stack-target") ? 1 : 0;
                loyalty += why.equals("loyalty-short") ? 1 : 0;
                System.out.println("PROBEOBJ-CENSUS " + name + " " + o.kind + (learned ? " LEARNED " + a : " STILL " + why + " "
                        + (a == null ? "gone" : a.costString() + " " + a.probeNote + (a.obj == null ? "" : " " + a.obj))));
            }
        }
        System.out.println("PROBEOBJ-CENSUS TOTAL cards=" + cards + " missing=" + missing + " abilitiesUnplayableOff=" + failedOff
                + " learnedOn=" + learnedOn + " stillUnplayable=" + stillFailed + " (stackTarget=" + stack + " loyaltyShort=" + loyalty + ") withNeed=" + withNeed + " plannerUsable=" + usable
                + " noAbilityOff=" + emptyOff + " ofThoseLearnedOn=" + emptyLearned + " probesOff=" + probesOff + " probesOn="
                + probesOn + " ms=" + (System.nanoTime() - t0) / 1_000_000);
        Assert.assertTrue(learnedOn > 0, "nothing learned");
        Assert.assertTrue(emptyLearned > 0, "no sacrifice-cost card learned");
    }
}
