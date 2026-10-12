package forge.bench.rl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.card.Card;
import forge.util.Localizer;

/**
 * Live play (lane forge-live-fixes-1012): the "Cancel Search?" loop guard of a live policy seat ({@link RlSeat}, only when
 * {@link RlSeat.Config#loopGuard} {@code K > 0}; the seat holds none otherwise, and then nothing changes).
 *
 * <p><b>The loop.</b> {@code ChangeZoneEffect}'s one-at-a-time hidden-zone choice (Show and Tell, tutors, every effect that
 * reaches {@code lblCancelSearchUpToSelectNumCards}) asks the decider for a card ({@code chooseSingleCardForZoneChange},
 * optional). An empty answer is followed by the confirm "Cancel Search? Up to N more card(s) can be selected."
 * ({@code confirmAction}, mode {@code ChangeZoneGeneral}): yes ends the choice with nothing, no asks for a card again. A
 * greedy policy that answers the card ask with no card and the confirm with no repeats that pair until the seat's
 * {@code maxDecisions} (lane st-loop-live-1012: 44 of 312 Show and Tell games of one read, 1,326-1,497 cycles each).
 *
 * <p><b>The guard.</b> Once the seat has answered the same card ask (the same ability, prompt, progress and candidate
 * cards) with no card and the confirm that follows it with no K times in a row, the next such confirm is answered yes
 * here, without asking the policy: "put nothing", the policy's own first answer. The trigger is exact: the confirm must
 * come straight after that card ask (no other ask between), with the mode {@code ChangeZoneGeneral}, the same ability
 * and the localized prompt text for an N between 1 and the number of candidate cards. Any other ask, a card picked, a
 * yes, or a different card ask resets the count.
 */
final class RlLoopGuard {

    static final String CARDS_METHOD = "chooseSingleCardForZoneChange";
    static final String CONFIRM_METHOD = "confirmAction";
    static final String CONFIRM_MODE = "ChangeZoneGeneral";
    static final String PROMPT_KEY = "lblCancelSearchUpToSelectNumCards";

    /** No-answers in a row before the guard answers yes. */
    final int k;
    /** Times the guard answered yes. */
    int hits = 0;

    /** The seat's previous ask: a card ask answered with no card (its key), else null. */
    private String armed;
    private String armedAbility;
    private int armedCards;
    /** The card ask whose confirm this ask is (set by {@link #fires} for {@link #saw}), else null. */
    private String pendingConfirm;
    /** The card ask of the current run of no-answers, and its length. */
    private String streakKey;
    private int streak;
    /** The run length when the guard last answered (for the log line). */
    int lastCycles;
    String lastTitle;
    String lastHost;
    int lastCards;

    RlLoopGuard(final int k) {
        if (k < 1) {
            throw new IllegalArgumentException("loopGuard needs K >= 1");
        }
        this.k = k;
    }

    void reset() {
        armed = null;
        armedAbility = null;
        armedCards = 0;
        pendingConfirm = null;
        streakKey = null;
        streak = 0;
    }

    /**
     * Before the seat answers an ask of its RL seat: true when this is the confirm the guard answers yes (the count is
     * then reset). Otherwise notes whether it is a guarded confirm, for {@link #saw}.
     */
    boolean fires(final String method, final String kind, final JsonObject body) {
        final String a = armed;
        final String ability = armedAbility;
        final int cards = armedCards;
        armed = null;
        armedAbility = null;
        armedCards = 0;
        pendingConfirm = null;
        if (a == null || !isCancelConfirm(method, kind, body, ability, cards)) {
            return false;
        }
        if (streak >= k && a.equals(streakKey)) {
            hits++;
            lastCycles = streak;
            streakKey = null;
            streak = 0;
            return true;
        }
        pendingConfirm = a;
        return false;
    }

