package forge.bench.rl;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.bench.PlayerControllerBridge;
import forge.bench.StateEncoder;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardView;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventFlipCoin;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventRollDie;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.IdScope;

/**
 * GAME REVIEW (lane game-review-1010; off unless the interactive server gets {@code -Dforge.interactive.review=<spec>}):
 * an observer of ONE human seat in a replayed live game, for the offline review of that game.
 *
 * <p><b>Frames.</b> At every request the human seat is asked ({@link #onRequest}) and at every priority the OTHER seat
 * receives ({@link GameEventPlayerPriority}), the human seat's own observation (the RL observation the live policy
 * reads, obs-v1 or with {@code obs=2} obs-v2, from the seat's own knowledge: never the opponent's hidden cards) is written as a v(o) leaf frame
 * ({@link RlSearch#leafFrame}, a DECIDE payload with PASS as the only candidate) to the spec's {@code out=} JSONL file,
 * with the public events since the previous frame (casts, lands, resolutions, turns, attacks, blocks, shuffles, coins,
 * dice, library exits, what the seat was shown) and the seat-level facts the review prints (life, hand and library
 * sizes, cards drawn this turn). An offline scorer reads the frames with the checkpoint's value head.
 *
 * <p><b>Searches.</b> At the human seat's priority requests the spec's {@code plan=} file names
 * ({@code {"requests": {"r57": {"pass": false, "card": 74, "ability": "<label>"}}}}), the S1 search the live policy seat
 * runs (S-c: the policy's prior, K worlds of policy play-outs, the v(o) leaf) runs FROM THE HUMAN SEAT with the
 * player's own action as its default candidate, and writes one {@code search} row: the searched entries, their prior,
 * mean P(win) and per-world values, the entry the search would play, and the entry the player played. The search's
 * service and settings are {@code -Dforge.interactive.reviewSearch=<spec>} ({@link RlLiveSeat.Spec}'s format; a review
 * passes {@code budgetMs=0,threads=0}: no wall budget, worlds in order, so the answer does not depend on the host).
 *
 * <p><b>Positions.</b> At a planned request with {@code "state": true}, Forge's own position dump ({@code state} row): the
 * start of a mined puzzle, in the format the puzzle suite installs ({@code GameState}).
 *
 * <p><b>The game is not moved.</b> Every read runs in a fresh {@link IdScope} on a scratch random stream with the AI
 * caches and card memory put back ({@link PlayerControllerBridge#isolated}), so the replay of the journal stays the
 * live game (the journal's inputs still answer the same requests).
 */
public final class RlReviewObserver {
    private final Game game;
    private final Player human;
    private final int humanSeat;
    private final long seed;
    private final CardIndex index;
    private final RlFeaturizer feat;
    private final RlKnowledge know;
    private final Writer out;
    private final long uid;
    private final Map<String, JsonObject> plan;
    private final RlLiveSeat.Spec searchSpec;
    /** The observation schema (1, or 2 for an obs-v2 checkpoint such as R2_F). */
    private final int obs;
    private RlSearch search;
    private int n = 0;
    private int searches = 0;
    private JsonArray events = new JsonArray();
    private boolean ended = false;
    /** True while this observer reads the game: events its own reads fire (LKI checks) are not the game's. */
    private boolean busy = false;
    /** The seat's own library cards it knows (by id), as of the last frame: a draw of one of them is not luck. */
    private final java.util.Set<Integer> knownLibrary = new java.util.HashSet<>();

    private RlReviewObserver(final Game game, final Player human, final int humanSeat, final long seed, final CardIndex index,
            final Writer out, final Map<String, JsonObject> plan, final RlLiveSeat.Spec searchSpec, final int obs) {
        this.game = game;
        this.human = human;
        this.humanSeat = humanSeat;
        this.seed = seed;
        this.index = index;
        this.out = out;
        this.plan = plan;
        this.searchSpec = searchSpec;
        this.obs = obs;
        this.uid = RlSearch.splitmix(seed ^ 0x72657669L);
        this.know = new RlKnowledge(game);
        know.v2 = obs == 2;
        this.feat = new RlFeaturizer(index);
        feat.setVersion(obs);
        feat.reset();
        feat.setKnowledge(know);
    }

