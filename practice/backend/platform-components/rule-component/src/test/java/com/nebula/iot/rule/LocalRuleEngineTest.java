package com.nebula.iot.rule;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocalRuleEngineTest {
    private static RuleNode node(String id) { return new RuleNode(id, "copy", Map.of()); }
    private static RuleLink link(String from, String to) { return new RuleLink(from, to, null); }
    private static RuleModel model(List<RuleNode> nodes, RuleLink... links) {
        return new RuleModel("rule-1", 3, "source", nodes, List.of(links));
    }
    private static TaskExecutorProvider provider(String type, TaskExecutor executor) {
        return new TaskExecutorProvider() {
            public String type() { return type; }
            public void validate(Map<String, Object> config) {
                if (!config.isEmpty()) throw new IllegalArgumentException("No configuration supported");
            }
            public TaskExecutor create(Map<String, Object> config) { return executor; }
        };
    }
    private static LocalRuleEngine engine() {
        return new LocalRuleEngine(List.of(provider("copy", (context, input) -> Optional.of(input))));
    }

    @Test void routesMatchingBranchesAndBindsContext() {
        var contexts = new ArrayList<ExecutionContext>();
        var engine = new LocalRuleEngine(List.of(provider("copy", (context, data) -> {
            contexts.add(context); return Optional.of(data);
        })));
        var rule = model(List.of(node("source"), node("hot"), node("cold")),
                new RuleLink("source", "hot", new Condition("temperature", Condition.Operator.GT, 30)),
                new RuleLink("source", "cold", new Condition("temperature", Condition.Operator.LT, 10)));
        var result = engine.execute(rule, "tenant-1", "run-1", "event-1", new RuleData(Map.of("temperature", 35)));
        assertEquals(List.of("source", "hot"), result.nodes().stream().map(RunResult.NodeResult::nodeId).toList());
        assertEquals(new ExecutionContext("tenant-1", "run-1", "event-1", "rule-1", 3, "hot"), contexts.get(1));
        assertTrue(result.successful());
    }

    @Test void rejectsInvalidGraphsBeforeExecuting() {
        var calls = new AtomicInteger();
        var engine = new LocalRuleEngine(List.of(provider("copy", (c, d) -> { calls.incrementAndGet(); return Optional.of(d); })));
        var invalid = List.of(
                model(List.of(node("source"), node("source"))),
                model(List.of(node("source")), link("source", "missing")),
                model(List.of(node("source"), node("orphan"))),
                model(List.of(node("source"), node("a")), link("source", "a"), link("a", "source")),
                model(List.of(node("source"), node("a")), link("source", "a"), link("source", "a")),
                model(List.of(node("source"), node("a"), node("b")), link("source", "a"), link("source", "b"), link("a", "b")),
                model(List.of(new RuleNode("source", "unknown", Map.of()))),
                model(List.of(new RuleNode("source", "copy", Map.of("bad", true)))));
        for (var rule : invalid) assertThrows(IllegalArgumentException.class,
                () -> engine.execute(rule, "t", "r", "e", new RuleData(Map.of())));
        assertEquals(0, calls.get());
    }

    @Test void isolatesFailuresAndFilteredBranches() {
        var engine = new LocalRuleEngine(List.of(
                provider("copy", (c, d) -> Optional.of(d)),
                provider("fail", (c, d) -> { throw new IllegalStateException("secret-token"); }),
                provider("filter", (c, d) -> Optional.empty())));
        var rule = model(List.of(node("source"), new RuleNode("failed", "fail", Map.of()),
                new RuleNode("filtered", "filter", Map.of()), node("ok"), node("afterFail"), node("afterFilter")),
                link("source", "failed"), link("source", "filtered"), link("source", "ok"),
                link("failed", "afterFail"), link("filtered", "afterFilter"));
        var result = engine.execute(rule, "t", "r", "e", new RuleData(Map.of()));
        assertEquals(List.of(RunResult.Status.SUCCEEDED, RunResult.Status.FAILED, RunResult.Status.FILTERED, RunResult.Status.SUCCEEDED),
                result.nodes().stream().map(RunResult.NodeResult::status).toList());
        assertFalse(result.successful());
        assertFalse(result.toString().contains("secret-token"));
    }

    @Test void snapshotsNestedDataAndConfiguration() {
        var items = new ArrayList<>(List.of("original"));
        var nested = new HashMap<String, Object>(); nested.put("items", items); nested.put("nullable", null);
        var data = new RuleData(Map.of("nested", nested));
        var node = new RuleNode("source", "copy", Map.of("nested", nested));
        items.add("later"); nested.put("new", true);
        assertEquals(List.of("original"), ((Map<?, ?>) data.values().get("nested")).get("items"));
        assertFalse(((Map<?, ?>) node.configuration().get("nested")).containsKey("new"));
        assertThrows(UnsupportedOperationException.class, () -> data.values().put("x", 1));
        assertThrows(IllegalArgumentException.class, () -> new RuleData(Map.of("object", new Object())));
    }

    @Test void comparesNumbersWithoutCoercingStringsAndMissingValues() {
        var data = new RuleData(Map.of("nested", Map.of("value", 30.0), "text", "30"));
        assertTrue(new Condition("nested.value", Condition.Operator.EQ, 30).matches(data));
        assertFalse(new Condition("text", Condition.Operator.GT, 20).matches(data));
        assertFalse(new Condition("missing", Condition.Operator.NE, 20).matches(data));
        assertFalse(new Condition("nested.value", Condition.Operator.LT, 20).matches(data));
        assertThrows(IllegalArgumentException.class, () -> new Condition("a..b", Condition.Operator.EQ, 1));
    }

    @Test void rejectsDuplicateProvidersAndInvalidIdentifiers() {
        var p = provider("copy", (c, d) -> Optional.of(d));
        assertThrows(IllegalArgumentException.class, () -> new LocalRuleEngine(List.of(p, p)));
        assertThrows(IllegalArgumentException.class, () -> new RuleModel("r", 0, "source", List.of(node("source")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> engine().execute(model(List.of(node("source"))), "", "r", "e", new RuleData(Map.of())));
    }

    @Test void createsExecutorsPerNodeAndRunWithoutSharingState() {
        var created = new AtomicInteger();
        var provider = new TaskExecutorProvider() {
            public String type() { return "counter"; }
            public void validate(Map<String, Object> config) {}
            public TaskExecutor create(Map<String, Object> config) {
                created.incrementAndGet();
                var counter = new AtomicInteger();
                return (c, d) -> Optional.of(new RuleData(Map.of("count", counter.incrementAndGet())));
            }
        };
        var engine = new LocalRuleEngine(List.of(provider));
        var rule = model(List.of(new RuleNode("source", "counter", Map.of()), new RuleNode("child", "counter", Map.of())), link("source", "child"));
        for (int i = 0; i < 2; i++) {
            var result = engine.execute(rule, "t", "r" + i, "e", new RuleData(Map.of()));
            assertEquals(List.of(1, 1), result.nodes().stream().map(n -> n.output().values().get("count")).toList());
        }
        assertEquals(4, created.get());
    }

    @Test void validatesEveryConfigurationBeforeCreatingAnyExecutor() {
        var created = new AtomicInteger();
        var invoked = new AtomicInteger();
        var provider = new TaskExecutorProvider() {
            public String type() { return "copy"; }
            public void validate(Map<String, Object> config) {
                if (!config.isEmpty()) throw new IllegalArgumentException("bad config");
            }
            public TaskExecutor create(Map<String, Object> config) {
                created.incrementAndGet();
                return (c, d) -> { invoked.incrementAndGet(); return Optional.of(d); };
            }
        };
        var engine = new LocalRuleEngine(List.of(provider));
        var valid = model(List.of(node("source")));
        engine.validate(valid);
        assertEquals(0, created.get());
        var invalid = model(List.of(node("source"), new RuleNode("bad", "copy", Map.of("bad", true))), link("source", "bad"));
        assertThrows(IllegalArgumentException.class, () -> engine.execute(invalid, "t", "r", "e", new RuleData(Map.of())));
        assertEquals(0, created.get());
        assertEquals(0, invoked.get());
    }

    @Test void routesTransformedOutputAndProtectsSiblingData() {
        var engine = new LocalRuleEngine(List.of(
                provider("copy", (c, d) -> Optional.of(d)),
                provider("transform", (c, d) -> Optional.of(new RuleData(Map.of("value", 42)))),
                provider("mutate", (c, d) -> { d.values().put("value", 0); return Optional.of(d); })));
        var rule = model(List.of(new RuleNode("source", "transform", Map.of()), new RuleNode("mutator", "mutate", Map.of()), node("reader")),
                link("source", "mutator"), new RuleLink("source", "reader", new Condition("value", Condition.Operator.EQ, 42)));
        var result = engine.execute(rule, "t", "r", "e", new RuleData(Map.of("value", 1)));
        assertEquals(RunResult.Status.FAILED, result.nodes().get(1).status());
        assertEquals(42, result.nodes().get(2).output().values().get("value"));
    }

    @Test void handlesNullAndRejectsNonJsonOrExcessivelyNestedValues() {
        var values = new HashMap<String, Object>(); values.put("value", null);
        var data = new RuleData(values);
        assertTrue(new Condition("value", Condition.Operator.EQ, null).matches(data));
        assertFalse(new Condition("missing", Condition.Operator.EQ, null).matches(data));
        assertTrue(new Condition("value", Condition.Operator.NE, 1).matches(data));
        assertTrue(new Condition("n", Condition.Operator.GE, 2).matches(new RuleData(Map.of("n", 2.0))));
        assertTrue(new Condition("n", Condition.Operator.LE, 2).matches(new RuleData(Map.of("n", 2.0))));
        assertThrows(IllegalArgumentException.class, () -> new RuleData(Map.of("n", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> new RuleData(Map.of("n", Double.POSITIVE_INFINITY)));
        var cycle = new HashMap<String, Object>(); cycle.put("self", cycle);
        assertThrows(IllegalArgumentException.class, () -> new RuleData(cycle));
    }

    @Test void initializationFailureAbortsBeforeAnyNodeRunsEvenForUnmatchedBranch() {
        var invoked = new AtomicInteger();
        for (boolean returnsNull : List.of(false, true)) {
            var factory = new TaskExecutorProvider() {
                public String type() { return "broken"; }
                public void validate(Map<String, Object> config) {}
                public TaskExecutor create(Map<String, Object> config) {
                    if (returnsNull) return null;
                    throw new IllegalStateException("Initialization failed");
                }
            };
            var engine = new LocalRuleEngine(List.of(factory, provider("copy", (c, d) -> {
                invoked.incrementAndGet(); return Optional.of(d);
            })));
            var rule = model(List.of(node("source"), new RuleNode("broken", "broken", Map.of())),
                    new RuleLink("source", "broken", new Condition("missing", Condition.Operator.EQ, 1)));
            assertThrows(RuntimeException.class, () -> engine.execute(rule, "t", "r", "e", new RuleData(Map.of())));
        }
        assertEquals(0, invoked.get());
    }
}
