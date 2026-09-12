package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.lang.reflect.Proxy;
import java.util.*;

/** v73 reanimator SELECTION fixture. Finite native games over prepared boards:
 * every cast, search, discard, target and trigger is native, and the only thing
 * under test is which card a selection takes.
 *
 * <p>Rows, and what each one separates:</p>
 * <ul>
 * <li><b>entomb-stable</b> - Entomb plus an Aura reanimation in hand and
 *     Emrakul, Ashen Rider and Griselbrand in the library. Default's chooser is
 *     {@code getBestAI} over a whole library, which falls through to
 *     {@code getMostExpensivePermanentAI} - a plain max(mana value) - and lands
 *     on Emrakul, whose own trigger shuffles the graveyard away. v73 must entomb
 *     a body that STAYS. Which one is RECORDED, not equated.</li>
 * <li><b>entomb-no-spell</b> - the same board with no reanimation card at all.
 *     MUST-NOT-MOVE: the plan must take no action and change no selection.</li>
 * <li><b>loot-payload</b> - our own loot outlet, a reanimation spell and a
 *     payload in hand, and one spare land. Default's discard ranking reaches
 *     lands BEFORE "unplayable by CMC", so it pitches the land; v73 pitches the
 *     payload. The spare land is what makes this a real differential rather
 *     than census 6.3's incidental CMC discard.</li>
 * <li><b>loot-no-spell</b> - the same board with no reanimation spell.
 *     MUST-NOT-MOVE.</li>
 * <li><b>target-value</b> - Reanimate with Ashen Rider and a value-free body of
 *     HIGHER mana value in our graveyard. Default takes the expensive one;
 *     v73 takes the one whose printed triggers change the board.</li>
 * <li><b>exhume-symmetry</b> - the same shape, but Exhume, and the opponent's
 *     PUBLIC graveyard holds a better body than ours. v73 must DECLINE to
 *     improve our half: Exhume gives them theirs too.</li>
 * <li><b>persist-legendary</b> - Persist's printed
 *     {@code Creature.nonLegendary+YouOwn} with a legend as our best body. The
 *     legend must never be taken, and the ranking must still prefer the
 *     value body over the expensive one.</li>
 * <li><b>entomb-sequence</b> - Entomb and Reanimate with mana for both: search
 *     in our own main and convert, measured as a board swing.</li>
 * <li><b>entomb-endstep</b> - the same hand with mana for ONE. The search must
 *     be HELD to the opponent's end step, which is the sequencing decision
 *     Default does not attempt at all.</li>
 * <li><b>ordinary-parity</b> - no reanimation card anywhere. PARITY.</li>
 * </ul>
 *
 * <p>v76 appends seven AURA-form rows. The aura form's target is chosen in
 * {@code AttachAi.attachAIReanimatePreference} by
 * {@code ComputerUtilCard.getBestCreatureAI} - a BODY SCORE - which is census
 * section 6.4 and v73's registered limitation 1. Appended, so every v73 row
 * keeps its name and therefore its {@code BenchRandomAudit} seed.</p>
 * <ul>
 * <li><b>aura-value</b> - Animate Dead with Ashen Rider and a value-free body
 *     of HIGHER mana value and a higher body score in OUR graveyard. The
 *     control takes the 9/9; v76 takes the one whose printed triggers change
 *     the board.</li>
 * <li><b>aura-necromancy</b> - the same board with Necromancy. RECORDED, not
 *     equated: Necromancy is NOT an Aura on the stack and carries no
 *     {@code AttachAILogic}; its target is its ETB trigger's targeted
 *     {@code RaiseDead}, which is v73's spell-form hook. The row measures that
 *     correction to census 6.4 rather than asserting it from a read.</li>
 * <li><b>aura-opponent-grave</b> - our graveyard holds only the value-free
 *     body and the opponent's PUBLIC graveyard holds the value one. Animate
 *     Dead enchants a creature card in ANY graveyard, so v76 takes theirs -
 *     strictly better, and denied to them.</li>
 * <li><b>aura-single</b> - one legal target. MUST-NOT-MOVE: a single candidate
 *     is not a choice.</li>
 * <li><b>aura-agree</b> - two tier-3 bodies. The ranking AGREES with the
 *     ordinary answer and must return null rather than "change" to the same
 *     card. MUST-NOT-MOVE.</li>
 * <li><b>aura-worldgorger</b> - the lock piece beside a real payload. Animate
 *     Dead's printed {@code AttachAITgts:Creature.!namedWorldgorger Dragon} is
 *     applied by {@code ComputerUtil.filterAITgts} BEFORE the list the plan
 *     ranks, so BOTH arms must leave it alone. MUST-NOT-MOVE.</li>
 * <li><b>portal-value</b> / <b>portal-reverse</b> - Portal to Phyrexia on our
 *     battlefield and one candidate in EACH graveyard, then the two swapped.
 *     Its upkeep trigger is a PHASE trigger whose {@code TrigChange} is a
 *     targeted {@code Graveyard -> Battlefield} {@code ValidTgts$ Creature} with
 *     {@code GainControl$ True} - a THIRD entry shape into v73's
 *     {@code ChangeZoneAi.isPreferredTarget} hook, after a spell and after
 *     Necromancy's ChangesZone trigger, and the first one no fixture covered.
 *     The value ranking must take the tier-3 body from WHICHEVER graveyard holds
 *     it, including the opponent's public one. Both arms, because the hook is
 *     v73's.</li>
 * <li><b>portal-single</b> - the same engine with ONE legal target anywhere.
 *     PARITY: a single candidate is not a choice, so the ranking declines and
 *     the ordinary answer stands.</li>
 * <li><b>aura-shuffler</b> - Emrakul and a Grizzly Bears in our graveyard. The
 *     aura form must NOT reuse v73's {@code staysInGraveyard} refusal: the card
 *     is already in a graveyard and the aura is taking it OUT. Both arms must
 *     return EMRAKUL; a plan that reused {@code payloadTier} would reanimate
 *     the Bears. MUST-NOT-MOVE.</li>
 * </ul>
 *
 * <p>v77 appends three SCOPE rows. v73 hooked
 * {@code ChangeZoneAi.isPreferredTarget} AFTER the
 * {@code origin.contains(Battlefield) ? getBestRemovalTargetAI :
 * getMostExpensivePermanentAI} ternary, so its {@code payloadTier} ranking also
 * re-ranked BOUNCE and EXILE-REMOVAL targets. The v74 pooled read measured
 * eleven such changed picks, every one from Jace, the Mind Sculptor's
 * {@code -1}. Appended, so every v73 and v76 row keeps its name and its
 * {@code BenchRandomAudit} seed.</p>
 * <ul>
 * <li><b>bounce-jace</b> - Jace, the Mind Sculptor on our battlefield at its
 *     printed loyalty behind two vanilla 0/8 defenders, and exactly Tough
 *     Cookie and Vorinclex on the opponent's. The {@code -1} is {@code Origin$
 *     Battlefield | Destination$ Hand}, which is not
 *     {@code reanimationShape}, so the plan must take no action:
 *     MUST-NOT-MOVE, and the pick must equal the Default arm's.</li>
 * <li><b>exile-swords</b> - the same board with Swords to Plowshares in hand,
 *     {@code Origin$ Battlefield | Destination$ Exile}. The SECOND shape that
 *     reaches the same native chooser. MUST-NOT-MOVE.</li>
 * <li><b>bounce-reanimate</b> - both decisions on ONE board: Jace beside
 *     Reanimate, with a value body and a more expensive value-free body in our
 *     graveyard. The reanimation must still be ranked by the value terms and
 *     the bounce beside it must not be, which is the separation the gate
 *     makes.</li>
 * </ul>
 *
 * <p>Assertions are gated on {@code -Dforge.test.requireReanimator} so the
 * identical source runs unasserted against the matched pre-v73 classes as a
 * control; those classes have no such counters and each reports -1.</p> */
