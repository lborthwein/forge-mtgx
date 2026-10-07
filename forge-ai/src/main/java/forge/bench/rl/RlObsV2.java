package forge.bench.rl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.common.collect.Multiset;

import forge.card.ColorSet;
import forge.card.CardTypeView;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.keyword.KeywordWithAmount;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/**
 * Observation {@code mtgx-rl-obs/2} (lane rl-obs-v2-1006; ICR obs-v2-1006-schema, approved 10-06; note N1). The v1
 * observation plus:
 * <ul>
 *   <li>relations: stack targets (and the event tail's), attacks, blocks, attachments, links;</li>
 *   <li>facts: current keywords, per-type counters, copy-of, chosen colour / type / name / number / player, class
 *       level, designations and dungeon rooms, a stack item's X, modes, kicker, costs, cast zone, ability, trigger;
 *       pile membership during a PILE ask;</li>
 *   <li>{@code tok_bits}: current types, colours, land types and state flags of visible tokens;</li>
 *   <li>attrs zone_age and known_to_opp; 37 more ctx features (player counters, designations, per-turn history);</li>
 *   <li>zone 22 o_seen (opponent cards seen face up this game and not shown now, identity only); own face-down
 *       cards' identity; O1 own remaining multiset; the critic-only zones at 23-25.</li>
 * </ul>
 * Visibility is v1's: every token passes {@code CardView.canBeShownTo(seat)}; bits and facts sit only on tokens the
 * seat sees (never on known-hidden or o_seen tokens); an opponent's face-down card is {@code <unk>}. Forge state is
 * read, never written.
 */
final class RlObsV2 {
    private RlObsV2() {
    }

    private static final int NA = RlSchemaV2.N_ATTR;

    /** One token before the cap. */
    private static final class Tok {
        final int card;
        final int zone;
        final float[] attr = new float[NA];
        final Card src;
        final SpellAbilityStackInstance si;
        RlKnowledge.StackEvent ev;
        /** A known-hidden or o_seen token: identity only (no bits, facts or relations). */
        boolean identityOnly;
        long bits;
        final List<int[]> facts = new ArrayList<>(2);

        Tok(final int card, final int zone, final Card src, final SpellAbilityStackInstance si) {
            this.card = card;
            this.zone = zone;
            this.src = src;
            this.si = si;
        }
    }

