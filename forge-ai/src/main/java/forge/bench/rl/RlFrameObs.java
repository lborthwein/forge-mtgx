package forge.bench.rl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.util.IdScope;

/**
 * Lane frame-score-1105 (research; nothing here runs unless a caller asks): the RL featurizer's value frame for a seat
 * of a position that is not a live decision, through the SAME path the frame dump and the labeller use
 * ({@link RlFeaturizer#observe} then {@link RlSearch#leafFrame}, under the bench isolation; {@link RlFrameDump}).
 *
 * <ul>
 *   <li>{@link #valueFrame}: a game and a seat-knowledge tracker as they are.</li>
 *   <li>{@link #featurizeForked}: a state forked inside a running JVM (a {@link forge.ai.simulation.GameCopier} copy,
 *       or a forced replay to an ask) with the live tracker carried over by {@link RlKnowledge#forkFor} (full history,
 *       not rebuilt from text). A forked and unchanged state featurizes byte-identically to the original.</li>
 *   <li>{@link #sidecar} / {@link #applySidecar}: what a Forge {@link forge.game.GameState} text frame does not carry
 *       but the featurizer reads (decklists, the seat-knowledge tracker, per-turn player counters, the starting and
 *       priority players, day/night), with cards referenced by place ({@link #refs}), so a frame installed in another
 *       JVM featurizes as the game it came from.</li>
 * </ul>
 */
public final class RlFrameObs {
    public static final String SIDECAR_SCHEMA = "mtgx-rl-frame-sidecar/1";
    /** The zones a card reference names, in this order (GameState's player zones). */
    static final ZoneType[] REF_ZONES = {ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Battlefield,
        ZoneType.Exile, ZoneType.Command, ZoneType.Sideboard};

    private RlFrameObs() {
    }

    /** One value frame: the DECIDE payload (mtgx-rl label-sink payload bytes), its candidate count and sha256. */
    public static final class Frame {
        public final byte[] payload;
        public final int C;
        public final int obs;
        public final boolean truncated;

        Frame(final byte[] payload, final int C, final int obs, final boolean truncated) {
            this.payload = payload;
            this.C = C;
            this.obs = obs;
            this.truncated = truncated;
        }

        public String sha256() {
            return sha256(payload);
        }

        public static String sha256(final byte[] b) {
            try {
                final StringBuilder s = new StringBuilder();
                for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) {
                    s.append(String.format("%02x", x));
                }
                return s.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * The seat's v(o) frame of {@code game} now, as {@link RlFrameDump} makes it (a fresh featurizer of {@code index}
     * at schema {@code obs} over {@code know}; null = visibility only). Header fields: {@code uid}, the deciding seat,
     * the game turn, {@code decIdx}. Reads the game under the bench isolation (ids, RNG, AI caches restored).
     */
    public static Frame valueFrame(final Game game, final Player seat, final RlKnowledge know, final CardIndex index,
            final int obs, final long uid, final int decIdx) {
        final RlFeaturizer feat = new RlFeaturizer(index);
        feat.setVersion(obs);
        feat.reset();
        feat.setKnowledge(know);
        final int s = game.getRegisteredPlayers().indexOf(seat);
        if (s < 0 || s > 1) {
            throw new IllegalArgumentException("valueFrame: the seat is not a registered player of the game");
        }
        final int turn = game.getPhaseHandler().getTurn();
        return PlayerControllerBridge.isolated(game, () -> IdScope.detached(() -> {
            final RlFeaturizer.Obs o = feat.observe(game, seat, 0, false, null);
            final RlWire.Decide f = RlSearch.leafFrame(o, uid, s, turn);
            f.decIdx = decIdx;
            if (f.hasPriv()) {
                throw new IllegalStateException("valueFrame: a value frame with the privileged block");
            }
            return new Frame(RlWire.encodeDecide(f), f.C, obs, o.truncated);
        }));
    }