    /**
     * Parse {@code spec} ({@code out=<file>,cardIndex=<tsv>[,plan=<file.json>]}) and the optional search spec, and
     * attach to {@code game} (created, not started). Throws on a bad spec: a review run must not silently review nothing.
     */
    public static RlReviewObserver attach(final Game game, final Player human, final int humanSeat, final long seed,
            final String spec, final String searchSpec) throws IOException {
        String outPath = null, cardIndex = null, planPath = null;
        int obs = 1;
        for (String kv : spec.split(",")) {
            final String[] p = kv.split("=", 2);
            if (p.length != 2) {
                throw new IllegalArgumentException("review: bad entry '" + kv + "'");
            }
            switch (p[0].trim()) {
                case "out": outPath = p[1].trim(); break;
                case "cardIndex": cardIndex = p[1].trim(); break;
                case "plan": planPath = p[1].trim(); break;
                case "obs": obs = Integer.parseInt(p[1].trim()); break;
                default: throw new IllegalArgumentException("review: unknown key " + p[0]);
            }
        }
        if (outPath == null || cardIndex == null) {
            throw new IllegalArgumentException("review needs out=<file> and cardIndex=<tsv>");
        }
        if (obs != 1 && obs != 2) {
            throw new IllegalArgumentException("review: obs must be 1 or 2, not " + obs);
        }
        final Map<String, JsonObject> plan = new HashMap<>();
        if (planPath != null) {
            final JsonObject o = JsonParser.parseString(Files.readString(Paths.get(planPath))).getAsJsonObject();
            final JsonObject reqs = o.getAsJsonObject("requests");
            for (String k : reqs.keySet()) {
                plan.put(k, reqs.getAsJsonObject(k));
            }
        }
        final RlLiveSeat.Spec ss = plan.isEmpty() || searchSpec == null ? null : RlLiveSeat.Spec.parse(searchSpec);
        if (!plan.isEmpty() && ss == null) {
            throw new IllegalArgumentException("review: a plan needs -Dforge.interactive.reviewSearch=<spec>");
        }
        if (ss != null && ss.obs() != obs) {
            throw new IllegalArgumentException("review: the search spec's obs=" + ss.obs() + " is not the review's obs=" + obs);
        }
        final Path op = Paths.get(outPath);
        final Writer w = Files.newBufferedWriter(op, StandardCharsets.UTF_8);
        final RlReviewObserver r = new RlReviewObserver(game, human, humanSeat, seed, CardIndex.load(Paths.get(cardIndex)),
                w, plan, ss, obs);
        r.know.attach();
        game.subscribeToEvents(r);
        final JsonObject h = new JsonObject();
        h.addProperty("type", "header");
        h.addProperty("schema", "mtgx-review-frames/1");
        h.addProperty("humanSeat", humanSeat);
        h.addProperty("obs", obs);
        h.addProperty("obsSchemaSha", RlSearch.schemaSha(obs));
        h.addProperty("cardIndexSha", r.index.sha());
        h.addProperty("planned", plan.size());
        if (ss != null) {
            h.add("search", ss.search.toJson());
        }
        r.write(h);
        return r;
    }

    // ------------------------------------------------------------------------------------------------ the knowledge hooks

    /** The human seat was shown {@code cards} (its controller's reveal): as the bridge reports a bridged seat's. */
    public void onReveal(final List<Card> cards, final ZoneType zone, final Player owner) {
        try {
            know.onReveal(game, human, cards, zone, owner);
            if (owner != null && owner != human) {
                // the opponent's hidden cards, shown to the seat: information it did not choose
                final JsonObject e = ev("reveal");
                e.addProperty("owner", seatOf(owner));
                e.addProperty("zone", String.valueOf(zone));
                e.addProperty("n", cards == null ? 0 : cards.size());
                add(e);
            }
        } catch (RuntimeException ex) {
            System.err.println("[review] reveal hook failed: " + ex);
        }
    }

