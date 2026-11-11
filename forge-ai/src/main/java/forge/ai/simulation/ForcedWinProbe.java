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
        /**
         * sdcc-tutorcast-1110: where the host was once everything had resolved, as an {@link Abil#selfTo} code (0 = on
         * the battlefield or not found, -1 = somewhere the plan has no place for). Read only by a sourceLeaves probe.
         */
        int hostEnd;
        /** Our library ended empty: a win then came from drawing an empty library, not from the probed ability. */
        boolean libraryEmptied;
        /** solver-gaps-1015: other cards moved in ways no effect kind expresses (onto the battlefield from a hand). */
        int unmapped;
        /** solver-gaps-1015: our hand (without the probed card) and graveyard sizes when it was played. */
        int handBefore, gyBefore;
        /** sdcc-draws-1110: the run was a {@link #PROBE_SETTLE} one; our library's size when it was played and after it. */
        boolean settle;
        int libBefore, libAfter;
        /**
         * sdcc-tutorcast-1110 (a tutorCast probe only; -1 / false otherwise): the id of the card a search of our library
         * exiled, whether the engine then offered to cast it without paying its mana cost, and whether it ended in our
         * hand.
         */
        int exilePick = -1;
        boolean freeCast, pickToHand;
        /** The effect read for that card (a TUTOR or TUTOR_CAST), or null. */
        Eff exileEff;
    }

    /** labeller-v2 (c): set by a planScry reader around its learning (false = v1). */
    static final ThreadLocal<Boolean> PLAN_SCRY = ThreadLocal.withInitial(() -> false);

    /** sdcc-tutorcast-1110: set by a tutorCast reader around its learning (false = off: every probe as before). */
    static final ThreadLocal<Boolean> TUTOR_CAST = ThreadLocal.withInitial(() -> false);
    /** sdcc-tutorcast-1110: set by a sourceLeaves reader around its learning (false = off: every probe as before). */
    static final ThreadLocal<Boolean> SOURCE_LEAVES = ThreadLocal.withInitial(() -> false);
    /** sdcc-tutorcast-1110: the most cards a search-and-cast spell is observed with (one sandbox each), and of those tried first. */
    static final int CAST_SWEEP = 48, CAST_DISCOVER = 4;

    /**
     * sdcc-draws-1110 (solverCfg {@code probeSettle}; set by a probeSettle reader around its learning, false = as before).
     * The width model counted the cards an ability moves between our library and hand from what the ability's own
     * resolution moved in the sandbox, with two holes. (1) A mana ability was never played there: its mana came from
     * engine metadata, and what paying its cost causes (the "when this is put into a graveyard, draw a card" trigger of
     * an artifact sacrificed for mana) was to come from a separate sacrifice probe. That probe fails in this engine (it
     * calls GameAction.sacrifice with no parameter map, which the engine now reads: a NullPointerException every time),
     * so the draw was never learned. (2) The probed card's own moves were skipped, so "draw a card, then put this on top
     * of your library" was a plain draw: the card stayed on the battlefield and the library read one card short.
     *
     * <p>On: every mana ability that is no plain source (it has a mana or life cost or a second part, or it sacrifices a
     * permanent with such a trigger) is activated in a sandbox like any other ability: the engine pays its cost, the
     * stack and the triggers that causes resolve, and the cards the engine then moved between our library, hand and
     * graveyard are the ability's effects after its mana ({@link #settledMana}). The separate sacrifice probe is not run
     * (each ability's own run holds what its cost causes). A probed card that goes to our library is a
     * {@link EffKind#SELF_TO_LIBRARY} effect, in order with the draws, with the place the engine put it. Receipts name
     * each ability whose effects still imply another library size than the engine left ({@code probe-net}).</p>
     */
    static final ThreadLocal<Boolean> PROBE_SETTLE = ThreadLocal.withInitial(() -> false);

    /**
     * ledger-sweep-1107, ledger row 45 ({@code -Dforge.probeEmptyDraw=true}, off by default). A probe runs in a copy of
     * the position. When our library there is empty, an ability that draws ("mill two, then draw a card") is replaced by
     * the permanent that turns an empty-library draw into a win, so the run is won with no card moved: the template got
     * neither a DRAW (no card was drawn) nor a WIN (that win belongs to the permanent, solver-gaps-1015). The planner then
     * saw the ability do nothing and found only the draw step's win, a turn later. On: such a run is repeated with our
     * library stocked (up to {@link #STOCK} of our own exiled or graveyard cards, not the probed card); when the repeat
     * plays, is not won and draws, its observation is the ability's. Otherwise the first run stands.
     */
    static boolean emptyDrawProbe = Boolean.getBoolean("forge.probeEmptyDraw");
    static final int STOCK = 8;

    /** For tests. */
    static void setEmptyDrawProbeForTest(final boolean on) {
        emptyDrawProbe = on;
    }

    /** ledger-sweep-1107 (row 45): {@code first}, or the stocked-library repeat of its run (see {@link #emptyDrawProbe}). */
    static Obs emptyDrawRetry(Game live, Player liveMe, int cardId, Setup setup, Act act, long seed, Stats st, Obs first,
            String name) {
        if (!emptyDrawProbe || first.error != null || !first.played || !first.won || !first.libraryEmptied) {
            return first;
        }
        final Obs o = run(live, liveMe, cardId, (g, m, c) -> {
            if (setup != null) {
                setup.apply(g, m, c);
            }
            int n = 0;
            for (ZoneType z : new ZoneType[] {ZoneType.Exile, ZoneType.Graveyard}) {
                for (Card x : new ArrayList<>(m.getCardsIn(z))) {
                    if (n >= STOCK) {
                        break;
                    }
                    if (x.getId() == c.getId() || x.getOwner() != m) {
                        continue;
                    }
                    g.getAction().moveToLibrary(x, null);
                    n++;
                }
            }
        }, act, ForcedWinSolver.mix(seed, 0x45L), st);
        boolean drew = false;
        for (String line : o.trace) {
            if (line.startsWith("MOVE ") && line.contains(" Library@me Hand@me")) {
                drew = true;
            }
        }
        final boolean use = o.error == null && o.played && !o.won && drew;
        ForcedWinSolver.receipt("FORCED_WIN probe-empty-draw card=" + name + " repeat=" + (use ? "used" : "kept-first")
                + " played=" + o.played + " won=" + o.won + " drew=" + drew);
        return use ? o : first;
    }

    /** The sandbox's controller for our seat: passes, records choice asks, never shuffles a looked-at library. */
    static final class ProbeAi extends PlayerControllerAi {
        final List<String> trace;
        /** labeller-v2 (c): record scry / surveil asks and keep the looked-at cards on top (false = Forge AI's answer). */
        boolean planScry;

        @Override
        public org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
            if (!planScry || topN == null) {
                return super.arrangeForScry(topN);
            }
            trace.add("ASK scry " + topN.size());
            return org.apache.commons.lang3.tuple.ImmutablePair.of(new CardCollection(topN), new CardCollection());
        }

        @Override
        public org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
            if (!planScry || topN == null) {
                return super.arrangeForSurveil(topN);
            }
            trace.add("ASK surveil " + topN.size());
            return org.apache.commons.lang3.tuple.ImmutablePair.of(new CardCollection(topN), new CardCollection());
        }

        ProbeAi(Game g, Player p, forge.LobbyPlayer lp, List<String> trace) {
            super(g, p, lp);
            this.trace = trace;
        }

        /**
         * sdcc-tutorcast-1110 (false = off: every answer below is Forge AI's, as before). On: the card a search of our
         * library takes is noted ("PICK id"; {@link #forcePick} names it when set); when the engine then offers to cast
         * that very card, the offer is noted ("FREECAST id" without its mana cost, "PAYCAST id" otherwise) and declined,
         * so the run observes the search and the offer and not what the cast card would do.
         */
        boolean tutorCast;
        String forcePick;
        int libPick = -1;

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
            if (tutorCast && decider == getPlayer() && origin != null && origin.contains(ZoneType.Library)) {
                Card pick = null;
                for (Card c : fetchList) {
                    if (forcePick != null && pick == null && c.getName().equals(forcePick) && c.getOwner() == getPlayer()) {
                        pick = c;
                    }
                }
                if (pick == null) {
                    pick = super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal,
                            selectPrompt, isOptional, decider);
                }
                libPick = pick == null ? -1 : pick.getId();
                trace.add("PICK " + libPick + " " + (pick == null ? "-" : pick.getName()));
                return pick;
            }
            return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt,
                    isOptional, decider);
        }

        /** sdcc-tutorcast-1110: the engine's "which card to play" of a play effect: the card our search just took. */
        @Override
        public <T extends forge.game.GameEntity> T chooseSingleEntityForEffect(forge.util.collect.FCollectionView<T> optionList,
                DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player targetedPlayer,
                Map<String, Object> params) {
            if (tutorCast && libPick >= 0 && sa != null && sa.getApi() == forge.game.ability.ApiType.Play && optionList != null) {
                for (T t : optionList) {
                    if (t instanceof Card c && c.getId() == libPick) {
                        return t;
                    }
                }
            }
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
        }

        /** sdcc-tutorcast-1110: the offer to cast the card our search just took is noted and declined (see {@link #tutorCast}). */
        @Override
        public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
            if (tutorCast && libPick >= 0 && tgtSA != null && tgtSA.getHostCard() != null
                    && tgtSA.getHostCard().getId() == libPick) {
                final forge.game.cost.Cost cost = tgtSA.getPayCosts();
                final boolean noMana = cost == null || !cost.hasManaCost() || cost.getTotalMana() == null
                        || cost.getTotalMana().isZero();
                trace.add((noMana && tgtSA.isSpell() ? "FREECAST " : "PAYCAST ") + libPick);
                return false;
            }
            return super.playSaFromPlayEffect(tgtSA);
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
            if (tutorCast && libPick >= 0 && sa != null && sa.getApi() == forge.game.ability.ApiType.Play
                    && cardToShow != null && cardToShow.getId() == libPick) {
                return true; // sdcc-tutorcast-1110: on to the offer itself (playSaFromPlayEffect notes and declines it)
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
        /**
         * sdcc-draws-1110 (a {@link #PROBE_SETTLE} run only; null otherwise): our seat in the sandbox. A card that enters
         * our library is then preceded by a "LIBPOS id index size" line: where the engine put it.
         */
        final Player seat;

        ZoneRec(List<String> trace, int me) {
            this(trace, me, null);
        }

        ZoneRec(List<String> trace, int me, Player seat) {
            this.trace = trace;
            this.me = me;
            this.seat = seat;
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
            if (seat != null && t.equals("Library@me")) {
                // sdcc-draws-1110: the event comes after the card is in the zone, so its index is where the engine put it
                final CardCollectionView lib = seat.getCardsIn(ZoneType.Library);
                for (int i = 0; i < lib.size(); i++) {
                    if (lib.get(i).getId() == e.card().getId()) {
                        trace.add("LIBPOS " + e.card().getId() + " " + i + " " + lib.size());
                        break;
                    }
                }
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
        return run(live, liveMe, cardId, setup, act, seed, st, null);
    }

    /** As above; sdcc-tutorcast-1110: {@code tune} (null = none) sets up the sandbox's controller for our seat first. */
    static Obs run(Game live, Player liveMe, int cardId, Setup setup, Act act, long seed, Stats st,
            java.util.function.Consumer<ProbeAi> tune) {
        final Obs o = new Obs();
        o.settle = PROBE_SETTLE.get(); // sdcc-draws-1110 (false = as before)
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
                copier.setCopyPending(GameCopier.SOLVER_COPY_PENDING); // gamecopier-fidelity-1030 (review #156 S2: a research copy that feeds labels)
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
            }
            if (!g.getStack().isEmpty()) {
                o.error = "stack";
                return o;
            }
            inPlay(g);
            g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
            final ProbeAi probeAi = new ProbeAi(g, me, me.getController().getLobbyPlayer(), o.trace);
            probeAi.planScry = PLAN_SCRY.get();
            probeAi.tutorCast = TUTOR_CAST.get();
            if (tune != null) {
                tune.accept(probeAi);
            }
            me.dangerouslySetController(probeAi);
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
            g.subscribeToEvents(new ZoneRec(o.trace, me.getId(), o.settle ? me : null));
            final int[] poolBefore = pool(me);
            o.handBefore = me.getCardsIn(ZoneType.Hand).size() - (card.isInZone(ZoneType.Hand) ? 1 : 0);
            o.gyBefore = me.getCardsIn(ZoneType.Graveyard).size();
            o.libBefore = me.getCardsIn(ZoneType.Library).size();
            o.played = act.apply(g, me, card);
            if (!o.played) {
                o.error = o.error == null ? "not played" : o.error;
                return o;
            }
            final int[] poolCast = pool(me);
            resolveAll(g);
            o.libAfter = me.getCardsIn(ZoneType.Library).size();
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
            if (after != null && !after.isInPlay() && after.getOwner() == me) {
                final List<Card> libNow = new ArrayList<>(me.getCardsIn(ZoneType.Library));
                o.hostEnd = after.isInZone(ZoneType.Library) ? (!libNow.isEmpty() && libNow.get(0) == after ? Abil.SELF_TOP : -1)
                        : after.isInZone(ZoneType.Hand) ? Abil.SELF_HAND
                        : after.isInZone(ZoneType.Exile) ? Abil.SELF_EXILE
                        : after.isInZone(ZoneType.Graveyard) ? Abil.SELF_GRAVEYARD : -1;
            }
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

    /**
     * sdcc-tutorcast-1110: the spell of {@code card} matching the template with exactly the optional additional costs
     * {@code optCosts} added (the engine's own list, {@link GameActionUtil#getOptionalCostValues}, by type name), or null.
     * A fresh copy for {@code me}: the cost text of the ability with those costs is the template's {@code costKey}.
     */
    static SpellAbility locateOpt(Card card, Player me, String desc, String costKey, List<String> optCosts) {
        for (SpellAbility sa0 : card.getSpellAbilities()) {
            if (!sa0.isSpell() || !desc.equals(sa0.getDescription())) {
                continue;
            }
            final SpellAbility sa = sa0.copy(me);
            sa.setActivatingPlayer(me);
            final List<forge.game.spellability.OptionalCostValue> chosen = new ArrayList<>();
            try {
                for (forge.game.spellability.OptionalCostValue v : GameActionUtil.getOptionalCostValues(sa)) {
                    if (optCosts.contains(v.getType().name())) {
                        chosen.add(v);
                    }
                }
            } catch (RuntimeException e) {
                continue;
            }
            if (chosen.size() != optCosts.size()) {
                continue;
            }
            final SpellAbility with = GameActionUtil.addOptionalCosts(sa, chosen);
            final String ck = with.getPayCosts() == null ? "" : with.getPayCosts().toString();
            if (ck.equals(costKey)) {
                with.setActivatingPlayer(me);
                return with;
            }
        }
        return null;
    }

    /**
     * sdcc-tutorcast-1110: the names of our cards (wherever they are) that the engine's validity check accepts for the
     * ability's "sacrifice another permanent" cost part; and that part's valid permanents in play now.
     */
    static java.util.Set<String> sacNames(SpellAbility sa, Player me) {
        final java.util.Set<String> out = new java.util.TreeSet<>();
        for (forge.game.cost.CostPart p : sa.getPayCosts().getCostParts()) {
            if (!(p instanceof forge.game.cost.CostSacrifice s) || s.payCostFromSource() || s.getType() == null) {
                continue;
            }
            final String[] valid = s.getType().split(";");
            for (ZoneType z : new ZoneType[] {ZoneType.Battlefield, ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard,
                    ZoneType.Exile}) {
                for (Card c : me.getCardsIn(z)) {
                    if (c.getOwner() == me && c != sa.getHostCard() && c.isValid(valid, me, sa.getHostCard(), sa)) {
                        out.add(c.getName());
                    }
                }
            }
        }
        return out;
    }

    static CardCollection sacValid(SpellAbility sa, Player me) {
        final CardCollection out = new CardCollection();
        for (forge.game.cost.CostPart p : sa.getPayCosts().getCostParts()) {
            if (!(p instanceof forge.game.cost.CostSacrifice s) || s.payCostFromSource() || s.getType() == null) {
                continue;
            }
            for (Card c : forge.game.card.CardLists.getValidCards(me.getCardsIn(ZoneType.Battlefield), s.getType().split(";"), me,
                    sa.getHostCard(), sa)) {
                if (c.canBeSacrificedBy(sa, false)) {
                    out.add(c);
                }
            }
        }
        return out;
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
        int posId = -1, posIndex = -1, posSize = -1; // sdcc-draws-1110: the last LIBPOS line (a settle run only)
        // sdcc-tutorcast-1110 (a tutorCast probe only): the card a search of our library exiled, and what became of it
        final boolean tc = TUTOR_CAST.get();
        int exilePick = -1, castAt = -1;
        java.util.Set<String> exileNames = null;
        final List<Eff> viaExile = new ArrayList<>();
        for (String line : o.trace) {
            if (line.startsWith("LIBPOS ")) {
                final String[] q = line.split(" ");
                posId = Integer.parseInt(q[1]);
                posIndex = Integer.parseInt(q[2]);
                posSize = Integer.parseInt(q[3]);
                continue;
            }
            if (tc && (line.startsWith("PICK ") || line.startsWith("PAYCAST "))) {
                continue;
            }
            if (tc && line.startsWith("FREECAST ")) {
                // the engine offered to cast the searched card without paying its mana cost
                if (Integer.parseInt(line.substring(9)) == exilePick && castAt < 0) {
                    if (last != null) {
                        out.add(new Eff(last, run, 0));
                        last = null;
                        run = 0;
                    }
                    castAt = out.size();
                    o.exileEff = new Eff(EffKind.TUTOR_CAST, 0, 0);
                    out.add(o.exileEff);
                    o.freeCast = true;
                }
                continue;
            }
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
            if (line.startsWith("ASK scry ") || line.startsWith("ASK surveil ")) {
                // labeller-v2 (c) (only a planScry probe records these): the look comes in order with the moves
                if (last != null) {
                    out.add(new Eff(last, run, 0));
                    last = null;
                    run = 0;
                }
                final int n = Integer.parseInt(line.substring(line.lastIndexOf(' ') + 1));
                if (n > 0) {
                    out.add(new Eff(line.startsWith("ASK scry ") ? EffKind.SCRY : EffKind.SURVEIL, n, 0));
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
                if (!o.settle) {
                    continue;
                }
                if (p[3].equals("Library@me")) {
                    // sdcc-draws-1110: the probed card itself goes to our library (its moves were skipped: the library
                    // then read one card short). An effect in order with the draws; the place is where the engine put it
                    // (a library that held nothing else reads as the top).
                    if (last != null) {
                        out.add(new Eff(last, run, 0));
                        last = null;
                        run = 0;
                    }
                    final int place = posId != id || posIndex < 0 ? ForcedWinModel.LIB_SOMEWHERE
                            : posIndex == 0 ? ForcedWinModel.LIB_TOP
                            : posIndex == posSize - 1 ? ForcedWinModel.LIB_BOTTOM : ForcedWinModel.LIB_SOMEWHERE;
                    out.add(new Eff(EffKind.SELF_TO_LIBRARY, place, 0));
                    continue;
                }
                if (!p[2].equals("Library@me")) {
                    continue;
                }
                // it leaves our library again (this very ability put it there): a draw or a mill like any other card's
            }
            final String from = p[2], to = p[3];
            if (tc && exilePick < 0 && from.equals("Library@me") && to.equals("Exile@me") && pendingAsk != null
                    && pendingAsk.startsWith("ASK pick Exile [Library")) {
                // sdcc-tutorcast-1110: the searched card, exiled (it is cast, or put into our hand, from there)
                exilePick = id;
                o.exilePick = id;
                final int ni = pendingAsk.indexOf(" names=");
                exileNames = ni < 0 ? null : new java.util.HashSet<>(java.util.Arrays.asList(pendingAsk.substring(ni + 7).split("\\|")));
                pendingAsk = null;
                continue;
            }
            if (tc && id == exilePick && from.equals("Exile@me") && to.equals("Hand@me")) {
                o.pickToHand = true;
                if (castAt >= 0) {
                    o.exileEff = new Eff(EffKind.TUTOR_CAST, 1, 0); // an uncast pick goes to our hand
                    out.set(castAt, o.exileEff);
                } else {
                    // a search that puts the card into our hand by way of exile: a tutor
                    if (last != null) {
                        out.add(new Eff(last, run, 0));
                        last = null;
                        run = 0;
                    }
                    final Eff tutor = new Eff(EffKind.TUTOR, 1, 0, 0, exileNames);
                    viaExile.add(tutor);
                    o.exileEff = tutor;
                    out.add(tutor);
                }
                exilePick = -2;
                continue;
            }
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
            } else if (e.kind == EffKind.TUTOR && tutorNames != null && !viaExile.contains(e)) {
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
                final Act castIt = (g, m, c) -> {
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
                };
                final long runSeed = ForcedWinSolver.mix(seed, id * 31L + variant++);
                final Obs o = emptyDrawRetry(live, me, id, inHand, castIt, runSeed, st,
                        run(live, me, id, inHand, castIt, runSeed, st), card.getName());
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
                final boolean tutorCast = TUTOR_CAST.get() && sa0.isSpell();
                if (tutorCast) {
                    // sdcc-tutorcast-1110: a search of our library into exile: is the card then offered as a free cast?
                    searchCast(ab, o, live, me, card, avoid -> inHand, castIt, null, ForcedWinSolver.mix(seed, id * 59L + 11), st);
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
                    a2.alt = tutorCast; // sdcc-tutorcast-1110: never the spell an effect casts for free
                    t.fromHand.add(a2);
                }
                if (tutorCast && !ab.unplayable && !ab.cardTargets) {
                    // sdcc-tutorcast-1110: the spell with each of its optional additional costs paid, kept when that
                    // unlocks a search-and-cast
                    learnOptional(t, live, me, card, sa0, base, v, inHand, ForcedWinSolver.mix(seed, id * 61L + 13), st);
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
                    if (PROBE_SETTLE.get()) {
                        // sdcc-draws-1110: what activating it moves besides the mana, as the engine settles it (its own
                        // seed stream: the other probes of this card run on the same seeds with the key on and off)
                        cost.effects.addAll(settledMana(live, me, id, cost,
                                ForcedWinSolver.mix(seed, id * 53L + 17 + t.onBattlefield.size()), st, card.getName()));
                    }
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
                    final Setup onBattlefield = (g, m, c) -> {
                        toBattlefield(g, m, c);
                        if (probeHand >= 0) {
                            setHandSize(g, m, null, probeHand);
                        }
                    };
                    final Act activate = (g, m, c) -> {
                        final SpellAbility sa = locate(c, m, desc, costKey, true);
                        if (sa == null) {
                            return false;
                        }
                        sa.setActivatingPlayer(m);
                        if (v != null && !setPlayerTargets(sa, m, v)) {
                            return false;
                        }
                        return play(g, m, sa);
                    };
                    final long runSeed = ForcedWinSolver.mix(seed, id * 31L + variant++);
                    final Obs o = emptyDrawRetry(live, me, id, onBattlefield, activate, runSeed, st,
                            run(live, me, id, onBattlefield, activate, runSeed, st), card.getName());
                    final Abil ab = withEffects(base, o, id, sa0, v);
                    ab.cardTargets = v == null && targetsAnything(sa0);
                    ab.handMask = handMask;
                    // sdcc-integ-1110: with probeSettle on as well, a source that went to our library is already an effect
                    // of the ability (SELF_TO_LIBRARY, in order with its draws and with the place the engine put it, so a
                    // later draw of the same ability can take it again): that effect owns the move, and selfTo stays 0.
                    // sourceLeaves keeps the ends probeSettle has no effect for (our hand, exile, our graveyard).
                    if (SOURCE_LEAVES.get() && !ab.unplayable && !ab.sacSelf && o.hostEnd != 0
                            && !ab.has(EffKind.SELF_TO_LIBRARY)) {
                        // sdcc-tutorcast-1110: the ability took its own source off the battlefield (not as its cost)
                        if (o.hostEnd > 0) {
                            ab.selfTo = o.hostEnd;
                        } else {
                            t.unmodeled = true; // somewhere the plan has no place for
                        }
                    }
                    if (!ab.unplayable && o.unmapped > 0) {
                        // solver-gaps-1015: an activation the width model cannot express (Sneak Attack)
                        t.unmodeled = true;
                    }
                    t.onBattlefield.add(ab);
                }
            }
            // sacrifice: what happens when it goes to our graveyard from the battlefield (Chromatic Star draws)
            // sdcc-draws-1110: not with probeSettle, where each ability's own sandbox run holds what its sacrifice causes
            // (folding this in as well would count the draw of a probed "sacrifice this:" ability twice)
            if (hasDiesTrigger(card) && !PROBE_SETTLE.get()) {
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
            if (hasDiesTrigger(card) && PROBE_SETTLE.get() && TUTOR_CAST.get()) {
                // sdcc-integ-1110 (both keys only): tutorCast's "sacrifice another permanent" cost plays what that
                // permanent does on leaving play (ACard.dies) before the spell's own effects, and probeSettle does not run
                // the sacrifice probe above (which fails in this engine anyway). So the draw of a permanent sacrificed to
                // another card's cost is observed here, with the parameters the engine's own sacrifice passes. It is not
                // folded into this card's own "sacrifice this:" abilities: their settled runs already hold it.
                final Obs o = run(live, me, id, (g, m, c) -> toBattlefield(g, m, c), (g, m, c) -> {
                    final Card bc = g.findById(c.getId());
                    if (bc == null || !bc.isInPlay()) {
                        return false;
                    }
                    final forge.game.card.CardZoneTable table = new forge.game.card.CardZoneTable(
                            g.copyLastStateBattlefield(), g.copyLastStateGraveyard());
                    final java.util.Map<forge.game.ability.AbilityKey, Object> params = forge.game.ability.AbilityKey.newMap();
                    forge.game.ability.AbilityKey.addCardZoneTableParams(params, table);
                    g.getAction().sacrifice(new CardCollection(bc), null, false, params);
                    table.triggerChangesZoneAll(g, null);
                    return true;
                }, ForcedWinSolver.mix(seed, id * 59L + 23), st);
                effectsFromTrace(o, id, null, null);
                if (o.error == null && o.played) {
                    for (Eff e : o.effects) {
                        if (e.kind == EffKind.DRAW) {
                            t.dies.add(e);
                        }
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

    // ------------------------------------------------------------------ sdcc-draws-1110 (probeSettle)

    /**
     * sdcc-draws-1110 ({@link #PROBE_SETTLE}): what activating a mana ability moves besides its mana. The ability is
     * played in a sandbox with the permanent in play and untapped; the engine pays its cost (a sacrifice of the permanent
     * included), and the stack and every trigger that causes resolve. The cards the engine then moved between our
     * library, hand and graveyard are returned in the order they moved: draws, mills, put-backs, the permanent itself
     * going to our library. The mana stays the engine metadata's (the sandbox's floating mana pays the ability's own mana
     * cost, so the pool does not show what it made). Empty when the sandbox could not play it: the ability is then its
     * mana, as before.
     */
    static List<Eff> settledMana(Game live, Player me, int id, Abil cost, long seed, Stats st, String name) {
        final String desc = cost.desc, costKey = cost.costKey;
        final Setup inPlay = (g, m, c) -> {
            toBattlefield(g, m, c);
            final Card bc = g.findById(c.getId());
            if (bc != null) {
                bc.setTapped(false);
            }
        };
        final Act activate = (g, m, c) -> {
            // the setup may have moved the card (a zone change makes a new object): find it again
            final Card bc = g.findById(c.getId());
            final SpellAbility sa = bc == null ? null : locate(bc, m, desc, costKey, true);
            return sa != null && play(g, m, sa);
        };
        final Obs o = emptyDrawRetry(live, me, id, inPlay, activate, seed, st, run(live, me, id, inPlay, activate, seed, st),
                name);
        final List<Eff> out = new ArrayList<>();
        if (o.error != null || !o.played) {
            ForcedWinSolver.receipt("FORCED_WIN probe-fail card=" + name + " ability=" + cost.kind + " why="
                    + (o.error == null ? "" : o.error.length() > 80 ? o.error.substring(0, 80) : o.error));
            return out;
        }
        effectsFromTrace(o, id, cost, null);
        for (Eff e : o.effects) {
            if (e.kind == EffKind.DRAW || e.kind == EffKind.MILL || e.kind == EffKind.PUTBACK
                    || e.kind == EffKind.SELF_TO_LIBRARY) {
                out.add(e);
            }
        }
        netReceipt(name, cost.kind + "", out, o);
        return out;
    }

    /**
     * sdcc-draws-1110: our library's size after {@code effs}, as the width search counts them, from the sizes a sandbox
     * run started with (a draw or mill takes what is there; a pile leaves its cards; a tutor to hand takes one).
     */
    static int modelLibrary(List<Eff> effs, int library, int hand, int graveyard) {
        int lib = library, h = hand, gy = graveyard;
        for (Eff e : effs) {
            switch (e.kind) {
                case DRAW: {
                    final int d = Math.min(e.n, lib);
                    lib -= d;
                    h += d;
                    break;
                }
                case MILL: {
                    final int d = Math.min(e.n, lib);
                    lib -= d;
                    gy += d;
                    break;
                }
                case PUTBACK: {
                    final int d = Math.min(e.n, h);
                    lib += d;
                    h -= d;
                    break;
                }
                case DISCARD: {
                    final int d = Math.min(e.n, h);
                    h -= d;
                    gy += d;
                    break;
                }
                case TUTOR:
                    if (lib > 0) {
                        lib--;
                        h++;
                    }
                    break;
                case PILE:
                    lib = Math.min(e.n, lib + gy);
                    gy = 0;
                    break;
                case HAND_TO_LIBRARY:
                    lib += h;
                    h = 0;
                    break;
                case GRAVEYARD_TO_LIBRARY:
                    lib += gy;
                    gy = 0;
                    break;
                case EXILE_HAND:
                    h = 0;
                    break;
                case SELF_TO_LIBRARY:
                    lib++;
                    break;
                default:
                    break;
            }
        }
        return lib;
    }

    /**
     * sdcc-draws-1110 ({@link #PROBE_SETTLE}, receipts only): names an ability whose learned effects imply another library
     * size than the engine left in the sandbox: a count of cards the width model still gets wrong.
     */
    static void netReceipt(String name, String ability, List<Eff> effs, Obs o) {
        if (!o.settle || !ForcedWinSolver.RECEIPTS || o.error != null || !o.played || o.won || o.lost) {
            return;
        }
        final int model = modelLibrary(effs, o.libBefore, o.handBefore, o.gyBefore);
        if (model != o.libAfter) {
            ForcedWinSolver.receipt("FORCED_WIN probe-net card=" + name + " ability=" + ability + " library=" + o.libBefore
                    + " model=" + model + " engine=" + o.libAfter + " effects=" + effs);
        }
    }

    // ------------------------------------------------------------------ sdcc-tutorcast-1110: search-and-cast

    /** Our cards a search of our library could find, one per name (library, then hand and graveyard), without {@code selfId}. */
    static List<Card> searchCandidates(Player me, int selfId) {
        final List<Card> out = new ArrayList<>();
        final java.util.Set<String> names = new java.util.HashSet<>();
        for (ZoneType z : new ZoneType[] {ZoneType.Library, ZoneType.Hand, ZoneType.Graveyard}) {
            final List<Card> cs = new ArrayList<>(me.getCardsIn(z));
            cs.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
            for (Card c : cs) {
                if (c.getId() != selfId && c.getOwner() == me && out.size() < CAST_SWEEP && names.add(c.getName())) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /**
     * The few candidates tried first, to see whether the ability offers a free cast at all: the cheapest nonland card, the
     * cheapest creature, the cheapest instant or sorcery, and a land (an order of trial only, never a rule: what the
     * engine offers is observed).
     */
    static List<Card> discover(List<Card> cands) {
        final List<Card> sorted = new ArrayList<>(cands);
        sorted.sort((x, y) -> x.getCMC() != y.getCMC() ? Integer.compare(x.getCMC(), y.getCMC()) : Integer.compare(x.getId(), y.getId()));
        final List<Card> out = new ArrayList<>();
        final List<java.util.function.Predicate<Card>> kinds = List.of(c -> !c.isLand(), Card::isCreature,
                c -> c.isInstant() || c.isSorcery(), Card::isLand);
        for (java.util.function.Predicate<Card> k : kinds) {
            for (Card c : sorted) {
                if (k.test(c)) {
                    if (!out.contains(c) && out.size() < CAST_DISCOVER) {
                        out.add(c);
                    }
                    break;
                }
            }
        }
        return out;
    }

    /**
     * One sandbox run of a searching ability with the search told to take the card named as {@code d} (moved into our
     * library first when it is elsewhere; {@code setupFor} gets its id, to leave it there): {offered as a free cast,
     * ended in our hand}, as observed; {false, false} when the search did not take it.
     */
    static boolean[] castOffer(Game live, Player me, int id, java.util.function.IntFunction<Setup> setupFor, Act act,
            java.util.function.Consumer<ProbeAi> tune, Card d, long seed, Stats st) {
        final int did = d.getId();
        final String name = d.getName();
        final Setup setup = setupFor.apply(did);
        final Obs o = run(live, me, id, (g, m, c) -> {
            if (setup != null) {
                setup.apply(g, m, c);
            }
            final Card x = g.findById(did);
            if (x != null && !x.isInZone(ZoneType.Library)) {
                g.getAction().moveToLibrary(x, -1, null);
            }
        }, act, seed, st, ai -> {
            if (tune != null) {
                tune.accept(ai);
            }
            ai.forcePick = name;
        });
        int pick = -1;
        boolean offered = false, toHand = false;
        for (String line : o.trace) {
            if (line.startsWith("PICK ")) {
                final int sp = line.indexOf(' ', 5);
                pick = sp > 0 && name.equals(line.substring(sp + 1)) ? Integer.parseInt(line.substring(5, sp)) : -1;
            } else if (pick >= 0 && line.equals("FREECAST " + pick)) {
                offered = true;
            } else if (pick >= 0 && line.startsWith("MOVE " + pick + " Exile@me Hand@me")) {
                toHand = true;
            }
        }
        return new boolean[] {offered, toHand};
    }

    /**
     * An ability whose run ({@code first}) searched our library for a card and exiled it: when the engine offers to cast
     * such a card without paying its mana cost (in that run, or for one of a few candidates tried next), the ability is
     * run once per card name of ours with the search told to take that card, and its search effect becomes
     * {@link EffKind#TUTOR_CAST} with the names the engine offered. Nothing is read from the card: what is offered,
     * and where an uncast card goes, is what the sandbox showed.
     */
    static void searchCast(Abil ab, Obs first, Game live, Player me, Card card, java.util.function.IntFunction<Setup> setupFor,
            Act act, java.util.function.Consumer<ProbeAi> tune, long seed, Stats st) {
        if (ab.unplayable || first.exilePick < 0) {
            return;
        }
        final List<Card> cands = searchCandidates(me, card.getId());
        boolean offered = first.freeCast;
        long k = 0;
        if (!offered) {
            for (Card d : discover(cands)) {
                if (castOffer(live, me, card.getId(), setupFor, act, tune, d, ForcedWinSolver.mix(seed, k++), st)[0]) {
                    offered = true;
                    break;
                }
            }
        }
        if (!offered) {
            return;
        }
        final java.util.Set<String> names = new java.util.TreeSet<>();
        boolean toHand = false;
        for (Card d : cands) {
            final boolean[] r = castOffer(live, me, card.getId(), setupFor, act, tune, d, ForcedWinSolver.mix(seed, 100 + k++), st);
            if (r[0]) {
                names.add(d.getName());
            }
            toHand |= r[1];
        }
        final Eff eff = new Eff(EffKind.TUTOR_CAST, toHand ? 1 : 0, 0, 0, names);
        final int at = first.exileEff == null ? -1 : ab.effects.indexOf(first.exileEff);
        if (at >= 0) {
            ab.effects.set(at, eff);
        } else if (!ab.has(EffKind.WIN)) {
            ab.effects.add(eff);
        }
        ForcedWinSolver.receipt("FORCED_WIN probe-search-cast card=" + card.getName() + " cost=" + ab.costString() + " tried="
                + cands.size() + " offered=" + names + " uncastToHand=" + toHand);
    }

    /**
     * The spell with each of its optional additional costs paid (the engine's list, one cost at a time, at most three):
     * a variant whose cost is in the model and whose run offers a free cast of a searched card joins the card's abilities
     * with that cost, the cost's type name (for the follower) and, for a sacrifice, the names of our cards the engine's
     * validity check accepts. Any other variant is left out, as before.
     */
    static void learnOptional(ACard t, Game live, Player me, Card card, SpellAbility sa0, Abil base, String v, Setup inHand,
            long seed, Stats st) {
        final List<forge.game.spellability.OptionalCostValue> opts;
        try {
            opts = GameActionUtil.getOptionalCostValues(sa0);
        } catch (RuntimeException e) {
            return;
        }
        final int id = card.getId();
        int k = 0;
        for (forge.game.spellability.OptionalCostValue ov : opts) {
            if (k++ >= 3) {
                break;
            }
            final List<String> types = List.of(ov.getType().name());
            final SpellAbility with;
            final Abil cost;
            try {
                with = GameActionUtil.addOptionalCosts(sa0, List.of(ov));
                cost = ForcedWinModel.readCost(with, AbilKind.SPELL, true);
            } catch (RuntimeException e) {
                continue;
            }
            if (cost == null || cost.costKey.equals(base.costKey)) {
                continue;
            }
            cost.optCosts = types;
            if (cost.sacN > 0) {
                cost.sacNames = sacNames(with, me);
                if (cost.sacNames.isEmpty()) {
                    continue; // nothing of ours could ever pay it
                }
            }
            final String desc = sa0.getDescription(), costKey = cost.costKey;
            // a card the search is told to take (avoid) is never the permanent put into play to be sacrificed
            final java.util.function.IntFunction<Setup> setupFor = avoid -> (g, m, c) -> {
                inHand.apply(g, m, c);
                ensureSacrifice(g, m, c, desc, costKey, types, avoid);
            };
            final Act act = (g, m, c) -> {
                final Card hc = g.findById(c.getId());
                final SpellAbility sa = hc == null ? null : locateOpt(hc, m, desc, costKey, types);
                if (sa == null || v != null && !setPlayerTargets(sa, m, v) || v == null && targetsAnything(sa)) {
                    return false;
                }
                return play(g, m, sa);
            };
            // the sandbox pays a sacrifice with a permanent that has no leaves-play trigger of its own when it can
            final java.util.function.Consumer<ProbeAi> tune = cost.sacN == 0 ? null : ai -> ai.setCacHook(new forge.ai.CacHook() {
                @Override
                public CardCollectionView costCards(PlayerControllerAi c, SpellAbility sa, String part, CardCollectionView valid,
                        int amount, CardCollectionView def) {
                    if (amount == 1 && valid != null) {
                        for (Card x : valid) {
                            if (!hasDiesTrigger(x)) {
                                return new CardCollection(x);
                            }
                        }
                    }
                    return def;
                }
            });
            final long s2 = ForcedWinSolver.mix(seed, k);
            final Obs o = run(live, me, id, setupFor.apply(-1), act, s2, st, tune);
            final Abil ab = withEffects(cost, o, id, with, v);
            if (ab.unplayable) {
                continue;
            }
            searchCast(ab, o, live, me, card, setupFor, act, tune, ForcedWinSolver.mix(s2, 7), st);
            if (!ab.has(EffKind.TUTOR_CAST) || o.unmapped > 0) {
                continue;
            }
            ab.handMask = base.handMask;
            t.fromHand.add(ab);
        }
    }

    /**
     * In a sandbox: when nothing in play could pay the ability's sacrifice, one of our other cards that could (not
     * {@code avoid}) is put into play.
     */
    static void ensureSacrifice(Game g, Player m, Card c, String desc, String costKey, List<String> types, int avoid) {
        final Card hc = g.findById(c.getId());
        final SpellAbility sa = hc == null ? null : locateOpt(hc, m, desc, costKey, types);
        if (sa == null) {
            return;
        }
        for (forge.game.cost.CostPart p : sa.getPayCosts().getCostParts()) {
            if (!(p instanceof forge.game.cost.CostSacrifice s) || s.payCostFromSource() || s.getType() == null
                    || !sacValid(sa, m).isEmpty()) {
                continue;
            }
            final String[] valid = s.getType().split(";");
            // a card with no trigger or replacement effect of its own first: its entering then shows nothing in the run
            for (int pass = 0; pass < 2; pass++) {
                for (ZoneType z : new ZoneType[] {ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Hand}) {
                    for (Card x : new ArrayList<>(m.getCardsIn(z))) {
                        if (x.getId() != hc.getId() && x.getId() != avoid && x.getOwner() == m && x.isValid(valid, m, hc, sa)
                                && (pass == 1 || x.getTriggers().isEmpty() && x.getReplacementEffects().isEmpty())) {
                            g.getAction().moveToPlay(x, m, null, null);
                            return;
                        }
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

    /**
     * solver-distill-cc-1109 (keepGate): a sandbox of a game that has not started (the mulligan decision) plays as a started
     * one: the copy's stage becomes Play (the live game is never touched). In a started game this does nothing.
     */
    static void inPlay(final Game copy) {
        if (copy.getAge() != forge.game.GameStage.Play && !copy.isGameOver()) {
            copy.setAge(forge.game.GameStage.Play);
        }
    }

    /** mullValue: what a caller does with a sandbox (the copy and our seat in it). */
    public interface SandboxUse<T> {
        T use(Game copy, Player me);
    }

    /**
     * solver-distill-cc-1109 (mullValue): a private copy of the game for one hypothetical opening hand. {@code use} gets the
     * copy before anything is changed (to attach its own trackers), calls {@link #redraw} / {@link #toFirstMain} itself
     * and reads what it needs. Own id scope, AI cache scope and random stream ({@code seed} makes the redrawn hand a
     * function of the game); the live game is never touched. Null when the sandbox failed.
     */
    public static <T> T handSandbox(final Game live, final Player liveMe, final long seed, final SandboxUse<T> use) {
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
                copier.setCopyFidelity(1);
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
            }
            return use.use(g, me);
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        } finally {
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
    }

    /** mullValue: in a sandbox, our hand goes back into our library, the library is shuffled and we draw {@code n}. */
    public static void redraw(final Game copy, final Player me, final int n) {
        for (Card c : new ArrayList<>(me.getCardsIn(ZoneType.Hand))) {
            copy.getAction().moveToLibrary(c, null);
        }
        me.shuffle(null);
        me.drawCards(n);
    }

    /** mullValue: in a sandbox, the hand cards at these positions (hand order) go to the bottom of our library. */
    public static void tuck(final Game copy, final Player me, final int[] handIdx) {
        final List<Card> hand = new ArrayList<>(me.getCardsIn(ZoneType.Hand));
        for (int i : handIdx) {
            if (i >= 0 && i < hand.size()) {
                copy.getAction().moveToLibrary(hand.get(i), -1, null);
            }
        }
    }

    /** mullValue: a sandbox of a game that has not started stands at our first main phase (the stage becomes Play). */
    public static void toFirstMain(final Game copy, final Player me) {
        inPlay(copy);
        copy.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
    }

    /**
     * solver-distill-cc-1109 (planLoops): does the engine win this turn from a sandbox that holds, for our seat, only its
     * lands, the permanent {@code cardId} and (when {@code partnerId} >= 0) that creature, both in play and able to act?
     * Our other nonland permanents and our hand are exiled first; the opponent's board stays. Own id scope, AI cache
     * scope and random stream, as every probe. Never throws (false).
     */
    static boolean sandboxWin(Game live, Player liveMe, int cardId, int partnerId, ForcedWinEngine.Limits lim, long seed,
            long budgetNanos) {
        return sandboxWin(live, liveMe, cardId, partnerId, false, lim, seed, budgetNanos);
    }

    /**
     * As above; {@code fromHand}: the card stays in our hand with plenty of floating mana of every colour (an Aura cast on
     * the partner), instead of being put into play.
     */
    static boolean sandboxWin(Game live, Player liveMe, int cardId, int partnerId, boolean fromHand, ForcedWinEngine.Limits lim,
            long seed, long budgetNanos) {
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
                copier.setCopyFidelity(1);
                copier.setCopyPending(GameCopier.SOLVER_COPY_PENDING);
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
            }
            if (!g.getStack().isEmpty()) {
                return false;
            }
            inPlay(g);
            g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
            final Card s = g.findById(cardId);
            final Card t = partnerId < 0 ? null : g.findById(partnerId);
            if (s == null || partnerId >= 0 && t == null) {
                return false;
            }
            for (Card c : new ArrayList<>(me.getCardsIn(ZoneType.Battlefield))) {
                if (!c.isLand() && c != s && c != t) {
                    g.getAction().exile(c, null, null);
                }
            }
            for (Card c : new ArrayList<>(me.getCardsIn(ZoneType.Hand))) {
                if (c != s && c != t) {
                    g.getAction().exile(c, null, null);
                }
            }
            if (fromHand) {
                if (!s.isInZone(ZoneType.Hand)) {
                    g.getAction().moveToHand(s, null);
                }
            } else {
                toBattlefield(g, me, s);
            }
            if (t != null) {
                toBattlefield(g, me, t);
            }
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                if (c.getId() == cardId || c.getId() == partnerId) {
                    c.setSickness(false);
                    c.setTapped(false);
                }
            }
            resolveAll(g);
            if (!g.getStack().isEmpty() || me.hasLost() || me.hasWon()) {
                return false;
            }
            g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
            if (fromHand) {
                final Card src = g.findById(cardId);
                for (int c = 0; c < 6; c++) {
                    for (int k = 0; k < 12; k++) {
                        me.getManaPool().addMana(new Mana(ForcedWinModel.FORGE_COLOR[c], src, null, me));
                    }
                }
            }
            final long deadline = forge.game.WorkClock.nanoTime() + budgetNanos;
            lim.wall = deadline + budgetNanos;
            return ForcedWinEngine.search(g, me, lim, new ForcedWinEngine.Stats(), deadline, seed) != null;
        } catch (RuntimeException | StackOverflowError e) {
            return false;
        } finally {
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
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
        netReceipt(sa.getHostCard() == null ? "?" : sa.getHostCard().getName(), ab.kind + (variant == null ? "" : "@" + variant),
                ab.effects, o); // sdcc-draws-1110 (a probeSettle run with receipts only)
        return ab;
    }

    static ReplacementEffect firstReplacement(Card c) {
        return c.getReplacementEffects().isEmpty() ? null : c.getReplacementEffects().iterator().next();
    }
}