    /**
     * A forked state's frame. Reuses the research copy+featurize path of ChooseACard (forge-mtgx #143) and the
     * look-ahead's leaves: the copy is {@link #forkCopy} (ChooseACard's GameCopier settings), the tracker is
     * {@code live.forkFor(copy)} exactly as {@link RlSearch#onCopy} makes it (not attached here; {@link #forkKnowledge}
     * attaches for a play-out), and the frame is {@link RlSearch#captureLeaf}'s observe + {@link RlSearch#leafFrame}
     * with {@code decIdx} set (captureLeaf leaves it 0), under the bench isolation. {@code copy} is any game whose card
     * and player ids are the live game's; {@code seat} its registered player index.
     */
    public static Frame featurizeForked(final Game copy, final int seat, final RlKnowledge live, final CardIndex index,
            final int obs, final long uid, final int decIdx) {
        final RlKnowledge k = live == null ? null : live.forkFor(copy);
        return valueFrame(copy, copy.getRegisteredPlayers().get(seat), k, index, obs, uid, decIdx);
    }

    /**
     * ChooseACard's copy of a live game for valuing an answer (GameCopier with plain AI players, copy fidelity 1, the
     * solver's pending-copy mode), under the live game's monitor. Unlike ChooseACard's answer copy, it also copies the
     * stack when {@link forge.ai.simulation.GameCopier#stackUnsupported} accepts it, and gives priority to the live
     * game's priority player (GameCopier's devModeSet hands it to the active player), so a fork of the live game
     * featurizes as the live game. Returns {copy, the copy's {@code me}, why the stack was not copied (null if it was
     * copied or is empty, or why a fidelity step failed)}: a fork with a non-null reason is approximate. Exiled-with links
     * and the stack's cast choices are set here rather than in GameCopier, so the solver's copies do not change.
     */
    public static Object[] forkCopy(final Game live, final Player me) {
        synchronized (live) {
            final forge.ai.simulation.GameCopier copier = new forge.ai.simulation.GameCopier(live, true);
            copier.setCopyFidelity(1);
            copier.setCopyPending(forge.ai.simulation.GameCopier.SOLVER_COPY_PENDING);
            final String why = live.getStack().isEmpty() ? null : forge.ai.simulation.GameCopier.stackUnsupported(live);
            copier.setCopyStack(!live.getStack().isEmpty() && why == null);
            final Game g = copier.makeCopy();
            final Player pr = live.getPhaseHandler().getPriorityPlayer();
            if (pr != null) {
                g.getPhaseHandler().setPriority(copier.find(pr));
            }
            String approx = why;
            try {
                forkLinks(live, g);
                if (why == null && !live.getStack().isEmpty()) {
                    forkStackChoices(live, g, copier);
                }
            } catch (RuntimeException e) {
                approx = why != null ? why : "fidelity: " + e;
            }
            return new Object[] {g, copier.find(me), approx};
        }
    }

    /** Exiled-with links, which GameCopier leaves out. A live link to an LKI of the card itself stays an LKI link. */
    private static void forkLinks(final Game live, final Game g) {
        for (Card c : live.getCardsIn(ZoneType.Exile)) {
            final Card w = c.getExiledWith();
            final Card n = w == null ? null : g.findById(c.getId());
            if (n == null) {
                continue;
            }
            final Card m = g.findById(w.getId());
            if (m != null) {
                n.setExiledWith(w != c && m == n ? forge.game.card.CardCopyService.getLKICopy(n) : m);
            }
        }
        final List<Player> from = live.getRegisteredPlayers(), to = g.getRegisteredPlayers();
        for (Card c : live.getCardsIn(ZoneType.Exile)) {
            final Card n = c.isFaceDown() ? g.findById(c.getId()) : null;
            if (n == null) {
                continue;
            }
            n.updateStateForView(); // GameCopier turns it face down without updating its view: it would show
            for (int i = 0; i < from.size(); i++) {
                if (c.getView().canFaceDownBeShownTo(from.get(i).getView())
                        && !n.getView().canFaceDownBeShownTo(to.get(i).getView())) {
                    n.addMayLookFaceDownExile(to.get(i));
                }
            }
        }
        for (int i = 0; i < from.size(); i++) {
            for (Card c : from.get(i).getCardsIn(ZoneType.Command)) {
                if (c.getType().isDungeon()) {
                    forkDungeon(c, to.get(i), g);
                }
            }
        }
    }

