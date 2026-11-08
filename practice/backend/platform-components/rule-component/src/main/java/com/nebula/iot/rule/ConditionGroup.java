package com.nebula.iot.rule;

import java.util.List;
import java.util.Objects;

public record ConditionGroup(Operator operator, List<RuleCondition> children) implements RuleCondition {
    public enum Operator { AND, OR }
    public ConditionGroup {
        Objects.requireNonNull(operator, "operator");
        children = List.copyOf(children);
        if (children.isEmpty()) throw new IllegalArgumentException("Condition group must not be empty");
        int count = 1;
        for (var child : children) count += count(child, 2);
        if (count > 256) throw new IllegalArgumentException("Condition tree exceeds 256 items");
    }
    private static int count(RuleCondition condition, int depth) {
        if (depth > 16) throw new IllegalArgumentException("Condition tree exceeds 16 levels");
        int count = 1;
        if (condition instanceof ConditionGroup group)
            for (var child : group.children()) {
                count += count(child, depth + 1);
                if (count > 256) throw new IllegalArgumentException("Condition tree exceeds 256 items");
            }
        return count;
    }
    @Override public boolean matches(RuleData data) {
        return operator == Operator.AND ? children.stream().allMatch(c -> c.matches(data))
                : children.stream().anyMatch(c -> c.matches(data));
    }
}
