package com.nebula.iot.rule;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/** Pure calculation providers only. action-preview never submits external work. */
public final class BuiltinExecutors {
    private BuiltinExecutors() {}
    public static List<TaskExecutorProvider> providers() {
        return List.of(provider("identity", config -> {
            ConditionConfiguration.keys(config, Set.of());
            return (context, data) -> Optional.of(data);
        }), provider("filter", config -> {
            ConditionConfiguration.keys(config, Set.of("condition"));
            var condition = ConditionConfiguration.parse(ConditionConfiguration.object(config.get("condition")));
            return (context, data) -> condition.matches(data) ? Optional.of(data) : Optional.empty();
        }), provider("mapping", config -> {
            ConditionConfiguration.keys(config, Set.of("fields"));
            if (!(config.get("fields") instanceof List<?> raw) || raw.isEmpty())
                throw new IllegalArgumentException("Mapping fields must be a nonempty array");
            var fields = FieldMapping.validate(raw.stream().map(BuiltinExecutors::mapping).toList());
            return (context, data) -> {
                var result = new LinkedHashMap<String, Object>();
                for (var field : fields) result.put(field.target(), field.evaluate(data));
                return Optional.of(new RuleData(result));
            };
        }), provider("action-preview", config -> {
            ConditionConfiguration.keys(config, Set.of("id", "kind", "target", "parameters"));
            new AutomationDefinition.Action(ConditionConfiguration.text(config, "id"),
                    ConditionConfiguration.text(config, "kind"), ConditionConfiguration.text(config, "target"),
                    ConditionConfiguration.object(config.get("parameters")));
            // Keep payload depth unchanged; the sample service associates trusted node configuration separately.
            return (context, data) -> Optional.of(data);
        }));
    }
    private static FieldMapping mapping(Object raw) {
        var config = ConditionConfiguration.object(raw);
        ConditionConfiguration.keys(config, Set.of("target", "source", "value", "multiplier", "offset"));
        String target = ConditionConfiguration.text(config, "target");
        if (config.containsKey("value")) {
            if (config.containsKey("source") || config.containsKey("multiplier") || config.containsKey("offset"))
                throw new IllegalArgumentException("Constant mapping cannot also reference fields");
            return FieldMapping.constant(target, config.get("value"));
        }
        return FieldMapping.field(target, ConditionConfiguration.text(config, "source"), number(config, "multiplier"), number(config, "offset"));
    }
    private static BigDecimal number(Map<String, Object> config, String field) {
        if (!config.containsKey(field)) return null;
        if (!(config.get(field) instanceof Number number)) throw new IllegalArgumentException(field + " must be numeric");
        return new BigDecimal(number.toString());
    }
    private static TaskExecutorProvider provider(String type, Function<Map<String, Object>, TaskExecutor> factory) {
        return new TaskExecutorProvider() {
            public String type() { return type; }
            public void validate(Map<String, Object> configuration) { factory.apply(configuration); }
            public TaskExecutor create(Map<String, Object> configuration) { return factory.apply(configuration); }
        };
    }
}
