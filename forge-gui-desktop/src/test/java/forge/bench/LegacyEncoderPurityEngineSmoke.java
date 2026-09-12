package forge.bench;

import forge.StaticData;
import forge.ai.ComputerUtilMana;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.*;

/** Actual legacy-encoder calls, both seats; development evidence, not strength certification. */
public final class LegacyEncoderPurityEngineSmoke {
    private static int checks;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    private static Card card(String name, Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var card = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), name), player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card); card.setSickness(false); return card;
    }
    private static Game game(int seat) {
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0", 0, 0, null, "Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), players, "Legacy estimate purity").createGame();
        game.setAge(GameStage.Play); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(seat)); return game;
    }
    private static Map<SpellAbility, Player> actors(Game game) {
        var result = new IdentityHashMap<SpellAbility, Player>();
        for (var card : game.getCardsInGame()) for (var sa : card.getManaAbilities()) result.put(sa, sa.getActivatingPlayer());
        return result;
    }
    private static int sequence() throws Exception {
        var field = SpellAbility.class.getDeclaredField("maxId"); field.setAccessible(true); return field.getInt(null);
    }
    private static Map<String, String> rawState(Game game) {
        // No StateEncoder here: deliberately malformed input cannot be rendered by encodeCard.
        var result = new TreeMap<String, String>();
        for (var p : game.getPlayers()) result.put("player/" + p.getId(), p.getLife() + "/" + p.getManaPool());
        for (var c : game.getCardsInGame()) {
            result.put("card/" + c.getId(), c.getZone() + "/" + c.isTapped() + "/" + c.getCounters() + "/" + c.getCurrentStateName());
            for (var sa : c.getAllSpellAbilities()) result.put("ability/" + c.getId() + "/" + sa.getId(),
                sa.getActivatingPlayer() + "/" + sa.getPayCosts() + "/" + sa.getTargets() + "/" + sa.getXManaCostPaid()
                + "/" + sa.getPipsToReduce() + "/" + sa.getPayingMana() + "/" + new TreeMap<>(sa.getMapParams())
                + "/" + new TreeMap<>(sa.getSVars()) + "/" + new com.google.gson.Gson().toJson(sa.getRestrictions()));
        }
        return result;
    }
    private static int repeats(Player player, SpellAbility sa, Cost cost) throws Exception {
        var controller = new PlayerControllerBridge(player.getGame(), player, player.getLobbyPlayer(), null,
            BenchSession.Mode.BRIDGE, player.getGame().getPlayers().indexOf(player), new CallCounter());
        var method = PlayerControllerBridge.class.getDeclaredMethod("affordableRepeats", SpellAbility.class, Cost.class, int.class);
        method.setAccessible(true);
        try { return (int) method.invoke(controller, sa, cost, 7); }
        catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }
    private static void setupActors(Game game, Player wrong, boolean unset) {
        for (var card : game.getCardsInGame()) for (var sa : card.getManaAbilities()) sa.setActivatingPlayer(unset ? null : wrong);
    }
    private static void scenario(int seat, String[] names, boolean tapped, int life, boolean unset, boolean baseline) throws Exception {
        var game = game(seat); var p = game.getPlayers().get(seat); var other = game.getPlayers().get(1 - seat);
        for (String name : names) {
            if (name.equals("POOL")) {
                var source = card("Mountain", p, ZoneType.Graveyard);
                p.getManaPool().addMana(new forge.game.mana.Mana((byte) forge.card.mana.ManaAtom.RED,
                    source, source.getManaAbilities().get(0).getManaPart(), p));
            } else card(name, p, ZoneType.Battlefield).setTapped(tapped);
        }
        card("Island", other, ZoneType.Battlefield);
        var spell = card("Walking Ballista", p, ZoneType.Hand).getFirstSpellAbility(); spell.setActivatingPlayer(p);
        p.setLife(life, null); game.getAction().checkStateEffects(true); setupActors(game, other, unset);
        var beforeActors = actors(game); var rng = BenchRandomAudit.begin(); int beforeId = sequence();
        String label = "seat=" + seat + " " + Arrays.toString(names) + " tapped=" + tapped + " life=" + life + " unset=" + unset;
        if (baseline) {
            var encoded = StateEncoder.encodeSpellAbility(spell);
            check(!actors(game).equals(beforeActors), "reproduced OLD actual encoder actor mutation " + label);
            check(encoded.getAsJsonObject("x").get("has").getAsBoolean(), "actual X ability reached estimate");
            // Reset test input only, to reproduce the second old entry point independently.
            setupActors(game, other, unset); beforeActors = actors(game);
            repeats(p, spell, new Cost("2", false));
            check(!actors(game).equals(beforeActors), "reproduced OLD optional-repeat actor mutation " + label);
            return;
        }
        var before = BenchMenuStateAudit.capture(game);
        check(actors(game).equals(beforeActors), "snapshot itself preserves actors " + label);
        int available = LegacyManaEstimate.available(p);
        int unchecked = LegacyManaEstimate.available(p, false);
        var encoded = StateEncoder.encodeSpellAbility(spell);
        int repeated = repeats(p, spell, new Cost("2", false));
        check(encoded.getAsJsonObject("x").get("max").getAsInt() == Math.max(0, available), "legacy X numeric formula " + label);
        check(encoded.getAsJsonObject("x").get("maxAnnounce").getAsInt() == Math.max(0, available) / 2, "legacy XX numeric formula " + label);
        check(repeated == Math.min(7, Math.max(0, available / 2)), "optional repeat legacy numeric formula " + label);
        check(actors(game).equals(beforeActors), "live actor identities unchanged " + label);
        check(sequence() == beforeId, "global ability allocation sequence unchanged " + label);
        BenchMenuStateAudit.assertUnchanged(before, game); BenchRandomAudit.assertUnchanged(rng, "legacy encoder " + label);
        check(true, "state tripwire and RNG untouched " + label);
        // Native comparison is deliberately last: it mutates actor fields. No restoration is used by the helper.
        int nativeAvailable = ComputerUtilMana.getAvailableManaEstimate(p);
        int nativeUnchecked = ComputerUtilMana.getAvailableManaEstimate(p, false);
        check(available == nativeAvailable && unchecked == nativeUnchecked,
            "same numeric answers as unmodified stock estimate " + label + " checked=" + available + " unchecked=" + unchecked);
    }
    private static void failure(int seat) throws Exception {
        var game = game(seat); var p = game.getPlayers().get(seat); var other = game.getPlayers().get(1 - seat);
        var source = card("Plains", p, ZoneType.Battlefield);
        var spell = card("Walking Ballista", p, ZoneType.Hand).getFirstSpellAbility(); spell.setActivatingPlayer(p);
        game.getAction().checkStateEffects(true); setupActors(game, other, false);
        source.getManaAbilities().get(0).setPayCosts(null); // malformed input, not a supported rules claim
        var beforeActors = actors(game); int beforeId = sequence(); var rng = BenchRandomAudit.begin();
        var before = rawState(game);
        check(actors(game).equals(beforeActors), "failed-input snapshot itself preserves actors");
        try { StateEncoder.encodeSpellAbility(spell); throw new AssertionError("Invented X ceiling after failure"); }
        catch (RulesCostFeasibility.Unsupported e) { check(e.getMessage().contains("Legacy X ceiling estimate failed"), "explicit X failure seat=" + seat); }
        try { repeats(p, spell, new Cost("2", false)); throw new AssertionError("Invented repeat ceiling after failure"); }
        catch (RulesCostFeasibility.Unsupported e) { check(e.getMessage().contains("Legacy keyword-cost ceiling estimate failed"), "explicit repeat failure seat=" + seat); }
        check(actors(game).equals(beforeActors), "failed query preserves live actors seat=" + seat);
        check(sequence() == beforeId, "failed query allocates no global IDs seat=" + seat);
        check(source.getManaAbilities().get(0).getPayCosts() == null, "failed query does not repair live input");
        check(before.equals(rawState(game)), "failed query preserves raw state tripwire");
        BenchRandomAudit.assertUnchanged(rng, "failed legacy queries"); check(true, "failed query preserves RNG");
    }
    private static void rejectsRandom(Runnable action, String operation) {
        try { action.run(); throw new AssertionError("Random operation allowed: " + operation); }
        catch (IllegalStateException expected) {
            check(expected.getMessage().contains(operation), "pre-mutation rejection " + operation);
        }
    }
    private static void randomGuard() {
        // Missing-provider test precedes installation of the independently seeded fixture RNG.
        forge.util.MyRandom.setRandom(new Random(91));
        boolean[] evaluated = {false};
        rejectsRandom(() -> BenchRandomAudit.withoutRandomUse("missing", () -> { evaluated[0] = true; return 0; }), "requires");
        check(!evaluated[0], "missing provider does not evaluate query");
        BenchRandomAudit.install(73019);
        var normal = new Random(73019);
        var audited = forge.util.MyRandom.getRandom();
        check(audited.nextInt() == normal.nextInt(), "unguarded Default nextInt stream identical");
        check(audited.nextGaussian() == normal.nextGaussian(), "unguarded Default first Gaussian identical");
        // Both providers now have a cached second Gaussian. A guard must not consume it.
        var before = BenchRandomAudit.begin();
        rejectsRandom(() -> BenchRandomAudit.withoutRandomUse("int", () -> audited.nextInt()), "attempted random operation");
        rejectsRandom(() -> BenchRandomAudit.withoutRandomUse("seed", () -> { audited.setSeed(12); return 0; }), "attempted random operation");
        rejectsRandom(() -> BenchRandomAudit.withoutRandomUse("Gaussian", () -> audited.nextGaussian()), "attempted random operation");
        rejectsRandom(() -> BenchRandomAudit.withoutRandomUse("swallowed", () -> {
            try { audited.nextInt(); } catch (IllegalStateException expected) { /* engine fallback must not escape */ }
            return 99;
        }), "attempted random operation");
        rejectsRandom(() -> BenchRandomAudit.withoutRandomUse("outer swallowed", () -> {
            try { BenchRandomAudit.withoutRandomUse("inner attempted", () -> audited.nextInt()); }
            catch (IllegalStateException expected) { /* nested rejected estimate must remain rejected */ }
            return 99;
        }), "attempted random operation");
        int value = BenchRandomAudit.withoutRandomUse("outer", () -> {
            check(BenchRandomAudit.withoutRandomUse("inner", () -> 9) == 9, "nested successful scope");
            try { BenchRandomAudit.withoutRandomUse("inner failure", () -> { throw new IllegalArgumentException("fixture"); }); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().equals("fixture"), "nested query exception preserved"); }
            return 17;
        });
        check(value == 17, "successful query answer unchanged");
        BenchRandomAudit.assertUnchanged(before, "all rejected random operations");
        check(audited.nextGaussian() == normal.nextGaussian(), "cached Gaussian preserved and scope released");
        try { BenchRandomAudit.withoutRandomUse("outer failure", () -> { throw new IllegalArgumentException("outer"); }); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().equals("outer"), "outer query exception preserved"); }
        for (int i = 0; i < 8; i++) {
            check(audited.nextLong() == normal.nextLong(), "unguarded Default continuation identical " + i);
            check(audited.nextGaussian() == normal.nextGaussian(), "unguarded Gaussian continuation identical " + i);
        }
    }
    private static void randomAmount(int seat) throws Exception {
        var game = game(seat); var p = game.getPlayers().get(seat); var other = game.getPlayers().get(1 - seat);
        var source = card("Plains", p, ZoneType.Battlefield);
        var spell = card("Walking Ballista", p, ZoneType.Hand).getFirstSpellAbility(); spell.setActivatingPlayer(p);
        game.getAction().checkStateEffects(true); setupActors(game, other, false);
        var mana = source.getManaAbilities().get(0);
        mana.getMapParams().put("Amount", "X"); mana.setSVar("X", "Count$Random.1.3");
        var state = rawState(game); var beforeActors = actors(game); int id = sequence(); var rng = BenchRandomAudit.begin();
        try { StateEncoder.encodeSpellAbility(spell); throw new AssertionError("Random X estimate accepted"); }
        catch (RulesCostFeasibility.Unsupported expected) { check(true, "random Amount becomes explicit unsupported X seat=" + seat); }
        try { repeats(p, spell, new Cost("2", false)); throw new AssertionError("Random repeat estimate accepted"); }
        catch (RulesCostFeasibility.Unsupported expected) { check(true, "random Amount becomes explicit unsupported repeat seat=" + seat); }
        check(beforeActors.equals(actors(game)) && state.equals(rawState(game)) && id == sequence(), "random query preserves actors/raw state/IDs seat=" + seat);
        BenchRandomAudit.assertUnchanged(rng, "random Amount query rejected BEFORE draw"); check(true, "random Amount rejection consumes no RNG");
        // Prove the expression is executable random arithmetic in the pinned native estimator.
        var nativeBefore = BenchRandomAudit.begin(); int stock = ComputerUtilMana.getAvailableManaEstimate(p);
        check(stock >= 1 && stock <= 3, "native random Amount returns its sampled value");
        try { BenchRandomAudit.assertUnchanged(nativeBefore, "expected native random Amount"); throw new AssertionError("Native random Amount consumed no random draw"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("consumed or reset RNG"), "native dynamic Amount actually consumes RNG"); }
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                new Class<?>[]{IGuiBase.class}, (proxy, method, values) -> switch (method.getName()) {
                    case "getAssetsDir" -> args[0] + "/forge-gui/";
                    case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                    case "getCurrentVersion" -> "legacy-purity-fixture";
                    default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            boolean baseline = args.length > 1 && args[1].equals("--baseline");
            if (baseline) BenchRandomAudit.install(73019); else randomGuard();
            for (int seat = 0; seat < 2; seat++) for (boolean unset : List.of(false, true)) {
                scenario(seat, new String[]{"Plains", "Forest"}, false, 20, unset, baseline);
                if (!baseline) {
                    scenario(seat, new String[]{"Boros Signet"}, false, 20, unset, false);
                    scenario(seat, new String[]{"Boros Signet", "Plains"}, false, 20, unset, false);
                    scenario(seat, new String[]{"Boros Signet", "POOL"}, false, 20, unset, false);
                    scenario(seat, new String[]{"Fire-Lit Thicket", "Forest"}, false, 20, unset, false);
                    scenario(seat, new String[]{"Priest of Titania", "Llanowar Elves"}, false, 20, unset, false);
                    scenario(seat, new String[]{"Ancient Tomb", "Mana Confluence"}, false, 20, unset, false);
                    scenario(seat, new String[]{"Plains", "Ancient Tomb"}, true, 20, unset, false);
                    scenario(seat, new String[]{"Mana Confluence"}, false, 0, unset, false);
                }
            }
            if (!baseline) { failure(0); failure(1); randomAmount(0); randomAmount(1); }
            System.out.println("PASS all " + checks + " legacy encoder checks; DEVELOPMENT ONLY"); System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
