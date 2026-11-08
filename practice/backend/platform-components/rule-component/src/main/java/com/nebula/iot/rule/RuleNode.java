package com.nebula.iot.rule;

import java.util.Map;

public record RuleNode(String id, String executor, Map<String, Object> configuration) {
    public RuleNode {
        JsonValues.identifier(id);
        JsonValues.identifier(executor);
        configuration = JsonValues.map(configuration);
    }
}