    /** The human seat looked at {@code cards} to arrange them (scry, surveil, orderMoveToZoneList). */
    public void onLook(final List<Card> cards, final ZoneType destination) {
        try {
            know.onLook(game, human, cards, destination);
        } catch (RuntimeException ex) {
            System.err.println("[review] look hook failed: " + ex);
        }
    }

    // ------------------------------------------------------------------------------------------------ frames

    /** The human seat is asked {@code requestId} ({@code kind}); called by the server just before it sends it. */
    public void onRequest(final String requestId, final String kind) {
        if (ended) {
            return;
        }
        sample("request", humanSeat, requestId, kind);
        final JsonObject p = plan.get(requestId);
        if (p == null) {
            return;
        }
        if (p.has("state") && p.get("state").getAsBoolean()) {
            dumpState(requestId);
        }
        if ("priority".equals(kind) && (p.has("card") || p.has("pass"))) {
            searchAt(requestId, p);
        }
    }

    /**
     * Forge's own position dump ({@link forge.game.GameState}, the puzzle format's {@code [state]} body) at a planned
     * request: the start of a mined puzzle. The full position, both seats' hidden cards included, as a puzzle needs it;
     * like every GameState dump it leaves out the stack, continuous effects and the seats' knowledge.
     */
    private synchronized void dumpState(final String requestId) {
        final JsonObject row = new JsonObject();
        row.addProperty("type", "state");
        row.addProperty("requestId", requestId);
        row.addProperty("n", n - 1);
        row.addProperty("stack", game.getStack().size());
        busy = true;
        try {
            final String text = PlayerControllerBridge.isolated(game, () -> IdScope.detached(() -> {
                final forge.game.GameState gs = new forge.game.GameState();
                gs.initFromGame(game);
                return gs.toString();
            }));
            row.addProperty("text", text);
        } catch (RuntimeException ex) {
            row.addProperty("error", String.valueOf(ex));
        } finally {
            busy = false;
        }
        write(row);
    }

    @Subscribe
    public void priority(final GameEventPlayerPriority ev) {
        final Player p = player(ev.priority());
        if (p == null || p == human || ended) {
            return;   // the human seat's priority is its request
        }
        sample("priority", seatOf(p), null, null);
    }

    @Subscribe
    public void cast(final GameEventSpellAbilityCast ev) {
        final JsonObject e = ev("cast");
        try {
            final CardView hv = ev.sa() == null ? null : ev.sa().getHostCard();
            final Card host = hv == null ? null : game.findByView(hv);
            e.addProperty("seat", ev.si() == null ? -1 : seatOf(player(ev.si().getActivatingPlayer())));
            e.addProperty("name", host == null ? "?" : host.getName());
            e.addProperty("ability", ev.sa() != null && !ev.sa().isSpell());
        } catch (RuntimeException ex) {
            e.addProperty("error", String.valueOf(ex));
        }
        add(e);
    }

    @Subscribe
    public void land(final GameEventLandPlayed ev) {
        final JsonObject e = ev("land");
        e.addProperty("seat", seatOf(player(ev.player())));
        e.addProperty("name", ev.land() == null ? "?" : ev.land().getName());
        add(e);
    }

    @Subscribe
    public void resolved(final GameEventSpellResolved ev) {
        final JsonObject e = ev("resolve");
        try {
            final CardView hv = ev.spell() == null ? null : ev.spell().getHostCard();
            e.addProperty("name", hv == null ? "?" : hv.getName());
        } catch (RuntimeException ex) {
            e.addProperty("name", "?");
        }
        e.addProperty("fizzled", ev.hasFizzled());
        add(e);
    }

    @Subscribe
    public void turn(final GameEventTurnBegan ev) {
        final JsonObject e = ev("turn");
        e.addProperty("turn", ev.turnNumber());
        e.addProperty("seat", seatOf(player(ev.turnOwner())));
        add(e);
    }

