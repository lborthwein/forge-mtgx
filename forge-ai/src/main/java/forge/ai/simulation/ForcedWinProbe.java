package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.common.eventbus.Subscribe;

import forge.ai.AiCache;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinModel.Eff;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.event.GameEventCardChangeZone;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Lane doomsday-solver-1012: learn what a card does by playing it in a sandbox. Each probe copies the live game
 * ({@link GameCopier}), puts our seat in its own main phase with an empty stack and plenty of floating mana of every
 * colour, gives the opponent a controller that only passes, plays the card's ability (once per player-target variant:
 * ourselves, the opponent) and resolves the stack. The engine's own zone-change events, the choice asks our controller
 * receives, the mana pool, both life totals and the game result are then read back as an ordered list of effects
 * ({@link EffKind}). Further sandbox probes: a permanent's win as a function of our library size (re-run with the
 * library cut to 0, 1, 2, ... cards), drawing from an empty library with the permanent in play, and sacrificing it.
 *
 * <p>Every probe runs in its own id scope, AI cache scope and random stream, restored afterwards, so the live game's
 * counters and random stream are untouched. Never throws: a probe that fails marks the ability unplayable.</p>
 */
public final class ForcedWinProbe {
    private ForcedWinProbe() {
    }

    static final class Stats {
        int probes, failures;
        long nanos;

        String summary() {
            return "probes=" + probes + " failures=" + failures + " ms=" + nanos / 1_000_000;
        }
    }

    /** What one sandbox run observed. */
    static final class Obs {
        final List<String> trace = new ArrayList<>();
        final List<Eff> effects = new ArrayList<>();
        boolean won, lost, entered, played;
        String error;
        int lifeDelta, oppLifeDelta;
        final int[] produced = new int[6];
        int stormBefore;
        int devotionAfter;
        /** solver-gaps-1015: our life when the probed thing was played; the host's state after resolution. */
        int lifeBefore;
        boolean hostTapped;
        /** Our library ended empty: a win then came from drawing an empty library, not from the probed ability. */
        boolean libraryEmptied;
        /** solver-gaps-1015: other cards moved in ways no effect kind expresses (onto the battlefield from a hand). */
        int unmapped;
        /** solver-gaps-1015: our hand (without the probed card) and graveyard sizes when it was played. */
        int handBefore, gyBefore;
    }

    /** The sandbox's controller for our seat: passes, records choice asks, never shuffles a looked-at library. */
    static final class ProbeAi extends PlayerControllerAi {
        final List<String> trace;

        ProbeAi(Game g, Player p, forge.LobbyPlayer lp, List<String> trace) {
            super(g, p, lp);
            this.trace = trace;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            return null;
        }

