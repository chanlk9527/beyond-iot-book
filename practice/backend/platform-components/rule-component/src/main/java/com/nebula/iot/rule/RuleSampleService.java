package com.nebula.iot.rule;

import java.util.*;

/** In-process sample API. Uses only pure built-ins, never production or caller-provided executors. */
public final class RuleSampleService {
    private final LocalRuleEngine engine = new LocalRuleEngine(BuiltinExecutors.providers());
    public enum Status { OUT_OF_SCOPE, FILTERED, MATCHED, FAILED }
    public record PreviewAction(String id, String kind, String target, Map<String, Object> parameters, RuleData payload) {
        public PreviewAction { parameters = JsonValues.map(parameters); Objects.requireNonNull(payload); }
    }
    public record Result(Status status, RunResult run, List<PreviewAction> actions) {
        public Result { actions = List.copyOf(actions); }
    }
    public Result test(AutomationCompiler.Compiled compiled, SourceScope.EventSource source,
                       String runId, String eventId, RuleData sample) {
        Objects.requireNonNull(compiled); Objects.requireNonNull(source); Objects.requireNonNull(sample);
        JsonValues.identifier(runId); JsonValues.identifier(eventId);
        engine.validate(compiled.model());
        if (!compiled.scope().matches(source)) return new Result(Status.OUT_OF_SCOPE, null, List.of());
        var run = engine.execute(compiled.model(), source.tenantId(), runId, eventId, sample);
        var actionNodes = new HashMap<String, RuleNode>();
        for (var node : compiled.model().nodes()) if (node.executor().equals("action-preview")) actionNodes.put(node.id(), node);
        var actions = new ArrayList<PreviewAction>();
        for (var node : run.nodes()) if (actionNodes.containsKey(node.nodeId()) && node.status() == RunResult.Status.SUCCEEDED) {
            var values = actionNodes.get(node.nodeId()).configuration();
            actions.add(new PreviewAction((String) values.get("id"), (String) values.get("kind"), (String) values.get("target"),
                    ConditionConfiguration.object(values.get("parameters")), node.output()));
        }
        return new Result(!run.successful() ? Status.FAILED : actions.isEmpty() ? Status.FILTERED : Status.MATCHED, run, actions);
    }
}