    @Subscribe
    public void attackers(final GameEventAttackersDeclared ev) {
        final JsonObject e = ev("attack");
        e.addProperty("seat", seatOf(player(ev.player())));
        e.addProperty("n", ev.attackersMap() == null ? 0 : ev.attackersMap().size());
        add(e);
    }

    @Subscribe
    public void blockers(final GameEventBlockersDeclared ev) {
        final JsonObject e = ev("block");
        e.addProperty("seat", seatOf(player(ev.defendingPlayer())));
        int k = 0;
        if (ev.blockers() != null) {
            for (com.google.common.collect.Multimap<CardView, CardView> m : ev.blockers().values()) {
                k += m.size();
            }
        }
        e.addProperty("n", k);
        add(e);
    }

    @Subscribe
    public void zone(final GameEventCardChangeZone ev) {
        if (ev.from() == null || ev.from().zoneType() != ZoneType.Library) {
            return;
        }
        final ZoneType to = ev.to() == null ? null : ev.to().zoneType();
        final int seat = seatOf(player(ev.from().player()));
        if (to == ZoneType.Hand) {
            // a draw (or a tutor to hand): whether the seat knew the card is what separates a known pile from luck
            final JsonObject e = ev("tohand");
            e.addProperty("seat", seat);
            if (seat == humanSeat) {
                final Card c = ev.card() == null ? null : game.findByView(ev.card());
                e.addProperty("known", c != null && knownLibrary.contains(c.getId()));
            }
            add(e);
            return;
        }
        final JsonObject e = ev("libout");
        e.addProperty("seat", seat);
        e.addProperty("to", String.valueOf(to));
        add(e);
    }

    @Subscribe
    public void shuffled(final GameEventShuffle ev) {
        final JsonObject e = ev("shuffle");
        e.addProperty("seat", seatOf(player(ev.player())));
        add(e);
    }

    @Subscribe
    public void coin(final GameEventFlipCoin ev) {
        add(ev("random"));
    }

    @Subscribe
    public void die(final GameEventRollDie ev) {
        add(ev("random"));
    }

    @Subscribe
    public void finished(final GameEventGameFinished ev) {
        finish();
    }

    /** The game is over (or the server is closing): the last events, then the file is closed. */
    public synchronized void finish() {
        if (ended) {
            return;
        }
        ended = true;
        final JsonObject o = new JsonObject();
        o.addProperty("type", "end");
        o.addProperty("n", n);
        o.addProperty("searches", searches);
        o.add("events", events);
        facts(o);
        write(o);
        if (search != null) {
            search.close();
        }
        try {
            out.close();
        } catch (IOException e) {
            System.err.println("[review] close failed: " + e);
        }
    }

    private synchronized void sample(final String what, final int seat, final String requestId, final String kind) {
        final JsonObject o = new JsonObject();
        o.addProperty("type", "frame");
        o.addProperty("n", n++);
        o.addProperty("at", what);
        o.addProperty("seat", seat);
        if (requestId != null) {
            o.addProperty("requestId", requestId);
            o.addProperty("kind", kind);
        }
        o.add("events", events);
        events = new JsonArray();
        facts(o);
        busy = true;
        try {
            if ("request".equals(what) && "priority".equals(kind) && game.getStack().isEmpty()) {
                // the size of the priority menu the S-c search would see (mana abilities are not entries): a review
                // flags only decisions with something to choose
                o.addProperty("menu", PlayerControllerBridge.isolated(game, () -> IdScope.detached(
                        () -> legalSpellAbilities(game, human).size())));
            }
            final byte[] payload = PlayerControllerBridge.isolated(game, () -> IdScope.detached(() -> {
                final RlFeaturizer.Obs obs = feat.observe(game, human, 0, false, null);
                o.addProperty("trunc", obs.truncated);
                return RlWire.encodeDecide(RlSearch.leafFrame(obs, uid, humanSeat, game.getPhaseHandler().getTurn()));
            }));
            o.addProperty("payload", Base64.getEncoder().encodeToString(payload));
            knownLibrary.clear();
            for (Card c : human.getCardsIn(ZoneType.Library)) {
                if (know.knows(humanSeat, c)) {
                    knownLibrary.add(c.getId());
                }
            }
            o.addProperty("knownLibrary", knownLibrary.size());
        } catch (RuntimeException ex) {
            o.addProperty("error", String.valueOf(ex));
        } finally {
            busy = false;
        }
        write(o);
    }

