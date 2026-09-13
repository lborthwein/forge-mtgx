package forge.bench;

import forge.card.mana.ManaCost;
import forge.game.card.CardCollection;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.util.List;
import static forge.bench.CombatDeclarationExecutionSmoke.*;

/** Actual callbacks, including rejected/fallback and exceptional paths.
 * Ownership evidence only; no strength or whole-controller certificate. */
public final class ControllerLegacyCoverageSmoke {
    private static int checks;
    private static void verify(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
        checks++;
    }
    private static void bucket(C c, String method, String owner) {
        var buckets = c.controller().getCounters().toJson().getAsJsonObject("controllerCoverage")
                .getAsJsonObject("methods").getAsJsonObject(method);
        verify(buckets != null && buckets.get(owner).getAsInt() == 1, method + " expected " + owner);
        verify(buckets.entrySet().stream().mapToInt(e -> e.getValue().getAsInt()).sum() == 1,
                method + " one invocation, classified once");
    }
    private static C fresh(int seat, String mode) {
        var c = context(seat, mode);
        c.controller().getCounters().reset();
        return c;
    }
    private static void start(int seat, String answer) {
        var c = fresh(seat, "BRIDGE");
        if (answer.equals("play") || answer.equals("draw")) c.host().payload.addProperty("play", answer.equals("play"));
        if (answer.equals("delegate")) c.host().payload.addProperty("delegate", true);
        var result = c.controller().chooseStartingPlayer(true);
        verify(result == (answer.equals("draw") ? c.other() : c.actor()), "actual starting-player answer");
        bucket(c, "chooseStartingPlayer", List.of("play", "draw").contains(answer) ? "host" : "stock");
    }
    private static void inherited(int seat, String mode) {
        var c = fresh(seat, mode);
        verify(!c.controller().acceptsDrawOffer(), "inherited draw-offer answer preserved");
        bucket(c, "acceptsDrawOffer", "stock");
        try {
            c.controller().chooseStartingHand(null);
            throw new AssertionError("invalid inherited domain did not throw");
        } catch (NullPointerException expected) {
            bucket(c, "chooseStartingHand", "unclassified");
        }
    }
    private static void nullCallbacks(int seat) {
        var c = fresh(seat, "NULL");
        verify(c.controller().chooseStartingPlayer(true) == c.actor(), "null takes play");
        bucket(c, "chooseStartingPlayer", "stock");
        var scry = c.controller().arrangeForScry(new CardCollection());
        verify(scry.getLeft().isEmpty() && scry.getRight().isEmpty(), "empty native scry");
        bucket(c, "arrangeForScry", "stock");
        c.controller().tuckCardsViaMulligan(new CardCollection(), 0);
        bucket(c, "tuckCardsViaMulligan", "stock");
        c.controller().mulliganKeepHand(c.other(), 0);
        bucket(c, "mulliganKeepHand", "stock");
        c.controller().orderSimultaneousSa(List.of());
        bucket(c, "orderSimultaneousSa", "stock");
        c.controller().reveal(new CardCollection(), ZoneType.Hand, c.actor(), "test", false);
        bucket(c, "reveal", "stock");
        var card = card(c, c.actor(), "Grizzly Bears");
        var ability = card.getFirstSpellAbility();
        ability.setActivatingPlayer(c.actor());
        c.controller().payManaCost(ManaCost.ZERO, null, ability, "test", null, false);
        bucket(c, "payManaCost", "stock");
        verify(c.host().asks == 0, "null never asks host");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                    new Class<?>[]{IGuiBase.class}, (p,m,v) -> switch(m.getName()) {
                case "getAssetsDir" -> args[0] + "/forge-gui/";
                case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                case "getCurrentVersion" -> "controller-legacy-coverage";
                default -> throw new AssertionError("unexpected GUI " + m.getName());
            }));
            FModel.initialize(null, prefs -> {
                prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                prefs.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (int seat = 0; seat < 2; seat++) {
                for (String answer : List.of("play", "draw", "malformed", "delegate")) start(seat, answer);
                for (String mode : List.of("BRIDGE", "NULL")) inherited(seat, mode);
                nullCallbacks(seat);
            }
            System.out.println("PASS " + checks + " legacy controller ownership checks; NOT whole-controller certification");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
