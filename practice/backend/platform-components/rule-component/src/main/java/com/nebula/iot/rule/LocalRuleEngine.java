package com.nebula.iot.rule;

import java.util.*;

/** Bounded, deterministic, single-input graph evaluator. No persistence, scheduling or IO isolation. */
public final class LocalRuleEngine {
    private final Map<String, TaskExecutorProvider> providers;

    public LocalRuleEngine(Collection<TaskExecutorProvider> providers) {
        var registry = new LinkedHashMap<String, TaskExecutorProvider>();
        for (var provider : providers) {
            String type = JsonValues.identifier(provider.type());
            if (registry.putIfAbsent(type, provider) != null)
                throw new IllegalArgumentException("Duplicate executor type: " + type);
        }
        this.providers = Map.copyOf(registry);
    }

    public void validate(RuleModel model) { prepare(model); }

    private Graph prepare(RuleModel model) {
        var nodes = new LinkedHashMap<String, RuleNode>();
        var outgoing = new LinkedHashMap<String, List<RuleLink>>();
        var incoming = new HashMap<String, Integer>();
        for (var node : model.nodes()) {
            if (nodes.putIfAbsent(node.id(), node) != null)
                throw new IllegalArgumentException("Duplicate node: " + node.id());
            if (!providers.containsKey(node.executor()))
                throw new IllegalArgumentException("Unknown executor: " + node.executor());
            outgoing.put(node.id(), new ArrayList<>());
        }
        if (!nodes.containsKey(model.entryNodeId())) throw new IllegalArgumentException("Entry node does not exist");
        for (var link : model.links()) {
            if (!nodes.containsKey(link.source()) || !nodes.containsKey(link.target()))
                throw new IllegalArgumentException("Link references missing node");
            if (incoming.merge(link.target(), 1, Integer::sum) > 1)
                throw new IllegalArgumentException("Duplicate edges and merge nodes are not supported");
            outgoing.get(link.source()).add(link);
        }
        if (incoming.containsKey(model.entryNodeId())) throw new IllegalArgumentException("Entry must have no incoming link");
        var visited = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(model.entryNodeId());
        while (!queue.isEmpty()) {
            String id = queue.remove();
            if (!visited.add(id)) throw new IllegalArgumentException("Cycle detected");
            for (var link : outgoing.get(id)) queue.add(link.target());
        }
        if (visited.size() != nodes.size()) throw new IllegalArgumentException("Unreachable nodes or disconnected cycle");
        for (var node : nodes.values()) providers.get(node.executor()).validate(node.configuration());
        return new Graph(nodes, outgoing);
    }

    public RunResult execute(RuleModel model, String tenantId, String runId, String eventId, RuleData input) {
        Objects.requireNonNull(input, "input");
        // Validate identity and the entire graph before creating or invoking any executor.
        new ExecutionContext(tenantId, runId, eventId, model.id(), model.version(), model.entryNodeId());
        var graph = prepare(model);
        var executors = new HashMap<String, TaskExecutor>();
        for (var node : graph.nodes().values()) {
            executors.put(node.id(), Objects.requireNonNull(
                    providers.get(node.executor()).create(node.configuration()), "Provider returned null executor"));
        }
        var queue = new ArrayDeque<Work>();
        var results = new ArrayList<RunResult.NodeResult>();
        queue.add(new Work(model.entryNodeId(), input));
        while (!queue.isEmpty()) {
            var work = queue.remove();
            var context = new ExecutionContext(tenantId, runId, eventId, model.id(), model.version(), work.nodeId());
            Optional<RuleData> output;
            try {
                output = Objects.requireNonNull(executors.get(work.nodeId()).execute(context, work.data()));
            } catch (RuntimeException failure) {
                results.add(new RunResult.NodeResult(work.nodeId(), RunResult.Status.FAILED, null, "EXECUTOR_FAILED"));
                continue;
            }
            if (output.isEmpty()) {
                results.add(new RunResult.NodeResult(work.nodeId(), RunResult.Status.FILTERED, null, null));
                continue;
            }
            var data = output.get();
            results.add(new RunResult.NodeResult(work.nodeId(), RunResult.Status.SUCCEEDED, data, null));
            for (var link : graph.outgoing().get(work.nodeId())) {
                if (link.condition() == null || link.condition().matches(data)) queue.add(new Work(link.target(), data));
            }
        }
        return new RunResult(results);
    }

    private record Graph(Map<String, RuleNode> nodes, Map<String, List<RuleLink>> outgoing) {}
    private record Work(String nodeId, RuleData data) {}
}
