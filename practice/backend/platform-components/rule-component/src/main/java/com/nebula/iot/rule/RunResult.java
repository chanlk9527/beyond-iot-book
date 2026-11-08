package com.nebula.iot.rule;

import java.util.List;

/** Evaluation outcome, not a confirmation of external business execution. */
public record RunResult(List<NodeResult> nodes) {
    public RunResult { nodes = List.copyOf(nodes); }
    public boolean successful() { return nodes.stream().noneMatch(n -> n.status() == Status.FAILED); }
    public enum Status { SUCCEEDED, FILTERED, FAILED }
    public record NodeResult(String nodeId, Status status, RuleData output, String errorCode) {}
}
