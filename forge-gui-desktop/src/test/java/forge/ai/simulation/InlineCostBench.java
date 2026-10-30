package forge.ai.simulation;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.MyRandom;

/**
 * r3-inline-choices-1030: the cost of the new in-line kinds on real positions. Plays real cube games (Forge AI vs Forge
 * AI, cur-1009 TRAIN decks) with choose-a-card in SHADOW on seat 0 (shadow = live byte for byte, so arm A and arm B play
 * the SAME game): arm A = #143's kinds, arm B = #143's + the r3 kinds. Every decision entry is logged; it stops once B
 * has logged {@code target} decisions of the new kinds. Static value (report-only), no belief re-draw (testUnsafe):
 * this measures work (copies, settle steps, ms), never a label.
 * usage: InlineCostBench <deck-list file> <out.json> [target=50] [seed=1030] [maxGames=40]
 */
public final class InlineCostBench {
    static final String NEW = "PAYMENT,COLOR,COST_CARDS,LIFE,SACRIFICE,ORDER_TRIGGERS,PAY_TO_PREVENT";

    public static void main(final String[] args) throws Exception {
        final List<String> decks = Files.readAllLines(Paths.get(args[0]));
        final int target = args.length > 2 ? Integer.parseInt(args[2]) : 50;
        final long seed = args.length > 3 ? Long.parseLong(args[3]) : 1030L;
        final int maxGames = args.length > 4 ? Integer.parseInt(args[4]) : 40;
        GuiBase.setInterface(new forge.GuiDesktop());
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            forge.bench.HeadlessSound.silence(prefs);
            return null;
        });
        final JsonObject out = new JsonObject();
        final JsonArray games = new JsonArray();
        int newDecisions = 0;
        for (int gi = 0; gi < maxGames && newDecisions < target; gi++) {
            final String d0 = decks.get((2 * gi) % decks.size()), d1 = decks.get((2 * gi + 1) % decks.size());
            final JsonObject row = new JsonObject();
            row.addProperty("game", gi);
            row.addProperty("deck0", new File(d0).getName());
            row.addProperty("deck1", new File(d1).getName());
            for (String arm : new String[] {"off", "A", "B"}) {
                final JsonObject r = play(d0, d1, LookaheadSearch.mix(seed, gi), arm);
                row.add(arm, r);
            }
            for (JsonElement e : row.getAsJsonObject("B").getAsJsonArray("decisions")) {
                if (isNew(e.getAsJsonObject().get("kind").getAsString())) newDecisions++;
            }
            row.addProperty("same_game", row.getAsJsonObject("off").get("fp").equals(row.getAsJsonObject("A").get("fp"))
                    && row.getAsJsonObject("off").get("fp").equals(row.getAsJsonObject("B").get("fp")));
            games.add(row);
            System.err.println("[inline-cost] game " + gi + " new decisions so far " + newDecisions);
            out.add("games", games);
            out.addProperty("new_decisions", newDecisions);
            Files.writeString(Paths.get(args[1]), out.toString());
        }
        System.exit(0);
    }

    static boolean isNew(final String k) {
        return NEW.contains(k);
    }

    static JsonObject play(final String d0, final String d1, final long seed, final String arm) throws Exception {
        final List<RegisteredPlayer> seats = new ArrayList<>();
        for (String p : new String[] {d0, d1}) {
            final Deck deck = DeckSerializer.fromFile(new File(p));
            final RegisteredPlayer rp = new RegisteredPlayer(deck);
            rp.setPlayer(new LobbyPlayerAi("p" + seats.size(), null));
            seats.add(rp);
        }
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
        rules.setGamesPerMatch(1);
        rules.setAISideboardingEnabled(false);
        rules.setSideboardForAI(false);
        rules.setAllowCheatShuffle(false);
        rules.setWarnAboutAICards(false);
        final Match match = new Match(rules, seats, "inline-cost");
        MyRandom.setRandom(new Random(seed));
        forge.util.IdScope.open();
        final Game game = match.createGame();
        ChooseACard cac = null;
        final List<JsonObject> entries = new ArrayList<>();
        if (!arm.equals("off")) {
            final JsonObject o = new JsonObject();
            o.addProperty("mode", "shadow");
            o.addProperty("topM", 0);
            o.addProperty("kinds", arm.equals("A") ? "SEARCH,MULTI,PILE,DIG,DIGPICK,DISCARD,SCRY,SURVEIL,ORDER,MULLIGAN,LAND,RETURN_COST"
                    : "SEARCH,MULTI,PILE,DIG,DIGPICK,DISCARD,SCRY,SURVEIL,ORDER,MULLIGAN,LAND,RETURN_COST," + NEW);
            final ChooseACard.Config cfg = ChooseACard.Config.fromJson(o);
            cfg.testUnsafe = true;
            cac = new ChooseACard(cfg, StaticValuer.INSTANCE, null);
            cac.setSink(entries::add);
        }
        final ChooseACard hook = cac;
        final long t0 = System.nanoTime();
        match.startGame(game, () -> {
            if (hook != null && game.getPlayers().get(0).getController() instanceof PlayerControllerAi pc) hook.install(pc);
        });
        final long ms = (System.nanoTime() - t0) / 1_000_000L;
        final JsonObject r = new JsonObject();
        r.addProperty("ms", ms);
        r.addProperty("turns", game.getPhaseHandler().getTurn());
        r.addProperty("fp", LookaheadSearch.fingerprint(game));
        final JsonArray ds = new JsonArray();
        if (cac != null) {
            for (JsonObject x : entries) {
                final JsonObject y = new JsonObject();
                for (String k : new String[] {"kind", "src", "n_enum", "outcome", "ms", "work"}) {
                    if (x.has(k)) y.add(k, x.get(k));
                }
                y.addProperty("cands", x.has("cands") ? x.getAsJsonArray("cands").size() : 0);
                ds.add(y);
            }
            r.add("counts", new com.google.gson.Gson().toJsonTree(cac.counts));
        }
        r.add("decisions", ds);
        forge.util.IdScope.install(null);
        return r;
    }
}
