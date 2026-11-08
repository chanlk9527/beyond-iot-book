package com.nebula.iot.rule;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/** Dot-separated object paths. Missing values never match, including NE. */
public record Condition(String field, Operator operator, Object expected) implements RuleCondition {
    public enum Operator { EQ, NE, GT, GE, LT, LE }

    public Condition {
        JsonValues.identifier(field);
        for (String part : field.split("\\.", -1)) {
            if (part.isBlank()) throw new IllegalArgumentException("Invalid field path");
        }
        Objects.requireNonNull(operator, "operator");
        expected = JsonValues.snapshot(expected, 0);
        if (expected instanceof Map || expected instanceof java.util.List)
            throw new IllegalArgumentException("Comparison requires scalar value");
        if (operator != Operator.EQ && operator != Operator.NE && !(expected instanceof Number))
            throw new IllegalArgumentException("Ordering requires a numeric value");
    }

    public boolean matches(RuleData data) {
        Object actual = data.values();
        for (String part : field.split("\\.")) {
            if (!(actual instanceof Map<?, ?> map) || !map.containsKey(part)) return false;
            actual = map.get(part);
        }
        if (actual instanceof Number a && expected instanceof Number b) {
            int comparison = new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
            return switch (operator) {
                case EQ -> comparison == 0;
                case NE -> comparison != 0;
                case GT -> comparison > 0;
                case GE -> comparison >= 0;
                case LT -> comparison < 0;
                case LE -> comparison <= 0;
            };
        }
        return switch (operator) {
            case EQ -> Objects.equals(actual, expected);
            case NE -> !Objects.equals(actual, expected);
            default -> false;
        };
    }
}