    /** A venture dungeon as the frame sees it (type, room, keywords): GameCopier does not carry it. Its room triggers are not copied. */
    private static void forkDungeon(final Card c, final Player owner, final Game g) {
        Card n = null;
        for (Card x : owner.getCardsIn(ZoneType.Command)) {
            n = x.getId() == c.getId() ? x : n;
        }
        if (n == null) {
            n = new Card(c.getId(), c.getPaperCard(), g);
            n.setOwner(owner);
            owner.getZone(ZoneType.Command).add(n);
        }
        n.setGamePieceType(c.getGamePieceType());
        n.setName(c.getName());
        n.setType(new forge.card.CardType(c.getType()));
        n.setCurrentRoom(c.getCurrentRoom());
        final List<String> has = new ArrayList<>();
        for (forge.game.keyword.KeywordInterface k : n.getKeywords()) {
            has.add(k.getOriginal());
        }
        final List<String> add = new ArrayList<>();
        for (forge.game.keyword.KeywordInterface k : c.getKeywords()) {
            if (!has.contains(k.getOriginal())) {
                add.add(k.getOriginal());
            }
        }
        n.addIntrinsicKeywords(add, false); // no traits: Dungeon: builds its room triggers from SVars this card lacks
        n.updateKeywordsCache();
    }

    /**
     * Cast choices GameCopier's faithful stack copy leaves out: the alternative cost, and a modal spell's chosen modes
     * chained as sub-abilities with their targets, and the stack instance ids (a tracked event's target refers to one).
     * Both stacks list top first and have the same size. The copy's stack is rebuilt last, as an instance captures its
     * sub-abilities when built.
     */
    private static void forkStackChoices(final Game live, final Game g, final forge.ai.simulation.GameCopier copier) {
        final java.util.Iterator<SpellAbilityStackInstance> copied = g.getStack().iterator();
        final List<SpellAbility> sas = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        for (SpellAbilityStackInstance si : live.getStack()) {
            final SpellAbility a = si.getSpellAbility();
            final SpellAbility b = copied.next().getSpellAbility();
            sas.add(b);
            ids.add(si.getId());
            b.setAlternativeCost(a.getAlternativeCost());
            if (a.getChosenList() != null && b.getChosenList() != null && b.getSubAbility() == null) {
                forge.game.ability.effects.CharmEffect.chainAbilities(b, b.getChosenList());
                for (SpellAbility x = a.getSubAbility(), y = b.getSubAbility(); x != null && y != null;
                        x = x.getSubAbility(), y = y.getSubAbility()) {
                    for (forge.game.GameObject t : x.getTargets()) {
                        final forge.game.GameObject m = copier.findWithStack(t);
                        y.getTargets().add(m);
                        if (x.isDividedAsYouChoose()) {
                            y.addDividedAllocation(m, x.getDividedValue(t));
                        }
                    }
                }
            }
        }
        g.getStack().clear();
        for (int i = sas.size() - 1; i >= 0; i--) {
            g.getStack().dangerouslyPushCopy(sas.get(i), ids.get(i));
        }
    }

    /** RlSearch.onCopy's tracker for a copy: the live one carried over and attached (a play-out follows it). */
    public static RlKnowledge forkKnowledge(final RlKnowledge live, final Game copy, final int obs) {
        final RlKnowledge k;
        if (live == null) {
            k = new RlKnowledge(copy);
            k.v2 = obs == 2;
        } else {
            k = live.forkFor(copy);
        }
        k.attach();
        return k;
    }

    // ------------------------------------------------------------------------------------------------ card refs

