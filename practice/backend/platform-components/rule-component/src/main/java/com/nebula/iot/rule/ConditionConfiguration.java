package com.nebula.iot.rule;

import java.util.*;

/** Strict JSON-shaped condition codec shared by filter nodes and business compilers. */
public final class ConditionConfiguration {
    private ConditionConfiguration() {}
    public static RuleCondition parse(Map<String, Object> config) { return parse(config, 1, new int[]{0}); }
    private static RuleCondition parse(Map<String, Object> config, int depth, int[] count) {
        if (depth > 16 || ++count[0] > 256) throw new IllegalArgumentException("Condition tree limit exceeded");
        String operator = text(config, "operator");
        if (operator.equals("AND") || operator.equals("OR")) {
            keys(config, Set.of("operator", "children"));
            Object raw = config.get("children");
            if (!(raw instanceof List<?> children)) throw new IllegalArgumentException("children must be an array");
            var conditions = new ArrayList<RuleCondition>();
            for (var child : children) conditions.add(parse(object(child), depth + 1, count));
            return new ConditionGroup(ConditionGroup.Operator.valueOf(operator), conditions);
        }
        keys(config, Set.of("field", "operator", "expected"));
        if (!config.containsKey("expected")) throw new IllegalArgumentException("expected is required, use explicit null if intended");
        return new Condition(text(config, "field"), Condition.Operator.valueOf(operator), config.get("expected"));
    }
    public static Map<String, Object> encode(RuleCondition condition) {
        if (condition instanceof ConditionGroup group) return Map.of("operator", group.operator().name(),
                "children", group.children().stream().map(ConditionConfiguration::encode).toList());
        var leaf = (Condition) Objects.requireNonNull(condition);
        var config = new LinkedHashMap<String, Object>();
        config.put("field", leaf.field()); config.put("operator", leaf.operator().name()); config.put("expected", leaf.expected());
        return JsonValues.map(config);
    }
    static void keys(Map<String, Object> config, Set<String> allowed) {
        if (!allowed.containsAll(config.keySet())) throw new IllegalArgumentException("Unknown configuration field");
    }
    static String text(Map<String, Object> config, String key) {
        if (!(config.get(key) instanceof String value)) throw new IllegalArgumentException(key + " must be a string");
        return JsonValues.identifier(value);
    }
    static Map<String, Object> object(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected JSON object");
        var result = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Expected string key");
            result.put(key, entry.getValue());
        }
        return result;
    }
}