    /** Builds the v2 observation of {@code seat}. */
    static RlFeaturizer.Obs observe(final RlFeaturizer f, final Game game, final Player seat, final int mullK,
            final boolean withPriv, final RlCandidates.Menu menu) {
        final RlFeaturizer.Obs o = new RlFeaturizer.Obs();
        o.version = 2;
        o.ctx = new float[RlSchemaV2.N_CTX];
        final CardIndex index = f.index();
        final RlKnowledge know = f.knowledge();
        final int me = know == null ? game.getRegisteredPlayers().indexOf(seat) : know.seatOf(seat);
        final Player opp = RlFeaturizer.opponentOf(game, seat);
        final int oppSeat = opp == null ? -1 : game.getRegisteredPlayers().indexOf(opp);
        final PlayerView viewer = seat.getView();
        final List<Tok> toks = new ArrayList<>(96);
        final int turn = game.getPhaseHandler().getTurn();
        final Combat combat = game.getCombat();
        /** Ids whose identity this frame shows (visible face up, own face-down shown, known hidden): not o_seen. */
        final Set<Integer> shown = new HashSet<>();
        final Ctx x = new Ctx(f, index, o, seat, viewer, combat, turn, know, oppSeat, shown);

        // 1. own hand
        for (Card c : seat.getCardsIn(ZoneType.Hand)) {
            x.add(toks, c, RlSchema.Z_U_HAND);
        }
        // 2. creatures and other permanents, own then opponent's
        battlefield(x, toks, seat, true, RlSchema.Z_U_CRE);
        battlefield(x, toks, seat, false, RlSchema.Z_U_OTH);
        if (opp != null) {
            battlefield(x, toks, opp, true, RlSchema.Z_O_CRE);
            battlefield(x, toks, opp, false, RlSchema.Z_O_OTH);
        }
        // 3. the stack, top first
        int sp = 0;
        for (SpellAbilityStackInstance si : game.getStack()) {
            final Card src = si.getSourceCard();
            if (src != null && !src.getView().canBeShownTo(viewer)) {
                sp++;
                continue;
            }
            final boolean hidden = src != null && src.isFaceDown() && !src.getView().canFaceDownBeShownTo(viewer);
            final int card = src == null ? CardIndex.UNK : hidden ? CardIndex.UNK : src.isFaceDown()
                    ? RlFeaturizer.lookupCard(index, src) : f.cardIndex(src, o);
            final Tok t = new Tok(card, si.getActivatingPlayer() == seat ? RlSchema.Z_U_STACK : RlSchema.Z_O_STACK,
                    null, si);
            final SpellAbility sa = si.getSpellAbility();
            t.attr[RlSchema.A_IS_ABILITY] = sa != null && !sa.isSpell() ? 1f : 0f;
            t.attr[RlSchema.A_STACK_POS] = sp / 10f;
            if (src != null) {
                t.attr[RlSchema.A_FACE_DOWN] = src.isFaceDown() ? 1f : 0f;
                t.attr[RlSchema.A_IS_TOKEN] = src.isToken() ? 1f : 0f;
                if (!hidden) {
                    if (sa != null && sa.isSpell()) {
                        shown.add(src.getId());
                        t.bits = bits(src);
                        if (sa.isCopied() || src.isCopiedSpell()) {
                            t.bits |= 1L << RlSchemaV2.B_COPY;
                        }
                        x.cardFacts(t, src, true);
                    }
                    addTriples(t, RlStackFacts.facts(sa, src));
                }
            }
            toks.add(t);
            sp++;
        }
        // 4. lands
        lands(x, toks, seat, RlSchema.Z_U_LAND);
        if (opp != null) {
            lands(x, toks, opp, RlSchema.Z_O_LAND);
        }
        // 5. known opponent-hand cards; known library positions
        if (opp != null) {
            for (Card c : opp.getCardsIn(ZoneType.Hand)) {
                if (c.getView().canBeShownTo(viewer)) {
                    x.add(toks, c, RlSchema.Z_O_HAND_KNOWN);
                } else if (f.knows(seat, c)) {
                    x.addKnown(toks, c, RlSchema.Z_O_HAND_KNOWN);
                }
            }
        }
        library(x, f, toks, seat, seat, RlSchema.Z_U_LIB_KNOWN);
        if (opp != null) {
            library(x, f, toks, seat, opp, RlSchema.Z_O_LIB_KNOWN);
        }
        // 6. graveyards, exiles
        for (Card c : seat.getCardsIn(ZoneType.Graveyard)) {
            x.add(toks, c, RlSchema.Z_U_GONE);
        }
        if (opp != null) {
            for (Card c : opp.getCardsIn(ZoneType.Graveyard)) {
                x.add(toks, c, RlSchema.Z_O_GONE);
            }
        }
        for (Card c : seat.getCardsIn(ZoneType.Exile)) {
            x.exiled(toks, c, RlSchema.Z_U_EXILE);
        }
        if (opp != null) {
            for (Card c : opp.getCardsIn(ZoneType.Exile)) {
                x.exiled(toks, c, RlSchema.Z_O_EXILE);
            }
        }
        // 7. command zone
        for (Player p : game.getPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Command)) {
                if (RlFeaturizer.commandObject(c)) {
                    final Tok t = x.add(toks, c, RlSchema.Z_COMMAND);
                    if (t != null) {
                        if (t.card == CardIndex.UNK) {
                            o.commandUnknown.add(c.getName());
                        }
                        designation(t, c);
                    }
                }
            }
        }
        // 7b. o_seen: opponent cards seen face up this game whose identity this frame does not show
        if (know != null && me >= 0) {
            for (Map.Entry<Integer, String[]> e : know.seenOpponentIds(me).entrySet()) {
                if (shown.contains(e.getKey())) {
                    continue;
                }
                final Tok t = new Tok(resolveNames(index, e.getValue()[0], e.getValue()[1]), RlSchemaV2.Z_O_SEEN,
                        null, null);
                t.identityOnly = true;
                toks.add(t);
                o.oSeen++;
                o.oSeenIds.add(e.getKey());
            }
        }
        // 8. event tail
        if (know != null) {
            int rank = 0;
            for (RlKnowledge.StackEvent e : know.tail()) {
                final boolean own = e.controllerSeat == me;
                int card = CardIndex.UNK;
                if (e.name != null && (!e.faceDown || own)) {
                    if (RlFeaturizer.isEmblemName(e.name)) {
                        card = RlFeaturizer.resolveEmblem(index, e.name, e.fullName);
                    } else {
                        card = e.fullName != null && (e.faceDown || !e.fullName.equals(e.name))
                                ? index.resolve(e.fullName) : CardIndex.UNK;
                    }
                    if (card == CardIndex.UNK && !e.faceDown) {
                        card = index.lookup(e.name);
                    }
                }
                if (card == CardIndex.UNK && !e.faceDown && e.name != null) {
                    o.unknownNames++;
                }
                final Tok t = new Tok(card, own ? RlSchema.Z_U_EVENT : RlSchema.Z_O_EVENT, null, null);
                t.ev = e;
                t.attr[RlSchema.A_IS_ABILITY] = e.ability ? 1f : 0f;
                t.attr[RlSchema.A_FACE_DOWN] = e.faceDown ? 1f : 0f;
                t.attr[RlSchema.A_EVENT_AGE] = rank / 16f;
                if (!e.faceDown || own) {
                    addTriples(t, e.facts);
                }
                toks.add(t);
                rank++;
            }
        }
        // ask-scoped facts: pile membership during a PILE ask
        if (menu != null && menu.family == RlSchema.F_PILE) {
            for (int p = 0; p < menu.piles.size() && p < 2; p++) {
                if (menu.pileHidden.get(p)) {
                    continue;
                }
                final Set<Integer> ids = new HashSet<>();
                for (Card c : menu.piles.get(p)) {
                    ids.add(c.getId());
                }
                for (Tok t : toks) {
                    if (t.src != null && ids.contains(t.src.getId()) && t.zone != RlSchemaV2.Z_O_SEEN) {
                        t.facts.add(new int[] {RlSchemaV2.F_PILE0 + p, 0, -1});
                    }
                }
            }
        }

        // ---- the cap; arrays; positions
        o.tokensBeforeCap = toks.size();
        o.truncated = toks.size() > RlSchemaV2.L_MAX;
        if (o.truncated) {
            o.droppedByZone = new int[RlSchemaV2.ZONES.size()];
            for (int i = RlSchemaV2.L_MAX; i < toks.size(); i++) {
                o.droppedByZone[toks.get(i).zone]++;
            }
        }
        o.L = Math.min(toks.size(), RlSchemaV2.L_MAX);
        o.tokCard = new int[o.L];
        o.tokZone = new byte[o.L];
        o.tokAttr = new float[o.L * NA];
        o.tokBits = new long[o.L];
        final Map<Integer, Integer> preCard = new HashMap<>();
        final Map<Integer, Integer> preStack = new HashMap<>();
        for (int i = 0; i < toks.size(); i++) {
            final Tok t = toks.get(i);
            if (t.src != null) {
                preCard.putIfAbsent(t.src.getId(), i);
            } else if (t.si != null) {
                preStack.putIfAbsent(t.si.getId(), i);
                if (t.si.getSourceCard() != null && t.si.getSpellAbility() != null && t.si.getSpellAbility().isSpell()) {
                    preCard.putIfAbsent(t.si.getSourceCard().getId(), i);
                }
            }
            if (i >= o.L) {
                continue;
            }
            o.tokCard[i] = t.card;
            o.tokZone[i] = (byte) t.zone;
            System.arraycopy(t.attr, 0, o.tokAttr, i * NA, NA);
            o.tokBits[i] = t.bits;
            if (t.src != null) {
                o.posByCardId.putIfAbsent(t.src.getId(), i);
            } else if (t.si != null) {
                o.posByStackId.putIfAbsent(t.si.getId(), i);
                if (t.si.getSourceCard() != null && t.si.getSpellAbility() != null && t.si.getSpellAbility().isSpell()) {
                    o.posByCardId.putIfAbsent(t.si.getSourceCard().getId(), i);
                }
            }
        }

        facts(o, toks);
        relations(o, game, seat, combat, toks, preCard, preStack);

        f.deck(seat, o);
        RlFeaturizer.scalars(game, seat, opp, mullK, o);
        RlFeaturizer.context(game, seat, opp, o);
        contextV2(game, seat, opp, o);
        rest(f, index, seat, viewer, o);
        if (withPriv) {
            privileged(f, index, seat, opp, viewer, o);
        }
        return o;
    }

    // ------------------------------------------------------------------------------------------------ tokens

    /** Per-observation state for adding tokens. */
    private static final class Ctx {
        final RlFeaturizer f;
        final CardIndex index;
        final RlFeaturizer.Obs o;
        final Player seat;
        final PlayerView viewer;
        final Combat combat;
        final int turn;
        final RlKnowledge know;
        final int oppSeat;
        final Set<Integer> shown;

        Ctx(final RlFeaturizer f, final CardIndex index, final RlFeaturizer.Obs o, final Player seat,
                final PlayerView viewer, final Combat combat, final int turn, final RlKnowledge know, final int oppSeat,
                final Set<Integer> shown) {
            this.f = f;
            this.index = index;
            this.o = o;
            this.seat = seat;
            this.viewer = viewer;
            this.combat = combat;
            this.turn = turn;
            this.know = know;
            this.oppSeat = oppSeat;
            this.shown = shown;
        }

        /** A card the seat can see (v1 addCard), with its v2 attrs, bits and facts. */
        Tok add(final List<Tok> toks, final Card c, final int zone) {
            if (c == null || !c.getView().canBeShownTo(viewer)) {
                return null;
            }
            final boolean hidden = c.isFaceDown() && !c.getView().canFaceDownBeShownTo(viewer);
            final int card = hidden ? CardIndex.UNK : c.isFaceDown() ? lookupCounting(c) : f.cardIndex(c, o);
            final Tok t = new Tok(card, zone, c, null);
            final float[] a = t.attr;
            a[RlSchema.A_TAPPED] = c.isTapped() ? 1f : 0f;
            a[RlSchema.A_IS_TOKEN] = c.isToken() ? 1f : 0f;
            a[RlSchema.A_FACE_DOWN] = c.isFaceDown() ? 1f : 0f;
            final boolean onField = c.isInZone(ZoneType.Battlefield);
            if (onField) {
                if (c.isCreature()) {
                    a[RlSchema.A_SICK] = c.isSick() ? 1f : 0f;
                    a[RlSchema.A_POWER] = c.getNetPower() / 10f;
                    a[RlSchema.A_TOUGHNESS] = c.getNetToughness() / 10f;
                    a[RlSchema.A_DAMAGE] = c.getDamage() / 10f;
                }
                if (combat != null) {
                    a[RlSchema.A_ATTACKING] = combat.isAttacking(c) ? 1f : 0f;
                    a[RlSchema.A_BLOCKING] = combat.isBlocking(c) ? 1f : 0f;
                }
                if (c.isPlaneswalker()) {
                    a[RlSchema.A_LOYALTY] = c.getCurrentLoyalty() / 10f;
                }
                a[RlSchema.A_ATTACHED] = c.getEntityAttachedTo() != null ? 1f : 0f;
                a[RlSchema.A_ENTERED_THIS_TURN] = c.getTurnInZone() == turn ? 1f : 0f;
                a[RlSchema.A_CONTROLLER_DIFFERS_OWNER] = c.getController() != c.getOwner() ? 1f : 0f;
            }
            int p1 = 0, m1 = 0, other = 0;
            try {
                for (Multiset.Entry<CounterType> e : c.getCounters().entrySet()) {
                    final CounterType ct = e.getElement();
                    if (ct.is(CounterEnumType.P1P1)) {
                        p1 += e.getCount();
                    } else if (ct.is(CounterEnumType.M1M1)) {
                        m1 += e.getCount();
                    } else if (!ct.is(CounterEnumType.LOYALTY)) {
                        other += e.getCount();
                    }
                }
            } catch (RuntimeException e) {
                // counters unreadable: leave zero
            }
            a[RlSchema.A_PLUS1] = p1 / 5f;
            a[RlSchema.A_MINUS1] = m1 / 5f;
            a[RlSchema.A_OTHER_COUNTERS] = other / 5f;
            // v2 attrs
            if (onField || c.isInZone(ZoneType.Graveyard) || c.isInZone(ZoneType.Exile)
                    || (c.isInZone(ZoneType.Hand) && c.getOwner() == seat)) {
                a[RlSchemaV2.A_ZONE_AGE] = Math.min(10, Math.max(0, turn - c.getTurnInZone())) / 10f;
            }
            if (c.getOwner() == seat && know != null && oppSeat >= 0
                    && (c.isInZone(ZoneType.Hand) || c.isInZone(ZoneType.Library)) && know.knows(oppSeat, c)) {
                a[RlSchemaV2.A_KNOWN_TO_OPP] = 1f;
            }
            if (!hidden) {
                shown.add(c.getId());
            }
            t.bits = bits(c);
            final boolean oppFaceDown = c.isFaceDown() && c.getController() != seat;
            cardFacts(t, c, !oppFaceDown);
            toks.add(t);
            return t;
        }

        /**
         * An exiled card: as {@link #add}; a face-down card the seat may not look at (an opponent's foretold card) is
         * still there to see as a face-down card: {@code <unk>}, face_down, presence only (note N2).
         */
        Tok exiled(final List<Tok> toks, final Card c, final int zone) {
            if (c.getView().canBeShownTo(viewer)) {
                return add(toks, c, zone);
            }
            if (!c.isFaceDown()) {
                return null;
            }
            final Tok t = new Tok(CardIndex.UNK, zone, c, null);
            t.identityOnly = true;
            t.attr[RlSchema.A_FACE_DOWN] = 1f;
            toks.add(t);
            return t;
        }

        /** A hidden card the seat knows: identity only (v1 addKnown), plus known_to_opp for its own library. */
        Tok addKnown(final List<Tok> toks, final Card c, final int zone) {
            final Tok t = new Tok(lookupCounting(c), zone, c, null);
            t.identityOnly = true;
            if (c.getOwner() == seat && know != null && oppSeat >= 0 && know.knows(oppSeat, c)) {
                t.attr[RlSchemaV2.A_KNOWN_TO_OPP] = 1f;
            }
            shown.add(c.getId());
            toks.add(t);
            return t;
        }

        int lookupCounting(final Card c) {
            final int idx = RlFeaturizer.lookupCard(index, c);
            if (idx == CardIndex.UNK) {
                o.unknownNames++;
            }
            return idx;
        }

        /** Keywords and counters always; the other card facts only when {@code full} (not an opponent's face-down). */
        void cardFacts(final Tok t, final Card c, final boolean full) {
            try {
                keywords(t, c);
            } catch (RuntimeException e) {
                // keywords unreadable
            }
            try {
                for (Multiset.Entry<CounterType> e : c.getCounters().entrySet()) {
                    final CounterType ct = e.getElement();
                    int id = ct instanceof CounterEnumType ? RlSchemaV2.factId("COUNTER:" + ((CounterEnumType) ct).name())
                            : -1;
                    if (id <= 0) {
                        id = RlSchemaV2.F_COUNTER_OTHER;
                        o.factsOther++;
                    }
                    t.facts.add(new int[] {id, 0, Math.min(e.getCount(), Short.MAX_VALUE)});
                }
            } catch (RuntimeException e) {
                // counters unreadable
            }
            if (!full) {
                return;
            }
            try {
                if (c.isCloned() || (c.isToken() && c.getCopiedPermanent() != null)) {
                    t.facts.add(new int[] {RlSchemaV2.F_COPY_OF, index.resolve(c.getName()), -1});
                }
                // a chosen value the seat is shown: Forge's view for others' cards (a secret choice never reaches
                // it), the card itself for the seat's own
                final boolean own = c.getController() == seat;
                final forge.game.card.CardView v = c.getView();
                final List<String> cols = own ? toList(c.getChosenColors()) : v.getChosenColors();
                if (cols != null) {
                    for (String col : cols) {
                        final String k = colorLetter(col);
                        if (k != null) {
                            t.facts.add(new int[] {RlSchemaV2.factId("CHOSEN_COLOR:" + k), 0, -1});
                        }
                    }
                }
                final String ty = own ? c.getChosenType() : v.getChosenType();
                if (ty != null && !ty.isEmpty()) {
                    t.facts.add(new int[] {RlSchemaV2.F_CHOSEN_TYPE, RlSchemaV2.subtypeId(ty), -1});
                }
                final List<String> named = own ? c.getNamedCards() : v.getNamedCard();
                if (named != null) {
                    for (String n : named) {
                        if (n != null && !n.isEmpty()) {
                            t.facts.add(new int[] {RlSchemaV2.F_NAMED_CARD, index.resolve(n), -1});
                        }
                    }
                }
                Integer num = null;
                if (own) {
                    num = c.getChosenNumber();
                } else if (v.getChosenNumber() != null && !v.getChosenNumber().isEmpty()) {
                    try {
                        num = Integer.valueOf(v.getChosenNumber().trim());
                    } catch (NumberFormatException e) {
                        num = null;
                    }
                }
                if (num != null) {
                    t.facts.add(new int[] {RlSchemaV2.F_CHOSEN_NUMBER, 0, Math.max(-1, Math.min(num, 32767))});
                }
                final Player cp = own ? c.getChosenPlayer() : null;
                final forge.game.player.PlayerView cpv = own ? null : v.getChosenPlayer();
                if (cp != null || cpv != null) {
                    final boolean isSeat = cp != null ? cp == seat : cpv.getId() == seat.getView().getId();
                    t.facts.add(new int[] {RlSchemaV2.F_CHOSEN_PLAYER, 0, isSeat ? 0 : 1});
                }
                if (c.isClassCard()) {
                    t.facts.add(new int[] {RlSchemaV2.F_CLASS_LEVEL, 0, c.getClassLevel()});
                }
            } catch (RuntimeException e) {
                // a choice Forge cannot give is left out
            }
        }

        private void keywords(final Tok t, final Card c) {
            final Set<Long> seen = new HashSet<>();
            for (KeywordInterface ki : c.getKeywords()) {
                final Keyword k = ki.getKeyword();
                int id = k == null ? -1 : RlSchemaV2.factId("KW:" + k.name());
                if (id <= 0) {
                    id = RlSchemaV2.F_KW_OTHER;
                    o.factsOther++;
                }
                int num = -1;
                if (k == Keyword.PROTECTION) {
                    num = protectionCode(ki.getOriginal());
                } else if (ki instanceof KeywordWithAmount) {
                    try {
                        num = Math.max(-1, Math.min(ki.getAmount(), 32767));
                    } catch (RuntimeException e) {
                        num = -1;
                    }
                }
                if (seen.add(((long) id << 32) | (num & 0xffffffffL))) {
                    t.facts.add(new int[] {id, 0, num});
                }
            }
        }
    }

    private static void battlefield(final Ctx x, final List<Tok> toks, final Player p, final boolean creatures,
            final int zone) {
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isLand() && c.isCreature() == creatures) {
                x.add(toks, c, zone);
            }
        }
    }

    private static void lands(final Ctx x, final List<Tok> toks, final Player p, final int zone) {
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand()) {
                x.add(toks, c, zone);
            }
        }
    }

    private static void library(final Ctx x, final RlFeaturizer f, final List<Tok> toks, final Player seat,
            final Player p, final int zone) {
        int i = 0;
        for (Card c : p.getCardsIn(ZoneType.Library)) {
            Tok t = null;
            if (c.getView().canBeShownTo(x.viewer)) {
                t = x.add(toks, c, zone);
            } else if (f.knows(seat, c)) {
                t = x.addKnown(toks, c, zone);
            }
            if (t != null) {
                t.attr[RlSchema.A_LIB_POS] = i / 10f;
            }
            i++;
        }
    }

    /** DESIG and ROOM facts on a command-zone token. */
    private static void designation(final Tok t, final Card c) {
        if (c.isEmblem()) {
            return;
        }
        final String n = c.getName();
        int id = RlSchemaV2.factId("DESIG:" + n);
        if (id <= 0) {
            id = RlSchemaV2.F_DESIG_OTHER;
        }
        t.facts.add(new int[] {id, 0, -1});
        final String room = c.getCurrentRoom();
        if (room != null && !room.isEmpty()) {
            final int r = RlSchemaV2.factId("ROOM:" + n + "/" + room);
            if (r > 0) {
                t.facts.add(new int[] {r, 0, -1});
            }
        }
    }

    static int resolveNames(final CardIndex index, final String name, final String full) {
        int card = CardIndex.UNK;
        if (full != null && !full.equals(name)) {
            card = index.resolve(full);
        }
        if (card == CardIndex.UNK && name != null) {
            card = index.resolve(name);
        }
        return card;
    }

    private static void addTriples(final Tok t, final int[] flat) {
        for (int i = 0; i + 2 < flat.length; i += 3) {
            t.facts.add(new int[] {flat[i], flat[i + 1], flat[i + 2]});
        }
    }

    private static List<String> toList(final Iterable<String> it) {
        final List<String> out = new ArrayList<>();
        if (it != null) {
            for (String x : it) {
                out.add(x);
            }
        }
        return out;
    }

    static String colorLetter(final String c) {
        if (c == null) {
            return null;
        }
        switch (c.toLowerCase()) {
            case "white": case "w": return "W";
            case "blue": case "u": return "U";
            case "black": case "b": return "B";
            case "red": case "r": return "R";
            case "green": case "g": return "G";
            default: return null;
        }
    }

    /** Protection's quality: a colour mask 1-31 (W 1, U 2, B 4, R 8, G 16), 32 everything, 33 creatures, 34 other. */
    static int protectionCode(final String original) {
        final String s = original == null ? "" : original.toLowerCase();
        if (s.contains("everything")) {
            return 32;
        }
        int m = 0;
        if (s.contains("white")) m |= 1;
        if (s.contains("blue")) m |= 2;
        if (s.contains("black")) m |= 4;
        if (s.contains("red")) m |= 8;
        if (s.contains("green")) m |= 16;
        if (m > 0) {
            return m;
        }
        return s.contains("creature") ? 33 : 34;
    }

    /** The u64 characteristics of a card's current state. */
    static long bits(final Card c) {
        long b = 0;
        try {
            final CardTypeView ty = c.getType();
            if (ty.isArtifact()) b |= 1L << RlSchemaV2.B_ARTIFACT;
            if (ty.isBattle()) b |= 1L << RlSchemaV2.B_BATTLE;
            if (ty.isCreature()) b |= 1L << RlSchemaV2.B_CREATURE;
            if (ty.isEnchantment()) b |= 1L << RlSchemaV2.B_ENCHANTMENT;
            if (ty.isKindred()) b |= 1L << RlSchemaV2.B_KINDRED;
            if (ty.isLand()) b |= 1L << RlSchemaV2.B_LAND;
            if (ty.isPlaneswalker()) b |= 1L << RlSchemaV2.B_PLANESWALKER;
            if (ty.isInstant()) b |= 1L << RlSchemaV2.B_INSTANT;
            if (ty.isSorcery()) b |= 1L << RlSchemaV2.B_SORCERY;
            if (ty.isLegendary()) b |= 1L << RlSchemaV2.B_LEGENDARY;
            if (ty.isBasic()) b |= 1L << RlSchemaV2.B_BASIC;
            if (ty.isSnow()) b |= 1L << RlSchemaV2.B_SNOW;
            if (ty.hasSubtype("Plains")) b |= 1L << RlSchemaV2.B_PLAINS;
            if (ty.hasSubtype("Island")) b |= 1L << RlSchemaV2.B_ISLAND;
            if (ty.hasSubtype("Swamp")) b |= 1L << RlSchemaV2.B_SWAMP;
            if (ty.hasSubtype("Mountain")) b |= 1L << RlSchemaV2.B_MOUNTAIN;
            if (ty.hasSubtype("Forest")) b |= 1L << RlSchemaV2.B_FOREST;
            final ColorSet col = c.getColor();
            if (col.hasWhite()) b |= 1L << RlSchemaV2.B_W;
            if (col.hasBlue()) b |= 1L << RlSchemaV2.B_U;
            if (col.hasBlack()) b |= 1L << RlSchemaV2.B_B;
            if (col.hasRed()) b |= 1L << RlSchemaV2.B_R;
            if (col.hasGreen()) b |= 1L << RlSchemaV2.B_G;
            if (c.isBackSide()) b |= 1L << RlSchemaV2.B_BACK_FACE;
            if (c.isFlipped()) b |= 1L << RlSchemaV2.B_FLIPPED;
            if (c.isCloned() || (c.isToken() && c.getCopiedPermanent() != null)) b |= 1L << RlSchemaV2.B_COPY;
            if (c.isPhasedOut()) b |= 1L << RlSchemaV2.B_PHASED_OUT;
            if (c.hasNoAbilities()) b |= 1L << RlSchemaV2.B_NO_ABILITIES;
            if (granted(c)) b |= 1L << RlSchemaV2.B_GRANTED_ABILITY;
            if (!c.getChangedTextColorWords().isEmpty() || !c.getChangedTextTypeWords().isEmpty()) {
                b |= 1L << RlSchemaV2.B_TEXT_CHANGED;
            }
            if (c.isMonstrous()) b |= 1L << RlSchemaV2.B_MONSTROUS;
            if (c.isRenowned()) b |= 1L << RlSchemaV2.B_RENOWNED;
            if (c.isSuspected()) b |= 1L << RlSchemaV2.B_SUSPECTED;
            if (c.isGoaded()) b |= 1L << RlSchemaV2.B_GOADED;
            if (c.isRingBearer()) b |= 1L << RlSchemaV2.B_RING_BEARER;
        } catch (RuntimeException e) {
            // characteristics unreadable: keep what was read
        }
        return b;
    }

    /** A spell, activated, triggered or static ability on the card that is not its own (granted by an effect). */
    private static boolean granted(final Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (!sa.isIntrinsic()) {
                return true;
            }
        }
        for (Trigger t : c.getTriggers()) {
            if (!t.isIntrinsic()) {
                return true;
            }
        }
        for (StaticAbility st : c.getStaticAbilities()) {
            if (!st.isIntrinsic()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ facts, rels

    private static void facts(final RlFeaturizer.Obs o, final List<Tok> toks) {
        final List<int[]> fs = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) {
            for (int[] q : toks.get(i).facts) {
                if (q[0] <= 0) {
                    continue;
                }
                if (i >= o.L) {
                    o.droppedRefs = true;
                    continue;
                }
                fs.add(new int[] {i, q[0], q[1], q[2]});
            }
        }
        fs.sort(LEX);
        if (fs.size() > RlSchemaV2.F_MAX) {
            o.factsCapped += fs.size() - RlSchemaV2.F_MAX;
            fs.subList(RlSchemaV2.F_MAX, fs.size()).clear();
        }
        o.F = fs.size();
        o.factTok = new short[o.F];
        o.factId = new short[o.F];
        o.factArg = new int[o.F];
        o.factNum = new short[o.F];
        for (int i = 0; i < o.F; i++) {
            final int[] q = fs.get(i);
            o.factTok[i] = (short) q[0];
            o.factId[i] = (short) q[1];
            o.factArg[i] = q[2];
            o.factNum[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, q[3]));
        }
    }

    private static final Comparator<int[]> LEX = (a, b) -> {
        for (int k = 0; k < a.length; k++) {
            if (a[k] != b[k]) {
                return Integer.compare(a[k], b[k]);
            }
        }
        return 0;
    };

    private static void relations(final RlFeaturizer.Obs o, final Game game, final Player seat, final Combat combat,
            final List<Tok> toks, final Map<Integer, Integer> preCard, final Map<Integer, Integer> preStack) {
        final List<int[]> rs = new ArrayList<>();
        final boolean[] idOnly = new boolean[toks.size()];
        for (int i = 0; i < toks.size(); i++) {
            idOnly[i] = toks.get(i).identityOnly;
        }
        final Rel r = new Rel(o, game, seat, preCard, preStack, rs, idOnly);
        for (int i = 0; i < toks.size(); i++) {
            final Tok t = toks.get(i);
            if (t.si != null) {
                // a stack item's targets are announced, so they are public even for a face-down spell
                for (int[] tg : RlStackFacts.targetRefs(game, t.si.getSpellAbility())) {
                    r.target(i, tg);
                }
            } else if (t.ev != null) {
                for (int[] tg : t.ev.targets) {
                    r.target(i, tg);
                }
            } else if (t.src != null && t.zone != RlSchemaV2.Z_O_SEEN && t.zone != RlSchema.Z_O_HAND_KNOWN
                    && t.zone != RlSchema.Z_U_LIB_KNOWN && t.zone != RlSchema.Z_O_LIB_KNOWN) {
                final Card c = t.src;
                if (c.isInZone(ZoneType.Battlefield)) {
                    final GameEntity host = c.getEntityAttachedTo();
                    if (host != null) {
                        r.entity(i, host, RlSchemaV2.R_ATTACHED, 0, -1);
                    }
                    if (combat != null) {
                        if (combat.isAttacking(c)) {
                            final GameEntity def = combat.getDefenderByAttacker(c);
                            if (def != null) {
                                r.entity(i, def, RlSchemaV2.R_ATTACKS, 0, -1);
                            }
                        }
                        if (combat.isBlocking(c)) {
                            for (Card a : combat.getAttackersBlockedBy(c)) {
                                r.entity(i, a, RlSchemaV2.R_BLOCKS, 0, -1);
                            }
                        }
                    }
                    try {
                        for (Card im : c.getImprintedCards()) {
                            r.link(im, i, RlSchemaV2.LINK_IMPRINTED);
                        }
                        for (Card en : c.getEncodedCards()) {
                            r.link(en, i, RlSchemaV2.LINK_ENCODED);
                        }
                        final Card pair = c.getPairedWith();
                        if (pair != null) {
                            r.entity(i, pair, RlSchemaV2.R_LINKED, RlSchemaV2.LINK_PAIRED, -1);
                        }
                        final Card haunt = c.getHaunting();
                        if (haunt != null) {
                            r.entity(i, haunt, RlSchemaV2.R_LINKED, RlSchemaV2.LINK_HAUNTING, -1);
                        }
                    } catch (RuntimeException e) {
                        // links unreadable
                    }
                } else if (c.isInZone(ZoneType.Exile)) {
                    final Card w = c.getExiledWith();
                    if (w != null && w != c) {
                        r.entity(i, w, RlSchemaV2.R_LINKED, RlSchemaV2.LINK_EXILED_WITH, -1);
                    }
                }
            }
        }
        rs.sort(LEX);
        // de-duplicate (an imprinted card reached from its host and from the exile zone, a repeated target)
        final List<int[]> uniq = new ArrayList<>(rs.size());
        for (int[] q : rs) {
            if (uniq.isEmpty() || LEX.compare(uniq.get(uniq.size() - 1), q) != 0) {
                uniq.add(q);
            }
        }
        if (uniq.size() > RlSchemaV2.R_MAX) {
            o.relsCapped += uniq.size() - RlSchemaV2.R_MAX;
            uniq.subList(RlSchemaV2.R_MAX, uniq.size()).clear();
        }
        o.R = uniq.size();
        o.relSrc = new short[o.R];
        o.relDst = new short[o.R];
        o.relType = new byte[o.R];
        o.relArg = new byte[o.R];
        o.relNum = new short[o.R];
        for (int i = 0; i < o.R; i++) {
            final int[] q = uniq.get(i); // {src, type, arg, dst, num}
            o.relSrc[i] = (short) q[0];
            o.relType[i] = (byte) q[1];
            o.relArg[i] = (byte) q[2];
            o.relDst[i] = (short) q[3];
            o.relNum[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, q[4]));
        }
    }

    /** Relation builder: endpoints are pre-cap token indices, kept only when both are under the cap. */
    private static final class Rel {
        final RlFeaturizer.Obs o;
        final Game game;
        final Player seat;
        final Map<Integer, Integer> preCard, preStack;
        final List<int[]> out;
        final boolean[] idOnly;

        Rel(final RlFeaturizer.Obs o, final Game game, final Player seat, final Map<Integer, Integer> preCard,
                final Map<Integer, Integer> preStack, final List<int[]> out, final boolean[] idOnly) {
            this.idOnly = idOnly;
            this.o = o;
            this.game = game;
            this.seat = seat;
            this.preCard = preCard;
            this.preStack = preStack;
            this.out = out;
        }

        void add(final int src, final int dst, final int type, final int arg, final int num) {
            // relations join objects the seat sees now; a known-hidden or o_seen token takes none
            if (idOnly[src] || (dst >= 0 && idOnly[dst])) {
                o.relsUnresolved++;
                return;
            }
            if (src >= o.L || dst >= o.L) {
                o.droppedRefs = true;
                return;
            }
            out.add(new int[] {src, type, arg, dst, num});
        }

        int player(final Player p) {
            return p == seat ? -2 : -3;
        }

        void entity(final int src, final GameEntity e, final int type, final int arg, final int num) {
            if (e instanceof Player) {
                add(src, player((Player) e), type, arg, num);
            } else if (e instanceof Card) {
                final Integer d = preCard.get(((Card) e).getId());
                if (d == null) {
                    o.relsUnresolved++;
                } else {
                    add(src, d, type, arg, num);
                }
            }
        }

        /** A LINKED relation from the linked card's token (if any) to its host at {@code hostIdx}. */
        void link(final Card linked, final int hostIdx, final int arg) {
            final Integer s = preCard.get(linked.getId());
            if (s == null) {
                o.relsUnresolved++;
            } else {
                add(s, hostIdx, RlSchemaV2.R_LINKED, arg, -1);
            }
        }

        void target(final int src, final int[] tg) {
            final int kind = tg[0], ref = tg[1], depth = tg[2], div = tg[3];
            Integer d = null;
            if (kind == RlStackFacts.T_CARD) {
                d = preCard.get(ref);
            } else if (kind == RlStackFacts.T_PLAYER) {
                final Player p = ref >= 0 && ref < game.getRegisteredPlayers().size()
                        ? game.getRegisteredPlayers().get(ref) : null;
                d = p == null ? null : player(p);
            } else if (kind == RlStackFacts.T_STACK) {
                d = preStack.get(ref);
            }
            if (d == null) {
                o.relsUnresolved++;
            } else {
                add(src, d, RlSchemaV2.R_TARGET, depth, div);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ ctx, O1, priv

    private static void contextV2(final Game game, final Player seat, final Player opp, final RlFeaturizer.Obs o) {
        final float[] x = o.ctx;
        x[RlSchemaV2.C_LANDS_PLAYED_OPP] = (opp == null ? 0 : opp.getLandsPlayedThisTurn()) / 2f;
        playerPair(x, RlSchemaV2.C_ENERGY_U, seat, opp, p -> p.getCounters(CounterEnumType.ENERGY) / 10f);
        playerPair(x, RlSchemaV2.C_EXPERIENCE_U, seat, opp, p -> p.getCounters(CounterEnumType.EXPERIENCE) / 10f);
        playerPair(x, RlSchemaV2.C_RAD_U, seat, opp, p -> p.getCounters(CounterEnumType.RAD) / 10f);
        playerPair(x, RlSchemaV2.C_TICKETS_U, seat, opp, p -> p.getCounters(CounterEnumType.TICKET) / 10f);
        playerPair(x, RlSchemaV2.C_RING_U, seat, opp, p -> Math.min(4, p.getNumRingTemptedYou()) / 4f);
        playerPair(x, RlSchemaV2.C_BLESSING_U, seat, opp, p -> p.hasBlessing() ? 1f : 0f);
        playerPair(x, RlSchemaV2.C_DUNGEONS_U, seat, opp, p -> p.getCompletedDungeons().size() / 3f);
        x[RlSchemaV2.C_DAY] = game.isDay() ? 1f : 0f;
        x[RlSchemaV2.C_NIGHT] = game.isNight() ? 1f : 0f;
        playerPair(x, RlSchemaV2.C_LIFE_GAINED_U, seat, opp, p -> p.getLifeGainedThisTurn() / 10f);
        playerPair(x, RlSchemaV2.C_LIFE_LOST_U, seat, opp, p -> p.getLifeLostThisTurn() / 10f);
        playerPair(x, RlSchemaV2.C_COMBAT_DMG_U, seat, opp, p -> p.getAssignedDamage(true, null) / 10f);
        playerPair(x, RlSchemaV2.C_NONCOMBAT_DMG_U, seat, opp, p -> p.getAssignedDamage(false, null) / 10f);
        playerPair(x, RlSchemaV2.C_DRAWN_U, seat, opp, p -> p.getNumDrawnThisTurn() / 5f);
        playerPair(x, RlSchemaV2.C_DISCARDED_U, seat, opp, p -> p.getDiscardedThisTurn().size() / 5f);
        playerPair(x, RlSchemaV2.C_LIFE_LOST_PREV_U, seat, opp, p -> p.getLifeLostLastTurn() / 10f);
        playerPair(x, RlSchemaV2.C_DRAWN_PREV_U, seat, opp, p -> p.getNumDrawnLastTurn() / 5f);
        playerPair(x, RlSchemaV2.C_SPELLS_PREV_U, seat, opp, p -> p.getSpellsCastLastTurn() / 5f);
        playerPair(x, RlSchemaV2.C_LANDS_PREV_U, seat, opp, p -> p.getLandsPlayedLastTurn() / 2f);
    }

    private interface PlayerF {
        float of(Player p);
    }

    /** ctx[i] = of(seat), ctx[i + 1] = of(opp); an unreadable value stays 0. */
    private static void playerPair(final float[] x, final int i, final Player seat, final Player opp, final PlayerF f) {
        try {
            x[i] = f.of(seat);
        } catch (RuntimeException e) {
            x[i] = 0f;
        }
        if (opp != null) {
            try {
                x[i + 1] = f.of(opp);
            } catch (RuntimeException e) {
                x[i + 1] = 0f;
            }
        }
    }

    /**
     * O1: the seat's decklist minus its own cards it can see now (tokens excluded), as (card, count) sorted by card:
     * "not yet seen", a function of the decklist and the visible state only.
     */
    private static void rest(final RlFeaturizer f, final CardIndex index, final Player seat, final PlayerView viewer,
            final RlFeaturizer.Obs o) {
        final TreeMap<Integer, Integer> m = new TreeMap<>();
        for (int i = 0; i < o.D; i++) {
            m.merge(o.deckCard[i], o.deckCnt[i] & 0xff, Integer::sum);
        }
        for (Player p : seat.getGame().getPlayers()) {
            for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile,
                    ZoneType.Command}) {
                for (Card c : p.getCardsIn(z)) {
                    subtractIfOwnSeen(m, index, seat, viewer, c);
                }
            }
        }
        for (SpellAbilityStackInstance si : seat.getGame().getStack()) {
            final Card c = si.getSourceCard();
            if (c != null && si.getSpellAbility() != null && si.getSpellAbility().isSpell()) {
                subtractIfOwnSeen(m, index, seat, viewer, c);
            }
        }
        final List<int[]> es = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : m.entrySet()) {
            if (e.getValue() > 0) {
                es.add(new int[] {e.getKey(), Math.min(255, e.getValue())});
            }
        }
        o.Dr = Math.min(es.size(), RlSchema.D_MAX);
        o.restCard = new int[o.Dr];
        o.restCnt = new byte[o.Dr];
        for (int i = 0; i < o.Dr; i++) {
            o.restCard[i] = es.get(i)[0];
            o.restCnt[i] = (byte) es.get(i)[1];
        }
    }

    private static void subtractIfOwnSeen(final Map<Integer, Integer> m, final CardIndex index, final Player seat,
            final PlayerView viewer, final Card c) {
        if (c.getOwner() != seat || c.isToken() || !c.getView().canBeShownTo(viewer)
                || (c.isFaceDown() && !c.getView().canFaceDownBeShownTo(viewer))) {
            return;
        }
        final String full = RlFeaturizer.fullName(c);
        final int idx = index.resolve(full != null ? full : c.getName());
        m.computeIfPresent(idx, (k, v) -> v - 1);
    }

    private static void privileged(final RlFeaturizer f, final CardIndex index, final Player seat, final Player opp,
            final PlayerView viewer, final RlFeaturizer.Obs o) {
        final List<Card> hidden = new ArrayList<>();
        if (opp != null) {
            for (Card c : opp.getCardsIn(ZoneType.Hand)) {
                if (!c.getView().canBeShownTo(viewer) && !f.knows(seat, c)) {
                    hidden.add(c);
                }
            }
        }
        final int[][] b = RlFeaturizer.privBlock(index, hidden, seat.getCardsIn(ZoneType.Library),
                opp == null ? java.util.Collections.<Card>emptyList() : opp.getCardsIn(ZoneType.Library),
                RlSchemaV2.Z_PRIV_O_HAND, RlSchemaV2.Z_PRIV_U_LIB, RlSchemaV2.Z_PRIV_O_LIB);
        o.hasPriv = true;
        o.P = b[0].length;
        o.privCard = b[0];
        o.privZone = new byte[o.P];
        o.privCnt = new byte[o.P];
        for (int i = 0; i < o.P; i++) {
            o.privZone[i] = (byte) b[1][i];
            o.privCnt[i] = (byte) b[2][i];
        }
    }
}