public final class CubeReanimatorSmoke {
    private record Entry(String name, ZoneType zone) {}
    private static final Set<String> loaded = new HashSet<>();
    private static final String ENTOMB = "Entomb", REANIMATE = "Reanimate", ANIMATE = "Animate Dead",
        EXHUME = "Exhume", PERSIST = "Persist", LOOTER = "Jace, Vryn's Prodigy",
        ASHEN = "Ashen Rider", GRISELBRAND = "Griselbrand", EMRAKUL = "Emrakul, the Aeons Torn",
        TITAN = "Grave Titan", ARCHON = "Archon of Cruelty", FATTY = "Bygone Colossus",
        BEARS = "Grizzly Bears", SWAMP = "Swamp", FOREST = "Forest",
        // v76 aura form.
        NECROMANCY = "Necromancy", WORLDGORGER = "Worldgorger Dragon",
        // v76 addendum: the standing trigger engine.
        PORTAL = "Portal to Phyrexia",
        // v77 scope rows. The bounce source is the one the v74 pooled read
        // measured (Jace, the Mind Sculptor's -1); the two bodies are the pair
        // that read disagreed on, and the exile-removal source is the
        // Swords-class shape that reaches the SAME native chooser.
        JACE_TMS = "Jace, the Mind Sculptor", SWORDS = "Swords to Plowshares",
        COOKIE = "Tough Cookie", VORINCLEX = "Vorinclex", PLAINS = "Plains",
        WALL = "Wall of Stone";
    /** v77. The rows whose opponent board is the CONTROLLED removal-target
     * pair rather than the shared three Grizzly Bears. Every pre-v77 row keeps
     * the shared board, byte for byte. */
    private static final List<String> V77_SCOPE =
        List.of("bounce-jace", "exile-swords", "bounce-reanimate");
    private static final List<String> MUST_MOVE =
        List.of("entomb-stable", "loot-payload", "target-value", "persist-legendary",
                "entomb-sequence", "entomb-endstep",
                "aura-value", "aura-opponent-grave",
                "portal-value", "portal-reverse",
                // v77: the reanimation half of the mixed board must still rank.
                "bounce-reanimate");
    private static final List<String> MUST_NOT_MOVE =
        List.of("entomb-no-spell", "loot-no-spell", "ordinary-parity",
                "aura-single", "aura-agree", "aura-worldgorger", "aura-shuffler",
                "portal-single",
                // v77. A BOUNCE and an EXILE removal are not reanimation, so
                // the plan must take no action and change no selection on
                // either board. Under v76 both of these rows MOVE -- that is
                // the defect, and the matched control measures it.
                "bounce-jace", "exile-swords");