    private void facts(final JsonObject o) {
        try {
            o.addProperty("turn", game.getPhaseHandler().getTurn());
            o.addProperty("phase", String.valueOf(game.getPhaseHandler().getPhase()));
            final Player act = game.getPhaseHandler().getPlayerTurn();
            o.addProperty("active", act == null ? -1 : seatOf(act));
            o.addProperty("stack", game.getStack().size());
            final JsonArray life = new JsonArray(), hand = new JsonArray(), lib = new JsonArray(), drawn = new JsonArray();
            for (Player p : game.getRegisteredPlayers()) {
                life.add(p.getLife());
                hand.add(p.getCardsIn(ZoneType.Hand).size());
                lib.add(p.getCardsIn(ZoneType.Library).size());
                drawn.add(p.getNumDrawnThisTurn());
            }
            o.add("life", life);
            o.add("hand", hand);
            o.add("library", lib);
            o.add("drawn", drawn);
        } catch (RuntimeException ex) {
            o.addProperty("factsError", String.valueOf(ex));
        }
    }

    // ------------------------------------------------------------------------------------------------ the search

    /**
     * The S1 search from the human seat at its priority request {@code requestId}, the player's own action (from the
     * plan) as the default candidate. One {@code search} row either way (a {@code skipped} reason when no search ran).
     */
    private synchronized void searchAt(final String requestId, final JsonObject played) {
        final JsonObject row = new JsonObject();
        row.addProperty("type", "search");
        row.addProperty("requestId", requestId);
        row.addProperty("n", n - 1);
        final long t0 = System.nanoTime();
        busy = true;
        try {
            PlayerControllerBridge.isolated(game, () -> IdScope.detached(() -> {
                searchInto(row, played);
                return null;
            }));
        } catch (RuntimeException ex) {
            row.addProperty("skipped", "error: " + ex);
            ex.printStackTrace(System.err);
        } finally {
            busy = false;
        }
        row.addProperty("wallMs", Math.round((System.nanoTime() - t0) / 1e5) / 10.0);
        searches++;
        write(row);
    }

    private void searchInto(final JsonObject row, final JsonObject played) {
        if (!game.getStack().isEmpty() && !searchSpec.search.stack) {
            row.addProperty("skipped", "stack");
            return;
        }
        final List<SpellAbility> menu = legalSpellAbilities(game, human);
        final JsonObject body = new JsonObject();
        final JsonArray items = new JsonArray();
        items.add(StateEncoder.encodeSpellAbility(null));
        for (SpellAbility sa : menu) {
            items.add(StateEncoder.encodeSpellAbility(sa));
        }
        body.add("menu", items);
        final RlCandidates.Menu m = RlCandidates.build(game, human, "chooseSpellAbilityToPlay", "priority", body, menu);
        if (m == null || m.unposable != null) {
            row.addProperty("skipped", m == null ? "no menu" : "unposable: " + m.unposable);
            return;
        }
        // the entries, as the review prints them
        final JsonArray labels = new JsonArray();
        labels.add("pass");
        for (SpellAbility sa : menu) {
            labels.add(label(sa));
        }
        row.add("menu", labels);
        final int entry = playedEntry(menu, played);
        row.addProperty("playedEntry", entry);
        if (m.trivial || menu.isEmpty()) {
            row.addProperty("skipped", "trivial");
            return;
        }
        if (entry < 0) {
            row.addProperty("skipped", "played action not in the menu");
            return;
        }
        int cand = -1;
        for (int i = 0; i < m.C(); i++) {
            if (RlSearch.choiceOf(m, i) == entry) {
                cand = i;
                break;
            }
        }
        if (cand < 0) {
            row.addProperty("skipped", "played entry has no candidate");
            return;
        }
        final RlFeaturizer.Obs obs = feat.observe(game, human, 0, false, m);
        m.bind(obs, feat, human);
        final RlWire.Decide frame = decideFrame(m, obs);
        if (search == null) {
            search = new RlSearch(searchSpec.search, seed, uid, index, know, "review", "review-" + humanSeat, this.obs);
            search.rowsWanted = true;
        }
        final int before = search.rows().size();
        final int alt = search.decide(game, human, m, frame, cand, menu);
        row.addProperty("departure", alt);
        if (search.rows().size() > before) {
            row.add("search", search.rows().get(search.rows().size() - 1));
        } else {
            row.addProperty("skipped", "the search did not run (service, stack or a one-entry menu)");
        }
    }

