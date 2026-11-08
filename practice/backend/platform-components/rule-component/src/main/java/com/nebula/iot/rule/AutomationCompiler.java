package com.nebula.iot.rule;

import java.util.*;

/** Converts supported business configurations to preview graphs; retains source scope separately. */
public final class AutomationCompiler {
    private AutomationCompiler() {}
    public record Compiled(SourceScope scope, RuleModel model) {
        public Compiled { Objects.requireNonNull(scope); Objects.requireNonNull(model); }
    }
    public static Compiled compile(AutomationDefinition.Flow flow) {
        var actions = flow.connectionIds().stream().map(id -> new AutomationDefinition.Action(id, "connection", id, Map.of())).toList();
        return compile(flow.id(), flow.version(), flow.scope(), flow.condition(), flow.mappings(), actions);
    }
    public static Compiled compile(AutomationDefinition.Scene scene) {
        return compile(scene.id(), scene.version(), scene.scope(), scene.condition(), List.of(), scene.actions());
    }
    private static Compiled compile(String id, long version, SourceScope scope, RuleCondition condition,
                                    List<FieldMapping> mappings, List<AutomationDefinition.Action> actions) {
        var nodes = new ArrayList<RuleNode>();
        var links = new ArrayList<RuleLink>();
        String tail = "source";
        nodes.add(new RuleNode(tail, "identity", Map.of()));
        if (condition != null) {
            nodes.add(new RuleNode("filter", "filter", Map.of("condition", ConditionConfiguration.encode(condition))));
            links.add(new RuleLink(tail, "filter", null)); tail = "filter";
        }
        if (!mappings.isEmpty()) {
            nodes.add(new RuleNode("mapping", "mapping", Map.of("fields", mappings.stream().map(FieldMapping::configuration).toList())));
            links.add(new RuleLink(tail, "mapping", null)); tail = "mapping";
        }
        for (int i = 0; i < actions.size(); i++) {
            String nodeId = "action-" + i;
            nodes.add(new RuleNode(nodeId, "action-preview", actions.get(i).configuration()));
            links.add(new RuleLink(tail, nodeId, null));
        }
        var model = new RuleModel(id, version, "source", nodes, links);
        new LocalRuleEngine(BuiltinExecutors.providers()).validate(model);
        return new Compiled(scope, model);
    }
}