    /** -1 means the classes under test have no such counter at all, which is
     * what the matched pre-v73 control arm reports. The plan class is resolved
     * by NAME rather than by a compile-time reference: the control compiles this
     * same source against probe-381's frozen v70 classes, where the class does
     * not exist at all, so a `.class` literal would not link there. */
    private static Class<?> plan;
    private static int counter(String name, boolean reset) {
        try {
            if (plan == null) {
                try { plan = Class.forName("forge.ai.CubeReanimatorPlan"); }
                catch (ClassNotFoundException preV73) { return -1; }
            }
            java.lang.reflect.Field field;
            try { field = plan.getDeclaredField(name); }
            catch (NoSuchFieldException absent) { return -1; }
            field.setAccessible(true);
            int value = field.getInt(null);
            if (reset) field.setInt(null, 0);
            return value;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static int[] counters(boolean reset) {
        return new int[] {counter("planActions", reset), counter("entombSelections", reset),
                counter("discardSwaps", reset), counter("targetChanges", reset),
                counter("symmetryDeclines", reset), counter("holds", reset),
                // v76. -1 against the pre-v76 control classes, which have the
                // plan class but not this field: the designed no-such-field
                // signal, one step finer than v73's no-such-class one.
                counter("auraTargets", reset)};
    }
    private static forge.item.PaperCard paper(String name) {
        if (loaded.add(name)) StaticData.instance().attemptToLoadCard(name);
        return Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), name);
    }

