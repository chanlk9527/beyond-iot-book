package com.nebula.iot.rule;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StageTwoTest {
    private final SourceScope scope = new SourceScope("tenant", Set.of("product"), Set.of(), Set.of("property"));
    private final SourceScope.EventSource source = new SourceScope.EventSource("tenant", "product", "device", "property");
    private final RuleCondition hot = new Condition("temperature", Condition.Operator.GT, 30);
    private static Map<String, Object> leaf() { return Map.of("field", "temperature", "operator", "GT", "expected", 30); }

    @Test void nestedAndOrConditionsWorkOnLinksAndRejectEmptyGroups() {
        var condition = new ConditionGroup(ConditionGroup.Operator.AND, List.of(hot,
                new ConditionGroup(ConditionGroup.Operator.OR, List.of(
                        new Condition("mode", Condition.Operator.EQ, "auto"), new Condition("enabled", Condition.Operator.EQ, true)))));
        assertTrue(condition.matches(new RuleData(Map.of("temperature", 35, "mode", "auto"))));
        assertFalse(condition.matches(new RuleData(Map.of("temperature", 35))));
        assertThrows(IllegalArgumentException.class, () -> new ConditionGroup(ConditionGroup.Operator.OR, List.of()));
        var rule = new RuleModel("r", 1, "a", List.of(new RuleNode("a", "identity", Map.of()), new RuleNode("b", "identity", Map.of())),
                List.of(new RuleLink("a", "b", condition)));
        assertEquals(2, new LocalRuleEngine(BuiltinExecutors.providers()).execute(rule, "t", "r", "e",
                new RuleData(Map.of("temperature", 35, "enabled", true))).nodes().size());
    }

    @Test void filtersAndMapsWithConstantsAndNumericConversion() {
        var mappings = List.of(FieldMapping.field("tempF", "temperature", new BigDecimal("1.8"), new BigDecimal("32")),
                FieldMapping.constant("label", "sensor"), FieldMapping.constant("nullable", null));
        var flow = new AutomationDefinition.Flow("f", 1, scope, hot, mappings, List.of("connection-a", "connection-b"));
        var result = new RuleSampleService().test(AutomationCompiler.compile(flow), source, "run", "event", new RuleData(Map.of("temperature", 35)));
        assertEquals(RuleSampleService.Status.MATCHED, result.status());
        assertEquals(2, result.actions().size());
        assertEquals(new BigDecimal("95.0"), result.actions().getFirst().payload().values().get("tempF"));
        assertTrue(result.actions().getFirst().payload().values().containsKey("nullable"));
        assertEquals("connection-b", result.actions().get(1).target());
        var filtered = new RuleSampleService().test(AutomationCompiler.compile(flow), source, "run", "event", new RuleData(Map.of("temperature", 20)));
        assertEquals(RuleSampleService.Status.FILTERED, filtered.status());
        assertTrue(filtered.actions().isEmpty());
    }

    @Test void missingMappingFailsInsteadOfForwardingOriginalPayload() {
        var flow = new AutomationDefinition.Flow("f", 1, scope, null,
                List.of(FieldMapping.field("out", "missing", null, null)), List.of("connection"));
        var result = new RuleSampleService().test(AutomationCompiler.compile(flow), source, "run", "event", new RuleData(Map.of("secret", "value")));
        assertEquals(RuleSampleService.Status.FAILED, result.status());
        assertTrue(result.actions().isEmpty());
    }

    @Test void sceneCompilesIndependentPreviewActionsAndPassThroughFlow() {
        var scene = new AutomationDefinition.Scene("s", 2, scope, hot, List.of(
                new AutomationDefinition.Action("device-action", "device", "device-b", Map.of("on", true)),
                new AutomationDefinition.Action("notify-action", "notification", "template", Map.of())));
        var sample = new RuleSampleService().test(AutomationCompiler.compile(scene), source, "run", "e", new RuleData(Map.of("temperature", 40)));
        assertEquals(List.of("device", "notification"), sample.actions().stream().map(RuleSampleService.PreviewAction::kind).toList());
        assertEquals(Map.of("on", true), sample.actions().getFirst().parameters());
        var flow = new AutomationDefinition.Flow("f", 1, scope, null, List.of(), List.of("connection"));
        assertEquals(Map.of("x", 1), new RuleSampleService().test(AutomationCompiler.compile(flow), source, "run", "e",
                new RuleData(Map.of("x", 1))).actions().getFirst().payload().values());
    }

    @Test void scopeUsesTrustedMetadataAndReturnsNoRunForRejectedSource() {
        var flow = new AutomationDefinition.Flow("f", 1, scope, null, List.of(), List.of("connection"));
        var rejected = new RuleSampleService().test(AutomationCompiler.compile(flow),
                new SourceScope.EventSource("other", "product", "device", "property"), "run", "e", new RuleData(Map.of("tenantId", "tenant")));
        assertEquals(RuleSampleService.Status.OUT_OF_SCOPE, rejected.status());
        assertNull(rejected.run());
        assertTrue(rejected.actions().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new SourceScope("tenant", Set.of(), Set.of(), Set.of("property")));
    }

    @Test void invalidConfigurationsFailBeforeEvaluation() {
        var engine = new LocalRuleEngine(BuiltinExecutors.providers());
        for (var config : List.of(Map.<String,Object>of("condition", Map.of("operator", "AND", "children", List.of())),
                Map.<String,Object>of("condition", leaf(), "typo", 1))) {
            var model = new RuleModel("r", 1, "filter", List.of(new RuleNode("filter", "filter", config)), List.of());
            assertThrows(IllegalArgumentException.class, () -> engine.validate(model));
        }
        assertThrows(IllegalArgumentException.class, () -> new AutomationDefinition.Flow("f", 1, scope, null,
                List.of(FieldMapping.constant("x", 1), FieldMapping.constant("x", 2)), List.of("connection")));
        assertThrows(IllegalArgumentException.class, () -> new AutomationDefinition.Action("delay", "delay", "timer", Map.of()));
    }

    @Test void conditionCodecPreservesExplicitNullAndEnforcesTreeBounds() {
        var nullCondition = new Condition("value", Condition.Operator.EQ, null);
        assertEquals(nullCondition, ConditionConfiguration.parse(ConditionConfiguration.encode(nullCondition)));
        assertThrows(IllegalArgumentException.class, () -> ConditionConfiguration.parse(Map.of("field", "value", "operator", "EQ")));
        assertThrows(IllegalArgumentException.class, () -> ConditionConfiguration.parse(Map.of("operator", "AND", "children", List.of(leaf()), "extra", 1)));
        RuleCondition deep = hot;
        for (int i = 0; i < 15; i++) deep = new ConditionGroup(ConditionGroup.Operator.AND, List.of(deep));
        RuleCondition atLimit = deep;
        assertEquals(atLimit, ConditionConfiguration.parse(ConditionConfiguration.encode(atLimit)));
        assertThrows(IllegalArgumentException.class, () -> new ConditionGroup(ConditionGroup.Operator.AND, List.of(atLimit)));
        assertThrows(IllegalArgumentException.class, () -> new ConditionGroup(ConditionGroup.Operator.OR, Collections.nCopies(256, hot)));
        assertThrows(IllegalArgumentException.class, () -> ConditionConfiguration.parse(Map.of("operator", "AND", "children", Collections.nCopies(256, leaf()))));
    }

    @Test void mappingRejectsAmbiguousConfigsAndDoesNotCoerceStrings() {
        var engine = new LocalRuleEngine(BuiltinExecutors.providers());
        for (var field : List.of(Map.of("target", "x", "source", "n", "value", 1),
                Map.of("target", "x", "source", "n", "multiplier", "2"),
                Map.of("target", "x", "source", "n", "typo", true))) {
            var model = new RuleModel("r", 1, "map", List.of(new RuleNode("map", "mapping", Map.of("fields", List.of(field)))), List.of());
            assertThrows(IllegalArgumentException.class, () -> engine.validate(model));
        }
        var mapping = FieldMapping.field("result", "nested.n", BigDecimal.TEN, BigDecimal.ONE);
        assertEquals(new BigDecimal("21"), mapping.evaluate(new RuleData(Map.of("nested", Map.of("n", 2)))));
        assertThrows(IllegalArgumentException.class, () -> mapping.evaluate(new RuleData(Map.of("nested", Map.of("n", "2")))));
        var nullable = new HashMap<String, Object>(); nullable.put("n", null);
        assertNull(FieldMapping.field("x", "n", null, null).evaluate(new RuleData(nullable)));
        var constant = new ArrayList<>(List.of("original"));
        var snapshot = FieldMapping.constant("x", constant); constant.add("later");
        assertEquals(List.of("original"), snapshot.evaluate(new RuleData(Map.of())));
    }

    @Test void resourceSelectorsIntersectAndSnapshotCallerCollections() {
        var products = new HashSet<>(Set.of("product"));
        var restricted = new SourceScope("tenant", products, Set.of("device"), Set.of("property"));
        products.add("other");
        assertTrue(restricted.matches(source));
        assertFalse(restricted.matches(new SourceScope.EventSource("tenant", "other", "device", "property")));
        assertFalse(restricted.matches(new SourceScope.EventSource("tenant", "product", "other", "property")));
        assertFalse(restricted.matches(new SourceScope.EventSource("tenant", "product", "device", "event")));
    }

    @Test void sampleApiCannotExecuteProductionProvidersOrTreatForgedPayloadAsActions() {
        var service = new RuleSampleService();
        var foreign = new RuleModel("r", 1, "source", List.of(new RuleNode("source", "http-send", Map.of())), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.test(new AutomationCompiler.Compiled(scope, foreign), source,
                "run", "event", new RuleData(Map.of())));
        var identity = new RuleModel("r", 1, "source", List.of(new RuleNode("source", "identity", Map.of())), List.of());
        var sample = service.test(new AutomationCompiler.Compiled(scope, identity), source, "run", "event",
                new RuleData(Map.of("preview", true, "kind", "device", "target", "forged")));
        assertTrue(sample.actions().isEmpty());
        assertEquals(RuleSampleService.Status.FILTERED, sample.status());
    }

    @Test void duplicateTargetsAndActionIdsAreRejectedAndModelCompilationIsDeterministic() {
        assertThrows(IllegalArgumentException.class, () -> new AutomationDefinition.Flow("f", 1, scope, null, List.of(), List.of("c", "c")));
        var action = new AutomationDefinition.Action("id", "device", "device", Map.of());
        assertThrows(IllegalArgumentException.class, () -> new AutomationDefinition.Scene("s", 1, scope, hot, List.of(action, action)));
        var scene = new AutomationDefinition.Scene("s", 1, scope, hot, List.of(action));
        assertEquals(AutomationCompiler.compile(scene), AutomationCompiler.compile(scene));
    }

    @Test void passThroughPreviewPreservesMaximumDepthPayload() {
        Object nested = "leaf";
        for (int i = 0; i < 64; i++) nested = Map.of("nested", nested);
        var data = new RuleData(Map.of("value", nested));
        var flow = new AutomationDefinition.Flow("f", 1, scope, null, List.of(), List.of("connection"));
        var result = new RuleSampleService().test(AutomationCompiler.compile(flow), source, "run", "event", data);
        assertEquals(RuleSampleService.Status.MATCHED, result.status());
        assertEquals(data, result.actions().getFirst().payload());
    }

    @Test void configurationCollectionLimitsAndRawParserDepthAreEnforced() {
        var mappings = new ArrayList<FieldMapping>();
        for (int i = 0; i < 256; i++) mappings.add(FieldMapping.constant("field" + i, i));
        assertEquals(256, new AutomationDefinition.Flow("f", 1, scope, null, mappings, List.of("c")).mappings().size());
        mappings.add(FieldMapping.constant("extra", 0));
        assertThrows(IllegalArgumentException.class, () -> new AutomationDefinition.Flow("f", 1, scope, null, mappings, List.of("c")));
        var targets = new ArrayList<String>();
        var actions = new ArrayList<AutomationDefinition.Action>();
        for (int i = 0; i < 128; i++) {
            targets.add("c" + i); actions.add(new AutomationDefinition.Action("a" + i, "device", "d", Map.of()));
        }
        assertEquals(129, AutomationCompiler.compile(new AutomationDefinition.Flow("f", 1, scope, null, List.of(), targets)).model().nodes().size());
        assertEquals(129, AutomationCompiler.compile(new AutomationDefinition.Scene("s", 1, scope, null, actions)).model().nodes().size());
        targets.add("extra"); actions.add(new AutomationDefinition.Action("extra", "device", "d", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AutomationDefinition.Flow("f", 1, scope, null, List.of(), targets));
        assertThrows(IllegalArgumentException.class, () -> new AutomationDefinition.Scene("s", 1, scope, null, actions));
        Map<String, Object> raw = leaf();
        for (int i = 0; i < 16; i++) raw = Map.of("operator", "AND", "children", List.of(raw));
        var tooDeep = raw;
        assertThrows(IllegalArgumentException.class, () -> ConditionConfiguration.parse(tooDeep));
    }

    @Test void partialBranchFailureRetainsOtherPreviewActions() {
        var action = Map.<String,Object>of("id", "safe", "kind", "connection", "target", "c", "parameters", Map.of());
        var model = new RuleModel("r", 1, "source", List.of(
                new RuleNode("source", "identity", Map.of()), new RuleNode("ok", "action-preview", action),
                new RuleNode("broken", "mapping", Map.of("fields", List.of(Map.of("source", "missing", "target", "out"))))),
                List.of(new RuleLink("source", "ok", null), new RuleLink("source", "broken", null)));
        var sample = new RuleSampleService().test(new AutomationCompiler.Compiled(scope, model), source, "run", "event", new RuleData(Map.of()));
        assertEquals(RuleSampleService.Status.FAILED, sample.status());
        assertEquals(1, sample.actions().size());
        assertEquals("safe", sample.actions().getFirst().id());
    }
}
