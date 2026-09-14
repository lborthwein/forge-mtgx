package forge.bench;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import forge.ai.PlayerControllerAi;
import forge.game.player.PlayerController;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.tools.ToolProvider;

/** Pinned API/source accounting, NOT a decision-completeness certificate.
 * Source AST binds each actual reflected overload to its own count site.
 * Inherited helpers are not made harmless by names such as get/prepare/play.
 */
public final class ControllerSurfaceSmoke {
    private static final String SURFACE = "d08f3f1dd44f22bbd329f81b42f35ae1ced128d6035fce514961ff166b60330b";
    // Exact signatures, reviewed bodies pinned below. Forwarders do not make a
    // choice before dispatching into a counted overload. Getter returns can
    // expose mutable references: this review is not whole-program dataflow proof.
    private static final Map<String, String> REVIEWED = Map.ofEntries(
        Map.entry("addKeywordCost(SpellAbility,Cost,KeywordInterface,String)", "forward: chooseNumberForKeywordCost result comparison"),
        Map.entry("canPlayUnlimitedLands()", "pure: constant false capability"),
        Map.entry("chooseBinary(SpellAbility,String,BinaryChoiceType)", "forward: Boolean overload"),
        Map.entry("chooseCardsToDiscardFrom(Player,SpellAbility,CardCollection,int,int)", "forward: visible-card overload"),
        Map.entry("chooseSector(Card,String)", "forward: constant sector list"),
        Map.entry("chooseSingleEntityForEffect(FCollectionView,SpellAbility,String,Map)", "forward: full entity overload"),
        Map.entry("chooseSingleEntityForEffect(FCollectionView,SpellAbility,String,boolean,Map)", "forward: full entity overload"),
        Map.entry("chooseSomeType(String,SpellAbility,Collection)", "forward: optional=false overload"),
        Map.entry("chooseSprocket(Card)", "forward: constant sprocket list"),
        Map.entry("confirmAction(SpellAbility,PlayerActionConfirmMode,String,Card,Map)", "forward: full confirmation overload"),
        Map.entry("confirmAction(SpellAbility,PlayerActionConfirmMode,String,Map)", "forward: full confirmation overload"),
        Map.entry("endTempShowCards()", "notification: empty body"),
        Map.entry("getAbilityToPlay(Card,List)", "forward: trigger-event overload"),
        Map.entry("getAi()", "pure: returns existing brains reference, no execution"),
        Map.entry("getAnteResult()", "pure: existing game-view ante lookup"),
        Map.entry("getCostDecisionMaker(Player,SpellAbility,boolean)", "forward: prompt overload"),
        Map.entry("getFullControl()", "pure: existing fullControls reference"),
        Map.entry("getGame()", "pure: existing game reference"),
        Map.entry("getLobbyPlayer()", "pure: existing lobby reference"),
        Map.entry("getMatch()", "pure: existing match reference"),
        Map.entry("getPlayer()", "pure: existing player reference"),
        Map.entry("isAI()", "pure: constant true capability"),
        Map.entry("isFullControl(FullControlFlag)", "pure: set membership"),
        Map.entry("isGuiPlayer()", "pure: constant false capability"),
        Map.entry("isOrderedZone()", "pure: constant false capability"),
        Map.entry("payManaCost(CostPartMana,SpellAbility,String,ManaConversionMatrix,boolean)", "forward: getManaCostFor then full payment overload; cost computation is not ownership"),
        Map.entry("pilotsNonAggroDeck()", "pure: existing boolean field"),
        Map.entry("reveal(CardCollectionView,ZoneType,Player)", "forward: reveal overload chain"),
        Map.entry("reveal(CardCollectionView,ZoneType,Player,String)", "forward: full reveal overload"),
        Map.entry("reveal(DelayedReveal)", "forward: reveal each zone subset; no card selection policy"),
        Map.entry("reveal(List,ZoneType,PlayerView,String)", "forward: full reveal overload"),
        Map.entry("tempShowCards(Iterable)", "notification: empty body")
    );
    private record SourceMethod(String body, Set<String> counted, boolean empty) {
        SourceMethod(String body, Set<String> counted) { this(body, counted, false); }
    }
    private static String signature(Method method) {
        return method.getName() + "(" + String.join(",", Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName).toList()) + ")";
    }
    private static String erased(String type) {
        var out = new StringBuilder(); int depth = 0;
        for (char ch : type.toCharArray()) { if (ch == '<') depth++; else if (ch == '>') depth--; else if (depth == 0) out.append(ch); }
        return out.toString().replaceAll("[A-Za-z_$][A-Za-z0-9_$]*\\.", "").replace("...", "[]").replace(" ", "");
    }
    private static Map<String, SourceMethod> source(Path path) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, files, null, List.of("-proc:none"), null, files.getJavaFileObjects(path.toFile()));
            var out = new TreeMap<String, SourceMethod>();
            for (var unit : task.parse()) new TreeScanner<Void, Void>() {
                @Override public Void visitMethod(MethodTree method, Void unused) {
                    if (!method.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.PUBLIC)) return null;
                    String key = method.getName() + "(" + String.join(",", method.getParameters().stream().map(p -> erased(p.getType().toString())).toList()) + ")";
                    var counts = new TreeSet<String>();
                    if (method.getBody() != null) new TreeScanner<Void, Void>() {
                        @Override public Void visitMethodInvocation(MethodInvocationTree call, Void ignored) {
                            String name = call.getMethodSelect().toString();
                            if ((name.equals("count") || name.equals("stockCall") || name.endsWith(".beginCall") || name.equals("beginCall"))
                                    && !call.getArguments().isEmpty() && call.getArguments().get(0) instanceof LiteralTree literal
                                    && literal.getValue() instanceof String value) counts.add(value);
                            return super.visitMethodInvocation(call, ignored);
                        }
                    }.scan(method.getBody(), null);
                    out.put(key, new SourceMethod(String.valueOf(method.getBody()), counts,
                            method.getBody() != null && method.getBody().getStatements().isEmpty())); return null;
                }
            }.scan(unit, null);
            return out;
        }
    }
    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static void validate(Map<String, Method> surface, Map<String, SourceMethod> bridge) throws Exception {
        if (!digest(String.join("\n", surface.keySet())).equals(SURFACE)) throw new AssertionError("Public controller API changed; new review required");
        var used = new TreeSet<String>();
        for (var entry : surface.entrySet()) {
            var method = entry.getValue(); var actual = PlayerControllerBridge.class.getMethod(method.getName(), method.getParameterTypes());
            var body = bridge.get(entry.getKey());
            if (actual.getDeclaringClass() == PlayerControllerBridge.class && body != null && body.counted().contains(method.getName())) continue;
            if (actual.getDeclaringClass() == PlayerControllerBridge.class || !REVIEWED.containsKey(entry.getKey()))
                throw new AssertionError("Uninstrumented public controller entry " + entry.getKey());
            used.add(entry.getKey());
        }
        if (!used.equals(REVIEWED.keySet())) throw new AssertionError("Stale inherited-method review exemptions");
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); boolean inventory = args.length > 1 && args[1].equals("--inventory");
        var bridge = source(root.resolve("forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java"));
        var ai = source(root.resolve("forge-ai/src/main/java/forge/ai/PlayerControllerAi.java"));
        var controller = source(root.resolve("forge-game/src/main/java/forge/game/player/PlayerController.java"));
        // These exact notification bodies, not naming conventions or convenient
        // fixture answers, justify RULES ownership in the bridge. Comments are
        // ignored by the AST; any executable statement requires fresh review.
        for (String key : List.of("notifyOfValue(SpellAbility,GameObject,String)",
                "autoPassCancel()", "awaitNextInput()", "cancelAwaitNextInput()",
                "revealAnte(String,Multimap)", "revealAISkipCards(String,Map)", "revealUnsupported(Map)")) {
            if (ai.get(key) == null || !ai.get(key).empty()) throw new AssertionError("Notification is no longer a no-op: " + key);
        }
        System.out.println("PASS seven RULES notification bodies are exactly empty in pinned AI source");
        // Reviewed source: original target-preparation access hook plus the
        // explicitly versioned closed-information hand observation hooks.
        // Scoped review and ClosedInformationRepairSmoke cover the added bodies;
        // the exact no-op notification AST checks above remain mandatory.
        final String aiSource = Files.readString(root.resolve("forge-ai/src/main/java/forge/ai/PlayerControllerAi.java"));
        if (!digest(aiSource).equals("a33ea1f5fd9b284baab6c2a4f58139aa7e7bda79ed27ca3a0cb7f6f16b2c6f40")
                || !digest(Files.readString(root.resolve("forge-game/src/main/java/forge/game/player/PlayerController.java"))).equals("3fa36600e368ae5285cf5b4a55f38ff566563101d76cc191b3a17e54b2eb6261"))
            throw new AssertionError("Reviewed controller source bodies changed; fresh semantic review required");
        var surface = new TreeMap<String, Method>();
        for (Class<?> type : List.of(PlayerController.class, PlayerControllerAi.class)) for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic() || method.getDeclaringClass() == Object.class) continue;
            surface.put(signature(method), method);
        }
        int counted = 0, inherited = 0, missing = 0;
        for (var entry : surface.entrySet()) {
            var method = entry.getValue(); String key = entry.getKey();
            var actual = PlayerControllerBridge.class.getMethod(method.getName(), method.getParameterTypes());
            var body = bridge.get(key);
            if (actual.getDeclaringClass() == PlayerControllerBridge.class && body != null && body.counted().contains(method.getName())) {
                counted++; System.out.println("COUNTED " + key);
            } else {
                if (actual.getDeclaringClass() != PlayerControllerBridge.class) inherited++; else missing++;
                var original = actual.getDeclaringClass() == PlayerControllerAi.class ? ai.get(key) : controller.get(key);
                System.out.println((REVIEWED.containsKey(key) ? "REVIEWED " : "UNREVIEWED ") + actual.getDeclaringClass().getSimpleName() + " " + key + " " + REVIEWED.getOrDefault(key, ""));
                if (inventory) System.out.println("BODY " + (original == null ? "SOURCE_NOT_MATCHED" : original.body().replace('\n', ' ')));
            }
        }
        System.out.println("SURFACE_SHA256 " + digest(String.join("\n", surface.keySet())));
        System.out.println("SURFACE_COUNTS public=" + surface.size() + " counted=" + counted + " inherited=" + inherited + " overrideWithoutCount=" + missing);
        validate(surface, bridge);
        var lost = new TreeMap<>(bridge); lost.put("chooseBinary(SpellAbility,String,BinaryChoiceType,Map)", new SourceMethod("", Set.of()));
        try { validate(surface, lost); throw new IllegalStateException("Mutation escaped"); }
        catch (AssertionError expected) { System.out.println("PASS removing one overload's count is detected"); }
        var lostStock = new TreeMap<>(bridge);
        lostStock.put("acceptsDrawOffer()", new SourceMethod("", Set.of()));
        try { validate(surface, lostStock); throw new IllegalStateException("Stock wrapper mutation escaped"); }
        catch (AssertionError expected) { System.out.println("PASS removing classified stock wrapper is detected"); }
        var added = new TreeMap<>(surface); added.put("futureChoice()", surface.firstEntry().getValue());
        try { validate(added, bridge); throw new IllegalStateException("Mutation escaped"); }
        catch (AssertionError expected) { System.out.println("PASS new unreviewed API signature is detected"); }
        System.out.println("PASS exact public surface accounted; counted does NOT imply classified or complete control");
    }
}