    /** Our own board. Every zone is written directly, so the library order is
     * the insertion order until an effect shuffles it. */
    private static List<Entry> layout(String control) {
        List<Entry> cards = new ArrayList<>();
        switch (control) {
            case "entomb-stable" -> {
                cards.add(new Entry(ENTOMB, ZoneType.Hand));
                cards.add(new Entry(ANIMATE, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(EMRAKUL, ZoneType.Library));
                cards.add(new Entry(ASHEN, ZoneType.Library));
                cards.add(new Entry(GRISELBRAND, ZoneType.Library));
            }
            case "entomb-no-spell" -> {
                cards.add(new Entry(ENTOMB, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(EMRAKUL, ZoneType.Library));
                cards.add(new Entry(ASHEN, ZoneType.Library));
                cards.add(new Entry(GRISELBRAND, ZoneType.Library));
            }
            case "loot-payload", "loot-no-spell" -> {
                cards.add(new Entry(LOOTER, ZoneType.Battlefield));
                if (control.equals("loot-payload")) cards.add(new Entry(REANIMATE, ZoneType.Hand));
                cards.add(new Entry(ASHEN, ZoneType.Hand));
                cards.add(new Entry(SWAMP, ZoneType.Hand));
                for (int i = 0; i < 6; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
            }
            case "target-value" -> {
                cards.add(new Entry(REANIMATE, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(FATTY, ZoneType.Graveyard));
                cards.add(new Entry(ASHEN, ZoneType.Graveyard));
            }
            case "exhume-symmetry" -> {
                cards.add(new Entry(EXHUME, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(FATTY, ZoneType.Graveyard));
                cards.add(new Entry(GRISELBRAND, ZoneType.Graveyard));
            }
            case "persist-legendary" -> {
                cards.add(new Entry(PERSIST, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(GRISELBRAND, ZoneType.Graveyard));
                cards.add(new Entry(FATTY, ZoneType.Graveyard));
                cards.add(new Entry(TITAN, ZoneType.Graveyard));
            }
            case "entomb-sequence" -> {
                cards.add(new Entry(ENTOMB, ZoneType.Hand));
                cards.add(new Entry(REANIMATE, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(EMRAKUL, ZoneType.Library));
                cards.add(new Entry(ASHEN, ZoneType.Library));
            }
            case "entomb-endstep" -> {
                cards.add(new Entry(ENTOMB, ZoneType.Hand));
                cards.add(new Entry(REANIMATE, ZoneType.Hand));
                cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(EMRAKUL, ZoneType.Library));
                cards.add(new Entry(ASHEN, ZoneType.Library));
            }
            // ------------------------------------------------ v76 aura rows
            case "aura-value" -> {
                cards.add(new Entry(ANIMATE, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(FATTY, ZoneType.Graveyard));
                cards.add(new Entry(ASHEN, ZoneType.Graveyard));
            }
            case "aura-necromancy" -> {
                cards.add(new Entry(NECROMANCY, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(FATTY, ZoneType.Graveyard));
                cards.add(new Entry(ASHEN, ZoneType.Graveyard));
            }
            // Our graveyard holds ONLY the value-free body; the payload is in
            // the opponent's PUBLIC graveyard (see opposing()).
            case "aura-opponent-grave" -> {
                cards.add(new Entry(ANIMATE, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(FATTY, ZoneType.Graveyard));
            }
            case "aura-single" -> {
                cards.add(new Entry(ANIMATE, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(ASHEN, ZoneType.Graveyard));
            }
            case "aura-agree" -> {
                cards.add(new Entry(ANIMATE, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(ASHEN, ZoneType.Graveyard));
                cards.add(new Entry(ARCHON, ZoneType.Graveyard));
            }
            case "aura-worldgorger" -> {
                cards.add(new Entry(ANIMATE, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(WORLDGORGER, ZoneType.Graveyard));
                cards.add(new Entry(ASHEN, ZoneType.Graveyard));
            }
            case "aura-shuffler" -> {
                cards.add(new Entry(ANIMATE, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(EMRAKUL, ZoneType.Graveyard));
                cards.add(new Entry(BEARS, ZoneType.Graveyard));
            }
            // ------------------------------------- v76 addendum: Portal rows
            // The engine is placed directly on our battlefield, so its ETB
            // ("each opponent sacrifices three creatures") never fires and the
            // only thing under test is the UPKEEP trigger's target.
            case "portal-value" -> {
                cards.add(new Entry(PORTAL, ZoneType.Battlefield));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(ASHEN, ZoneType.Graveyard));
            }
            case "portal-reverse" -> {
                cards.add(new Entry(PORTAL, ZoneType.Battlefield));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(FATTY, ZoneType.Graveyard));
            }
            case "portal-single" -> {
                cards.add(new Entry(PORTAL, ZoneType.Battlefield));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(ASHEN, ZoneType.Graveyard));
            }
            // ------------------------------------------- v77 scope rows
            // The bounce engine is placed directly on our battlefield with its
            // printed starting loyalty, so the only decision under test is
            // which creature the -1 returns.
            // The two vanilla 0/8 defenders are what keep Jace ALIVE long
            // enough to spend the -1: probe-1 measured that with an open board
            // the ordinary AI takes the +2 on turn 1 and Jace is dead to the
            // 6/6 before a bounce is ever offered, so the row was inert. They
            // are our own permanents, and the -1's candidate list is filtered
            // to the OPPONENT'S creatures, so they cannot enter the decision
            // under test.
            case "bounce-jace" -> {
                cards.add(new Entry(JACE_TMS, ZoneType.Battlefield));
                for (int i = 0; i < 2; i++) cards.add(new Entry(WALL, ZoneType.Battlefield));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
            }
            case "exile-swords" -> {
                cards.add(new Entry(SWORDS, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(PLAINS, ZoneType.Battlefield));
            }
            // Both decisions on ONE board: the reanimation must still be ranked
            // by the value terms, and the bounce beside it must not be.
            case "bounce-reanimate" -> {
                cards.add(new Entry(JACE_TMS, ZoneType.Battlefield));
                cards.add(new Entry(REANIMATE, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
                cards.add(new Entry(FATTY, ZoneType.Graveyard));
                cards.add(new Entry(ASHEN, ZoneType.Graveyard));
            }
            default -> { // ordinary-parity
                cards.add(new Entry(BEARS, ZoneType.Hand));
                for (int i = 0; i < 4; i++) cards.add(new Entry(SWAMP, ZoneType.Battlefield));
            }
        }
        while (cards.size() < 40) cards.add(new Entry(FOREST, ZoneType.Library));
        return cards;
    }

    /** The opponent's board. Three PUBLIC permanents wherever a printed
     * enter-the-battlefield value trigger has to have something to resolve
     * against; a PUBLIC graveyard threat only for the symmetry row. */
    private static List<Entry> opposing(String control) {
        List<Entry> cards = new ArrayList<>();
        // v77. The scope rows need a CONTROLLED removal-target list, so they
        // replace the shared three-Bears board with exactly the pair the v74
        // pooled read disagreed on: Tough Cookie, whose printed enters-trigger
        // makes a Food token and so scores payloadTier 3, and Vorinclex, a 6/6
        // trample/reach whose enters-trigger is a Library -> Hand fetch and so
        // scores payloadTier 1. getBestRemovalTargetAI ranks them the other way
        // round, which is precisely the disagreement under test. Returning here
        // leaves every pre-v77 row's board untouched.
        if (V77_SCOPE.contains(control)) {
            cards.add(new Entry(COOKIE, ZoneType.Battlefield));
            cards.add(new Entry(VORINCLEX, ZoneType.Battlefield));
            for (int i = 0; i < 20; i++) cards.add(new Entry(FOREST, ZoneType.Library));
            while (cards.size() < 40) cards.add(new Entry(FOREST, ZoneType.Graveyard));
            return cards;
        }
        for (int i = 0; i < 3; i++) cards.add(new Entry(BEARS, ZoneType.Battlefield));
        if (control.equals("exhume-symmetry")) cards.add(new Entry(ARCHON, ZoneType.Graveyard));
        // v76: the one row whose payload is in the opponent's PUBLIC graveyard.
        if (control.equals("aura-opponent-grave")) cards.add(new Entry(ASHEN, ZoneType.Graveyard));
        // v76 addendum. portal-value puts the value-free body in the PUBLIC
        // graveyard and the payload in ours; portal-reverse swaps them, so the
        // ranking has to reach across into the opponent's graveyard to be right.
        // portal-single leaves this graveyard creature-free on purpose.
        if (control.equals("portal-value")) cards.add(new Entry(FATTY, ZoneType.Graveyard));
        if (control.equals("portal-reverse")) cards.add(new Entry(ASHEN, ZoneType.Graveyard));
        for (int i = 0; i < 20; i++) cards.add(new Entry(FOREST, ZoneType.Library));
        while (cards.size() < 40) cards.add(new Entry(FOREST, ZoneType.Graveyard));
        return cards;
    }

    private static Deck deck(List<Entry> entries) {
        Deck deck = new Deck();
        for (Entry e : entries) deck.getMain().add(paper(e.name()), 1);
        return deck;
    }
    private static void populate(Player player, List<Entry> entries) { populate(player, entries, ""); }
    /** v77. {@code control} is passed so the ONE new setup step -- a
     * planeswalker put straight onto the battlefield needs its printed starting
     * loyalty, which the zone write does not supply -- is gated on the rows
     * that introduce one. No pre-v77 row plays a planeswalker, so the gate is
     * provably inert for them rather than merely inert in fact. */
    private static void populate(Player player, List<Entry> entries, String control) {
        for (Entry e : entries) {
            Card c = Card.fromPaperCard(paper(e.name()), player);
            c.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(e.zone()).add(c);
            c.setSickness(false);
            if (V77_SCOPE.contains(control) && e.zone() == ZoneType.Battlefield && c.isPlaneswalker()) {
                String printed = c.getCurrentState().getBaseLoyalty();
                c.setCounters(forge.game.card.CounterEnumType.LOYALTY,
                    printed == null || printed.isEmpty() ? 0 : Integer.parseInt(printed));
            }
        }
    }
    private static String zoneOf(Player player, String name) {
        for (ZoneType zone : List.of(ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Hand,
                ZoneType.Library, ZoneType.Exile)) {
            for (Card c : player.getCardsIn(zone)) if (c.getName().equals(name)) return zone.name();
        }
        return "missing";
    }

    /** v76. The card our reanimation AURA is attached to - the DIRECT witness
     * of the decision under test. Needed because Animate Dead's own
     * reanimation is {@code Defined$ Enchanted}, i.e. UNTARGETED, so v73's
     * {@code returned=} set (built from {@code sa.getTargets()}) is empty on
     * every Animate Dead row. Necromancy's {@code RaiseDead} IS targeted, so
     * that row populates both fields and they cross-check each other. */
    private static String auraOn(Player player) {
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            if (!c.getName().equals(ANIMATE) && !c.getName().equals(NECROMANCY)) continue;
            Card host = c.getAttachedTo();
            return host == null ? "unattached" : host.getName().replace(' ', '_');
        }
        return "none";
    }

    private static void run(int seat, String control, boolean candidate) {
        List<Entry> own = layout(control), other = opposing(control);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat ? own : other)).setPlayer(candidate && s == seat
            ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s) : GamePlayerUtil.createAiPlayer("Default-" + s, s, 0, null, "Default")));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "Reanimator selection native fixture").createGame();
        game.setAge(GameStage.Play);
        Player p = game.getPlayers().get(seat), opp = game.getPlayers().get(1 - seat);
        game.getPhaseHandler().setupFirstTurn(p, () -> game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p));
        populate(p, own, control); populate(opp, other, control);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(97900 + seat * 100 + control.length());
        counters(true);
        String key = "seat=" + seat + " control=" + control;
        System.out.println("REANIMATOR_FIXTURE " + key + " candidate=" + candidate
            + " policy=" + forge.ai.CubeComboAi.VERSION + " oppPermanents=" + opp.getCardsIn(ZoneType.Battlefield).size());
        Set<Integer> seen = new HashSet<>();
        Set<String> graveyardSeen = new TreeSet<>(), returned = new TreeSet<>();
        int steps = 0, searches = 0, reanimations = 0, discards = 0, searchTurn = -1;
        String searchPhase = "none";
        for (Card c : p.getCardsIn(ZoneType.Graveyard)) graveyardSeen.add(c.getName());
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= 4 && steps < 900) {
            steps++; game.getPhaseHandler().mainLoopStep();
            for (Card c : p.getCardsIn(ZoneType.Graveyard)) graveyardSeen.add(c.getName());
            for (var item : game.getStack()) if (seen.add(item.getId())) {
                var sa = item.getSpellAbility();
                if (sa.getActivatingPlayer() != p) continue;
                String name = sa.getHostCard().getName();
                boolean toGraveyard = sa.getApi() == forge.game.ability.ApiType.ChangeZone
                        && "Library".equals(sa.getParam("Origin")) && "Graveyard".equals(sa.getParam("Destination"));
                boolean fromGraveyard = sa.getApi() == forge.game.ability.ApiType.ChangeZone
                        && "Graveyard".equals(sa.getParam("Origin")) && "Battlefield".equals(sa.getParam("Destination"));
                if (toGraveyard) {
                    searches++;
                    if (searchTurn < 0) {
                        searchTurn = game.getPhaseHandler().getTurn();
                        searchPhase = game.getPhaseHandler().getPhase()
                                + (game.getPhaseHandler().isPlayerTurn(p) ? ":own" : ":opponent");
                    }
                }
                if (fromGraveyard) {
                    reanimations++;
                    for (Card t : sa.getTargets().getTargetCards()) returned.add(t.getName());
                }
                if (sa.getApi() == forge.game.ability.ApiType.Discard) discards++;
                System.out.println("REANIMATOR_STACK step=" + steps + " id=" + item.getId() + " card=" + name
                    + " api=" + sa.getApi() + " targets=" + sa.getTargets());
            }
        }
        int[] c = counters(true);
        System.out.println("REANIMATOR_RESULT " + key
            + " planActions=" + c[0] + " entombSelections=" + c[1] + " discardSwaps=" + c[2] + " targetChanges=" + c[3]
            + " symmetryDeclines=" + c[4] + " holds=" + c[5] + " auraTargets=" + c[6]
            + " searches=" + searches + " searchTurn=" + searchTurn + " searchPhase=" + searchPhase
            + " reanimations=" + reanimations + " discards=" + discards
            + " graveyardSeen=[" + String.join(";", graveyardSeen).replace(' ', '_') + "]"
            + " returned=[" + String.join(";", returned).replace(' ', '_') + "]"
            + " ashen=" + zoneOf(p, ASHEN) + " emrakul=" + zoneOf(p, EMRAKUL)
            + " griselbrand=" + zoneOf(p, GRISELBRAND) + " titan=" + zoneOf(p, TITAN)
            + " fatty=" + zoneOf(p, FATTY) + " auraOn=" + auraOn(p)
            // v77. The DIRECT witnesses of the two decisions the scope fix is
            // about, read on the OPPONENT'S side because that is whose
            // permanents a bounce and an exile removal move. `missing` on every
            // pre-v77 row, which is what makes them inert additions.
            + " oppCookie=" + zoneOf(opp, COOKIE) + " oppVorinclex=" + zoneOf(opp, VORINCLEX)
            + " swords=" + zoneOf(p, SWORDS) + " jace=" + zoneOf(p, JACE_TMS)
            + " oppPermanents=" + opp.getCardsIn(ZoneType.Battlefield).size()
            + " oppExile=" + opp.getCardsIn(ZoneType.Exile).size()
            + " life=" + p.getLife() + " opponentLife=" + opp.getLife()
            + " steps=" + steps + " budgetExhausted=" + (steps >= 900) + " outcome=" + game.getOutcome());
        if (!candidate || !Boolean.getBoolean("forge.test.requireReanimator")) return;

        // MUST-NOT-MOVE: no action of ANY kind, not merely no win.
        if (MUST_NOT_MOVE.contains(control)
                && (c[0] != 0 || c[1] != 0 || c[2] != 0 || c[3] != 0 || c[4] != 0 || c[5] != 0 || c[6] != 0))
            throw new AssertionError("Reanimator control moved: " + key + " counters=" + Arrays.toString(c));
        switch (control) {
            // The payload is RECORDED, not named: the rule under test is the
            // refusal, and both Ashen Rider and Griselbrand satisfy it.
            case "entomb-stable" -> {
                if (c[1] < 1 || graveyardSeen.contains(EMRAKUL)) throw new AssertionError("Entomb took a shuffle-away body: " + key);
                if (!graveyardSeen.contains(ASHEN) && !graveyardSeen.contains(GRISELBRAND))
                    throw new AssertionError("Entomb took no stable payload: " + key);
            }
            case "loot-payload" -> {
                if (c[2] < 1 || zoneOf(p, ASHEN).equals("Hand"))
                    throw new AssertionError("Looting kept the payload in hand: " + key);
            }
            case "target-value" -> {
                if (c[3] < 1 || !returned.contains(ASHEN) || returned.contains(FATTY))
                    throw new AssertionError("Reanimate took the expensive body: " + key + " returned=" + returned);
            }
            // The symmetry refusal: the plan must change NOTHING here, and the
            // ordinary answer must stand.
            case "exhume-symmetry" -> {
                if (c[3] != 0) throw new AssertionError("Exhume improved our half into a public threat: " + key);
                // The refusal must be WITNESSED, not merely absent: the plan had
                // a better answer and declined it.
                if (c[4] < 1) throw new AssertionError("Exhume symmetry was never reached: " + key);
            }
            case "persist-legendary" -> {
                if (returned.contains(GRISELBRAND) || zoneOf(p, GRISELBRAND).equals("Battlefield"))
                    throw new AssertionError("Persist took a legend: " + key);
                if (c[3] < 1 || !returned.contains(TITAN))
                    throw new AssertionError("Persist did not take the value body: " + key + " returned=" + returned);
            }
            // The swing is read from the OPPONENT'S EXILE zone, not from their
            // permanent count: they keep making land drops, so the count grows
            // even while Ashen Rider's enter-the-battlefield trigger is exiling
            // one of their permanents. Exile is the direct, unambiguous witness
            // that the reanimated body's printed trigger resolved against them.
            case "entomb-sequence" -> {
                if (c[0] < 1 || reanimations < 1 || opp.getCardsIn(ZoneType.Exile).isEmpty())
                    throw new AssertionError("Entomb -> Reanimate produced no board swing: " + key);
            }
            case "entomb-endstep" -> {
                if (c[0] < 1 || searches < 1 || c[5] < 1)
                    throw new AssertionError("No held search at all: " + key + " counters=" + Arrays.toString(c));
                if (!searchPhase.equals("END_OF_TURN:opponent"))
                    throw new AssertionError("Search was not held to the opponent's end step: " + key
                        + " searchPhase=" + searchPhase);
            }
            // ------------------------------------------------ v76 aura rows
            case "aura-value" -> {
                if (c[6] < 1 || !auraOn(p).equals("Ashen_Rider"))
                    throw new AssertionError("Aura took the body score: " + key + " auraOn=" + auraOn(p));
            }
            // RECORDED: the counter is NOT asserted, because the correction this
            // row measures is that Necromancy's target is v73's spell-form hook,
            // not the aura one. The OUTCOME is asserted.
            case "aura-necromancy" -> {
                if (auraOn(p).equals("Bygone_Colossus") || returned.contains(FATTY))
                    throw new AssertionError("Necromancy took the body score: " + key + " auraOn=" + auraOn(p));
            }
            case "aura-opponent-grave" -> {
                if (c[6] < 1 || !auraOn(p).equals("Ashen_Rider"))
                    throw new AssertionError("Aura ignored the public graveyard: " + key + " auraOn=" + auraOn(p));
            }
            // One legal candidate is not a choice; the ordinary answer stands.
            case "aura-single" -> {
                if (!auraOn(p).equals("Ashen_Rider"))
                    throw new AssertionError("Single-target aura row moved: " + key + " auraOn=" + auraOn(p));
            }
            // The ranking agrees with the ordinary answer and must say so by
            // returning null, not by "changing" to the same card.
            case "aura-agree" -> {
                if (!auraOn(p).equals("Archon_of_Cruelty"))
                    throw new AssertionError("Aura disagreed on an agreed board: " + key + " auraOn=" + auraOn(p));
            }
            // The lock piece is removed by the aura's OWN printed AttachAITgts
            // before the plan sees the list.
            case "aura-worldgorger" -> {
                if (auraOn(p).equals("Worldgorger_Dragon"))
                    throw new AssertionError("Aura took the lock piece: " + key);
                if (!auraOn(p).equals("Ashen_Rider"))
                    throw new AssertionError("Aura took no payload at all: " + key + " auraOn=" + auraOn(p));
            }
            // The aura form must NOT reuse v73's staysInGraveyard refusal.
            case "aura-shuffler" -> {
                if (!auraOn(p).equals("Emrakul,_the_Aeons_Torn"))
                    throw new AssertionError("Aura refused a body that is ALREADY in the graveyard: "
                        + key + " auraOn=" + auraOn(p));
            }
            // ------------------------------------- v76 addendum: Portal rows
            // v73's hook, reached from a PHASE trigger. The counter asserted is
            // targetChanges (c[3]), NOT auraTargets: Portal to Phyrexia is not
            // an Aura and never reaches AttachAi.
            case "portal-value", "portal-reverse" -> {
                if (c[3] < 1 || !returned.contains(ASHEN) || returned.contains(FATTY))
                    throw new AssertionError("Portal took the expensive body: " + key + " returned=" + returned);
                if (!zoneOf(p, ASHEN).equals("Battlefield"))
                    throw new AssertionError("Portal returned nothing: " + key);
            }
            case "portal-single" -> {
                if (!returned.contains(ASHEN) || !zoneOf(p, ASHEN).equals("Battlefield"))
                    throw new AssertionError("Portal's single legal target was not taken: " + key
                        + " returned=" + returned);
            }
            // ------------------------------------------- v77 scope rows
            // The two MUST-NOT-MOVE rows are already covered by the blanket
            // all-counters-zero check above; what is asserted here is the
            // OUTCOME, so a build that suppressed the receipt while still
            // changing the pick would be caught.
            //
            // Tough Cookie must NOT be the one taken: it is payloadTier 3 and
            // Vorinclex is payloadTier 1, so v73's ranking prefers it, while
            // getBestRemovalTargetAI -- the chooser that owns this decision --
            // prefers the 6/6. The row does not assert WHICH card Default's
            // chooser takes; it asserts that the plan did not substitute its
            // own, and readout-v77 equates the pick against the Default arm and
            // against the matched control.
            case "bounce-jace" -> {
                // The row must not go inert: a -1 that never fires proves
                // nothing about the target it would have taken.
                if (!zoneOf(opp, COOKIE).equals("Hand") && !zoneOf(opp, VORINCLEX).equals("Hand"))
                    throw new AssertionError("No bounce was ever made: " + key);
                if (zoneOf(opp, COOKIE).equals("Hand"))
                    throw new AssertionError("The reanimation ranking chose a BOUNCE target: "
                        + key + " oppCookie=" + zoneOf(opp, COOKIE) + " oppVorinclex=" + zoneOf(opp, VORINCLEX));
            }
            case "exile-swords" -> {
                if (!zoneOf(opp, COOKIE).equals("Exile") && !zoneOf(opp, VORINCLEX).equals("Exile"))
                    throw new AssertionError("No exile removal was ever cast: " + key);
                if (zoneOf(opp, COOKIE).equals("Exile"))
                    throw new AssertionError("The reanimation ranking chose an EXILE-REMOVAL target: "
                        + key + " oppCookie=" + zoneOf(opp, COOKIE) + " oppVorinclex=" + zoneOf(opp, VORINCLEX));
            }
            // The reanimation half of a board that also offers a bounce. The
            // ranking must still fire, and must still take the value body.
            case "bounce-reanimate" -> {
                if (c[3] < 1 || !returned.contains(ASHEN) || returned.contains(FATTY))
                    throw new AssertionError("Reanimate stopped ranking beside a bounce: "
                        + key + " returned=" + returned);
                if (zoneOf(opp, COOKIE).equals("Hand"))
                    throw new AssertionError("The bounce beside the reanimation was re-ranked: " + key);
            }
            default -> { }
        }
        if (MUST_MOVE.contains(control) && c[0] + c[1] + c[2] + c[3] + c[4] + c[5] + c[6] == 0)
            throw new AssertionError("Reanimator row took no action: " + key);
    }

    private static List<String> controls() {
        return List.of("entomb-stable", "entomb-no-spell", "loot-payload", "loot-no-spell",
            "target-value", "exhume-symmetry", "persist-legendary", "entomb-sequence",
            "entomb-endstep", "ordinary-parity",
            // v76, APPENDED so every v73 row keeps its name and its seed.
            "aura-value", "aura-necromancy", "aura-opponent-grave", "aura-single",
            "aura-agree", "aura-worldgorger", "aura-shuffler",
            // v76 addendum, APPENDED again for the same reason.
            "portal-value", "portal-reverse", "portal-single",
            // v77, APPENDED again so every pre-v77 row keeps its name and
            // therefore its BenchRandomAudit seed.
            "bounce-jace", "exile-swords", "bounce-reanimate");
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (p, m, v) -> switch (m.getName()) {
                case "getAssetsDir" -> args[0] + "/forge-gui/";
                case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                case "getCurrentVersion" -> "reanimator-v1";
                default -> throw new AssertionError(m.getName()); }));
            FModel.initialize(null, p -> {p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); p.setPref(FPref.UI_LANGUAGE, "en-US"); return null;});
            boolean candidate = args.length < 2 || !args[1].equals("baseline");
            int cases = 0;
            for (int seat = 0; seat < 2; seat++)
                for (String control : controls()) { run(seat, control, candidate); cases++; }
            System.out.println("REANIMATOR_SUITE_COMPLETE cases=" + cases);
        } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }
}
