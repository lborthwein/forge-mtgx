package forge.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** DEVELOPMENT-only observation of native battlefield target legality in actual priority menus. */
public final class PriorityBoardTargetWitness {
    private static int checks;

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }

    private static Card card(String name, Player owner, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        Card result = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)), owner);
        result.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(zone).add(result);
        result.setSickness(false);
        return result;
    }

    private static SpellAbility spell(Card card) {
        return card.getSpellAbilities().stream().filter(SpellAbility::isSpell).findFirst().orElseThrow();
    }

    private static int globalSequence(Class<?> type, String field) throws ReflectiveOperationException {
        var value = type.getDeclaredField(field);
        value.setAccessible(true);
        return value.getInt(null);
    }

    @SuppressWarnings("unchecked")
    private static Decision productionDecision(Player actor) throws Exception {
        Method build = PlayerControllerBridge.class.getDeclaredMethod("buildPriorityDecision");
        build.setAccessible(true);
        Object decision;
        try {
            decision = build.invoke(actor.getController());
        } catch (InvocationTargetException failure) {
            throw (failure.getCause() instanceof Exception e) ? e : failure;
        }
        Method menu = decision.getClass().getDeclaredMethod("menu");
        Method body = decision.getClass().getDeclaredMethod("body");
        menu.setAccessible(true);
        body.setAccessible(true);
        return new Decision((List<SpellAbility>) menu.invoke(decision), ((JsonObject) body.invoke(decision)).deepCopy());
    }

    private record Decision(List<SpellAbility> menu, JsonObject body) {}

    private static SpellAbility offered(Decision decision, Card source) {
        return decision.menu().stream()
                .filter(sa -> sa.getHostCard() != null && sa.getHostCard().getId() == source.getId() && sa.isSpell())
                .findFirst().orElse(null);
    }

    private static JsonObject binding(String role, SpellAbility ability, Card target, int controllerSeat) {
        JsonObject row = new JsonObject();
        row.addProperty("role", role);
        row.addProperty("name", target.getName());
        row.addProperty("fid", target.getId());
        row.addProperty("type", target.getType().toString()); // retained for the Forge-side object identity/type record
        row.addProperty("controller", controllerSeat);
        row.addProperty("allowed", ability.canTarget(target));
        return row;
    }

    private static JsonObject playerBinding(SpellAbility ability, Player player, int playerSeat) {
        JsonObject row = new JsonObject();
        row.addProperty("seat", playerSeat);
        row.addProperty("allowed", ability.canTarget(player));
        return row;
    }

    private static JsonObject row(JsonArray rows, String kind, int id) {
        for (var element : rows) {
            JsonObject row = element.getAsJsonObject();
            if (kind.equals(row.get("kind").getAsString()) && id == row.get("id").getAsInt()) return row;
        }
        throw new AssertionError("missing board domain row " + kind + "/" + id);
    }

    private static void publishAndCheckBoardDomain(JsonObject ask, List<SpellAbility> menu, int seat, List<Card> battlefield, List<Player> players) {
        check(ask.has("priorityBoardTargetsVersion") && PriorityBoardTargetDomain.VERSION.equals(ask.get("priorityBoardTargetsVersion").getAsString()),
                "actual production ask publishes board domain version seat=" + seat);
        JsonArray encoded = ask.getAsJsonArray("menu");
        JsonObject pass = PriorityBoardTargetDomain.encode(null);
        check(pass.equals(encoded.get(0).getAsJsonObject().get("boardTargetDomain")), "actual production pass domain matches rules encoder");
        check("none".equals(pass.get("kind").getAsString()), "pass publishes board target domain none seat=" + seat);
        for (int i = 0; i < menu.size(); i++) {
            SpellAbility ability = menu.get(i);
            JsonObject domain = PriorityBoardTargetDomain.encode(ability);
            check(domain.equals(encoded.get(i + 1).getAsJsonObject().get("boardTargetDomain")), "actual production option domain matches rules encoder");
            if (!ability.usesTargeting()) {
                check("none".equals(domain.get("kind").getAsString()), "actual simple no-target option publishes none seat=" + seat + " fid=" + ability.getHostCard().getId());
                continue;
            }
            check("exact".equals(domain.get("kind").getAsString()), "single actual board/player target option publishes exact seat=" + seat + " fid=" + ability.getHostCard().getId());
            check(domain.get("sourceFid").getAsInt() == ability.getHostCard().getId() && domain.get("actor").getAsInt() == seat, "exact domain source and physical actor seat=" + seat + " fid=" + ability.getHostCard().getId());
            JsonArray rows = domain.getAsJsonArray("rows");
            check(rows.size() == battlefield.size() + players.size(), "exact domain covers full battlefield and every player seat=" + seat + " fid=" + ability.getHostCard().getId());
            for (Card card : battlefield) check(row(rows, "card", card.getId()).get("allowed").getAsBoolean() == ability.canTarget(card), "exact card row equals native canTarget fid=" + card.getId());
            for (int playerSeat = 0; playerSeat < players.size(); playerSeat++) check(row(rows, "player", playerSeat).get("allowed").getAsBoolean() == ability.canTarget(players.get(playerSeat)), "exact player row equals native canTarget seat=" + playerSeat);
            check(row(rows, "card", 1) != null && row(rows, "player", 1) != null, "card/player same numeric id remains disambiguated by kind seat=" + seat);
        }
    }

    private static void emit(String name, int seat, SpellAbility ability, JsonObject ask, Card ownBear, Card enemyBear, Card protectedCreature, Card hexproofCreature, Player actor, Player other) {
        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        result.addProperty("seat", seat);
        JsonObject fixtureAsk = ask.deepCopy();
        fixtureAsk.addProperty("id", 910000000 + seat * 100 + ability.getHostCard().getId());
        fixtureAsk.addProperty("fixtureProvenance", "DEVELOPMENT PriorityBoardTargetWitness synthetic observation; not a live RPC ask");
        fixtureAsk.addProperty("fixtureSynthetic", true);
        result.add("ask", fixtureAsk);
        result.addProperty("sourceFid", ability.getHostCard().getId());
        result.addProperty("bindingScope", "tested battlefield creature subset; lands are represented only in ask.state");
        JsonArray bindings = new JsonArray();
        bindings.add(binding("own-bear", ability, ownBear, seat));
        bindings.add(binding("opponent-bear", ability, enemyBear, 1 - seat));
        bindings.add(binding("protected", ability, protectedCreature, 1 - seat));
        bindings.add(binding("hexproof", ability, hexproofCreature, 1 - seat));
        result.add("bindings", bindings);
        JsonArray players = new JsonArray();
        players.add(playerBinding(ability, actor, seat));
        players.add(playerBinding(ability, other, 1 - seat));
        result.add("players", players);
        System.out.println("BOARD_DOMAIN_CASE " + result);
    }

    private static void run(int seat) throws Exception {
        var session = new BenchSession(new JsonRpcChannel(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
        var lobby = new LobbyPlayerBridge("Actor", null, session, BenchSession.Mode.BRIDGE, seat);
        lobby.setAiProfile("Default");
        var own = new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var enemy = new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Other", 1 - seat, 0, null, "Default"));
        var game = new Match(new GameRules(GameType.Constructed), seat == 0 ? List.of(own, enemy) : List.of(enemy, own), "Priority board target witness").createGame();
        game.setAge(GameStage.Play);
        Player actor = game.getPlayers().get(seat);
        Player other = game.getPlayers().get(1 - seat);
        session.setLiveGame(game);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, actor);
        card("Plains", actor, ZoneType.Battlefield);
        card("Mountain", actor, ZoneType.Battlefield);
        card("Swamp", actor, ZoneType.Battlefield);
        Card swords = card("Swords to Plowshares", actor, ZoneType.Hand);
        Card bolt = card("Lightning Bolt", actor, ZoneType.Hand);
        Card ephemerate = card("Ephemerate", actor, ZoneType.Hand);
        Card ritual = card("Dark Ritual", actor, ZoneType.Hand);
        // Engine-script controls kept out of the actual priority menu: these exercise
        // conservative refusal without changing the offered-action witness.
        Card fireball = card("Fireball", actor, ZoneType.Exile);
        Card cryptic = card("Cryptic Command", actor, ZoneType.Exile);
        Card forkedBolt = card("Forked Bolt", actor, ZoneType.Exile);
        Card graveyardBolt = card("Lightning Bolt", actor, ZoneType.Exile);
        Card opponentOnly = card("Stupor", actor, ZoneType.Exile);
        Card malformedZone = card("Lightning Bolt", actor, ZoneType.Exile);
        Card ownBear = card("Grizzly Bears", actor, ZoneType.Battlefield);
        Card emrakul = card("Emrakul, the Aeons Torn", other, ZoneType.Battlefield);
        Card caryatid = card("Sylvan Caryatid", other, ZoneType.Battlefield);
        Card enemyBear = card("Grizzly Bears", other, ZoneType.Battlefield);
        SpellAbility sourceSwords = spell(swords);
        SpellAbility sourceBolt = spell(bolt);
        SpellAbility sourceEphemerate = spell(ephemerate);
        SpellAbility sourceRitual = spell(ritual);
        SpellAbility sourceFireball = spell(fireball);
        SpellAbility sourceCryptic = spell(cryptic);
        SpellAbility sourceForkedBolt = spell(forkedBolt);
        SpellAbility sourceGraveyardBolt = spell(graveyardBolt);
        SpellAbility sourceOpponentOnly = spell(opponentOnly);
        SpellAbility sourceMalformedZone = spell(malformedZone);
        sourceMalformedZone.setActivatingPlayer(actor);
        sourceMalformedZone.getTargetRestrictions().setZone((List<ZoneType>) null);
        sourceSwords.setActivatingPlayer(actor);
        sourceBolt.setActivatingPlayer(actor);
        sourceEphemerate.setActivatingPlayer(actor);
        sourceRitual.setActivatingPlayer(actor);
        sourceFireball.setActivatingPlayer(actor);
        sourceCryptic.setActivatingPlayer(actor);
        sourceForkedBolt.setActivatingPlayer(actor);
        sourceGraveyardBolt.setActivatingPlayer(actor);
        sourceOpponentOnly.setActivatingPlayer(actor);
        // Native setter control: player validity remains structural even when the
        // card-target zone is explicitly non-battlefield. This occurs before both
        // purity captures and allocates no copied ability/identity.
        sourceGraveyardBolt.getTargetRestrictions().setZone(ZoneType.Graveyard);
        game.getPhaseHandler().setPriority(actor);
        game.getAction().checkStaticAbilities();
        BenchRandomAudit.install(4900 + seat);

        var before = BenchMenuStateAudit.capture(game);
        var rng = BenchRandomAudit.begin();
        var swordsActor = sourceSwords.getActivatingPlayer();
        var boltActor = sourceBolt.getActivatingPlayer();
        var ephemerateActor = sourceEphemerate.getActivatingPlayer();
        var swordsTargets = new ArrayList<>(sourceSwords.getTargets());
        var boltTargets = new ArrayList<>(sourceBolt.getTargets());
        var ephemerateTargets = new ArrayList<>(sourceEphemerate.getTargets());
        int abilityIds = globalSequence(SpellAbility.class, "maxId");
        int stackIds = globalSequence(forge.game.spellability.SpellAbilityStackInstance.class, "maxId");
        Decision decision = productionDecision(actor);
        BenchMenuStateAudit.assertUnchanged(before, game);
        BenchRandomAudit.assertUnchanged(rng, "production board enumeration witness");
        check(true, "production board enumeration consumes no RNG seat=" + seat);
        check(sourceSwords.getActivatingPlayer() == swordsActor && sourceBolt.getActivatingPlayer() == boltActor && sourceEphemerate.getActivatingPlayer() == ephemerateActor
                && swordsTargets.equals(sourceSwords.getTargets()) && boltTargets.equals(sourceBolt.getTargets()) && ephemerateTargets.equals(sourceEphemerate.getTargets()), "source actors and targets unchanged by enumeration seat=" + seat);
        check(abilityIds == globalSequence(SpellAbility.class, "maxId") && stackIds == globalSequence(forge.game.spellability.SpellAbilityStackInstance.class, "maxId"), "production enumeration allocates no global ability or stack IDs seat=" + seat);

        SpellAbility actualSwords = offered(decision, swords);
        SpellAbility actualBolt = offered(decision, bolt);
        SpellAbility actualEphemerate = offered(decision, ephemerate);
        SpellAbility actualRitual = offered(decision, ritual);
        check(actualSwords != null, "actual production priority offers Swords because legal battlefield bear exists seat=" + seat);
        check(actualBolt != null, "actual production priority offers Lightning Bolt player-target control seat=" + seat);
        check(actualEphemerate != null, "actual production priority offers Ephemerate own-only control seat=" + seat);
        check(actualRitual != null && !actualRitual.usesTargeting(), "actual production priority offers simple no-target Dark Ritual control seat=" + seat);

        // This second envelope deliberately includes every rules-only canTarget read below,
        // including the reads performed while serializing bindings for the replay witness.
        var targetReadBefore = BenchMenuStateAudit.capture(game);
        var targetReadRng = BenchRandomAudit.begin();
        var swordsReadActor = sourceSwords.getActivatingPlayer();
        var boltReadActor = sourceBolt.getActivatingPlayer();
        var ephemerateReadActor = sourceEphemerate.getActivatingPlayer();
        var swordsReadTargets = new ArrayList<>(sourceSwords.getTargets());
        var boltReadTargets = new ArrayList<>(sourceBolt.getTargets());
        var ephemerateReadTargets = new ArrayList<>(sourceEphemerate.getTargets());
        int targetReadAbilityIds = globalSequence(SpellAbility.class, "maxId");
        int targetReadStackIds = globalSequence(forge.game.spellability.SpellAbilityStackInstance.class, "maxId");

        check(actualSwords.canTarget(ownBear) && !actualSwords.canTarget(emrakul) && !actualSwords.canTarget(caryatid) && actualSwords.canTarget(enemyBear), "Swords literal native board legality: own bear=true, opponent bear=true, Emrakul protection=false, Caryatid hexproof=false seat=" + seat);
        check(actualBolt.canTarget(ownBear) && actualBolt.canTarget(enemyBear) && !actualBolt.canTarget(emrakul) && !actualBolt.canTarget(caryatid) && actualBolt.canTarget(actor) && actualBolt.canTarget(other), "Lightning Bolt literal creature/player control: bears and players=true, protected and hexproof=false seat=" + seat);
        check(actualEphemerate.canTarget(ownBear) && !actualEphemerate.canTarget(emrakul) && !actualEphemerate.canTarget(caryatid) && !actualEphemerate.canTarget(enemyBear), "Ephemerate literal own-bear=true and all opponent creatures=false seat=" + seat);
        check("unsupported".equals(PriorityBoardTargetDomain.encode(sourceFireball).get("kind").getAsString()), "actual X-script target control is explicitly unsupported seat=" + seat);
        check("unsupported".equals(PriorityBoardTargetDomain.encode(sourceCryptic).get("kind").getAsString()), "actual modal-script target control is explicitly unsupported seat=" + seat);
        check("unsupported".equals(PriorityBoardTargetDomain.encode(sourceForkedBolt).get("kind").getAsString()), "actual multi-target-script control is explicitly unsupported seat=" + seat);
        JsonObject graveyardBoltDomain = PriorityBoardTargetDomain.encode(sourceGraveyardBolt);
        check("exact".equals(graveyardBoltDomain.get("kind").getAsString()), "native graveyard-zone Lightning Bolt still has exact player domain seat=" + seat);
        JsonArray graveyardBoltRows = graveyardBoltDomain.getAsJsonArray("rows");
        check(row(graveyardBoltRows, "player", seat).get("allowed").getAsBoolean() && row(graveyardBoltRows, "player", 1 - seat).get("allowed").getAsBoolean(), "non-battlefield zone does not conceal native Lightning Bolt player targets seat=" + seat);
        JsonObject opponentOnlyDomain = PriorityBoardTargetDomain.encode(sourceOpponentOnly);
        check("exact".equals(opponentOnlyDomain.get("kind").getAsString()), "actual opponent-only player target publishes exact seat=" + seat);
        JsonArray opponentOnlyRows = opponentOnlyDomain.getAsJsonArray("rows");
        check(!row(opponentOnlyRows, "player", seat).get("allowed").getAsBoolean() && row(opponentOnlyRows, "player", 1 - seat).get("allowed").getAsBoolean(), "opponent-only native player row distinguishes physical seats=" + seat);

        JsonObject ask = decision.body().deepCopy();
        check("unsupported".equals(PriorityBoardTargetDomain.encode(sourceMalformedZone).get("kind").getAsString()),
                "null zone is malformed, not native Battlefield default");
        ask.addProperty("type", "ask");
        ask.addProperty("kind", "priority");
        List<Card> battlefield = new ArrayList<>();
        for (Player player : game.getPlayers()) battlefield.addAll(player.getCardsIn(ZoneType.Battlefield));
        publishAndCheckBoardDomain(ask, decision.menu(), seat, battlefield, game.getPlayers());
        emit("Swords to Plowshares", seat, actualSwords, ask, ownBear, enemyBear, emrakul, caryatid, actor, other);
        emit("Lightning Bolt", seat, actualBolt, ask, ownBear, enemyBear, emrakul, caryatid, actor, other);
        emit("Ephemerate", seat, actualEphemerate, ask, ownBear, enemyBear, emrakul, caryatid, actor, other);
        BenchMenuStateAudit.assertUnchanged(targetReadBefore, game);
        BenchRandomAudit.assertUnchanged(targetReadRng, "rules-only target enumeration and BOARD_DOMAIN_CASE emission");
        check(sourceSwords.getActivatingPlayer() == swordsReadActor && sourceBolt.getActivatingPlayer() == boltReadActor && sourceEphemerate.getActivatingPlayer() == ephemerateReadActor
                && swordsReadTargets.equals(sourceSwords.getTargets()) && boltReadTargets.equals(sourceBolt.getTargets()) && ephemerateReadTargets.equals(sourceEphemerate.getTargets()), "source actors and targets unchanged by target enumeration and emission seat=" + seat);
        check(targetReadAbilityIds == globalSequence(SpellAbility.class, "maxId") && targetReadStackIds == globalSequence(forge.game.spellability.SpellAbilityStackInstance.class, "maxId"), "target enumeration and emission allocate no global ability or stack IDs seat=" + seat);
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[] { IGuiBase.class },
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "priority-board-target-witness";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            run(0);
            run(1);
            System.out.println("PASS " + checks + " priority board-target witness checks; no policy or win-rate claim");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