    /** Card id -> its place "p&lt;seat&gt;:&lt;Zone&gt;:&lt;index&gt;" (zone order as Forge lists it; "stack:&lt;i&gt;" top first). */
    public static Map<Integer, String> refs(final Game game) {
        final Map<Integer, String> m = new HashMap<>();
        final List<Player> ps = game.getRegisteredPlayers();
        for (int i = 0; i < ps.size(); i++) {
            for (ZoneType z : REF_ZONES) {
                int k = 0;
                for (Card c : ps.get(i).getCardsIn(z, false)) {
                    m.putIfAbsent(c.getId(), "p" + i + ":" + z.name() + ":" + k++);
                }
            }
        }
        int k = 0;
        for (SpellAbilityStackInstance si : game.getStack()) {
            final Card c = si.getSourceCard();
            if (c != null) {
                m.putIfAbsent(c.getId(), "stack:" + k);
            }
            k++;
        }
        return m;
    }

    /** The inverse of {@link #refs}. */
    public static Map<String, Integer> idsByRef(final Game game) {
        final Map<String, Integer> m = new HashMap<>();
        for (Map.Entry<Integer, String> e : refs(game).entrySet()) {
            m.put(e.getValue(), e.getKey());
        }
        return m;
    }

    /** A card's ref, or "#&lt;id&gt;" for a card no zone holds now (it never names a card of another game). */
    static String refOf(final Map<Integer, String> refs, final int id) {
        final String r = refs.get(id);
        return r != null ? r : "#" + id;
    }

    /** A ref's card id in a game: its card there, else a negative id no card has ("#" refs and unplaced places). */
    static int idOf(final Map<String, Integer> ids, final String ref) {
        final Integer id = ids.get(ref);
        if (id != null) {
            return id;
        }
        final int h = ref.startsWith("#") ? Integer.parseInt(ref.substring(1)) : ref.hashCode() & 0x3fffffff;
        return -1 - Math.abs(h);
    }

    // ------------------------------------------------------------------------------------------------ the sidecar