    /** The DECIDE frame of the seat's priority menu (RlSeat.frame). */
    private RlWire.Decide decideFrame(final RlCandidates.Menu m, final RlFeaturizer.Obs o) {
        final RlWire.Decide f = o.version == 2 ? RlWire.Decide.v2() : new RlWire.Decide();
        f.gameUid = uid;
        f.decIdx = n;
        f.seat = humanSeat;
        f.family = m.family;
        f.mode = m.mode;
        f.flags = (o.truncated ? RlWire.F_TRUNC_TOKENS : 0) | (o.droppedRefs ? RlWire.F_DROPPED_REFS : 0);
        f.minPick = m.minPick;
        f.maxPick = m.maxPick;
        f.turn = Math.min(0xffff, game.getPhaseHandler().getTurn());
        f.L = o.L;
        f.D = o.D;
        f.C = m.C();
        f.S = m.S();
        f.P = 0;
        f.tokCard = o.tokCard;
        f.tokZone = o.tokZone;
        f.tokAttr = o.tokAttr;
        f.deckCard = o.deckCard;
        f.deckCnt = o.deckCnt;
        System.arraycopy(o.scal, 0, f.scal, 0, RlSchema.N_SCAL);
        System.arraycopy(o.ctx, 0, f.ctx, 0, f.nCtx());
        f.candKind = m.kindA;
        f.candTok = m.tok;
        f.candCard = m.card;
        f.candTgt = m.tgt;
        f.candSlot = m.slot;
        f.candNum = m.num;
        f.candAbility = m.ability;
        f.candFlags = m.flagsA;
        f.slotTok = m.slotTok;
        if (o.version == 2) {
            f.R = o.R;
            f.F = o.F;
            f.Dr = o.Dr;
            f.tokBits = o.tokBits;
            f.relSrc = o.relSrc;
            f.relDst = o.relDst;
            f.relType = o.relType;
            f.relArg = o.relArg;
            f.relNum = o.relNum;
            f.factTok = o.factTok;
            f.factId = o.factId;
            f.factArg = o.factArg;
            f.factNum = o.factNum;
            f.restCard = o.restCard;
            f.restCnt = o.restCnt;
        }
        return f;
    }

