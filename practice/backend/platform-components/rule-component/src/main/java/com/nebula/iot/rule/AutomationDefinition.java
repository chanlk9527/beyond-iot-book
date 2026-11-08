package com.nebula.iot.rule;

import java.util.*;

/** Pure configuration contracts; management, permissions and resource resolution belong to managers. */
public final class AutomationDefinition {
    private AutomationDefinition() {}
    public record Flow(String id, long version, SourceScope scope, RuleCondition condition,
                       List<FieldMapping> mappings, List<String> connectionIds) {
        public Flow {
            identity(id, version, scope);
            mappings = FieldMapping.validate(mappings);
            connectionIds = List.copyOf(connectionIds);
            if (connectionIds.isEmpty() || connectionIds.size() > 128) throw new IllegalArgumentException("1..128 targets required");
            var unique = new HashSet<String>();
            for (var connection : connectionIds) {
                JsonValues.identifier(connection);
                if (!unique.add(connection)) throw new IllegalArgumentException("Duplicate connection");
            }
        }
    }
    public record Scene(String id, long version, SourceScope scope, RuleCondition condition, List<Action> actions) {
        public Scene {
            identity(id, version, scope);
            actions = List.copyOf(actions);
            if (actions.isEmpty() || actions.size() > 128) throw new IllegalArgumentException("1..128 actions required");
            var unique = new HashSet<String>();
            for (var action : actions) if (!unique.add(action.id())) throw new IllegalArgumentException("Duplicate action id");
        }
    }
    public record Action(String id, String kind, String target, Map<String, Object> parameters) {
        public Action {
            JsonValues.identifier(id); JsonValues.identifier(target);
            if (!Set.of("device", "notification", "connection").contains(Objects.requireNonNull(kind)))
                throw new IllegalArgumentException("Unsupported action kind");
            parameters = JsonValues.map(parameters);
        }
        Map<String, Object> configuration() { return Map.of("id", id, "kind", kind, "target", target, "parameters", parameters); }
    }
    private static void identity(String id, long version, SourceScope scope) {
        JsonValues.identifier(id);
        if (version < 1) throw new IllegalArgumentException("Version must be positive");
        Objects.requireNonNull(scope, "scope");
    }
}