    /**
     * What the featurizer reads that a {@link forge.game.GameState} text frame of {@code game} does not carry. Card
     * names here are data (the games' own decklists), never rules.
     */
    public static JsonObject sidecar(final Game game, final RlKnowledge know, final List<List<Object[]>> decks) {
        final Map<Integer, String> refs = refs(game);
        final JsonObject o = new JsonObject();
        o.addProperty("schema", SIDECAR_SCHEMA);
        final List<Player> ps = game.getRegisteredPlayers();
        o.addProperty("starting", ps.indexOf(game.getStartingPlayer()));
        o.addProperty("priority", ps.indexOf(game.getPhaseHandler().getPriorityPlayer()));
        final Boolean day = game.getDayTime();
        if (day == null) {
            o.add("day", JsonNull.INSTANCE);
        } else {
            o.addProperty("day", day);
        }
        o.addProperty("stack", game.getStack().size());
        final JsonArray pl = new JsonArray();
        for (Player p : ps) {
            final JsonObject x = new JsonObject();
            x.addProperty("mulligans", p.getStats().getMulliganCount());
            x.addProperty("drawn_this", p.getNumDrawnThisTurn());
            x.addProperty("drawn_last", p.getNumDrawnLastTurn());
            x.addProperty("life_gained_this", p.getLifeGainedThisTurn());
            x.addProperty("life_lost_this", p.getLifeLostThisTurn());
            x.addProperty("life_lost_last", p.getLifeLostLastTurn());
            x.addProperty("spells_last", p.getSpellsCastLastTurn());
            x.addProperty("blessing", p.hasBlessing());
            final JsonArray sp = new JsonArray();
            for (SpellAbility sa : game.getStack().getSpellsCastThisTurn()) {
                if (p.equals(sa.getActivatingPlayer())) {
                    sp.add(sa.getHostCard() == null ? "#0" : refOf(refs, sa.getHostCard().getId()));
                }
            }
            x.add("spells_this", sp);
            final JsonArray di = new JsonArray();
            for (Card c : p.getDiscardedThisTurn()) {
                di.add(refOf(refs, c.getId()));
            }
            x.add("discarded_this", di);
            final JsonArray dm = new JsonArray();
            for (Pair<Integer, Boolean> d : p.getDamageReceivedThisTurn()) {
                final JsonArray y = new JsonArray();
                y.add(d.getLeft());
                y.add(d.getRight());
                dm.add(y);
            }
            x.add("damage_this", dm);
            pl.add(x);
        }
        o.add("players", pl);
        // each player zone in Forge's order: (name, turn it entered the zone). GameState text keeps neither the order
        // an install produces for every zone (tokens, attachments) nor turnInZone (entered_this_turn, zone age)
        final JsonObject zs = new JsonObject();
        for (int i = 0; i < ps.size(); i++) {
            for (ZoneType z : REF_ZONES) {
                final JsonArray a = new JsonArray();
                for (Card c : ps.get(i).getCardsIn(z, false)) {
                    final JsonArray y = new JsonArray();
                    y.add(c.getName());
                    y.add(c.getTurnInZone());
                    a.add(y);
                }
                if (a.size() > 0) {
                    zs.add("p" + i + ":" + z.name(), a);
                }
            }
        }
        o.add("zones", zs);
        // look permissions GameState text drops (a revealed library card, an opponent's hand card shown, a face-down
        // exiled card its exiler may look at): [ref, seat, "shown" | "face_down"]
        final JsonArray vis = new JsonArray();
        for (int i = 0; i < ps.size(); i++) {
            for (ZoneType z : new ZoneType[] {ZoneType.Library, ZoneType.Hand, ZoneType.Exile}) {
                for (Card c : ps.get(i).getCardsIn(z, false)) {
                    for (int v = 0; v < ps.size(); v++) {
                        final forge.game.player.PlayerView pv = ps.get(v).getView();
                        final boolean exFd = z == ZoneType.Exile && c.isFaceDown();
                        if (z == ZoneType.Exile && !exFd || z == ZoneType.Hand && v == i) {
                            continue;
                        }
                        final boolean shown = exFd ? c.getView().canFaceDownBeShownTo(pv) : c.getView().canBeShownTo(pv);
                        if (shown) {
                            final JsonArray y = new JsonArray();
                            y.add(refOf(refs, c.getId()));
                            y.add(v);
                            y.add(exFd ? "face_down" : "shown");
                            vis.add(y);
                        }
                    }
                }
            }
        }
        o.add("visible", vis);
        // links between objects the text may drop: [ref, kind, ref | "player:<seat>"]
        final JsonArray lk = new JsonArray();
        for (int i = 0; i < ps.size(); i++) {
            for (ZoneType z : REF_ZONES) {
                for (Card c : ps.get(i).getCardsIn(z, false)) {
                    final String r = refOf(refs, c.getId());
                    try {
                        if (z == ZoneType.Battlefield) {
                            final forge.game.GameEntity host = c.getEntityAttachedTo();
                            if (host != null) {
                                link(lk, r, "attached", entityRef(refs, ps, host));
                            }
                            for (Card im : c.getImprintedCards()) {
                                link(lk, r, "imprinted", refOf(refs, im.getId()));
                            }
                            for (Card en : c.getEncodedCards()) {
                                link(lk, r, "encoded", refOf(refs, en.getId()));
                            }
                            if (c.getPairedWith() != null) {
                                link(lk, r, "paired", refOf(refs, c.getPairedWith().getId()));
                            }
                            if (c.getHaunting() != null) {
                                link(lk, r, "haunting", refOf(refs, c.getHaunting().getId()));
                            }
                        } else if (z == ZoneType.Exile && c.getExiledWith() != null && c.getExiledWith() != c) {
                            link(lk, r, "exiled_with", refOf(refs, c.getExiledWith().getId()));
                        }
                    } catch (RuntimeException e) {
                        // a link unreadable: left to the text
                    }
                }
            }
        }
        o.add("links", lk);
        // combat (GameState text restores it only in the declare steps)
        final forge.game.combat.Combat cb = game.getCombat();
        if (cb != null) {
            final JsonObject co = new JsonObject();
            final JsonArray at = new JsonArray(), bl = new JsonArray();
            for (Card a : cb.getAttackers()) {
                final JsonArray y = new JsonArray();
                y.add(refOf(refs, a.getId()));
                y.add(entityRef(refs, ps, cb.getDefenderByAttacker(a)));
                at.add(y);
            }
            for (Card b : cb.getAllBlockers()) {
                final JsonArray y = new JsonArray();
                y.add(refOf(refs, b.getId()));
                final JsonArray as = new JsonArray();
                for (Card a : cb.getAttackersBlockedBy(b)) {
                    as.add(refOf(refs, a.getId()));
                }
                y.add(as);
                bl.add(y);
            }
            co.add("attackers", at);
            co.add("blockers", bl);
            o.add("combat", co);
        }
        if (decks != null) {
            final JsonArray da = new JsonArray();
            for (List<Object[]> d : decks) {
                final JsonArray a = new JsonArray();
                for (Object[] e : d) {
                    final JsonArray y = new JsonArray();
                    y.add((String) e[0]);
                    y.add((Integer) e[1]);
                    a.add(y);
                }
                da.add(a);
            }
            o.add("decks", da);
        }
        if (know != null) {
            o.add("knowledge", know.snapshot(id -> refOf(refs, id)));
        }
        return o;
    }

