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
            trace.add("ASK pick " + destination + " " + origin + " " + fetchList.size());
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
        public void on(GameEventCardChangeZone e) {
            if (e.card() == null) {
                return;
            }
            final String f = e.from() == null ? "-" : e.from().zoneType() + (e.from().player() == null ? "" : e.from().player().getId() == me ? "@me" : "@opp");
            final String t = e.to() == null ? "-" : e.to().zoneType() + (e.to().player() == null ? "" : e.to().player().getId() == me ? "@me" : "@opp");
            trace.add("MOVE " + e.card().getId() + " " + f + " " + t);
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
            o.stormBefore = g.getStack().getSpellsCastThisTurn().size();
            g.subscribeToEvents(new ZoneRec(o.trace, me.getId()));
            final int[] poolBefore = pool(me);
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
            o.lost = me.hasLost();
            o.entered = card.getGame() == g && me.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c.getId() == cardId);
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
        for (String line : o.trace) {
            if (line.startsWith("ASK pick ")) {
                pendingAsk = line;
                // a pick INTO our library FROM our library or graveyard (not Brainstorm's put-back from hand)
                final int lb = line.indexOf('['), rb = line.indexOf(']');
                final String origins = lb >= 0 && rb > lb ? line.substring(lb, rb) : "";
                if (line.startsWith("ASK pick Library ") && origins.contains("Library")) {
                    pickToLibrary++;
                    pickFromLibraryOrGy |= line.contains("Graveyard") && line.contains("Library, ")
                            || line.contains("[Graveyard, Library]") || line.contains("[Library, Graveyard]");
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
            } else if (from.equals("Library@opp") && to.equals("Graveyard@opp")) {
                k = EffKind.MILL_OPP;
            } else if ((from.equals("Library@me") || from.equals("Graveyard@me")) && to.equals("Exile@me")) {
                exiled++;
                continue;
            }
            pendingAsk = null;
            if (k == null) {
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
        if (pickToLibrary > 0) {
            if (exiled > 0 && pickFromLibraryOrGy) {
                out.add(0, new Eff(EffKind.PILE, pickToLibrary, 0));
            } else {
                out.add(new Eff(EffKind.TUTOR_TOP, pickToLibrary, 0));
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
        if (life != 0 && !o.won) {
            out.add(new Eff(EffKind.LIFE, life, 0));
        }
        if (o.won) {
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
            for (String v : variants) {
                final String desc = sa0.getDescription(), costKey = base.costKey;
                final Obs o = run(live, me, id, (g, m, c) -> {
                    if (!c.isInZone(ZoneType.Hand)) {
                        g.getAction().moveToHand(c, null);
                    }
                }, (g, m, c) -> {
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
                t.fromHand.add(ab);
                if (o.won && t.permanent) {
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
                for (String v : variants) {
                    final String desc = sa0.getDescription(), costKey = base.costKey;
                    final Obs o = run(live, me, id, (g, m, c) -> toBattlefield(g, m, c), (g, m, c) -> {
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
            }
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
