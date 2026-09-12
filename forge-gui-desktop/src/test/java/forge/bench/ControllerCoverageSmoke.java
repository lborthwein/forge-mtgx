package forge.bench;

/** Accounting only, not certification of uninstrumented controller decisions. */
public final class ControllerCoverageSmoke {
    private static int checks;
    private static void check(boolean value) { if (!value) throw new AssertionError(); checks++; }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError("Expected explicit accounting failure");
    }
    private static int bucket(CallCounter counter, String method, String owner) {
        return counter.toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods")
                .getAsJsonObject(method).get(owner).getAsInt();
    }
    public static void main(String[] args) {
        final var counter = new CallCounter();
        check(!counter.toJson().has("controllerCoverage"));
        counter.configureControllerMode("bridge");
        final var outer = counter.beginCall("choose");
        final var inner = counter.beginCall("choose");
        counter.count("unported"); counter.instrument("choose", 99);
        inner.classify(CallCounter.Ownership.FORCED);
        check(bucket(counter, "choose", "forced") == 1 && bucket(counter, "choose", "unclassified") == 1);
        outer.classify(CallCounter.Ownership.HOST);
        check(bucket(counter, "choose", "host") == 1 && bucket(counter, "choose", "unclassified") == 0);
        check(bucket(counter, "unported", "unclassified") == 1 && counter.totalCalls() == 3);
        rejects(() -> inner.classify(CallCounter.Ownership.HOST));
        rejects(() -> counter.configureControllerMode("null"));
        final var old = counter.beginCall("reset"); counter.reset();
        final var fresh = counter.beginCall("reset");
        rejects(() -> old.classify(CallCounter.Ownership.RULES));
        check(bucket(counter, "reset", "unclassified") == 1);
        rejects(() -> fresh.classify(CallCounter.Ownership.UNCLASSIFIED));
        check(bucket(counter, "reset", "unclassified") == 1);
        fresh.classify(CallCounter.Ownership.RULES);
        check(bucket(counter, "reset", "rules") == 1 && counter.totalCalls() == 1);
        final var stock = new CallCounter(); stock.configureControllerMode("null");
        stock.beginCall("default").classify(CallCounter.Ownership.STOCK);
        check(bucket(stock, "default", "stock") == 1);
        check(stock.toJson().getAsJsonObject("controllerCoverage").get("mode").getAsString().equals("null"));
        final var probe = new CallCounter(); probe.configureControllerMode("null-probe");
        probe.count("unclassifiedProbe");
        check(bucket(probe, "unclassifiedProbe", "unclassified") == 1);
        final var composed = new CallCounter(); composed.configureControllerMode("bridge");
        var parent = composed.beginCall("rulesWrapper");
        var child = composed.beginCall("nestedChoice");
        check(!parent.classifyRulesIfChildrenAccounted());
        check(bucket(composed, "rulesWrapper", "rules") == 0 && bucket(composed, "nestedChoice", "unclassified") == 1);
        child.classify(CallCounter.Ownership.HOST);
        check(parent.classifyRulesIfChildrenAccounted());
        check(bucket(composed, "rulesWrapper", "rules") == 1 && bucket(composed, "nestedChoice", "host") == 1);
        rejects(parent::classifyRulesIfChildrenAccounted);
        var stockParent = composed.beginCall("stockWrapper");
        composed.beginCall("stockChild").classify(CallCounter.Ownership.STOCK);
        check(!stockParent.classifyRulesIfChildrenAccounted());
        check(bucket(composed, "stockWrapper", "unclassified") == 1 && bucket(composed, "stockChild", "stock") == 1);
        var staleParent = composed.beginCall("staleWrapper"); composed.reset();
        rejects(staleParent::classifyRulesIfChildrenAccounted);
        var outerRules = composed.beginCall("outerRules");
        var innerRules = composed.beginCall("innerRules");
        composed.beginCall("forcedChild").classify(CallCounter.Ownership.FORCED);
        check(innerRules.classifyRulesIfChildrenAccounted() && outerRules.classifyRulesIfChildrenAccounted());
        check(composed.totalCalls() == 3);
        System.out.println("PASS " + checks + " per-invocation ownership accounting checks; NOT complete controller coverage");
    }
}
