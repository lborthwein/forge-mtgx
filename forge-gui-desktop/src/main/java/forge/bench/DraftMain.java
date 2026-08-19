/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.bench;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.GuiDesktop;
import forge.StaticData;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gamemodes.limited.CustomLimited;
import forge.gamemodes.limited.HeadlessCubeDraft;
import forge.gamemodes.limited.LimitedPlayer;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.item.generation.BoosterSlots;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.MyRandom;

/**
 * Headless 8-seat cube draft plus Forge's limited deck build, for the mtgx benchmark.
 *
 * <p>Reads one JSON config line on stdin:
 * <pre>
 * {"cards":[{"name":"Ponder","set":"LRW"}, ...],
 *  "seed":123, "rankingsFile":"rankings_mtgx.txt",
 *  "players":8, "packs":3, "cardsPerPack":15}
 * </pre>
 * {@code rankingsFile} may be a bare filename already present under
 * {@code forge-gui/res/draft/}, or an absolute path, in which case it is copied there
 * (Forge's {@code ReadDraftRankings} only resolves names relative to that folder).
 *
 * <p>Writes one JSON line per seat on stdout:
 * <pre>
 * {"type":"seat","seat":0,"picks":[...],"pool":[...],"deck":{"main":[...],"sideboard":[...]}}
 * </pre>
 */
public final class DraftMain {

    private DraftMain() {
    }

    public static void main(final String[] args) {
        final PrintStream protocolOut =
                new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

        final JsonRpcChannel ch = new JsonRpcChannel(System.in, protocolOut);

        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        System.setProperty("sun.java2d.d3d", "false");

        JsonObject cfg;
        try {
            cfg = ch.readLine();
        } catch (Exception e) {
            JsonRpcChannel.logErr("could not read config line", e);
            System.exit(2);
            return;
        }
        if (cfg == null) {
            JsonRpcChannel.log("no config line on stdin; nothing to do");
            System.exit(2);
            return;
        }

        final long seed = cfg.has("seed") ? cfg.get("seed").getAsLong() : 0L;
        final int players = cfg.has("players") ? cfg.get("players").getAsInt() : 8;
        final int packs = cfg.has("packs") ? cfg.get("packs").getAsInt() : 3;
        final int cardsPerPack = cfg.has("cardsPerPack") ? cfg.get("cardsPerPack").getAsInt() : 15;

        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        MyRandom.setRandom(new Random(seed));

        final String rankings = installRankings(cfg.has("rankingsFile")
                ? cfg.get("rankingsFile").getAsString() : null);

        // ---------------------------------------------------------------- build the cube
        final CardPool pool = new CardPool();
        int missing = 0;
        if (cfg.has("cards")) {
            for (JsonElement e : cfg.getAsJsonArray("cards")) {
                final String name;
                final String set;
                if (e.isJsonObject()) {
                    final JsonObject o = e.getAsJsonObject();
                    name = o.get("name").getAsString();
                    set = o.has("set") && !o.get("set").isJsonNull() ? o.get("set").getAsString() : null;
                } else {
                    name = e.getAsString();
                    set = null;
                }
                PaperCard pc = set == null || set.isEmpty()
                        ? StaticData.instance().getCommonCards().getCard(name)
                        : StaticData.instance().getCommonCards().getCard(name, set);
                if (pc == null) {
                    pc = StaticData.instance().getCommonCards().getCard(name);
                }
                if (pc == null) {
                    JsonRpcChannel.log("cube card not found in Forge's DB: " + name
                            + (set == null ? "" : "|" + set));
                    missing++;
                    continue;
                }
                pool.add(pc);
            }
        }
        if (pool.countAll() < players * packs * cardsPerPack) {
            JsonRpcChannel.log("cube has " + pool.countAll() + " cards but the pod needs "
                    + (players * packs * cardsPerPack));
            System.exit(2);
            return;
        }

        final List<Pair<String, Integer>> slots = new ArrayList<>();
        slots.add(ImmutablePair.of(BoosterSlots.ANY, cardsPerPack));
        final CustomLimited cube = new CustomLimited("mtgx-cube", slots);
        cube.setCardPool(pool);
        cube.setSingleton(true);
        cube.setNumPacks(packs);
        cube.setNumPlayers(players);
        cube.setCustomRankingsFile(rankings);

        final JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("protocol", JsonRpcChannel.PROTOCOL_VERSION);
        hello.addProperty("mode", "draft");
        hello.addProperty("seed", seed);
        hello.addProperty("players", players);
        hello.addProperty("packs", packs);
        hello.addProperty("cardsPerPack", cardsPerPack);
        hello.addProperty("cubeCards", pool.countAll());
        hello.addProperty("missingCards", missing);
        hello.addProperty("rankingsFile", String.valueOf(rankings));
        ch.send(hello);

        // -------------------------------------------------------------------- draft
        final long t0 = System.currentTimeMillis();
        final HeadlessCubeDraft draft = new HeadlessCubeDraft(cube);
        draft.runToCompletion();
        final Deck[] decks = draft.buildDecks();
        final long wallMs = System.currentTimeMillis() - t0;

        final List<LimitedPlayer> seats = draft.getAllPlayers();
        for (int i = 0; i < seats.size(); i++) {
            final LimitedPlayer pl = seats.get(i);
            final JsonObject o = new JsonObject();
            o.addProperty("type", "seat");
            o.addProperty("seat", i);
            o.add("picks", names(draft.getPicks(i)));
            o.add("pool", poolNames(pl.getDeck(), DeckSection.Sideboard));
            final JsonObject deck = new JsonObject();
            deck.add("main", poolNames(decks[i], DeckSection.Main));
            deck.add("sideboard", poolNames(decks[i], DeckSection.Sideboard));
            o.add("deck", deck);
            ch.send(o);
        }

        final JsonObject done = new JsonObject();
        done.addProperty("type", "result");
        done.addProperty("seats", seats.size());
        done.addProperty("wallMs", wallMs);
        ch.send(done);
        System.exit(0);
    }

