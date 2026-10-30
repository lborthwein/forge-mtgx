package forge.bench.rl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.ai.AiTapeController;
import forge.ai.CubeComboPlayerController;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.AssemblyWorlds;
import forge.ai.simulation.ForcedWinSolver;
import forge.ai.simulation.GameCopier;
import forge.ai.simulation.PlayoutKeys;
import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.PriorityDecisionListener;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterType;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.IdScope;
import forge.util.MyRandom;

/**
 * r1-assembly-bank-1029 (BVW R1, metric M2; research only, off unless a replay config has a {@code bank} block): the
 * assembly-decision bank on FORCED replays of decision-logged tapes.
 *
 * <p><b>scan</b>: a {@link AiTapeController.Probe} shown every forced choice of the game's winning seat P; within the
 * window (P's own turns before the tape's end) it writes one row per assembly-relevant choice: the method, the options
 * it offered (by name), the record's answer, P's zones. Read-only, under the bench isolation; the game's rows are
 * written only when its replay is digest-equal (the probe did not move it).
 *
 * <p><b>label</b>: one job = one (decision, option). The replay is forced up to P's choice number {@code ordinal}; there
 * the job's answer (or script, for a compound choice: Brainstorm's two picks, a Doomsday pile's five picks and order) is
 * given in the record's place and both seats' tapes are detached, so the seats decide live from then on (their solvers
 * on the counted spec, {@code forge.ai.forcedWin.spec}). At the first empty-stack priority after that the probe
 * captures P's v(o) frame and a state summary, then plays the position out in W honest worlds (copies; the opponent's
 * hidden cards re-drawn from the belief pool; P's library: every position P knows kept, the unknown positions shuffled,
 * the FIRST unknown position stratified over the unknown cards sorted by name; both seats Forge AI + the counted honest
 * forced-win solver) to a horizon in P's own turns. The option's value = the share of worlds P wins by the horizon.
 */
public final class RlAssemblyBank implements AiTapeController.Probe, PriorityDecisionListener {
    public static final String SCHEMA = "mtgx-r1-assembly-bank/1";