    /** The main decklists of a game's registered players, as (PaperCard name, count) in deck order. */
    public static List<List<Object[]>> decks(final Game game) {
        final List<List<Object[]>> out = new ArrayList<>();
        for (Player p : game.getRegisteredPlayers()) {
            final List<Object[]> d = new ArrayList<>();
            final forge.game.player.RegisteredPlayer rp = p.getRegisteredPlayer();
            final forge.deck.Deck deck = rp == null ? null : rp.getDeck();
            final forge.deck.CardPool main = deck == null ? null : deck.getMain();
            if (main != null) {
                for (Map.Entry<forge.item.PaperCard, Integer> e : main) {
                    d.add(new Object[] {e.getKey().getName(), e.getValue()});
                }
            }
            out.add(d);
        }
        return out;
    }

    /**
     * Puts a {@link #sidecar}'s state on {@code game} (a GameState frame just installed; decklists are the caller's,
     * at game creation): the starting and priority players, day/night, each player's per-turn counters; returns the
     * restored seat-knowledge tracker (null when the sidecar has none), not attached. A spell cast this turn whose card
     * the frame cannot place counts through a stand-in host.
     */
    public static RlKnowledge applySidecar(final Game game, final JsonObject sc) {
        if (!SIDECAR_SCHEMA.equals(sc.get("schema").getAsString())) {
            throw new IllegalArgumentException("sidecar schema " + sc.get("schema"));
        }
        final List<Player> ps = game.getRegisteredPlayers();
        if (sc.has("zones")) {
            zones(ps, sc.getAsJsonObject("zones"));
        }
        final Map<String, Integer> ids = idsByRef(game);
        final int st = sc.get("starting").getAsInt();
        if (st >= 0) {
            game.setStartingPlayer(ps.get(st));
        }
        final int pr = sc.get("priority").getAsInt();
        if (pr >= 0) {
            game.getPhaseHandler().setPriority(ps.get(pr));
        }
        final JsonElement day = sc.get("day");
        if (day != null && !day.isJsonNull() && !Boolean.valueOf(day.getAsBoolean()).equals(game.getDayTime())) {
            game.setDayTime(day.getAsBoolean());
        }
        final JsonArray pl = sc.getAsJsonArray("players");
        for (int i = 0; i < ps.size() && i < pl.size(); i++) {
            final Player p = ps.get(i);
            final JsonObject x = pl.get(i).getAsJsonObject();
            for (int m = p.getStats().getMulliganCount(); m < x.get("mulligans").getAsInt(); m++) {
                p.getStats().notifyHasMulliganed();
            }
            setInt(p, "numDrawnThisTurn", x.get("drawn_this").getAsInt());
            p.setNumDrawnLastTurn(x.get("drawn_last").getAsInt());
            p.setLifeGainedThisTurn(x.get("life_gained_this").getAsInt());
            p.setLifeLostThisTurn(x.get("life_lost_this").getAsInt());
            p.setLifeLostLastTurn(x.get("life_lost_last").getAsInt());
            p.setSpellsCastLastTurn(x.get("spells_last").getAsInt());
            if (x.get("blessing").getAsBoolean() && !p.hasBlessing()) {
                p.setBlessing(true, null);
            }
            final List<SpellAbility> cast = game.getStack().getSpellsCastThisTurn();
            cast.removeIf(sa -> p.equals(sa.getActivatingPlayer()));
            for (JsonElement e : x.getAsJsonArray("spells_this")) {
                final Integer id = ids.get(e.getAsString());
                Card host = id == null ? null : game.findById(id);
                if (host == null) {
                    host = new Card(-900000 - cast.size(), game);
                    host.setOwner(p);
                }
                cast.add(new SpellAbility.EmptySa(host, p));
            }
            p.resetDiscardedThisTurn();
            for (JsonElement e : x.getAsJsonArray("discarded_this")) {
                final Integer id = ids.get(e.getAsString());
                Card c = id == null ? null : game.findById(id);
                if (c == null) {
                    c = new Card(-910000 - p.getDiscardedThisTurn().size(), game);
                    c.setOwner(p);
                }
                p.getDiscardedThisTurn().add(c);
            }
            p.getDamageReceivedThisTurn().clear();
            for (JsonElement e : x.getAsJsonArray("damage_this")) {
                final JsonArray y = e.getAsJsonArray();
                p.receiveDamage(Pair.of(y.get(0).getAsInt(), y.get(1).getAsBoolean()));
            }
        }
        if (sc.has("visible")) {
            for (JsonElement e : sc.getAsJsonArray("visible")) {
                final JsonArray y = e.getAsJsonArray();
                final Integer id = ids.get(y.get(0).getAsString());
                final Card c = id == null ? null : game.findById(id);
                if (c == null) {
                    continue;
                }
                final Player v = ps.get(y.get(1).getAsInt());
                if ("face_down".equals(y.get(2).getAsString())) {
                    if (!c.getView().canFaceDownBeShownTo(v.getView())) {
                        c.addMayLookFaceDownExile(v);
                    }
                } else if (!c.getView().canBeShownTo(v.getView())) {
                    c.addMayLookAt(game.getNextTimestamp(), java.util.Collections.singletonList(v));
                }
            }
        }
        if (sc.has("links")) {
            for (JsonElement e : sc.getAsJsonArray("links")) {
                final JsonArray y = e.getAsJsonArray();
                final Card c = cardOf(game, ids, y.get(0).getAsString());
                final String kind = y.get(1).getAsString(), to = y.get(2).getAsString();
                if (c == null) {
                    continue;
                }
                try {
                    if ("attached".equals(kind)) {
                        final forge.game.GameEntity h = entityOf(game, ids, ps, to);
                        if (h != null && c.getEntityAttachedTo() != h) {
                            c.attachToEntity(h, null, true);
                        }
                        continue;
                    }
                    final Card d = cardOf(game, ids, to);
                    if (d == null) {
                        continue;
                    }
                    if ("imprinted".equals(kind) && !c.getImprintedCards().contains(d)) {
                        c.addImprintedCard(d);
                    } else if ("encoded".equals(kind) && !c.getEncodedCards().contains(d)) {
                        c.addEncodedCard(d);
                    } else if ("paired".equals(kind) && c.getPairedWith() != d) {
                        c.setPairedWith(d);
                    } else if ("haunting".equals(kind) && c.getHaunting() != d) {
                        c.setHaunting(d);
                    } else if ("exiled_with".equals(kind) && c.getExiledWith() != d) {
                        c.setExiledWith(d);
                    }
                } catch (RuntimeException ex) {
                    // left as installed
                }
            }
        }
        if (sc.has("combat") && game.getCombat() == null && game.getPhaseHandler().getPhase() != null
                && game.getPhaseHandler().getPhase().name().startsWith("COMBAT_")) {
            final JsonObject co = sc.getAsJsonObject("combat");
            final forge.game.combat.Combat cb = new forge.game.combat.Combat(game.getPhaseHandler().getPlayerTurn());
            for (JsonElement e : co.getAsJsonArray("attackers")) {
                final JsonArray y = e.getAsJsonArray();
                final Card a = cardOf(game, ids, y.get(0).getAsString());
                final forge.game.GameEntity d = entityOf(game, ids, ps, y.get(1).getAsString());
                if (a != null && d != null) {
                    cb.addAttacker(a, d);
                }
            }
            for (JsonElement e : co.getAsJsonArray("blockers")) {
                final JsonArray y = e.getAsJsonArray();
                final Card b = cardOf(game, ids, y.get(0).getAsString());
                for (JsonElement x : y.get(1).getAsJsonArray()) {
                    final Card a = cardOf(game, ids, x.getAsString());
                    if (a != null && b != null && cb.isAttacking(a)) {
                        cb.addBlocker(a, b);
                    }
                }
            }
            game.getPhaseHandler().setCombat(cb);
        }
        if (!sc.has("knowledge")) {
            return null;
        }
        return RlKnowledge.restore(game, sc.getAsJsonObject("knowledge"), ref -> idOf(ids, ref));
    }