    private static JsonArray names(final Iterable<PaperCard> cards) {
        final JsonArray a = new JsonArray();
        for (PaperCard c : cards) {
            a.add(c.getName());
        }
        return a;
    }

    private static JsonArray poolNames(final Deck deck, final DeckSection section) {
        final JsonArray a = new JsonArray();
        if (deck == null || !deck.has(section)) {
            return a;
        }
        for (PaperCard c : deck.get(section).toFlatList()) {
            a.add(c.getName());
        }
        return a;
    }

    /**
     * Forge resolves a custom rankings file only against {@code res/draft/}. Accept a bare
     * filename that is already there, or copy an absolute path into place.
     */
    private static String installRankings(final String requested) {
        if (requested == null || requested.isEmpty()) {
            return null;
        }
        final File src = new File(requested);
        if (!src.isAbsolute()) {
            final File inPlace = new File(ForgeConstants.DRAFT_DIR + requested);
            if (!inPlace.isFile()) {
                JsonRpcChannel.log("rankings file not found: " + inPlace.getAbsolutePath()
                        + " -- the draft will fall back to Forge's own rankings");
                return null;
            }
            return requested;
        }
        if (!src.isFile()) {
            JsonRpcChannel.log("rankings file not found: " + src.getAbsolutePath());
            return null;
        }
        final File dst = new File(ForgeConstants.DRAFT_DIR + src.getName());
        try {
            Files.createDirectories(dst.getParentFile().toPath());
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            JsonRpcChannel.log("installed rankings " + src + " -> " + dst);
        } catch (Exception e) {
            JsonRpcChannel.logErr("could not install rankings file", e);
            return null;
        }
        return src.getName();
    }
}
