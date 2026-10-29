package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.GameEventVote;
import forge.game.player.Player;
import forge.game.player.PlayerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The vote in progress, as one browser seat may know it (owner report 2026-10-29T18-03-47: Council's Judgment cast by
 * the AI; the AI's vote came as a raw "Default Forge vote Craterhoof Behemoth (32) Current Votes:{...}" pop-up, and the
 * human's own vote request said nothing of it).
 *
 * <p>Fed by {@link GameEventVote} (fired by VoteEffect on the game thread, before the next voter is asked). While a
 * vote is open every request of the seat carries {@link #context}; the pop-up announcing another player's vote is
 * rewritten by {@link #announce}. A secret vote shows only the seat's own choice until the end. Text and context only:
 * nothing here is read by the game.</p>
 */
final class InteractiveVotes {
    /** One vote cast: whose vote it is, who chose it (vote control), and what it is for. */
    record Cast(PlayerView voter, PlayerView chooser, CardView card, PlayerView player, String label) {
    }

    private static final class Open {
        final CardView source;
        final List<PlayerView> order;
        final boolean secret;
        final List<Cast> casts = new CopyOnWriteArrayList<>();
        /** The last vote of another player, until its pop-up is shown. */
        volatile Cast unannounced;

        Open(final CardView source, final List<PlayerView> order, final boolean secret) {
            this.source = source;
            this.order = List.copyOf(order);
            this.secret = secret;
        }
    }

    private volatile Open open;

    /** Follows a vote event; {@code self} is this seat's player. */
    void onEvent(final GameEventVote e, final PlayerView self) {
        switch (e.stage()) {
            case STARTED -> open = new Open(e.source(), e.order(), e.secret());
            case CAST -> {
                Open current = open;
                if (current == null) {
                    current = new Open(e.source(), e.order(), e.secret());
                    open = current;
                }
                final Cast cast = new Cast(e.voter(), e.chooser(), e.choiceCard(), e.choicePlayer(), e.choiceLabel());
                current.casts.add(cast);
                current.unannounced = self != null && e.voter() != null && e.voter().getId() == self.getId()
                        ? null : cast;
            }
            case ENDED -> open = null;
        }
    }

    boolean isOpen() {
        return open != null;
    }

    /**
     * The request context of the open vote, added to {@code context}. The host's boundary (mtgx
     * {@code tools/forge/interactive-protocol.ts} parseContext) admits scalars and per-option records only, so the list
     * of votes is flattened, one index per vote cast so far, in the order cast:
     * <ul>
     * <li>{@code voteOpen} true; {@code voteSource} (the card id holding the vote) and {@code voteSourceName};
     * {@code voteSecret};</li>
     * <li>{@code voteOrder}: the voting seats in order, as text ("0,1"), with {@code voteOrderCount} and
     * {@code voteOrder0}, {@code voteOrder1}, ... (seats); {@code voteNextSeat}: the first of them with no vote yet,
     * absent once all have voted;</li>
     * <li>{@code voteCount}, and per vote {@code i}: {@code vote<i>Seat} (whose vote), {@code vote<i>ChoiceKind} (card /
     * player / option), {@code vote<i>Choice} (the card id voted for, or the option's label; for a player, the seat
     * as {@code vote<i>ChoiceSeat}), {@code vote<i>Label} (the choice in words, seat names for a player),
     * {@code vote<i>ChooserSeat} under vote control; a secret vote of another seat is {@code vote<i>Hidden} true with
     * no choice, and a card this seat may not identify has no id;</li>
     * <li>{@code voteTally}, per advertised option of this request that has votes: {@code votes} (how many) and
     * {@code seats} ("0" / "0,1": who voted for it).</li>
     * </ul>
     * Nothing is added when no vote is open.
     */
    void addContext(final JsonObject context, final Game game, final PlayerView viewer, final JsonArray controls,
                    final InteractiveSeatNames names, final int viewerSeat) {
        final Open current = open;
        if (current == null || game == null) {
            return;
        }
        context.addProperty("voteOpen", true);
        if (current.source != null) {
            context.addProperty("voteSource", current.source.getId());
            final Card source = game.findById(current.source.getId());
            if (source != null && InteractiveState.mayReceiveIdentity(source.getView(), viewer)) {
                context.addProperty("voteSourceName", source.getName());
            }
        }
        context.addProperty("voteSecret", current.secret);
        final List<String> order = new ArrayList<>();
        for (PlayerView player : current.order) {
            final int seat = seatOf(game, player);
            if (seat >= 0) {
                context.addProperty("voteOrder" + order.size(), seat);
                order.add(String.valueOf(seat));
            }
        }
        context.addProperty("voteOrder", String.join(",", order));
        context.addProperty("voteOrderCount", order.size());
        final int next = nextSeat(current, game);
        if (next >= 0) {
            context.addProperty("voteNextSeat", next);
        }
        final Map<Integer, List<String>> byCard = new LinkedHashMap<>();
        final Map<String, List<String>> byLabel = new LinkedHashMap<>();
        int i = 0;
        for (Cast cast : current.casts) {
            final String k = "vote" + i++;
            final int seat = seatOf(game, cast.voter());
            context.addProperty(k + "Seat", seat);
            if (cast.chooser() != null) {
                context.addProperty(k + "ChooserSeat", seatOf(game, cast.chooser()));
            }
            if (hiddenFrom(cast, current.secret, game, viewer)) {
                context.addProperty(k + "Hidden", true);
                continue;
            }
            if (cast.card() != null) {
                context.addProperty(k + "ChoiceKind", "card");
                final Card card = game.findById(cast.card().getId());
                if (card != null && InteractiveState.mayReceiveIdentity(card.getView(), viewer)) {
                    context.addProperty(k + "Choice", card.getId());
                    context.addProperty(k + "Label", card.getName());
                    byCard.computeIfAbsent(card.getId(), x -> new ArrayList<>()).add(String.valueOf(seat));
                } else {
                    context.addProperty(k + "Label", "a hidden card");
                }
            } else if (cast.player() != null) {
                final int choiceSeat = seatOf(game, cast.player());
                context.addProperty(k + "ChoiceKind", "player");
                context.addProperty(k + "ChoiceSeat", choiceSeat);
                context.addProperty(k + "Choice", choiceSeat);
                context.addProperty(k + "Label", names.displayName(game, choiceSeat, viewerSeat));
            } else {
                context.addProperty(k + "ChoiceKind", "option");
                context.addProperty(k + "Choice", cast.label());
                context.addProperty(k + "Label", cast.label());
                byLabel.computeIfAbsent(cast.label(), x -> new ArrayList<>()).add(String.valueOf(seat));
            }
        }
        context.addProperty("voteCount", i);
        // per advertised option: a card control (cardId) or a list item whose id or label names the choice
        final JsonObject tally = new JsonObject();
        if (controls != null) {
            for (com.google.gson.JsonElement element : controls) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject control = element.getAsJsonObject();
                if (control.has("cardId") && control.get("cardId").isJsonPrimitive()
                        && control.getAsJsonPrimitive("cardId").isNumber()) {
                    tally(tally, string(control, "controlId"), byCard.get(control.get("cardId").getAsInt()));
                }
                if (control.has("items") && control.get("items").isJsonArray()) {
                    for (com.google.gson.JsonElement item : control.getAsJsonArray("items")) {
                        if (!item.isJsonObject()) {
                            continue;
                        }
                        final String id = string(item.getAsJsonObject(), "id");
                        List<String> seats = null;
                        if (id != null && id.startsWith("card:")) {
                            try {
                                seats = byCard.get(Integer.parseInt(id.substring("card:".length())));
                            } catch (NumberFormatException ignored) {
                                seats = null;
                            }
                        } else {
                            seats = byLabel.get(string(item.getAsJsonObject(), "label"));
                        }
                        tally(tally, id, seats);
                    }
                }
            }
        }
        if (tally.size() > 0) {
            context.add("voteTally", tally);
        }
    }

    private static void tally(final JsonObject tally, final String optionId, final List<String> seats) {
        if (optionId == null || seats == null || seats.isEmpty()) {
            return;
        }
        final JsonObject facts = new JsonObject();
        facts.addProperty("votes", seats.size());
        facts.addProperty("seats", String.join(",", seats));
        tally.add(optionId, facts);
    }

    private static String string(final JsonObject o, final String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    /**
     * The pop-up announcing another player's vote, in words, when a vote of another player has not been announced
     * yet and {@code message} is Forge's announcement of it; otherwise null (show Forge's own text). Clears the pending
     * announcement. E.g. "AI Player votes for Craterhoof Behemoth.\n\nVotes so far:\nCraterhoof Behemoth: AI Player
     * \n\nStill to vote: You".
     */
    String announce(final String message, final Game game, final PlayerView viewer, final InteractiveSeatNames names,
                    final int viewerSeat) {
        final Open current = open;
        if (current == null || current.unannounced == null || message == null || game == null
                || !message.contains(CURRENT_VOTES_MARKER)) {
            return null;
        }
        final Cast cast = current.unannounced;
        current.unannounced = null;
        final StringBuilder text = new StringBuilder();
        final String voter = names.displayName(game, seatOf(game, cast.voter()), viewerSeat);
        text.append(voter).append(InteractiveSeatNames.YOU.equals(voter) ? " vote for " : " votes for ")
                .append(choiceText(cast, game, viewer, names, viewerSeat)).append('.');
        // who has voted for what so far, choice by choice in the order first voted for
        final Map<String, List<String>> tally = new LinkedHashMap<>();
        for (Cast each : current.casts) {
            if (hiddenFrom(each, current.secret, game, viewer)) {
                continue;
            }
            tally.computeIfAbsent(choiceText(each, game, viewer, names, viewerSeat), k -> new ArrayList<>())
                    .add(names.displayName(game, seatOf(game, each.voter()), viewerSeat));
        }
        if (!tally.isEmpty()) {
            text.append("\n\nVotes so far:");
            for (Map.Entry<String, List<String>> entry : tally.entrySet()) {
                text.append('\n').append(entry.getKey()).append(": ").append(String.join(", ", entry.getValue()));
            }
        }
        final List<String> still = new ArrayList<>();
        final List<Integer> voted = new ArrayList<>();
        for (Cast each : current.casts) {
            voted.add(seatOf(game, each.voter()));
        }
        for (PlayerView player : current.order) {
            final int seat = seatOf(game, player);
            if (seat >= 0 && !voted.contains(seat)) {
                still.add(names.displayName(game, seat, viewerSeat));
            }
        }
        if (!still.isEmpty()) {
            text.append("\n\nStill to vote: ").append(String.join(", ", still));
        }
        return text.toString();
    }

    /** Forge's announcement of a vote ends with its running tally, "Current Votes:{...}" (VoteEffect). */
    static final String CURRENT_VOTES_MARKER =
            forge.util.Localizer.getInstance().getMessage("lblCurrentVote") + ":";

    private static int nextSeat(final Open current, final Game game) {
        final List<Integer> voted = new ArrayList<>();
        for (Cast each : current.casts) {
            voted.add(seatOf(game, each.voter()));
        }
        for (PlayerView player : current.order) {
            final int seat = seatOf(game, player);
            if (seat >= 0 && !voted.contains(seat)) {
                return seat;
            }
        }
        return -1;
    }

    private static boolean hiddenFrom(final Cast cast, final boolean secret, final Game game, final PlayerView viewer) {
        return secret && (viewer == null || cast.voter() == null || cast.voter().getId() != viewer.getId());
    }

    private static String choiceText(final Cast cast, final Game game, final PlayerView viewer,
                                     final InteractiveSeatNames names, final int viewerSeat) {
        if (cast.card() != null) {
            final Card card = game.findById(cast.card().getId());
            return card != null && InteractiveState.mayReceiveIdentity(card.getView(), viewer)
                    ? card.getName() : "a hidden card";
        }
        if (cast.player() != null) {
            return names.displayName(game, seatOf(game, cast.player()), viewerSeat);
        }
        return cast.label();
    }

    static int seatOf(final Game game, final PlayerView player) {
        if (player == null) {
            return -1;
        }
        int index = 0;
        for (Player registered : game.getRegisteredPlayers()) {
            if (registered.getId() == player.getId()) {
                return index;
            }
            index++;
        }
        return -1;
    }
}