    /**
     * Each zone put in the sidecar's order (cards matched by name, first unmatched first; a zone whose names do not
     * match is left as installed) and each matched card's turnInZone set.
     */
    static void zones(final List<Player> ps, final JsonObject zs) {
        for (int i = 0; i < ps.size(); i++) {
            for (ZoneType z : REF_ZONES) {
                final JsonArray want = zs.has("p" + i + ":" + z.name()) ? zs.getAsJsonArray("p" + i + ":" + z.name())
                        : new JsonArray();
                final forge.game.zone.PlayerZone zone = ps.get(i).getZone(z);
                if (zone == null) {
                    continue;
                }
                final List<Card> have = new ArrayList<>(ps.get(i).getCardsIn(z, false));
                if (have.size() != want.size()) {
                    continue;
                }
                final List<Card> pool = new ArrayList<>(have), order = new ArrayList<>();
                for (JsonElement e : want) {
                    final String n = e.getAsJsonArray().get(0).getAsString();
                    Card hit = null;
                    for (Card c : pool) {
                        if (n.equals(c.getName())) {
                            hit = c;
                            break;
                        }
                    }
                    if (hit == null) {
                        break;
                    }
                    pool.remove(hit);
                    order.add(hit);
                }
                if (order.size() != have.size()) {
                    continue;
                }
                if (!order.equals(have)) {
                    zone.setCards(order);
                }
                for (int k = 0; k < order.size(); k++) {
                    order.get(k).setTurnInZone(want.get(k).getAsJsonArray().get(1).getAsInt());
                }
            }
        }
    }

