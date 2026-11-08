package com.nebula.iot.rule;

import java.util.List;

/** Published-version-shaped model; persistence and publication belong to rule-manager. */
public record RuleModel(String id, long version, String entryNodeId, List<RuleNode> nodes, List<RuleLink> links) {
    public RuleModel {
        JsonValues.identifier(id);
        JsonValues.identifier(entryNodeId);
        if (version < 1) throw new IllegalArgumentException("Version must be positive");
        nodes = List.copyOf(nodes);
        links = List.copyOf(links);
        if (nodes.isEmpty() || nodes.size() > 256 || links.size() > 255)
            throw new IllegalArgumentException("Graph must have 1..256 nodes and at most 255 links");
    }
}
