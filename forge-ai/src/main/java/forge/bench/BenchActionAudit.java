package forge.bench;

import java.util.IdentityHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.event.GameEvent;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.spellability.SpellAbility;

/** Private requested-versus-engine-event receipts, NOT a replacement rules engine. */
public final class BenchActionAudit {
    private BenchActionAudit() { }
    private static final Map<Game, Ledger> GAMES = new IdentityHashMap<>();

    private static final class Ledger {
        final String gameId;
        final Map<SpellAbility, JsonObject> pending = new IdentityHashMap<>();
        int selected;
        int observed;
        int mismatches;
        Ledger(String gameId) { this.gameId = gameId; }
    }

    public static synchronized void beginGame(Game game, String gameId) {
        GAMES.put(game, new Ledger(gameId));
    }

    public static synchronized void selected(Game game, int seat, SpellAbility ability, JsonObject answer) {
        final Ledger ledger = GAMES.get(game);
        if (ledger == null) return; // only the explicitly installed live benchmark game
        final JsonObject row = base(ledger, "selected-not-executed");
        row.addProperty("seat", seat);
        row.add("answer", answer.deepCopy());
        row.add("selected", semantics(ability));
        row.addProperty("abilityId", ability.getId());
        if (ledger.pending.put(ability, row) != null) unsupported("reselected pending ability", row);
        ledger.selected++;
        write(row);
    }

    public static synchronized void event(Game game, GameEvent event) {
        try { observe(game, event); }
        catch (RuntimeException failure) {
            // EventBus otherwise swallows subscriber failures. Keep the game's
            // behavior untouched, but invalidate every outcome from this worker.
            System.err.println("[bench] BENCH_INTEGRITY_FAILURE ACTION_RECEIPT: " + failure);
            failure.printStackTrace(System.err);
        }
    }

    private static void observe(Game game, GameEvent event) {
        final Ledger ledger = GAMES.get(game);
        if (ledger == null) return;
        if (event instanceof GameEventSpellAbilityCast cast) {
            SpellAbility actual = null;
            // The event carries VIEWS, not the live ability. Locate the actual
            // instance already inserted into MagicStack by this exact stack ID.
            for (var instance : game.getStack()) {
                if (instance.getId() == cast.si().getId()) { actual = instance.getSpellAbility(); break; }
            }
            if (actual == null) { unsupported("cast event has no matching stack instance", base(ledger, "missing-stack-instance")); return; }
            SpellAbility key = actual;
            String match = "object-identity";
            if (!ledger.pending.containsKey(key) && actual.getOriginalAbility() != null) {
                // MagicStack clones activated abilities, explicitly preserving
                // their original pointer. Never fuzzy-match card descriptions.
                key = actual.getOriginalAbility();
                match = "engine-original-ability";
            }
            final JsonObject requested = ledger.pending.remove(key);
            final JsonObject row = base(ledger, "engine-stack-add");
            row.addProperty("stackId", cast.si().getId());
            row.addProperty("abilityId", actual.getId());
            row.add("actual", semantics(actual));
            row.addProperty("paidMana", String.valueOf(actual.getPayingMana()));
            if (requested != null) {
                row.add("request", requested);
                row.addProperty("identityMatch", match);
                boolean same = requested.get("selected").equals(row.get("actual"));
                final JsonObject answer = requested.getAsJsonObject("answer");
                if (answer.has("x")) same &= actual.getXManaCostPaid() != null
                        && actual.getXManaCostPaid() == answer.get("x").getAsInt();
                row.addProperty("semanticsMatch", same);
                ledger.observed++;
                if (!same) {
                    ledger.mismatches++;
                    unsupported("selected/stack-added semantics mismatch", row);
                }
            } else row.addProperty("identityMatch", "no-pending-request");
            write(row);
        } else if (event instanceof GameEventLandPlayed land) {
            SpellAbility selected = null;
            for (SpellAbility pending : ledger.pending.keySet()) {
                if (pending.isLandAbility() && pending.getHostCard().getId() == land.land().getId()
                        && pending.getActivatingPlayer().getId() == land.player().getId()) {
                    if (selected != null) { unsupported("ambiguous land event join", base(ledger, "ambiguous-land")); return; }
                    selected = pending;
                }
            }
            final JsonObject row = base(ledger, "engine-land-played");
            row.addProperty("sourceCardId", land.land().getId());
            row.addProperty("actorId", land.player().getId());
            if (selected != null) {
                row.add("request", ledger.pending.remove(selected));
                row.addProperty("identityMatch", "card-and-player-id");
                ledger.observed++;
            } else row.addProperty("identityMatch", "no-pending-request");
            write(row);
        }
    }

    public static synchronized void finishGame(Game game) {
        final Ledger ledger = GAMES.remove(game);
        if (ledger == null) return;
        for (JsonObject pending : ledger.pending.values()) unsupported("selected action lacks engine event receipt", pending);
        final JsonObject row = base(ledger, "summary");
        row.addProperty("selected", ledger.selected);
        row.addProperty("observed", ledger.observed);
        row.addProperty("unobserved", ledger.pending.size());
        row.addProperty("mismatches", ledger.mismatches);
        write(row);
    }

    private static JsonObject semantics(SpellAbility ability) {
        final JsonObject out = new JsonObject();
        out.addProperty("sourceCardId", ability.getHostCard().getId());
        out.addProperty("actorId", ability.getActivatingPlayer() == null ? -1 : ability.getActivatingPlayer().getId());
        out.addProperty("api", String.valueOf(ability.getApi()));
        out.addProperty("land", ability.isLandAbility());
        out.addProperty("spell", ability.isSpell());
        out.addProperty("x", ability.getXManaCostPaid());
        out.addProperty("optionalCosts", String.valueOf(ability.getOptionalCosts()));
        final JsonArray chains = new JsonArray();
        for (SpellAbility part = ability; part != null; part = part.getSubAbility()) {
            final JsonObject targets = new JsonObject();
            final JsonArray cards = new JsonArray(), players = new JsonArray(), spells = new JsonArray();
            for (var card : part.getTargets().getTargetCards()) cards.add(card.getId());
            for (var player : part.getTargets().getTargetPlayers()) players.add(player.getId());
            for (var spell : part.getTargets().getTargetSpells()) spells.add(spell.getId());
            targets.add("cards", cards); targets.add("players", players); targets.add("spells", spells);
            chains.add(targets);
        }
        out.add("targetsByAbility", chains);
        return out;
    }

    private static JsonObject base(Ledger ledger, String kind) {
        final JsonObject out = new JsonObject();
        out.addProperty("schema", "forge-bench-private-action/1");
        out.addProperty("game", ledger.gameId);
        out.addProperty("kind", kind);
        return out;
    }

    private static void unsupported(String message, JsonObject evidence) {
        System.err.println("[bench] BENCH_INTEGRITY_UNSUPPORTED ACTION_RECEIPT: " + message + " " + evidence);
        System.err.flush();
    }

    private static void write(JsonObject row) {
        System.err.println("[bench-action] " + row);
        System.err.flush();
    }
}