    /**
     * After the seat answered (or declined, {@code ans} null) an ask; {@code rl} false for any ask of this game that is
     * not its RL seat's, or that came after the game or seat ended (each resets the count).
     */
    void saw(final boolean rl, final String method, final String kind, final JsonObject body, final Object objs,
            final JsonObject ans) {
        if (!rl) {
            reset();
            return;
        }
        if (pendingConfirm != null) {
            final String key = pendingConfirm;
            pendingConfirm = null;
            if (isNo(ans)) {
                streak = key.equals(streakKey) ? streak + 1 : 1;
                streakKey = key;
            } else {
                streakKey = null;
                streak = 0;
            }
            return;
        }
        final String key = isNone(ans) ? cardsKey(method, kind, body, objs) : null;
        if (key == null) {
            reset();
            return;
        }
        if (!key.equals(streakKey)) {
            streakKey = null;
            streak = 0;
        }
        armed = key;
        armedAbility = String.valueOf(body.get("ability"));
        armedCards = count(objs, body);
        lastTitle = body.has("title") ? body.get("title").getAsString() : "";
        lastCards = armedCards;
        lastHost = host(body);
    }

    /** The key of an optional one-at-a-time zone-change card ask, or null for any other ask. */
    static String cardsKey(final String method, final String kind, final JsonObject body, final Object objs) {
        if (!CARDS_METHOD.equals(method) || !"zoneChange".equals(kind) || body == null || !body.has("ability")
                || !isTrue(body, "optional") || !isTrue(body, "single")) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(body.get("ability")).append('\u0001').append(body.get("title")).append('\u0001')
                .append(body.get("min")).append('/').append(body.get("max")).append('\u0001')
                .append(body.get("changeNum")).append('/').append(body.get("chosen")).append('\u0001')
                .append(body.get("origin")).append('>').append(body.get("destination")).append('\u0001');
        if (objs instanceof Iterable) {
            for (Object o : (Iterable<?>) objs) {
                sb.append(o instanceof Card ? ((Card) o).getId() : String.valueOf(o)).append(',');
            }
        } else {
            sb.append(body.get("menu"));
        }
        return sb.toString();
    }

    /** The "Cancel Search?" confirm that follows a card ask of {@code ability} over {@code cards} candidates. */
    static boolean isCancelConfirm(final String method, final String kind, final JsonObject body, final String ability,
            final int cards) {
        if (!CONFIRM_METHOD.equals(method) || !"confirm".equals(kind) || body == null
                || !CONFIRM_MODE.equals(str(body, "mode")) || !String.valueOf(body.get("ability")).equals(ability)) {
            return false;
        }
        final String msg = str(body, "message");
        if (msg == null) {
            return false;
        }
        final Localizer l = Localizer.getInstance();
        for (int n = 1; n <= cards; n++) {
            if (msg.equals(l.getMessage(PROMPT_KEY, String.valueOf(n)))) {
                return true;
            }
        }
        return false;
    }

    /** A card-list answer with no card. */
    static boolean isNone(final JsonObject ans) {
        if (ans == null || (ans.has("delegate") && ans.get("delegate").getAsBoolean())) {
            return false;
        }
        final JsonElement c = ans.get("choices");
        return c != null && c.isJsonArray() && c.getAsJsonArray().size() == 0;
    }

    /** A confirm answered no. */
    static boolean isNo(final JsonObject ans) {
        if (ans == null) {
            return false;
        }
        final JsonElement y = ans.get("yes");
        return y != null && y.isJsonPrimitive() && y.getAsJsonPrimitive().isBoolean() && !y.getAsBoolean();
    }

    private static boolean isTrue(final JsonObject o, final String k) {
        final JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static String str(final JsonObject o, final String k) {
        final JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int count(final Object objs, final JsonObject body) {
        if (objs instanceof Iterable) {
            int n = 0;
            for (Object o : (Iterable<?>) objs) {
                n++;
            }
            return n;
        }
        final JsonElement m = body.get("menu");
        return m != null && m.isJsonArray() ? m.getAsJsonArray().size() : 0;
    }

    private static String host(final JsonObject body) {
        final JsonElement a = body.get("ability");
        return a != null && a.isJsonObject() ? str(a.getAsJsonObject(), "source") : null;
    }
}