        @Override
        public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional,
                Player decider) {
            final StringBuilder names = new StringBuilder();
            for (Card c : fetchList) {
                names.append(names.length() == 0 ? "" : "|").append(c.getName());
            }
            trace.add("ASK pick " + destination + " " + origin + " " + fetchList.size() + " names=" + names);
            return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt,
                    isOptional, decider);
        }

        @Override
        public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone,
                SpellAbility source) {
            trace.add("ASK order " + destinationZone + " " + cards.size());
            return super.orderMoveToZoneList(cards, destinationZone, source);
        }

        @Override
        public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options,
                Card cardToShow, Map<String, Object> params) {
            if (message != null && message.toLowerCase().contains("shuffle")) {
                return false;
            }
            return super.confirmAction(sa, mode, message, options, cardToShow, params);
        }

        /**
         * solver-gaps-1015: the probe observes the ability at its printed cost only. Optional additional costs (squad,
         * replicate, kicker) are declined: with the sandbox's rich floating mana Forge AI paid squad 31 times and the
         * model learned that a 3-mana creature draws 32 cards.
         */
        @Override
        public int chooseNumberForKeywordCost(SpellAbility sa, forge.game.cost.Cost cost,
                forge.game.keyword.KeywordInterface keyword, String prompt, int max) {
            return 0;
        }

        @Override
        public List<forge.game.spellability.OptionalCostValue> chooseOptionalCosts(SpellAbility chosen,
                List<forge.game.spellability.OptionalCostValue> optionalCostValues) {
            return new ArrayList<>();
        }
    }

    /** The opponent in a sandbox: never acts. */
    static final class PassAi extends PlayerControllerAi {
        PassAi(Game g, Player p, forge.LobbyPlayer lp) {
            super(g, p, lp);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            return null;
        }
    }

    /** Records zone changes in order. */
    static final class ZoneRec {
        final List<String> trace;
        final int me;

        ZoneRec(List<String> trace, int me) {
            this.trace = trace;
            this.me = me;
        }

        @Subscribe
        public void on(forge.game.event.GameEventShuffle e) {
            if (e.player() != null && e.player().getId() == me) {
                trace.add("SHUFFLE");
            }
        }

        @Subscribe
        public void on(GameEventCardChangeZone e) {
            if (e.card() == null) {
                return;
            }
            final String f = e.from() == null ? "-" : e.from().zoneType() + (e.from().player() == null ? "" : e.from().player().getId() == me ? "@me" : "@opp");
            final String t = e.to() == null ? "-" : e.to().zoneType() + (e.to().player() == null ? "" : e.to().player().getId() == me ? "@me" : "@opp");
            boolean land = false;
            try {
                land = e.card().getCurrentState() != null && e.card().getCurrentState().isLand();
            } catch (RuntimeException ex) {
                land = false;
            }
            trace.add("MOVE " + e.card().getId() + " " + f + " " + t + (land ? " L" : ""));
        }
    }

    interface Setup {
        void apply(Game g, Player me, Card card);
    }

    interface Act {
        /** Play the probed thing; false when it could not be played. */
        boolean apply(Game g, Player me, Card card);
    }

    // ------------------------------------------------------------------ one sandbox run

    static Obs run(Game live, Player liveMe, int cardId, Setup setup, Act act, long seed, Stats st) {
        final Obs o = new Obs();
        final long t0 = System.nanoTime();
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(seed));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final Game g;
            final Player me;
            synchronized (live) {
                final GameCopier copier = new GameCopier(live, true);
                copier.setCopyFidelity(1); // storm count, per-turn records
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
            }
            if (!g.getStack().isEmpty()) {
                o.error = "stack";
                return o;
            }
            g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
            me.dangerouslySetController(new ProbeAi(g, me, me.getController().getLobbyPlayer(), o.trace));
            for (Player p : g.getPlayers()) {
                if (p != me) {
                    p.dangerouslySetController(new PassAi(g, p, p.getController().getLobbyPlayer()));
                }
            }
            final Card card = g.findById(cardId);
            if (card == null) {
                o.error = "no card";
                return o;
            }
            if (setup != null) {
                setup.apply(g, me, card);
            }
            // plenty of floating mana of every colour
            final Card src = card;
            for (int c = 0; c < 6; c++) {
                for (int k = 0; k < 12; k++) {
                    me.getManaPool().addMana(new Mana(ForcedWinModel.FORGE_COLOR[c], src, null, me));
                }
            }
            Player opp = null;
            for (Player p : me.getOpponents()) {
                opp = p;
            }
            final int life0 = me.getLife(), opp0 = opp == null ? 0 : opp.getLife();
            o.lifeBefore = life0;
            o.stormBefore = g.getStack().getSpellsCastThisTurn().size();
            g.subscribeToEvents(new ZoneRec(o.trace, me.getId()));
            final int[] poolBefore = pool(me);
            o.handBefore = me.getCardsIn(ZoneType.Hand).size() - (card.isInZone(ZoneType.Hand) ? 1 : 0);
            o.gyBefore = me.getCardsIn(ZoneType.Graveyard).size();
            o.played = act.apply(g, me, card);
            if (!o.played) {
                o.error = o.error == null ? "not played" : o.error;
                return o;
            }
            final int[] poolCast = pool(me);
            resolveAll(g);
            final int[] poolAfter = pool(me);
            for (int c = 0; c < 6; c++) {
                o.produced[c] = Math.max(0, poolAfter[c] - Math.min(poolCast[c], poolBefore[c]));
            }
            o.lifeDelta = me.getLife() - life0;
            o.oppLifeDelta = opp == null ? 0 : opp.getLife() - opp0;
            o.won = me.hasWon() || opp != null && opp.hasLost();
            // solver-gaps-1015: a win reached by drawing from an empty library belongs to the permanent that turns it into
            // a win (its own emptyDrawWin probe), not to the ability that drew
            o.libraryEmptied = o.won && me.getCardsIn(ZoneType.Library).isEmpty();
            o.lost = me.hasLost();
            o.entered = card.getGame() == g && me.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c.getId() == cardId);
            final Card after = g.findById(cardId);
            o.hostTapped = after != null && after.isInPlay() && after.isTapped();
            int dev = me.getDevotionMod();
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                dev += ForcedWinModel.bluePips(c);
            }
            o.devotionAfter = dev;
        } catch (RuntimeException | StackOverflowError e) {
            o.error = String.valueOf(e);
        } finally {
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
            if (st != null) {
                st.probes++;
                st.nanos += System.nanoTime() - t0;
                if (o.error != null) {
                    st.failures++;
                }
            }
        }
        return o;
    }

    private static int[] pool(Player me) {
        final int[] p = new int[6];
        for (int c = 0; c < 6; c++) {
            p[c] = me.getManaPool().getAmountOfColor(ForcedWinModel.FORGE_COLOR[c]);
        }
        return p;
    }

    /** Resolve the whole stack, triggers included, with every player passing. */
    static void resolveAll(Game g) {
        for (int i = 0; i < 200 && !g.isGameOver(); i++) {
            g.getAction().checkStateEffects(true);
            if (g.isGameOver()) {
                return;
            }
            if (g.getStack().isEmpty() && !g.getStack().addAllTriggeredAbilitiesToStack() && g.getStack().isEmpty()) {
                return;
            }
            if (g.getStack().isEmpty()) {
                return;
            }
            g.getStack().resolveStack();
        }
    }

    // ------------------------------------------------------------------ locating an ability in a copy

    /** The ability of {@code card} matching the template (description, cost text), or null. */
    static SpellAbility locate(Card card, Player me, String desc, String costKey, boolean battlefield) {
        final List<SpellAbility> all = new ArrayList<>();
        for (SpellAbility sa : card.getSpellAbilities()) {
            all.add(sa);
            if (sa.isSpell()) {
                try {
                    all.addAll(GameActionUtil.getAlternativeCosts(sa, me, false));
                } catch (RuntimeException e) {
                    // none
                }
            }
        }
        all.addAll(card.getManaAbilities());
        SpellAbility loose = null;
        for (SpellAbility sa : all) {
            if (!desc.equals(sa.getDescription())) {
                continue;
            }
            final String ck = sa.getPayCosts() == null ? "" : sa.getPayCosts().toString();
            if (ck.equals(costKey)) {
                return sa;
            }
            if (loose == null) {
                loose = sa;
            }
        }
        return costKey == null ? loose : null;
    }

    /** Set an ability's player targets: every targeting link at us ("self") or the opponent ("opp"). */
    static boolean setPlayerTargets(SpellAbility sa, Player me, String variant) {
        Player opp = null;
        for (Player p : me.getOpponents()) {
            opp = p;
        }
        for (SpellAbility l = sa; l != null; l = l.getSubAbility()) {
            if (!l.usesTargeting()) {
                continue;
            }
            final Player who = "self".equals(variant) ? me : opp;
            if (who == null || !l.canTarget(who)) {
                return false;
            }
            l.resetTargets();
            l.getTargets().add(who);
        }
        return true;
    }

    static boolean targetsPlayersOnly(SpellAbility sa, Player me) {
        boolean any = false;
        for (SpellAbility l = sa; l != null; l = l.getSubAbility()) {
            if (!l.usesTargeting()) {
                continue;
            }
            any = true;
            boolean player = l.canTarget(me);
            for (Player p : me.getOpponents()) {
                player |= l.canTarget(p);
            }
            if (!player) {
                return false;
            }
        }
        return any;
    }

    static boolean targetsAnything(SpellAbility sa) {
        for (SpellAbility l = sa; l != null; l = l.getSubAbility()) {
            if (l.usesTargeting()) {
                return true;
            }
        }
        return false;
    }

    /** Play an ability in a sandbox (or a search copy): a land is played, a spell or ability goes on the stack. */
    static boolean play(Game g, Player me, SpellAbility sa) {
        sa.setActivatingPlayer(me);
        if (sa.isLandAbility()) {
            if (!me.canPlayLand(sa.getHostCard(), false, sa)) {
                return false;
            }
            sa.resolve();
            return true;
        }
        return ComputerUtil.handlePlayingSpellAbility(me, sa, null);
    }

    // ------------------------------------------------------------------ reading a trace into effects

    static void effectsFromTrace(Obs o, int hostId, Abil ab, SpellAbility sa) {
        final List<Eff> out = o.effects;
        // mana first (rituals, Manamorphose): the colours the ability can make (engine metadata), the amount observed
        int made = 0, mask = 0;
        for (int c = 0; c < 6; c++) {
            made += o.produced[c];
            if (o.produced[c] > 0) {
                mask |= 1 << c;
            }
        }
        if (made > 0) {
            for (SpellAbility l = sa; l != null; l = l.getSubAbility()) {
                if (l.getManaPart() != null) {
                    for (int c = 0; c < 6; c++) {
                        if (l.canProduce(ForcedWinModel.SHORT[c])) {
                            mask |= 1 << c;
                        }
                    }
                }
            }
            out.add(new Eff(EffKind.MANA, made, mask));
        }
        int pickToLibrary = 0, exiled = 0;
        boolean pickFromLibraryOrGy = false;
        String pendingAsk = null;
        EffKind last = null;
        int run = 0;
        java.util.Set<String> tutorNames = null, topNames = null;
        final int mark = out.size();
        for (String line : o.trace) {
            if (line.equals("SHUFFLE")) {
                // our library was shuffled: what is on top afterwards is unknown
                if (last != null) {
                    out.add(new Eff(last, run, 0));
                    last = null;
                    run = 0;
                }
                if (out.isEmpty() || out.get(out.size() - 1).kind != EffKind.SHUFFLE) {
                    out.add(new Eff(EffKind.SHUFFLE, 0, 0));
                }
                continue;
            }
            if (line.startsWith("ASK pick ")) {
                pendingAsk = line;
                final int ni = line.indexOf(" names=");
                if (ni > 0) {
                    final java.util.Set<String> offered = new java.util.HashSet<>(java.util.Arrays.asList(line.substring(ni + 7).split("\\|")));
                    if (line.startsWith("ASK pick Hand [Library")) {
                        tutorNames = offered;
                    } else if (line.startsWith("ASK pick Library [Library]")) {
                        topNames = offered;
                    }
                }
                // a pick INTO our library FROM our library or graveyard (not Brainstorm's put-back from hand)
                final String head = ni > 0 ? line.substring(0, ni) : line;
                final int lb = head.indexOf('['), rb = head.indexOf(']');
                final String origins = lb >= 0 && rb > lb ? head.substring(lb, rb) : "";
                if (head.startsWith("ASK pick Library ") && origins.contains("Library")) {
                    pickToLibrary++;
                    pickFromLibraryOrGy |= head.contains("Graveyard") && head.contains("Library, ")
                            || head.contains("[Graveyard, Library]") || head.contains("[Library, Graveyard]");
                }
                continue;
            }
            if (!line.startsWith("MOVE ")) {
                continue;
            }
            final String[] p = line.split(" ");
            final int id = Integer.parseInt(p[1]);
            if (id == hostId) {
                continue;
            }
            final String from = p[2], to = p[3];
            EffKind k = null;
            if (from.equals("Library@me") && to.equals("Hand@me")) {
                k = pendingAsk != null && pendingAsk.contains("Hand [Library") ? EffKind.TUTOR : EffKind.DRAW;
            } else if (from.equals("Hand@me") && to.equals("Library@me")) {
                k = EffKind.PUTBACK;
            } else if (from.equals("Library@me") && to.equals("Graveyard@me")) {
                k = EffKind.MILL;
            } else if (from.equals("Hand@me") && to.equals("Graveyard@me")) {
                k = EffKind.DISCARD;
            } else if (from.equals("Graveyard@me") && to.equals("Library@me") && pendingAsk == null) {
                k = EffKind.GRAVEYARD_TO_LIBRARY;
            } else if (from.equals("Hand@me") && to.equals("Exile@me")) {
                k = EffKind.EXILE_HAND;
            } else if (from.equals("Library@opp") && to.equals("Graveyard@opp")) {
                k = EffKind.MILL_OPP;
            } else if ((from.equals("Library@me") || from.equals("Graveyard@me")) && to.equals("Exile@me")) {
                exiled++;
                continue;
            }
            pendingAsk = null;
            if (k == null) {
                // solver-gaps-1015: a move the width model has no effect for (a creature put onto the battlefield from
                // our hand or library, a token): the engine search must handle this card
                final boolean landMove = p.length > 4 && "L".equals(p[4]);
                if (!landMove && (to.startsWith("Battlefield") || from.startsWith("Hand@me") && !to.startsWith("Stack"))) {
                    o.unmapped++;
                }
                continue;
            }
            if (k == last) {
                run++;
            } else {
                if (last != null) {
                    out.add(new Eff(last, run, 0));
                }
                last = k;
                run = 1;
            }
        }
        if (last != null) {
            out.add(new Eff(last, run, 0));
        }
        // solver-gaps-1015: whole-hand and whole-graveyard effects, and tutors' observed choices
        for (int i = mark; i < out.size(); i++) {
            final Eff e = out.get(i);
            boolean shuffleLater = false;
            for (int j = i + 1; j < out.size(); j++) {
                shuffleLater |= out.get(j).kind == EffKind.SHUFFLE;
            }
            if (e.kind == EffKind.PUTBACK && shuffleLater) {
                // our hand into our library, then a shuffle: all of it (Timetwister), else no put-back on top (PF2)
                if (e.n >= 2 && e.n == o.handBefore) {
                    out.set(i, new Eff(EffKind.HAND_TO_LIBRARY, ForcedWinModel.ALL, 0));
                } else {
                    out.remove(i--);
                }
            } else if (e.kind == EffKind.GRAVEYARD_TO_LIBRARY) {
                if (e.n >= 1 && e.n == o.gyBefore) {
                    out.set(i, new Eff(EffKind.GRAVEYARD_TO_LIBRARY, ForcedWinModel.ALL, 0));
                } else {
                    out.remove(i--);
                }
            } else if (e.kind == EffKind.EXILE_HAND) {
                if (e.n >= 2 && e.n == o.handBefore) {
                    out.set(i, new Eff(EffKind.EXILE_HAND, ForcedWinModel.ALL, 0));
                } else {
                    out.remove(i--);
                }
            } else if (e.kind == EffKind.DISCARD && e.n >= 2 && e.n == o.handBefore) {
                out.set(i, new Eff(EffKind.DISCARD, ForcedWinModel.ALL, 0));
            } else if (e.kind == EffKind.TUTOR && tutorNames != null) {
                out.set(i, new Eff(EffKind.TUTOR, e.n, 0, 0, tutorNames));
            }
        }
        // POST-FREEZE 6: a pick into our library still counts after a shuffle. "Search, shuffle, then put that card on
        // top" (Mystical Tutor) shuffles before the card goes on top, so TUTOR_TOP comes after SHUFFLE here. Only a
        // put-back from hand followed by a shuffle (Timetwister) is erased, above.
        if (pickToLibrary > 0) {
            if (exiled > 0 && pickFromLibraryOrGy) {
                out.add(0, new Eff(EffKind.PILE, pickToLibrary, 0));
            } else {
                out.add(new Eff(EffKind.TUTOR_TOP, pickToLibrary, 0, 0, topNames));
            }
        }
        // per-instance amounts for storm spells
        final int instances = ab != null && ab.storm ? o.stormBefore + 1 : 1;
        for (int i = 0; i < out.size(); i++) {
            final Eff e = out.get(i);
            if (e.kind == EffKind.MILL_OPP && instances > 1) {
                out.set(i, new Eff(EffKind.MILL_OPP, Math.max(1, e.n / instances), 0));
            }
        }
        final int life = o.lifeDelta + (ab == null ? 0 : ab.life);
        if (o.oppLifeDelta < 0) {
            out.add(new Eff(EffKind.DRAIN, Math.max(1, -o.oppLifeDelta / instances), 0));
        }
        final boolean won = o.won && !o.libraryEmptied;
        if (life != 0 && !won) {
            out.add(new Eff(EffKind.LIFE, life, 0));
        }
        if (won) {
            out.add(new Eff(EffKind.WIN, 1, 0));
        }
    }

    // ------------------------------------------------------------------ learning a card

    /** Learn the template of {@code liveCard} (a card the seat owns, in any zone). Never throws. */
    static ACard learn(Game live, Player liveMe, Card liveCard, long seed, Stats st) {
        final ACard t = new ACard();
        t.id = liveCard.getId();
        t.name = liveCard.getName();
        try {
            learnInto(t, live, liveMe, liveCard, seed, st);
        } catch (RuntimeException e) {
            t.note = "learn failed: " + e;
        }
        if (ForcedWinSolver.RECEIPTS) {
            ForcedWinSolver.receipt("FORCED_WIN learned " + t.name + " role=" + t.role() + " hand=" + t.fromHand
                    + " bf=" + t.onBattlefield + (t.sourceColors != 0 ? " source=" + ForcedWinModel.colorString(t.sourceColors)
                    + "x" + t.sourceAmount + (t.sourceSac ? "sac" : "") : "") + (t.winIfLibraryAtMost >= 0 ? " winIfLibrary<="
                    + t.winIfLibraryAtMost + "@devotion" + t.winDevotion : "") + (t.emptyDrawWin ? " emptyDrawWin" : "")
                    + (t.winsOnEntry ? " winsOnEntry" : "") + (t.dies.isEmpty() ? "" : " dies=" + t.dies)
                    + (t.note.isEmpty() ? "" : " note=" + t.note));
        }
        return t;
    }

    private static void learnInto(ACard t, Game live, Player me, Card card, long seed, Stats st) {
        t.land = card.isLand();
        t.permanent = card.isPermanent();
        t.creature = card.isCreature();
        t.bluePips = ForcedWinModel.bluePips(card);
        t.subtypes.addAll(card.getType().getSubtypes());
        if (card.isPlaneswalker()) {
            try {
                t.startLoyalty = Integer.parseInt(card.getState(card.getCurrentStateName()).getBaseLoyalty());
            } catch (RuntimeException e) {
                t.startLoyalty = 0;
            }
        }
        final int id = card.getId();
        long variant = 0;
        // ---- from hand: spells (and their alternative costs), cycling and other hand abilities
        for (SpellAbility original : card.getSpellAbilities()) {
            if (original.isLandAbility()) {
                continue;
            }
            final SpellAbility sa0 = original.copy(me);
            final boolean fromHand = sa0.isSpell() || sa0.isCycling()
                    || "Hand".equals(sa0.getParam("ActivationZone"));
            if (!fromHand) {
                continue;
            }
            final AbilKind kind = sa0.isSpell() ? AbilKind.SPELL : sa0.isCycling() ? AbilKind.CYCLE : AbilKind.ACTIVATE;
            final Abil base = ForcedWinModel.readCost(sa0, kind);
            if (sa0.getApi() == forge.game.ability.ApiType.Counter) {
                t.counter = true;
            }
            if (base == null) {
                t.unmodeled = true;
                continue;
            }
            if (targetsAnything(sa0) && !targetsPlayersOnly(sa0, me)) {
                t.unmodeled = true;
            }
            final List<String> variants = new ArrayList<>();
            if (targetsPlayersOnly(sa0, me)) {
                variants.add("self");
                variants.add("opp");
            } else {
                variants.add(null);
            }
            final List<SpellAbility> alts = new ArrayList<>();
            if (sa0.isSpell()) {
                try {
                    alts.addAll(GameActionUtil.getAlternativeCosts(sa0, me, false));
                } catch (RuntimeException e) {
                    // none
                }
            }
            // solver-gaps-1015: a legality condition on our hand size (engine restriction metadata says one may exist)
            final int handMask = stateRestricted(sa0)
                    ? legalHandMask(live, me, id, sa0.getDescription(), base.costKey, false, ForcedWinSolver.mix(seed, id * 41L + 3), st)
                    : -1;
            final int probeHand = handFor(handMask, me.getCardsIn(ZoneType.Hand).size() + (card.isInZone(ZoneType.Hand) ? 0 : 1));
            for (String v : variants) {
                final String desc = sa0.getDescription(), costKey = base.costKey;
                final Setup inHand = (g, m, c) -> {
                    if (!c.isInZone(ZoneType.Hand)) {
                        g.getAction().moveToHand(c, null);
                    }
                    if (probeHand >= 0) {
                        setHandSize(g, m, c, probeHand);
                    }
                };
                final Obs o = run(live, me, id, inHand, (g, m, c) -> {
                    final SpellAbility sa = locate(c, m, desc, costKey, false);
                    if (sa == null) {
                        return false;
                    }
                    sa.setActivatingPlayer(m);
                    if (v != null && !setPlayerTargets(sa, m, v)) {
                        return false;
                    }
                    if (v == null && targetsAnything(sa)) {
                        // a card target: Forge AI chooses one, the search will not plan it
                        if (((PlayerControllerAi) m.getController()).getAi().canPlaySa(sa) != forge.ai.AiPlayDecision.WillPlay
                                && !sa.isTargetNumberValid()) {
                            return false;
                        }
                    }
                    return play(g, m, sa);
                }, ForcedWinSolver.mix(seed, id * 31L + variant++), st);
                final Abil ab = withEffects(base, o, id, sa0, v);
                ab.cardTargets = v == null && targetsAnything(sa0);
                ab.handMask = handMask;
                if (!ab.unplayable && (o.unmapped > 0 || ab.effects.isEmpty() && !t.permanent)) {
                    // solver-gaps-1015: it did something the width model cannot express (Show and Tell)
                    t.unmodeled = true;
                }
                if (!ab.unplayable && ab.has(EffKind.LIFE)) {
                    // solver-gaps-1015: the same play at another life total: a fixed change or a share of our life
                    final int l2 = 2 * Math.max(1, o.lifeBefore) + 7;
                    final Act replay = (g, m, c) -> {
                        final SpellAbility sa = locate(c, m, desc, costKey, false);
                        if (sa == null) {
                            return false;
                        }
                        sa.setActivatingPlayer(m);
                        if (v != null && !setPlayerTargets(sa, m, v)) {
                            return false;
                        }
                        return play(g, m, sa);
                    };
                    final Obs o2 = run(live, me, id, (g, m, c) -> {
                        inHand.apply(g, m, c);
                        m.setLife(l2, null);
                    }, replay, ForcedWinSolver.mix(seed, id * 31L + 500 + variant), st);
                    fitLife(ab, o, o2, base.life);
                }
                t.fromHand.add(ab);
                if (o.won && !o.libraryEmptied && t.permanent) {
                    t.winsOnEntry = true;
                }
                if (!ab.unplayable && ab.effects.isEmpty() && t.permanent && !t.creature && !t.land
                        && card.getManaAbilities().isEmpty() && card.getSpellAbilities().stream().noneMatch(
                                x -> x.isActivatedAbility() && !x.isManaAbility())) {
                    // a non-creature permanent whose entering changes nothing we can see: a static effect the width
                    // model cannot express (Underworld Breach's escape, cost reducers): the engine search may use it
                    t.unmodeled = true;
                }
                for (SpellAbility alt : alts) {
                    final Abil altCost = ForcedWinModel.readCost(alt, AbilKind.SPELL);
                    if (altCost == null || altCost.costKey.equals(base.costKey)) {
                        continue;
                    }
                    final Abil a2 = altCost.copyCost();
                    a2.desc = alt.getDescription();
                    a2.targetVariant = v;
                    a2.effects.addAll(ab.effects);
                    a2.unplayable = ab.unplayable;
                    a2.cardTargets = ab.cardTargets;
                    t.fromHand.add(a2);
                }
            }
        }
        if (t.land) {
            final Abil play = new Abil();
            play.kind = AbilKind.LAND;
            play.desc = "land";
            t.fromHand.add(play);
            // solver-gaps-1015: play it as our land drop in a sandbox: does it enter tapped, and what does that cost?
            final Obs o = run(live, me, id, (g, m, c) -> {
                if (!c.isInZone(ZoneType.Hand)) {
                    g.getAction().moveToHand(c, null);
                }
                m.setLandsPlayedThisTurn(0);
            }, (g, m, c) -> {
                // the card object moved to our hand in setup (a zone change makes a new object): find it again
                final Card hc = g.findById(c.getId());
                if (hc == null) {
                    return false;
                }
                for (SpellAbility sa : hc.getAllPossibleAbilities(m, true)) {
                    if (sa.isLandAbility()) {
                        return play(g, m, sa);
                    }
                }
                return false;
            }, ForcedWinSolver.mix(seed, id * 31L + 977), st);
            if (o.played && o.error == null) {
                t.entersTapped = o.hostTapped;
                t.landLife = o.lifeDelta;
            }
        }
        // ---- on the battlefield: mana sources (engine metadata), activated abilities (probed), sacrifice, wins
        if (t.permanent) {
            for (SpellAbility original : card.getManaAbilities()) {
                final SpellAbility m = original.copy(me);
                if (m.getManaPart() == null) {
                    continue;
                }
                int colors = 0;
                for (int i = 0; i < 6; i++) {
                    if (m.canProduce(ForcedWinModel.SHORT[i])) {
                        colors |= 1 << i;
                    }
                }
                if (colors == 0) {
                    continue;
                }
                final Abil cost = ForcedWinModel.readCost(m, AbilKind.FLOAT);
                if (cost == null) {
                    continue;
                }
                int amount;
                try {
                    amount = Math.max(1, m.amountOfManaGenerated(true));
                } catch (RuntimeException e) {
                    amount = 1;
                }
                final boolean plain = cost.generic == 0 && java.util.Arrays.stream(cost.pips).sum() == 0
                        && cost.life == 0 && !cost.discardHand && m.getSubAbility() == null;
                if (plain && !(cost.sacSelf && hasDiesTrigger(card))) {
                    t.sourceColors |= colors;
                    t.sourceAmount = Math.max(t.sourceAmount, amount);
                    t.sourceSac |= cost.sacSelf;
                } else {
                    cost.effects.add(new Eff(EffKind.MANA, amount, colors));
                    t.onBattlefield.add(cost);
                }
            }
            for (SpellAbility original : card.getSpellAbilities()) {
                final SpellAbility sa0 = original.copy(me);
                if (sa0.isSpell() || sa0.isLandAbility() || sa0.isCycling() || sa0.isManaAbility()
                        || "Hand".equals(sa0.getParam("ActivationZone")) || !sa0.isActivatedAbility()) {
                    continue;
                }
                final Abil base = ForcedWinModel.readCost(sa0, AbilKind.ACTIVATE);
                if (base == null) {
                    t.unmodeled = true;
                    continue;
                }
                if (targetsAnything(sa0) && !targetsPlayersOnly(sa0, me)) {
                    t.unmodeled = true;
                }
                final List<String> variants = new ArrayList<>();
                if (targetsPlayersOnly(sa0, me)) {
                    variants.add("self");
                    variants.add("opp");
                } else {
                    variants.add(null);
                }
                final int handMask = stateRestricted(sa0)
                        ? legalHandMask(live, me, id, sa0.getDescription(), base.costKey, true, ForcedWinSolver.mix(seed, id * 43L + 5), st)
                        : -1;
                final int probeHand = handFor(handMask, me.getCardsIn(ZoneType.Hand).size() - (card.isInZone(ZoneType.Hand) ? 1 : 0));
                for (String v : variants) {
                    final String desc = sa0.getDescription(), costKey = base.costKey;
                    final Obs o = run(live, me, id, (g, m, c) -> {
                        toBattlefield(g, m, c);
                        if (probeHand >= 0) {
                            setHandSize(g, m, null, probeHand);
                        }
                    }, (g, m, c) -> {
                        final SpellAbility sa = locate(c, m, desc, costKey, true);
                        if (sa == null) {
                            return false;
                        }
                        sa.setActivatingPlayer(m);
                        if (v != null && !setPlayerTargets(sa, m, v)) {
                            return false;
                        }
                        return play(g, m, sa);
                    }, ForcedWinSolver.mix(seed, id * 31L + variant++), st);
                    final Abil ab = withEffects(base, o, id, sa0, v);
                    ab.cardTargets = v == null && targetsAnything(sa0);
                    ab.handMask = handMask;
                    if (!ab.unplayable && o.unmapped > 0) {
                        // solver-gaps-1015: an activation the width model cannot express (Sneak Attack)
                        t.unmodeled = true;
                    }
                    t.onBattlefield.add(ab);
                }
            }
            // sacrifice: what happens when it goes to our graveyard from the battlefield (Chromatic Star draws)
            if (hasDiesTrigger(card)) {
                final Obs o = run(live, me, id, (g, m, c) -> toBattlefield(g, m, c), (g, m, c) -> {
                    final Card bc = g.findById(c.getId());
                    if (bc == null || !bc.isInPlay()) {
                        return false;
                    }
                    g.getAction().sacrifice(new CardCollection(bc), null, false, null);
                    return true;
                }, ForcedWinSolver.mix(seed, id * 31L + variant++), st);
                effectsFromTrace(o, id, null, null);
                for (Eff e : o.effects) {
                    if (e.kind == EffKind.DRAW) {
                        t.dies.add(e);
                    }
                }
                for (Abil a : t.onBattlefield) {
                    if (a.sacSelf) {
                        a.effects.addAll(t.dies);
                    }
                }
            }
            // win while our library is small (an enters trigger that checks it): cut the library to L and cast it
            if (!t.winsOnEntry && hasSelfEnterTrigger(card) && castableFromHand(t)) {
                final Abil cast = firstSpell(t);
                int best = -1, devo = 0;
                for (int lib = 0; lib <= 12; lib++) {
                    final int keep = lib;
                    final Obs o = run(live, me, id, (g, m, c) -> {
                        if (!c.isInZone(ZoneType.Hand)) {
                            g.getAction().moveToHand(c, null);
                        }
                        final List<Card> library = new ArrayList<>(m.getCardsIn(ZoneType.Library));
                        library.remove(c);
                        for (int i = keep; i < library.size(); i++) {
                            g.getAction().exile(library.get(i), null, null);
                        }
                    }, (g, m, c) -> {
                        final SpellAbility sa = locate(c, m, cast.desc, cast.costKey, false);
                        return sa != null && play(g, m, sa);
                    }, ForcedWinSolver.mix(seed, id * 131L + lib), st);
                    if (!o.won) {
                        break;
                    }
                    best = lib;
                    devo = o.devotionAfter;
                    if (lib >= 10) {
                        break;
                    }
                }
                t.winIfLibraryAtMost = best;
                t.winDevotion = devo;
            }
            // drawing from an empty library with it on the battlefield
            if (!card.getReplacementEffects().isEmpty()) {
                final Obs o = run(live, me, id, (g, m, c) -> {
                    toBattlefield(g, m, c);
                    for (Card l : new ArrayList<>(m.getCardsIn(ZoneType.Library))) {
                        g.getAction().exile(l, null, null);
                    }
                }, (g, m, c) -> {
                    m.drawCards(1);
                    return true;
                }, ForcedWinSolver.mix(seed, id * 37L + 7), st);
                t.emptyDrawWin = o.won;
                if (o.won) {
                    // solver-gaps-1015: a control without the card (another permanent of ours may be the one that wins)
                    final Obs ctl = run(live, me, id, (g, m, c) -> {
                        g.getAction().exile(c, null, null);
                        for (Card l : new ArrayList<>(m.getCardsIn(ZoneType.Library))) {
                            g.getAction().exile(l, null, null);
                        }
                    }, (g, m, c) -> {
                        m.drawCards(1);
                        return true;
                    }, ForcedWinSolver.mix(seed, id * 37L + 8), st);
                    if (ctl.won) {
                        t.emptyDrawWin = false;
                        t.note += " emptyDrawWin:control-wins";
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ solver-gaps-1015 helpers

    /** Engine metadata: the ability carries a state restriction (a count of cards present, an SVar check, threshold). */
    static boolean stateRestricted(SpellAbility sa) {
        return sa.hasParam("IsPresent") || sa.hasParam("CheckSVar") || sa.hasParam("Activation")
                || sa.hasParam("PresentCompare");
    }

    /** The hand size to probe effects at: the current one when legal, else the nearest legal one; -1 = unchanged. */
    static int handFor(int mask, int current) {
        if (mask == -1 || mask == 0 || (mask >> Math.min(11, Math.max(0, current)) & 1) != 0) {
            return -1;
        }
        int best = -1;
        for (int h = 0; h <= 11; h++) {
            if ((mask >> h & 1) != 0 && (best < 0 || Math.abs(h - current) < Math.abs(best - current))) {
                best = h;
            }
        }
        return best;
    }

    /**
     * Set our hand to {@code h} cards in a sandbox: extra cards go to the bottom of our library, missing ones come from
     * its top; {@code keep} stays in hand. False when the library cannot supply them.
     */
    static boolean setHandSize(Game g, Player m, Card keep, int h) {
        List<Card> hand = new ArrayList<>(m.getCardsIn(ZoneType.Hand));
        while (hand.size() > h) {
            Card out = null;
            for (int i = hand.size() - 1; i >= 0; i--) {
                if (keep == null || hand.get(i).getId() != keep.getId()) {
                    out = hand.get(i);
                    break;
                }
            }
            if (out == null) {
                return false;
            }
            g.getAction().moveToLibrary(out, -1, null);
            hand = new ArrayList<>(m.getCardsIn(ZoneType.Hand));
        }
        while (hand.size() < h) {
            final List<Card> lib = new ArrayList<>(m.getCardsIn(ZoneType.Library));
            if (lib.isEmpty()) {
                return false;
            }
            g.getAction().moveToHand(lib.get(0), null);
            hand = new ArrayList<>(m.getCardsIn(ZoneType.Hand));
        }
        return true;
    }

    /**
     * The hand sizes 0..11 (bit 11: 11 or more) at which the ability is legal, probed in one sandbox by moving cards
     * between our hand and library; -1 when its legality does not depend on our hand size.
     */
    static int legalHandMask(Game live, Player me, int id, String desc, String costKey, boolean battlefield, long seed,
            Stats st) {
        final int[] legal = {0, 0};
        run(live, me, id, (g, m, c) -> {
            if (battlefield) {
                toBattlefield(g, m, c);
            } else if (!c.isInZone(ZoneType.Hand)) {
                g.getAction().moveToHand(c, null);
            }
        }, (g, m, c) -> {
            final Card host = g.findById(c.getId());
            final SpellAbility sa = host == null ? null : locate(host, m, desc, costKey, battlefield);
            if (sa == null) {
                return false;
            }
            sa.setActivatingPlayer(m);
            for (int h = 0; h <= 11; h++) {
                if (!setHandSize(g, m, battlefield ? null : host, h)) {
                    continue;
                }
                legal[1] |= 1 << h;
                if (sa.canPlay()) {
                    legal[0] |= 1 << h;
                }
            }
            return true;
        }, seed, st);
        if (legal[1] == 0 || legal[0] == legal[1] || legal[0] == 0) {
            return -1;
        }
        return legal[0];
    }

    /**
     * Our life change of an ability seen at two life totals: a fixed change, or (when the second observation is the
     * first one's share of our life, rounded away from zero) a share of our life total.
     */
    static void fitLife(Abil ab, Obs o1, Obs o2, int costLife) {
        if (o2.error != null || !o2.played || o1.lifeBefore <= 0) {
            return;
        }
        for (int i = 0; i < ab.effects.size(); i++) {
            final Eff e = ab.effects.get(i);
            if (e.kind != EffKind.LIFE) {
                continue;
            }
            final int d2 = o2.lifeDelta + costLife;
            if (d2 == e.n) {
                return;
            }
            final double ratio = (double) e.n / o1.lifeBefore;
            final Eff prop = new Eff(EffKind.LIFE, e.n, 0, ratio);
            if (prop.lifeChange(o2.lifeBefore) == d2) {
                ab.effects.set(i, prop);
            }
            return;
        }
    }

    private static void toBattlefield(Game g, Player m, Card c) {
        if (!c.isInPlay()) {
            g.getAction().moveToPlay(c, m, null, null);
        }
        final Card bc = g.findById(c.getId());
        if (bc != null) {
            bc.setSickness(false);
        }
    }

    static boolean hasDiesTrigger(Card card) {
        for (Trigger tr : card.getTriggers()) {
            if (tr.getMode() == TriggerType.ChangesZone && "Battlefield".equals(tr.getParam("Origin"))
                    && "Graveyard".equals(tr.getParam("Destination"))) {
                return true;
            }
        }
        return false;
    }

    static boolean hasSelfEnterTrigger(Card card) {
        for (Trigger tr : card.getTriggers()) {
            if (tr.getMode() == TriggerType.ChangesZone && "Battlefield".equals(tr.getParam("Destination"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean castableFromHand(ACard t) {
        return firstSpell(t) != null;
    }

    private static Abil firstSpell(ACard t) {
        for (Abil a : t.fromHand) {
            if (a.kind == AbilKind.SPELL && !a.unplayable) {
                return a;
            }
        }
        return null;
    }

    private static Abil withEffects(Abil base, Obs o, int hostId, SpellAbility sa, String variant) {
        final Abil ab = base.copyCost();
        ab.targetVariant = variant;
        if (o.error != null || !o.played) {
            ab.unplayable = true;
            ab.probeNote = o.error == null ? "" : o.error.length() > 80 ? o.error.substring(0, 80) : o.error;
            ForcedWinSolver.receipt("FORCED_WIN probe-fail card=" + (sa.getHostCard() == null ? "?" : sa.getHostCard().getName())
                    + " ability=" + ab.kind + (variant == null ? "" : "@" + variant) + " why=" + ab.probeNote);
            return ab;
        }
        effectsFromTrace(o, hostId, ab, sa);
        ab.effects.addAll(o.effects);
        ab.counter = sa.getApi() == forge.game.ability.ApiType.Counter;
        return ab;
    }

    static ReplacementEffect firstReplacement(Card c) {
        return c.getReplacementEffects().isEmpty() ? null : c.getReplacementEffects().iterator().next();
    }
}