    /** Thrown on the game thread after a label job's capture: the replay has done its work. */
    public static final class Done extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Done() {
            super("r1 assembly bank: job done", null, false, false);
        }
    }

    public static final class Spec {
        public String mode;
        public int window = 3;
        public int mullWindow = 5;
        public int worlds = 16;
        public int horizonExtra = 1;
        public int horizonCap = 4;
        public int maxSteps = 200000;
        public int maxPriorityWait = 400;
        public String profile = "HonestForcedWin";
        public long seed = 1029L;
        public String out;
        public final JsonObject json;

        public Spec(final JsonObject o) {
            json = o;
            mode = o.get("mode").getAsString();
            if (!"scan".equals(mode) && !"label".equals(mode)) {
                throw new IllegalArgumentException("bank.mode is scan or label, not " + mode);
            }
            if (o.has("window")) window = o.get("window").getAsInt();
            if (o.has("mullWindow")) mullWindow = o.get("mullWindow").getAsInt();
            if (o.has("worlds")) worlds = o.get("worlds").getAsInt();
            if (o.has("horizonExtra")) horizonExtra = o.get("horizonExtra").getAsInt();
            if (o.has("horizonCap")) horizonCap = o.get("horizonCap").getAsInt();
            if (o.has("maxSteps")) maxSteps = o.get("maxSteps").getAsInt();
            if (o.has("maxPriorityWait")) maxPriorityWait = o.get("maxPriorityWait").getAsInt();
            if (o.has("profile")) profile = o.get("profile").getAsString();
            if (o.has("seed")) seed = o.get("seed").getAsLong();
            out = o.get("out").getAsString();
            if ("label".equals(mode)) {
                final String spec = System.getProperty("forge.ai.forcedWin.spec", "");
                if (!spec.contains("budget=counter") || !Boolean.getBoolean("forge.bench.sequentialAi")) {
                    throw new IllegalArgumentException("refused: bank label mode needs the counted solver spec "
                            + "(-Dforge.ai.forcedWin.spec=...budget=counter...) and -Dforge.bench.sequentialAi=true");
                }
            }
        }

        public String sha() {
            final JsonObject o = json.deepCopy();
            o.remove("out");
            o.remove("jobs");
            o.addProperty("solverSpec", System.getProperty("forge.ai.forcedWin.spec", ""));
            o.addProperty("beliefSpec", AssemblyWorlds.beliefSpecSha());
            return CardIndex.sha256(RlWire.canonicalString(o).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** One JSONL file per JVM. */
    public static final class Sink {
        private final Path file;

        public Sink(final Path dir, final String mode) throws IOException {
            Files.createDirectories(dir);
            file = dir.resolve("bank-" + mode + "-" + ProcessHandle.current().pid() + ".jsonl");
        }

        public synchronized void write(final List<JsonObject> rows) {
            if (rows.isEmpty()) {
                return;
            }
            final StringBuilder sb = new StringBuilder();
            for (JsonObject r : rows) {
                sb.append(RlWire.canonicalString(r)).append('\n');
            }
            try {
                Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException("bank sink: " + e, e);
            }
        }

        public Path file() {
            return file;
        }
    }

    private final Spec spec;
    private final Sink sink;
    private final JsonObject job; // label mode: the job, else null
    private final Game game;
    private final RlKnowledge know;
    private final RlFeaturizer feat;
    private final String uid;
    private final long uidL;
    private final int tapeTurns;
    private final int winner; // P's seat, or -1 (no winner: nothing to do)
    private final JsonObject tape;
    private final List<JsonObject> scanRows = new ArrayList<>();
    private final Map<String, String> lastPrio = new LinkedHashMap<>();
    private final java.util.TreeMap<String, Integer> census = new java.util.TreeMap<>();
    private int parity = -1;
    // label state
    private boolean diverted = false;
    private boolean captured = false;
    private int waitPrio = 0;
    private int divertTurn = -1;
    private final Deque<JsonObject> script = new ArrayDeque<>();
    private JsonObject divertInfo = new JsonObject();
    private boolean pendingRecDetach = false;
    public String error;

    public RlAssemblyBank(final Spec spec, final Sink sink, final JsonObject job, final JsonObject tapeLine,
            final Game game, final RlKnowledge know, final CardIndex index, final int obs) {
        this.spec = spec;
        this.sink = sink;
        this.job = job;
        this.tape = tapeLine;
        this.game = game;
        this.know = know;
        this.feat = new RlFeaturizer(index);
        feat.setVersion(obs);
        feat.reset();
        feat.setKnowledge(know);
        this.uid = tapeLine.get("game_uid").getAsString();
        this.uidL = RlWire.parseUid(uid);
        this.tapeTurns = tapeLine.get("turns").getAsInt();
        final JsonArray res = tapeLine.getAsJsonArray("result");
        int w = -1;
        for (int i = 0; i < res.size(); i++) {
            if (res.get(i).getAsInt() == 1) {
                w = i;
            }
        }
        this.winner = w;
        if (job != null) {
            for (JsonElement e : job.getAsJsonArray("script")) {
                script.add(e.getAsJsonObject());
            }
        }
    }

    public void attach() {
        AiTapeController.PROBES.put(game, this);
        game.addPriorityDecisionListener(this);
    }

    /** The game is over (or stopped). Scan rows only when the replay was digest-equal. */
    public JsonObject finish(final boolean equal) {
        AiTapeController.PROBES.remove(game);
        final JsonObject r = new JsonObject();
        r.addProperty("bank_mode", spec.mode);
        if ("scan".equals(spec.mode)) {
            r.addProperty("bank_rows", equal ? scanRows.size() : 0);
            final JsonObject cj = new JsonObject();
            census.forEach(cj::addProperty);
            r.add("bank_census", cj);
            if (equal) {
                sink.write(scanRows);
            }
        } else {
            r.addProperty("bank_diverted", diverted);
            r.addProperty("bank_captured", captured);
            if (!captured) {
                final JsonObject row = header();
                row.addProperty("kind", "label");
                row.add("job", job);
                row.addProperty("error", error != null ? error : diverted ? "no capture" : "decision not reached");
                sink.write(List.of(row));
            }
        }
        if (error != null) {
            r.addProperty("bank_error", error);
        }
        return r;
    }

    // ------------------------------------------------------------------ turns

    private Player seatP() {
        return winner < 0 ? null : game.getRegisteredPlayers().get(winner);
    }

    private void learnParity() {
        if (parity < 0 && seatP() != null) {
            final PhaseHandler ph = game.getPhaseHandler();
            if (ph.getPlayerTurn() != null && ph.getTurn() > 0) {
                parity = ph.getPlayerTurn() == seatP() ? ph.getTurn() % 2 : (ph.getTurn() + 1) % 2;
            }
        }
    }

    /** P's own turns among global turns 1..u. */
    private int ownIdx(final int u) {
        if (parity < 0 || u <= 0) {
            return 0;
        }
        return parity == 1 ? (u + 1) / 2 : u / 2;
    }

    private int ownBefore(final int t) {
        return ownIdx(tapeTurns) - ownIdx(t);
    }

    private JsonObject header() {
        final JsonObject r = new JsonObject();
        r.addProperty("schema", SCHEMA);
        r.addProperty("game_uid", uid);
        r.addProperty("seat", winner);
        r.addProperty("tape_turns", tapeTurns);
        r.add("reason", tape.get("reason"));
        r.addProperty("spec_sha", spec.sha());
        if (tape.has("meta") && tape.getAsJsonObject("meta").has("families")) {
            r.add("families", tape.getAsJsonObject("meta").get("families"));
            r.add("deck_ids", tape.getAsJsonObject("meta").get("deck_ids"));
        }
        return r;
    }

    // ------------------------------------------------------------------ the probe

    @Override
    public Object divert(final AiTapeController c, final String method, final Object[] args, final int ordinal) {
        learnParity();
        if (job == null || diverted || c.getPlayer() != seatP()) {
            return NONE;
        }
        if (ordinal != job.get("ordinal").getAsInt()) {
            return NONE;
        }
        if (!method.equals(job.get("method").getAsString())) {
            error = "ordinal " + ordinal + " is " + method + ", the job says " + job.get("method").getAsString();
            return NONE;
        }
        final JsonObject first = script.poll();
        if (first == null) {
            error = "empty script";
            return NONE;
        }
        divertInfo.addProperty("turn", game.getPhaseHandler().getTurn());
        divertInfo.addProperty("phase", String.valueOf(game.getPhaseHandler().getPhase()));
        if ("rec".equals(first.get("t").getAsString())) {
            pendingRecDetach = true; // the record's own answer: taken from the tape, then the tapes detach (seen)
            return NONE;
        }
        final Object a;
        try {
            a = answer(c, method, args, first);
        } catch (RuntimeException e) {
            error = "answer: " + e;
            return NONE;
        }
        detachAll();
        return a;
    }

    @Override
    public void seen(final AiTapeController c, final String method, final Object[] args, final int ordinal,
            final Object value) {
        learnParity();
        if (pendingRecDetach) {
            pendingRecDetach = false;
            detachAll();
            return;
        }
        if ("scan".equals(spec.mode) && "chooseSpellAbilityToPlay".equals(method)) {
            // the census's timing split (both seats): stack empty or not, own turn or not, play or pass
            final PhaseHandler ph0 = game.getPhaseHandler();
            final String key = (c.getPlayer() == seatP() ? "winner" : "loser") + "|stack" + (game.getStack().isEmpty() ? 0 : 1)
                    + "|" + (ph0.getPlayerTurn() == c.getPlayer() ? "own" : "opp") + "|"
                    + (value == null || ((List<?>) value).isEmpty() ? "pass" : "play");
            census.merge(key, 1, Integer::sum);
        }
        if (!"scan".equals(spec.mode) || c.getPlayer() != seatP()) {
            return;
        }
        final int turn = game.getPhaseHandler().getTurn();
        final int d = ownBefore(turn);
        final boolean mull = "mulliganKeepHand".equals(method) || "tuckCardsViaMulligan".equals(method);
        if (mull ? ownIdx(tapeTurns) > spec.mullWindow : d > spec.window) {
            return;
        }
        try {
            final JsonObject row = PlayerControllerBridge.isolated(game, () -> IdScope.detached(
                    () -> describe(c, method, args, value, ordinal, d)));
            if (row != null) {
                scanRows.add(row);
            }
        } catch (RuntimeException e) {
            final JsonObject row = header();
            row.addProperty("kind", "scan");
            row.addProperty("ordinal", ordinal);
            row.addProperty("method", method);
            row.addProperty("error", String.valueOf(e));
            scanRows.add(row);
        }
    }

    @Override
    public Object scripted(final AiTapeController c, final String method, final Object[] args) {
        if (c.getPlayer() != seatP() || script.isEmpty() || captured) {
            return NONE;
        }
        final JsonObject next = script.peek();
        if (!method.equals(next.has("m") ? next.get("m").getAsString() : "")) {
            return NONE;
        }
        script.poll();
        try {
            return answer(c, method, args, next);
        } catch (RuntimeException e) {
            error = "script answer " + method + ": " + e;
            return NONE;
        }
    }

    private void detachAll() {
        diverted = true;
        divertTurn = game.getPhaseHandler().getTurn();
        for (Player p : game.getPlayers()) {
            if (p.getController() instanceof AiTapeController tc) {
                tc.tape().detach();
            }
        }
    }

    // ------------------------------------------------------------------ answers

    private static Card byName(final Collection<Card> opts, final String name, final List<Card> used) {
        Card best = null;
        for (Card c : opts) {
            if (c.getName().equals(name) && (used == null || !used.contains(c))
                    && (best == null || c.getId() < best.getId())) {
                best = c;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("no option named " + name);
        }
        return best;
    }

    private static List<Card> byNames(final Collection<Card> opts, final JsonArray names) {
        final List<Card> out = new ArrayList<>();
        for (JsonElement n : names) {
            out.add(byName(opts, n.getAsString(), out));
        }
        return out;
    }

    private static List<Card> list(final Object o) {
        final List<Card> l = new ArrayList<>();
        if (o instanceof Iterable<?> it) {
            for (Object x : it) {
                l.add((Card) x);
            }
        }
        return l;
    }

    private Object answer(final AiTapeController c, final String method, final Object[] args, final JsonObject a) {
        final String t = a.get("t").getAsString();
        switch (method) {
            case "chooseSingleCardForZoneChange":
                return "none".equals(t) ? null : byName(list(args[3]), a.get("name").getAsString(), null);
            case "chooseCardsForZoneChange":
                return byNames(list(args[3]), a.getAsJsonArray("names"));
            case "orderMoveToZoneList":
                return new CardCollection(byNames(list(args[0]), a.getAsJsonArray("names")));
            case "arrangeForScry":
            case "arrangeForSurveil": {
                final List<Card> all = list(args[0]);
                final List<Card> top = byNames(all, a.getAsJsonArray("top"));
                final List<Card> rest = new ArrayList<>(all);
                rest.removeAll(top);
                final List<Card> bot = byNames(rest, a.getAsJsonArray("bottom"));
                return ImmutablePair.of(new CardCollection(top), new CardCollection(bot));
            }
            case "chooseCardsToDiscardFrom":
                return new CardCollection(byNames(list(args[2]), a.getAsJsonArray("names")));
            case "chooseCardsToDiscardToMaximumHandSize":
                return new CardCollection(byNames(list(c.getPlayer().getCardsIn(ZoneType.Hand)), a.getAsJsonArray("names")));
            case "tuckCardsViaMulligan":
                return new CardCollection(byNames(list(args[0]), a.getAsJsonArray("names")));
            case "chooseCardsForEffect":
                return new CardCollection(byNames(list(args[0]), a.getAsJsonArray("names")));
            case "mulliganKeepHand":
            case "confirmAction":
                return a.get("v").getAsBoolean();
            case "chooseSpellAbilityToPlay": {
                if ("pass".equals(t)) {
                    return null;
                }
                final Player p = c.getPlayer();
                final Card card = game.findById(a.get("id").getAsInt());
                if (card == null) {
                    throw new IllegalArgumentException("no card " + a.get("id"));
                }
                final List<SpellAbility> sas = classified(p, card);
                final int k = a.get("ab").getAsInt();
                for (SpellAbility sa : sas) {
                    if (abIndex(card, sa, p) == k) {
                        final List<SpellAbility> out = new ArrayList<>();
                        out.add(sa);
                        return out;
                    }
                }
                throw new IllegalArgumentException("ability " + k + " of " + card + " is not playable");
            }
            default:
                throw new IllegalArgumentException("no answers for " + method);
        }
    }

    // ------------------------------------------------------------------ priority classes

    static final java.util.Set<String> COSTED = new java.util.TreeSet<>(java.util.Arrays.asList("The One Ring",
            "Necropotence", "Ad Nauseam", "Griselbrand", "Sylvan Library", "Bolas's Citadel", "Peer into the Abyss",
            "Night's Whisper", "Sign in Blood", "Dark Confidant", "Phyrexian Arena", "Greed", "Erebos, Bleak-Hearted",
            "Yawgmoth, Thran Physician", "Bitterblossom", "Toxic Deluge"));

    /** The assembly class of a priority play, or null. */
    static String classOf(final SpellAbility sa) {
        final Card host = sa.getHostCard();
        if (sa.isLandAbility()) {
            return "land";
        }
        if (sa.usesTargeting()) {
            return null;
        }
        final ApiType api = sa.getApi();
        final String origin = sa.getParam("Origin");
        if (host.isLand() && api == ApiType.ChangeZone && origin != null && origin.contains("Library")) {
            return "fetch";
        }
        final boolean payLife = sa.getPayCosts() != null && sa.getPayCosts().hasSpecificCostType(
                forge.game.cost.CostPayLife.class);
        if (COSTED.contains(host.getName()) || ((api == ApiType.Draw || api == ApiType.Dig || api == ApiType.DigUntil)
                && payLife)) {
            return "costed-draw";
        }
        if ((api == ApiType.ChangeZone || api == ApiType.ChangeZoneAll) && origin != null && origin.contains("Library")) {
            return "tutor";
        }
        if (api == ApiType.Draw && (hasSub(sa, ApiType.Discard) || (sa.getPayCosts() != null
                && sa.getPayCosts().hasSpecificCostType(forge.game.cost.CostDiscard.class)))) {
            return "loot";
        }
        if (api == ApiType.Discard && hasSub(sa, ApiType.Draw)) {
            return "loot";
        }
        return null;
    }

    static boolean hasSub(final SpellAbility sa, final ApiType t) {
        for (SpellAbility s = sa.getSubAbility(); s != null; s = s.getSubAbility()) {
            if (s.getApi() == t) {
                return true;
            }
        }
        return false;
    }

    private static int abIndex(final Card card, final SpellAbility sa, final Player p) {
        int i = 0;
        for (SpellAbility x : card.getAllPossibleAbilities(p, false)) {
            if (x == sa || (x.getDescription() != null && x.getDescription().equals(sa.getDescription())
                    && x.isLandAbility() == sa.isLandAbility() && x.isSpell() == sa.isSpell())) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static List<SpellAbility> classified(final Player p, final Card card) {
        final List<SpellAbility> out = new ArrayList<>();
        for (SpellAbility sa : card.getAllPossibleAbilities(p, true)) {
            if (classOf(sa) == null) {
                continue;
            }
            sa.setActivatingPlayer(p);
            if (!sa.isLandAbility() && !forge.ai.ComputerUtilCost.canPayCost(sa, p, false)) {
                continue;
            }
            out.add(sa);
        }
        return out;
    }

    // ------------------------------------------------------------------ scan rows

    private static JsonArray names(final Object o) {
        final JsonArray a = new JsonArray();
        for (Card c : list(o)) {
            a.add(c.getName());
        }
        return a;
    }

    private static JsonArray zoneNames(final Player p, final ZoneType z) {
        return names(p.getCardsIn(z));
    }

    private JsonObject zones(final Player p) {
        final JsonObject z = new JsonObject();
        z.add("hand", zoneNames(p, ZoneType.Hand));
        z.add("battlefield", zoneNames(p, ZoneType.Battlefield));
        z.add("graveyard", zoneNames(p, ZoneType.Graveyard));
        z.add("exile", zoneNames(p, ZoneType.Exile));
        z.addProperty("library", p.getCardsIn(ZoneType.Library).size());
        z.addProperty("life", p.getLife());
        z.addProperty("poison", p.getPoisonCounters());
        final Player o = p.getOpponents().isEmpty() ? null : p.getOpponents().get(0);
        if (o != null) {
            z.addProperty("opp_life", o.getLife());
            z.addProperty("opp_hand", o.getCardsIn(ZoneType.Hand).size());
            z.add("opp_battlefield", zoneNames(o, ZoneType.Battlefield));
        }
        int untappedLands = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && !c.isTapped()) {
                untappedLands++;
            }
        }
        z.addProperty("untapped_lands", untappedLands);
        z.addProperty("lands_played", p.getLandsPlayedThisTurn());
        final JsonArray counters = new JsonArray();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            for (com.google.common.collect.Multiset.Entry<CounterType> e : c.getCounters().entrySet()) {
                final JsonArray x = new JsonArray();
                x.add(c.getName());
                x.add(String.valueOf(e.getElement()));
                x.add(e.getCount());
                counters.add(x);
            }
        }
        z.add("counters", counters);
        // what P knows of its own library, by position (top = 0)
        final JsonArray kl = new JsonArray();
        final List<Card> lib = new ArrayList<>(p.getCardsIn(ZoneType.Library));
        final int s = game.getRegisteredPlayers().indexOf(p);
        for (int i = 0; i < lib.size(); i++) {
            if (know != null && know.knows(s, lib.get(i))) {
                final JsonArray x = new JsonArray();
                x.add(i);
                x.add(lib.get(i).getName());
                kl.add(x);
            }
        }
        z.add("library_known", kl);
        return z;
    }

    private JsonObject describe(final AiTapeController c, final String method, final Object[] args, final Object value,
            final int ordinal, final int d) {
        final PhaseHandler ph = game.getPhaseHandler();
        final Player p = c.getPlayer();
        final JsonObject r = header();
        r.addProperty("kind", "scan");
        r.addProperty("ordinal", ordinal);
        r.addProperty("method", method);
        r.addProperty("turn", ph.getTurn());
        r.addProperty("phase", String.valueOf(ph.getPhase()));
        r.addProperty("active", ph.getPlayerTurn() == p);
        r.addProperty("own_before_end", d);
        r.addProperty("own_turn", ownIdx(ph.getTurn()));
        r.addProperty("stack", game.getStack().size());
        final SpellAbility top = game.getStack().isEmpty() ? null : game.getStack().peekAbility();
        if (top != null) {
            r.addProperty("stack_top", top.getHostCard().getName());
            r.addProperty("stack_top_api", String.valueOf(top.getApi()));
        }
        final JsonObject o = new JsonObject();
        switch (method) {
            case "chooseSingleCardForZoneChange": {
                final SpellAbility sa = (SpellAbility) args[2];
                o.addProperty("dest", String.valueOf(args[0]));
                o.addProperty("origin", String.valueOf(args[1]));
                o.addProperty("src", sa == null ? null : sa.getHostCard().getName());
                o.addProperty("api", sa == null ? null : String.valueOf(sa.getApi()));
                o.add("options", names(args[3]));
                final JsonArray oz = new JsonArray();
                for (Card x : list(args[3])) {
                    oz.add(String.valueOf(x.getZone() == null ? null : x.getZone().getZoneType()));
                }
                o.add("option_zones", oz);
                o.addProperty("optional", (Boolean) args[4]);
                o.addProperty("decider_is_me", args[5] == null || args[5] == p);
                r.addProperty("recorded", value == null ? null : ((Card) value).getName());
                break;
            }
            case "chooseCardsForZoneChange": {
                final SpellAbility sa = (SpellAbility) args[2];
                o.addProperty("dest", String.valueOf(args[0]));
                o.addProperty("origin", String.valueOf(args[1]));
                o.addProperty("src", sa == null ? null : sa.getHostCard().getName());
                o.add("options", names(args[3]));
                o.addProperty("min", (Integer) args[4]);
                o.addProperty("max", (Integer) args[5]);
                r.add("recorded", names(value));
                break;
            }
            case "orderMoveToZoneList": {
                final SpellAbility sa = (SpellAbility) args[2];
                o.add("options", names(args[0]));
                o.addProperty("dest", String.valueOf(args[1]));
                o.addProperty("src", sa == null ? null : sa.getHostCard().getName());
                r.add("recorded", names(value));
                break;
            }
            case "arrangeForScry":
            case "arrangeForSurveil": {
                o.add("options", names(args[0]));
                @SuppressWarnings("unchecked")
                final ImmutablePair<CardCollection, CardCollection> v = (ImmutablePair<CardCollection, CardCollection>) value;
                final JsonObject rec = new JsonObject();
                rec.add("top", names(v == null ? null : v.getLeft()));
                rec.add("bottom", names(v == null ? null : v.getRight()));
                r.add("recorded", rec);
                break;
            }
            case "chooseCardsToDiscardFrom": {
                final SpellAbility sa = (SpellAbility) args[1];
                o.addProperty("src", sa == null ? null : sa.getHostCard().getName());
                o.add("options", names(args[2]));
                o.addProperty("min", (Integer) args[3]);
                o.addProperty("max", (Integer) args[4]);
                r.add("recorded", names(value));
                break;
            }
            case "chooseCardsToDiscardToMaximumHandSize":
                o.add("options", zoneNames(p, ZoneType.Hand));
                o.addProperty("min", (Integer) args[0]);
                o.addProperty("max", (Integer) args[0]);
                r.add("recorded", names(value));
                break;
            case "tuckCardsViaMulligan":
                o.add("options", names(args[0]));
                o.addProperty("min", (Integer) args[1]);
                o.addProperty("max", (Integer) args[1]);
                r.add("recorded", names(value));
                break;
            case "mulliganKeepHand":
                o.add("options", zoneNames(p, ZoneType.Hand));
                o.addProperty("to_return", (Integer) args[1]);
                r.addProperty("recorded", (Boolean) value);
                break;
            case "chooseCardsForEffect": {
                final SpellAbility sa = (SpellAbility) args[1];
                o.addProperty("src", sa == null ? null : sa.getHostCard().getName());
                o.addProperty("api", sa == null ? null : String.valueOf(sa.getApi()));
                o.addProperty("title", (String) args[2]);
                o.add("options", names(args[0]));
                o.addProperty("min", (Integer) args[3]);
                o.addProperty("max", (Integer) args[4]);
                r.add("recorded", names(value));
                break;
            }
            case "confirmAction": {
                final SpellAbility sa = (SpellAbility) args[0];
                o.addProperty("src", sa == null ? null : sa.getHostCard().getName());
                o.addProperty("api", sa == null ? null : String.valueOf(sa.getApi()));
                o.addProperty("mode", String.valueOf(args[1]));
                o.addProperty("message", (String) args[2]);
                r.addProperty("recorded", (Boolean) value);
                break;
            }
            case "chooseSpellAbilityToPlay": {
                if (!game.getStack().isEmpty()) {
                    return null;
                }
                final PhaseType phase = ph.getPhase();
                final boolean mine = ph.getPlayerTurn() == p;
                if (!(mine && (phase == PhaseType.MAIN1 || phase == PhaseType.MAIN2))
                        && !(!mine && phase == PhaseType.END_OF_TURN)) {
                    return null;
                }
                final JsonArray opts = new JsonArray();
                final List<Card> cards = new ArrayList<>(p.getCardsIn(ZoneType.Hand));
                cards.addAll(p.getCardsIn(ZoneType.Battlefield));
                final StringBuilder key = new StringBuilder();
                for (Card card : cards) {
                    for (SpellAbility sa : classified(p, card)) {
                        final JsonObject x = new JsonObject();
                        x.addProperty("t", "play");
                        x.addProperty("cls", classOf(sa));
                        x.addProperty("card", card.getName());
                        x.addProperty("id", card.getId());
                        x.addProperty("ab", abIndex(card, sa, p));
                        x.addProperty("zone", String.valueOf(card.getZone().getZoneType()));
                        x.addProperty("desc", String.valueOf(sa));
                        opts.add(x);
                        key.append(card.getName()).append('|').append(classOf(sa)).append(';');
                    }
                }
                if (opts.isEmpty()) {
                    return null;
                }
                final String k = ph.getTurn() + "/" + phase;
                if (key.toString().equals(lastPrio.get(k))) {
                    return null; // the same assembly options as this phase's last logged priority
                }
                lastPrio.put(k, key.toString());
                o.add("options", opts);
                @SuppressWarnings("unchecked")
                final List<SpellAbility> v = (List<SpellAbility>) value;
                final JsonObject rec = new JsonObject();
                if (v == null || v.isEmpty()) {
                    rec.addProperty("t", "pass");
                } else {
                    final SpellAbility sa = v.get(0);
                    rec.addProperty("t", "play");
                    rec.addProperty("card", sa.getHostCard().getName());
                    rec.addProperty("id", sa.getHostCard().getId());
                    rec.addProperty("cls", classOf(sa));
                    rec.addProperty("ab", abIndex(sa.getHostCard(), sa, p));
                    rec.addProperty("desc", String.valueOf(sa));
                }
                r.add("recorded", rec);
                break;
            }
            default:
                return null;
        }
        r.add("ctx", o);
        r.add("zones", zones(p));
        return r;
    }

    // ------------------------------------------------------------------ capture and worlds

    @Override
    public void beforeDecision(final Player p) {
        learnParity();
        if (!diverted || captured || job == null) {
            return;
        }
        if (!script.isEmpty() && waitPrio == 0) {
            // a compound script not consumed before the next priority: the option did not fit this game
            error = "script left unconsumed: " + script.size() + " answer(s)";
        }
        waitPrio++;
        if (!game.getStack().isEmpty() && waitPrio < spec.maxPriorityWait) {
            return;
        }
        captured = true;
        AiTapeController.PROBES.remove(game);
        final long c0 = System.nanoTime();
        final JsonObject row = header();
        row.addProperty("kind", "label");
        row.add("job", job);
        row.add("divert", divertInfo);
        if (error != null) {
            row.addProperty("error", error);
        }
        final Player me = seatP();
        final int seat = winner;
        final PhaseHandler ph = game.getPhaseHandler();
        final JsonObject cap = new JsonObject();
        cap.addProperty("turn", ph.getTurn());
        cap.addProperty("phase", String.valueOf(ph.getPhase()));
        cap.addProperty("priority_seat", game.getRegisteredPlayers().indexOf(p));
        cap.addProperty("stack", game.getStack().size());
        cap.addProperty("waited", waitPrio);
        row.add("capture", cap);
        try {
            final Object[] fr = PlayerControllerBridge.isolated(game, () -> IdScope.detached(() -> {
                final RlFeaturizer.Obs o = feat.observe(game, me, 0, false, null);
                final RlWire.Decide f = RlSearch.leafFrame(o, uidL, seat, ph.getTurn());
                if (f.hasPriv()) {
                    throw new IllegalStateException("bank: a frame with the privileged block");
                }
                return new Object[] {RlWire.encodeDecide(f), zones(me), TrajectoryRef.stateSha(game)};
            }));
            final byte[] payload = (byte[]) fr[0];
            row.addProperty("frame_b64", Base64.getEncoder().encodeToString(payload));
            row.addProperty("frame_sha", CardIndex.sha256(payload));
            row.add("zones", (JsonObject) fr[1]);
            row.addProperty("state_sha", (String) fr[2]);
        } catch (RuntimeException e) {
            row.addProperty("error", "frame: " + e);
        }
        // the horizon: through the end of P's own turn ownIdx(decision) + min(d + extra, cap)
        final int dTurn = job.get("turn").getAsInt();
        final int d = job.get("own_before_end").getAsInt();
        final int targetOwn = ownIdx(dTurn) + Math.min(d + spec.horizonExtra, spec.horizonCap);
        final int uTarget = parity == 1 ? 2 * targetOwn - 1 : 2 * targetOwn;
        final int stopTurn = uTarget + 1;
        row.addProperty("horizon_own_turn", targetOwn);
        row.addProperty("stop_turn", stopTurn);
        final JsonArray worlds = new JsonArray();
        double wins = 0;
        int ok = 0;
        final List<Card> unknown = unknownLibrary(me);
        final int w = Math.max(1, Math.min(spec.worlds, unknown.size()));
        row.addProperty("unknown_library", unknown.size());
        if (!row.has("error")) {
            final ForcedWinSolver.Redraw redraw = AssemblyWorlds.opponentBelief();
            for (int i = 0; i < w; i++) {
                final JsonObject wr = world(i, w, me, unknown, redraw, stopTurn);
                worlds.add(wr);
                if (!wr.has("fail")) {
                    ok++;
                    wins += "W".equals(wr.get("outcome").getAsString()) ? 1 : 0;
                }
            }
        }
        row.add("worlds", worlds);
        row.addProperty("n_worlds", w);
        row.addProperty("n_ok", ok);
        if (ok > 0) {
            row.addProperty("value", wins / ok);
        }
        row.addProperty("cpu_ms", Math.round((System.nanoTime() - c0) / 1e6));
        sink.write(List.of(row));
        throw new Done();
    }

    @Override
    public void afterDecision(final Player p, final List<SpellAbility> chosen) {
    }

    /** P's library cards P does not know the position of, sorted by (name, id): the first-draw strata. */
    private List<Card> unknownLibrary(final Player me) {
        final List<Card> u = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Library)) {
            if (know == null || !know.knows(winner, c)) {
                u.add(c);
            }
        }
        u.sort((a, b) -> {
            final int x = a.getName().compareTo(b.getName());
            return x != 0 ? x : Integer.compare(a.getId(), b.getId());
        });
        return u;
    }

    static long mix(final long a, final long b) {
        return RlSearch.splitmix(a * 0x9E3779B97F4A7C15L + b);
    }

    /** The turn watch of a play-out: reached when turn {@code stop} begins. */
    public static final class Watch {
        final int stop;
        volatile boolean reached;

        Watch(final int stop) {
            this.stop = stop;
        }

        @Subscribe
        public void on(final GameEventTurnBegan e) {
            if (e.turnNumber() >= stop) {
                reached = true;
            }
        }
    }

    private JsonObject world(final int wi, final int w, final Player liveMe, final List<Card> unknown,
            final ForcedWinSolver.Redraw redraw, final int stopTurn) {
        final JsonObject wr = new JsonObject();
        wr.addProperty("w", wi);
        final long seed = mix(mix(spec.seed, uidL), job.get("decision").getAsString().hashCode() * 1000L + wi);
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = IdScope.capture();
        final Object prevCache = forge.ai.AiCache.captureScope();
        final long t0 = System.nanoTime();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(seed, 1)));
        forge.ai.AiCache.openScope();
        IdScope.open();
        try {
            final GameCopier copier = new GameCopier(game, true);
            copier.setCopyStack(true);
            final Game g = copier.makeCopy();
            final Player me = (Player) copier.find(liveMe);
            g.AI_TIMEOUT = Math.max(g.AI_TIMEOUT, 600);
            redraw.apply(game, liveMe, g, me, new Random(mix(seed, 2)));
            // P's own library: known positions kept, unknown positions shuffled, the first unknown one stratified
            final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
            final List<Integer> upos = new ArrayList<>();
            final List<Card> ucards = new ArrayList<>();
            final java.util.Set<Integer> uids = new java.util.HashSet<>();
            for (Card c : unknown) {
                uids.add(c.getId());
            }
            for (int i = 0; i < lib.size(); i++) {
                if (uids.contains(lib.get(i).getId())) {
                    upos.add(i);
                    ucards.add(lib.get(i));
                }
            }
            if (!ucards.isEmpty()) {
                final int k = (int) Math.floor((wi + 0.5) * unknown.size() / w);
                final int firstId = unknown.get(Math.min(k, unknown.size() - 1)).getId();
                Card first = null;
                for (Card c : ucards) {
                    if (c.getId() == firstId) {
                        first = c;
                    }
                }
                ucards.remove(first);
                java.util.Collections.shuffle(ucards, new Random(mix(seed, 3)));
                ucards.add(0, first);
                for (int i = 0; i < upos.size(); i++) {
                    lib.set(upos.get(i), ucards.get(i));
                }
                me.getZone(ZoneType.Library).setCards(lib);
                wr.addProperty("first_draw", first.getName());
            }
            for (Player pl : g.getPlayers()) {
                final LobbyPlayerAi lp = new LobbyPlayerAi(pl.getName(), null);
                lp.setAiProfile(spec.profile);
                pl.dangerouslySetController(new CubeComboPlayerController(g, pl, lp));
            }
            final Watch watch = new Watch(stopTurn);
            g.subscribeToEvents(watch);
            MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(seed, 4)));
            final PhaseHandler ph = g.getPhaseHandler();
            int steps = 0;
            while (!g.isGameOver() && !watch.reached && steps < spec.maxSteps) {
                ph.mainLoopStep();
                steps++;
            }
            wr.addProperty("steps", steps);
            wr.addProperty("end_turn", ph.getTurn());
            if (me.hasWon()) {
                wr.addProperty("outcome", "W");
            } else if (g.isGameOver() && (me.hasLost() || !me.isInGame())) {
                wr.addProperty("outcome", "L");
            } else if (g.isGameOver()) {
                wr.addProperty("outcome", "D");
            } else {
                wr.addProperty("outcome", steps >= spec.maxSteps ? "C" : "U");
            }
        } catch (RuntimeException | StackOverflowError e) {
            wr.addProperty("fail", String.valueOf(e));
        } finally {
            wr.addProperty("ms", Math.round((System.nanoTime() - t0) / 1e6));
            forge.ai.AiCache.installScope(prevCache);
            IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
        return wr;
    }
}