    /**
     * The menu entry (1-based; 0 = pass) of the player's action: {@code {"pass": true}}, or {@code card} (the Forge card
     * id the player clicked) and, when that card offered several abilities, the {@code ability} label the player chose.
     * -1 when no entry matches.
     */
    static int playedEntry(final List<SpellAbility> menu, final JsonObject played) {
        if (played.has("pass") && played.get("pass").getAsBoolean()) {
            return 0;
        }
        if (!played.has("card")) {
            return -1;
        }
        final int card = played.get("card").getAsInt();
        final List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) {
            final Card h = menu.get(i).getHostCard();
            if (h != null && h.getId() == card) {
                hits.add(i + 1);
            }
        }
        if (hits.isEmpty()) {
            return -1;
        }
        if (hits.size() == 1 || !played.has("ability") || played.get("ability").isJsonNull()) {
            return hits.get(0);
        }
        final String want = norm(played.get("ability").getAsString());
        for (int e : hits) {
            final SpellAbility sa = menu.get(e - 1);
            if (norm(sa.toString()).equals(want) || norm(sa.getDescription()).equals(want)) {
                return e;
            }
        }
        for (int e : hits) {
            final SpellAbility sa = menu.get(e - 1);
            final String d = norm(sa.toString());
            if (!want.isEmpty() && (d.contains(want) || want.contains(d))) {
                return e;
            }
        }
        return hits.get(0);
    }

    private static String norm(final String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }

    static String label(final SpellAbility sa) {
        final Card h = sa.getHostCard();
        final String name = h == null ? "?" : h.getName();
        if (sa.isLandAbility()) {
            return "Play " + name;
        }
        final String d = sa.getDescription() == null ? "" : sa.getDescription().replaceAll("\\s+", " ").trim();
        return (sa.isSpell() ? "Cast " : "Activate ") + name + (sa.isSpell() || d.isEmpty() ? "" : " — " + d);
    }

    /**
     * The seat's legal priority actions: the bridge's priority menu (PlayerControllerBridge.legalSpellAbilities), for a
     * seat that is not bridged. Built inside the caller's isolation (canPayCost reads the random stream and AI caches).
     */
    static List<SpellAbility> legalSpellAbilities(final Game game, final Player p) {
        final List<SpellAbility> out = new ArrayList<>();
        try {
            final CardCollection lands = ComputerUtilAbility.getAvailableLandsToPlay(game, p);
            if (lands != null) {
                for (Card land : lands) {
                    for (SpellAbility sa : land.getAllPossibleAbilities(p, true)) {
                        if (sa.isLandAbility() && sa.canPlay()) {
                            out.add(sa);
                        }
                    }
                }
            }
            final CardCollection cards = ComputerUtilAbility.getAvailableCards(game, p);
            final List<SpellAbility> all = ComputerUtilAbility.getOriginalAndAltCostAbilities(
                    ComputerUtilAbility.getSpellAbilities(cards, p), p);
            final List<SpellAbility> withVariants = new ArrayList<>(all);
            for (SpellAbility sa : all) {
                try {
                    final List<OptionalCostValue> opts = GameActionUtil.getOptionalCostValues(sa);
                    if (opts == null || opts.isEmpty()) {
                        continue;
                    }
                    final SpellAbility kicked = GameActionUtil.addOptionalCosts(sa, opts);
                    if (kicked != null && kicked != sa) {
                        kicked.setActivatingPlayer(p);
                        withVariants.add(kicked);
                    }
                } catch (RuntimeException e) {
                    // a variant that cannot be built is not offered
                }
            }
            for (SpellAbility sa : withVariants) {
                if (sa.isManaAbility() || sa.isLandAbility()) {
                    continue;
                }
                sa.setActivatingPlayer(p);
                if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, p, sa.isTrigger())
                        || !PlayerControllerBridge.hasEnoughTargets(sa)) {
                    continue;
                }
                out.add(sa);
            }
        } catch (RuntimeException e) {
            System.err.println("[review] legal actions failed, pass only: " + e);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ plumbing

    private static JsonObject ev(final String t) {
        final JsonObject e = new JsonObject();
        e.addProperty("t", t);
        return e;
    }

    private synchronized void add(final JsonObject e) {
        if (!ended && !busy) {
            events.add(e);
        }
    }

    private Player player(final PlayerView v) {
        if (v == null) {
            return null;
        }
        for (Player p : game.getRegisteredPlayers()) {
            if (p.getView() == v || p.getId() == v.getId()) {
                return p;
            }
        }
        return null;
    }

    private int seatOf(final Player p) {
        return p == null ? -1 : game.getRegisteredPlayers().indexOf(p);
    }

    private synchronized void write(final JsonElement o) {
        try {
            out.write(RlWire.canonicalString(o));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            System.err.println("[review] write failed: " + e);
        }
    }
}