    private static void link(final JsonArray lk, final String a, final String kind, final String b) {
        final JsonArray y = new JsonArray();
        y.add(a);
        y.add(kind);
        y.add(b);
        lk.add(y);
    }

    static String entityRef(final Map<Integer, String> refs, final List<Player> ps, final forge.game.GameEntity e) {
        if (e instanceof Player) {
            return "player:" + ps.indexOf(e);
        }
        return e instanceof Card ? refOf(refs, ((Card) e).getId()) : "#none";
    }

    static Card cardOf(final Game g, final Map<String, Integer> ids, final String ref) {
        final Integer id = ids.get(ref);
        return id == null ? null : g.findById(id);
    }

    static forge.game.GameEntity entityOf(final Game g, final Map<String, Integer> ids, final List<Player> ps,
            final String ref) {
        if (ref.startsWith("player:")) {
            final int i = Integer.parseInt(ref.substring(7));
            return i >= 0 && i < ps.size() ? ps.get(i) : null;
        }
        return cardOf(g, ids, ref);
    }

    private static void setInt(final Object o, final String field, final int v) {
        try {
            final java.lang.reflect.Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.setInt(o, v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("sidecar: cannot set " + field + ": " + e);
        }
    }

    /** An {@code mtgx-rl-labelsink/1} record (as the dump's rows): {@code MXL1 | u32le hlen | header | u32le plen | payload}. */
    public static byte[] record(final JsonObject header, final byte[] payload) {
        return RlReplayLabeller.record(header, payload);
    }

    /** A UTF-8 string's sha256 (hex). */
    public static String sha256(final String s) {
        return Frame.sha256(s.getBytes(StandardCharsets.UTF_8));
    }
}
