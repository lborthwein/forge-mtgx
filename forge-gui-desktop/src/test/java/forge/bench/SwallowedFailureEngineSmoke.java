package forge.bench;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.game.*;
import forge.game.phase.PhaseType;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.io.*;
import java.util.List;

/** Inject faults at real controller boundaries, swallow them, then run the result guard. */
public final class SwallowedFailureEngineSmoke {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static final class DisconnectableInput extends InputStream {
        final OptionalTriggerExecutionSmoke.Host host = new OptionalTriggerExecutionSmoke.Host();
        boolean eof;
        @Override public int read() { return eof ? -1 : host.read(); }
        @Override public int read(byte[] b, int off, int len) { return eof ? -1 : host.read(b, off, len); }
    }
    private record Fixture(OptionalTriggerExecutionSmoke.Context context, DisconnectableInput input) {}
    private static Fixture fixture(int seat) {
        return fixture(seat, null);
    }
    private static Fixture fixture(int seat, BenchSession shared) {
        var input = new DisconnectableInput();
        var session = shared == null ? new BenchSession(new JsonRpcChannel(input, input.host.wire)) : shared;
        var lobby = new LobbyPlayerBridge("Observed", null, session, BenchSession.Mode.BRIDGE, seat);
        lobby.setAiProfile("Default");
        var own = new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var other = new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Opponent", 1-seat, 0, null, "Default"));
        var game = new Match(new GameRules(GameType.Constructed), seat==0 ? List.of(own, other) : List.of(other, own), "Failure fixture").createGame();
        var actor = game.getPlayers().get(seat);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, actor);
        session.setLiveGame(game);
        return new Fixture(new OptionalTriggerExecutionSmoke.Context(game, actor, input.host, session), input);
    }
    private static JsonObject normalOutcome(int winner) {
        var o = new JsonObject();
        o.addProperty("winner", winner); o.addProperty("reason", "AllOpponentsLost"); o.addProperty("crashed", false);
        return o;
    }
    private static void run(int seat, String fault) throws Exception {
        var f = fixture(seat); var c = f.context();
        var controller = (PlayerControllerBridge)c.actor().getController();
        var clean = normalOutcome(seat); var before = clean.deepCopy();
        BenchMain.guardIntegrityOutcome(c.session(), c.game(), clean);
        check(clean.equals(before), "healthy result is byte-equivalent");
        Throwable swallowed = null;
        if (fault.equals("identity")) {
            var card = OptionalTriggerExecutionSmoke.card("Grizzly Bears", c);
            // Inject inconsistent pending execution state; do not bypass the production check.
            var pending = PlayerControllerBridge.class.getDeclaredField("pendingExternalAbility");
            pending.setAccessible(true); pending.set(controller, new SpellAbility.EmptySa(card));
            try { controller.playChosenSpellAbility(new SpellAbility.EmptySa(card)); }
            catch (RulesCostFeasibility.Unsupported expected) { swallowed = expected; }
        } else if (fault.equals("no-stack") || fault.equals("simultaneous")) {
            var source = OptionalTriggerExecutionSmoke.card("Grizzly Bears", c);
            var nonTrigger = new SpellAbility.EmptySa(source);
            nonTrigger.setActivatingPlayer(c.actor());
            try {
                if (fault.equals("no-stack")) controller.playSpellAbilityNoStack(nonTrigger, false);
                else controller.orderAndPlaySimultaneousSa(List.of(nonTrigger));
            } catch (RulesCostFeasibility.Unsupported expected) { swallowed = expected; }
        } else {
            OptionalTriggerExecutionSmoke.card("Soulherder", c);
            var bear = OptionalTriggerExecutionSmoke.card("Grizzly Bears", c);
            OptionalTriggerExecutionSmoke.ready(c); c.host().target = bear.getId();
            var wrapper = OptionalTriggerExecutionSmoke.queue(c);
            if (fault.equals("closed-channel")) {
                f.input().eof = true;
                c.session().getChannel().ask("fixture-disconnect", new JsonObject());
                check(c.session().getChannel().isClosed(), "real EOF closes channel");
            }
            try {
                if (fault.equals("static-trigger")) {
                    controller.playTrigger(wrapper.getHostCard(), wrapper, false);
                    throw new AssertionError("invalid static trigger accepted");
                }
                controller.withTriggerResolutionScope(wrapper, () -> {
                    if (fault.equals("closed-channel")) throw new AssertionError("closed channel entered native resolution");
                    if (fault.equals("runtime")) throw new IllegalStateException("injected native resolution failure");
                    throw new AssertionError("injected native resolution error");
                });
            } catch (RuntimeException | Error expected) { swallowed = expected; }
            check(!fault.equals("closed-channel") || swallowed instanceof RulesCostFeasibility.Unsupported,
                    "channel refusal precedes native continuation");
        }
        check(swallowed != null, "fault reached real controller boundary");
        final String failure = c.session().integrityFailure(c.game());
        check(failure != null && failure.contains("seat " + seat), "failure is retained for exact game and seat");
        c.session().setLiveGame(c.game());
        check(failure.equals(c.session().integrityFailure(c.game())), "same-game assignment cannot reset failure");
        c.session().noteIntegrityFailure(c.game(), seat, "later failure", new IllegalArgumentException("later"));
        check(failure.equals(c.session().integrityFailure(c.game())), "first failure preserved");
        // A controller swap (including temporary control ending) must not erase the failure.
        c.actor().dangerouslySetController(new forge.ai.PlayerControllerAi(c.game(), c.actor(), c.actor().getLobbyPlayer()));
        var outcome = normalOutcome(seat);
        BenchMain.guardIntegrityOutcome(c.session(), c.game(), outcome);
        check(outcome.get("crashed").getAsBoolean() && outcome.get("aborted").getAsString().equals("InstrumentError"), "swallowed failure invalidates outcome");
        check(outcome.get("winner").getAsInt()==seat, "raw Forge verdict retained for diagnostics");
        var row = new JsonObject(); row.addProperty("case", fault); row.addProperty("seat", seat); row.add("outcome", outcome);
        System.out.println("INTEGRITY_OUTCOME " + row);
        var next = fixture(seat, c.session()).context().game();
        check(c.session().integrityFailure(c.game())==null, "old-game lookup cannot borrow new game's state");
        c.session().noteIntegrityFailure(c.game(), seat, "stale or copied game", swallowed);
        check(c.session().integrityFailure(next)==null, "next game clean; stale/copied controller cannot poison it");
    }
    public static void main(String[] args) { try {
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (p,m,v)->switch(m.getName()) {
            case "getAssetsDir" -> args[0]+"/forge-gui/";
            case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame" -> false;
            case "getCurrentVersion" -> "failure-boundary";
            default -> throw new AssertionError(m.getName());
        }));
        FModel.initialize(null, prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for (int seat=0; seat<2; seat++) for (String fault:List.of("identity","closed-channel","runtime","error","no-stack","simultaneous","static-trigger")) run(seat,fault);
        System.out.println("PASS swallowed failure boundary " + checks + " checks"); System.exit(0);
    } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); } }
}
